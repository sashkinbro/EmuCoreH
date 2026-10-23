// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "adapter_helpers.h"
#include "../Src/Model3/RTCClock.h"
#include <cmath>
namespace model3retro {
// These are byte offsets in the pinned serializer, not C++ struct offsets.
// Check before any legacy LoadState call, so a rejected import never mutates a running game.
inline bool safe_state_block(const std::string& name,const unsigned char* p,size_t n) {
  if(name=="Real3D") {
    constexpr size_t ram=0x1200000;
    return n==ram+98 && read32(p+ram)<=0x40000 && read32(p+ram+94)<=2;
  }
  if(name=="JTAG") {
    size_t offset=0;
    for(unsigned reg=0;reg<2;reg++) {
      if(n-offset<2)return false;
      const size_t length=p[offset]|(size_t(p[offset+1])<<8);offset+=2;
      if(!length||length>n-offset||p[offset+length-1])return false;
      for(size_t i=0;i+1<length;i++)if(p[offset+i]!='0'&&p[offset+i]!='1')return false;
      offset+=length;
    }
    return n-offset==11 && p[offset+8]<16 && p[offset+9]<=1 && p[offset+10]<=1;
  }
  if(name=="Sega 315-5881") {
    if(n!=1051)return false;
    const auto buffer=read32(p+1039),pos=read32(p+1043),length=read32(p+1047);
    return p[1032]<=1 && buffer<=2 && pos<=length && length<=512;
  }
  if(name=="DSB1"||name=="DSB2") {
    const bool dsb1=name=="DSB1";
    const size_t fifo=25+(dsb1?0x8000:0x20000)+256;
    if(n!=fifo+8+(dsb1?24:23)||p[0]>1||read32(p+fifo)>255||read32(p+fifo+4)>255)return false;
    constexpr uint32_t rom=0x1000000;
    const auto begin=read32(p+9),end=read32(p+13),loop=read32(p+17),loopEnd=read32(p+21);
    // usingLoopEnd stores a byte count, unlike usingMPEGEnd (an absolute address).
    if(begin>rom||end>rom||begin>end||loop>rom||loopEnd>rom-loop)return false;
    // Signed preface positions are valid after a loop changes the buffer base.
    // The complete MPEG Decoder extension validates the resulting ROM range.
    // Offset/length pairs will be passed to the MPEG decoder on load and on the next command.
    const size_t registers=fifo+(dsb1?8:16);
    if(read32(p+registers)>rom||read32(p+registers+4)>rom)return false;
    if(dsb1&&(read32(p+fifo+20)>rom||read32(p+fifo+24)>rom))return false;
    return true;
  }
  if(name=="SCSP x 2") {
    constexpr size_t global=314,slotBytes=103,dspBytes=1973,chipBytes=50+32*slotBytes+dspBytes;
    if(n!=global+2*chipBytes)return false;
    for(size_t chip=0;chip<2;chip++) {
      const size_t start=global+chip*chipBytes;
      if(p[start+48]>63)return false; // RINGBUF destination pointer
      for(size_t slot=0;slot<32;slot++) {
        const size_t at=start+50+slot*slotBytes;
        if(read32(p+at+54)>31||p[at+64]>3)return false; // slot number, envelope state
        if(p[at+32]) {
          const uint32_t base=read32(p+at+33)&0xfffff;
          const bool pcm8=(p[at]&0x10)!=0;
          for(size_t address:{at+41,at+45}) {
            const uint32_t sample=pcm8?(read32(p+address)>>12):((read32(p+address)>>11)&0x7fffe);
            if(base+sample+(pcm8?1:2)>0x100000)return false;
          }
          // EG_Update uses the result as an EG_TABLE index; negative and oversized rates/volumes are unsafe.
          if(read32(p+at+60)>0x3ff0000)return false;
          for(size_t rate:{at+69,at+73,at+77,at+81})if(read32(p+rate)>0x4000000)return false;
        }
      }
      const size_t dsp=start+50+32*slotBytes;
      if(p[dsp+dspBytes-5]>1||read32(p+dsp+dspBytes-4)>128)return false;
    }
    return true;
  }
  // Instruction overshoot is nonnegative. Leave generous headroom over actual
  // 68K instruction costs, while excluding signed overflow and backwards timing.
  if(name=="SCSP Timing")return n==260&&read32(p)<=65536;
  if(name=="DSB2 Timing")return n==8&&read32(p)<=65536&&read32(p+4)<=11000;
  if(name=="Sound Board Timing"||name=="PowerPC Pending IRQ")return n==4&&read32(p)<=7;
  if(name=="Libretro Input Latches")return n==8&&read32(p)<=1&&read32(p+4)<=1;
  if(name=="Ski Board Protocol")return n==6&&p[0]<=1&&p[3]<=7;
  if(name=="Model 3 RTC") {
    if(n!=32)return false;
    const Model3Clock::Calendar clock{read32(p),read32(p+4),read32(p+8),read32(p+12),
      read32(p+16),read32(p+20),read32(p+24),read32(p+28)};
    return clock.Valid();
  }
  if(name=="DSB1 Audio"||name=="DSB2 Audio")
    return n==3220&&read32(p)<=256&&read32(p+4)<=256&&
      read32(p)+read32(p+4)==256&&read32(p+8)<=535;
  if(name=="MPEG Decoder") {
    if(n!=11321||read32(p)!=1||p[16]>1||p[17]>1)return false;
    const auto offset=read32(p+4);
    const auto length=static_cast<int32_t>(read32(p+8));
    const auto position=static_cast<int32_t>(read32(p+12));
    const auto samples=read32(p+18),pcm=read32(p+22),channels=read32(p+30);
    if(length<0||position>length||channels>2||samples>1152||
       (samples&&!channels)||pcm>samples*channels||(channels&&pcm%channels)||
       read32(p+46)>511||read32(p+50)>2304)return false;
    if(offset==UINT32_MAX) {
      if(length||position||!p[17])return false;
    } else if(offset>0x1000000||uint64_t(offset)+length>0x1000000||int64_t(offset)+position<0)return false;
    for(size_t i=569;i<6713;i+=4) {
      float value;std::memcpy(&value,p+i,sizeof(value));
      if(!std::isfinite(value)||std::fabs(value)>1e20f)return false;
    }
    return true;
  }
  if(name=="MPC10x")return n==272&&read32(p+256)<=255&&read32(p+260)<=31&&read32(p+264)<=7&&read32(p+268)<=255;
  if(name=="DSB1 Z80"||name=="DriveBoard Z80")
    return n==38&&p[26]<=3&&p[27]<=2&&read32(p+28)<=1&&read32(p+32)<=1&&p[36]<=1&&p[37]<=1;
  if(name=="93C46")return n==166&&p[156]<=1&&read32(p+157)<=63&&p[165]<=1;
  if(name=="DriveBoard.2") {
    if(n<1||p[0]>1)return false;
    if(!p[0])return n==1;
    return n>=2&&p[1]<=1&&(p[1]?n==2:n==8198);
  }
  // Remaining pinned blocks contain only register/memory images. The load path
  // also requires their complete layout to match a fresh capture of this game.
  // Explicitly list them so a new extension must opt into validation.
  return name=="Supermodel Save State"||name=="Supermodel NVRAM State"||
    name=="Backup RAM"||name=="Model 3"||name=="PowerPC"||name=="IRQ"||
    name=="53C810"||name=="Tile Generator"||name=="Sound Board"||
    name=="Sound Board 68K"||name=="DSB2 68K"||name=="WheelBoard"||
    name=="JoystickBoard"||name=="BillBoard";
}
inline bool valid_state_scalars(const unsigned char* data,size_t size) {
  if(!valid_blocks(data,size))return false;
  for(size_t offset=0;offset<size;) {
    const auto block=read32(data+offset),names=read32(data+offset+4),comment=read32(data+offset+8);
    const size_t header=12+names+comment;
    if(std::memchr(data+offset+12,0,names)!=data+offset+12+names-1)return false;
    const std::string name(reinterpret_cast<const char*>(data+offset+12),names-1);
    if(!safe_state_block(name,data+offset+header,block-header))return false;
    offset+=block;
  }
  return true;
}
}
