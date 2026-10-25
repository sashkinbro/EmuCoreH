// SPDX-License-Identifier: GPL-3.0-or-later
#include "resolution.h"
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
using namespace model3retro;
static void check(bool value,const char* reason){if(!value){std::fprintf(stderr,"FAIL: %s\n",reason);std::exit(1);}}
int main(){
  Resolution resolution;
  check(resolution.Geometry().base_width==496&&resolution.Geometry().base_height==384,"default is native");
  for(unsigned n=1;n<=4;++n){
    const char* values[]={"1x","2x","3x","4x"};
    Resolution selected(values[n-1]);const auto g=selected.Geometry();
    check(g.base_width==496*n&&g.base_height==384*n,"bounded integer output targets");
    check(g.max_width==1984&&g.max_height==1536&&g.aspect_ratio==4.f/3.f,"maximum and display aspect stay fixed");
  }
  for(const char* invalid:std::initializer_list<const char*>{nullptr,"","0x","5x","99x","2","2xjunk","-1x"}){
    check(Resolution(invalid).Scale()==1,"missing or invalid options fall back to native");
  }
  unsigned publishes=0,frames=0,drawWidth=496;
  auto publish=[&](const retro_game_geometry& g){++publishes;check(drawWidth==496,"old framebuffer untouched during geometry publication");return true;};
  // The production caller returns before emulation/drawing on true, and draws on the next run.
  if(!resolution.Update("4x",publish))++frames;
  check(frames==0&&publishes==1&&resolution.Geometry().base_width==1984,"geometry change defers guest work for one call");
  if(!resolution.Update("4x",publish)){drawWidth=resolution.Geometry().base_width;++frames;}
  check(frames==1&&publishes==1&&drawWidth==1984,"same option draws next call without repeated deferral");
  // Reset, state restore and GL context recreation do not own or reset host option policy.
  check(resolution.Scale()==4,"host setting survives guest lifecycle");
  check(!resolution.Update("2x",[](const retro_game_geometry&){return false;})&&resolution.Scale()==4,"frontend rejection retains old extent");
  check(resolution.Update(nullptr,[](const retro_game_geometry&){return true;})&&resolution.Scale()==1,"invalid runtime option restores native");
  std::puts("PASS: Model3 resolution policy and deferred geometry");
}
