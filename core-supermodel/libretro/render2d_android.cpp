// SPDX-License-Identifier: GPL-3.0-only
// Derived from izzy2lost/Super3 c410f82; Android GLES changes retained.
#include "Graphics/Render2D.h"

#ifdef __ANDROID__

#include <algorithm>
#include <cstring>

#include "OSD/Logger.h"
#include "Util/NewConfig.h"

namespace {
// Palette data from Model 3 hardware is in little-endian format (ABGR when viewed as 32-bit).
// Convert to ARGB (0xAARRGGBB) before handing it to the presenter.
static inline uint32_t ABGRToARGB(uint32_t abgr)
{
  return (abgr & 0xFF00FF00u) | ((abgr & 0x00FF0000u) >> 16) | ((abgr & 0x000000FFu) << 16);
}
static inline uint8_t GetA(uint32_t argb) { return static_cast<uint8_t>(argb >> 24); }
static inline uint8_t GetR(uint32_t argb) { return static_cast<uint8_t>(argb >> 16); }
static inline uint8_t GetG(uint32_t argb) { return static_cast<uint8_t>(argb >> 8); }
static inline uint8_t GetB(uint32_t argb) { return static_cast<uint8_t>(argb >> 0); }
static inline uint32_t ARGB(uint8_t a, uint8_t r, uint8_t g, uint8_t b)
{
  return (uint32_t(a) << 24) | (uint32_t(r) << 16) | (uint32_t(g) << 8) | uint32_t(b);
}

static CRender2D::AndroidTileBlitFn g_tileBlit = nullptr;

template <int bits, bool alphaTest, bool clip>
static inline void DrawTileLine(uint32_t *line,
                                int pixelOffset,
                                uint16_t tile,
                                int patternLine,
                                const uint32_t *vram,
                                const uint32_t *palette,
                                uint16_t mask)
{
  static_assert(bits == 4 || bits == 8, "Tiles are either 4- or 8-bit");

  if (bits == 8)
    patternLine *= 2;

  int patternOffset;
  if (bits == 4)
  {
    patternOffset = ((tile & 0x3FFF) << 1) | ((tile >> 15) & 1);
    patternOffset *= 32;
    patternOffset /= 4;
  }
  else
  {
    patternOffset = tile & 0x3FFF;
    patternOffset *= 64;
    patternOffset /= 4;
  }

  uint32_t colorHi = tile & ((bits == 4) ? 0x7FF0 : 0x7F00);

  if (bits == 4)
  {
    uint32_t pattern = vram[patternOffset + patternLine];
    for (int p = 7; p >= 0; p--)
    {
      if (!clip || (unsigned int)pixelOffset < 496u)
      {
        uint16_t maskTest = 1 << (15 - ((pixelOffset + 0) / 32));
        bool visible = (mask & maskTest) != 0;
        uint32_t pixel = visible ? ABGRToARGB(palette[((pattern >> (p * 4)) & 0xF) | colorHi]) : 0;
        if (!alphaTest || (visible && (pixel >> 24) != 0))
          line[pixelOffset] = pixel;
      }
      ++pixelOffset;
    }
  }
  else
  {
    for (int i = 0; i < 2; i++)
    {
      uint32_t pattern = vram[patternOffset + patternLine + i];
      for (int p = 3; p >= 0; p--)
      {
        if (!clip || (unsigned int)pixelOffset < 496u)
        {
          uint16_t maskTest = 1 << (15 - ((pixelOffset + 0) / 32));
          bool visible = (mask & maskTest) != 0;
          uint32_t pixel = visible ? ABGRToARGB(palette[((pattern >> (p * 8)) & 0xFF) | colorHi]) : 0;
          if (!alphaTest || (visible && (pixel >> 24) != 0))
            line[pixelOffset] = pixel;
        }
        ++pixelOffset;
      }
    }
  }
}

template <int bits, bool alphaTest>
static void DrawLayerLine(uint32_t *pixels, int layerNum, const uint32_t *vram, const uint32_t *regs, const uint32_t *palette, unsigned y)
{
  const uint16_t *nameTableBase = (const uint16_t *)&vram[(0xF8000 + layerNum * 0x2000) / 4];
  const uint16_t *hScrollTable = (const uint16_t *)&vram[(0xF6000 + layerNum * 0x400) / 4];
  bool lineScrollMode = (regs[0x60 / 4 + layerNum] & 0x8000) != 0;
  int hFullScroll = regs[0x60 / 4 + layerNum] & 0x3FF;
  int vScroll = (regs[0x60 / 4 + layerNum] >> 16) & 0x1FF;

  const uint16_t *maskTable = (const uint16_t *)&vram[0xF7000 / 4];
  if (layerNum < 2)
    maskTable += 1;

  const uint16_t maskPolarity = (layerNum & 1) ? 0xFFFF : 0x0000;

  uint32_t *line = pixels + y * 496;
  maskTable += y * 2;
    int hScroll = (lineScrollMode ? hScrollTable[y] : hFullScroll) & 0x1FF;
    int hTile = hScroll / 8;
    int hFine = hScroll & 7;
    int vFine = (y + vScroll) & 7;
    const uint16_t *nameTable = &nameTableBase[(64 * ((y + vScroll) / 8)) & 0xFFF];
    uint16_t mask = *maskTable ^ maskPolarity;

    int pixelOffset = -hFine;
    int extraTile = (hFine != 0) ? 1 : 0;

    DrawTileLine<bits, alphaTest, true>(line, pixelOffset, nameTable[(hTile ^ 1) & 63], vFine, vram, palette, mask);
    ++hTile;
    pixelOffset += 8;

    for (int tx = 1; tx < (62 - 1 + extraTile); tx++)
    {
      DrawTileLine<bits, alphaTest, false>(line, pixelOffset, nameTable[(hTile ^ 1) & 63], vFine, vram, palette, mask);
      ++hTile;
      pixelOffset += 8;
    }

    DrawTileLine<bits, alphaTest, true>(line, pixelOffset, nameTable[(hTile ^ 1) & 63], vFine, vram, palette, mask);
    ++hTile;
    pixelOffset += 8;

}
} // namespace

CRender2D::CRender2D(const Util::Config::Node &config) : m_config(config) {}

void CRender2D::SetAndroidTileBlit(AndroidTileBlitFn fn)
{
  g_tileBlit = fn;
}

bool CRender2D::Init(unsigned /*xOffset*/, unsigned /*yOffset*/, unsigned xRes, unsigned yRes, unsigned /*totalXRes*/, unsigned /*totalYRes*/)
{
  // Use the core's nominal resolution if present, but fall back to known TG size.
  m_xPixels = (xRes != 0) ? xRes : 496;
  m_yPixels = (yRes != 0) ? yRes : 384;
  if (m_xPixels != 496 || m_yPixels != 384)
  {
    // The tile generator renderer is currently hard-coded to the native TG resolution.
    m_xPixels = 496;
    m_yPixels = 384;
  }

  m_topSurface.assign(m_xPixels * m_yPixels, 0);
  m_bottomSurface.assign(m_xPixels * m_yPixels, 0);
  m_frame.assign(m_xPixels * m_yPixels, ARGB(0xFF, 0, 0, 0));
  return true;
}

void CRender2D::AttachRegisters(const uint32_t *regPtr) { m_regs = regPtr; }
void CRender2D::AttachPalette(const uint32_t *palPtr[2])
{
  m_palette[0] = palPtr[0];
  m_palette[1] = palPtr[1];
}
void CRender2D::AttachVRAM(const uint8_t *vramPtr) { m_vram = reinterpret_cast<const uint32_t *>(vramPtr); }

void CRender2D::BeginFrame(void) {}

std::pair<bool, bool> CRender2D::DrawTilemapLine(uint32_t *pixelsBottom, uint32_t *pixelsTop, unsigned y)
{
  if (!m_regs || !m_vram || !m_palette[0] || !m_palette[1])
    return {false, false};

  std::fill_n(pixelsBottom + y * 496, 496, 0);
  std::fill_n(pixelsTop + y * 496, 496, 0);

  unsigned priority = (m_regs[0x20 / 4] >> 8) & 0xF;

  bool noBottomSurface = true;
  static const int bottomOrder[4] = {3, 2, 1, 0};
  for (int i = 0; i < 4; i++)
  {
    int layerNum = bottomOrder[i];
    bool is4Bit = (m_regs[0x20 / 4] & (1 << (12 + layerNum))) != 0;
    bool enabled = (m_regs[0x60 / 4 + layerNum] & 0x80000000) != 0;
    bool selected = (priority & (1 << layerNum)) == 0;
    if (enabled && selected)
    {
      if (noBottomSurface)
      {
        if (is4Bit)
          DrawLayerLine<4, false>(pixelsBottom, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
        else
          DrawLayerLine<8, false>(pixelsBottom, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
      }
      else
      {
        if (is4Bit)
          DrawLayerLine<4, true>(pixelsBottom, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
        else
          DrawLayerLine<8, true>(pixelsBottom, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
      }
      noBottomSurface = false;
    }
  }

  bool noTopSurface = true;
  static const int topOrder[4] = {3, 2, 1, 0};
  for (int i = 0; i < 4; i++)
  {
    int layerNum = topOrder[i];
    bool is4Bit = (m_regs[0x20 / 4] & (1 << (12 + layerNum))) != 0;
    bool enabled = (m_regs[0x60 / 4 + layerNum] & 0x80000000) != 0;
    bool selected = (priority & (1 << layerNum)) != 0;
    if (enabled && selected)
    {
      if (noTopSurface)
      {
        if (is4Bit)
          DrawLayerLine<4, false>(pixelsTop, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
        else
          DrawLayerLine<8, false>(pixelsTop, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
      }
      else
      {
        if (is4Bit)
          DrawLayerLine<4, true>(pixelsTop, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
        else
          DrawLayerLine<8, true>(pixelsTop, layerNum, m_vram, m_regs, m_palette[layerNum / 2], y);
      }
      noTopSurface = false;
    }
  }

  return { !noTopSurface, !noBottomSurface };
}

void CRender2D::PreRenderFrame(void)
{
  if (m_frame.empty() || m_scanlineCapture)
    return;
  // Legacy timing has no scanline callbacks.
  m_surfacesPresent = {false, false};
  for (unsigned y = 0; y < m_yPixels; ++y)
    DrawScanline(y);
}

void CRender2D::BeginScanlineFrame()
{
  m_scanlineCapture = true;
  m_surfacesPresent = {false, false};
}

void CRender2D::DrawScanline(unsigned y)
{
  if (m_frame.empty() || y >= m_yPixels)
    return;
  const auto present = DrawTilemapLine(m_bottomSurface.data(), m_topSurface.data(), y);
  m_surfacesPresent.first |= present.first;
  m_surfacesPresent.second |= present.second;
}

void CRender2D::RenderFrameBottom(void)
{
  if (m_frame.empty())
    return;
  if (m_surfacesPresent.second)
    std::memcpy(m_frame.data(), m_bottomSurface.data(), m_frame.size() * sizeof(uint32_t));
  else
    std::fill(m_frame.begin(), m_frame.end(), ARGB(0xFF, 0, 0, 0));

  if (g_tileBlit && m_surfacesPresent.second) {
    g_tileBlit(m_bottomSurface.data(), (int)m_xPixels, (int)m_yPixels, false);
  }
}

void CRender2D::CompositeTopOntoFrame()
{
  if (!m_surfacesPresent.first)
    return;

  const uint32_t *src = m_topSurface.data();
  uint32_t *dst = m_frame.data();
  const size_t count = m_frame.size();
  for (size_t i = 0; i < count; i++)
  {
    uint32_t s = src[i];
    uint8_t a = GetA(s);
    if (a == 0)
      continue;
    if (a == 255)
    {
      dst[i] = s;
      continue;
    }

    uint32_t d = dst[i];
    const uint8_t sr = GetR(s), sg = GetG(s), sb = GetB(s);
    const uint8_t dr = GetR(d), dg = GetG(d), db = GetB(d);
    const uint32_t invA = 255 - a;
    uint8_t rr = static_cast<uint8_t>((sr * a + dr * invA) / 255);
    uint8_t rg = static_cast<uint8_t>((sg * a + dg * invA) / 255);
    uint8_t rb = static_cast<uint8_t>((sb * a + db * invA) / 255);
    dst[i] = ARGB(0xFF, rr, rg, rb);
  }
}

void CRender2D::RenderFrameTop(void)
{
  if (m_frame.empty())
    return;
  CompositeTopOntoFrame();
}

void CRender2D::EndFrame(void) { m_scanlineCapture = false; }

#endif // __ANDROID__

