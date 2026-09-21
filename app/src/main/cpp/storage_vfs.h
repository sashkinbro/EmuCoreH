#pragma once
#include <jni.h>
#include <libretro.h>

bool InitializeStorageVfs(JavaVM *vm, JNIEnv *env);
bool GetStorageVfs(retro_vfs_interface_info *info);
