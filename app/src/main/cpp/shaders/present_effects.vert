#version 450
// EmuCoreR frontend post-processing vertex shader (Vulkan).
// Generated SPIR-V is checked in as shader_effects_spv.h; see regen_spirv.ps1.

layout(location = 0) out vec2 v_uv;

layout(push_constant) uniform PushConstants {
    vec4 dst_rect;
    vec4 src_rect;
    vec2 out_size;
    float effect;
    float pad;
} pc;

void main() {
    vec2 uv = vec2(float(gl_VertexIndex & 1), float((gl_VertexIndex >> 1) & 1)) * 2.0;
    vec2 pos = pc.dst_rect.xy + uv * pc.dst_rect.zw;
    gl_Position = vec4(pos.x * 2.0 - 1.0, pos.y * 2.0 - 1.0, 0.0, 1.0);
    v_uv = pc.src_rect.xy + uv * pc.src_rect.zw;
}
