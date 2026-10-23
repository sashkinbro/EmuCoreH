// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
namespace model3retro {
constexpr size_t state_header_size=80, max_state_size=64u*1024u*1024u;
inline uint16_t axis_byte(int16_t v,bool invert=false) {
  if(v==0)return 128;
  auto n=static_cast<uint16_t>((static_cast<int>(v)+32768)*255/65535);
  return invert?255-n:n;
}
inline uint16_t gun_coord(int16_t v,unsigned low,unsigned high) {
  return static_cast<uint16_t>(low+(static_cast<int>(v)+32768)*static_cast<int>(high-low)/65535);
}
inline uint16_t pedal_byte(int16_t v) { return static_cast<uint16_t>(std::max(0,static_cast<int>(v))*255/32767); }
inline int16_t mix_channel(int16_t front,int16_t rear) { return static_cast<int16_t>((static_cast<int>(front)+rear)/2); }
inline uint32_t read32(const unsigned char* p) { return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24); }
inline void write32(unsigned char* p,uint32_t n) { for(unsigned i=0;i<4;i++)p[i]=static_cast<unsigned char>(n>>(i*8)); }
inline uint32_t checksum(const unsigned char* data,size_t n) {
  uint32_t h=2166136261u; for(size_t i=0;i<n;i++)h=(h^data[i])*16777619u; return h;
}
inline bool pack_state(void* data,size_t size,const std::string& game,const unsigned char* payload,size_t length) {
  if(!data||!payload||size>max_state_size||game.size()>59||length>max_state_size-state_header_size||size<state_header_size+length)return false;
  auto p=static_cast<unsigned char*>(data); std::memset(p,0,size);
  std::memcpy(p,"M3RETRO1",8);write32(p+8,1);write32(p+12,static_cast<uint32_t>(length));write32(p+16,checksum(payload,length));
  std::memcpy(p+20,game.c_str(),game.size()+1);std::memcpy(p+state_header_size,payload,length);return true;
}
inline bool unpack_state(const void* data,size_t size,const std::string& game,const unsigned char*& payload,size_t& length) {
  if(!data||size<state_header_size||size>max_state_size||game.size()>59)return false;
  auto p=static_cast<const unsigned char*>(data);
  if(std::memcmp(p,"M3RETRO1",8)||read32(p+8)!=1||std::memcmp(p+20,game.c_str(),game.size()+1))return false;
  length=read32(p+12);if(length>size-state_header_size)return false;
  payload=p+state_header_size;return checksum(payload,length)==read32(p+16);
}
// Legacy CBlockFile readers assume valid length fields; validate every block first.
inline bool valid_blocks(const unsigned char* data,size_t size) {
  if(!data||size==0)return false;
  size_t offset=0;while(offset<size) {
    if(size-offset<12)return false;
    const auto block=read32(data+offset),name=read32(data+offset+4),comment=read32(data+offset+8);
    if(block<12||block>size-offset||name<1||name>1025||comment<1||comment>1025||uint64_t(12)+name+comment>block)return false;
    if(data[offset+12+name-1]||data[offset+12+name+comment-1])return false;
    offset+=block;
  }return offset==size;
}
inline bool same_block_layout(const unsigned char* a,size_t a_size,const unsigned char* b,size_t b_size) {
  if(a_size!=b_size||!valid_blocks(a,a_size)||!valid_blocks(b,b_size))return false;
  size_t offset=0;
  while(offset<a_size) {
    const auto length=read32(a+offset);
    if(length!=read32(b+offset))return false;
    const size_t header=12u+read32(a+offset+4)+read32(a+offset+8);
    if(std::memcmp(a+offset,b+offset,header))return false;
    offset+=length;
  }
  return true;
}
}
