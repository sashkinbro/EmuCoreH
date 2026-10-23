// SPDX-License-Identifier: GPL-3.0-or-later
// Android executable: no BIOS or game data required; verifies failure paths and ABI lifecycle.
#include "libretro.h"
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
static std::string directory;
static bool environment(unsigned command,void* data){
  if(command==RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY||command==RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY){*static_cast<const char**>(data)=directory.c_str();return true;}
  return false;
}
static void check(bool ok,const char* message){if(!ok){std::fprintf(stderr,"FAIL: %s\n",message);std::exit(1);}}
template<typename T>T symbol(void* lib,const char* name){auto p=reinterpret_cast<T>(dlsym(lib,name));check(p!=nullptr,name);return p;}
int main(int argc,char** argv){
  check(argc==3,"usage: no_content_lifecycle path/to/core.so temporary_directory");directory=argv[2];
  auto lib=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!lib){std::fprintf(stderr,"%s\n",dlerror());return 1;}
  auto init=symbol<void(*)()>(lib,"retro_init"),deinit=symbol<void(*)()>(lib,"retro_deinit"),unload=symbol<void(*)()>(lib,"retro_unload_game"),run=symbol<void(*)()>(lib,"retro_run"),reset=symbol<void(*)()>(lib,"retro_reset");
  auto load=symbol<bool(*)(const retro_game_info*)>(lib,"retro_load_game");
  auto state_size=symbol<size_t(*)()>(lib,"retro_serialize_size");
  auto serialize=symbol<bool(*)(void*,size_t)>(lib,"retro_serialize");
  auto unserialize=symbol<bool(*)(const void*,size_t)>(lib,"retro_unserialize");
  auto error=symbol<const char*(*)()>(lib,"supermodel_boot_error");
  symbol<void(*)(retro_environment_t)>(lib,"retro_set_environment")(environment);
  check(symbol<unsigned(*)()>(lib,"retro_api_version")()==1,"ABI version");
  for(int iteration=0;iteration<3;iteration++){
    init();retro_system_info info{};symbol<void(*)(retro_system_info*)>(lib,"retro_get_system_info")(&info);
    check(info.need_fullpath&&info.block_extract&&!std::strcmp(info.valid_extensions,"zip"),"ZIP contract");
    retro_system_av_info av{};symbol<void(*)(retro_system_av_info*)>(lib,"retro_get_system_av_info")(&av);
    check(av.geometry.base_width==496&&av.geometry.base_height==384&&av.timing.sample_rate==44100,"AV contract");
    check(!load(nullptr),"must reject null content");check(std::strlen(error())>0,"missing content diagnostic");
    retro_game_info missing{};missing.path="/this-file-cannot-exist/model3.zip";check(!load(&missing),"must reject missing ROM");
    std::filesystem::create_directories(std::filesystem::path(directory)/"supermodel");
    std::ofstream(std::filesystem::path(directory)/"supermodel"/"Games.xml")<<"<games/>";
    auto invalid=(std::filesystem::path(directory)/"invalid.zip").string();std::ofstream(invalid)<<"This is not a ROM set";
    retro_game_info bad{};bad.path=invalid.c_str();check(!load(&bad),"must reject invalid ZIP");
    run();reset();check(state_size()==0,"no unloaded state allocation");
    unsigned char data[128]{};check(!serialize(data,sizeof(data))&&!unserialize(data,sizeof(data)),"no unloaded state success");
    unload();unload();deinit();deinit();
  }
  dlclose(lib);std::puts("PASS: no-content Model3 lifecycle (not a game boot)");
}
