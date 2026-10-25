// SPDX-License-Identifier: GPL-3.0-or-later
// Real3D texel formats and mip layout, matching Texture.cpp's GLES decoder.
#pragma once
#include "Types.h"
#include <array>
namespace New3D {
inline std::array<UINT8,4> DecodeTexel(UINT16 texel,int format) {
  UINT8 c=0,a=255;
  switch(format) {
    case 0:return {UINT8(((texel>>10)&31)*255/31),UINT8(((texel>>5)&31)*255/31),UINT8((texel&31)*255/31),UINT8((texel&0x8000)?0:255)};
    case 1:c=(texel&15)*17;a=((texel>>4)&15)*17;break;
    case 2:c=((texel>>4)&15)*17;a=(texel&15)*17;break;
    case 3:c=((texel>>8)&15)*17;a=((texel>>12)&15)*17;break;
    case 4:c=((texel>>12)&15)*17;a=((texel>>8)&15)*17;break;
    case 5:c=texel&255;a=c==255?0:255;break;
    case 6:c=texel>>8;a=c==255?0:255;break;
    case 7:return {UINT8(((texel>>12)&15)*17),UINT8(((texel>>8)&15)*17),UINT8(((texel>>4)&15)*17),UINT8((texel&15)*17)};
    case 8:c=(texel&15)*17;a=c==255?0:255;break;
    case 9:c=((texel>>4)&15)*17;a=c==255?0:255;break;
    case 10:c=((texel>>8)&15)*17;a=c==255?0:255;break;
    case 11:c=((texel>>12)&15)*17;a=c==255?0:255;break;
    default:return {255,0,0,255};
  }
  return {c,c,c,a};
}
struct MipOrigin {int x,y;};
inline MipOrigin TextureMipOrigin(int x,int y,unsigned level) {
  static constexpr int xs[]={0,1024,1536,1792,1920,1984,2016,2032,2040,2044,2046};
  static constexpr int ys[]={0,512,768,896,960,992,1008,1016,1020,1022,1023};
  if(level>10)return {2047,2047};
  return {xs[level]+(x>>level),ys[level]+((y&1023)>>level)+(y/1024)*1024};
}
}
