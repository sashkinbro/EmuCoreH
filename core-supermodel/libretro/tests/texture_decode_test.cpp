// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/TextureDecode.h"
#include <array>
#include <cassert>
int main(){
  using namespace New3D;
  assert((DecodeTexel(0x7c00,0)==std::array<UINT8,4>{255,0,0,255}));
  assert((DecodeTexel(0xfc00,0)==std::array<UINT8,4>{255,0,0,0}));
  assert((DecodeTexel(0x1234,7)==std::array<UINT8,4>{17,34,51,68}));
  const std::array<std::array<UINT8,4>,12> expected={{{0,0,0,0},{204,204,204,170},{170,170,170,204},{51,51,51,255},{255,255,255,51},{172,172,172,255},{243,243,243,255},{255,51,170,204},{204,204,204,255},{170,170,170,255},{51,51,51,255},{255,255,255,0}}};
  for(int format=1;format<12;++format)assert(DecodeTexel(0xf3ac,format)==expected[format]);
  assert((DecodeTexel(0xffff,5)==std::array<UINT8,4>{255,255,255,0}));
  auto mip=TextureMipOrigin(64,1088,1);assert(mip.x==1056&&mip.y==1568);
  mip=TextureMipOrigin(64,1088,6);assert(mip.x==2017&&mip.y==2033);
}
