// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
//
// EmuCoreH libretro frontend for the vendored Flycast core.
//
// The core (ppsspp_libretro_android.so, built by the :core-android module
// from /core) is loaded with dlopen and driven through the libretro C API.
// This file owns:
//   * environment negotiation (directories, options, logging, AV info),
//   * the OpenGL ES hardware render context and framebuffer presentation,
//   * PCM capture from retro_audio_sample_batch + AAudio output,
//   * controller state forwarding into retro_input_state,
//   * save-state (retro_serialize) file IO.
#include <jni.h>
#include "audio_resampler.h"
#include "storage_vfs.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "libretro.h"
#include "shader_chain.h"
#include "shader_chain_present.h"
#include "shader_effect.h"
#include "vulkan_frontend.h"

namespace vulkan = emucoreh::vulkan;

extern "C" void EmuCoreHAchievementsSetJavaVm(JavaVM* vm);
extern "C" void EmuCoreHAchievementsInitializeJava(JNIEnv* env);
extern "C" void EmuCoreHAchievementsOnFrame();
extern "C" void EmuCoreHAchievementsOnSessionEnd();
extern "C" void EmuCoreHAchievementsShutdown();

#define LOG_TAG "EmuCoreH"
#define CORE_LOG_TAG "EmuCoreH-Core"
#ifdef NDEBUG
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace core_renderer {
[[maybe_unused]] constexpr int kSoftware = 0;
constexpr int kVulkan = 1;
constexpr int kOpenGl = 2;
}  // namespace core_renderer

namespace {

constexpr size_t kAudioRingCapacityFrames = 48000;  // ~1s at 48 kHz stereo.
constexpr int32_t kAudioDeclickFrames = 48;
// Safety valve: never hold the emulation on the audio ring for longer than this.
constexpr int32_t kAudioBackPressureTimeoutMs = 250;

// ---------------------------------------------------------------------------
// Core loader.
// ---------------------------------------------------------------------------
struct CoreApi {
    void* handle = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    unsigned (*api_version)() = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*reset)() = nullptr;
    void (*run)() = nullptr;
    size_t (*serialize_size)() = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    void (*cheat_reset)() = nullptr;
    void (*cheat_set)(unsigned, bool, const char*) = nullptr;
    void (*cheat_reload)() = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)() = nullptr;
    bool (*rewind_step)() = nullptr;
    bool (*disc_achievement_hash)(const char*, char*) = nullptr;
    int (*game_asset)(const char*, int, uint8_t*, size_t) = nullptr;
    int (*game_asset_fd)(int, int, uint8_t*, size_t) = nullptr;
    const char* (*boot_error)() = nullptr;
};

CoreApi g_core;
std::mutex g_core_load_mutex;

// Disc product code reported by the core once the bootstrap has been parsed,
// for example "MK-51035". Read by the frontend to fill library serials that
// the content scanner cannot read from container formats such as CHD.
std::mutex g_game_serial_mutex;
std::string g_game_serial;

// ---------------------------------------------------------------------------
// Frontend state. The libretro core is a process singleton, so this state is
// global and only accessed from the emulation thread plus short JNI calls.
// ---------------------------------------------------------------------------
struct CoreOption {
    std::string default_value;
    std::vector<std::string> values;
};

struct FrontendState {
    std::mutex mutex;
    // Serialises retro_init/retro_deinit/retro_load_game. Never taken by the
    // environment callback, so core-initiated environment queries cannot
    // deadlock against a caller that owns [mutex].
    std::mutex core_mutex;

    std::string system_dir;
    std::string save_dir;
    // Save directory used when no data root override is configured.
    std::string default_save_dir;
    std::string core_assets_dir;
    std::string native_library_dir;

    // Presentation surface.
    ANativeWindow* window = nullptr;
    int requested_renderer = core_renderer::kOpenGl;
    int current_window_width = 0;
    int current_window_height = 0;
    uint32_t window_generation = 0;

    // Core option overrides plus the defaults registered by the core.
    std::unordered_map<std::string, std::string> options;
    std::unordered_map<std::string, CoreOption> registered_options;
    std::atomic<bool> options_dirty{false};
    retro_core_options_update_display_callback update_display_callback{};

    // Last frame geometry reported by the core.
    unsigned frame_width = 0;
    unsigned frame_height = 0;
    // Aspect ratio reported by the core (widescreen setting, video mode, ...).
    // It drives the Auto display mode on every renderer.
    std::atomic<double> aspect_ratio{16.0 / 9.0};
    std::atomic<bool> av_info_refresh_pending{true};
    // 0 = normal, 1 = fast forward, 2 = rewind.
    std::atomic<int> time_control{0};
    std::atomic<int> display_aspect_mode{1};

    int pixel_format = RETRO_PIXEL_FORMAT_XRGB8888;

    // Audio ring buffer (interleaved stereo int16 frames).
    std::mutex audio_mutex;
    std::vector<int16_t> audio_ring;
    size_t audio_read_frame = 0;
    size_t audio_write_frame = 0;
    uint64_t audio_generation = 0;
    std::atomic<double> audio_playback_rate{1.0};
    std::atomic<int32_t> audio_declick_frames{0};

    // AAudio output configuration, applied when the next stream is opened.
    std::atomic<int> audio_output_latency_ms{30};
    std::atomic<bool> audio_low_latency{false};
    std::atomic<float> audio_gain{1.0f};
    // Audio push back pressure: while the ring holds more frames than this
    // the core's audio push blocks, exactly like standalone Flycast. Zero
    // disables the limiter (output closed, paused or unavailable).
    std::atomic<int32_t> audio_pacing_high_water{0};

    std::atomic<int> frame_skip{0};

    std::atomic<int> crop_left{0};
    std::atomic<int> crop_top{0};
    std::atomic<int> crop_right{0};
    std::atomic<int> crop_bottom{0};

    // Input: active-high bitmask per port plus analog axes.
    std::atomic<uint16_t> pad_buttons[2]{{0xFFFF}, {0xFFFF}};
    std::atomic<int16_t> pad_analog[2][4]{};
    std::atomic<bool> pad_analog_mode[2]{{false}, {false}};

    std::atomic<bool> core_initialized{false};
    std::atomic<bool> game_loaded{false};
    std::atomic<bool> shutdown_requested{false};

    std::atomic<uint8_t> rumble_strong[2]{{0}, {0}};
    std::atomic<uint8_t> rumble_weak[2]{{0}, {0}};
};

FrontendState g_frontend;

// ---------------------------------------------------------------------------
// OpenGL ES hardware renderer state. Created and used exclusively on the frame
// worker thread, because EGL contexts are thread-affine.
// ---------------------------------------------------------------------------
struct GlRenderState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    retro_hw_render_callback hw{};
    bool hw_registered = false;
    bool pending = false;
    bool ready = false;
    bool failed = false;
    uint32_t window_generation = 0;

    GLuint fbo = 0;
    GLuint fbo_texture = 0;
    GLuint fbo_depth = 0;
    GLsizei fbo_width = 0;
    GLsizei fbo_height = 0;
    GLsizei fbo_depth_requested = 0;
};

GlRenderState g_gl;

std::atomic<int> g_present_diag_count{0};

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool LoadCoreLocked() {
    if (g_core.handle != nullptr) return true;
    std::lock_guard<std::mutex> load_lock(g_core_load_mutex);
    if (g_core.handle != nullptr) return true;

    void* handle = dlopen("flycast_libretro.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        std::string library_dir;
        {
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            library_dir = g_frontend.native_library_dir;
        }
        if (!library_dir.empty()) {
            const std::string path = library_dir + "/flycast_libretro.so";
            handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        }
    }
    if (handle == nullptr) {
        LOGE("Unable to load Flycast libretro core: %s", dlerror());
        return false;
    }

    auto resolve = [handle](const char* name) { return dlsym(handle, name); };
    g_core.set_environment = reinterpret_cast<void (*)(retro_environment_t)>(resolve("retro_set_environment"));
    g_core.set_video_refresh = reinterpret_cast<void (*)(retro_video_refresh_t)>(resolve("retro_set_video_refresh"));
    g_core.set_audio_sample = reinterpret_cast<void (*)(retro_audio_sample_t)>(resolve("retro_set_audio_sample"));
    g_core.set_audio_sample_batch = reinterpret_cast<void (*)(retro_audio_sample_batch_t)>(resolve("retro_set_audio_sample_batch"));
    g_core.set_input_poll = reinterpret_cast<void (*)(retro_input_poll_t)>(resolve("retro_set_input_poll"));
    g_core.set_input_state = reinterpret_cast<void (*)(retro_input_state_t)>(resolve("retro_set_input_state"));
    g_core.init = reinterpret_cast<void (*)()>(resolve("retro_init"));
    g_core.deinit = reinterpret_cast<void (*)()>(resolve("retro_deinit"));
    g_core.api_version = reinterpret_cast<unsigned (*)()>(resolve("retro_api_version"));
    g_core.get_system_info = reinterpret_cast<void (*)(retro_system_info*)>(resolve("retro_get_system_info"));
    g_core.get_system_av_info = reinterpret_cast<void (*)(retro_system_av_info*)>(resolve("retro_get_system_av_info"));
    g_core.get_memory_data = reinterpret_cast<void* (*)(unsigned)>(resolve("retro_get_memory_data"));
    g_core.disc_achievement_hash = reinterpret_cast<bool (*)(const char*, char*)>(resolve("emucorea_disc_achievement_hash"));
    g_core.game_asset = reinterpret_cast<int (*)(const char*, int, uint8_t*, size_t)>(resolve("emucorea_game_asset"));
    g_core.game_asset_fd = reinterpret_cast<int (*)(int, int, uint8_t*, size_t)>(resolve("emucorea_game_asset_fd"));
    g_core.boot_error = reinterpret_cast<const char* (*)()>(resolve("emucorea_boot_error"));
    g_core.get_memory_size = reinterpret_cast<size_t (*)(unsigned)>(resolve("retro_get_memory_size"));
    g_core.set_controller_port_device = reinterpret_cast<void (*)(unsigned, unsigned)>(resolve("retro_set_controller_port_device"));
    g_core.reset = reinterpret_cast<void (*)()>(resolve("retro_reset"));
    g_core.run = reinterpret_cast<void (*)()>(resolve("retro_run"));
    g_core.serialize_size = reinterpret_cast<size_t (*)()>(resolve("retro_serialize_size"));
    g_core.serialize = reinterpret_cast<bool (*)(void*, size_t)>(resolve("retro_serialize"));
    g_core.unserialize = reinterpret_cast<bool (*)(const void*, size_t)>(resolve("retro_unserialize"));
    g_core.cheat_reset = reinterpret_cast<void (*)()>(resolve("retro_cheat_reset"));
    g_core.cheat_set = reinterpret_cast<void (*)(unsigned, bool, const char*)>(resolve("retro_cheat_set"));
    g_core.cheat_reload = reinterpret_cast<void (*)()>(resolve("emucorea_cheat_reload"));
    g_core.load_game = reinterpret_cast<bool (*)(const retro_game_info*)>(resolve("retro_load_game"));
    g_core.unload_game = reinterpret_cast<void (*)()>(resolve("retro_unload_game"));
    g_core.rewind_step = reinterpret_cast<bool (*)()>(resolve("emucorea_rewind_step"));

    if (g_core.set_environment == nullptr || g_core.init == nullptr || g_core.load_game == nullptr ||
        g_core.run == nullptr || g_core.get_system_info == nullptr) {
        LOGE("Flycast libretro core is missing required symbols");
        dlclose(handle);
        return false;
    }

    g_core.handle = handle;
    LOGI("Flycast libretro core loaded");
    return true;
}

void AudioRingEnsureCapacity(size_t additional_frames);
void RetroLogCallback(enum retro_log_level level, const char* fmt, ...);
bool EnvironmentCallback(unsigned cmd, void* data);
void RetroVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch);
size_t RetroAudioSampleBatchWrapper(const int16_t* data, size_t frames);
void RetroAudioSample(int16_t left, int16_t right);
void RetroInputPoll();
int16_t RetroInputState(unsigned port, unsigned device, unsigned index, unsigned id);
bool RetroSetRumbleState(unsigned port, retro_rumble_effect effect, uint16_t strength);
bool EnsureHardwareContext();
void DestroyHardwareRendererContext();

// ---------------------------------------------------------------------------
// Environment callback.
// ---------------------------------------------------------------------------
bool HandleHardwareRender(retro_hw_render_callback* callback) {
    if (callback == nullptr) return false;
    if (callback->context_type == RETRO_HW_CONTEXT_VULKAN) {
        return vulkan::AcceptHardwareRender(callback);
    }
    if (callback->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
        callback->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
        callback->context_type != RETRO_HW_CONTEXT_OPENGL) {
        LOGW("Rejecting hardware render context type %d", static_cast<int>(callback->context_type));
        return false;
    }

    callback->get_proc_address = [](const char* sym) -> retro_proc_address_t {
        return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(sym));
    };
    callback->get_current_framebuffer = []() -> uintptr_t {
        return static_cast<uintptr_t>(g_gl.fbo);
    };

    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    g_gl.hw = *callback;
    g_gl.hw_registered = true;
    g_gl.hw.context_type = callback->context_type;
    g_gl.pending = true;
    g_gl.ready = false;
    g_gl.failed = false;
    LOGI("Hardware render accepted (context type %d)", static_cast<int>(callback->context_type));
    return true;
}

void RegisterCoreOptionsV2(const retro_core_options_v2* v2) {
    if (v2 == nullptr || v2->definitions == nullptr) return;
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    for (unsigned i = 0; v2->definitions[i].key != nullptr; i++) {
        const retro_core_option_v2_definition& definition = v2->definitions[i];
        CoreOption option;
        option.default_value = definition.default_value != nullptr ? definition.default_value : "";
        for (unsigned v = 0; definition.values[v].value != nullptr; v++) {
            option.values.emplace_back(definition.values[v].value);
        }
        g_frontend.registered_options[definition.key] = std::move(option);
    }
    LOGI("Registered %zu Flycast core options", g_frontend.registered_options.size());
}

bool EnvironmentCallback(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto format = static_cast<const retro_pixel_format*>(data);
            if (format == nullptr) return false;
            if (*format != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            g_frontend.pixel_format = *format;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto variable = static_cast<retro_variable*>(data);
            if (variable == nullptr || variable->key == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            auto override_it = g_frontend.options.find(variable->key);
            if (override_it != g_frontend.options.end()) {
                variable->value = override_it->second.c_str();
                return true;
            }
            auto default_it = g_frontend.registered_options.find(variable->key);
            if (default_it != g_frontend.registered_options.end()) {
                variable->value = default_it->second.default_value.c_str();
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            auto variable = static_cast<const retro_variable*>(data);
            if (variable == nullptr || variable->key == nullptr || variable->value == nullptr) return false;
            {
                std::lock_guard<std::mutex> lock(g_frontend.mutex);
                g_frontend.options[variable->key] = variable->value;
            }
            g_frontend.options_dirty.store(true);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            auto updated = static_cast<bool*>(data);
            if (updated == nullptr) return false;
            *updated = g_frontend.options_dirty.exchange(false);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            auto version = static_cast<unsigned*>(data);
            if (version == nullptr) return false;
            *version = 2;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            RegisterCoreOptionsV2(static_cast<const retro_core_options_v2*>(data));
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            auto intl = static_cast<const retro_core_options_v2_intl*>(data);
            if (intl == nullptr) return false;
            RegisterCoreOptionsV2(intl->us);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK: {
            auto callback = static_cast<const retro_core_options_update_display_callback*>(data);
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            g_frontend.update_display_callback = callback != nullptr ? *callback
                                                                     : retro_core_options_update_display_callback{};
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            auto directory = static_cast<const char**>(data);
            if (directory == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *directory = g_frontend.system_dir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            auto directory = static_cast<const char**>(data);
            if (directory == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *directory = g_frontend.save_dir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: {
            auto directory = static_cast<const char**>(data);
            if (directory == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *directory = g_frontend.core_assets_dir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto callback = static_cast<retro_log_callback*>(data);
            if (callback == nullptr) return false;
            callback->log = RetroLogCallback;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
            auto* rumble = static_cast<retro_rumble_interface*>(data);
            if (rumble == nullptr) return false;
            rumble->set_rumble_state = RetroSetRumbleState;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: {
            auto version = static_cast<unsigned*>(data);
            if (version == nullptr) return false;
            *version = 1;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            auto language = static_cast<retro_language*>(data);
            if (language == nullptr) return false;
            *language = RETRO_LANGUAGE_ENGLISH;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_USERNAME: {
            auto username = static_cast<const char**>(data);
            if (username == nullptr) return false;
            *username = "EmuCoreH";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING: {
            auto fast_forwarding = static_cast<bool*>(data);
            if (fast_forwarding == nullptr) return false;
            *fast_forwarding = g_frontend.time_control.load() == 1;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_JIT_CAPABLE: {
            auto jit_capable = static_cast<bool*>(data);
            if (jit_capable == nullptr) return false;
            *jit_capable = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            auto preferred = static_cast<retro_hw_context_type*>(data);
            if (preferred == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *preferred = g_frontend.requested_renderer == core_renderer::kVulkan
                ? RETRO_HW_CONTEXT_VULKAN : RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE:
            return data != nullptr && vulkan::AcceptNegotiationInterface(
                static_cast<const retro_hw_render_context_negotiation_interface*>(data));
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            return data != nullptr && vulkan::FillHardwareRenderInterface(
                reinterpret_cast<retro_hw_render_interface**>(data));
        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
            if (data == nullptr) return false;
            auto* negotiation = static_cast<retro_hw_render_context_negotiation_interface*>(data);
            negotiation->interface_version =
                negotiation->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN
                    ? RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN_VERSION : 0;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER:
            return HandleHardwareRender(static_cast<retro_hw_render_callback*>(data));
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            g_frontend.av_info_refresh_pending.store(true);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE:
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            if (cmd == RETRO_ENVIRONMENT_SET_MESSAGE) {
                auto message = static_cast<const retro_message*>(data);
                if (message != nullptr && message->msg != nullptr) LOGI("Core message: %s", message->msg);
            } else {
                auto message = static_cast<const retro_message_ext*>(data);
                if (message != nullptr && message->msg != nullptr) LOGI("Core message: %s", message->msg);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SHUTDOWN:
            LOGW("Core requested shutdown");
            g_frontend.shutdown_requested.store(true);
            return true;
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return GetStorageVfs(static_cast<retro_vfs_interface_info *>(data));
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
        default:
            return false;
    }
}

void RetroLogCallback(enum retro_log_level level, const char* fmt, ...) {
    if (fmt == nullptr) return;
    char message[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);

    // The core reports the disc product code as "Game ID is [MK-51035]" once
    // the bootstrap is parsed. Keep it for the library's learned serials.
    static constexpr char kGameIdMarker[] = "Game ID is [";
    const char* marker = strstr(message, kGameIdMarker);
    if (marker != nullptr) {
        const char* value = marker + sizeof(kGameIdMarker) - 1;
        const char* end = strchr(value, ']');
        if (end != nullptr && end > value) {
            std::lock_guard<std::mutex> lock(g_game_serial_mutex);
            g_game_serial.assign(value, static_cast<size_t>(end - value));
        }
    }

#ifdef NDEBUG
    (void)level;
#else
    // Flycast runs its LogManager at debug verbosity and traces every MMIO
    // access and dynarec bookkeeping entry. Forwarding that to logcat floods
    // the ring buffer and evicts the frontend's own logs, so debug output
    // stays out of logcat and only notices, warnings and errors are kept.
    if (level == RETRO_LOG_DEBUG) return;
    int priority = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_WARN: priority = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_write(priority, CORE_LOG_TAG, message);
#endif
}

}  // namespace

// ---------------------------------------------------------------------------
// Audio ring + AAudio output.
// ---------------------------------------------------------------------------
namespace {

struct AudioOutput {
    AAudioStream* stream = nullptr;
    emucoreh::AudioResampler resampler;
    uint64_t audio_generation = 0;
    int32_t sample_rate = 44100;
    int32_t device_buffer_frames = 0;
    int32_t pacing_high_water_frames = 0;
    std::atomic<bool> started{false};
    std::atomic<int> state{0};
    std::atomic<int> last_error{0};
    std::atomic<int64_t> callback_frames{0};
    std::atomic<int64_t> silence_frames{0};
    std::atomic<int64_t> queued_frames{0};
    std::mutex mutex;
};

void AudioRingEnsureCapacity(size_t additional_frames) {
    const size_t capacity = kAudioRingCapacityFrames;
    size_t occupied = (g_frontend.audio_write_frame + capacity - g_frontend.audio_read_frame) % capacity;
    if (occupied + additional_frames < capacity) return;
    const size_t drop = occupied + additional_frames - (capacity - 1);
    g_frontend.audio_read_frame = (g_frontend.audio_read_frame + drop) % capacity;
    g_frontend.audio_declick_frames.store(kAudioDeclickFrames);
}

// Standalone Flycast blocks inside AudioBackend::push() while the output ring
// is full, which is what pins the console to the sound card's rate there. The
// libretro core cannot block in its own frontend callback, so the same back
// pressure is applied here: the push waits for the ring to fall back to the
// pacing watermark before accepting more samples. The bound keeps a stalled
// output from freezing the emulation.
void BlockOnAudioRingHighWater() {
    // Fast forward and rewind must run unthrottled; their audio is allowed to
    // overrun the ring (the oldest frames are dropped) like standalone does
    // by muting the AICA during fast forward.
    if (g_frontend.time_control.load() != 0) return;
    const int32_t highWater = g_frontend.audio_pacing_high_water.load();
    if (highWater <= 0) return;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kAudioBackPressureTimeoutMs);
    for (;;) {
        size_t queued;
        {
            std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
            const size_t capacity = kAudioRingCapacityFrames;
            queued = (g_frontend.audio_write_frame + capacity - g_frontend.audio_read_frame) % capacity;
        }
        if (queued <= static_cast<size_t>(highWater)) return;
        if (std::chrono::steady_clock::now() >= deadline) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

void RetroAudioSampleBatch(const int16_t* data, size_t frames) {
    if (data == nullptr || frames == 0) return;
    if (g_frontend.time_control.load() == 2) return;
    BlockOnAudioRingHighWater();
    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    AudioRingEnsureCapacity(frames);
    const size_t capacity = kAudioRingCapacityFrames;
    for (size_t i = 0; i < frames; i++) {
        const size_t slot = g_frontend.audio_write_frame;
        g_frontend.audio_ring[slot * 2 + 0] = data[i * 2 + 0];
        g_frontend.audio_ring[slot * 2 + 1] = data[i * 2 + 1];
        g_frontend.audio_write_frame = (slot + 1) % capacity;
    }
}

size_t RetroAudioSampleBatchWrapper(const int16_t* data, size_t frames) {
    RetroAudioSampleBatch(data, frames);
    return frames;
}

void RetroAudioSample(int16_t left, int16_t right) {
    const int16_t frame[2] = {left, right};
    RetroAudioSampleBatch(frame, 1);
}

void AudioDeclickTail(int16_t* samples, size_t frames) {
    const size_t ramp = frames < static_cast<size_t>(kAudioDeclickFrames) ? frames : kAudioDeclickFrames;
    for (size_t i = 0; i < ramp; i++) {
        const float gain = 1.0f - static_cast<float>(i + 1) / static_cast<float>(ramp);
        samples[(frames - 1 - i) * 2 + 0] = static_cast<int16_t>(samples[(frames - 1 - i) * 2 + 0] * gain);
        samples[(frames - 1 - i) * 2 + 1] = static_cast<int16_t>(samples[(frames - 1 - i) * 2 + 1] * gain);
    }
}

aaudio_data_callback_result_t AudioDataCallback(AAudioStream*, void* user_data, void* audio_data, int32_t num_frames) {
    auto* output = static_cast<AudioOutput*>(user_data);
    auto* out = static_cast<int16_t*>(audio_data);
    if (output == nullptr || out == nullptr) return AAUDIO_CALLBACK_RESULT_CONTINUE;

    const size_t capacity = kAudioRingCapacityFrames;
    const float gain = g_frontend.audio_gain.load();
    int32_t declick = g_frontend.audio_declick_frames.exchange(0);
    size_t to_read = 0;

    {
        std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
        if (output->audio_generation != g_frontend.audio_generation) {
            output->resampler.Reset();
            output->audio_generation = g_frontend.audio_generation;
        }
        to_read = output->resampler.Read(g_frontend.audio_ring.data(), capacity,
            g_frontend.audio_read_frame, g_frontend.audio_write_frame,
            static_cast<size_t>(output->pacing_high_water_frames),
            out, static_cast<size_t>(num_frames), g_frontend.audio_playback_rate.load());
        for (size_t i = 0; i < to_read; i++) {
            float frame_gain = gain;
            if (declick > 0) {
                frame_gain *= 1.0f - static_cast<float>(declick) / static_cast<float>(kAudioDeclickFrames);
                declick--;
            }
            out[i * 2 + 0] = static_cast<int16_t>(out[i * 2 + 0] * frame_gain);
            out[i * 2 + 1] = static_cast<int16_t>(out[i * 2 + 1] * frame_gain);
        }
    }

    if (to_read < static_cast<size_t>(num_frames)) {
        if (to_read > 0) AudioDeclickTail(out, to_read);
        std::memset(out + to_read * 2, 0, (static_cast<size_t>(num_frames) - to_read) * 2 * sizeof(int16_t));
        output->silence_frames.fetch_add(num_frames - static_cast<int64_t>(to_read));
        g_frontend.audio_declick_frames.store(kAudioDeclickFrames);
    } else {
        g_frontend.audio_declick_frames.store(declick);
    }

    output->callback_frames.fetch_add(num_frames);
    output->queued_frames.store(static_cast<int64_t>(to_read));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioErrorCallback(AAudioStream*, void* user_data, aaudio_result_t error) {
    auto* output = static_cast<AudioOutput*>(user_data);
    if (output == nullptr) return;
    output->last_error.store(static_cast<int>(error));
    output->started.store(false);
    output->state.store(0);
    LOGE("AAudio stream error %d", static_cast<int>(error));
}

// ---------------------------------------------------------------------------
// OpenGL ES context and presentation.
// ---------------------------------------------------------------------------
struct RectF {
    float left = 0;
    float top = 0;
    float right = 0;
    float bottom = 0;
};

RectF FitDisplayRect(int win_width, int win_height, double aspect) {
    if (aspect <= 0.0) aspect = 16.0 / 9.0;
    const double window_aspect = static_cast<double>(win_width) / static_cast<double>(win_height);
    int width = 0;
    int height = 0;
    if (window_aspect > aspect) {
        height = win_height;
        width = static_cast<int>(std::lround(win_height * aspect));
    } else {
        width = win_width;
        height = static_cast<int>(std::lround(win_width / aspect));
    }
    const int left = (win_width - width) / 2;
    const int top = (win_height - height) / 2;
    return {static_cast<float>(left), static_cast<float>(top), static_cast<float>(left + width),
            static_cast<float>(top + height)};
}

double DisplayAspectForMode(int mode, double core_aspect) {
    switch (mode) {
        case 2: return 4.0 / 3.0;
        case 3: return 16.0 / 9.0;
        case 4: return 10.0 / 7.0;
        default: return core_aspect;
    }
}

RectF DisplayRectForMode(int win_width, int win_height, double core_aspect) {
    const int mode = g_frontend.display_aspect_mode.load();
    if (mode == 0) return {0.0f, 0.0f, static_cast<float>(win_width), static_cast<float>(win_height)};
    return FitDisplayRect(win_width, win_height, DisplayAspectForMode(mode, core_aspect));
}

bool CreateEglContext(ANativeWindow* window) {
    if (g_gl.display == EGL_NO_DISPLAY) {
        g_gl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g_gl.display == EGL_NO_DISPLAY || !eglInitialize(g_gl.display, nullptr, nullptr)) {
            LOGE("eglInitialize failed");
            g_gl.display = EGL_NO_DISPLAY;
            return false;
        }
    }

    if (g_gl.config == nullptr) {
        const EGLint config_attributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24,
            EGL_NONE,
        };
        EGLint config_count = 0;
        if (!eglChooseConfig(g_gl.display, config_attributes, &g_gl.config, 1, &config_count) ||
            config_count == 0) {
            LOGE("eglChooseConfig failed");
            return false;
        }
    }

    if (g_gl.context == EGL_NO_CONTEXT) {
        const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        g_gl.context = eglCreateContext(g_gl.display, g_gl.config, EGL_NO_CONTEXT, context_attributes);
        if (g_gl.context == EGL_NO_CONTEXT) {
            LOGE("eglCreateContext failed");
            return false;
        }
    }

    if (g_gl.surface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(g_gl.display, g_gl.surface);
        g_gl.surface = EGL_NO_SURFACE;
    }
    g_gl.surface = eglCreateWindowSurface(g_gl.display, g_gl.config, window, nullptr);
    if (g_gl.surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }
    if (!eglMakeCurrent(g_gl.display, g_gl.surface, g_gl.surface, g_gl.context)) {
        LOGE("eglMakeCurrent failed");
        eglDestroySurface(g_gl.display, g_gl.surface);
        g_gl.surface = EGL_NO_SURFACE;
        return false;
    }
    return true;
}

void DestroyFramebuffer() {
    if (g_gl.fbo != 0) glDeleteFramebuffers(1, &g_gl.fbo);
    if (g_gl.fbo_texture != 0) glDeleteTextures(1, &g_gl.fbo_texture);
    if (g_gl.fbo_depth != 0) glDeleteRenderbuffers(1, &g_gl.fbo_depth);
    g_gl.fbo = 0;
    g_gl.fbo_texture = 0;
    g_gl.fbo_depth = 0;
    g_gl.fbo_width = 0;
    g_gl.fbo_height = 0;
}

bool CreatePresentFramebuffer() {
    retro_system_av_info info{};
    if (g_core.get_system_av_info != nullptr) g_core.get_system_av_info(&info);
    GLsizei width = info.geometry.max_width > 0 ? static_cast<GLsizei>(info.geometry.max_width) : 1024;
    GLsizei height = info.geometry.max_height > 0 ? static_cast<GLsizei>(info.geometry.max_height) : 512;
    if (width < 64) width = 64;
    if (height < 64) height = 64;
    if (g_gl.fbo != 0 && g_gl.fbo_width == width && g_gl.fbo_height == height) return true;

    DestroyFramebuffer();
    glGenTextures(1, &g_gl.fbo_texture);
    glBindTexture(GL_TEXTURE_2D, g_gl.fbo_texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenRenderbuffers(1, &g_gl.fbo_depth);
    glBindRenderbuffer(GL_RENDERBUFFER, g_gl.fbo_depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);

    glGenFramebuffers(1, &g_gl.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_gl.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_gl.fbo_texture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, g_gl.fbo_depth);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Present framebuffer incomplete: 0x%x", status);
        DestroyFramebuffer();
        return false;
    }
    g_gl.fbo_width = width;
    g_gl.fbo_height = height;
    LOGI("Present framebuffer %dx%d", width, height);
    return true;
}

bool EnsureHardwareContextLocked() {
    if (g_gl.failed) return false;
    if (g_gl.ready && g_gl.window_generation == g_frontend.window_generation && g_gl.surface != EGL_NO_SURFACE) {
        return true;
    }

    ANativeWindow* window = nullptr;
    uint32_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
        generation = g_frontend.window_generation;
        if (window != nullptr) ANativeWindow_acquire(window);
    }
    if (window == nullptr) return false;

    const bool created = CreateEglContext(window) && CreatePresentFramebuffer();
    ANativeWindow_release(window);
    if (!created) {
        g_gl.failed = true;
        return false;
    }
    g_gl.window_generation = generation;
    g_gl.ready = true;
    g_gl.pending = false;

    if (g_gl.hw_registered && g_gl.hw.context_reset != nullptr) {
        g_gl.hw.context_reset();
        LOGI("Core context_reset completed");
    }
    return true;
}

bool EnsureHardwareContext() {
    if (vulkan::IsRequested()) {
        ANativeWindow* window = nullptr;
        uint32_t generation = 0;
        {
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            window = g_frontend.window;
            generation = g_frontend.window_generation;
            if (window != nullptr) ANativeWindow_acquire(window);
        }
        if (window == nullptr) return false;
        const bool ready = vulkan::EnsureContext(window, generation);
        ANativeWindow_release(window);
        return ready;
    }
    return EnsureHardwareContextLocked();
}

void PresentHardwareFrame(GLsizei src_width, GLsizei src_height) {
    ANativeWindow* window = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
        if (window != nullptr) ANativeWindow_acquire(window);
    }
    if (window == nullptr || !g_gl.ready || g_gl.fbo == 0) {
        if (window != nullptr) ANativeWindow_release(window);
        return;
    }

    const int win_width = ANativeWindow_getWidth(window);
    const int win_height = ANativeWindow_getHeight(window);
    if (win_width <= 0 || win_height <= 0) {
        ANativeWindow_release(window);
        return;
    }

    int src_x0 = std::max(0, g_frontend.crop_left.load());
    int src_y0 = std::max(0, g_frontend.crop_bottom.load());
    int src_x1 = static_cast<int>(src_width) - std::max(0, g_frontend.crop_right.load());
    int src_y1 = static_cast<int>(src_height) - std::max(0, g_frontend.crop_top.load());
    src_x1 = std::min(src_x1, g_gl.fbo_width);
    src_y1 = std::min(src_y1, g_gl.fbo_height);
    if (src_x1 <= src_x0 || src_y1 <= src_y0) {
        src_x0 = 0;
        src_y0 = 0;
        src_x1 = g_gl.fbo_width;
        src_y1 = g_gl.fbo_height;
    }

    // The core reports the aspect ratio it wants presented (widescreen setting,
    // active video mode). Use it for every display mode so the picture looks
    // identical whichever renderer is active.
    const double core_aspect = g_frontend.aspect_ratio.load();
    const double aspect = core_aspect > 0.01 ? core_aspect
                                             : (src_height > 0
                                                ? static_cast<double>(src_width) / src_height
                                                : 16.0 / 9.0);
    const RectF dst = DisplayRectForMode(win_width, win_height, aspect);

    if (g_present_diag_count.fetch_add(1) < 3) {
        LOGI("Present %ux%u into %dx%d window, dst=(%.0f,%.0f,%.0f,%.0f)", src_width, src_height,
             win_width, win_height, dst.left, dst.top, dst.right, dst.bottom);
    }

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glViewport(0, 0, win_width, win_height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (emucoreh::shader_chain_present::Present(
            g_gl.fbo, src_x0, src_y0, src_x1 - src_x0, src_y1 - src_y0,
            win_width, win_height,
            static_cast<int>(dst.left), static_cast<int>(dst.top),
            static_cast<int>(dst.right - dst.left), static_cast<int>(dst.bottom - dst.top)) ||
        emucoreh::shader_effect::Present(
            g_gl.fbo_texture, g_gl.fbo_width, g_gl.fbo_height,
            src_x0, src_y0, src_x1 - src_x0, src_y1 - src_y0,
            win_height,
            static_cast<int>(dst.left), static_cast<int>(dst.top),
            static_cast<int>(dst.right - dst.left), static_cast<int>(dst.bottom - dst.top))) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        ANativeWindow_release(window);
        return;
    }

    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_gl.fbo);
    glBlitFramebuffer(src_x0, src_y0, src_x1, src_y1,
                      static_cast<int>(dst.left), win_height - static_cast<int>(dst.bottom),
                      static_cast<int>(dst.right), win_height - static_cast<int>(dst.top),
                      GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    ANativeWindow_release(window);
}

void PresentSoftwareFrame(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (data == nullptr || width == 0 || height == 0) return;
    if (!g_gl.ready || g_gl.fbo_texture == 0 ||
        width > static_cast<unsigned>(g_gl.fbo_width) ||
        height > static_cast<unsigned>(g_gl.fbo_height) ||
        pitch < static_cast<size_t>(width) * sizeof(uint32_t)) {
        return;
    }
    // The libretro software frame is top-down XRGB8888. GLES expects RGBA
    // with its first upload row at the bottom of the texture.
    static thread_local std::vector<uint8_t> rgba;
    rgba.resize(static_cast<size_t>(width) * height * 4);
    const auto* source = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; ++y) {
        const auto* row = source + static_cast<size_t>(y) * pitch;
        auto* target = rgba.data() + static_cast<size_t>(height - 1 - y) * width * 4;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t pixel;
            std::memcpy(&pixel, row + static_cast<size_t>(x) * 4, sizeof(pixel));
            target[x * 4 + 0] = static_cast<uint8_t>(pixel >> 16);
            target[x * 4 + 1] = static_cast<uint8_t>(pixel >> 8);
            target[x * 4 + 2] = static_cast<uint8_t>(pixel);
            target[x * 4 + 3] = 255;
        }
    }
    glBindTexture(GL_TEXTURE_2D, g_gl.fbo_texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, static_cast<GLsizei>(width),
                    static_cast<GLsizei>(height), GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    PresentHardwareFrame(static_cast<GLsizei>(width), static_cast<GLsizei>(height));
}

void DestroyHardwareRendererContext() {
    if (vulkan::IsRequested()) {
        vulkan::NotifyContextDestroy();
        return;
    }
    if (g_gl.ready && g_gl.hw_registered && g_gl.hw.context_destroy != nullptr) {
        g_gl.hw.context_destroy();
    }
    g_gl.ready = false;
    g_gl.pending = false;
}

void DestroyHardwareContext() {
    vulkan::Destroy();
    if (g_gl.display == EGL_NO_DISPLAY) return;
    // Framebuffer objects belong to the EGL context. Delete them while that
    // context is still current; an unbound glDelete* is undefined on Android.
    emucoreh::shader_chain_present::Destroy();
    emucoreh::shader_effect::Destroy();
    DestroyFramebuffer();
    if (g_gl.context != EGL_NO_CONTEXT) {
        eglMakeCurrent(g_gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (g_gl.surface != EGL_NO_SURFACE) {
        eglDestroySurface(g_gl.display, g_gl.surface);
        g_gl.surface = EGL_NO_SURFACE;
    }
    if (g_gl.context != EGL_NO_CONTEXT) {
        eglDestroyContext(g_gl.display, g_gl.context);
        g_gl.context = EGL_NO_CONTEXT;
    }
    if (g_gl.display != EGL_NO_DISPLAY) {
        eglTerminate(g_gl.display);
        g_gl.display = EGL_NO_DISPLAY;
    }
    g_gl.config = nullptr;
    g_gl.ready = false;
    g_gl.failed = false;
    g_gl.hw_registered = false;
    g_gl.hw = retro_hw_render_callback{};
}

void RetroVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    (void)pitch;
    static thread_local int skip_counter = 0;
    const int skip = g_frontend.frame_skip.load();
    if (skip > 0) {
        if (skip_counter > 0) {
            skip_counter--;
            return;
        }
        skip_counter = skip;
    }

    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        g_frontend.frame_width = width;
        g_frontend.frame_height = height;
        if (vulkan::IsActive()) {
            const int mode = g_frontend.display_aspect_mode.load();
            const double aspect = DisplayAspectForMode(mode, g_frontend.aspect_ratio.load());
            vulkan::Present(width, height, aspect, mode == 0, mode == 1);
            return;
        }
        PresentHardwareFrame(static_cast<GLsizei>(width), static_cast<GLsizei>(height));
        if (g_gl.surface != EGL_NO_SURFACE) eglSwapBuffers(g_gl.display, g_gl.surface);
        return;
    }

    if (data == nullptr) {
        if (g_gl.surface != EGL_NO_SURFACE) eglSwapBuffers(g_gl.display, g_gl.surface);
        return;
    }

    g_frontend.frame_width = width;
    g_frontend.frame_height = height;
    PresentSoftwareFrame(data, width, height, pitch);
    if (g_gl.surface != EGL_NO_SURFACE) eglSwapBuffers(g_gl.display, g_gl.surface);
}

// ---------------------------------------------------------------------------
// Input.
// ---------------------------------------------------------------------------
// The Kotlin layer keeps the EmuCoreH active-low pad bitmask convention:
//   0 Select, 3 Start, 4 Up, 5 Right, 6 Down, 7 Left,
//   10 L1, 11 R1, 12 Triangle, 13 Circle, 14 Cross, 15 Square.
int16_t RetroInputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port > 1) return 0;
    auto pressed = [port](int bit) { return (g_frontend.pad_buttons[port].load() & (1u << bit)) == 0; };

    if (device == RETRO_DEVICE_JOYPAD) {
        uint16_t active = 0;
        if (pressed(4)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_UP;
        if (pressed(6)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_DOWN;
        if (pressed(7)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_LEFT;
        if (pressed(5)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_RIGHT;
        if (pressed(12)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_X;  // Triangle
        if (pressed(13)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_A;  // Circle
        if (pressed(14)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_B;  // Cross
        if (pressed(15)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_Y;  // Square
        if (pressed(10)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_L;
        if (pressed(11)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_R;
        // The Dreamcast pad's analog L/R triggers are read from the libretro
        // L2/R2 ids by the core (get_analog_trigger), with the digital mask as
        // fallback.
        if (pressed(8)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_L2;
        if (pressed(9)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_R2;
        // Arcade (Naomi/Atomiswave) service switches: the core maps L3 to Test
        // and R3 to Service, SELECT to Coin and L to Insert Card.
        if (pressed(1)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_L3;
        if (pressed(2)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_R3;
        if (pressed(3)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_START;
        if (pressed(0)) active |= 1u << RETRO_DEVICE_ID_JOYPAD_SELECT;
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(active);
        if (id < 16) return (active & (1u << id)) != 0 ? 1 : 0;
        return 0;
    }

    if (device == RETRO_DEVICE_ANALOG) {
        const int axis_offset = index == RETRO_DEVICE_INDEX_ANALOG_RIGHT ? 2 : 0;
        if (id == RETRO_DEVICE_ID_ANALOG_X) return g_frontend.pad_analog[port][axis_offset + 0].load();
        if (id == RETRO_DEVICE_ID_ANALOG_Y) return g_frontend.pad_analog[port][axis_offset + 1].load();
        return 0;
    }

    return 0;
}

bool RetroSetRumbleState(unsigned port, retro_rumble_effect effect, uint16_t strength) {
    if (port >= 2) return false;
    const uint8_t intensity = static_cast<uint8_t>((static_cast<uint32_t>(strength) + 128U) / 257U);
    switch (effect) {
        case RETRO_RUMBLE_STRONG:
            g_frontend.rumble_strong[port].store(intensity);
            return true;
        case RETRO_RUMBLE_WEAK:
            g_frontend.rumble_weak[port].store(intensity);
            return true;
        default:
            return false;
    }
}

void RetroInputPoll() {}

}  // namespace

// ---------------------------------------------------------------------------
// JNI surface. All functions are instance methods of
// com.sbro.emucoreh.core.NativeCoreBridge.
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    EmuCoreHAchievementsSetJavaVm(vm);
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        EmuCoreHAchievementsInitializeJava(env);
        if (!InitializeStorageVfs(vm, env)) return JNI_ERR;
    }
    LOGI("emucoreh_jni loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    EmuCoreHAchievementsShutdown();
}

void* EmuCoreHGetMemoryData(unsigned id) {
    return g_core.get_memory_data != nullptr ? g_core.get_memory_data(id) : nullptr;
}

size_t EmuCoreHGetMemorySize(unsigned id) {
    return g_core.get_memory_size != nullptr ? g_core.get_memory_size(id) : 0;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_nativeInit(JNIEnv* env, jobject, jstring system_dir,
                                                        jstring save_dir, jstring core_assets_dir) {
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    g_frontend.system_dir = ToString(env, system_dir);
    g_frontend.save_dir = ToString(env, save_dir);
    g_frontend.default_save_dir = g_frontend.save_dir;
    g_frontend.core_assets_dir = ToString(env, core_assets_dir);
    if (g_frontend.audio_ring.empty()) {
        g_frontend.audio_ring.assign(kAudioRingCapacityFrames * 2, 0);
    }
    LOGI("nativeInit system=%s save=%s", g_frontend.system_dir.c_str(), g_frontend.save_dir.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_createSession(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (!LoadCoreLocked()) return 0;
    if (!g_frontend.core_initialized.load()) {
        g_core.set_environment(EnvironmentCallback);
        g_core.set_video_refresh(RetroVideoRefresh);
        g_core.set_audio_sample(RetroAudioSample);
        g_core.set_audio_sample_batch(RetroAudioSampleBatchWrapper);
        g_core.set_input_poll(RetroInputPoll);
        g_core.set_input_state(RetroInputState);
        g_core.init();
        g_frontend.core_initialized.store(true);
        LOGI("retro_init complete");
    }
    g_frontend.shutdown_requested.store(false);
    return reinterpret_cast<jlong>(&g_frontend);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_destroySession(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    g_frontend.time_control.store(0);
    for (int port = 0; port < 2; ++port) {
        g_frontend.rumble_strong[port].store(0);
        g_frontend.rumble_weak[port].store(0);
    }
    EmuCoreHAchievementsOnSessionEnd();
    // The frame worker has already joined. Rebind its EGL context on this
    // teardown thread before Flycast releases GPU resources in context_destroy
    // and retro_unload_game. Otherwise repeated game exits can corrupt GL
    // state and crash the following launch.
    if (g_gl.display != EGL_NO_DISPLAY && g_gl.surface != EGL_NO_SURFACE &&
        g_gl.context != EGL_NO_CONTEXT &&
        !eglMakeCurrent(g_gl.display, g_gl.surface, g_gl.surface, g_gl.context)) {
        LOGE("Unable to bind EGL context for session teardown: 0x%x", eglGetError());
    }
    DestroyHardwareRendererContext();
    if (g_frontend.game_loaded.load()) {
        if (g_core.unload_game != nullptr) g_core.unload_game();
        g_frontend.game_loaded.store(false);
    }
    // A full core teardown is required between sessions: Flycast builds its
    // renderer during retro_init/retro_load_game and releases it in
    // retro_deinit, which is what makes a renderer change take effect on the
    // next boot. Emulator::init() in the core is re-armed for the Terminated
    // state so repeated init/deinit cycles in one process stay safe.
    if (g_frontend.core_initialized.load()) {
        if (g_core.deinit != nullptr) g_core.deinit();
        g_frontend.core_initialized.store(false);
    }
    DestroyHardwareContext();
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_reset(JNIEnv*, jobject, jlong handle) {
    if (handle == 0 || !g_frontend.game_loaded.load() || g_core.reset == nullptr) return -1;
    g_core.reset();
    return 0;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_runFrame(JNIEnv* env, jobject, jlong handle) {
    if (g_frontend.shutdown_requested.load()) {
        const char* detail = g_core.boot_error ? g_core.boot_error() : nullptr;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      detail && *detail ? detail : "Flycast requested shutdown");
        return;
    }
    if (handle == 0 || !g_frontend.core_initialized.load()) return;
    if (!EnsureHardwareContext()) return;
    if (g_frontend.time_control.load() == 2 && g_core.rewind_step != nullptr) {
        static auto last_rewind = std::chrono::steady_clock::time_point{};
        const auto now = std::chrono::steady_clock::now();
        if (now - last_rewind >= std::chrono::milliseconds(500)) {
            const bool queued = g_core.rewind_step();
            LOGI("Rewind step queued=%d", queued ? 1 : 0);
            last_rewind = now;
        }
    }
    // Flycast updates its AV geometry when internal resolution changes. The
    // render target must grow before the next retro_run(), otherwise 4x and
    // higher frames are clipped to the old (for example 3x) framebuffer.
    if (g_frontend.av_info_refresh_pending.exchange(false)) {
        if (g_core.get_system_av_info != nullptr) {
            retro_system_av_info info{};
            g_core.get_system_av_info(&info);
            if (info.geometry.aspect_ratio > 0.01) {
                g_frontend.aspect_ratio.store(info.geometry.aspect_ratio);
            }
        }
        if (!vulkan::IsRequested() && !CreatePresentFramebuffer()) {
            g_frontend.av_info_refresh_pending.store(true);
            return;
        }
    }
    if (g_core.run != nullptr) g_core.run();
    if (g_frontend.shutdown_requested.load()) {
        const char* detail = g_core.boot_error ? g_core.boot_error() : nullptr;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      detail && *detail ? detail : "Flycast requested shutdown");
        return;
    }
    EmuCoreHAchievementsOnFrame();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setTimeControl(JNIEnv*, jobject, jint mode) {
    const int safe_mode = mode >= 0 && mode <= 2 ? mode : 0;
    g_frontend.time_control.store(safe_mode);
    LOGI("Time control mode=%d", safe_mode);
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_ensureHardwareContext(JNIEnv*, jobject) {
    return EnsureHardwareContext() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setDisplayAspectRatio(JNIEnv*, jobject, jint mode) {
    g_frontend.display_aspect_mode.store(mode >= 0 && mode <= 4 ? mode : 1);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setSurface(JNIEnv* env, jobject, jlong handle,
                                                        jobject surface, jint renderer) {
    if (handle == 0) return -1;
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }

    uint32_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        if (g_frontend.window != nullptr) ANativeWindow_release(g_frontend.window);
        g_frontend.window = window;
        g_frontend.requested_renderer = renderer;
        generation = ++g_frontend.window_generation;
    }
    return window != nullptr && renderer == core_renderer::kVulkan &&
           !vulkan::Prepare(window, generation) ? -2 : 0;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_nativeSetOption(JNIEnv* env, jobject, jstring key,
                                                             jstring value) {
    if (key == nullptr || value == nullptr) return;
    retro_core_options_update_display_callback_t update_display = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        g_frontend.options[ToString(env, key)] = ToString(env, value);
        update_display = g_frontend.update_display_callback.callback;
    }
    g_frontend.options_dirty.store(true);
    g_frontend.av_info_refresh_pending.store(true);
    // The callback re-queries options through the environment interface, so it
    // must not run while the options lock is held.
    if (update_display != nullptr) update_display();
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_nativeGetOption(JNIEnv* env, jobject, jstring key) {
    if (key == nullptr) return nullptr;
    const std::string key_string = ToString(env, key);
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    auto it = g_frontend.options.find(key_string);
    if (it == g_frontend.options.end()) return nullptr;
    return env->NewStringUTF(it->second.c_str());
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_nativeSetShaderEffect(JNIEnv*, jobject, jint effect) {
    emucoreh::shader_effect::Set(effect);
    vulkan::SetShaderEffect(effect);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_nativeSetShaderPreset(JNIEnv* env, jobject,
                                                                     jstring path, jboolean enabled) {
    emucoreh::shader_chain::SetPreset(ToString(env, path), enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_loadBios(JNIEnv*, jobject, jlong handle, jstring) {
    // Flycast does not need a BIOS image; firmware assets are handled by the
    // frontend data directory.
    return handle == 0 ? -1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_loadBiosOnly(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;

    {
        std::lock_guard<std::mutex> serial_lock(g_game_serial_mutex);
        g_game_serial.clear();
    }
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (!LoadCoreLocked() || !g_frontend.core_initialized.load()) return -2;
    if (g_frontend.game_loaded.load()) {
        if (g_core.unload_game != nullptr) g_core.unload_game();
        g_frontend.game_loaded.store(false);
    }

    // An empty content path makes the core boot its BIOS without a disc.
    retro_game_info info{};
    info.path = "";
    if (!g_core.load_game(&info)) {
        LOGE("retro_load_game failed for BIOS-only boot");
        return -3;
    }
    if (g_core.set_controller_port_device != nullptr) {
        for (unsigned port = 0; port < 4; ++port) {
            g_core.set_controller_port_device(port, RETRO_DEVICE_JOYPAD);
        }
    }
    g_frontend.game_loaded.store(true);
    g_frontend.shutdown_requested.store(false);
    g_frontend.av_info_refresh_pending.store(true);
    LOGI("Loaded BIOS without content");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_loadDisc(JNIEnv* env, jobject, jlong handle, jstring path) {
    if (handle == 0 || path == nullptr) return -1;
    const std::string path_string = ToString(env, path);
    if (path_string.empty()) return -1;

    {
        std::lock_guard<std::mutex> serial_lock(g_game_serial_mutex);
        g_game_serial.clear();
    }
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (!LoadCoreLocked() || !g_frontend.core_initialized.load()) return -2;
    if (g_frontend.game_loaded.load()) {
        if (g_core.unload_game != nullptr) g_core.unload_game();
        g_frontend.game_loaded.store(false);
    }

    retro_game_info info{};
    info.path = path_string.c_str();
    if (!g_core.load_game(&info)) {
        LOGE("retro_load_game failed for %s", path_string.c_str());
        return -3;
    }
    // Frontends announce the attached pads after loading. Flycast builds its
    // Maple devices (controller + VMU in each expansion slot) from this call.
    if (g_core.set_controller_port_device != nullptr) {
        for (unsigned port = 0; port < 4; ++port) {
            g_core.set_controller_port_device(port, RETRO_DEVICE_JOYPAD);
        }
    }
    g_frontend.game_loaded.store(true);
    g_frontend.shutdown_requested.store(false);
    g_frontend.av_info_refresh_pending.store(true);
    LOGI("Loaded content: %s", path_string.c_str());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setPadButtons(JNIEnv*, jobject, jlong handle,
                                                           jint port, jint buttons) {
    if (handle == 0 || port < 0 || port > 1) return;
    g_frontend.pad_buttons[port].store(static_cast<uint16_t>(buttons & 0xFFFF));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setPadAnalog(JNIEnv*, jobject, jlong handle, jint port,
                                                          jint lx, jint ly, jint rx, jint ry) {
    if (handle == 0 || port < 0 || port > 1) return;
    auto scale = [](jint value) -> int16_t {
        const int scaled = (value - 128) * 256;
        return static_cast<int16_t>(std::clamp(scaled, -32768, 32767));
    };
    g_frontend.pad_analog[port][0].store(scale(lx));
    g_frontend.pad_analog[port][1].store(scale(ly));
    g_frontend.pad_analog[port][2].store(scale(rx));
    g_frontend.pad_analog[port][3].store(scale(ry));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setPadAnalogMode(JNIEnv*, jobject, jlong handle, jint port,
                                                              jboolean enabled) {
    if (handle == 0 || port < 0 || port > 1) return;
    g_frontend.pad_analog_mode[port].store(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getPadState(JNIEnv*, jobject, jlong handle, jint port) {
    if (handle == 0 || port < 0 || port > 1) return -1;
    const int analog = g_frontend.pad_analog_mode[port].load() ? 1 : 0;
    const int strong = g_frontend.rumble_strong[port].load();
    const int weak = g_frontend.rumble_weak[port].load();
    return (analog << 16) | (strong << 8) | weak;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_saveState(JNIEnv* env, jobject, jlong handle, jstring path) {
    if (handle == 0 || path == nullptr || g_core.serialize == nullptr) return -1;
    const std::string path_string = ToString(env, path);
    if (path_string.empty()) return -2;

    const size_t size = g_core.serialize_size != nullptr ? g_core.serialize_size() : 0;
    if (size == 0) return -2;
    std::vector<uint8_t> buffer(size);
    if (!g_core.serialize(buffer.data(), buffer.size())) return -3;

    std::ofstream out(path_string, std::ios::binary | std::ios::trunc);
    if (!out.is_open()) return -4;
    out.write(reinterpret_cast<const char*>(buffer.data()), static_cast<std::streamsize>(buffer.size()));
    if (!out.good()) return -5;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_loadState(JNIEnv* env, jobject, jlong handle, jstring path) {
    if (handle == 0 || path == nullptr || g_core.unserialize == nullptr) return -1;
    const std::string path_string = ToString(env, path);
    if (path_string.empty()) return -2;

    std::ifstream in(path_string, std::ios::binary | std::ios::ate);
    if (!in.is_open()) return -2;
    const std::streamsize size = in.tellg();
    if (size <= 0) return -2;
    in.seekg(0, std::ios::beg);
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    if (!in.read(reinterpret_cast<char*>(buffer.data()), size)) return -3;
    if (!g_core.unserialize(buffer.data(), buffer.size())) return -3;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_createMemoryCard(JNIEnv*, jobject, jstring) {
    // PSP savedata is managed by the core inside the memstick directory; the
    // legacy PS1 memory-card manager has no native counterpart.
    return -1;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setMemoryCardPath(JNIEnv*, jobject, jint, jstring) {}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setDataRootOverride(JNIEnv* env, jobject,
                                                                  jstring path) {
    const std::string data_root = ToString(env, path);
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    g_frontend.save_dir = data_root.empty() ? g_frontend.default_save_dir : data_root;
}

// Turns one "ADDRESS VALUE" line into the eight-hex-digit groups the core's
// cheat parser expects, dropping the decoration some converters keep.
std::string NormalizeCheatCodeLine(const std::string& raw) {
    std::string result;
    size_t start = 0;
    while (start < raw.size()) {
        size_t end = raw.find_first_of(" \t", start);
        if (end == std::string::npos) end = raw.size();
        std::string token = raw.substr(start, end - start);
        start = end + 1;
        token.erase(std::remove_if(token.begin(), token.end(), [](unsigned char c) {
            return !std::isxdigit(c);
        }), token.end());
        if (token.empty()) continue;
        // The address group is always eight digits; a shorter trailing value is
        // the code's payload and is left-padded instead of rejected.
        if (token.size() < 8) token.insert(0, 8 - token.size(), '0');
        if (!result.empty()) result += ' ';
        result += token;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_loadCheats(JNIEnv* env, jobject, jstring source_path) {
    if (g_core.cheat_reset != nullptr) g_core.cheat_reset();
    if (source_path == nullptr || !g_frontend.game_loaded.load() || g_core.cheat_set == nullptr) {
        return;
    }
    const std::string path = ToString(env, source_path);
    std::ifstream input(path, std::ios::binary);
    if (!input) return;
    // The cheat manager stages one "// <label>" section per selected block,
    // followed by address/value code lines. Every section is handed to the
    // core as one libretro cheat entry.
    std::string codes;
    unsigned index = 0;
    std::string line;
    while (std::getline(input, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const size_t start = line.find_first_not_of(" \t");
        if (start == std::string::npos) continue;
        const std::string trimmed = line.substr(start);
        if (trimmed.rfind("//", 0) == 0 || trimmed.rfind("#", 0) == 0) {
            if (!codes.empty()) {
                g_core.cheat_set(index++, true, codes.c_str());
                codes.clear();
            }
            continue;
        }
        if (trimmed.rfind("Author", 0) == 0 && trimmed.find('=') != std::string::npos) continue;
        const std::string normalized = NormalizeCheatCodeLine(trimmed);
        if (normalized.empty()) continue;
        if (!codes.empty()) codes += ' ';
        codes += normalized;
    }
    if (!codes.empty()) {
        g_core.cheat_set(index, true, codes.c_str());
    }
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_clearCheats(JNIEnv*, jobject) {
    if (g_core.cheat_reset != nullptr) g_core.cheat_reset();
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getSystemInfo(JNIEnv* env, jobject) {
    if (!LoadCoreLocked() || g_core.get_system_info == nullptr) {
        return env->NewStringUTF("Flycast");
    }
    retro_system_info info{};
    g_core.get_system_info(&info);
    std::string name = info.library_name != nullptr ? info.library_name : "Flycast";
    std::string version = info.library_version != nullptr ? info.library_version : "";
    std::string result = version.empty() ? name : name + " " + version;
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getDiagnostics(JNIEnv* env, jobject) {
    char buffer[256];
    std::snprintf(buffer, sizeof(buffer),
                  "{\"core\":\"Flycast\",\"initialized\":%d,\"game\":%d,\"renderer\":%d,\"w\":%u,\"h\":%u}",
                  g_frontend.core_initialized.load() ? 1 : 0, g_frontend.game_loaded.load() ? 1 : 0,
                  g_frontend.requested_renderer, g_frontend.frame_width, g_frontend.frame_height);
    return env->NewStringUTF(buffer);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_apiVersion(JNIEnv* env, jobject) {
    if (!LoadCoreLocked()) {
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class, "Flycast libretro core could not be loaded");
        }
        return 0;
    }
    return static_cast<jint>(g_core.api_version != nullptr ? g_core.api_version() : RETRO_API_VERSION);
}

JNIEXPORT jintArray JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getDisplayRect(JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return nullptr;
    unsigned width = g_frontend.frame_width;
    unsigned height = g_frontend.frame_height;
    if (width == 0 || height == 0) {
        retro_system_av_info info{};
        if (LoadCoreLocked() && g_core.get_system_av_info != nullptr) {
            g_core.get_system_av_info(&info);
            width = info.geometry.base_width;
            height = info.geometry.base_height;
        }
    }
    jint values[4] = {0, 0, static_cast<jint>(width), static_cast<jint>(height)};
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getPresentRect(JNIEnv* env, jobject) {
    float values[4] = {0, 0, 0, 0};
    ANativeWindow* window = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
        if (window != nullptr) {
            const int win_width = ANativeWindow_getWidth(window);
            const int win_height = ANativeWindow_getHeight(window);
            const double aspect = g_frontend.frame_height > 0
                                      ? static_cast<double>(g_frontend.frame_width) / g_frontend.frame_height
                                      : g_frontend.aspect_ratio.load();
            const RectF rect = DisplayRectForMode(win_width, win_height, aspect);
            values[0] = rect.left;
            values[1] = rect.top;
            values[2] = rect.right;
            values[3] = rect.bottom;
        }
    }
    jfloatArray result = env->NewFloatArray(4);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getFrameRate(JNIEnv*, jobject, jlong handle) {
    if (handle == 0 || !LoadCoreLocked() || g_core.get_system_av_info == nullptr) return 60.0;
    retro_system_av_info info{};
    g_core.get_system_av_info(&info);
    return info.timing.fps > 0.0 ? info.timing.fps : 60.0;
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_nativeGameSerial(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_game_serial_mutex);
    if (g_game_serial.empty()) return nullptr;
    return env->NewStringUTF(g_game_serial.c_str());
}

JNIEXPORT jlongArray JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getAvInfo(JNIEnv* env, jobject, jlong handle) {
    retro_system_av_info info{};
    if (handle != 0 && LoadCoreLocked() && g_core.get_system_av_info != nullptr) {
        g_core.get_system_av_info(&info);
    }
    jlong values[6] = {
        static_cast<jlong>(info.geometry.base_width), static_cast<jlong>(info.geometry.base_height),
        static_cast<jlong>(info.geometry.max_width), static_cast<jlong>(info.geometry.max_height),
        static_cast<jlong>(info.timing.fps), static_cast<jlong>(info.timing.sample_rate)};
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_hasDiscMedia(JNIEnv*, jobject, jlong handle) {
    return (handle != 0 && g_frontend.game_loaded.load()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_readGameAsset(JNIEnv* env, jobject, jstring path, jint asset) {
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (!LoadCoreLocked() || !g_core.game_asset) return nullptr;
    std::vector<uint8_t> bytes(4 * 1024 * 1024);
    const int size = g_core.game_asset(ToString(env, path).c_str(), asset, bytes.data(), bytes.size());
    if (size <= 0 || (size_t)size > bytes.size()) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getDiscMetadata(JNIEnv*, jobject, jstring) {
    return nullptr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_readGameAssetFd(JNIEnv* env, jobject, jint fd, jint asset) {
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (!LoadCoreLocked() || !g_core.game_asset_fd) return nullptr;
    std::vector<uint8_t> bytes(4 * 1024 * 1024);
    const int size = g_core.game_asset_fd(fd, asset, bytes.data(), bytes.size());
    if (size <= 0 || (size_t)size > bytes.size()) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_getDiscMetadataFd(JNIEnv*, jobject, jint, jlong, jlong) {
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setAudioOutputLatencyMs(JNIEnv*, jobject, jint milliseconds) {
    g_frontend.audio_output_latency_ms.store(std::clamp(static_cast<int>(milliseconds), 1, 500));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setAudioLowLatency(JNIEnv*, jobject, jboolean enabled) {
    g_frontend.audio_low_latency.store(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setFrameSkip(JNIEnv*, jobject, jint frames) {
    g_frontend.frame_skip.store(std::clamp(static_cast<int>(frames), 0, 4));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setDisplayCrop(JNIEnv*, jobject, jint left, jint top,
                                                            jint right, jint bottom) {
    const int l = std::clamp(static_cast<int>(left), 0, 64);
    const int t = std::clamp(static_cast<int>(top), 0, 64);
    const int r = std::clamp(static_cast<int>(right), 0, 64);
    const int b = std::clamp(static_cast<int>(bottom), 0, 64);
    g_frontend.crop_left.store(l);
    g_frontend.crop_top.store(t);
    g_frontend.crop_right.store(r);
    g_frontend.crop_bottom.store(b);
    vulkan::SetDisplayCrop(l, t, r, b);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setAudioGain(JNIEnv*, jobject, jfloat gain) {
    float value = static_cast<float>(gain);
    if (std::isnan(value)) value = 0.0f;
    g_frontend.audio_gain.store(std::clamp(value, 0.0f, 1.0f));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_setAudioPlaybackRate(JNIEnv*, jobject, jdouble rate) {
    if (std::isfinite(rate)) g_frontend.audio_playback_rate.store(std::clamp(rate, 0.25, 2.0));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_resetAudioQueue(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    g_frontend.audio_read_frame = 0;
    g_frontend.audio_write_frame = 0;
    ++g_frontend.audio_generation;
    g_frontend.audio_declick_frames.store(0);
}

JNIEXPORT jlong JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_createAudioOutput(JNIEnv*, jobject) {
    auto* output = new AudioOutput();
    output->sample_rate = 44100;

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        delete output;
        return 0;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, output->sample_rate);
    const int latency_ms = g_frontend.audio_output_latency_ms.load();
    const int32_t capacity_frames = latency_ms * output->sample_rate / 1000;
    AAudioStreamBuilder_setBufferCapacityInFrames(builder, std::max(capacity_frames, 512));
    AAudioStreamBuilder_setPerformanceMode(builder,
        g_frontend.audio_low_latency.load() ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
                                            : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setDataCallback(builder, AudioDataCallback, output);
    AAudioStreamBuilder_setErrorCallback(builder, AudioErrorCallback, output);

    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &output->stream);
    if (result != AAUDIO_OK && g_frontend.audio_low_latency.load()) {
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        result = AAudioStreamBuilder_openStream(builder, &output->stream);
    }
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || output->stream == nullptr) {
        LOGE("AAudio open failed: %d", static_cast<int>(result));
        delete output;
        return 0;
    }

    output->sample_rate = AAudioStream_getSampleRate(output->stream);
    const int32_t burst = AAudioStream_getFramesPerBurst(output->stream);
    int32_t device_buffer = std::clamp(burst * 2, 512, 1024);
    const int32_t stream_capacity = AAudioStream_getBufferCapacityInFrames(output->stream);
    if (stream_capacity > 0) device_buffer = std::min(device_buffer, stream_capacity);
    AAudioStream_setBufferSizeInFrames(output->stream, device_buffer);
    output->device_buffer_frames = device_buffer;
    output->pacing_high_water_frames = std::min(device_buffer * 4, 6144);
    LOGI("AAudio output created: %d Hz, buffer %d frames, high water %d", output->sample_rate,
         output->device_buffer_frames, output->pacing_high_water_frames);
    g_frontend.audio_pacing_high_water.store(output->pacing_high_water_frames);
    return reinterpret_cast<jlong>(output);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_destroyAudioOutput(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    g_frontend.audio_pacing_high_water.store(0);
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    std::unique_lock<std::mutex> lock(output->mutex);
    if (output->stream != nullptr) {
        AAudioStream_requestStop(output->stream);
        AAudioStream_close(output->stream);
        output->stream = nullptr;
    }
    lock.unlock();
    delete output;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_startAudioOutput(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output->stream == nullptr) return -2;
    const aaudio_result_t result = AAudioStream_requestStart(output->stream);
    if (result != AAUDIO_OK) {
        output->last_error.store(static_cast<int>(result));
        return -2;
    }
    output->started.store(true);
    output->state.store(1);
    g_frontend.audio_pacing_high_water.store(output->pacing_high_water_frames);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_pauseAudioOutput(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output->stream == nullptr) return -2;
    const aaudio_result_t result = AAudioStream_requestPause(output->stream);
    if (result != AAUDIO_OK && result != AAUDIO_ERROR_INVALID_STATE) {
        output->last_error.store(static_cast<int>(result));
        return -2;
    }
    output->started.store(false);
    output->state.store(2);
    g_frontend.audio_pacing_high_water.store(0);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_flushAudioOutput(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    aaudio_result_t result = AAUDIO_OK;
    if (output->stream != nullptr) {
        result = AAudioStream_requestFlush(output->stream);
        if (result != AAUDIO_OK && result != AAUDIO_ERROR_INVALID_STATE) {
            output->last_error.store(static_cast<int>(result));
            return -2;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
        g_frontend.audio_read_frame = 0;
        g_frontend.audio_write_frame = 0;
        ++g_frontend.audio_generation;
        g_frontend.audio_declick_frames.store(0);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_audioOutputBufferedFrames(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output->stream == nullptr || !output->started.load() || output->last_error.load() != 0) return -1;
    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    const size_t capacity = kAudioRingCapacityFrames;
    return static_cast<jint>((g_frontend.audio_write_frame + capacity - g_frontend.audio_read_frame) % capacity);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_audioOutputPacingHighWaterFrames(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    return output->pacing_high_water_frames;
}

JNIEXPORT jlongArray JNICALL
Java_com_sbro_emucoreh_core_NativeCoreBridge_audioOutputStats(JNIEnv* env, jobject, jlong handle) {
    jlong values[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    if (handle != 0) {
        auto* output = reinterpret_cast<AudioOutput*>(handle);
        values[0] = output->state.load();
        values[1] = output->last_error.load();
        values[2] = output->sample_rate;
        values[4] = output->queued_frames.load();
        values[6] = output->callback_frames.load();
        values[7] = output->silence_frames.load();
    }
    jlongArray result = env->NewLongArray(8);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}

}  // extern "C"
