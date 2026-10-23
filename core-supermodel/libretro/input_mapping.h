// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "libretro.h"
#include "adapter_helpers.h"
#include <array>
namespace model3retro {
// The shared Android frontend uses the legacy Cross=B, Circle=A, Square=Y,
// Triangle=X libretro ABI. Model 3's UI labels those controls A, B, X, Y.
inline constexpr std::array<unsigned,4> action_buttons{
  RETRO_DEVICE_ID_JOYPAD_B,RETRO_DEVICE_ID_JOYPAD_A,
  RETRO_DEVICE_ID_JOYPAD_Y,RETRO_DEVICE_ID_JOYPAD_X};
inline uint16_t pedal_input(int16_t analog,bool digital) {
  return analog>0?static_cast<uint16_t>((int(analog)*255+16383)/32767):digital?255:0;
}
}
