#version 450
// EmuCoreR frontend post-processing fragment shader (Vulkan).
// Generated SPIR-V is checked in as shader_effects_spv.h; see regen_spirv.ps1.

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 o_color;

layout(set = 0, binding = 0) uniform sampler2D u_point;
layout(set = 0, binding = 1) uniform sampler2D u_linear;

layout(push_constant) uniform PushConstants {
    vec4 dst_rect;
    vec4 src_rect;
    vec2 out_size;
    float effect;
    float pad;
} pc;

const float PI = 3.14159265358979323846;

vec3 sample_sharp(vec2 uv) {
    vec2 tex_size = vec2(textureSize(u_point, 0));
    vec2 pixels = uv * tex_size;
    vec2 center = floor(pixels) + 0.5;
    vec2 out_px = max(pc.dst_rect.zw * pc.out_size, vec2(1.0));
    vec2 scale = max(out_px / max(tex_size, vec2(1.0)), vec2(1.0));
    vec2 offset = clamp((pixels - center) * scale, vec2(-0.5), vec2(0.5));
    return texture(u_linear, (center + offset) / tex_size).rgb;
}

vec3 apply_crt(vec2 uv, vec2 out_pix) {
    vec3 c = texture(u_linear, uv).rgb;
    float scan = 0.82 + 0.18 * cos(out_pix.y * PI);
    vec2 phase = mod(out_pix, vec2(3.0));
    vec3 mask = vec3(1.0);
    if (phase.x < 1.0)
        mask = vec3(1.22, 0.86, 0.86);
    else if (phase.x < 2.0)
        mask = vec3(0.86, 1.22, 0.86);
    else
        mask = vec3(0.86, 0.86, 1.22);
    return c * scan * mask;
}

vec3 apply_lcd(vec2 uv, vec2 out_pix) {
    vec3 c = texture(u_linear, uv).rgb;
    float phase = mod(out_pix.x, 3.0);
    vec3 mask = phase < 1.0 ? vec3(1.15, 0.4, 0.4) : (phase < 2.0 ? vec3(0.4, 1.15, 0.4) : vec3(0.4, 0.4, 1.15));
    float grid = mod(out_pix.y, 3.0) < 1.0 ? 1.0 : 0.62;
    return c * mask * grid;
}

void main() {
    int effect = int(pc.effect + 0.5);
    vec3 color;
    if (effect == 3) {
        color = sample_sharp(v_uv);
    } else if (effect == 1) {
        color = apply_crt(v_uv, gl_FragCoord.xy);
    } else if (effect == 2) {
        color = apply_lcd(v_uv, gl_FragCoord.xy);
    } else if (effect == 4) {
        color = texture(u_point, v_uv).rgb;
    } else {
        color = texture(u_linear, v_uv).rgb;
    }
    o_color = vec4(color, 1.0);
}
