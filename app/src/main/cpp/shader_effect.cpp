#include "shader_effect.h"

#include <algorithm>
#include <atomic>
#include <android/log.h>

#ifdef NDEBUG
#define SHADER_LOGE(...) ((void)0)
#else
#define SHADER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmuCoreH-Shader", __VA_ARGS__)
#endif

namespace emucoreh::shader_effect {
namespace {

std::atomic<int> selected_effect{0};
GLuint program = 0;
GLuint vertex_array = 0;
bool program_failed = false;
GLint source_rect_uniform = -1;
GLint effect_uniform = -1;
GLint texture_uniform = -1;
GLint output_size_uniform = -1;
GLint source_size_uniform = -1;

constexpr const char* vertex_source = R"(#version 300 es
precision highp float;
uniform vec4 u_source_rect;
out vec2 v_uv;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
    v_uv = u_source_rect.xy + corner * u_source_rect.zw;
}
)";

constexpr const char* fragment_source = R"(#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 color_out;
uniform sampler2D u_texture;
uniform int u_effect;
uniform vec2 u_output_size;
uniform vec2 u_source_size;

vec3 sharp_bilinear(vec2 uv) {
    vec2 size = vec2(textureSize(u_texture, 0));
    vec2 pixel = uv * size;
    vec2 center = floor(pixel) + 0.5;
    vec2 scale = max(u_output_size / max(u_source_size, vec2(1.0)), vec2(1.0));
    vec2 offset = clamp((pixel - center) * scale, vec2(-0.5), vec2(0.5));
    return texture(u_texture, (center + offset) / size).rgb;
}

void main() {
    vec3 color = u_effect == 3 ? sharp_bilinear(v_uv) : texture(u_texture, v_uv).rgb;
    if (u_effect == 1) {
        float scan = 0.82 + 0.18 * cos(gl_FragCoord.y * 3.14159265);
        float phase = mod(gl_FragCoord.x, 3.0);
        vec3 mask = phase < 1.0 ? vec3(1.22, 0.86, 0.86)
                   : phase < 2.0 ? vec3(0.86, 1.22, 0.86)
                                 : vec3(0.86, 0.86, 1.22);
        color *= scan * mask;
    } else if (u_effect == 2) {
        float phase = mod(gl_FragCoord.x, 3.0);
        vec3 mask = phase < 1.0 ? vec3(1.15, 0.4, 0.4)
                   : phase < 2.0 ? vec3(0.4, 1.15, 0.4)
                                 : vec3(0.4, 0.4, 1.15);
        color *= mask * (mod(gl_FragCoord.y, 3.0) < 1.0 ? 1.0 : 0.62);
    }
    color_out = vec4(color, 1.0);
}
)";

GLuint Compile(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) return shader;
#ifndef NDEBUG
    char message[512]{};
    glGetShaderInfoLog(shader, sizeof(message), nullptr, message);
    SHADER_LOGE("Compile failed: %s", message);
#endif
    glDeleteShader(shader);
    return 0;
}

bool EnsureProgram() {
    if (program != 0) return true;
    if (program_failed) return false;
    const GLuint vertex = Compile(GL_VERTEX_SHADER, vertex_source);
    const GLuint fragment = Compile(GL_FRAGMENT_SHADER, fragment_source);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        program_failed = true;
        return false;
    }
    const GLuint compiled_program = glCreateProgram();
    glAttachShader(compiled_program, vertex);
    glAttachShader(compiled_program, fragment);
    glLinkProgram(compiled_program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(compiled_program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
#ifndef NDEBUG
        char message[512]{};
        glGetProgramInfoLog(compiled_program, sizeof(message), nullptr, message);
        SHADER_LOGE("Link failed: %s", message);
#endif
        glDeleteProgram(compiled_program);
        program_failed = true;
        return false;
    }
    program = compiled_program;
    source_rect_uniform = glGetUniformLocation(program, "u_source_rect");
    effect_uniform = glGetUniformLocation(program, "u_effect");
    texture_uniform = glGetUniformLocation(program, "u_texture");
    output_size_uniform = glGetUniformLocation(program, "u_output_size");
    source_size_uniform = glGetUniformLocation(program, "u_source_size");
    glGenVertexArrays(1, &vertex_array);
    return true;
}

}  // namespace

void Set(int effect) { selected_effect.store(std::clamp(effect, 0, 5)); }

bool Present(GLuint texture, int texture_width, int texture_height,
             int source_x, int source_y, int source_width, int source_height,
             int window_height,
             int destination_x, int destination_y, int destination_width, int destination_height) {
    const int effect = selected_effect.load();
    if (effect == 0 || texture == 0 || !EnsureProgram() ||
        texture_width <= 0 || texture_height <= 0 ||
        destination_width <= 0 || destination_height <= 0) return false;

    GLint old_program = 0, old_vertex_array = 0, old_active_texture = 0;
    GLint old_texture = 0, old_viewport[4]{};
    glGetIntegerv(GL_CURRENT_PROGRAM, &old_program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &old_vertex_array);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &old_active_texture);
    glGetIntegerv(GL_VIEWPORT, old_viewport);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_texture);
    const GLboolean old_scissor = glIsEnabled(GL_SCISSOR_TEST);
    const GLboolean old_blend = glIsEnabled(GL_BLEND);
    const GLboolean old_depth = glIsEnabled(GL_DEPTH_TEST);
    GLint old_scissor_box[4]{};
    glGetIntegerv(GL_SCISSOR_BOX, old_scissor_box);

    const int gl_y = window_height - destination_y - destination_height;
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glViewport(destination_x, gl_y, destination_width, destination_height);
    glEnable(GL_SCISSOR_TEST);
    glScissor(destination_x, gl_y, destination_width, destination_height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glUseProgram(program);
    glBindVertexArray(vertex_array);
    glBindTexture(GL_TEXTURE_2D, texture);
    const GLint filtering = effect == 4 ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);
    glUniform1i(texture_uniform, 0);
    glUniform1i(effect_uniform, effect);
    glUniform2f(output_size_uniform, static_cast<float>(destination_width),
                static_cast<float>(destination_height));
    glUniform2f(source_size_uniform, static_cast<float>(source_width),
                static_cast<float>(source_height));
    glUniform4f(source_rect_uniform,
                static_cast<float>(source_x) / texture_width,
                static_cast<float>(source_y) / texture_height,
                static_cast<float>(source_width) / texture_width,
                static_cast<float>(source_height) / texture_height);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(old_texture));
    glBindVertexArray(static_cast<GLuint>(old_vertex_array));
    glUseProgram(static_cast<GLuint>(old_program));
    glActiveTexture(static_cast<GLenum>(old_active_texture));
    glViewport(old_viewport[0], old_viewport[1], old_viewport[2], old_viewport[3]);
    glScissor(old_scissor_box[0], old_scissor_box[1], old_scissor_box[2], old_scissor_box[3]);
    if (!old_scissor) glDisable(GL_SCISSOR_TEST);
    if (old_blend) glEnable(GL_BLEND);
    if (old_depth) glEnable(GL_DEPTH_TEST);
    return true;
}

void Destroy() {
    if (vertex_array != 0) glDeleteVertexArrays(1, &vertex_array);
    if (program != 0) glDeleteProgram(program);
    vertex_array = 0;
    program = 0;
    program_failed = false;
}

}  // namespace emucoreh::shader_effect
