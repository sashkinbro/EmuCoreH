// SPDX-License-Identifier: GPL-3.0-or-later
// Android GLES integration probe. Uses owner-provided content and a temporary save directory.
#include "libretro.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>
static void check(bool ok,const char* reason){if(!ok){std::fprintf(stderr,"FAIL: %s (GL 0x%x)\n",reason,glGetError());std::exit(1);}}
static std::string system_directory,save_directory,option="1x";
static retro_hw_render_callback hardware{};
static retro_game_geometry pending{},allocated{};
static GLuint fbo=0,color=0,depth=0;
static bool updated=false,has_pending=false,registered=false;
static unsigned draws=0,duplicates=0,polls=0,publishes=0;
static size_t samples=0;
static uintptr_t framebuffer(){return fbo;}
static retro_proc_address_t procedure(const char* name){return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));}
static bool environment(unsigned command,void* data){
  switch(command){
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:*static_cast<const char**>(data)=system_directory.c_str();return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:*static_cast<const char**>(data)=save_directory.c_str();return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES:{auto* vars=static_cast<retro_variable*>(data);for(;vars->key;++vars)if(!std::strcmp(vars->key,"supermodel_internal_resolution"))registered=!std::strcmp(vars->value,"Internal resolution; 1x|2x|3x|4x");return true;}
    case RETRO_ENVIRONMENT_GET_VARIABLE:{auto* var=static_cast<retro_variable*>(data);if(std::strcmp(var->key,"supermodel_internal_resolution"))return false;var->value=option.c_str();return true;}
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*static_cast<bool*>(data)=updated;updated=false;return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:pending=*static_cast<retro_game_geometry*>(data);has_pending=true;++publishes;return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER:{auto* cb=static_cast<retro_hw_render_callback*>(data);cb->get_current_framebuffer=framebuffer;cb->get_proc_address=procedure;hardware=*cb;return true;}
    case RETRO_ENVIRONMENT_SHUTDOWN:check(false,"unexpected core shutdown");return true;
    default:return false;
  }
}
static void allocate(const retro_game_geometry& g){
  if(fbo)glDeleteFramebuffers(1,&fbo);if(color)glDeleteTextures(1,&color);if(depth)glDeleteRenderbuffers(1,&depth);
  glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
  glGenTextures(1,&color);glBindTexture(GL_TEXTURE_2D,color);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,g.base_width,g.base_height,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
  glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
  glGenRenderbuffers(1,&depth);glBindRenderbuffer(GL_RENDERBUFFER,depth);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH24_STENCIL8,g.base_width,g.base_height);
  glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_RENDERBUFFER,depth);
  check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"frontend FBO complete");allocated=g;
}
static void apply_pending(){if(has_pending){allocate(pending);has_pending=false;}}
static void poll(){++polls;}
static int16_t input(unsigned,unsigned,unsigned,unsigned){return 0;}
static size_t audio(const int16_t*,size_t count){samples+=count;return count;}
static void video(const void* data,unsigned w,unsigned h,size_t){
  check(w==allocated.base_width&&h==allocated.base_height,"video extent fits frontend allocation");
  if(!data){++duplicates;return;}
  check(data==RETRO_HW_FRAME_BUFFER_VALID,"hardware frame marker");++draws;
}
template<class T>T symbol(void* library,const char* name){auto value=reinterpret_cast<T>(dlsym(library,name));check(value!=nullptr,name);return value;}
int main(int argc,char** argv){
  check(argc==5,"usage: resolution_lifecycle core.so system_directory owner_set.zip temporary_save_directory");
  system_directory=argv[2];save_directory=argv[4];std::filesystem::create_directories(save_directory);
  EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);check(eglInitialize(display,nullptr,nullptr),"EGL initialize");
  const EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
  EGLConfig config{};EGLint count=0;check(eglChooseConfig(display,attributes,&config,1,&count)&&count==1,"GLES config");
  const EGLint context_attributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},surface_attributes[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
  EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attributes);EGLSurface surface=eglCreatePbufferSurface(display,config,surface_attributes);
  check(eglMakeCurrent(display,surface,surface,context),"current GLES context");
  void* library=dlopen(argv[1],RTLD_NOW);check(library!=nullptr,dlerror());
  auto init=symbol<void(*)()>(library,"retro_init"),deinit=symbol<void(*)()>(library,"retro_deinit"),run=symbol<void(*)()>(library,"retro_run"),reset=symbol<void(*)()>(library,"retro_reset"),unload=symbol<void(*)()>(library,"retro_unload_game");
  auto load=symbol<bool(*)(const retro_game_info*)>(library,"retro_load_game");auto error=symbol<const char*(*)()>(library,"supermodel_boot_error");
  auto av=symbol<void(*)(retro_system_av_info*)>(library,"retro_get_system_av_info");
  auto state_size=symbol<size_t(*)()>(library,"retro_serialize_size");auto save=symbol<bool(*)(void*,size_t)>(library,"retro_serialize");auto restore=symbol<bool(*)(const void*,size_t)>(library,"retro_unserialize");
  symbol<void(*)(retro_environment_t)>(library,"retro_set_environment")(environment);
  symbol<void(*)(retro_video_refresh_t)>(library,"retro_set_video_refresh")(video);symbol<void(*)(retro_input_poll_t)>(library,"retro_set_input_poll")(poll);
  symbol<void(*)(retro_input_state_t)>(library,"retro_set_input_state")(input);symbol<void(*)(retro_audio_sample_batch_t)>(library,"retro_set_audio_sample_batch")(audio);
  init();check(registered,"core option registered");retro_game_info game{};game.path=argv[3];check(load(&game),error());
  retro_system_av_info info{};av(&info);allocate(info.geometry);check(allocated.base_width==496,"native load default");hardware.context_reset();run();
  check(draws==1&&polls==1&&samples>0,"native frame and PCM");
  std::vector<unsigned char> state(state_size());check(!state.empty()&&save(state.data(),state.size()),"native state capture");
  for(const char* value:{"2x","3x","4x","1x","4x","bad"}){
    const unsigned scale=(value[0]>='1'&&value[0]<='4')?unsigned(value[0]-'0'):1;
    const unsigned old_draws=draws,old_polls=polls,old_duplicates=duplicates;const size_t old_samples=samples;
    option=value;updated=true;run();
    check(draws==old_draws&&polls==old_polls&&samples==old_samples&&duplicates==old_duplicates+1,"resize defers drawing and guest work");
    av(&info);check(info.geometry.base_width==496*scale&&info.geometry.base_height==384*scale&&info.geometry.max_width==1984&&info.geometry.max_height==1536&&info.geometry.aspect_ratio==4.f/3.f,"pending AV exposes desired geometry");
    if(scale==3){hardware.context_destroy();run();check(polls==old_polls,"pending resize survives absent context");apply_pending();hardware.context_reset();}
    else apply_pending();
    run();check(draws==old_draws+1&&polls==old_polls+1,"new extent rendered next call");check(glGetError()==GL_NO_ERROR,"resize has no GL errors");
    const unsigned published=publishes;updated=true;run();check(publishes==published&&draws==old_draws+2,"unchanged option does not defer");
    check(state_size()==state.size()&&restore(state.data(),state.size()),"state format remains compatible across extents");reset();av(&info);check(info.geometry.base_width==496*scale,"reset and state restore preserve host resolution");
  }
  hardware.context_destroy();unload();option="3x";check(load(&game),error());av(&info);check(info.geometry.base_width==1488,"load reads persisted option");allocate(info.geometry);hardware.context_reset();run();
  hardware.context_destroy();unload();deinit();dlclose(library);
  glDeleteFramebuffers(1,&fbo);glDeleteTextures(1,&color);glDeleteRenderbuffers(1,&depth);
  eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);
  std::puts("PASS: actual Model3 GLES extents, deferred frontend allocation, context/reset/state/reload lifecycle");
}
