// SPDX-License-Identifier: GPL-3.0-or-later
#include "state_safety.h"
#include <cassert>
#include <vector>
using namespace model3retro;
int main(){
  std::vector<unsigned char> mpeg(11321);auto p=mpeg.data();write32(p,1);write32(p+8,16128);
  write32(p+12,576);write32(p+18,1152);write32(p+22,346);write32(p+30,2);
  auto valid=[&](){return safe_state_block("MPEG Decoder",p,mpeg.size());};assert(valid());
  for(auto offset:{16u,17u}){p[offset]=2;assert(!valid());p[offset]=0;}
  for(auto field:{18u,22u,30u,46u,50u}){const auto old=read32(p+field);write32(p+field,UINT32_MAX);assert(!valid());write32(p+field,old);}
  write32(p+22,345);assert(!valid());write32(p+22,346);
  write32(p+4,576);write32(p+8,15552);write32(p+12,uint32_t(-576));assert(valid());
  write32(p+12,uint32_t(-577));assert(!valid());write32(p+12,0);
  write32(p+4,0x1000000);assert(!valid());write32(p+4,576);
  write32(p+569,0x7f800000);assert(!valid());write32(p+569,0);
  write32(p+4,UINT32_MAX);write32(p+8,0);p[17]=1;assert(valid());p[17]=0;assert(!valid());
  std::vector<unsigned char> audio(3220);write32(audio.data()+4,256);write32(audio.data()+8,4);
  for(const auto* name:{"DSB1 Audio","DSB2 Audio"}){
    assert(safe_state_block(name,audio.data(),audio.size()));
    write32(audio.data()+8,536);assert(!safe_state_block(name,audio.data(),audio.size()));write32(audio.data()+8,4);
    write32(audio.data(),257);assert(!safe_state_block(name,audio.data(),audio.size()));write32(audio.data(),0);
    assert(!safe_state_block(name,audio.data(),audio.size()-1));
  }
}
