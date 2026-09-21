#include "shader_chain.h"
#include "shader_chain_present.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cstdio>
#include <cstdlib>
int main(int argc, char **argv) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  EGLint a, b, n;
  if (!eglInitialize(d, &a, &b))
    return 2;
  EGLint cfg[] = {EGL_SURFACE_TYPE,
                  EGL_PBUFFER_BIT,
                  EGL_RENDERABLE_TYPE,
                  EGL_OPENGL_ES3_BIT,
                  EGL_RED_SIZE,
                  8,
                  EGL_GREEN_SIZE,
                  8,
                  EGL_BLUE_SIZE,
                  8,
                  EGL_ALPHA_SIZE,
                  8,
                  EGL_NONE};
  EGLConfig c;
  eglChooseConfig(d, cfg, &c, 1, &n);
  EGLint size[] = {EGL_WIDTH, 8, EGL_HEIGHT, 8, EGL_NONE};
  auto s = eglCreatePbufferSurface(d, c, size);
  EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  auto x = eglCreateContext(d, c, EGL_NO_CONTEXT, ctx);
  if (!eglMakeCurrent(d, s, s, x))
    return 3;
  GLuint tex, fbo;
  glGenTextures(1, &tex);
  glBindTexture(GL_TEXTURE_2D, tex);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE,
               nullptr);
  glGenFramebuffers(1, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex, 0);
  glClearColor(0.8f, 0.4f, 0.2f, 1);
  glClear(GL_COLOR_BUFFER_BIT);
  emucorer::shader_chain::SetPreset(argv[1], true);
  bool pass = true;
  for (int i = 0; i < 3; i++) {
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glClearColor(i == 1 ? 0.2f : 0.8f, i == 1 ? 0.8f : 0.4f, 0.2f, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glColorMask(i == 1 ? GL_FALSE : GL_TRUE, i == 1 ? GL_FALSE : GL_TRUE,
                GL_TRUE, GL_TRUE);
    if (!emucoreh::shader_chain_present::Present(fbo, 0, 0, 8, 8, 8, 8, 0, 0, 8,
                                                 8)) {
      printf("Present failed\n");
      return 4;
    }
    unsigned char pixel[4];
    glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    printf("mask=%d RGB=%d,%d,%d,%d error=%x\n", i, pixel[0], pixel[1],
           pixel[2], pixel[3], glGetError());
    pass &= abs(int(pixel[0]) - (i == 1 ? 51 : 204)) <= 2 &&
            abs(int(pixel[1]) - (i == 1 ? 204 : 102)) <= 2 &&
            abs(int(pixel[2]) - 51) <= 2;
  }
  emucoreh::shader_chain_present::Destroy();
  return pass ? 0 : 5;
}
