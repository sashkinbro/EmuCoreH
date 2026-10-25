// SPDX-License-Identifier: GPL-3.0-or-later
// Fresh GLES renderer + shared libretro CPU state successor comparison.
#include "libretro.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
static void check(bool ok, const char *message) {
  if (!ok) {
    std::fprintf(stderr, "FAIL: %s\n", message ? message : "unknown");
    std::exit(1);
  }
}
static std::string systemDir, saveDir;
static retro_hw_render_callback hardware{};
static GLuint fbo{}, color{}, depth{};
static unsigned frames = 0, glErrors = 0;
static size_t pcm = 0, nonzero = 0;
static unsigned errors(const char *phase) {
  unsigned count = 0;
  for (GLenum error; (error = glGetError()) != GL_NO_ERROR;) {
    std::fprintf(stderr, "GL_ERROR phase=%s code=0x%x\n", phase, error);
    ++count;
  }
  glErrors += count;
  return count;
}
static uintptr_t framebuffer() { return fbo; }
static retro_proc_address_t procedure(const char *name) {
  return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));
}
static bool environment(unsigned command, void *data) {
  switch (command) {
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    *static_cast<const char **>(data) = systemDir.c_str();
    return true;
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    *static_cast<const char **>(data) = saveDir.c_str();
    return true;
  case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
    *static_cast<unsigned *>(data) = RETRO_HW_CONTEXT_OPENGLES3;
    return true;
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    auto *var = static_cast<retro_variable *>(data);
    var->value = "1x";
    return true;
  }
  case RETRO_ENVIRONMENT_SET_HW_RENDER: {
    auto *cb = static_cast<retro_hw_render_callback *>(data);
    check(cb->context_type == RETRO_HW_CONTEXT_OPENGLES3,
          "GLES context requested");
    cb->get_current_framebuffer = framebuffer;
    cb->get_proc_address = procedure;
    hardware = *cb;
    return true;
  }
  case RETRO_ENVIRONMENT_SHUTDOWN:
    check(false, "core shutdown");
    return true;
  default:
    return false;
  }
}
static void video(const void *data, unsigned w, unsigned h, size_t) {
  check(data == RETRO_HW_FRAME_BUFFER_VALID && w == 496 && h == 384,
        "native GLES frame");
  ++frames;
}
static size_t audio(const int16_t *data, size_t count) {
  pcm += count;
  for (size_t i = 0; i < count * 2; ++i)
    nonzero += data[i] != 0;
  return count;
}
static int16_t input(unsigned, unsigned, unsigned, unsigned) { return 0; }
template <class T> T symbol(void *lib, const char *name) {
  auto fn = reinterpret_cast<T>(dlsym(lib, name));
  check(fn != nullptr, name);
  return fn;
}
int main(int argc, char **argv) {
  check(argc == 7, "usage: gles_state_compare core.so system_directory "
                   "owner.zip temporary-save state.rstate output.ppm");
  systemDir = argv[2];
  saveDir = argv[4];
  std::filesystem::create_directories(saveDir);
  EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  check(eglInitialize(display, nullptr, nullptr), "EGL initialize");
  const EGLint attributes[] = {EGL_SURFACE_TYPE,
                               EGL_PBUFFER_BIT,
                               EGL_RENDERABLE_TYPE,
                               0x40,
                               EGL_RED_SIZE,
                               8,
                               EGL_GREEN_SIZE,
                               8,
                               EGL_BLUE_SIZE,
                               8,
                               EGL_NONE};
  EGLConfig config{};
  EGLint count = 0;
  check(eglChooseConfig(display, attributes, &config, 1, &count) && count == 1,
        "GLES config");
  const EGLint ca[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE},
               sa[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ca);
  EGLSurface surface = eglCreatePbufferSurface(display, config, sa);
  check(eglMakeCurrent(display, surface, surface, context),
        "current GLES context");
  glGenFramebuffers(1, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glGenTextures(1, &color);
  glBindTexture(GL_TEXTURE_2D, color);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 496, 384, 0, GL_RGBA,
               GL_UNSIGNED_BYTE, nullptr);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         color, 0);
  glGenRenderbuffers(1, &depth);
  glBindRenderbuffer(GL_RENDERBUFFER, depth);
  glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, 496, 384);
  glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                            GL_RENDERBUFFER, depth);
  check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
        "comparison FBO complete");
  errors("FBO");
  void *lib = dlopen(argv[1], RTLD_NOW);
  check(lib != nullptr, dlerror());
  symbol<void (*)(retro_environment_t)>(lib,
                                        "retro_set_environment")(environment);
  symbol<void (*)(retro_video_refresh_t)>(lib,
                                          "retro_set_video_refresh")(video);
  symbol<void (*)(retro_audio_sample_batch_t)>(
      lib, "retro_set_audio_sample_batch")(audio);
  symbol<void (*)(retro_input_state_t)>(lib, "retro_set_input_state")(input);
  symbol<void (*)()>(lib, "retro_init")();
  retro_game_info game{};
  game.path = argv[3];
  check(
      symbol<bool (*)(const retro_game_info *)>(lib, "retro_load_game")(&game),
      symbol<const char *(*)()>(lib, "supermodel_boot_error")());
  hardware.context_reset();
  errors("renderer-init");
  std::ifstream file(argv[5], std::ios::binary | std::ios::ate);
  check(bool(file), "state file");
  auto size = file.tellg();
  check(size > 0 && size < 512 * 1024 * 1024, "state length");
  std::vector<unsigned char> state(static_cast<size_t>(size));
  file.seekg(0);
  check(bool(file.read(reinterpret_cast<char *>(state.data()), size)),
        "read state");
  check(symbol<bool (*)(const void *, size_t)>(lib, "retro_unserialize")(
            state.data(), state.size()),
        "shared state restore");
  errors("state-restore");
  auto start = std::chrono::steady_clock::now();
  symbol<void (*)()>(lib, "retro_run")();
  auto elapsed = std::chrono::duration<double, std::milli>(
                     std::chrono::steady_clock::now() - start)
                     .count();
  errors("successor-frame");
  check(frames == 1 && pcm > 0, "one successor frame and PCM");
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  std::vector<unsigned char> rgba(496 * 384 * 4);
  glReadPixels(0, 0, 496, 384, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
  check(errors("RGBA-readback") == 0, "RGBA comparison readback");
  FILE *output = std::fopen(argv[6], "wb");
  check(output != nullptr, "PPM output");
  std::fprintf(output, "P6\n496 384\n255\n");
  size_t lit = 0;
  for (int y = 383; y >= 0; --y)
    for (unsigned x = 0; x < 496; ++x) {
      auto *p = rgba.data() + (y * 496 + x) * 4;
      std::fwrite(p, 1, 3, output);
      lit += p[0] || p[1] || p[2];
    }
  std::fclose(output);
  std::printf(
      "SUCCESSOR nonblack=%zu PCM=%zu nonzero=%zu GL_errors=%u wall_ms=%.3f\n",
      lit, pcm, nonzero, glErrors, elapsed);
  hardware.context_destroy();
  symbol<void (*)()>(lib, "retro_unload_game")();
  symbol<void (*)()>(lib, "retro_deinit")();
  dlclose(lib);
  errors("destroy");
  glDeleteFramebuffers(1, &fbo);
  glDeleteTextures(1, &color);
  glDeleteRenderbuffers(1, &depth);
  eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  eglDestroySurface(display, surface);
  eglDestroyContext(display, context);
  eglTerminate(display);
  std::printf("PASS: shared CPU state GLES successor captured; GL_errors=%u\n",
              glErrors);
}
