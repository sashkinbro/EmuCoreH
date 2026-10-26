#include "disc_control_abi.h"
#include <stdio.h>
#include <stdbool.h>
#include <string.h>
#include "cs2.h"
static struct retro_disk_control_ext_callback disk;
static int opens, closes, reject;
bool saturn_core_is_initialized(void) { return true; }
void Cs2ForceOpenTray(void) { ++opens; }
int Cs2ForceCloseTray(int id, const char *path) { ++closes; return reject ? -1 : 0; }
extern void saturn_reset_discs(const char*);
extern void saturn_register_disk_control(retro_environment_t);
static bool env(unsigned cmd, void *data) {
    if(cmd==RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION){*(unsigned*)data=1;return true;}
    if(cmd==RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE){disk=*(struct retro_disk_control_ext_callback*)data;return true;}
    return false;
}
#define CHECK(x) do { if(!(x)){fprintf(stderr,"FAIL line %d\n",__LINE__);return 1;} }while(0)
int main(int argc, char **argv) {
    CHECK(argc==2);
    saturn_register_disk_control(env); saturn_reset_discs(argv[1]);
    CHECK(disk.get_num_images()==1 && disk.get_image_index()==0);
    CHECK(!disk.add_image_index()); CHECK(disk.set_eject_state(true));
    CHECK(disk.add_image_index());
    struct retro_game_info image={argv[1],0,0,0};
    CHECK(disk.replace_image_index(1,&image)); CHECK(disk.set_image_index(1));
    reject=1; CHECK(!disk.set_eject_state(false) && disk.get_eject_state());
    CHECK(disk.set_image_index(0)); CHECK(disk.replace_image_index(1,NULL));
    reject=0; CHECK(disk.set_eject_state(false) && !disk.get_eject_state());
    CHECK(disk.get_num_images()==1 && opens==1 && closes==2);
    saturn_reset_discs(NULL); CHECK(disk.get_num_images()==0);
    puts("PASS: Saturn tray, replacement and failed-insert rollback contracts"); return 0;
}
