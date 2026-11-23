#pragma once

namespace dcui {

inline constexpr const char* kQuadVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_color;

uniform vec2 u_screen;

out vec2 v_uv;
out vec4 v_color;

void main() {
    vec2 ndc = vec2(
        (a_pos.x / u_screen.x) * 2.0 - 1.0,
        1.0 - (a_pos.y / u_screen.y) * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    v_uv = a_uv;
    v_color = a_color;
}
)";

inline constexpr const char* kQuadFragmentShader = R"(#version 300 es
precision mediump float;

uniform sampler2D u_texture;

in vec2 v_uv;
in vec4 v_color;

out vec4 o_color;

void main() {
    vec4 tex = texture(u_texture, v_uv);
    o_color = tex * v_color;
}
)";

inline constexpr const char* kFullQuadVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
out vec2 v_uv;

void main() {
    v_uv = a_pos * 0.5 + 0.5;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// Dreamcast BIOS Luminous Atmospheric Backdrop + Floating Refractive Glass Orbs
inline constexpr const char* kDcBgFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;
uniform vec2 u_screen;
uniform int u_theme;
uniform float u_time;
uniform vec4 u_spheres[6]; // xy = center virtual px, z = radius, w = 1/radius

out vec4 o_color;

const vec3 kLightDir = normalize(vec3(-0.32, 0.44, 0.84));
const vec3 kHalfVec = normalize(vec3(-0.18, 0.24, 0.95));

float hash21(vec2 p) {
    // Wrap screen coordinates before large multiplications to stay numerically well-conditioned
    p = mod(p, 64.0);
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Analytic translucent Dreamcast crystal sphere with early squared-distance reject
vec4 evalGlassSphere(vec2 px, vec4 sphere, vec3 tintCol, vec3 rimCol, vec3 specCol, float isLightBg) {
    vec2 d = px - sphere.xy;
    float d2 = dot(d, d);
    float radius = sphere.z;
    float aa = 1.4;
    float maxR = radius + aa;
    if (d2 >= maxR * maxR) {
        return vec4(0.0);
    }
    float dist = sqrt(d2);
    float mask = 1.0 - smoothstep(radius - aa, maxR, dist);
    if (mask <= 0.002) {
        return vec4(0.0);
    }
    vec2 nxy = clamp(d * sphere.w, -1.0, 1.0);
    float r2 = dot(nxy, nxy);
    float nz = sqrt(max(0.0001, 1.0 - r2));
    vec3 normal = vec3(nxy.x, -nxy.y, nz);

    float ndotl = max(dot(normal, kLightDir), 0.0);
    float nh = max(dot(normal, kHalfVec), 0.0);
    float nh2 = nh * nh;
    float nh4 = nh2 * nh2;
    float nh16 = nh4 * nh4 * nh4 * nh4;
    float oneMinusNz = max(0.0, 1.0 - nz);
    float fresnel = oneMinusNz * oneMinusNz;

    // Translucent glass body: bright caustic bottom, luminous upper highlight
    float caustic = clamp(0.45 + 0.55 * nxy.y, 0.0, 1.0);
    vec3 col = mix(tintCol * (0.55 + 0.45 * ndotl), rimCol, fresnel * 0.65);
    col += rimCol * (caustic * caustic * 0.24);
    col += specCol * (nh16 * 0.72 + nh4 * 0.14);

    // Subtle soft ambient rim shadow on light backgrounds for 3D pop
    float alpha = mix(0.28, 0.68, fresnel * 0.72 + nh16 * 0.45);
    if (isLightBg > 0.5) {
        alpha = mix(0.32, 0.74, fresnel * 0.75 + nh16 * 0.40);
    }
    return vec4(col, mask * alpha);
}

void main() {
    vec2 px = vec2(v_uv.x * u_screen.x, (1.0 - v_uv.y) * u_screen.y);
    vec2 norm = (px / max(u_screen, vec2(1.0))) - 0.5;
    vec2 normScaled = norm * vec2(1.15, 1.35);
    float rad2 = dot(normScaled, normScaled);
    float rad = sqrt(rad2);
    float bloom = exp(-rad2 * 2.1);

    vec3 bg = vec3(0.0);
    vec3 orbTint = vec3(0.42, 0.72, 0.98);
    vec3 orbRim = vec3(0.82, 0.94, 1.0);
    vec3 orbSpec = vec3(1.0);
    float isLight = 0.0;

    // Gentle Dreamcast BIOS sweeping light ribbon across backdrop
    float wavePhase = v_uv.x * 3.8 + u_time * 0.18;
    float waveY = 0.52 + 0.14 * sin(wavePhase) + 0.05 * cos(wavePhase * 2.1);
    float wy = (v_uv.y - waveY) * 4.2;
    float ribbon = exp(-(wy * wy));

    if (u_theme == 0) {
        // Theme 0: Dreamcast Pearl (classic luminous white-ice BIOS)
        isLight = 1.0;
        vec3 outerIce = vec3(0.79, 0.85, 0.94);
        vec3 midPearl = vec3(0.90, 0.94, 0.985);
        vec3 centerGlow = vec3(0.975, 0.985, 1.0);
        bg = mix(outerIce, midPearl, smoothstep(1.1, 0.15, rad));
        bg = mix(bg, centerGlow, bloom * 0.88);
        bg += vec3(0.03, 0.045, 0.07) * ribbon;
        orbTint = vec3(0.38, 0.68, 0.96);
        orbRim = vec3(0.95, 0.52, 0.34);
    } else if (u_theme == 1) {
        // Theme 1: Dreamcast PAL Blue (European BIOS cobalt-sky frost)
        isLight = 1.0;
        vec3 outerSky = vec3(0.56, 0.73, 0.94);
        vec3 midSky = vec3(0.76, 0.86, 0.98);
        vec3 innerFrost = vec3(0.92, 0.965, 1.0);
        bg = mix(outerSky, midSky, smoothstep(1.1, 0.15, rad));
        bg = mix(bg, innerFrost, bloom * 0.85);
        bg += vec3(0.05, 0.07, 0.10) * ribbon;
        orbTint = vec3(0.18, 0.48, 0.92);
        orbRim = vec3(0.52, 0.84, 1.0);
    } else if (u_theme == 2) {
        // Theme 2: Sega Sports Black (deep onyx metallic + vermilion glow)
        vec3 outerOnyx = vec3(0.032, 0.042, 0.068);
        vec3 innerSteel = vec3(0.088, 0.118, 0.178);
        bg = mix(outerOnyx, innerSteel, bloom * 0.92);
        bg += vec3(0.085, 0.025, 0.014) * ribbon;
        orbTint = vec3(0.24, 0.30, 0.42);
        orbRim = vec3(0.96, 0.36, 0.18);
    } else if (u_theme == 3) {
        // Theme 3: Katana Devkit (deep teal-navy + clean engineering grid)
        vec3 outerNavy = vec3(0.018, 0.055, 0.098);
        vec3 innerTeal = vec3(0.055, 0.155, 0.245);
        bg = mix(outerNavy, innerTeal, bloom * 0.90);
        vec2 gp = px * 0.022;
        vec2 cell = abs(fract(gp) - 0.5);
        float grid = 1.0 - smoothstep(0.465, 0.495, max(cell.x, cell.y));
        bg += vec3(0.018, 0.075, 0.11) * grid * (0.35 + 0.65 * bloom);
        bg += vec3(0.015, 0.075, 0.11) * ribbon;
        orbTint = vec3(0.08, 0.54, 0.74);
        orbRim = vec3(0.28, 0.92, 1.0);
    } else {
        // Theme 4: Saturn Midnight (cosmic sapphire-indigo)
        vec3 outerSpace = vec3(0.020, 0.026, 0.075);
        vec3 innerIndigo = vec3(0.075, 0.115, 0.285);
        bg = mix(outerSpace, innerIndigo, bloom * 0.92);
        bg += vec3(0.035, 0.065, 0.16) * ribbon;
        orbTint = vec3(0.22, 0.44, 0.92);
        orbRim = vec3(0.62, 0.82, 1.0);
    }

    // Subtle geometric Dreamcast BIOS horizon hairline arcs
    float arcDist = abs(length((v_uv - vec2(0.5, 1.18)) * vec2(1.0, 1.55)) - 0.74);
    float arcLine = exp(-arcDist * 190.0) * (isLight > 0.5 ? 0.07 : 0.10);
    bg += (isLight > 0.5 ? vec3(0.35, 0.58, 0.90) : vec3(0.45, 0.72, 1.0)) * arcLine;

    // 6 translucent crystal spheres back-to-front
    for (int i = 0; i < 6; ++i) {
        vec4 s = evalGlassSphere(px, u_spheres[i], orbTint, orbRim, orbSpec, isLight);
        bg = mix(bg, s.rgb, s.a);
    }

    // Dither removes 8-bit gradient banding on large 10-bit/8-bit tablet displays
    float dither = (hash21(gl_FragCoord.xy) - 0.5) / 255.0;
    o_color = vec4(clamp(bg + dither, 0.0, 1.0), 1.0);
}
)";

// Analytic Dreamcast Logarithmic Spiral Swirl Shader
inline constexpr const char* kRectVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_pos;

uniform vec2 u_screen;
uniform vec4 u_rect;

out vec2 v_uv;

void main() {
    vec2 uv = vec2(a_pos.x * 0.5 + 0.5, 0.5 - a_pos.y * 0.5);
    vec2 pos = u_rect.xy + uv * u_rect.zw;
    vec2 ndc = vec2(
        (pos.x / u_screen.x) * 2.0 - 1.0,
        1.0 - (pos.y / u_screen.y) * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    v_uv = uv;
}
)";

inline constexpr const char* kDcSwirlFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;

uniform float u_progress; // 0..1 unspool progress
uniform float u_rot;      // rotation angle rad
uniform float u_alpha;
uniform vec4 u_color;     // rgb = swirl color

out vec4 o_color;

const float kTwoPi = 6.283185307;
const float kMaxTheta = 16.4; // ~2.61 turns matching authentic Dreamcast spiral

void main() {
    vec2 p = v_uv * 2.0 - 1.0;
    float r = length(p);
    if (r > 1.04) discard;

    float endTheta = clamp(u_progress, 0.0, 1.0) * kMaxTheta;
    if (endTheta <= 0.02) discard;

    float ang = atan(p.y, p.x) - u_rot;
    float phi = mod(ang, kTwoPi);

    float minDist = 10.0;
    float bestNorm = 0.0;

    // 3 full turns cover all spiral intersections analytically
    for (int k = 0; k < 3; ++k) {
        float th = phi + float(k) * kTwoPi;
        if (th <= endTheta + 0.35) {
            float thClamped = clamp(th, 0.0, endTheta);
            float tNorm = thClamped / kMaxTheta;
            float rCurve = 0.03 + 0.84 * pow(tNorm, 1.06);
            float halfW = mix(0.020, 0.058, tNorm);
            // Smooth rounded tip at outer end
            if (th > endTheta) {
                float over = (th - endTheta) * rCurve;
                float d = length(vec2(r - rCurve, over)) - halfW * 0.85;
                if (d < minDist) {
                    minDist = d;
                    bestNorm = tNorm;
                }
            } else {
                float d = abs(r - rCurve) - halfW;
                if (d < minDist) {
                    minDist = d;
                    bestNorm = tNorm;
                }
            }
        }
    }

    float aa = 0.016;
    float coreMask = 1.0 - smoothstep(-aa, aa, minDist);
    float halo = exp(-max(minDist, 0.0) * 14.0) * 0.22;
    float totalA = clamp(coreMask + halo, 0.0, 1.0);
    if (totalA <= 0.004) discard;

    // Subtle specular highlight along spiral core + brighter center
    vec3 highlight = mix(u_color.rgb, vec3(1.0), 0.35 * (1.0 - bestNorm));
    vec3 col = mix(u_color.rgb * 1.1, highlight, coreMask);
    o_color = vec4(col, totalA * u_color.a * u_alpha);
}
)";

// Frosted Glass Acrylic Panel / Pill / Card Shader
inline constexpr const char* kDcPanelFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;

uniform vec2 u_size;
uniform float u_radius;
uniform float u_border_px;
uniform vec4 u_top_col;
uniform vec4 u_bot_col;
uniform vec4 u_border_col;
uniform vec4 u_glow_col; // xyz = glow color, w = glow strength
uniform float u_shimmer; // < 0 off, >= 0 phase 0..1

out vec4 o_color;

float sdRoundedBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 px = v_uv * u_size;
    vec2 center = u_size * 0.5;
    float r = clamp(u_radius, 0.0, min(center.x, center.y) - 0.5);
    float d = sdRoundedBox(px - center, center, r);

    float aa = 1.0;
    float outerAlpha = 1.0 - smoothstep(-aa, aa, d);
    if (outerAlpha <= 0.002) {
        discard;
    }

    vec4 fill = mix(u_top_col, u_bot_col, v_uv.y);

    // Dreamcast frosted glass top lip highlight
    float glassTop = (1.0 - smoothstep(0.0, 0.34, v_uv.y)) * 0.14;
    fill.rgb += vec3(glassTop);

    // Optional soft radial inner aura
    if (u_glow_col.w > 0.002) {
        vec2 ell = (v_uv - vec2(0.5, 0.45)) * vec2(1.4, 1.9);
        float glow = exp(-dot(ell, ell) * 2.1);
        fill.rgb = mix(fill.rgb, u_glow_col.rgb, glow * u_glow_col.w);
    }

    // Subtle moving crystal gleam for focused pills/orbs
    if (u_shimmer >= 0.0) {
        float diag = (v_uv.x * 0.72 + v_uv.y * 0.28) - u_shimmer;
        float gleam = exp(-diag * diag * 32.0) * 0.12;
        fill.rgb += vec3(gleam);
    }

    if (u_border_px > 0.05) {
        float innerMask = 1.0 - smoothstep(-u_border_px - aa, -u_border_px + aa, d);
        vec3 col = mix(u_border_col.rgb, fill.rgb, innerMask);
        float alpha = mix(u_border_col.a, fill.a, innerMask) * outerAlpha;
        o_color = vec4(col, alpha);
    } else {
        o_color = vec4(fill.rgb, fill.a * outerAlpha);
    }
}
)";

// Procedural 3D-shaded Sega / Dreamcast Icons (GD-ROM, VMU, Crystal, Gear, Cartridge, Arcade, Droplet)
inline constexpr const char* kDcIconFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;

uniform int u_kind;      // 0=GD-ROM, 1=VMU, 2=SaveCrystal, 3=Gear, 4=Cartridge, 5=Arcade, 6=Droplet
uniform float u_angle;   // rotation/phase
uniform float u_alpha;
uniform vec4 u_accent;
uniform int u_light_ui;

out vec4 o_color;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 p = v_uv * 2.0 - 1.0;
    vec4 res = vec4(0.0);

    if (u_kind == 0) {
        // 0: Spinning Sega GD-ROM Optical Disc
        float r = length(p);
        float aa = 0.02;
        if (r > 0.96 || r < 0.14) discard;
        float mask = (1.0 - smoothstep(0.96 - aa, 0.96, r)) * smoothstep(0.14, 0.14 + aa, r);
        float ang = atan(p.y, p.x) + u_angle;
        vec3 col = vec3(0.84, 0.89, 0.95);
        if (r > 0.90) {
            col = vec3(0.65, 0.72, 0.82);
        } else if (r > 0.36) {
            // Luminous silver-ice GD-ROM with cyan/orange diffraction cones
            float c1 = pow(max(0.0, cos(ang * 2.0)), 6.0);
            float c2 = pow(max(0.0, cos(ang * 2.0 + 1.57)), 7.0);
            float rings = 0.035 * sin(r * 130.0);
            col = vec3(0.82, 0.88, 0.96) + vec3(rings);
            col = mix(col, u_accent.rgb, c1 * 0.42);
            col += vec3(0.22, 0.72, 1.0) * c2 * 0.38;
            // High-density GD-ROM outer track separation at r=0.58
            float gdRing = exp(-pow((r - 0.58) * 65.0, 2.0)) * 0.22;
            col -= vec3(gdRing);
        } else if (r > 0.24) {
            col = vec3(0.62, 0.70, 0.82) * (0.85 + 0.15 * cos(ang * 2.0));
        } else {
            col = vec3(0.92, 0.96, 1.0);
        }
        float rimLine = exp(-pow((r - 0.36) * 80.0, 2.0)) * 0.32;
        col += vec3(0.35, 0.55, 0.95) * rimLine;
        res = vec4(col, mask);
    } else if (u_kind == 1) {
        // 1: Dreamcast Visual Memory Unit (VMU)
        float bodyD = sdRoundBox(p, vec2(0.56, 0.78), 0.14);
        float capD = sdRoundBox(p - vec2(0.0, -0.74), vec2(0.46, 0.12), 0.06);
        float d = min(bodyD, capD);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);

        vec3 shellCol = (u_light_ui > 0)
            ? mix(vec3(0.94, 0.97, 1.0), vec3(0.76, 0.84, 0.95), v_uv.y)
            : mix(vec3(0.85, 0.91, 0.98), vec3(0.54, 0.65, 0.80), v_uv.y);
        float border = 1.0 - smoothstep(-0.06, -0.02, d);
        vec3 col = mix(u_accent.rgb * 0.75 + vec3(0.25), shellCol, border);

        // Teal monochrome LCD screen
        float lcdD = sdRoundBox(p - vec2(0.0, -0.22), vec2(0.38, 0.28), 0.04);
        if (lcdD < 0.03) {
            float lcdMask = 1.0 - smoothstep(-0.015, 0.015, lcdD);
            vec3 lcdBg = vec3(0.56, 0.76, 0.65);
            // Tiny animated pixel wave inside LCD
            vec2 luv = (p - vec2(0.0, -0.22)) / vec2(0.34, 0.24);
            float lr = length(luv);
            float lang = atan(luv.y, luv.x) - u_angle * 1.4;
            float miniSwirl = smoothstep(0.08, 0.02, abs(lr - 0.15 - 0.45 * fract(lang / 6.28318)) - 0.06);
            vec3 lcdFg = mix(lcdBg, vec3(0.10, 0.24, 0.22), miniSwirl * step(lr, 0.72));
            col = mix(col, lcdFg, lcdMask);
        }

        // Mini D-pad left & A/B buttons right
        vec2 dp = p - vec2(-0.20, 0.34);
        float dpad = min(sdRoundBox(dp, vec2(0.14, 0.045), 0.01), sdRoundBox(dp, vec2(0.045, 0.14), 0.01));
        float btnA = length(p - vec2(0.18, 0.39)) - 0.065;
        float btnB = length(p - vec2(0.32, 0.28)) - 0.065;
        float ctrl = 1.0 - smoothstep(-0.015, 0.015, min(dpad, min(btnA, btnB)));
        col = mix(col, mix(vec3(0.28, 0.38, 0.56), u_accent.rgb, 0.5), ctrl);
        res = vec4(col, mask);
    } else if (u_kind == 2) {
        // 2: Luminous Quick-Save Memory Crystal
        vec2 q = abs(p);
        float hex = max(q.x * 0.866025 + q.y * 0.5, q.y) - 0.76;
        if (hex > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, hex);
        float facet = 0.65 + 0.35 * cos(atan(p.y, p.x) * 3.0 + u_angle * 0.5);
        float innerRing = exp(-pow((length(p) - 0.34) * 16.0, 2.0));
        vec3 col = mix(u_accent.rgb * 0.7, vec3(0.55, 0.88, 1.0), facet * 0.55);
        col += vec3(0.45, 0.75, 1.0) * innerRing * 0.65;
        col += vec3(0.25) * pow(max(0.0, 1.0 - length(p - vec2(-0.25, -0.25))), 3.0);
        res = vec4(col, mask);
    } else if (u_kind == 3) {
        // 3: Precision Dreamcast Settings Gear
        float r = length(p);
        float ang = atan(p.y, p.x) + u_angle * 0.45;
        float teeth = 0.095 * smoothstep(-0.25, 0.35, cos(ang * 8.0));
        float outer = r - (0.68 + teeth);
        float hole = 0.25 - r;
        float d = max(outer, hole);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        float sheen = 0.72 + 0.28 * dot(normalize(p + 0.001), vec2(-0.6, -0.8));
        vec3 col = mix(vec3(0.45, 0.65, 0.92), u_accent.rgb, 0.42) * sheen;
        float ring = exp(-pow((r - 0.44) * 28.0, 2.0)) * 0.35;
        col += vec3(ring);
        res = vec4(col, mask);
    } else if (u_kind == 4) {
        // 4: Classic Sega Cartridge (Genesis / 32X / SMS / GG)
        float body = sdRoundBox(p - vec2(0.0, 0.05), vec2(0.68, 0.64), 0.08);
        float topArc = sdRoundBox(p - vec2(0.0, -0.52), vec2(0.54, 0.22), 0.16);
        float d = min(body, topArc);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        vec3 col = mix(vec3(0.16, 0.19, 0.25), vec3(0.26, 0.31, 0.40), 1.0 - v_uv.y);
        float label = sdRoundBox(p - vec2(0.0, 0.08), vec2(0.48, 0.38), 0.04);
        float lblMask = 1.0 - smoothstep(-0.015, 0.015, label);
        vec3 lblCol = mix(u_accent.rgb, vec3(0.95, 0.97, 1.0), 0.25 * (1.0 - v_uv.y));
        col = mix(col, lblCol, lblMask);
        float stripe = exp(-pow((p.y + 0.48) * 30.0, 2.0)) * 0.35;
        col += vec3(stripe);
        res = vec4(col, mask);
    } else if (u_kind == 5) {
        // 5: Sega Arcade / NAOMI / Model 3 Hardware Module
        float d = sdRoundBox(p, vec2(0.76, 0.58), 0.09);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        vec3 col = mix(vec3(0.13, 0.17, 0.26), vec3(0.22, 0.28, 0.40), 1.0 - v_uv.y);
        float band = sdRoundBox(p - vec2(0.0, -0.22), vec2(0.62, 0.18), 0.03);
        col = mix(col, u_accent.rgb, 1.0 - smoothstep(-0.015, 0.015, band));
        float led1 = 1.0 - smoothstep(0.04, 0.06, length(p - vec2(-0.45, 0.26)));
        float led2 = 1.0 - smoothstep(0.04, 0.06, length(p - vec2(-0.26, 0.26)));
        col = mix(col, vec3(0.25, 1.0, 0.65), led1);
        col = mix(col, u_accent.rgb * 1.2, led2);
        res = vec4(col, mask);
    } else {
        // 6: Luminous Dreamcast Water Droplet
        float r = length(p);
        if (r > 0.98) discard;
        float mask = 1.0 - smoothstep(0.92, 0.98, r);
        vec3 n = vec3(p.x, -p.y, sqrt(max(0.001, 1.0 - r * r)));
        float spec = pow(max(0.0, dot(n, normalize(vec3(-0.35, 0.45, 0.82)))), 22.0);
        float rim = pow(max(0.0, 1.0 - n.z), 2.2);
        vec3 col = mix(u_accent.rgb * 0.85 + vec3(0.15), vec3(1.0), rim * 0.45 + spec * 0.92);
        res = vec4(col, mask * (0.78 + 0.22 * rim));
    }

    o_color = vec4(res.rgb, res.a * u_alpha);
}
)";

// Subtle VGA / CRT Scanlines Overlay
inline constexpr const char* kDcScanlineFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;
uniform vec2 u_viewport_px;
uniform float u_strength;

out vec4 o_color;

void main() {
    float yPx = v_uv.y * u_viewport_px.y;
    float line = 0.5 - 0.5 * cos(yPx * 3.14159265 * 0.65);
    float dark = line * u_strength;
    vec2 d = v_uv - 0.5;
    float vig = dot(d, d) * u_strength * 0.65;
    float alpha = clamp(dark + vig, 0.0, 0.45);
    o_color = vec4(0.0, 0.01, 0.04, alpha);
}
)";

} // namespace dcui
