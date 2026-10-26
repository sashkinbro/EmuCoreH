// SPDX-License-Identifier: MIT
// Libretro's backwards-compatible disk ABI extension, absent in the pinned header.
#pragma once
#include <libretro.h>
#ifndef RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION
#define RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION 57
#define RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE 58
struct retro_disk_control_ext_callback {
    retro_set_eject_state_t set_eject_state;
    retro_get_eject_state_t get_eject_state;
    retro_get_image_index_t get_image_index;
    retro_set_image_index_t set_image_index;
    retro_get_num_images_t get_num_images;
    retro_replace_image_index_t replace_image_index;
    retro_add_image_index_t add_image_index;
    bool (RETRO_CALLCONV *set_initial_image)(unsigned index, const char *path);
    bool (RETRO_CALLCONV *get_image_path)(unsigned index, char *path, size_t len);
    bool (RETRO_CALLCONV *get_image_label)(unsigned index, char *label, size_t len);
};
#endif
