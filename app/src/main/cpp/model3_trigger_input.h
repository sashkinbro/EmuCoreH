#pragma once
#include "libretro.h"
#include <algorithm>
#include <cstdint>
namespace emucoreh {
inline uint16_t PackModel3Triggers(int left,int right) {
    return static_cast<uint16_t>(std::clamp(left,0,255)|(std::clamp(right,0,255)<<8));
}
inline int16_t ReadModel3Trigger(int core_id,uint16_t packed,unsigned id) {
    if(core_id!=2||(id!=RETRO_DEVICE_ID_JOYPAD_L2&&id!=RETRO_DEVICE_ID_JOYPAD_R2))return 0;
    const unsigned pressure=(packed>>(id==RETRO_DEVICE_ID_JOYPAD_R2?8:0))&255;
    return static_cast<int16_t>((pressure*32767+127)/255);
}
}
