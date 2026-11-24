// SPDX-License-Identifier: GPL-3.0-only
// Host (Windows/MSYS2) regression and speed harness for the Beetle Saturn core.
// Links the core statically, boots one disc with the owner BIOS, replays an
// app-style pad script and prints, every N frames, a hash of retro_serialize()
// plus a running hash of every presented frame and of the audio stream. The
// last lines give ms/frame for the whole run and for the gameplay window.
//
// Usage: host_sat <game> <system-dir> <save-dir> <frames> <every>
//                 [options k=v;k=v] [input from-to:mask,...] [profile-out]
// Pad masks use the app layout (pressed=1): Start 8, Up 16, Right 32, Down 64,
// Left 128, Cross 16384, Circle 8192, Square 32768 (see native_bridge.cpp).
// HOST_SAT_DUMP=<dir> writes the frame at each checkpoint as PPM (plus the state when
// HOST_SAT_DUMP_STATES=1).
#include <libretro.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#endif

namespace {
std::string g_system, g_save;
std::map<std::string, std::string> g_options;
uint64_t g_frame_hash = 0x9e3779b97f4a7c15ull, g_audio_hash = 0x9e3779b97f4a7c15ull;
unsigned g_pad = 0;  // app mask, pressed = 1
retro_pixel_format g_format = RETRO_PIXEL_FORMAT_0RGB1555;
std::vector<uint32_t> g_last;  // last XRGB8888 frame, kept only when HOST_SAT_DUMP is set
unsigned g_last_w = 0, g_last_h = 0;
bool g_keep_last = false;

inline uint64_t Mix(uint64_t h, uint64_t v) {
    h ^= v;
    h *= 0xff51afd7ed558ccdull;
    return h ^ (h >> 29);
}
uint64_t HashBytes(uint64_t h, const void* data, size_t size) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    while (size >= 8) { uint64_t v; std::memcpy(&v, p, 8); h = Mix(h, v); p += 8; size -= 8; }
    uint64_t tail = 0;
    std::memcpy(&tail, p, size);
    return Mix(h, tail ^ (uint64_t(size) << 56));
}

void Log(enum retro_log_level level, const char* fmt, ...) {
    if (level < RETRO_LOG_WARN && !getenv("HOST_SAT_VERBOSE")) return;
    va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap);
}

bool Environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *static_cast<const char**>(data) = g_system.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *static_cast<const char**>(data) = g_save.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: static_cast<retro_log_callback*>(data)->log = Log; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: g_format = *static_cast<retro_pixel_format*>(data); return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<retro_variable*>(data);
            auto it = g_options.find(var->key ? var->key : "");
            var->value = it == g_options.end() ? nullptr : it->second.c_str();
            return var->value != nullptr;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *static_cast<bool*>(data) = false; return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *static_cast<unsigned*>(data) = 0; return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            return true;
        default: return false;
    }
}

void Video(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data) { g_frame_hash = Mix(g_frame_hash, 0xdeadull); return; }
    const size_t bpp = g_format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
    g_frame_hash = Mix(g_frame_hash, (uint64_t(w) << 32) | h);
    if (g_keep_last && bpp == 4) {
        g_last_w = w; g_last_h = h; g_last.resize(size_t(w) * h);
        for (unsigned y = 0; y < h; y++) std::memcpy(&g_last[size_t(y) * w], static_cast<const uint8_t*>(data) + y * pitch, w * 4);
    }
    for (unsigned y = 0; y < h; y++)
        g_frame_hash = HashBytes(g_frame_hash, static_cast<const uint8_t*>(data) + y * pitch, w * bpp);
}
void AudioSample(int16_t l, int16_t r) { g_audio_hash = Mix(g_audio_hash, (uint16_t(l) << 16) | uint16_t(r)); }
size_t AudioBatch(const int16_t* data, size_t frames) { g_audio_hash = HashBytes(g_audio_hash, data, frames * 4); return frames; }
void InputPoll() {}
int16_t InputState(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    auto pressed = [](int bit) { return (g_pad & (1u << bit)) != 0; };
    uint16_t active = 0;
    if (pressed(4)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_UP;
    if (pressed(6)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_DOWN;
    if (pressed(7)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_LEFT;
    if (pressed(5)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_RIGHT;
    if (pressed(12)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_X;
    if (pressed(13)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_R;
    if (pressed(14)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_A;
    if (pressed(15)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_B;
    if (pressed(10)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_Y;
    if (pressed(11)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_L;
    if (pressed(8)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_L2;
    if (pressed(9)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_R2;
    if (pressed(3)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_START;
    if (pressed(0)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_SELECT;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(active);
    return id < 16 && (active & (1u << id)) ? 1 : 0;
}

struct Range { int from, to; unsigned mask; };
std::vector<Range> ParseInput(const std::string& spec) {
    std::vector<Range> out;
    size_t pos = 0;
    while (pos < spec.size()) {
        size_t end = spec.find(',', pos);
        if (end == std::string::npos) end = spec.size();
        std::string entry = spec.substr(pos, end - pos);
        int from = 0, to = 0; unsigned mask = 0;
        if (std::sscanf(entry.c_str(), "%d-%d:%u", &from, &to, &mask) == 3) out.push_back({from, to, mask});
        pos = end + 1;
    }
    return out;
}

#ifdef _WIN32
// Minimal sampling profiler: suspends the emulation thread every ~1 ms and
// records its instruction pointer. Addresses are written relative to the
// image base so tools/oracle/host_sat.sh can symbolize them with addr2line.
std::atomic<bool> g_prof_run{false};
std::map<uint64_t, uint64_t> g_samples;
void Profiler(HANDLE thread) {
    timeBeginPeriod(1);
    while (g_prof_run.load()) {
        Sleep(1);
        if (SuspendThread(thread) == (DWORD)-1) continue;
        CONTEXT ctx; ctx.ContextFlags = CONTEXT_CONTROL;
        if (GetThreadContext(thread, &ctx)) g_samples[ctx.Rip]++;
        ResumeThread(thread);
    }
    timeEndPeriod(1);
}
#endif
}  // namespace

int main(int argc, char** argv) {
    if (argc < 6) {
        std::fprintf(stderr, "usage: %s game system save frames every [options] [input] [profile-out]\n", argv[0]);
        return 2;
    }
    const std::string game = argv[1];
    g_system = argv[2];
    g_save = argv[3];
    const int frames = std::atoi(argv[4]);
    const int every = std::max(1, std::atoi(argv[5]));
    if (argc > 6) {
        std::string spec = argv[6];
        size_t pos = 0;
        while (pos < spec.size()) {
            size_t end = spec.find(';', pos);
            if (end == std::string::npos) end = spec.size();
            std::string kv = spec.substr(pos, end - pos);
            size_t eq = kv.find('=');
            if (eq != std::string::npos) g_options[kv.substr(0, eq)] = kv.substr(eq + 1);
            pos = end + 1;
        }
    }
    const auto input = argc > 7 ? ParseInput(argv[7]) : std::vector<Range>{};
    const char* profile_out = argc > 8 && argv[8][0] ? argv[8] : nullptr;
    const int gameplay_from = getenv("HOST_SAT_GAMEPLAY_FROM") ? std::atoi(getenv("HOST_SAT_GAMEPLAY_FROM")) : 1000;

    retro_set_environment(Environment);
    retro_set_video_refresh(Video);
    retro_set_audio_sample(AudioSample);
    retro_set_audio_sample_batch(AudioBatch);
    retro_set_input_poll(InputPoll);
    retro_set_input_state(InputState);
    retro_init();
    retro_game_info info{};
    info.path = game.c_str();
    if (!retro_load_game(&info)) { std::fprintf(stderr, "load failed\n"); return 1; }
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);

#ifdef _WIN32
    std::thread prof;
    if (profile_out) {
        HANDLE self;
        DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &self, 0, FALSE, DUPLICATE_SAME_ACCESS);
        g_prof_run = true;
        prof = std::thread(Profiler, self);
    }
#endif

    const char* dump_dir = getenv("HOST_SAT_DUMP");  // writes <dir>/f<frame>.ppm at checkpoints
    g_keep_last = dump_dir && dump_dir[0];
    std::vector<double> times;
    times.reserve(frames);
    // Emulation-thread CPU cycles: unlike wall time, not inflated when other processes preempt us.
    unsigned long long cycles_total = 0;
    std::vector<uint8_t> state;
    for (int frame = 1; frame <= frames; frame++) {
        unsigned mask = 0;
        for (const auto& r : input) if (frame >= r.from && frame <= r.to) { mask = r.mask; break; }
        g_pad = mask;
        const auto t0 = std::chrono::steady_clock::now();
#ifdef _WIN32
        ULONG64 c0 = 0, c1 = 0;
        QueryThreadCycleTime(GetCurrentThread(), &c0);
#endif
        retro_run();
#ifdef _WIN32
        QueryThreadCycleTime(GetCurrentThread(), &c1);
        cycles_total += c1 - c0;
#endif
        times.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
        if (frame % every == 0 || frame == frames) {
            state.resize(retro_serialize_size());
            const bool ok = retro_serialize(state.data(), state.size());
            std::printf("f%d state=%016llx frames=%016llx audio=%016llx%s\n", frame,
                (unsigned long long)HashBytes(0x1234, state.data(), state.size()),
                (unsigned long long)g_frame_hash, (unsigned long long)g_audio_hash, ok ? "" : " SERIALIZE-FAILED");
            if (dump_dir && dump_dir[0] && getenv("HOST_SAT_DUMP_STATES")) {
                std::string path = std::string(dump_dir) + "/f" + std::to_string(frame) + ".state";
                if (FILE* f = std::fopen(path.c_str(), "wb")) { std::fwrite(state.data(), 1, state.size(), f); std::fclose(f); }
            }
            if (g_keep_last && !g_last.empty()) {
                std::string path = std::string(dump_dir) + "/f" + std::to_string(frame) + ".ppm";
                if (FILE* f = std::fopen(path.c_str(), "wb")) {
                    std::fprintf(f, "P6\n%u %u\n255\n", g_last_w, g_last_h);
                    for (uint32_t px : g_last) { uint8_t rgb[3] = {uint8_t(px >> 16), uint8_t(px >> 8), uint8_t(px)}; std::fwrite(rgb, 1, 3, f); }
                    std::fclose(f);
                }
            }
        }
    }
#ifdef _WIN32
    if (profile_out) {
        g_prof_run = false;
        prof.join();
        const uint64_t base = reinterpret_cast<uint64_t>(GetModuleHandleW(nullptr));
        if (FILE* f = std::fopen(profile_out, "w")) {
            for (auto& [addr, count] : g_samples) std::fprintf(f, "%llu 0x%llx\n", (unsigned long long)count, (unsigned long long)(addr - base));
            std::fclose(f);
        }
    }
#endif
    auto summarize = [&](int from) {
        double sum = 0, peak = 0; int n = 0;
        std::vector<double> v;
        for (int i = from; i < (int)times.size(); i++) { sum += times[i]; peak = std::max(peak, times[i]); v.push_back(times[i]); n++; }
        std::sort(v.begin(), v.end());
        double p99 = v.empty() ? 0 : v[std::min(v.size() - 1, size_t(v.size() * 0.99))];
        std::printf("%s frames=%d avg_ms=%.4f p99_ms=%.4f peak_ms=%.4f\n", from ? "TIME_GAMEPLAY" : "TIME_ALL", n, n ? sum / n : 0, p99, peak);
    };
    summarize(0);
    std::printf("CYCLES_ALL frames=%d mcycles_per_frame=%.4f\n", frames, cycles_total / 1e6 / std::max(1, frames));
    if (gameplay_from < frames) summarize(gameplay_from);
    retro_unload_game();
    retro_deinit();
    return 0;
}
