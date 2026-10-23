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
inline uint16_t analog_joystick_y(int16_t y) {
  return axis_byte(y);
}
inline int shifted_gear(int gear,bool upEdge,bool downEdge) {
  if(upEdge)gear=std::min(gear+1,4);
  else if(downEdge)gear=std::max(gear-1,0);
  return gear;
}
inline bool valid_gear(uint32_t gear) { return gear<=4; }
struct MagicalP2Input { uint16_t lever,pedal; };
inline MagicalP2Input magical_p2_input(int16_t fallbackY,bool fallbackPedal,int16_t player2Y,bool player2Pedal) {
  // One pad can still operate both levers. An actively moved P2 lever takes
  // priority, while either pad can press the P2 pedal.
  return {axis_byte(player2Y!=0?player2Y:fallbackY,true),
          static_cast<uint16_t>(fallbackPedal||player2Pedal)};
}
}
