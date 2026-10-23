// SPDX-License-Identifier: GPL-3.0-or-later
#include "adapter_helpers.h"
#include <cassert>
#include <array>
int main() {
  using namespace model3retro;
  assert(axis_byte(-32768)==0 && axis_byte(0)==128 && axis_byte(32767)==255);
  assert(axis_byte(-32768,true)==255 && axis_byte(32767,true)==0);
  assert(axis_byte(0,true)==128);
  assert(gun_coord(-32768,150,651)==150 && gun_coord(32767,150,651)==651);
  assert(pedal_byte(-100)==0 && pedal_byte(32767)==255);
  assert(mix_channel(32767,32767)==32767 && mix_channel(-32768,-32768)==-32768);
  assert(mix_channel(30000,-30000)==0);
  std::array<unsigned char,128> buf{};
  unsigned char payload[] = {1,2,3,4};
  assert(pack_state(buf.data(),buf.size(),"daytona2",payload,4));
  const unsigned char* content=nullptr; size_t length=0;
  assert(unpack_state(buf.data(),buf.size(),"daytona2",content,length));
  assert(length==4 && content[2]==3);
  assert(!unpack_state(buf.data(),buf.size(),"vf3",content,length));
  assert(!unpack_state(buf.data(),12,"daytona2",content,length));
  buf[80]^=1; // payload corruption is rejected before passing data to legacy loaders.
  assert(!unpack_state(buf.data(),buf.size(),"daytona2",content,length));
  assert(!pack_state(buf.data(),80,"daytona2",payload,4));
  assert(!pack_state(buf.data(),max_state_size+1,"daytona2",payload,4));
  std::array<unsigned char,14> block{};
  write32(block.data(),14);write32(block.data()+4,1);write32(block.data()+8,1);
  assert(valid_blocks(block.data(),block.size()));
  assert(same_block_layout(block.data(),block.size(),block.data(),block.size()));
  auto other=block;other[12]=1;
  assert(!same_block_layout(block.data(),block.size(),other.data(),other.size()));
  write32(block.data(),0);assert(!valid_blocks(block.data(),block.size()));
  write32(block.data(),15);assert(!valid_blocks(block.data(),block.size()));
  write32(block.data(),14);block[12]=1;assert(!valid_blocks(block.data(),block.size()));
  assert(!valid_blocks(block.data(),4));
}
