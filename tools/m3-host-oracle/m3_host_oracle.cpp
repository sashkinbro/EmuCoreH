// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-3.0-or-later
// Host-side Model 3 (Supermodel) regression oracle and benchmark.
//
// Builds the Android core sources (with __ANDROID__ so the same code paths run) and drives
// CModel3 directly, headless: no GL context, no Vulkan. The Real3D scene is still built on the
// CPU by New3D through a SceneConsumer (exactly like the device Vulkan path), and that scene is
// hashed instead of drawn. Every K frames the full Supermodel savestate (PPC registers, RAM,
// sound board, Real3D, ...) is hashed together with the scene, the 2D tile layers and audio.
//
// Usage: m3-host-oracle --rom <zip> --games-xml <Games.xml> --frames N [--every K]
//          [--input "from-to:mask,..."] [--label name] [--json out.json] [--dump-state file]
//          [--no-scene-hash] [--profile samples.txt]
// The input masks use the app pad bits (tools/oracle/suites.sh). ms_per_frame counts only
// CModel3::RunFrame time minus the time spent hashing the scene inside the consumer.
#include "adapter_helpers.h"
#include "input_mapping.h"
#include "resolution.h"
#include "Supermodel.h"
#include "Model3/Model3.h"
#include "GameLoader.h"
#include "BlockFile.h"
#include "Inputs/Inputs.h"
#include "Inputs/InputTypes.h"
#include "Graphics/New3D/New3D.h"
#include "Graphics/Render2D.h"
#include "OSD/Outputs.h"
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#include <atomic>
#include <thread>
#include <unordered_map>
#endif

extern bool g_model3FixedRtc;
namespace fs = std::filesystem;
using namespace model3retro;
using Clock = std::chrono::steady_clock;

namespace {
// ---- hashing (FNV-1a 64 over 8-byte words where possible; deterministic, endian-fixed) ----
struct Hash {
  uint64_t h = 14695981039346656037ULL;
  void bytes(const void* p, size_t n) {
    auto* b = static_cast<const unsigned char*>(p);
    for (size_t i = 0; i < n; i++) h = (h ^ b[i]) * 1099511628211ULL;
  }
  template <class T> void pod(const T& v) { bytes(&v, sizeof(v)); }
  void f(float v) { uint32_t u; std::memcpy(&u, &v, 4); if (trace) std::fprintf(trace, "f %08x\n", u); pod(u); }
  void fs(const float* v, size_t n) { for (size_t i = 0; i < n; i++) f(v[i]); }
  void i(int64_t v) { if (trace) std::fprintf(trace, "i %lld\n", static_cast<long long>(v)); pod(v); }
  FILE* trace = nullptr;  // M3_SCENE_TRACE=<frame>: text dump of one frame's scene for diffing
};
// Faster bulk hash for multi-megabyte savestates: 4 interleaved FNV lanes over 64-bit words.
uint64_t bulk_hash(const unsigned char* p, size_t n) {
  uint64_t lane[4] = {14695981039346656037ULL, 1099511628211ULL, 0x9E3779B97F4A7C15ULL, 0xC2B2AE3D27D4EB4FULL};
  size_t i = 0;
  for (; i + 32 <= n; i += 32)
    for (int k = 0; k < 4; k++) { uint64_t w; std::memcpy(&w, p + i + 8 * k, 8); lane[k] = (lane[k] ^ w) * 0x100000001B3ULL; lane[k] ^= lane[k] >> 29; }
  Hash t; t.pod(lane); t.bytes(p + i, n - i); t.i(static_cast<int64_t>(n));
  return t.h;
}
std::string hex64(uint64_t v) { char b[17]; std::snprintf(b, sizeof b, "%016llx", static_cast<unsigned long long>(v)); return b; }

double ms_since(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }

// ---- scene consumer: hashes the CPU-built Real3D scene (what the device Vulkan path draws) ----
struct HashConsumer final : New3D::SceneConsumer {
  bool enabled = true;
  Hash scene;          // running hash since the last checkpoint
  uint64_t meshes = 0, vertices = 0;
  double hash_ms = 0;  // time spent hashing, subtracted from frame time
  int frame_no = 0, trace_frame = -1;
  std::array<float, 4> Render(const New3D::SceneFrame& frame) override {
    if (!enabled) return {0, 0, 0, 0};
    const auto start = Clock::now();
    Hash& h = scene;
    if (frame_no == trace_frame) h.trace = std::fopen(("scene-trace-" + std::to_string(frame_no) + ".txt").c_str(), "w");
    h.i(frame.width); h.i(frame.height);
    for (auto& z : frame.zRanges) h.fs(z.data(), 2);
    h.i(static_cast<int64_t>(frame.nodes.size()));
    for (const auto& node : frame.nodes) {
      const auto& v = node.viewport;
      h.i(v.vpX); h.i(v.vpY); h.i(v.vpWidth); h.i(v.vpHeight);
      h.f(v.angle_left); h.f(v.angle_right); h.f(v.angle_top); h.f(v.angle_bottom);
      h.fs(v.projectionMatrix.currentMatrix, 16); h.fs(v.lightingParams, 6);
      h.i(v.sunClamp); h.i(v.intensityClamp); h.fs(v.spotEllipse, 4); h.fs(v.spotRange, 2); h.fs(v.spotColor, 3);
      h.fs(v.fogParams, 7); h.f(v.scrollFog); h.i(v.losPosX); h.i(v.losPosY); h.i(v.x); h.i(v.y);
      h.i(v.width); h.i(v.height); h.i(v.priority); h.i(v.select); h.i(v.number); h.fs(v.spotFogColor, 3);
      h.f(v.scrollAtt); h.i(v.hardwareStep);
      h.i(static_cast<int64_t>(node.models.size()));
      for (const auto& model : node.models) {
        h.i(model.dynamic); h.i(model.textureOffsetX); h.i(model.textureOffsetY); h.i(model.page);
        h.fs(model.modelMat, 16); h.f(model.scale);
        if (!model.meshes) { h.i(-1); continue; }
        h.i(static_cast<int64_t>(model.meshes->size()));
        for (const auto& m : *model.meshes) {
          meshes++;
          h.i(m.format); h.i(m.x); h.i(m.y); h.i(m.width); h.i(m.height); h.i(m.wrapModeU); h.i(m.wrapModeV);
          h.i(m.inverted); h.i(m.microTexture); h.i(m.microTextureID); h.f(m.microTextureScale);
          h.i(m.textured); h.i(m.polyAlpha); h.i(m.textureAlpha); h.i(m.alphaTest); h.i(m.layered);
          h.i(m.highPriority); h.i(m.transLSelect); h.i(m.translatorMap); h.i(m.fixedShading);
          h.i(m.lighting); h.i(m.specular); h.f(m.shininess); h.f(m.specularValue); h.f(m.fogIntensity);
          h.i(m.vertexCount);
          const auto verts = frame.Vertices(model, m);
          vertices += verts.count;
          for (size_t k = 0; k < verts.count; k++) {
            const auto& fv = verts.data[k];
            h.fs(fv.pos, 4); h.fs(fv.normal, 3); h.fs(fv.texcoords, 2); h.f(fv.fixedShade);
            h.fs(fv.faceNormal, 3); h.bytes(fv.faceColour, 4);
          }
        }
      }
    }
    if (h.trace) { std::fclose(h.trace); h.trace = nullptr; }
    hash_ms += ms_since(start);
    return {0, 0, 0, 0};
  }
  void InvalidateTextures(unsigned level, unsigned x, unsigned y, unsigned w, unsigned hgt) override {
    if (!enabled) return;
    scene.i(0x7E57); scene.i(level); scene.i(x); scene.i(y); scene.i(w); scene.i(hgt);
  }
};

class NullOutputs final : public COutputs {
 public:
  bool Initialize() override { return true; }
  void Attached() override {}
 protected:
  void SendOutput(EOutputs, UINT8, UINT8) override {}
};

Hash g_audio;
std::vector<int16_t> g_pcm;
Game::AudioTypes g_audio_layout = Game::STEREO_LR;

// Mirrors libretro.cpp: the same config the Android core uses (single-threaded, sound + DSB).
void configure(Util::Config::Node& config) {
  config.Set("MultiThreaded", false); config.Set("GPUMultiThreaded", false);
  config.Set("EmulateSound", true); config.Set("EmulateDSB", true);
  config.Set("Balance", "0"); config.Set("BalanceLeftRight", "0"); config.Set("BalanceFrontRear", "0");
  config.Set("NbSoundChannels", "4"); config.Set("SoundFreq", "57.6");
  config.Set("SoundVolume", "100"); config.Set("MusicVolume", "150");
  config.Set("LegacySoundDSP", false); config.Set("New3DEngine", true); config.Set("New3DAccurate", false);
  config.Set("AndroidForceNew3D", true); config.Set("AndroidTileBlit", true); config.Set("AndroidSimpleShader", false);
  config.Set("AndroidClampRamVerts", true); config.Set("QuadRendering", false); config.Set("FlipStereo", false);
  config.Set("PowerPCFrequency", "50"); config.Set("InputSystem", "libretro");
  config.Set("Outputs", "none"); config.Set("ForceFeedback", false); config.Set("Network", false); config.Set("SimulateNet", true);
  config.Set("XResolution", "496"); config.Set("YResolution", "384"); config.Set("Throttle", false);
  config.Set("RefreshRate", "60.0"); config.Set("Crosshairs", 0);
}

// App pad mask (pressed = 1, see native_bridge.cpp RetroInputState) -> libretro joypad bits.
uint32_t g_mask = 0;
bool pad(unsigned id) {
  auto p = [](int bit) { return (g_mask >> bit) & 1u; };
  switch (id) {
    case RETRO_DEVICE_ID_JOYPAD_UP: return p(4);
    case RETRO_DEVICE_ID_JOYPAD_DOWN: return p(6);
    case RETRO_DEVICE_ID_JOYPAD_LEFT: return p(7);
    case RETRO_DEVICE_ID_JOYPAD_RIGHT: return p(5);
    case RETRO_DEVICE_ID_JOYPAD_X: return p(12);
    case RETRO_DEVICE_ID_JOYPAD_A: return p(13);
    case RETRO_DEVICE_ID_JOYPAD_B: return p(14);
    case RETRO_DEVICE_ID_JOYPAD_Y: return p(15);
    case RETRO_DEVICE_ID_JOYPAD_L: return p(10);
    case RETRO_DEVICE_ID_JOYPAD_R: return p(11);
    case RETRO_DEVICE_ID_JOYPAD_L2: return p(8);
    case RETRO_DEVICE_ID_JOYPAD_R2: return p(9);
    case RETRO_DEVICE_ID_JOYPAD_L3: return p(1);
    case RETRO_DEVICE_ID_JOYPAD_R3: return p(2);
    case RETRO_DEVICE_ID_JOYPAD_START: return p(3);
    case RETRO_DEVICE_ID_JOYPAD_SELECT: return p(0);
    default: return false;
  }
}
void value(CInput* input, uint16_t n) { input->prevValue = input->value; input->value = n; }
// Subset of libretro.cpp poll_inputs() for player 1 with centred analog sticks and no
// analog trigger pressure (what the device oracle feeds), player 2 idle.
void poll_inputs(CInputs& in, int& gear) {
  for (unsigned p = 0; p < 2; p++) {
    auto b = [p](unsigned id) { return p == 0 && pad(id); };
    value(in.coin[p], b(RETRO_DEVICE_ID_JOYPAD_SELECT)); value(in.start[p], b(RETRO_DEVICE_ID_JOYPAD_START));
    value(in.service[p], b(RETRO_DEVICE_ID_JOYPAD_L3)); value(in.test[p], b(RETRO_DEVICE_ID_JOYPAD_R3));
    value(in.up[p], b(RETRO_DEVICE_ID_JOYPAD_UP)); value(in.down[p], b(RETRO_DEVICE_ID_JOYPAD_DOWN));
    value(in.left[p], b(RETRO_DEVICE_ID_JOYPAD_LEFT)); value(in.right[p], b(RETRO_DEVICE_ID_JOYPAD_RIGHT));
    const bool a = b(action_buttons[0]), bb = b(action_buttons[1]), c = b(action_buttons[2]), d = b(action_buttons[3]);
    value(in.punch[p], a); value(in.kick[p], bb); value(in.guard[p], c); value(in.escape[p], d);
    value(in.shortPass[p], a); value(in.longPass[p], bb); value(in.shoot[p], c);
    value(in.gunX[p], gun_coord(0, 150, 651)); value(in.gunY[p], gun_coord(0, 80, 465));
    value(in.trigger[p], a || bb); in.trigger[p]->offscreenValue = bb;
    value(in.analogGunX[p], axis_byte(0)); value(in.analogGunY[p], axis_byte(0, true));
    value(in.analogTriggerLeft[p], a); value(in.analogTriggerRight[p], bb);
    if (p == 0) {
      const int16_t steering = b(RETRO_DEVICE_ID_JOYPAD_LEFT) ? -32768 : b(RETRO_DEVICE_ID_JOYPAD_RIGHT) ? 32767 : 0;
      value(in.steering, axis_byte(steering));
      value(in.accelerator, pedal_input(0, b(RETRO_DEVICE_ID_JOYPAD_R2)));
      value(in.brake, pedal_input(0, b(RETRO_DEVICE_ID_JOYPAD_L2)));
      value(in.gearShiftUp, b(RETRO_DEVICE_ID_JOYPAD_R)); value(in.gearShiftDown, b(RETRO_DEVICE_ID_JOYPAD_L));
      gear = shifted_gear(gear, in.gearShiftUp->value && !in.gearShiftUp->prevValue, in.gearShiftDown->value && !in.gearShiftDown->prevValue);
      value(in.gearShift4, static_cast<uint16_t>(gear));
      value(in.viewChange, a); value(in.handBrake, bb); value(in.rearBrake, bb ? 255 : 0); value(in.musicSelect, c);
      for (unsigned v = 0; v < 4; v++) value(in.vr[v], b(action_buttons[v]));
      value(in.shift, a); value(in.beat, bb); value(in.charge, c); value(in.jump, d);
      value(in.analogJoyX, axis_byte(0)); value(in.analogJoyY, analog_joystick_y(0));
      value(in.analogJoyTrigger1, a); value(in.analogJoyTrigger2, bb); value(in.analogJoyEvent1, c); value(in.analogJoyEvent2, d);
    }
  }
}

struct Range { int from, to; uint32_t mask; };
std::vector<Range> parse_input(const std::string& spec) {
  std::vector<Range> out;
  size_t pos = 0;
  while (pos < spec.size()) {
    size_t end = spec.find(',', pos); if (end == std::string::npos) end = spec.size();
    const std::string part = spec.substr(pos, end - pos); pos = end + 1;
    const size_t colon = part.find(':'); if (colon == std::string::npos) continue;
    const std::string range = part.substr(0, colon);
    const size_t dash = range.find('-');
    Range r{};
    r.from = std::stoi(range.substr(0, dash));
    r.to = dash == std::string::npos ? r.from : std::stoi(range.substr(dash + 1));
    r.mask = static_cast<uint32_t>(std::stoul(part.substr(colon + 1)));
    out.push_back(r);
  }
  return out;
}

std::vector<unsigned char> read_all(const fs::path& path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f) return {};
  std::vector<unsigned char> data(static_cast<size_t>(f.tellg()));
  f.seekg(0); f.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()));
  return data;
}

// Normalizes a CBlockFile image (block: u32 total, u32 name len, u32 comment len, name,
// comment, data) to name+data per block. Comments carry __FILE__, an absolute build path that
// differs between a baseline tree and the candidate, so they are dropped.
std::vector<unsigned char> normalize_state(const std::vector<unsigned char>& data, bool print) {
  std::vector<unsigned char> out;
  out.reserve(data.size());
  size_t pos = 0;
  while (pos + 12 <= data.size()) {
    uint32_t total, name_len, comment_len;
    std::memcpy(&total, &data[pos], 4); std::memcpy(&name_len, &data[pos + 4], 4); std::memcpy(&comment_len, &data[pos + 8], 4);
    const uint64_t offset = 12ull + name_len + comment_len;
    if (total < offset || pos + total > data.size()) { std::fprintf(stderr, "malformed state block at %zu\n", pos); std::exit(4); }
    out.insert(out.end(), data.begin() + pos + 12, data.begin() + pos + 12 + name_len);
    out.insert(out.end(), data.begin() + pos + offset, data.begin() + pos + total);
    if (print)
      std::printf("BLOCK %-28s bytes=%-8llu hash=%s\n", reinterpret_cast<const char*>(&data[pos + 12]),
        static_cast<unsigned long long>(total - offset), hex64(bulk_hash(&data[pos + offset], total - offset)).c_str());
    pos += total;
  }
  return out;
}

#ifdef _WIN32
// Minimal sampling profiler (same idea as tools/jg-host-oracle): suspend the emulation thread
// at ~4 kHz and record RIP relative to the image base.
struct Sampler {
  std::atomic<bool> stop{false};
  std::unordered_map<uint64_t, uint64_t> hist;
  std::thread worker;
  void start() {
    HANDLE target;
    DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &target, 0, FALSE, DUPLICATE_SAME_ACCESS);
    const uint64_t base = reinterpret_cast<uint64_t>(GetModuleHandleW(nullptr));
    worker = std::thread([this, target, base] {
      CONTEXT ctx;
      while (!stop.load(std::memory_order_relaxed)) {
        const auto until = Clock::now() + std::chrono::microseconds(250);
        while (Clock::now() < until) {}
        if (SuspendThread(target) == static_cast<DWORD>(-1)) continue;
        ctx.ContextFlags = CONTEXT_CONTROL;
        if (GetThreadContext(target, &ctx)) hist[ctx.Rip - base]++;
        ResumeThread(target);
      }
    });
  }
  void finish(const std::string& path) {
    stop = true; worker.join();
    FILE* f = std::fopen(path.c_str(), "w");
    if (!f) return;
    for (auto& [rip, n] : hist) std::fprintf(f, "%llx %llu\n", static_cast<unsigned long long>(rip), static_cast<unsigned long long>(n));
    std::fclose(f);
  }
};
#endif
}  // namespace

// Hooks required by libretro/platform.cpp.
void model3_log(int, const char* message) {
  if (std::getenv("M3_ORACLE_LOG")) std::fprintf(stderr, "%s", message);
}
bool model3_audio(unsigned n, INT16* lf, INT16* rf, INT16* lr, INT16* rr, bool flip) {
  if (n > 8192) return true;
  g_pcm.resize(size_t(n) * 2);
  const auto layout = g_audio_layout;
  if (layout == Game::STEREO_RL || layout == Game::QUAD_1_FRL_2_RRL || layout == Game::QUAD_1_RRL_2_FRL) flip = !flip;
  for (unsigned i = 0; i < n; i++) {
    auto l = mix_channel(lf ? lf[i] : 0, lr ? lr[i] : 0), r = mix_channel(rf ? rf[i] : 0, rr ? rr[i] : 0);
    if (layout == Game::MONO) l = r = mix_channel(l, r);
    g_pcm[i * 2] = flip ? r : l; g_pcm[i * 2 + 1] = flip ? l : r;
  }
  g_audio.bytes(g_pcm.data(), g_pcm.size() * 2);
  return true;
}
unsigned SupermodelFrontendFramebuffer() { return 0; }

int main(int argc, char** argv) {
  std::string rom, xml, label = "m3", json, input, dump, profile;
  int frames = 600, every = 300;
  bool scene_hash = true;
  for (int i = 1; i < argc; i++) {
    const std::string a = argv[i];
    auto next = [&]() -> std::string { if (i + 1 >= argc) { std::fprintf(stderr, "missing value for %s\n", a.c_str()); std::exit(64); } return argv[++i]; };
    if (a == "--rom") rom = next();
    else if (a == "--games-xml") xml = next();
    else if (a == "--frames") frames = std::stoi(next());
    else if (a == "--every") every = std::stoi(next());
    else if (a == "--input") input = next();
    else if (a == "--label") label = next();
    else if (a == "--json") json = next();
    else if (a == "--dump-state") dump = next();
    else if (a == "--profile") profile = next();
    else if (a == "--no-scene-hash") scene_hash = false;
    else { std::fprintf(stderr, "unknown argument %s\n", a.c_str()); return 64; }
  }
  if (rom.empty() || xml.empty()) { std::fprintf(stderr, "usage: %s --rom zip --games-xml Games.xml [--frames N] ...\n", argv[0]); return 64; }
#ifdef _WIN32
  if (const char* cpu = std::getenv("M3_PIN_CPU")) {
    SetThreadAffinityMask(GetCurrentThread(), DWORD_PTR(1) << std::atoi(cpu));
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
  }
#endif
  if (every <= 0) every = frames;
  const auto script = parse_input(input);
  const fs::path temp = fs::temp_directory_path() / ("m3-host-oracle-" + std::to_string(
#ifdef _WIN32
    GetCurrentProcessId()
#else
    0
#endif
    ) + ".state");

  const auto load_start = Clock::now();
  g_model3FixedRtc = true;
  Util::Config::Node config{"Global"};
  configure(config);
  CInputs inputs{nullptr};
  NullOutputs outputs;
  CRender2D render2d{config};
  render2d.Init(0, 0, Resolution::native_width, Resolution::native_height, Resolution::native_width, Resolution::native_height);
  HashConsumer consumer;
  consumer.enabled = scene_hash;
  if (const char* t = std::getenv("M3_SCENE_TRACE")) consumer.trace_frame = std::atoi(t);
  Game game; ROMSet roms;
  GameLoader loader(xml);
  if (loader.Load(&game, &roms, rom, "")) { std::printf("ORACLE_ERROR label=%s boot failed: ROM_SET\n", label.c_str()); return 2; }
  g_audio_layout = game.audio;
  if (game.driveboard_type == Game::DRIVE_BOARD_SKI) config.Set("EmulateSkiBoard", true);
  auto model = std::make_unique<CModel3>(config);
  if (model->Init() != OKAY || model->LoadGame(game, roms) != OKAY) { std::printf("ORACLE_ERROR label=%s boot failed: INIT\n", label.c_str()); return 2; }
  outputs.SetGame(game);
  model->AttachInputs(&inputs); model->AttachOutputs(&outputs);
  auto renderer = std::make_unique<New3D::CNew3D>(config, game.name, &consumer);
  const auto geometry = Resolution().Geometry();
  if (renderer->Init(0, 0, geometry.base_width, geometry.base_height, geometry.base_width, geometry.base_height) != OKAY) { std::printf("ORACLE_ERROR label=%s boot failed: NEW3D\n", label.c_str()); return 2; }
  model->AttachRenderers(&render2d, renderer.get());
  CRender2D::SetAndroidTileBlit(nullptr);
  model->Reset();
  int gear = 1;
  const double load_ms = ms_since(load_start);

#ifdef _WIN32
  std::unique_ptr<Sampler> sampler;
  if (!profile.empty()) { sampler = std::make_unique<Sampler>(); sampler->start(); }
#endif
  struct Checkpoint { int frame; std::string state, scene, tiles, audio; size_t bytes; double ms; };
  std::vector<Checkpoint> checkpoints;
  Hash all, tiles;
  double total = 0, segment = 0;
  std::vector<unsigned char> last_state;
  for (int frame = 1; frame <= frames; frame++) {
    g_mask = 0;
    for (const auto& r : script) if (frame >= r.from && frame <= r.to) { g_mask = r.mask; break; }
    poll_inputs(inputs, gear);
    const double hashed_before = consumer.hash_ms;
    const auto start = Clock::now();
    consumer.frame_no = frame;
    model->RunFrame();
    const double elapsed = ms_since(start) - (consumer.hash_ms - hashed_before);
    total += elapsed; segment += elapsed;
    // 2D layers the device consumer composites (bottom frame + optional top surface).
    if (scene_hash) {
      const unsigned w = render2d.GetFrameWidth(), h = render2d.GetFrameHeight();
      tiles.i(w); tiles.i(h);
      if (render2d.HasFrame()) tiles.pod(bulk_hash(reinterpret_cast<const unsigned char*>(render2d.GetFrameBufferARGB()), size_t(w) * h * 4));
      if (render2d.HasTopSurface()) tiles.pod(bulk_hash(reinterpret_cast<const unsigned char*>(render2d.GetTopSurfaceARGB()), size_t(w) * h * 4));
    }
    if (frame % every == 0 || frame == frames) {
      CBlockFile file;
      if (file.Create(temp.string(), "Supermodel Save State", "Model3 host oracle") != OKAY) { std::fprintf(stderr, "cannot create %s\n", temp.string().c_str()); return 3; }
      model->SaveState(&file);
      file.Close();
      last_state = normalize_state(read_all(temp), frame == frames);
      const uint64_t state = bulk_hash(last_state.data(), last_state.size());
      const int seg_frames = frame % every == 0 ? every : frame % every;
      const double ms = segment / seg_frames;
      Checkpoint c{frame, hex64(state), hex64(consumer.scene.h), hex64(tiles.h), hex64(g_audio.h), last_state.size(), ms};
      std::printf("ORACLE label=%s frame=%d state=%s scene=%s tiles=%s audio=%s state_bytes=%zu meshes=%llu verts=%llu ms_per_frame=%.4f\n",
        label.c_str(), frame, c.state.c_str(), c.scene.c_str(), c.tiles.c_str(), c.audio.c_str(), c.bytes,
        static_cast<unsigned long long>(consumer.meshes), static_cast<unsigned long long>(consumer.vertices), ms);
      std::fflush(stdout);
      const std::string line = std::to_string(frame) + ":" + c.state + ":" + c.scene + ":" + c.tiles + ":" + c.audio + "\n";
      all.bytes(line.data(), line.size());
      checkpoints.push_back(c);
      segment = 0;
    }
  }
#ifdef _WIN32
  if (sampler) sampler->finish(profile);
#endif
  std::error_code ec; fs::remove(temp, ec);
  if (!dump.empty()) { std::ofstream f(dump, std::ios::binary); f.write(reinterpret_cast<const char*>(last_state.data()), static_cast<std::streamsize>(last_state.size())); }
  const double ms_per_frame = total / frames;
  std::printf("ORACLE_SUMMARY label=%s frames=%d digest=%s run_ms=%.1f ms_per_frame=%.4f load_ms=%.1f\n",
    label.c_str(), frames, hex64(all.h).c_str(), total, ms_per_frame, load_ms);
  if (!json.empty()) {
    std::ofstream f(json);
    f << "{\"label\":\"" << label << "\",\"frames\":" << frames << ",\"every\":" << every << ",\"input\":\"" << input
      << "\",\"digest\":\"" << hex64(all.h) << "\",\"run_ms\":" << total << ",\"ms_per_frame\":" << ms_per_frame
      << ",\"load_ms\":" << load_ms << ",\"checkpoints\":[";
    for (size_t k = 0; k < checkpoints.size(); k++) {
      const auto& c = checkpoints[k];
      f << (k ? "," : "") << "{\"frame\":" << c.frame << ",\"state\":\"" << c.state << "\",\"scene\":\"" << c.scene
        << "\",\"tiles\":\"" << c.tiles << "\",\"audio\":\"" << c.audio << "\",\"state_bytes\":" << c.bytes << ",\"ms_per_frame\":" << c.ms << "}";
    }
    f << "]}\n";
  }
  model.reset();
  return 0;
}
