#pragma once

#include "libretro.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <utility>

namespace emucoreh {
struct LightGunState {
    float x = .5f;
    float y = .5f;
    unsigned buttons = 0; // trigger, auxiliary A, reload, coin, test, service
    bool offscreen = false;
};

inline int16_t GunAxis(float value) {
    if (!std::isfinite(value)) return 0;
    return static_cast<int16_t>(std::clamp(std::lround(value * 65536.0f - 32768.0f), -32768L, 32767L));
}

inline int16_t ReadLightGun(const LightGunState& gun, uint16_t pad, unsigned id) {
    auto pressed = [pad](int bit) { return (pad & (1u << bit)) == 0; };
    switch (id) {
        case RETRO_DEVICE_ID_LIGHTGUN_SCREEN_X: return GunAxis(gun.x);
        case RETRO_DEVICE_ID_LIGHTGUN_SCREEN_Y: return GunAxis(gun.y);
        case RETRO_DEVICE_ID_LIGHTGUN_IS_OFFSCREEN: return gun.offscreen;
        case RETRO_DEVICE_ID_LIGHTGUN_TRIGGER: return (gun.buttons & 1) != 0 || pressed(14) || pressed(9);
        case RETRO_DEVICE_ID_LIGHTGUN_AUX_A: return (gun.buttons & 2) != 0 || pressed(13);
        case RETRO_DEVICE_ID_LIGHTGUN_AUX_B: return pressed(15);
        case RETRO_DEVICE_ID_LIGHTGUN_AUX_C: return pressed(12);
        case RETRO_DEVICE_ID_LIGHTGUN_RELOAD: return (gun.buttons & 4) != 0;
        case RETRO_DEVICE_ID_LIGHTGUN_START: return pressed(3);
        case RETRO_DEVICE_ID_LIGHTGUN_SELECT: return pressed(0) || (gun.buttons & 8) != 0;
        case RETRO_DEVICE_ID_LIGHTGUN_DPAD_UP: return pressed(4);
        case RETRO_DEVICE_ID_LIGHTGUN_DPAD_DOWN: return pressed(6);
        case RETRO_DEVICE_ID_LIGHTGUN_DPAD_LEFT: return pressed(7);
        case RETRO_DEVICE_ID_LIGHTGUN_DPAD_RIGHT: return pressed(5);
        default: return 0;
    }
}

// Aim is relative to the visible image; the core expects its original framebuffer.
inline std::pair<float, float> MapVisibleGunAim(float x, float y, int width, int height,
                                               int left, int top, int right, int bottom) {
    if (width > 0 && height > 0 && left + right < width && top + bottom < height) {
        x = (left + x * (width - left - right)) / width;
        y = (top + y * (height - top - bottom)) / height;
    }
    return {x, y};
}
} // namespace emucoreh
