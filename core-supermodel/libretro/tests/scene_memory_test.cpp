// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/SceneMemory.h"
#include <cassert>
#include <vector>
#include <array>
int main() {
  using namespace New3D;
  assert(CullingWordsRemaining(0)==0x100000);
  assert(CullingWordsRemaining(0x0fffff)==1);
  assert(CullingWordsRemaining(0x100000)==0);
  assert(CullingWordsRemaining(0x800600)==0x3fa00);
  assert(CullingWordsRemaining(0x83ffff)==1);
  assert(CullingWordsRemaining(0x840000)==0);
  std::vector<uint32_t> visited;
  auto visit=[&](uint32_t node){visited.push_back(node);};
  const uint32_t lostWorldBoot[]={0x800100,0,0x800200,0x02800300};
  VisitPointerList(lostWorldBoot,4,visit);
  assert((visited==std::vector<uint32_t>{0x800100}));
  visited.clear();
  const uint32_t terminated[]={0x100,0x02000200,0x300};
  VisitPointerList(terminated,3,visit);
  assert((visited==std::vector<uint32_t>{0x100,0x200}));
  visited.clear();
  const uint32_t empty[]={0x01000100,0x200};
  VisitPointerList(empty,2,visit);assert(visited.empty());
  const uint32_t boundary[]={0x800100,0x800200,0x02800300};
  VisitPointerList(boundary,CullingWordsRemaining(0x83ffff),visit);
  assert((visited==std::vector<uint32_t>{0x800100}));
  visited.clear();
  const uint32_t invalidFlags[]={0x40000100,0x02000200};
  VisitPointerList(invalidFlags,2,visit);assert(visited.empty());
}
