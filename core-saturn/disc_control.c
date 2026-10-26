// SPDX-License-Identifier: GPL-3.0-only
#include "disc_control_abi.h"
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "cs2.h"

#define MAX_DISCS 128
static char *images[MAX_DISCS];
static unsigned count, selected;
static bool ejected;
static char *copy_path(const char *path) {
    size_t size = strlen(path) + 1;
    char *copy = malloc(size);
    if (copy) memcpy(copy, path, size);
    return copy;
}
extern bool saturn_core_is_initialized(void);

void saturn_reset_discs(const char *initial_path) {
    for (unsigned i = 0; i < count; ++i) { free(images[i]); images[i] = NULL; }
    count = selected = 0;
    ejected = false;
    if (initial_path) { images[0] = copy_path(initial_path); if (images[0]) count = 1; }
}
static bool set_eject(bool state) {
    if (!saturn_core_is_initialized()) return false;
    if (state == ejected) return true;
    if (state) { Cs2ForceOpenTray(); ejected = true; return true; }
    if (selected >= count || !images[selected]) return false;
    if (Cs2ForceCloseTray(CDCORE_ISO, images[selected]) != 0) return false;
    ejected = false;
    return true;
}
static bool get_eject(void) { return ejected; }
static unsigned get_index(void) { return selected; }
static unsigned get_count(void) { return count; }
static bool set_index(unsigned index) {
    if (!ejected || index >= count || !images[index]) return false;
    selected = index; return true;
}
static bool replace_image(unsigned index, const struct retro_game_info *info) {
    if (!ejected || index >= count) return false;
    if (!info) {
        if (index == selected) return false;
        free(images[index]);
        for (unsigned i = index; i + 1 < count; ++i) images[i] = images[i + 1];
        images[--count] = NULL;
        if (selected > index) --selected;
        return true;
    }
    if (!info->path || !*info->path) return false;
    FILE *file = fopen(info->path, "rb");
    if (!file) return false;
    fclose(file);
    char *path = copy_path(info->path);
    if (!path) return false;
    free(images[index]); images[index] = path;
    return true;
}
static bool add_image(void) {
    if (!ejected || count >= MAX_DISCS) return false;
    images[count++] = NULL; return true;
}
static bool get_path(unsigned index, char *path, size_t size) {
    if (index >= count || !images[index] || !path || !size || strlen(images[index]) >= size) return false;
    memcpy(path, images[index], strlen(images[index]) + 1); return true;
}
void saturn_register_disk_control(retro_environment_t env) {
    static const struct retro_disk_control_ext_callback ext = {
        set_eject, get_eject, get_index, set_index, get_count, replace_image, add_image,
        NULL, get_path, NULL
    };
    static const struct retro_disk_control_callback basic = {
        set_eject, get_eject, get_index, set_index, get_count, replace_image, add_image
    };
    unsigned version = 0;
    if (env(RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION, &version) && version >= 1)
        env(RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE, (void*)&ext);
    else env(RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE, (void*)&basic);
}
