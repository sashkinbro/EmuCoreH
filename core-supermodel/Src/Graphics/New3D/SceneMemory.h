// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
namespace New3D {
inline size_t CullingWordsRemaining(uint32_t address) {
  address&=0xffffff;
  if(address<0x100000)return 0x100000-address;
  if(address>=0x800000&&address<0x840000)return 0x840000-address;
  return 0;
}
template<class Visit> void VisitPointerList(const uint32_t* list,size_t words,Visit visit) {
  if(!list)return;
  for(size_t index=0;index<words;index++) {
    const uint32_t entry=list[index];
    // Empty/uninitialized lists also terminate with a null pointer. Only the
    // two documented list flags may occupy the upper byte (as in Legacy3D).
    if(!(entry&0xffffff)||(entry&0xfd000000))break;
    visit(entry&0xffffff);
    if(entry&0x02000000)break;
  }
}
}
