// SPDX-License-Identifier: GPL-3.0-only
// Derived from izzy2lost/Super3 c410f82; Android GLES changes retained.
#include "gles_presenter.h"

#include <GLES3/gl3.h>
#include "OSD/Logger.h"

#include <algorithm>
#include <cstring>

namespace {
static GLuint Compile(GLenum type, const char* src)
{
  if (!src) {
    ErrorLog("GlesPresenter: null shader source");
    return 0;
  }

  // Mali drivers can be strict about #version being the first token.
  // Trim leading BOM/whitespace/newlines before passing to the compiler.
  const unsigned char* u = reinterpret_cast<const unsigned char*>(src);
  if (u[0] == 0xEF && u[1] == 0xBB && u[2] == 0xBF) {
    src += 3;
  }
  while (*src) {
    const unsigned char c = static_cast<unsigned char>(*src);
    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
      ++src;
      continue;
    }
    break;
  }

  GLuint sh = glCreateShader(type);
  if (!sh) {
    ErrorLog("GlesPresenter: glCreateShader(%u) failed (err=0x%x)", (unsigned)type, (unsigned)glGetError());
    return 0;
  }
  glShaderSource(sh, 1, &src, nullptr);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok)
  {
    char log[2048];
    GLsizei n = 0;
    glGetShaderInfoLog(sh, (GLsizei)sizeof(log), &n, log);
    log[(n >= (GLsizei)sizeof(log)) ? ((GLsizei)sizeof(log) - 1) : n] = '\0';
    ErrorLog("GlesPresenter: shader compile failed (%s):\n%s",
                 (type == GL_VERTEX_SHADER) ? "VS" : (type == GL_FRAGMENT_SHADER) ? "FS" : "UNKNOWN",
                 log);
    glDeleteShader(sh);
    return 0;
  }
  return sh;
}
} // namespace

bool GlesPresenter::Init()
{
  if (!CreateProgram())
    return false;
  if (!CreateGeometry())
    return false;
  if (!CreateCrosshairProgram() || !CreateCrosshairGeometry()) {
    ErrorLog("GlesPresenter: crosshair renderer disabled");
    if (m_crossProgram) { glDeleteProgram(m_crossProgram); m_crossProgram = 0; }
    if (m_crossVbo) { glDeleteBuffers(1, &m_crossVbo); m_crossVbo = 0; }
    if (m_crossVao) { glDeleteVertexArrays(1, &m_crossVao); m_crossVao = 0; }
    m_uCrossColor = -1;
  }

  glGenTextures(1, &m_tex);
  glBindTexture(GL_TEXTURE_2D, m_tex);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glBindTexture(GL_TEXTURE_2D, 0);

  return true;
}

void GlesPresenter::Shutdown()
{
  if (m_tex) { glDeleteTextures(1, &m_tex); m_tex = 0; }
  if (m_vbo) { glDeleteBuffers(1, &m_vbo); m_vbo = 0; }
  if (m_vao) { glDeleteVertexArrays(1, &m_vao); m_vao = 0; }
  if (m_program) { glDeleteProgram(m_program); m_program = 0; }
  if (m_crossVbo) { glDeleteBuffers(1, &m_crossVbo); m_crossVbo = 0; }
  if (m_crossVao) { glDeleteVertexArrays(1, &m_crossVao); m_crossVao = 0; }
  if (m_crossProgram) { glDeleteProgram(m_crossProgram); m_crossProgram = 0; }
  m_uTex = -1;
  m_uCrossColor = -1;
  m_uploadRGBA.clear();
  m_outputW = m_outputH = m_srcW = m_srcH = 0;
}

bool GlesPresenter::CreateProgram()
{
  static const char* vs = R"glsl(#version 300 es
    precision highp float;
    precision highp int;
    in vec2 aPos;
    in vec2 aUv;
    out vec2 vUv;
    void main() {
      vUv = aUv;
      gl_Position = vec4(aPos, 0.0, 1.0);
    }
  )glsl";

  static const char* fs = R"glsl(#version 300 es
    precision mediump float;
    precision mediump int;
    precision lowp sampler2D;
    in vec2 vUv;
    uniform sampler2D uTex;
    layout(location=0) out vec4 oColor;
    void main() {
      oColor = texture(uTex, vUv);
    }
  )glsl";

  GLuint v = Compile(GL_VERTEX_SHADER, vs);
  GLuint f = Compile(GL_FRAGMENT_SHADER, fs);
  if (!v || !f)
  {
    if (v) glDeleteShader(v);
    if (f) glDeleteShader(f);
    return false;
  }

  m_program = glCreateProgram();
  glAttachShader(m_program, v);
  glAttachShader(m_program, f);
  glBindAttribLocation(m_program, 0, "aPos");
  glBindAttribLocation(m_program, 1, "aUv");
  glLinkProgram(m_program);
  glDeleteShader(v);
  glDeleteShader(f);

  GLint ok = 0;
  glGetProgramiv(m_program, GL_LINK_STATUS, &ok);
  if (!ok)
  {
    char log[2048];
    GLsizei n = 0;
    glGetProgramInfoLog(m_program, (GLsizei)sizeof(log), &n, log);
    log[(n >= (GLsizei)sizeof(log)) ? ((GLsizei)sizeof(log) - 1) : n] = '\0';
    ErrorLog("GlesPresenter: program link failed:\n%s", log);
    glDeleteProgram(m_program);
    m_program = 0;
    return false;
  }

  m_uTex = glGetUniformLocation(m_program, "uTex");
  return true;
}

bool GlesPresenter::CreateGeometry()
{
  glGenVertexArrays(1, &m_vao);
  glGenBuffers(1, &m_vbo);
  glBindVertexArray(m_vao);
  glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
  glBufferData(GL_ARRAY_BUFFER, sizeof(m_verts), m_verts, GL_DYNAMIC_DRAW);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 4, (void*)0);
  glEnableVertexAttribArray(1);
  glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 4, (void*)(sizeof(float) * 2));
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glBindVertexArray(0);
  return true;
}

bool GlesPresenter::CreateCrosshairProgram()
{
  static const char* vs = R"glsl(#version 300 es
    precision highp float;
    in vec2 aPos;
    void main() {
      gl_Position = vec4(aPos, 0.0, 1.0);
    }
  )glsl";

  static const char* fs = R"glsl(#version 300 es
    precision mediump float;
    uniform vec4 uColor;
    layout(location=0) out vec4 oColor;
    void main() {
      oColor = uColor;
    }
  )glsl";

  GLuint v = Compile(GL_VERTEX_SHADER, vs);
  GLuint f = Compile(GL_FRAGMENT_SHADER, fs);
  if (!v || !f)
  {
    if (v) glDeleteShader(v);
    if (f) glDeleteShader(f);
    return false;
  }

  m_crossProgram = glCreateProgram();
  glAttachShader(m_crossProgram, v);
  glAttachShader(m_crossProgram, f);
  glBindAttribLocation(m_crossProgram, 0, "aPos");
  glLinkProgram(m_crossProgram);
  glDeleteShader(v);
  glDeleteShader(f);

  GLint ok = 0;
  glGetProgramiv(m_crossProgram, GL_LINK_STATUS, &ok);
  if (!ok)
  {
    char log[2048];
    GLsizei n = 0;
    glGetProgramInfoLog(m_crossProgram, (GLsizei)sizeof(log), &n, log);
    log[(n >= (GLsizei)sizeof(log)) ? ((GLsizei)sizeof(log) - 1) : n] = '\0';
    ErrorLog("GlesPresenter: crosshair program link failed:\n%s", log);
    glDeleteProgram(m_crossProgram);
    m_crossProgram = 0;
    return false;
  }

  m_uCrossColor = glGetUniformLocation(m_crossProgram, "uColor");
  return true;
}

bool GlesPresenter::CreateCrosshairGeometry()
{
  glGenVertexArrays(1, &m_crossVao);
  glGenBuffers(1, &m_crossVbo);
  glBindVertexArray(m_crossVao);
  glBindBuffer(GL_ARRAY_BUFFER, m_crossVbo);
  glBufferData(GL_ARRAY_BUFFER, sizeof(float) * 24, nullptr, GL_DYNAMIC_DRAW);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 2, (void*)0);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glBindVertexArray(0);
  return true;
}

void GlesPresenter::Resize(int outputW, int outputH)
{
  m_outputW = std::max(1, outputW);
  m_outputH = std::max(1, outputH);
  UpdateQuadVerts();
}

void GlesPresenter::UpdateFrameARGB(const uint32_t* pixelsARGB, int width, int height)
{
  if (!pixelsARGB || width <= 0 || height <= 0)
    return;

  if (m_srcW != width || m_srcH != height)
  {
    m_srcW = width;
    m_srcH = height;
    glBindTexture(GL_TEXTURE_2D, m_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, m_srcW, m_srcH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);
    UpdateQuadVerts();
  }

  const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
  m_uploadRGBA.resize(count * 4);

  // Convert ARGB (0xAARRGGBB) -> RGBA8 bytes.
  // Note: input is packed in CPU endian; treat as integer and extract.
  for (size_t i = 0; i < count; ++i)
  {
    const uint32_t p = pixelsARGB[i];
    const uint8_t a = static_cast<uint8_t>(p >> 24);
    const uint8_t r = static_cast<uint8_t>(p >> 16);
    const uint8_t g = static_cast<uint8_t>(p >> 8);
    const uint8_t b = static_cast<uint8_t>(p >> 0);
    m_uploadRGBA[(i * 4) + 0] = r;
    m_uploadRGBA[(i * 4) + 1] = g;
    m_uploadRGBA[(i * 4) + 2] = b;
    m_uploadRGBA[(i * 4) + 3] = a;
  }

  glBindTexture(GL_TEXTURE_2D, m_tex);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, m_srcW, m_srcH, GL_RGBA, GL_UNSIGNED_BYTE, m_uploadRGBA.data());
  glBindTexture(GL_TEXTURE_2D, 0);
}

void GlesPresenter::UpdateQuadVerts()
{
  if (m_outputW <= 0 || m_outputH <= 0 || m_srcW <= 0 || m_srcH <= 0)
    return;

  const float outW = (float)m_outputW;
  const float outH = (float)m_outputH;
  const float srcW = (float)m_srcW;
  const float srcH = (float)m_srcH;

  float drawW;
  float drawH;
  if (m_stretch) {
    drawW = outW;
    drawH = outH;
  } else {
    const float scale = std::min(outW / srcW, outH / srcH);
    drawW = srcW * scale;
    drawH = srcH * scale;
  }

  const float ndcW = (drawW / outW) * 2.0f;
  const float ndcH = (drawH / outH) * 2.0f;

  const float l = -ndcW * 0.5f;
  const float r = +ndcW * 0.5f;
  const float b = -ndcH * 0.5f;
  const float t = +ndcH * 0.5f;

  // Triangle strip: (l,b)->(r,b)->(l,t)->(r,t)
  // pos.xy, uv.xy
  const float v[] = {
    l, b, 0.0f, 1.0f,
    r, b, 1.0f, 1.0f,
    l, t, 0.0f, 0.0f,
    r, t, 1.0f, 0.0f,
  };
  std::memcpy(m_verts, v, sizeof(v));

  glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
  glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(m_verts), m_verts);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlesPresenter::Render(bool alphaBlend)
{
  if (!m_program || !m_vao || !m_tex || m_outputW <= 0 || m_outputH <= 0)
    return;

  glViewport(0, 0, m_outputW, m_outputH);
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_CULL_FACE);
  glDisable(GL_STENCIL_TEST);

  if (alphaBlend) {
    glEnable(GL_BLEND);
    glBlendEquation(GL_FUNC_ADD);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  } else {
    glDisable(GL_BLEND);
  }

  glUseProgram(m_program);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, m_tex);
  glUniform1i(m_uTex, 0);

  glBindVertexArray(m_vao);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  glBindVertexArray(0);

  glBindTexture(GL_TEXTURE_2D, 0);
  glUseProgram(0);
}

void GlesPresenter::RenderCrosshair(float xNorm, float yNorm, float r, float g, float b, float aspect,
                                    int viewX, int viewY, int viewW, int viewH)
{
  if (!m_crossProgram || !m_crossVao || viewW <= 0 || viewH <= 0)
    return;

  auto toNdc = [](float x, float y, float& outX, float& outY) {
    outX = (x * 2.0f) - 1.0f;
    outY = 1.0f - (y * 2.0f);
  };

  const float base = 0.01f;
  const float height = 0.02f;
  const float dist = 0.004f;
  const float bx = base * 0.5f;
  const float hy = (dist + height) * aspect;
  const float by = bx * aspect;

  float verts[24];
  int idx = 0;
  auto emit = [&](float x, float y) {
    float nx, ny;
    toNdc(x, y, nx, ny);
    verts[idx++] = nx;
    verts[idx++] = ny;
  };

  // Bottom triangle
  emit(xNorm, yNorm + dist);
  emit(xNorm + bx, yNorm + hy);
  emit(xNorm - bx, yNorm + hy);
  // Top triangle
  emit(xNorm, yNorm - dist);
  emit(xNorm - bx, yNorm - hy);
  emit(xNorm + bx, yNorm - hy);
  // Left triangle
  emit(xNorm - dist, yNorm);
  emit(xNorm - dist - height, yNorm + by);
  emit(xNorm - dist - height, yNorm - by);
  // Right triangle
  emit(xNorm + dist, yNorm);
  emit(xNorm + dist + height, yNorm - by);
  emit(xNorm + dist + height, yNorm + by);

  glViewport(viewX, viewY, viewW, viewH);
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_CULL_FACE);
  glDisable(GL_STENCIL_TEST);
  glEnable(GL_BLEND);
  glBlendEquation(GL_FUNC_ADD);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

  glUseProgram(m_crossProgram);
  glUniform4f(m_uCrossColor, r, g, b, 1.0f);
  glBindVertexArray(m_crossVao);
  glBindBuffer(GL_ARRAY_BUFFER, m_crossVbo);
  glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(verts), verts);
  glDrawArrays(GL_TRIANGLES, 0, 12);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glBindVertexArray(0);
  glUseProgram(0);
}


