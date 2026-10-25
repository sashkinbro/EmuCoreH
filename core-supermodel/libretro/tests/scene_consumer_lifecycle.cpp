// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/New3D.h"
#include <cstdio>
#include <cstdlib>
#include <vector>
static unsigned glCalls=0;
extern "C" void __wrap_glGenBuffers(GLsizei,GLuint*){++glCalls;}
extern "C" GLuint __wrap_glCreateShader(GLenum){++glCalls;return 0;}
extern "C" void __wrap_glUseProgram(GLuint){++glCalls;}
extern "C" void __wrap_glViewport(GLint,GLint,GLsizei,GLsizei){++glCalls;}
extern "C" void __wrap_glDrawArrays(GLenum,GLint,GLsizei){++glCalls;}
extern "C" void __wrap_glDeleteBuffers(GLsizei,const GLuint*){++glCalls;}
static void check(bool ok,const char* message){if(!ok){std::fprintf(stderr,"FAIL: %s\n",message);std::exit(1);}}
bool ErrorLog(const char*,...){return true;}
void InfoLog(const char*,...){}
void DebugLog(const char*,...){}
GLuint SupermodelFrontendFramebuffer(){return 0;}
class Consumer final:public New3D::SceneConsumer {
public:
  unsigned calls=0,invalidations=0;
  std::array<float,4> Render(const New3D::SceneFrame& frame)override{
    ++calls;check(frame.width==992&&frame.height==768,"scaled scene extent");
    check(frame.nodes.empty(),"disabled viewport excluded");
    return {1,2,3,4};
  }
  void InvalidateTextures(unsigned level,unsigned x,unsigned y,unsigned w,unsigned h)override{
    check(level==1&&x==1024&&y==512&&w==32&&h==32,"original invalidation coordinates");++invalidations;
  }
};
int main(){
  Util::Config::Node config("Global");config.Set("New3DAccurate",false);
  Consumer consumer;
  std::vector<UINT32> low(0x100000),high(0x40000),polys(0x100000),rom(0x1000000);
  std::vector<UINT16> textures(2048*2048);
  high[0]=0x20; // Disabled viewport; no matrices or display lists are dereferenced.
  {
    New3D::CNew3D scene(config,"test",&consumer);
    check(scene.Init(0,0,992,768,992,768)==OKAY,"CPU scene init");scene.SetStepping(0x20);
    scene.AttachMemory(low.data(),high.data(),polys.data(),rom.data(),textures.data());
    scene.UploadTextures(1,1024,512,32,32);
    scene.RenderFrame();check(scene.GetLosValue(2)==0,"LOS delayed one frame");
    scene.RenderFrame();check(scene.GetLosValue(2)==3,"LOS returned by backend");
    check(consumer.calls==2&&consumer.invalidations==1,"consumer receives frames and invalidations");
  }
  check(glCalls==0,"CPU scene path never calls GL");
  std::puts("PASS: backend-neutral scene build and LOS latency without a GL context");
}
