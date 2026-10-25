// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/R3DShader.h"
#include <EGL/egl.h>
#include <cstdio>
#include <cstdlib>
static void check(bool ok,const char* reason){if(!ok){std::fprintf(stderr,"FAIL: %s\n",reason);std::exit(1);}}
int main(){
  EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);check(eglInitialize(display,nullptr,nullptr),"EGL initialize");
  const EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
  EGLConfig config{};EGLint count=0;check(eglChooseConfig(display,attributes,&config,1,&count)&&count==1,"GLES config");
  const EGLint context_attributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},surface_attributes[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
  EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attributes);EGLSurface surface=eglCreatePbufferSurface(display,config,surface_attributes);
  check(eglMakeCurrent(display,surface,surface,context),"current context");
  Util::Config::Node settings("Global");settings.Set("QuadRendering",false);settings.Set("AndroidSimpleShader",false);
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
  std::puts("PASS: real GLES shader resources released through repeated renderer recreation");
}
