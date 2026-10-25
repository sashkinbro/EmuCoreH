// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/R3DShader.h"
#include "Graphics/New3D/R3DFrameBuffers.h"
#include <EGL/egl.h>
#include <cstdio>
#include <cstdlib>
static void check(bool ok,const char* reason){if(!ok){std::fprintf(stderr,"FAIL: %s\n",reason);std::exit(1);}}
unsigned SupermodelFrontendFramebuffer(){return 0;}
int main(){
  EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);check(eglInitialize(display,nullptr,nullptr),"EGL initialize");
  const EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
  EGLConfig config{};EGLint count=0;check(eglChooseConfig(display,attributes,&config,1,&count)&&count==1,"GLES config");
  const EGLint context_attributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},surface_attributes[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
  EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attributes);EGLSurface surface=eglCreatePbufferSurface(display,config,surface_attributes);
  check(eglMakeCurrent(display,surface,surface,context),"current context");
  Util::Config::Node settings("Global");settings.Set("QuadRendering",false);settings.Set("AndroidSimpleShader",false);
  {
    New3D::R3DFrameBuffers layers;check(layers.CreateFBO(1,1),"accurate framebuffer creation");
    check(glGetError()==GL_NO_ERROR,"accurate framebuffer creation has no GL errors");
    const New3D::Layer selected[]={New3D::Layer::colour,New3D::Layer::trans1,New3D::Layer::trans2};
    for(unsigned i=0;i<3;++i){
      layers.SetFBO(selected[i]);check(glGetError()==GL_NO_ERROR,"single layer GLES draw attachment selection");
      const GLfloat color[]={i==0?1.f:0.f,i==1?1.f:0.f,i==2?1.f:0.f,1.f};
      glClearBufferfv(GL_COLOR,0,color);
    }
    // Independent layer storage must survive attachment changes.
    for(unsigned i=0;i<3;++i){
      layers.SetFBO(selected[i]);unsigned char pixel[4]{};glReadBuffer(GL_COLOR_ATTACHMENT0);
      glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
      check(pixel[i]==255&&pixel[(i+1)%3]==0&&pixel[(i+2)%3]==0,"selected layer preserves independent pixels");
    }
    layers.SetFBO(New3D::Layer::trans12);check(glGetError()==GL_NO_ERROR,"dual transparency GLES draw attachment selection");
    const GLfloat first[]={.25f,0,0,1},second[]={0,.5f,0,1};
    glClearBufferfv(GL_COLOR,0,first);glClearBufferfv(GL_COLOR,1,second);
    layers.SetFBO(New3D::Layer::trans1);unsigned char pixel[4]{};glReadBuffer(GL_COLOR_ATTACHMENT0);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
    check(pixel[0]>=63&&pixel[0]<=64&&pixel[1]==0,"dual output zero addresses first transparency texture");
    layers.SetFBO(New3D::Layer::trans2);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
    check(pixel[0]==0&&pixel[1]>=127&&pixel[1]<=128,"dual output one addresses second transparency texture");
    layers.SetFBO(New3D::Layer::all);check(glGetError()==GL_NO_ERROR,"restore all framebuffer attachments");
    layers.SetFBO(New3D::Layer::none);
  }
  for(unsigned repeat=0;repeat<8;++repeat){
    GLuint program=0,shaders[3]{};GLsizei attached=0;
    {
      New3D::R3DShader shader(settings);check(shader.LoadShader(),"load shader");shader.SetShader();
      GLint current=0;glGetIntegerv(GL_CURRENT_PROGRAM,&current);program=static_cast<GLuint>(current);
      check(glIsProgram(program),"live shader program");glGetAttachedShaders(program,3,&attached,shaders);
      check(attached==2,"live vertex and fragment shaders");shader.SetShader(false);
    }
    check(!glIsProgram(program),"renderer shader program released on destruction");
    for(GLsizei i=0;i<attached;++i)check(!glIsShader(shaders[i]),"renderer shader objects released on destruction");
    check(glGetError()==GL_NO_ERROR,"shader lifecycle has no GL errors");
  }
  eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);
  std::puts("PASS: real GLES accurate transparency attachments and repeated shader resource recreation");
}
