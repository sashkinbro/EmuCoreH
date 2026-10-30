// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
#include "crash_reporter.h"

// The fatal-signal handler is part of release builds too: a tester build must
// leave the native crash reason in crash.log, not only a logcat tombstone.
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace emucoreh::crash {
namespace {

// ---------------------------------------------------------------------------
// The handler runs while the process is dying, so it may only use raw
// syscalls and hand-rolled formatting: no heap, no stdio, no locks beyond
// the ones created here.
// ---------------------------------------------------------------------------
class Out {
public:
    Out(char* begin, size_t capacity) : begin_(begin), cursor_(begin), end_(begin + capacity) {}

    void ch(char value) {
        if (cursor_ < end_) *cursor_++ = value;
    }

    void str(const char* value) {
        while (*value != '\0') ch(*value++);
    }

    void dec(long long value) {
        if (value < 0) {
            ch('-');
            value = -value;
        }
        char digits[20];
        int count = 0;
        do {
            digits[count++] = static_cast<char>('0' + value % 10);
            value /= 10;
        } while (value != 0);
        while (count > 0) ch(digits[--count]);
    }

    void hex(uint64_t value, int min_digits) {
        char digits[16];
        int count = 0;
        do {
            digits[count++] = "0123456789abcdef"[value & 0xF];
            value >>= 4;
        } while (value != 0);
        while (count < min_digits) digits[count++] = '0';
        while (count > 0) ch(digits[--count]);
    }

    void pad2(long long value) {
        if (value < 10) ch('0');
        dec(value);
    }

    size_t size() const { return static_cast<size_t>(cursor_ - begin_); }

private:
    char* begin_;
    char* cursor_;
    char* end_;
};

void AppendTimestamp(Out& out) {
    struct timespec now;
    struct tm local;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0 || localtime_r(&now.tv_sec, &local) == nullptr) {
        out.str("0000-00-00 00:00:00");
        return;
    }
    out.dec(local.tm_year + 1900);
    out.ch('-');
    out.pad2(local.tm_mon + 1);
    out.ch('-');
    out.pad2(local.tm_mday);
    out.ch(' ');
    out.pad2(local.tm_hour);
    out.ch(':');
    out.pad2(local.tm_min);
    out.ch(':');
    out.pad2(local.tm_sec);
}

// Matches the CrashLogger.kt entry format so the report blends into the log.
void AppendLineStart(Out& out) {
    out.ch('[');
    AppendTimestamp(out);
    out.str("] CRASH: [Native] ");
}

constexpr int kCrashSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
constexpr int kCrashSignalCount = static_cast<int>(sizeof(kCrashSignals) / sizeof(kCrashSignals[0]));

constexpr int kMaxFrames = 64;

struct sigaction g_previous[kCrashSignalCount];
void* g_frames[kMaxFrames];
char g_report[32 * 1024];

std::atomic<int> g_log_fd{-1};
std::atomic<int> g_handling{0};
std::atomic<bool> g_installed{false};
std::mutex g_install_mutex;

const char* SignalName(int signal_number) {
    switch (signal_number) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default: return "SIGNAL";
    }
}

int SignalIndex(int signal_number) {
    for (int i = 0; i < kCrashSignalCount; ++i) {
        if (kCrashSignals[i] == signal_number) return i;
    }
    return -1;
}

struct UnwindState {
    void** frames;
    int count;
    int capacity;
};

_Unwind_Reason_Code UnwindCallback(struct _Unwind_Context* context, void* argument) {
    auto* state = static_cast<UnwindState*>(argument);
    if (state->count >= state->capacity) return _URC_END_OF_STACK;
    const uintptr_t pc = static_cast<uintptr_t>(_Unwind_GetIP(context));
    if (pc != 0) state->frames[state->count++] = reinterpret_cast<void*>(pc);
    return _URC_NO_REASON;
}

// Hands the signal back to whatever was installed before us (on Android that
// is usually the runtime's handler that triggers debuggerd) or restores the
// default disposition so the kernel kills the process like any fatal signal.
[[noreturn]] void ChainToPrevious(int signal_number, siginfo_t* info, void* context) {
    const int index = SignalIndex(signal_number);
    if (index >= 0) {
        const struct sigaction& previous = g_previous[index];
        if ((previous.sa_flags & SA_SIGINFO) != 0) {
            if (previous.sa_sigaction != nullptr) {
                previous.sa_sigaction(signal_number, info, context);
                _exit(128 + signal_number);
            }
        } else if (previous.sa_handler != SIG_DFL && previous.sa_handler != SIG_IGN &&
                   previous.sa_handler != nullptr) {
            previous.sa_handler(signal_number);
            _exit(128 + signal_number);
        }
    }

    struct sigaction default_action {};
    default_action.sa_handler = SIG_DFL;
    sigemptyset(&default_action.sa_mask);
    sigaction(signal_number, &default_action, nullptr);

    sigset_t unblock;
    sigemptyset(&unblock);
    sigaddset(&unblock, signal_number);
    sigprocmask(SIG_UNBLOCK, &unblock, nullptr);

    raise(signal_number);
    kill(getpid(), signal_number);
    _exit(128 + signal_number);
}

void CrashHandler(int signal_number, siginfo_t* info, void* context) {
    const int fd = g_log_fd.load(std::memory_order_relaxed);
    if (fd < 0 || g_handling.exchange(1, std::memory_order_relaxed) != 0) {
        ChainToPrevious(signal_number, info, context);
    }

    Out out(g_report, sizeof(g_report));

    char thread_name[16] = {};
    prctl(PR_GET_NAME, thread_name);
    const long tid = syscall(SYS_gettid);

    UnwindState state{g_frames, 0, kMaxFrames};
    _Unwind_Backtrace(UnwindCallback, &state);

    const bool has_fault_address = signal_number == SIGSEGV || signal_number == SIGBUS ||
                                   signal_number == SIGILL || signal_number == SIGFPE ||
                                   signal_number == SIGTRAP;

    AppendLineStart(out);
    out.str("========== FATAL SIGNAL ==========");
    out.ch('\n');

    AppendLineStart(out);
    out.str("signal=");
    out.str(SignalName(signal_number));
    out.ch('(');
    out.dec(signal_number);
    out.ch(')');
    out.str(" code=");
    out.dec(info != nullptr ? info->si_code : 0);
    if (has_fault_address && info != nullptr) {
        out.str(" fault=0x");
        out.hex(reinterpret_cast<uintptr_t>(info->si_addr), 0);
    }
    out.str(" thread=");
    out.str(thread_name[0] != '\0' ? thread_name : "?");
    out.str(" tid=");
    out.dec(tid);
    out.ch('\n');

    for (int i = 0; i < state.count; ++i) {
        AppendLineStart(out);
        out.ch('#');
        out.pad2(i);
        out.str(" pc=0x");
        out.hex(reinterpret_cast<uintptr_t>(state.frames[i]), 0);

        Dl_info module;
        if (dladdr(state.frames[i], &module) != 0 && module.dli_fname != nullptr) {
            const char* name = module.dli_fname;
            for (const char* c = module.dli_fname; *c != '\0'; ++c) {
                if (*c == '/') name = c + 1;
            }
            out.str(" | ");
            out.str(name);
            out.str(" +0x");
            out.hex(reinterpret_cast<uintptr_t>(state.frames[i]) -
                        reinterpret_cast<uintptr_t>(module.dli_fbase),
                    0);
            if (module.dli_sname != nullptr) {
                out.str(" | ");
                out.str(module.dli_sname);
                out.str(" +0x");
                out.hex(reinterpret_cast<uintptr_t>(state.frames[i]) -
                            reinterpret_cast<uintptr_t>(module.dli_saddr),
                    0);
            }
        }
        out.ch('\n');
    }

    AppendLineStart(out);
    out.str("========== END OF CRASH ==========");
    out.ch('\n');

    const size_t length = out.size();
    size_t written = 0;
    while (written < length) {
        const ssize_t result = write(fd, g_report + written, length - written);
        if (result <= 0) break;
        written += static_cast<size_t>(result);
    }

    ChainToPrevious(signal_number, info, context);
}

}  // namespace

void LogNativeError(const char* message) {
    const int fd = g_log_fd.load(std::memory_order_relaxed);
    if (fd < 0 || message == nullptr || *message == '\0') return;

    char buffer[4096];
    Out out(buffer, sizeof(buffer));
    out.ch('[');
    AppendTimestamp(out);
    out.str("] ERROR: [Native] ");
    out.str(message);
    out.ch('\n');

    const size_t length = out.size();
    size_t written = 0;
    while (written < length) {
        const ssize_t result = write(fd, buffer + written, length - written);
        if (result <= 0) break;
        written += static_cast<size_t>(result);
    }
}

void InstallCrashReporter(const char* log_path) {
    if (log_path == nullptr || *log_path == '\0') return;

    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (g_installed.load(std::memory_order_relaxed)) return;

    const int fd = open(log_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) return;
    g_log_fd.store(fd, std::memory_order_relaxed);

    // Gives the handler a guaranteed stack for overflow-style faults on the
    // installing thread; other threads fall back to their regular stack
    // because SA_ONSTACK only redirects when an alternate stack exists.
    static char alternate_stack[64 * 1024];
    stack_t stack {};
    stack.ss_sp = alternate_stack;
    stack.ss_size = sizeof(alternate_stack);
    stack.ss_flags = 0;
    sigaltstack(&stack, nullptr);

    struct sigaction action {};
    action.sa_sigaction = &CrashHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigfillset(&action.sa_mask);

    for (int i = 0; i < kCrashSignalCount; ++i) {
        sigaction(kCrashSignals[i], &action, &g_previous[i]);
    }

    g_installed.store(true, std::memory_order_relaxed);
}

}  // namespace emucoreh::crash
