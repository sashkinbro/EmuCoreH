#include "light_gun_input.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace emucoreh;
    LightGunState gun{0.5f, 0.5f, 0, false};
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_SCREEN_X) == 0);
    gun.x = 0; gun.y = 1;
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_SCREEN_X) == -32768);
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_SCREEN_Y) == 32767);
    gun.buttons = 5;
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_TRIGGER) == 1);
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_RELOAD) == 1);
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_AUX_A) == 0);
    gun.buttons = 0;
    assert(ReadLightGun(gun, 0xfffe, RETRO_DEVICE_ID_LIGHTGUN_SELECT) == 1);
    assert(ReadLightGun(gun, 0xfff7, RETRO_DEVICE_ID_LIGHTGUN_START) == 1);
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_TRIGGER) == 0);
    gun.buttons = 8;
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_SELECT) == 1);
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_TRIGGER) == 0);
    gun.offscreen = true;
    assert(ReadLightGun(gun, 0xffff, RETRO_DEVICE_ID_LIGHTGUN_IS_OFFSCREEN) == 1);
    auto cropped = MapVisibleGunAim(.5f, .5f, 640, 480, 64, 24, 0, 0);
    assert(cropped.first == .55f && cropped.second == .525f);
    std::cout << "Light-gun protocol and crop tests passed\n";
}
