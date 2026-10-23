#include "model3_trigger_input.h"
#include "input_mapping.h"
#include <cassert>
int main() {
    using namespace emucoreh;
    for(int n=0;n<=255;n++) {
        auto packed=PackModel3Triggers(n,255-n);
        auto left=ReadModel3Trigger(2,packed,RETRO_DEVICE_ID_JOYPAD_L2);
        auto right=ReadModel3Trigger(2,packed,RETRO_DEVICE_ID_JOYPAD_R2);
        assert(model3retro::pedal_input(left,false)==n);
        assert(model3retro::pedal_input(right,false)==255-n);
        assert(ReadModel3Trigger(0,packed,RETRO_DEVICE_ID_JOYPAD_L2)==0);
        assert(ReadModel3Trigger(1,packed,RETRO_DEVICE_ID_JOYPAD_R2)==0);
        assert(ReadModel3Trigger(2,packed,RETRO_DEVICE_ID_JOYPAD_A)==0);
    }
    assert(PackModel3Triggers(-20,1000)==0xff00);
    assert(ReadModel3Trigger(2,0,RETRO_DEVICE_ID_JOYPAD_R2)==0);
}
