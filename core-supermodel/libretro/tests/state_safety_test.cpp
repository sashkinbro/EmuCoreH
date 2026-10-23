// SPDX-License-Identifier: GPL-3.0-or-later
#include "state_safety.h"
#include <cassert>
#include <vector>
#include <string>
#include <fstream>
#include <iostream>
using namespace model3retro;
static std::vector<unsigned char> block(const char* name,size_t bytes) {
  size_t names=std::strlen(name)+1,header=12+names+1;
  std::vector<unsigned char> b(header+bytes);
  write32(b.data(),static_cast<uint32_t>(b.size()));write32(b.data()+4,static_cast<uint32_t>(names));write32(b.data()+8,1);
  std::memcpy(b.data()+12,name,names);return b;
}
static unsigned char* payload(std::vector<unsigned char>& b){return b.data()+12+read32(b.data()+4)+read32(b.data()+8);}
int main(int argc,char** argv){
  if(argc>1) {
    std::ifstream f(argv[1],std::ios::binary|std::ios::ate);assert(f);
    std::vector<unsigned char> bytes(static_cast<size_t>(f.tellg()));f.seekg(0);f.read(reinterpret_cast<char*>(bytes.data()),bytes.size());assert(f);
    const unsigned char* raw=nullptr;size_t count=0;
    assert(unpack_state(bytes.data(),bytes.size(),"srally2",raw,count));assert(count>4);
    for(size_t off=4;off<count;) {
      auto b=raw+off;size_t length=read32(b),header=12+read32(b+4)+read32(b+8);
      std::string name(reinterpret_cast<const char*>(b+12));
      if(!safe_state_block(name,b+header,length-header))std::cerr<<"Rejected "<<name<<" payload "<<length-header<<'\n';
      off+=length;
    }
    assert(valid_state_scalars(raw+4,count-4));
    // Forge a checksum-correct import from an actual device snapshot. Framing and
    // block-layout validation alone accept it; preflight must reject before load.
    auto forged=bytes;
    for(size_t off=84;off<80+count;) {
      auto b=forged.data()+off;size_t length=read32(b),header=12+read32(b+4)+read32(b+8);
      if(std::string(reinterpret_cast<const char*>(b+12))=="Real3D") {
        write32(b+header+0x1200000+94,0xffffffff);
        break;
      }
      off+=length;
    }
    write32(forged.data()+16,checksum(forged.data()+80,count));
    const unsigned char* mutated=nullptr;size_t mutatedSize=0;
    assert(unpack_state(forged.data(),forged.size(),"srally2",mutated,mutatedSize));
    assert(same_block_layout(raw+4,count-4,mutated+4,mutatedSize-4));
    assert(!valid_state_scalars(mutated+4,mutatedSize-4));
  }
  auto real=block("Real3D",0x1200000+98);auto p=payload(real);
  assert(valid_state_scalars(real.data(),real.size()));
  write32(p+0x1200000,0x40001);assert(!valid_state_scalars(real.data(),real.size()));
  write32(p+0x1200000,0x40000);assert(valid_state_scalars(real.data(),real.size()));
  write32(p+0x1200000+94,3);assert(!valid_state_scalars(real.data(),real.size()));
  write32(p+0x1200000+94,2);assert(valid_state_scalars(real.data(),real.size()));
  auto dsb=block("DSB2",25+0x20000+256+8+23);p=payload(dsb);
  assert(valid_state_scalars(dsb.data(),dsb.size()));
  write32(p+25+0x20000+256,256);assert(!valid_state_scalars(dsb.data(),dsb.size()));
  write32(p+25+0x20000+256,255);assert(valid_state_scalars(dsb.data(),dsb.size()));
  write32(p+9,0x1000001);assert(!valid_state_scalars(dsb.data(),dsb.size()));
  auto crypto=block("Sega 315-5881",1051);p=payload(crypto);
  assert(valid_state_scalars(crypto.data(),crypto.size()));
  write32(p+1039,3);assert(!valid_state_scalars(crypto.data(),crypto.size()));
  write32(p+1039,2);assert(valid_state_scalars(crypto.data(),crypto.size()));
  write32(p+1047,513);assert(!valid_state_scalars(crypto.data(),crypto.size()));
  auto jtag=block("JTAG",21);p=payload(jtag);p[0]=3;p[2]='0';p[3]='1';p[5]=3;p[7]='1';p[8]='0';
  assert(valid_state_scalars(jtag.data(),jtag.size()));
  p[4]='1';assert(!valid_state_scalars(jtag.data(),jtag.size()));
  p[4]=0;p[0]=255;assert(!valid_state_scalars(jtag.data(),jtag.size()));
  p[0]=3;p[18]=16;assert(!valid_state_scalars(jtag.data(),jtag.size()));
  auto scsp=block("SCSP x 2",10952);p=payload(scsp);
  assert(valid_state_scalars(scsp.data(),scsp.size()));
  p[314+48]=64;assert(!valid_state_scalars(scsp.data(),scsp.size()));
  p[314+48]=63;assert(valid_state_scalars(scsp.data(),scsp.size()));
  write32(p+314+50+32*103+1973-4,129);assert(!valid_state_scalars(scsp.data(),scsp.size()));
  write32(p+314+50+32*103+1973-4,128);assert(valid_state_scalars(scsp.data(),scsp.size()));
  p[314+50+64]=4;assert(!valid_state_scalars(scsp.data(),scsp.size()));
  p[314+50+64]=0;p[314+50+32]=1;
  write32(p+314+50+60,0xffffffff);assert(!valid_state_scalars(scsp.data(),scsp.size()));
  write32(p+314+50+60,0);write32(p+314+50+33,0xfffff);
  assert(!valid_state_scalars(scsp.data(),scsp.size())); // 16-bit sample would cross the RAM allocation
  auto drive=block("DriveBoard.2",1);p=payload(drive);
  assert(valid_state_scalars(drive.data(),drive.size()));
  p[0]=1;assert(!valid_state_scalars(drive.data(),drive.size())); // cannot make null m_ram reachable
  auto pci=block("MPC10x",272);p=payload(pci);
  assert(valid_state_scalars(pci.data(),pci.size()));
  write32(p+268,0xffffffff);assert(!valid_state_scalars(pci.data(),pci.size()));
  auto z80=block("DSB1 Z80",38);p=payload(z80);
  assert(valid_state_scalars(z80.data(),z80.size()));
  write32(p+28,2);assert(!valid_state_scalars(z80.data(),z80.size())); // regs[2] is beyond the primary/alternate pair
  write32(p+28,1);write32(p+32,0xffffffff);assert(!valid_state_scalars(z80.data(),z80.size()));
  auto timing=block("SCSP Timing",260);p=payload(timing);
  assert(valid_state_scalars(timing.data(),timing.size()));
  write32(p,0xffffffff);assert(!valid_state_scalars(timing.data(),timing.size()));
  write32(p,65536);assert(valid_state_scalars(timing.data(),timing.size()));
  write32(p,65537);assert(!valid_state_scalars(timing.data(),timing.size()));
  auto dsbTiming=block("DSB2 Timing",8);p=payload(dsbTiming);write32(p+4,11000);
  assert(valid_state_scalars(dsbTiming.data(),dsbTiming.size()));
  write32(p+4,11001);assert(!valid_state_scalars(dsbTiming.data(),dsbTiming.size()));
  write32(p+4,11000);write32(p,0xffffffff);assert(!valid_state_scalars(dsbTiming.data(),dsbTiming.size()));
  for(const char* name:{"Sound Board Timing","PowerPC Pending IRQ"}) {
    auto irq=block(name,4);p=payload(irq);write32(p,7);assert(valid_state_scalars(irq.data(),irq.size()));
    write32(p,8);assert(!valid_state_scalars(irq.data(),irq.size()));
    auto truncated=block(name,3);assert(!valid_state_scalars(truncated.data(),truncated.size()));
  }
  auto future=block("Unrecognized extension",4);assert(!valid_state_scalars(future.data(),future.size()));
  auto rtc=block("Model 3 RTC",32);p=payload(rtc);
  write32(p,2024);write32(p+4,2);write32(p+8,29);write32(p+12,4);
  assert(valid_state_scalars(rtc.data(),rtc.size()));
  write32(p,2023);assert(!valid_state_scalars(rtc.data(),rtc.size()));
  write32(p,2024);write32(p+28,60);assert(!valid_state_scalars(rtc.data(),rtc.size()));
  auto shortRtc=block("Model 3 RTC",28);assert(!valid_state_scalars(shortRtc.data(),shortRtc.size()));
  auto latches=block("Libretro Input Latches",8);p=payload(latches);
  write32(p,1);write32(p+4,1);assert(valid_state_scalars(latches.data(),latches.size()));
  write32(p,2);assert(!valid_state_scalars(latches.data(),latches.size()));
  write32(p,0);write32(p+4,0xffffffff);assert(!valid_state_scalars(latches.data(),latches.size()));
  auto shortLatches=block("Libretro Input Latches",4);assert(!valid_state_scalars(shortLatches.data(),shortLatches.size()));
}
