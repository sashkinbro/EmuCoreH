/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "crash_handler.h"

#if __ANDROID_API__ < 16 || ANDROID_NDK_VERSION <= 14
namespace tuningfork {
CrashHandler::CrashHandler() {}
CrashHandler::~CrashHandler() {}
void CrashHandler::Init(std::function<bool(void)> callback) {}
}  // namespace tuningfork
#else

#include <pthread.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iomanip>
#include <sstream>
#include <vector>

#include "Log.h"
#define LOG_TAG "TFCrashHandler"
#include "system_utils.h"
#include "tuningfork_utils.h"

namespace tuningfork {

namespace {

const int signals[]{SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV};

const int numSignals = sizeof(signals) / sizeof(signals[0]);

// static
const char *GetSignalName(int signal) {
    switch (signal) {
        case SIGILL:
            return "SIGILL";
        case SIGTRAP:
            return "SIGTRAP";
        case SIGABRT:
            return "SIGABRT";
        case SIGBUS:
            return "SIGBUS";
        case SIGFPE:
            return "SIGFPE";
        case SIGKILL:
            return "SIGKILL";
        case SIGSEGV:
            return "SIGSEGV";
        default:
            return "UNKNOWN SIGNAL";
    }
}

struct sigaction old_handlers[numSignals];
bool handlers_installed = false;

stack_t old_stack;
stack_t new_stack;
bool stack_installed = false;

struct kernel_sigset_t {
    uint64_t sig[1];
};

struct kernel_sigaction {
    union {
        void (*sa_handler_x)(int);
        void (*sa_sigaction_x)(int, siginfo_t *, void *);
    };
    size_t sa_flags;
    void (*sa_restorer)();
    struct kernel_sigset_t sa_mask;
};

void InstallAlternateStackLocked() {
    if (stack_installed) return;

    std::memset(&old_stack, 0, sizeof(old_stack));
    std::memset(&new_stack, 0, sizeof(new_stack));

    static const unsigned kSigStackSize = std::max(16384, SIGSTKSZ);

    if (sigaltstack(nullptr, &old_stack) == -1 || !old_stack.ss_sp ||
        old_stack.ss_size < kSigStackSize) {
        new_stack.ss_sp = std::calloc(1, kSigStackSize);
        new_stack.ss_size = kSigStackSize;

        if (sigaltstack(&new_stack, nullptr) == -1) {
            free(new_stack.ss_sp);
            return;
        }
        stack_installed = true;
    }
}

void RestoreAlternateStackLocked() {
    if (!stack_installed) return;

    stack_t current_stack;
    if (sigaltstack(nullptr, &current_stack) == -1) return;

    if (current_stack.ss_sp == new_stack.ss_sp) {
        if (old_stack.ss_sp) {
            if (sigaltstack(&old_stack, nullptr) == -1) return;
        } else {
            stack_t disable_stack;
            disable_stack.ss_flags = SS_DISABLE;
            if (sigaltstack(&disable_stack, nullptr) == -1) return;
        }
    }

    free(new_stack.ss_sp);
    stack_installed = false;
}

void InstallDefaultHandler(int sig) {
    struct kernel_sigaction sa = {};
    sa.sa_handler_x = SIG_DFL;
    sa.sa_flags = SA_RESTART;
    syscall(__NR_rt_sigaction, sig, &sa, NULL, sizeof(kernel_sigset_t));
}

std::vector<CrashHandler *> *g_handler_stack_ = NULL;
pthread_mutex_t handler_mutex = PTHREAD_MUTEX_INITIALIZER;

}  // namespace

CrashHandler::CrashHandler() {}

void CrashHandler::Init(std::function<bool(void)> callback) {
    if (handler_inited_) return;
    pthread_mutex_lock(&handler_mutex);
    if (!g_handler_stack_) {
        g_handler_stack_ = new std::vector<CrashHandler *>;
    }
    InstallAlternateStackLocked();
    InstallHandlerLocked();
    g_handler_stack_->push_back(this);

    std::stringstream crash_file_path_builder;
    crash_file_path_builder << DefaultTuningForkSaveDirectory();
    file_utils::CheckAndCreateDir(crash_file_path_builder.str());
    crash_file_path_builder << "/crash_info.bin";
    tf_crash_info_file_ = crash_file_path_builder.str();
    ALOGV("Path to crash info file: %s", tf_crash_info_file_.c_str());

    handler_inited_ = true;
    callback_ = callback;
    ALOGI("CrashHandler initialized");
    pthread_mutex_unlock(&handler_mutex);
}

CrashHandler::~CrashHandler() {
    if (!handler_inited_) return;
    pthread_mutex_lock(&handler_mutex);
    std::vector<CrashHandler *>::iterator handler =
        std::find(g_handler_stack_->begin(), g_handler_stack_->end(), this);
    g_handler_stack_->erase(handler);
    if (g_handler_stack_->empty()) {
        delete g_handler_stack_;
        g_handler_stack_ = NULL;
        RestoreAlternateStackLocked();
        RestoreHandlerLocked();
    }
    pthread_mutex_unlock(&handler_mutex);
}
// static
bool CrashHandler::InstallHandlerLocked() {
    if (handlers_installed) return false;

    for (int i = 0; i < numSignals; ++i) {
        if (sigaction(signals[i], NULL, &old_handlers[i]) == -1) {
            ALOGI("%s", "Not able to store old handler");
            return false;
        }
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);

    for (int i = 0; i < numSignals; ++i) {
        sigaddset(&sa.sa_mask, signals[i]);
    }

    sa.sa_sigaction = SignalHandler;
    sa.sa_flags = SA_ONSTACK | SA_SIGINFO;

    for (int i = 0; i < numSignals; ++i) {
        if (sigaction(signals[i], &sa, NULL) == -1) {
            ALOGI("%s", "Not able to store old handler 2");
        }
    }
    handlers_installed = true;
    return true;
}
// static
void CrashHandler::RestoreHandlerLocked() {
    if (!handlers_installed) return;

    for (int i = 0; i < numSignals; ++i) {
        if (sigaction(signals[i], &old_handlers[i], NULL) == -1) {
            InstallDefaultHandler(signals[i]);
        }
    }
    handlers_installed = false;
}
// static
void CrashHandler::SignalHandler(int sig, siginfo_t *info, void *ucontext) {
    pthread_mutex_lock(&handler_mutex);
    struct sigaction cur_handler;
    if (sigaction(sig, NULL, &cur_handler) == 0 &&
        (cur_handler.sa_flags & SA_SIGINFO) == 0) {
        sigemptyset(&cur_handler.sa_mask);
        sigaddset(&cur_handler.sa_mask, sig);

        cur_handler.sa_sigaction = SignalHandler;
        cur_handler.sa_flags = SA_ONSTACK | SA_SIGINFO;

        if (sigaction(sig, &cur_handler, NULL) == -1) {
            InstallDefaultHandler(sig);
        }
        pthread_mutex_unlock(&handler_mutex);
        return;
    }

    bool handled = false;
    for (int i = g_handler_stack_->size() - 1; !handled && i >= 0; --i) {
        handled = (*g_handler_stack_)[i]->HandlerSignal(sig, info, ucontext);
    }

    if (handled)
        InstallDefaultHandler(sig);
    else
        RestoreHandlerLocked();
    pthread_mutex_unlock(&handler_mutex);

    if (info->si_code <= 0 || sig == SIGABRT) {
        if (tgkill(getpid(), syscall(__NR_gettid), sig) < 0) {
            _exit(1);
        }
    }
}

bool CrashHandler::HandlerSignal(int sig, siginfo_t *info, void *ucontext) {
    ALOGI("HandlerSignal: sig %d, name %s, pid %d", sig, GetSignalName(sig),
          info->si_pid);

    std::ofstream file(tf_crash_info_file_);
    if (file.is_open()) {
        file << sig;
    } else {
        ALOGE_ONCE("Crash reason couldn't be stored.");
    }

    if (callback_) {
        callback_();
    }
    // Crash is not handled here, return false to restore other signal handlers.
    return false;
}
}  // namespace tuningfork
#endif
