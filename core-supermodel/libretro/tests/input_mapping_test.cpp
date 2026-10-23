// SPDX-License-Identifier: GPL-3.0-or-later
#include "input_mapping.h"
#include <cassert>
int main() {
  using namespace model3retro;
  // Android A/Cross, B/Circle, X/Square, Y/Triangle keep the shared frontend ABI.
  assert(action_buttons[0]==RETRO_DEVICE_ID_JOYPAD_B);
  assert(action_buttons[1]==RETRO_DEVICE_ID_JOYPAD_A);
  assert(action_buttons[2]==RETRO_DEVICE_ID_JOYPAD_Y);
  assert(action_buttons[3]==RETRO_DEVICE_ID_JOYPAD_X);
  assert(pedal_input(0,false)==0 && pedal_input(0,true)==255);
  assert(pedal_input(16384,true)==128); // Partial pressure is never overridden by a digital press.
  for(int n=1;n<=255;n++) {
    auto analog=static_cast<int16_t>((n*32767+127)/255);
    assert(pedal_input(analog,false)==n && pedal_input(analog,true)==n);
  }
}
