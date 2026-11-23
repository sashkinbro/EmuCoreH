#pragma once

#include "dc_renderer.h"
#include <algorithm>
#include <cmath>

namespace dcui {

inline constexpr float kPi = 3.14159265359f;
inline constexpr float kTwoPi = 6.28318530718f;

inline float Clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

inline float Lerp(float a, float b, float t) {
    return a + (b - a) * t;
}

inline RgbaColor LerpColor(const RgbaColor& a, const RgbaColor& b, float t) {
    return {
        Lerp(a.r, b.r, t),
        Lerp(a.g, b.g, t),
        Lerp(a.b, b.b, t),
        Lerp(a.a, b.a, t),
    };
}

inline float SmoothStep(float a, float b, float x) {
    float t = Clamp01((x - a) / std::max(0.0001f, b - a));
    return t * t * (3.0f - 2.0f * t);
}

inline float EaseOutCubic(float t) {
    t = Clamp01(t);
    float inv = 1.0f - t;
    return 1.0f - inv * inv * inv;
}

inline float EaseOutBack(float t) {
    t = Clamp01(t);
    const float c1 = 1.70158f;
    const float c3 = c1 + 1.0f;
    float u = t - 1.0f;
    return 1.0f + c3 * u * u * u + c1 * u * u;
}

} // namespace dcui
