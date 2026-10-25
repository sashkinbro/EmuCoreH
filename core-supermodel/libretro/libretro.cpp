// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-3.0-or-later
// Libretro lifecycle for Super3/Supermodel. Emulation code retains upstream notices.
#include "libretro.h"
#include "adapter_helpers.h"
#include "input_mapping.h"
#include "runtime_guard.h"
#include "resolution.h"
#include "state_safety.h"
#include "Supermodel.h"
#include "Model3/Model3.h"
#include "GameLoader.h"
#include "BlockFile.h"
#include "Inputs/Inputs.h"
#include "Inputs/InputTypes.h"
#include "Graphics/New3D/New3D.h"
#include "Graphics/Render2D.h"
#include "OSD/Outputs.h"
#include "gles_presenter.h"
#include "compositor.h"
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
#include "libretro_vulkan.h"
#include "Graphics/Vulkan/VulkanScene.h"
#endif
#include <GLES3/gl3.h>
#include <filesystem>
#include <fstream>
#include <memory>
#include <vector>
#include <string>
#include <array>
#include <cstdio>
#include <exception>
namespace fs=std::filesystem;
using namespace model3retro;
namespace {
constexpr unsigned width=Resolution::native_width,height=Resolution::native_height;
constexpr const char* resolution_key="supermodel_internal_resolution";
const retro_variable core_variables[]={{resolution_key,"Internal resolution; 1x|2x|3x|4x"},{nullptr,nullptr}};
retro_environment_t environment=nullptr;
retro_video_refresh_t video=nullptr;
retro_audio_sample_t sample=nullptr;
retro_audio_sample_batch_t batch=nullptr;
retro_input_poll_t input_poll=nullptr;
retro_input_state_t input_state=nullptr;
retro_log_printf_t logger=nullptr;
retro_hw_render_callback hw{};
bool context_alive=false;
RuntimeGuard runtime_guard;
std::string error;
std::array<unsigned,2> controller_types{RETRO_DEVICE_JOYPAD,RETRO_DEVICE_JOYPAD};
class Outputs final:public COutputs {public:bool Initialize()override{return true;}void Attached()override{} protected:void SendOutput(EOutputs,UINT8,UINT8)override{}};
class NullRender3D final:public IRender3D {
public:
  void RenderFrame()override{} void BeginFrame()override{} void EndFrame()override{}
  void UploadTextures(unsigned,unsigned,unsigned,unsigned,unsigned)override{}
  void AttachMemory(const uint32_t*,const uint32_t*,const uint32_t*,const uint32_t*,const uint16_t*)override{}
  void SetStepping(int)override{} bool Init(unsigned,unsigned,unsigned,unsigned,unsigned,unsigned)override{return OKAY;}
  void SetSunClamp(bool)override{} void SetSignedShade(bool)override{} float GetLosValue(int)override{return 0;}
};
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
class VulkanFrameConsumer final:public New3D::SceneConsumer {
public:
  New3D::VulkanScene& renderer;
  CRender2D& tiles;
  VulkanFrameConsumer(New3D::VulkanScene& r,CRender2D& t):renderer(r),tiles(t){}
  std::array<float,4> Render(const New3D::SceneFrame& frame)override{
    renderer.SetTileLayers(tiles.HasFrame()?tiles.GetFrameBufferARGB():nullptr,
      tiles.HasTopSurface()?tiles.GetTopSurfaceARGB():nullptr,tiles.GetFrameWidth(),tiles.GetFrameHeight());
    return renderer.Render(frame);
  }
  void InvalidateTextures(unsigned level,unsigned x,unsigned y,unsigned w,unsigned h)override{renderer.InvalidateTextures(level,x,y,w,h);}
};
#endif
struct Host {
  Util::Config::Node config{"Global"};
  // Libretro supplies already calibrated values directly, so no desktop input system is initialized or polled.
  CInputs inputs{nullptr};
  Outputs outputs;
  NullRender3D null3d;
  CRender2D render2d{config};
  GlesPresenter presenter;
  std::unique_ptr<New3D::CNew3D> renderer;
  std::unique_ptr<CompositedRender3D> compositor;
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
  const retro_hw_render_interface_vulkan* vulkan_interface=nullptr;
  std::unique_ptr<New3D::VulkanScene> vulkan;
  std::unique_ptr<VulkanFrameConsumer> vulkan_consumer;
  retro_vulkan_image vulkan_image{};
#endif
  std::unique_ptr<CModel3> model;
  Game game;
  fs::path directory;
  size_t state_size=0;
  int gear=1;
  bool gl_ready=false;
  Resolution resolution;
  unsigned graphics_scale=0;
  bool loaded=false;
  std::vector<int16_t> pcm;
  bool fingerprint_audio=false;
  uint64_t audio_fingerprint=14695981039346656037ULL;
  Host() {
    config.Set("MultiThreaded",false);config.Set("GPUMultiThreaded",false);
    config.Set("EmulateSound",true);config.Set("EmulateDSB",true);
    config.Set("Balance","0");config.Set("BalanceLeftRight","0");config.Set("BalanceFrontRear","0");
    config.Set("NbSoundChannels","4");config.Set("SoundFreq","57.6");
    config.Set("SoundVolume","100");config.Set("MusicVolume","150");
    config.Set("LegacySoundDSP",false);config.Set("New3DEngine",true);config.Set("New3DAccurate",false);
#ifdef MODEL3_TEST_ACCURATE_GLES
    config.Set("New3DAccurate",true);
#endif
    config.Set("AndroidForceNew3D",true);config.Set("AndroidTileBlit",true);config.Set("AndroidSimpleShader",false);
    config.Set("AndroidClampRamVerts",true);config.Set("QuadRendering",false);config.Set("FlipStereo",false);
    config.Set("PowerPCFrequency","50");config.Set("InputSystem","libretro");
    // The standalone Model 3 netboard is a one-cabinet simulation; enable it
    // so games that require a network board pass their boot self-test.
    config.Set("Outputs","none");config.Set("ForceFeedback",false);config.Set("Network",false);config.Set("SimulateNet",true);
    config.Set("XResolution","496");config.Set("YResolution","384");config.Set("Throttle",false);
    config.Set("RefreshRate","60.0");config.Set("Crosshairs",0);
    render2d.Init(0,0,width,height,width,height);
  }
};
std::unique_ptr<Host> host;
std::vector<retro_input_descriptor> descriptors;
bool fail(const std::string& reason){error=reason;FatalLog(reason.c_str());return false;}
void fatal_runtime(const char* reason){fail(reason);if(environment)environment(RETRO_ENVIRONMENT_SHUTDOWN,nullptr);}
void tile_blit(const uint32_t* pixels,int w,int h,bool blend){if(host&&host->gl_ready&&pixels){host->presenter.UpdateFrameARGB(pixels,w,h);host->presenter.Render(blend);}}
void destroy_graphics(){
  if(!host||!host->gl_ready)return;
  if(host->model)host->model->AttachRenderers(&host->render2d,&host->null3d);
  host->compositor.reset();host->renderer.reset();
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
  host->vulkan_consumer.reset();host->vulkan.reset();host->vulkan_interface=nullptr;
#endif
  host->presenter.Shutdown();host->gl_ready=false;host->graphics_scale=0;
}
const char* resolution_variable(){retro_variable var{resolution_key,nullptr};if(environment)environment(RETRO_ENVIRONMENT_GET_VARIABLE,&var);return var.value;}
bool create_graphics(){
  if(!host||!host->model||!context_alive)return false;
  if(host->gl_ready&&host->graphics_scale!=host->resolution.Scale())destroy_graphics();
  if(host->gl_ready)return true;
  const auto geometry=host->resolution.Geometry();
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
  if(hw.context_type==RETRO_HW_CONTEXT_VULKAN){
    const retro_hw_render_interface* iface=nullptr;
    if(!environment(RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE,&iface)||!iface||iface->interface_type!=RETRO_HW_RENDER_INTERFACE_VULKAN||iface->interface_version<RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION)
      return fail("Frontend did not provide a compatible Vulkan interface");
    auto* vk=reinterpret_cast<const retro_hw_render_interface_vulkan*>(iface);
    if(!vk->set_image||!vk->lock_queue||!vk->unlock_queue)return fail("Frontend Vulkan queue contract is incomplete");
    host->vulkan_interface=vk;
    New3D::VulkanContext context{vk->gpu,vk->device,vk->queue,vk->queue_index,
      [vk]{vk->lock_queue(vk->handle);},[vk]{vk->unlock_queue(vk->handle);}};
    host->vulkan=std::make_unique<New3D::VulkanScene>(std::move(context));
    host->vulkan_consumer=std::make_unique<VulkanFrameConsumer>(*host->vulkan,host->render2d);
    host->renderer=std::make_unique<New3D::CNew3D>(host->config,host->game.name,host->vulkan_consumer.get());
    if(host->renderer->Init(0,0,geometry.base_width,geometry.base_height,geometry.base_width,geometry.base_height)!=OKAY)return fail("Vulkan scene initialization failed");
    host->model->AttachRenderers(&host->render2d,host->renderer.get());host->gl_ready=true;host->graphics_scale=host->resolution.Scale();
    CRender2D::SetAndroidTileBlit(nullptr);return true;
  }
#endif
  glBindFramebuffer(GL_FRAMEBUFFER,static_cast<GLuint>(hw.get_current_framebuffer()));
  if(!host->presenter.Init()){host->presenter.Shutdown();return fail("GLES presenter initialization failed");}
  host->presenter.Resize(geometry.base_width,geometry.base_height);
  host->renderer=std::make_unique<New3D::CNew3D>(host->config,host->game.name);
  if(host->renderer->Init(0,0,geometry.base_width,geometry.base_height,geometry.base_width,geometry.base_height)!=OKAY){host->renderer.reset();host->presenter.Shutdown();return fail("GLES New3D initialization failed");}
  host->compositor=std::make_unique<CompositedRender3D>(host->renderer.get(),&host->render2d,&host->presenter);
  host->model->AttachRenderers(&host->render2d,host->compositor.get());host->gl_ready=true;host->graphics_scale=host->resolution.Scale();
  CRender2D::SetAndroidTileBlit(tile_blit);return true;
}
const char* graphics_error(){return create_graphics()?nullptr:error.c_str();}
void context_reset(){context_alive=true;runtime_guard.Run(host&&host->model,graphics_error,[]{},fatal_runtime);}
void context_destroy(){destroy_graphics();context_alive=false;}
std::vector<unsigned char> read_file(const fs::path& path){
  std::ifstream stream(path,std::ios::binary|std::ios::ate);if(!stream)return {};
  auto n=stream.tellg();if(n<=0||static_cast<uint64_t>(n)>max_state_size)return {};
  std::vector<unsigned char> data(static_cast<size_t>(n));stream.seekg(0);if(!stream.read(reinterpret_cast<char*>(data.data()),n))return {};return data;
}
bool write_file(const fs::path& path,const unsigned char* data,size_t size){std::ofstream f(path,std::ios::binary|std::ios::trunc);return f&&static_cast<bool>(f.write(reinterpret_cast<const char*>(data),static_cast<std::streamsize>(size)));}
struct TempFile {fs::path path;~TempFile(){std::error_code ec;fs::remove(path,ec);}};
void nvram(bool save){
  if(!host||!host->model)return;
  const auto path=host->directory/"nvram.bin";
  CBlockFile file;
  if(save){
    TempFile tmp{host->directory/"nvram.tmp"};
    if(file.Create(tmp.path.string(),"Supermodel NVRAM State","Model3 libretro")!=OKAY)return;
    const int32_t version=0;file.Write(&version,sizeof(version));file.Write(host->game.name);host->model->SaveNVRAM(&file);file.Close();
    if(read_file(tmp.path).empty())return;
    // POSIX rename atomically replaces the previous NVRAM; no process-global cwd change.
    if(std::rename(tmp.path.string().c_str(),path.string().c_str())!=0)fail("Unable to persist NVRAM");
  }else{
    auto bytes=read_file(path);if(bytes.empty()||!valid_state_scalars(bytes.data(),bytes.size()))return;
    if(file.Load(path.string())!=OKAY||file.FindBlock("Supermodel NVRAM State")!=OKAY)return;
    int32_t version=-1;if(file.Read(&version,sizeof(version))!=sizeof(version)||version!=0)return;
    host->model->LoadNVRAM(&file);
  }
}
void unload(){
  if(!host)return;
  if(host->loaded)nvram(true);destroy_graphics();CRender2D::SetAndroidTileBlit(nullptr);
  host.reset();descriptors.clear();
}
int16_t state(unsigned p,unsigned device,unsigned index,unsigned id){return input_state&&p<2&&controller_types[p]!=RETRO_DEVICE_NONE?input_state(p,device,index,id):0;}
bool button(unsigned p,unsigned id){
  return state(p,RETRO_DEVICE_JOYPAD,0,id)!=0||
    ((id==RETRO_DEVICE_ID_JOYPAD_L2||id==RETRO_DEVICE_ID_JOYPAD_R2)&&
      state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_BUTTON,id)>0);
}
void value(CInput* input,uint16_t n){input->prevValue=input->value;input->value=n;}
void poll_inputs(){
  if(input_poll)input_poll();auto& in=host->inputs;
  for(unsigned p=0;p<2;p++){
    const auto x=state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_LEFT,RETRO_DEVICE_ID_ANALOG_X);
    const auto y=state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_LEFT,RETRO_DEVICE_ID_ANALOG_Y);
    value(in.coin[p],button(p,RETRO_DEVICE_ID_JOYPAD_SELECT));value(in.start[p],button(p,RETRO_DEVICE_ID_JOYPAD_START));
    value(in.service[p],button(p,RETRO_DEVICE_ID_JOYPAD_L3));value(in.test[p],button(p,RETRO_DEVICE_ID_JOYPAD_R3));
    value(in.up[p],button(p,RETRO_DEVICE_ID_JOYPAD_UP)||y<-16384);value(in.down[p],button(p,RETRO_DEVICE_ID_JOYPAD_DOWN)||y>16384);
    value(in.left[p],button(p,RETRO_DEVICE_ID_JOYPAD_LEFT)||x<-16384);value(in.right[p],button(p,RETRO_DEVICE_ID_JOYPAD_RIGHT)||x>16384);
    const bool a=button(p,action_buttons[0]),b=button(p,action_buttons[1]),c=button(p,action_buttons[2]),d=button(p,action_buttons[3]);
    value(in.punch[p],a);value(in.kick[p],b);value(in.guard[p],c);value(in.escape[p],d);
    value(in.shortPass[p],a);value(in.longPass[p],b);value(in.shoot[p],c);
    const bool gun=(controller_types[p]&RETRO_DEVICE_MASK)==RETRO_DEVICE_LIGHTGUN;
    const auto gx=gun?state(p,RETRO_DEVICE_LIGHTGUN,0,RETRO_DEVICE_ID_LIGHTGUN_SCREEN_X):x;
    const auto gy=gun?state(p,RETRO_DEVICE_LIGHTGUN,0,RETRO_DEVICE_ID_LIGHTGUN_SCREEN_Y):y;
    const bool trigger=gun?state(p,RETRO_DEVICE_LIGHTGUN,0,RETRO_DEVICE_ID_LIGHTGUN_TRIGGER)!=0:a;
    const bool reload=gun?(state(p,RETRO_DEVICE_LIGHTGUN,0,RETRO_DEVICE_ID_LIGHTGUN_RELOAD)||state(p,RETRO_DEVICE_LIGHTGUN,0,RETRO_DEVICE_ID_LIGHTGUN_IS_OFFSCREEN)):b;
    value(in.gunX[p],gun_coord(gx,150,651));value(in.gunY[p],gun_coord(gy,80,465));
    value(in.trigger[p],trigger||reload);in.trigger[p]->offscreenValue=reload;
    value(in.analogGunX[p],axis_byte(gx));value(in.analogGunY[p],axis_byte(gy,true));
    value(in.analogTriggerLeft[p],trigger);value(in.analogTriggerRight[p],b||reload);
    if(p==0){
      const auto steering=x?x:static_cast<int16_t>(button(p,RETRO_DEVICE_ID_JOYPAD_LEFT)?-32768:button(p,RETRO_DEVICE_ID_JOYPAD_RIGHT)?32767:0);
      value(in.steering,axis_byte(steering));
      value(in.accelerator,pedal_input(state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_BUTTON,RETRO_DEVICE_ID_JOYPAD_R2),button(p,RETRO_DEVICE_ID_JOYPAD_R2)));
      value(in.brake,pedal_input(state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_BUTTON,RETRO_DEVICE_ID_JOYPAD_L2),button(p,RETRO_DEVICE_ID_JOYPAD_L2)));
      value(in.gearShiftUp,button(p,RETRO_DEVICE_ID_JOYPAD_R));value(in.gearShiftDown,button(p,RETRO_DEVICE_ID_JOYPAD_L));
      host->gear=shifted_gear(host->gear,in.gearShiftUp->value&&!in.gearShiftUp->prevValue,in.gearShiftDown->value&&!in.gearShiftDown->prevValue);
      value(in.gearShift4,static_cast<uint16_t>(host->gear));
      value(in.viewChange,a);value(in.handBrake,b);value(in.rearBrake,b?255:0);value(in.musicSelect,c);
      for(unsigned v=0;v<4;v++)value(in.vr[v],button(p,action_buttons[v]));
      value(in.shift,a);value(in.beat,b);value(in.charge,c);value(in.jump,d);
      value(in.analogJoyX,axis_byte(x));value(in.analogJoyY,analog_joystick_y(y));
      value(in.analogJoyTrigger1,a);value(in.analogJoyTrigger2,b);value(in.analogJoyEvent1,c);value(in.analogJoyEvent2,d);
      const auto rx=state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_RIGHT,RETRO_DEVICE_ID_ANALOG_X),ry=state(p,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_RIGHT,RETRO_DEVICE_ID_ANALOG_Y);
      value(in.twinJoyLeft1,x<-16384);value(in.twinJoyRight1,x>16384);value(in.twinJoyUp1,y<-16384);value(in.twinJoyDown1,y>16384);
      value(in.twinJoyLeft2,rx<-16384);value(in.twinJoyRight2,rx>16384);value(in.twinJoyUp2,ry<-16384);value(in.twinJoyDown2,ry>16384);
      value(in.twinJoyShot1,button(p,RETRO_DEVICE_ID_JOYPAD_L2));value(in.twinJoyShot2,button(p,RETRO_DEVICE_ID_JOYPAD_R2));value(in.twinJoyTurbo1,button(p,RETRO_DEVICE_ID_JOYPAD_L));value(in.twinJoyTurbo2,button(p,RETRO_DEVICE_ID_JOYPAD_R));
      value(in.skiX,axis_byte(x,true));value(in.skiY,axis_byte(y));value(in.skiPollLeft,a);value(in.skiPollRight,b);value(in.skiSelect1,c);value(in.skiSelect2,d);value(in.skiSelect3,button(p,RETRO_DEVICE_ID_JOYPAD_L));
      const auto truckP2=magical_p2_input(ry,b,state(1,RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_LEFT,RETRO_DEVICE_ID_ANALOG_Y),button(1,action_buttons[0]));
      value(in.magicalLever1,axis_byte(y,true));value(in.magicalLever2,truckP2.lever);value(in.magicalPedal1,a);value(in.magicalPedal2,truckP2.pedal);
      value(in.fishingRodX,axis_byte(x));value(in.fishingRodY,axis_byte(y));value(in.fishingStickX,axis_byte(rx));value(in.fishingStickY,axis_byte(ry));value(in.fishingReel,button(p,RETRO_DEVICE_ID_JOYPAD_R2)?255:0);value(in.fishingCast,a);value(in.fishingSelect,b);value(in.fishingTension,button(p,RETRO_DEVICE_ID_JOYPAD_L2)?255:0);
    }
  }
}
void describe_inputs(){
  descriptors.clear();
  const bool racing=(host->game.inputs&(Game::INPUT_VEHICLE|Game::INPUT_HARLEY))!=0;
  for(unsigned p=0;p<2;p++){
    auto add=[p](unsigned dev,unsigned index,unsigned id,const char* label){descriptors.push_back({p,dev,index,id,label});};
    add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_SELECT,"Coin");add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_START,"Start");add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_L3,"Service");add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_R3,"Test menu");
    add(RETRO_DEVICE_JOYPAD,0,action_buttons[0],racing?"View 1 / View change":"Button 1 / Trigger");add(RETRO_DEVICE_JOYPAD,0,action_buttons[1],racing?"View 2 / Hand brake":"Button 2 / Reload");add(RETRO_DEVICE_JOYPAD,0,action_buttons[2],"Button 3 / View 3");add(RETRO_DEVICE_JOYPAD,0,action_buttons[3],"Button 4 / View 4");
    add(RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_BUTTON,RETRO_DEVICE_ID_JOYPAD_R2,racing?"Accelerator pressure":"Right trigger pressure");add(RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_BUTTON,RETRO_DEVICE_ID_JOYPAD_L2,racing?"Brake pressure":"Left trigger pressure");
    add(RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_LEFT,RETRO_DEVICE_ID_ANALOG_X,racing?"Steering":"Aim / Left stick X");add(RETRO_DEVICE_ANALOG,RETRO_DEVICE_INDEX_ANALOG_LEFT,RETRO_DEVICE_ID_ANALOG_Y,"Aim / Left stick Y");
    add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_R2,racing?"Accelerator":"Right trigger");add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_L2,racing?"Brake":"Left trigger");add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_R,racing?"Shift up":"Right turbo");add(RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_L,racing?"Shift down":"Left turbo");
  }
  descriptors.push_back({0,0,0,0,nullptr});environment(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS,descriptors.data());
}
std::vector<unsigned char> capture_state(){
  if(!host||!host->model)return {};
  TempFile tmp{host->directory/"serialize.tmp"};CBlockFile file;
  if(file.Create(tmp.path.string(),"Supermodel Save State","Model3 libretro v1")!=OKAY)return {};
  host->model->SaveState(&file);
  // These previous button levels suppress duplicate gear edges after restore.
  const uint32_t shifts[2]={host->inputs.gearShiftUp->value,host->inputs.gearShiftDown->value};
  file.NewBlock("Libretro Input Latches",__FILE__);file.Write(shifts,sizeof(shifts));
  file.Close();auto data=read_file(tmp.path);
  if(!valid_blocks(data.data(),data.size()))return {};
  data.insert(data.begin(),4,0);write32(data.data(),static_cast<uint32_t>(host->gear));return data;
}
}
void model3_log(int level,const char* message){if(logger)logger(static_cast<retro_log_level>(level),"%s",message);}
bool model3_audio(unsigned n,INT16* lf,INT16* rf,INT16* lr,INT16* rr,bool flip){
  if(!host||n>8192)return true;
  auto& pcm=host->pcm;pcm.resize(size_t(n)*2);
  const auto layout=host->game.audio;
  if(layout==Game::STEREO_RL||layout==Game::QUAD_1_FRL_2_RRL||layout==Game::QUAD_1_RRL_2_FRL)flip=!flip;
  for(unsigned i=0;i<n;i++){
    auto l=mix_channel(lf?lf[i]:0,lr?lr[i]:0),r=mix_channel(rf?rf[i]:0,rr?rr[i]:0);
    if(layout==Game::MONO)l=r=mix_channel(l,r);
    pcm[i*2]=flip?r:l;pcm[i*2+1]=flip?l:r;
  }
    if(host->fingerprint_audio){
      for(auto sample:pcm){const auto bits=static_cast<uint16_t>(sample);
        host->audio_fingerprint=(host->audio_fingerprint^(bits&255))*1099511628211ULL;
        host->audio_fingerprint=(host->audio_fingerprint^(bits>>8))*1099511628211ULL;}
    }
    // All core work is synchronous. Never block the emulation worker on a full frontend ring.
  if(batch){size_t consumed=0;while(consumed<n){size_t accepted=batch(pcm.data()+consumed*2,n-consumed);if(accepted==0||accepted>n-consumed)break;consumed+=accepted;}}
  else if(sample)for(unsigned i=0;i<n;i++)sample(pcm[i*2],pcm[i*2+1]);return true;
}
unsigned SupermodelFrontendFramebuffer(){return hw.get_current_framebuffer?static_cast<unsigned>(hw.get_current_framebuffer()):0;}
extern "C" {
RETRO_API unsigned retro_api_version(){return RETRO_API_VERSION;}
RETRO_API void retro_set_environment(retro_environment_t cb){environment=cb;bool no_game=false;if(cb){cb(RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME,&no_game);cb(RETRO_ENVIRONMENT_SET_VARIABLES,const_cast<retro_variable*>(core_variables));}}
RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb){video=cb;}
RETRO_API void retro_set_audio_sample(retro_audio_sample_t cb){sample=cb;}
RETRO_API void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb){batch=cb;}
RETRO_API void retro_set_input_poll(retro_input_poll_t cb){input_poll=cb;}
RETRO_API void retro_set_input_state(retro_input_state_t cb){input_state=cb;}
RETRO_API void retro_set_controller_port_device(unsigned p,unsigned device){if(p<2)controller_types[p]=device;}
RETRO_API void retro_init(){ResetFatalLog();error.clear();if(environment){retro_log_callback cb{};if(environment(RETRO_ENVIRONMENT_GET_LOG_INTERFACE,&cb))logger=cb.log;}}
RETRO_API void retro_deinit(){unload();runtime_guard.Reset();logger=nullptr;context_alive=false;hw={};}
RETRO_API void retro_get_system_info(retro_system_info* info){if(info)*info={"Supermodel (Super3)","c410f82-emucoreh1","zip",true,true};}
RETRO_API void retro_get_system_av_info(retro_system_av_info* info){if(info)*info={host?host->resolution.Geometry():Resolution().Geometry(),{60.0,44100.0}};}
RETRO_API bool retro_load_game(const retro_game_info* game_info){
  unload();ResetFatalLog();runtime_guard.Reset();error.clear();
  if(!environment||!game_info||!game_info->path)return fail("A full-path Model 3 ZIP ROM set is required");
  try{
    if(!fs::is_regular_file(game_info->path))return fail("Model 3 ZIP file is missing");
    const char *system=nullptr,*save=nullptr;environment(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY,&system);environment(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY,&save);
    if(!system||!save)return fail("Frontend system and save directories are required");
    const fs::path xml=fs::path(system)/"supermodel"/"Games.xml";
    if(!fs::is_regular_file(xml))return fail("Missing system/supermodel/Games.xml ROM definitions");
    host=std::make_unique<Host>();host->resolution=Resolution(resolution_variable());GameLoader loader(xml.string());ROMSet roms;
    if(loader.Load(&host->game,&roms,game_info->path,"")){host.reset();return fail("ROM set is unsupported, incomplete, or has incorrect CRCs");}
    if(host->game.driveboard_type==Game::DRIVE_BOARD_SKI)host->config.Set("EmulateSkiBoard",true);
    host->directory=fs::path(save)/"supermodel"/host->game.name;fs::create_directories(host->directory);
    if(host->game.name.rfind("von2",0)==0)host->config.Set("AndroidSimpleShader",true);
    host->model=std::make_unique<CModel3>(host->config);
    if(host->model->Init()!=OKAY||host->model->LoadGame(host->game,roms)!=OKAY){host.reset();return fail("Model 3 initialization failed");}
    host->outputs.SetGame(host->game);host->model->AttachInputs(&host->inputs);host->model->AttachOutputs(&host->outputs);host->model->AttachRenderers(&host->render2d,&host->null3d);host->model->Reset();host->loaded=true;nvram(false);
    hw={};hw.context_type=RETRO_HW_CONTEXT_OPENGLES3;hw.context_reset=context_reset;hw.context_destroy=context_destroy;hw.depth=true;hw.stencil=true;hw.bottom_left_origin=true;hw.version_major=3;hw.version_minor=0;
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
    unsigned preferred=RETRO_HW_CONTEXT_OPENGLES3;
    if(environment(RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER,&preferred)&&preferred==RETRO_HW_CONTEXT_VULKAN){hw.context_type=RETRO_HW_CONTEXT_VULKAN;hw.bottom_left_origin=false;hw.version_major=1;hw.version_minor=0;}
#endif
    if(!environment(RETRO_ENVIRONMENT_SET_HW_RENDER,&hw)){unload();return fail(hw.context_type==RETRO_HW_CONTEXT_VULKAN?"Frontend rejected Vulkan hardware rendering":"Frontend rejected OpenGL ES 3 hardware rendering");}
    if(hw.context_type!=RETRO_HW_CONTEXT_VULKAN&&!hw.get_current_framebuffer){unload();return fail("Frontend did not provide a framebuffer callback");}
    describe_inputs();return true;
  }catch(const std::exception& e){unload();return fail(e.what());}
}
RETRO_API void retro_unload_game(){unload();}
RETRO_API void retro_reset(){if(host&&host->model){host->model->Reset();host->gear=1;}}
RETRO_API void retro_run(){
  if(host&&host->model&&context_alive&&!runtime_guard.Failed()){
    bool updated=false;
    if(environment(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE,&updated)&&updated){
      const auto previous=host->resolution.Geometry();
      if(host->resolution.Update(resolution_variable(),[](const retro_game_geometry& geometry){return environment(RETRO_ENVIRONMENT_SET_GEOMETRY,const_cast<retro_game_geometry*>(&geometry));})){
        if(video)video(nullptr,previous.base_width,previous.base_height,0);
        return;
      }
    }
  }
  runtime_guard.Run(host&&host->model&&context_alive,graphics_error,[]{
    const auto geometry=host->resolution.Geometry();
#ifdef MODEL3_ENABLE_EXPERIMENTAL_VULKAN
    if(host->vulkan){
      poll_inputs();host->model->RunFrame();
      auto& image=host->vulkan_image;image={};image.image_view=host->vulkan->View();image.image_layout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
      image.create_info.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;image.create_info.image=host->vulkan->Image();image.create_info.viewType=VK_IMAGE_VIEW_TYPE_2D;image.create_info.format=VK_FORMAT_R8G8B8A8_UNORM;image.create_info.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
      host->vulkan_interface->set_image(host->vulkan_interface->handle,&image,0,nullptr,VK_QUEUE_FAMILY_IGNORED);
      if(video)video(RETRO_HW_FRAME_BUFFER_VALID,geometry.base_width,geometry.base_height,0);
      return;
    }
#endif
    poll_inputs();glBindFramebuffer(GL_FRAMEBUFFER,SupermodelFrontendFramebuffer());glViewport(0,0,geometry.base_width,geometry.base_height);glDisable(GL_SCISSOR_TEST);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
#ifdef MODEL3_TEST_ACCURATE_GLES
    // Offscreen opaque/transparency surfaces use zero alpha to represent no
    // geometry. Preserve the frontend's opaque black clear above.
    glClearColor(0,0,0,0);
#endif
    host->model->RunFrame();
    if(host->render2d.HasTopSurface()){host->presenter.UpdateFrameARGB(host->render2d.GetTopSurfaceARGB(),host->render2d.GetFrameWidth(),host->render2d.GetFrameHeight());host->presenter.Render(true);}
    if(video)video(RETRO_HW_FRAME_BUFFER_VALID,geometry.base_width,geometry.base_height,0);
  },fatal_runtime);
}
RETRO_API size_t retro_serialize_size(){try{if(!host)return 0;if(!host->state_size){auto data=capture_state();if(!data.empty()&&data.size()<=max_state_size-state_header_size)host->state_size=data.size()+state_header_size;}return host->state_size;}catch(...){return 0;}}
RETRO_API bool retro_serialize(void* data,size_t size){try{if(!host||size<retro_serialize_size()||!host->state_size)return false;auto bytes=capture_state();return pack_state(data,size,host->game.name,bytes.data(),bytes.size());}catch(...){return false;}}
RETRO_API bool retro_unserialize(const void* data,size_t size){
  try{if(!host||!host->model)return false;const unsigned char* payload=nullptr;size_t length=0;
    if(!unpack_state(data,size,host->game.name,payload,length)||length<4||!valid_gear(read32(payload))||!valid_blocks(payload+4,length-4))return false;
    const auto expected=capture_state();
    if(expected.size()<4||!same_block_layout(payload+4,length-4,expected.data()+4,expected.size()-4)||!valid_state_scalars(payload+4,length-4))return false;
    TempFile tmp{host->directory/"unserialize.tmp"};if(!write_file(tmp.path,payload+4,length-4))return false;
    CBlockFile file;if(file.Load(tmp.path.string())!=OKAY||file.FindBlock("Model 3")!=OKAY)return false;
    host->model->LoadState(&file);host->gear=static_cast<int>(read32(payload));
    if(file.FindBlock("Libretro Input Latches")!=OKAY)return false;
    uint32_t shifts[2]{};file.Read(shifts,sizeof(shifts));
    host->inputs.gearShiftUp->value=host->inputs.gearShiftUp->prevValue=static_cast<uint16_t>(shifts[0]);
    host->inputs.gearShiftDown->value=host->inputs.gearShiftDown->prevValue=static_cast<uint16_t>(shifts[1]);
    return true;
  }catch(...){return false;}
}
RETRO_API void retro_cheat_reset(){}
RETRO_API void retro_cheat_set(unsigned,bool,const char*){}
RETRO_API bool retro_load_game_special(unsigned,const retro_game_info*,size_t){return false;}
RETRO_API unsigned retro_get_region(){return RETRO_REGION_NTSC;}
RETRO_API void* retro_get_memory_data(unsigned){return nullptr;}
RETRO_API size_t retro_get_memory_size(unsigned){return 0;}
__attribute__((visibility("default"))) const char* supermodel_boot_error(){return error.c_str();}
__attribute__((visibility("default"))) unsigned supermodel_input_flags(){return host&&host->loaded?host->game.inputs:0;}
__attribute__((visibility("default"))) const char* supermodel_game_name(){return host&&host->loaded?host->game.name.c_str():"";}
__attribute__((visibility("default"))) void supermodel_reset_audio_fingerprint(){if(host){host->fingerprint_audio=true;host->audio_fingerprint=14695981039346656037ULL;}}
__attribute__((visibility("default"))) uint64_t supermodel_audio_fingerprint(){return host&&host->fingerprint_audio?host->audio_fingerprint:0;}
}
