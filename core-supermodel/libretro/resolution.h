// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "libretro.h"
#include <cstring>
namespace model3retro {
// Guest TileGen surfaces and input coordinates always remain at hardware resolution.
class Resolution {
public:
  static constexpr unsigned native_width=496,native_height=384,max_scale=4;
  explicit Resolution(const char* value=nullptr):scale_(Parse(value)){}
  unsigned Scale()const{return scale_;}
  retro_game_geometry Geometry()const{return GeometryFor(scale_);}
  template<class Publish> bool Update(const char* value,Publish publish){
    const unsigned requested=Parse(value);
    if(requested==scale_||!publish(GeometryFor(requested)))return false;
    scale_=requested;
    // The caller must skip this frame: frontends may allocate their new FBO only
    // between retro_run calls. Recreate host GL resources before the next draw.
    return true;
  }
private:
  static unsigned Parse(const char* value){
    if(value){if(!std::strcmp(value,"2x"))return 2;if(!std::strcmp(value,"3x"))return 3;if(!std::strcmp(value,"4x"))return 4;}
    return 1;
  }
  static retro_game_geometry GeometryFor(unsigned scale){return {native_width*scale,native_height*scale,native_width*max_scale,native_height*max_scale,4.f/3.f};}
  unsigned scale_;
};
}
