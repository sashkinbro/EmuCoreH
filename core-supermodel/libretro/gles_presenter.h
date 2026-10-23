// SPDX-License-Identifier: GPL-3.0-only
// Derived from izzy2lost/Super3 c410f82; Android GLES changes retained.
#pragma once

#include <cstdint>
#include <vector>

// Minimal OpenGL ES presenter:
// - Uploads an ARGB8888 (0xAARRGGBB) framebuffer as a texture
// - Renders it with aspect-correct letterboxing
class GlesPresenter
{
public:
  bool Init();
  void Shutdown();

  void Resize(int outputW, int outputH);
  void SetStretch(bool stretch) { m_stretch = stretch; }

  // pixelsARGB: 0xAARRGGBB packed.
  void UpdateFrameARGB(const uint32_t* pixelsARGB, int width, int height);
  void Render(bool alphaBlend);
  void RenderCrosshair(float xNorm, float yNorm, float r, float g, float b, float aspect, int viewX, int viewY, int viewW, int viewH);

private:
  bool CreateProgram();
  bool CreateGeometry();
  bool CreateCrosshairProgram();
  bool CreateCrosshairGeometry();
  void UpdateQuadVerts();

  int m_outputW = 0;
  int m_outputH = 0;
  int m_srcW = 0;
  int m_srcH = 0;
  bool m_stretch = false;

  unsigned m_program = 0;
  unsigned m_vao = 0;
  unsigned m_vbo = 0;
  unsigned m_tex = 0;
  int m_uTex = -1;

  unsigned m_crossProgram = 0;
  unsigned m_crossVao = 0;
  unsigned m_crossVbo = 0;
  int m_uCrossColor = -1;

  // Interleaved: pos.xy, uv.xy (4 verts)
  float m_verts[16]{};

  // Converted upload buffer in RGBA8.
  std::vector<uint8_t> m_uploadRGBA;
};

