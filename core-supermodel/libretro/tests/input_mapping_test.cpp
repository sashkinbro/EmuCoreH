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
  // Inputs.cpp's AnalogJoyY uses the default CAxisInput range: the "Up"
  // negative input produces 0, neutral is 0x80, and "Down" produces 0xFF.
  // Model3.cpp passes this value directly to ADC channel 0 for swtrilgy.
  assert(analog_joystick_y(-32768)==0);
  assert(analog_joystick_y(0)==0x80);
  assert(analog_joystick_y(32767)==0xFF);
  assert(analog_joystick_y(-16384)<0x80);
  assert(analog_joystick_y(16384)>0x80);
  // Original CGearShift4Input permits neutral and gives an up edge priority.
  assert(shifted_gear(1,false,true)==0);
  assert(shifted_gear(0,false,true)==0);
  assert(shifted_gear(0,true,false)==1);
  assert(shifted_gear(4,true,false)==4);
  assert(shifted_gear(2,true,true)==3);
  assert(shifted_gear(2,false,false)==2);
  for(uint32_t gear=0;gear<=4;++gear)assert(valid_gear(gear));
  assert(!valid_gear(5) && !valid_gear(UINT32_MAX));
  // Preserve one-controller play while allowing the second cabinet player's
  // own controller to override only its actively moved lever.
  auto onePad=magical_p2_input(-32768,true,0,false);
  assert(onePad.lever==255 && onePad.pedal==1);
  auto twoPads=magical_p2_input(-32768,false,32767,true);
  assert(twoPads.lever==0 && twoPads.pedal==1);
  auto independentPedal=magical_p2_input(0,false,0,true);
  assert(independentPedal.lever==128 && independentPedal.pedal==1);
  auto released=magical_p2_input(0,false,0,false);
  assert(released.lever==128 && released.pedal==0);
}
