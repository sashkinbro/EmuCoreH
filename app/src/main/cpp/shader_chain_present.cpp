#include "shader_chain_present.h"

#include "shader_chain.h"

#include <EGL/egl.h>
#include <android/log.h>
#include <cstdint>
#include <string>

#ifdef NDEBUG
#define SHADER_LOGE(...) ((void)0)
#else
#define SHADER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmuCoreH-Shader", __VA_ARGS__)
#endif

#if defined(EMUCOREA_HAVE_LIBRASHADER)
#include <librashader.h>
#endif

namespace emucoreh::shader_chain_present {

#if defined(EMUCOREA_HAVE_LIBRASHADER)
namespace {

struct State {
    libra_gl_filter_chain_t chain = nullptr;
    std::string preset;
    uint64_t generation = 0;
    bool failed = false;
    uint64_t frame_count = 0;
    GLuint input_texture = 0;
    GLuint input_fbo = 0;
    GLsizei input_width = 0;
    GLsizei input_height = 0;
    GLuint target_texture = 0;
    GLuint target_fbo = 0;
    GLsizei target_width = 0;
    GLsizei target_height = 0;
};

State state;

const void* GlLoader(const char* name) {
    return reinterpret_cast<const void*>(eglGetProcAddress(name));
}

void ReportError(const char* operation, libra_error_t error) {
#ifdef NDEBUG
    (void)operation;
    libra_error_free(&error);
#else
    char* message = nullptr;
    if (libra_error_write(error, &message) == 0 && message != nullptr) {
        SHADER_LOGE("%s: %s", operation, message);
        libra_error_free_string(&message);
    } else {
        SHADER_LOGE("%s: error %d", operation,
                            static_cast<int>(libra_error_errno(error)));
    }
    libra_error_free(&error);
#endif
}

bool EnsureTexture(GLuint* texture, GLuint* fbo, GLsizei* current_width, GLsizei* current_height,
                   GLsizei width, GLsizei height) {
    if (width <= 0 || height <= 0) return false;
    if (*texture != 0 && *current_width == width && *current_height == height) return true;
    if (*fbo != 0) glDeleteFramebuffers(1, fbo);
    if (*texture != 0) glDeleteTextures(1, texture);
    *fbo = 0;
    *texture = 0;

    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *texture, 0);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        SHADER_LOGE("FBO incomplete: 0x%x", status);
        glDeleteFramebuffers(1, fbo);
        glDeleteTextures(1, texture);
        *fbo = 0;
        *texture = 0;
        return false;
    }
    *current_width = width;
    *current_height = height;
    return true;
}

bool EnsureChain() {
    const std::string path = emucoreh::shader_chain::PresetPath();
    if (path.empty() || !emucoreh::shader_chain::IsEnabled()) return false;
    const uint64_t generation = emucoreh::shader_chain::Generation();
    if (state.chain != nullptr && state.preset == path && state.generation == generation) return true;
    if (state.failed && state.preset == path && state.generation == generation) return false;

    Destroy();
    state.preset = path;
    state.generation = generation;
    libra_shader_preset_t preset = nullptr;
    if (libra_error_t error = libra_preset_create(path.c_str(), &preset)) {
        ReportError("Preset load", error);
        state.failed = true;
        return false;
    }
    filter_chain_gl_opt_t options{};
    options.version = LIBRASHADER_CURRENT_VERSION;
    options.use_dsa = false;
    options.force_no_mipmaps = false;
    options.disable_cache = false;
    if (libra_error_t error = libra_gl_filter_chain_create(&preset, &GlLoader, &options, &state.chain)) {
        ReportError("Chain create", error);
        state.failed = true;
        return false;
    }
    return true;
}

void RestoreState() {
    // librashader uses samplers and multiple texture units. PPSSPP's next
    // frame starts with its own GL state, so clear bindings that would leak.
    for (GLuint unit = 0; unit < 16; ++unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindSampler(unit, 0);
    }
    glActiveTexture(GL_TEXTURE0);
    glUseProgram(0);
    glBindVertexArray(0);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

}  // namespace
#endif

bool Present(GLuint source_fbo, int source_x, int source_y, int source_width, int source_height,
             int window_width, int window_height,
             int destination_x, int destination_y, int destination_width, int destination_height) {
#if defined(EMUCOREA_HAVE_LIBRASHADER)
    if (!emucoreh::shader_chain::IsEnabled() || !EnsureChain()) return false;
    if (!EnsureTexture(&state.input_texture, &state.input_fbo, &state.input_width,
                       &state.input_height, source_width, source_height) ||
        !EnsureTexture(&state.target_texture, &state.target_fbo, &state.target_width,
                       &state.target_height, destination_width, destination_height)) return false;

    glBindFramebuffer(GL_READ_FRAMEBUFFER, source_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.input_fbo);
    glDisable(GL_SCISSOR_TEST);
    glBlitFramebuffer(source_x, source_y, source_x + source_width, source_y + source_height,
                      0, 0, source_width, source_height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    const libra_image_gl_t input = {state.input_texture, GL_RGBA8,
                                    static_cast<uint32_t>(source_width),
                                    static_cast<uint32_t>(source_height)};
    const libra_image_gl_t output = {state.target_texture, GL_RGBA8,
                                     static_cast<uint32_t>(destination_width),
                                     static_cast<uint32_t>(destination_height)};
    const libra_viewport_t viewport = {0.0f, 0.0f,
                                       static_cast<uint32_t>(destination_width),
                                       static_cast<uint32_t>(destination_height)};
    libra_error_t error = libra_gl_filter_chain_frame(&state.chain, state.frame_count,
                                                       input, output, &viewport, nullptr, nullptr);
    RestoreState();
    if (error) {
        ReportError("Frame", error);
        state.failed = true;
        return false;
    }
    ++state.frame_count;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, state.target_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glViewport(0, 0, window_width, window_height);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    const int bottom = window_height - destination_y - destination_height;
    glBlitFramebuffer(0, 0, destination_width, destination_height,
                      destination_x, bottom, destination_x + destination_width,
                      bottom + destination_height, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
#else
    (void)source_fbo; (void)source_x; (void)source_y; (void)source_width; (void)source_height;
    (void)window_width; (void)window_height; (void)destination_x; (void)destination_y;
    (void)destination_width; (void)destination_height;
    return false;
#endif
}

void Destroy() {
#if defined(EMUCOREA_HAVE_LIBRASHADER)
    if (state.chain != nullptr) libra_gl_filter_chain_free(&state.chain);
    if (state.input_fbo != 0) glDeleteFramebuffers(1, &state.input_fbo);
    if (state.input_texture != 0) glDeleteTextures(1, &state.input_texture);
    if (state.target_fbo != 0) glDeleteFramebuffers(1, &state.target_fbo);
    if (state.target_texture != 0) glDeleteTextures(1, &state.target_texture);
    state = State{};
#endif
}

}  // namespace emucoreh::shader_chain_present
