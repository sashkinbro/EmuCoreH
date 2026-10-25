// SPDX-License-Identifier: GPL-3.0-or-later
// Standalone owner-content benchmark: never linked into the Android app.
#include "libretro.h"
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <stdexcept>
#include <string>
#include <time.h>
#include <vector>

namespace {
std::string system_directory, save_directory;
uint64_t video_frames = 0, audio_frames = 0, nonzero_audio = 0;
bool colored_frame = false, stopped = false;
bool verify_output = false;
uint64_t video_digest = 14695981039346656037ULL, audio_digest = 14695981039346656037ULL;
void digest_bytes(uint64_t& digest, const void* data, size_t size) {
  const auto* bytes = static_cast<const uint8_t*>(data);
  for (size_t i = 0; i < size; ++i) digest = (digest ^ bytes[i]) * 1099511628211ULL;
}
uint64_t capture_at = 0;
unsigned captured_width = 0, captured_height = 0;
std::vector<uint8_t> captured_rgb;
struct InputPulse { uint64_t frame; unsigned button; };
std::vector<InputPulse> input_pulses;
retro_pixel_format pixel_format = RETRO_PIXEL_FORMAT_XRGB8888;
bool environment(unsigned command, void* data) {
  switch (command) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
      *static_cast<const char**>(data) = system_directory.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
      *static_cast<const char**>(data) = save_directory.c_str(); return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
      pixel_format = *static_cast<retro_pixel_format*>(data);
      return pixel_format == RETRO_PIXEL_FORMAT_XRGB8888 || pixel_format == RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      *static_cast<bool*>(data) = true; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
      *static_cast<bool*>(data) = false; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE:
      if (auto* variable = static_cast<retro_variable*>(data);
          variable->key && !std::strcmp(variable->key, "jgenesis_jit_backend")) {
        const char* requested = std::getenv("JGENESIS_BENCH_BACKEND");
        variable->value = requested && !std::strcmp(requested, "native") ? "native" : "interpreter";
        return true;
      }
      static_cast<retro_variable*>(data)->value = nullptr; return false;
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
      return true;
    case RETRO_ENVIRONMENT_SHUTDOWN:
      stopped = true; return true;
    default: return false;
  }
}
void video(const void* data, unsigned width, unsigned height, size_t pitch) {
  if (!data || data == RETRO_HW_FRAME_BUFFER_VALID || !width || !height) return;
  ++video_frames;
  if (verify_output) {
    digest_bytes(video_digest, &width, sizeof(width));
    digest_bytes(video_digest, &height, sizeof(height));
    const size_t row_size = size_t(width) * (pixel_format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2);
    for (unsigned y = 0; y < height; ++y)
      digest_bytes(video_digest, static_cast<const uint8_t*>(data) + y * pitch, row_size);
  }
  if (video_frames == capture_at) {
    captured_width = width; captured_height = height;
    captured_rgb.resize(size_t(width) * height * 3);
    for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
      const auto* src = static_cast<const uint8_t*>(data) + y * pitch;
      auto* dst = captured_rgb.data() + (size_t(y) * width + x) * 3;
      if (pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
        uint32_t pixel; std::memcpy(&pixel, src + x * 4, 4);
        dst[0] = pixel >> 16; dst[1] = pixel >> 8; dst[2] = pixel;
      } else {
        uint16_t pixel; std::memcpy(&pixel, src + x * 2, 2);
        dst[0] = ((pixel >> 11) & 31) * 255 / 31;
        dst[1] = ((pixel >> 5) & 63) * 255 / 63;
        dst[2] = (pixel & 31) * 255 / 31;
      }
    }
  }
  if (colored_frame) return;
  const auto* bytes = static_cast<const uint8_t*>(data);
  const unsigned size = pixel_format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
  uint32_t first = 0;
  std::memcpy(&first, bytes, size);
  for (unsigned y = 0; y < height; y += 8) {
    for (unsigned x = 0; x < width; x += 8) {
      uint32_t pixel = 0;
      std::memcpy(&pixel, bytes + y * pitch + x * size, size);
      if (pixel != first) { colored_frame = true; return; }
    }
  }
}
size_t audio(const int16_t* samples, size_t frames) {
  audio_frames += frames;
  if (verify_output) digest_bytes(audio_digest, samples, frames * 2 * sizeof(int16_t));
  for (size_t i = 0; i < frames * 2; ++i) nonzero_audio += samples[i] != 0;
  return frames;
}
void audio_sample(int16_t left, int16_t right) { const int16_t pair[]{left, right}; audio(pair, 1); }
void poll() {}
int16_t input(unsigned port, unsigned device, unsigned, unsigned id) {
  if (port || device != RETRO_DEVICE_JOYPAD) return 0;
  return std::any_of(input_pulses.begin(), input_pulses.end(), [id](InputPulse pulse) {
    return id == pulse.button && video_frames >= pulse.frame && video_frames < pulse.frame + 5;
  });
}
uint64_t cpu_nanos() {
  timespec value{};
  if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value)) throw std::runtime_error("clock_gettime failed");
  return uint64_t(value.tv_sec) * 1000000000ULL + value.tv_nsec;
}
template<class T> T symbol(void* library, const char* name) {
  auto value = dlsym(library, name);
  if (!value) throw std::runtime_error(std::string("Missing symbol: ") + name);
  return reinterpret_cast<T>(value);
}
}

int main(int argc, char** argv) {
  if (argc < 6) {
    std::fprintf(stderr, "Usage: %s core.so system_dir isolated_save_dir owner_rom warmup_frames [frame[:libretro_button_id] ...]\n", argv[0]);
    return 2;
  }
  try {
    verify_output = std::getenv("JGENESIS_BENCH_VERIFY") != nullptr;
    char* end = nullptr;
    const long warmup = std::strtol(argv[5], &end, 10);
    if (!end || *end || warmup < 0 || warmup > 36000) throw std::runtime_error("Invalid warmup");
    for (int i = 6; i < argc; ++i) {
      const long frame = std::strtol(argv[i], &end, 10);
      if (!end || end == argv[i] || frame < 0 || frame >= warmup) throw std::runtime_error("Invalid input frame");
      unsigned button = RETRO_DEVICE_ID_JOYPAD_START;
      if (*end == ':') {
        const char* start = end + 1;
        const long parsed = std::strtol(start, &end, 10);
        if (end == start || *end || parsed < 0 || parsed > RETRO_DEVICE_ID_JOYPAD_R3)
          throw std::runtime_error("Invalid libretro button id");
        button = static_cast<unsigned>(parsed);
      } else if (*end) throw std::runtime_error("Invalid input pulse");
      input_pulses.push_back({uint64_t(frame), button});
    }
    capture_at = uint64_t(warmup) + 360;
    system_directory = argv[2]; save_directory = argv[3];
    std::filesystem::create_directories(save_directory);
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) throw std::runtime_error(dlerror());
    symbol<void(*)(retro_environment_t)>(library, "retro_set_environment")(environment);
    symbol<void(*)(retro_video_refresh_t)>(library, "retro_set_video_refresh")(video);
    symbol<void(*)(retro_audio_sample_t)>(library, "retro_set_audio_sample")(audio_sample);
    symbol<void(*)(retro_audio_sample_batch_t)>(library, "retro_set_audio_sample_batch")(audio);
    symbol<void(*)(retro_input_poll_t)>(library, "retro_set_input_poll")(poll);
    symbol<void(*)(retro_input_state_t)>(library, "retro_set_input_state")(input);
    symbol<void(*)()>(library, "retro_init")();
    auto load = symbol<bool(*)(const retro_game_info*)>(library, "retro_load_game");
    auto run = symbol<void(*)()>(library, "retro_run");
    auto reset_counters = reinterpret_cast<void(*)()>(dlsym(library, "jgenesis_jit_reset_counters"));
    auto snapshot = reinterpret_cast<size_t(*)(char*, size_t)>(dlsym(library, "jgenesis_jit_snapshot_json"));
    const retro_game_info game{argv[4], nullptr, 0, nullptr};
    if (!load(&game)) {
      auto error = reinterpret_cast<const char*(*)()>(dlsym(library, "jgenesis_boot_error"));
      const char* message = error ? error() : nullptr;
      throw std::runtime_error(message ? message : "Game load failed");
    }
    // Restore an owner-supplied gameplay snapshot so renderer and CPU variants
    // can begin at exactly the same scene without replaying title screens.
    if (const char* path = std::getenv("JGENESIS_BENCH_STATE")) {
      const auto size = std::filesystem::file_size(path);
      if (!size || size > 128 * 1024 * 1024)
        throw std::runtime_error("Invalid initial state size");
      std::vector<uint8_t> initial(size);
      FILE* file = std::fopen(path, "rb");
      if (!file) throw std::runtime_error("Cannot open initial state");
      const auto read = std::fread(initial.data(), 1, initial.size(), file);
      std::fclose(file);
      if (read != initial.size() ||
          !symbol<bool(*)(const void*, size_t)>(library, "retro_unserialize")(initial.data(), initial.size()))
        throw std::runtime_error("Initial gameplay state restore failed");
    }
    for (long i = 0; i < warmup && !stopped; ++i) run();
    if (reset_counters) reset_counters();
    for (int sample = 0; sample < 3 && !stopped; ++sample) {
      const auto wall = std::chrono::steady_clock::now();
      const auto cpu = cpu_nanos();
      constexpr int frames = 120;
      for (int i = 0; i < frames && !stopped; ++i) run();
      const auto cpu_elapsed = cpu_nanos() - cpu;
      const double wall_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - wall).count();
      std::printf("sample=%d wall_ms_per_frame=%.4f cpu_ms_per_frame=%.4f unpaced_fps=%.2f\n",
                  sample, wall_ms / frames, cpu_elapsed / (frames * 1e6), frames * 1000.0 / wall_ms);
      std::fflush(stdout);
    }
    if (snapshot) {
      const size_t size = snapshot(nullptr, 0);
      if (!size || size > 32 * 1024 * 1024) throw std::runtime_error("Invalid JIT profile size");
      std::vector<char> profile(size);
      if (snapshot(profile.data(), profile.size()) != size || profile.back() != '\0')
        throw std::runtime_error("JIT profile changed unexpectedly");
      FILE* file = std::fopen((save_directory + "/jit-profile.json").c_str(), "wb");
      if (!file) throw std::runtime_error("Cannot save JIT profile");
      const auto written = std::fwrite(profile.data(), 1, size - 1, file);
      std::fclose(file);
      if (written != size - 1) throw std::runtime_error("JIT profile truncated");
    }
    if (!captured_rgb.empty()) {
      FILE* file = std::fopen((save_directory + "/benchmark.ppm").c_str(), "wb");
      if (!file) throw std::runtime_error("Cannot save benchmark scene");
      std::fprintf(file, "P6\n%u %u\n255\n", captured_width, captured_height);
      const auto written = std::fwrite(captured_rgb.data(), 1, captured_rgb.size(), file);
      std::fclose(file);
      if (written != captured_rgb.size()) throw std::runtime_error("Scene capture truncated");
    }
    const auto state_size = symbol<size_t(*)()>(library, "retro_serialize_size")();
    if (!state_size || state_size > 128 * 1024 * 1024) throw std::runtime_error("Invalid final state size");
    std::vector<uint8_t> state(state_size);
    if (!symbol<bool(*)(void*, size_t)>(library, "retro_serialize")(state.data(), state.size()))
      throw std::runtime_error("Final state serialization failed");
    FILE* state_file = std::fopen((save_directory + "/benchmark.rstate").c_str(), "wb");
    if (!state_file) throw std::runtime_error("Cannot save final state");
    const auto state_written = std::fwrite(state.data(), 1, state.size(), state_file);
    std::fclose(state_file);
    if (state_written != state.size()) throw std::runtime_error("Final state truncated");
    symbol<void(*)()>(library, "retro_unload_game")();
    symbol<void(*)()>(library, "retro_deinit")();
    dlclose(library);
    std::printf("video_frames=%llu nonuniform=%d audio_frames=%llu nonzero_samples=%llu stopped=%d\n",
      static_cast<unsigned long long>(video_frames), colored_frame,
      static_cast<unsigned long long>(audio_frames), static_cast<unsigned long long>(nonzero_audio), stopped);
    std::printf("verification=%d video_digest=%016llx audio_digest=%016llx state_bytes=%zu\n",
      verify_output, static_cast<unsigned long long>(video_digest),
      static_cast<unsigned long long>(audio_digest), state.size());
    return video_frames && colored_frame && audio_frames && nonzero_audio && !stopped ? 0 : 1;
  } catch (const std::exception& error) {
    std::fprintf(stderr, "Benchmark failed: %s\n", error.what()); return 1;
  }
}
