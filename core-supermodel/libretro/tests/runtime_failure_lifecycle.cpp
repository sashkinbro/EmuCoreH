// SPDX-License-Identifier: GPL-3.0-or-later
// Android-only integration executable; takes owner content and never adds a core crash API.
#include "libretro.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
static std::string system_directory,save_directory;
static retro_hw_render_callback hardware{};
static int shutdowns=0,polls=0,frames=0;
static size_t audio_frames=0;
static void check(bool ok,const char* detail){if(!ok){std::fprintf(stderr,"FAIL: %s\n",detail);std::exit(1);}}
static uintptr_t framebuffer(){return 0;}
static retro_proc_address_t procedure(const char* name){return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));}
static bool environment(unsigned command,void* data){
  switch(command){
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:*static_cast<const char**>(data)=system_directory.c_str();return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:*static_cast<const char**>(data)=save_directory.c_str();return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER:{auto* callback=static_cast<retro_hw_render_callback*>(data);callback->get_current_framebuffer=framebuffer;callback->get_proc_address=procedure;hardware=*callback;return true;}
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:return true;
    case RETRO_ENVIRONMENT_SHUTDOWN:++shutdowns;return true;
    default:return false;
  }
}
static void poll(){++polls;}
static int16_t input(unsigned,unsigned,unsigned,unsigned){return 0;}
static void video(const void*,unsigned,unsigned,size_t){++frames;}
static size_t audio(const int16_t*,size_t count){audio_frames+=count;return count;}
template<typename T>T symbol(void* library,const char* name){auto result=reinterpret_cast<T>(dlsym(library,name));check(result!=nullptr,name);return result;}
int main(int argc,char** argv){
  check(argc==5,"usage: runtime_failure_lifecycle core.so system_directory owner_set.zip temporary_save_directory");
  system_directory=argv[2];save_directory=argv[4];std::filesystem::create_directories(save_directory);
  EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);check(display!=EGL_NO_DISPLAY&&eglInitialize(display,nullptr,nullptr),"EGL initialize");
  const EGLint config_attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_DEPTH_SIZE,24,EGL_STENCIL_SIZE,8,EGL_NONE};
  EGLConfig config{};EGLint count=0;check(eglChooseConfig(display,config_attributes,&config,1,&count)&&count==1,"GLES3 pbuffer config");
  const EGLint context_attributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
  EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attributes);check(context!=EGL_NO_CONTEXT,"GLES3 context");
  const EGLint surface_attributes[]={EGL_WIDTH,496,EGL_HEIGHT,384,EGL_NONE};
  EGLSurface surface=eglCreatePbufferSurface(display,config,surface_attributes);check(surface!=EGL_NO_SURFACE,"pbuffer");
  check(eglMakeCurrent(display,surface,surface,context),"current GLES3 context");
  void* library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!library){std::fprintf(stderr,"%s\n",dlerror());return 1;}
  auto init=symbol<void(*)()>(library,"retro_init"),deinit=symbol<void(*)()>(library,"retro_deinit"),unload=symbol<void(*)()>(library,"retro_unload_game"),run=symbol<void(*)()>(library,"retro_run");
  auto load=symbol<bool(*)(const retro_game_info*)>(library,"retro_load_game");auto error=symbol<const char*(*)()>(library,"supermodel_boot_error");
  symbol<void(*)(retro_environment_t)>(library,"retro_set_environment")(environment);
  symbol<void(*)(retro_input_poll_t)>(library,"retro_set_input_poll")(poll);
  symbol<void(*)(retro_input_state_t)>(library,"retro_set_input_state")(input);
  symbol<void(*)(retro_video_refresh_t)>(library,"retro_set_video_refresh")(video);
  symbol<void(*)(retro_audio_sample_batch_t)>(library,"retro_set_audio_sample_batch")(audio);
  init();retro_game_info game{};game.path=argv[3];bool loaded=load(&game);check(loaded,error());
  check(hardware.context_reset&&hardware.context_destroy,"hardware lifecycle callbacks");hardware.context_reset();
  check(shutdowns==0,"valid graphics do not request shutdown");
  run();check(polls==1&&frames==1&&audio_frames>0,"owner set reaches actual core frame and PCM callback");
  hardware.context_destroy();
  const int before_wait=polls,previous_frames=frames;const size_t previous_audio=audio_frames;
  run();check(polls==before_wait&&frames==previous_frames&&audio_frames==previous_audio&&shutdowns==0,"destroyed context is normal wait");
  // Deliberately remove the EGL context through its normal API: shader creation now fails.
  check(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT),"detach EGL context");
  hardware.context_reset();check(shutdowns==1&&std::strstr(error(),"initialization failed"),"actual presenter initialization failure requests shutdown");
  run();hardware.context_reset();run();
  check(shutdowns==1&&polls==before_wait&&frames==previous_frames&&audio_frames==previous_audio,"fatal latch suppresses further input, video, PCM and duplicate shutdown");
  check(eglMakeCurrent(display,surface,surface,context),"restore EGL for cleanup");
  hardware.context_destroy();unload();
  loaded=load(&game);check(loaded,error());hardware.context_reset();run();
  check(shutdowns==1&&polls==before_wait+1&&frames==previous_frames+1&&audio_frames>previous_audio,"new load resets fatal latch and resumes callbacks");
  hardware.context_destroy();unload();deinit();dlclose(library);
  eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);
  std::puts("PASS: actual Model3 runtime shutdown propagation, graphics failure, context wait, and reload recovery");
}
