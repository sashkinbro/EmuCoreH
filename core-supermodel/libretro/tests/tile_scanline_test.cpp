// SPDX-License-Identifier: GPL-3.0-only
#include "Graphics/Render2D.h"
#include "Util/NewConfig.h"
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

int main()
{
  Util::Config::Node config("tile-test");
  CRender2D renderer(config);
  assert(renderer.Init(0, 0, 496, 384, 496, 384));
  std::vector<uint32_t> vram(0x120000 / 4), palette(32768);
  uint32_t regs[64] = {};
  const uint32_t *palettes[2] = {palette.data(), palette.data()};
  renderer.AttachVRAM(reinterpret_cast<const uint8_t *>(vram.data()));
  renderer.AttachPalette(palettes);
  renderer.AttachRegisters(regs);
  regs[0x20 / 4] = 1 << 12; // layer A uses 4-bit tiles, below 3D
  regs[0x60 / 4] = 0x80000000;
  std::fill(vram.begin() + 16, vram.begin() + 24, 0x11111111); // solid tile 1
  std::fill(vram.begin() + 0xF8000 / 4, vram.begin() + 0xFA000 / 4, 0x00010001);
  std::fill(vram.begin() + 0xF7000 / 4, vram.begin() + 0xF7600 / 4, 0xFFFF0000);
  palette[1] = 0xFF0000FF; // hardware ABGR red
  const uint32_t *frame = renderer.GetFrameBufferARGB();
  renderer.BeginScanlineFrame();
  for (unsigned y = 0; y < 384; ++y)
  {
    if (y == 192) palette[1] = 0xFF00FF00;
    renderer.DrawScanline(y);
    // Daytona's IRQ2 handler clears the menu after the last visible line.
    if (y == 383)
    {
      std::fill(vram.begin() + 0xF8000 / 4, vram.begin() + 0xFA000 / 4, 0);
      palette[1] = 0;
    }
  }
  renderer.BeginFrame();
  renderer.PreRenderFrame();
  renderer.RenderFrameBottom();
  renderer.RenderFrameTop();
  assert(frame == renderer.GetFrameBufferARGB());
  assert(frame[100 * 496 + 100] == 0xFFFF0000); // completed line survives IRQ2
  assert(frame[300 * 496 + 100] == 0xFF00FF00); // palette sampled per line
  renderer.EndFrame();

  // A disabled layer must not retain pixels or surface flags from a prior frame.
  renderer.BeginScanlineFrame();
  regs[0x60 / 4] = 0;
  for (unsigned y = 0; y < 384; ++y) renderer.DrawScanline(y);
  renderer.DrawScanline(384); // reject invalid rows without writing outside buffers
  renderer.PreRenderFrame();
  renderer.RenderFrameBottom();
  renderer.RenderFrameTop();
  assert(!renderer.HasTopSurface());
  assert(std::all_of(frame, frame + 496 * 384, [](uint32_t p) { return p == 0xFF000000; }));
  renderer.EndFrame();

  // Sample 8-bit tiles, top/bottom priority and the line mask at display time.
  regs[0x60 / 4] = 0x80000000;
  regs[0x20 / 4] = 1 << 8; // layer A above 3D, 8-bit tiles
  std::fill(vram.begin() + 16, vram.begin() + 32, 0x01010101);
  std::fill(vram.begin() + 0xF8000 / 4, vram.begin() + 0xFA000 / 4, 0x00010001);
  palette[1] = 0xFFFF0000; // hardware ABGR blue
  renderer.BeginScanlineFrame();
  for (unsigned y = 0; y < 384; ++y)
  {
    if (y == 192)
    {
      regs[0x20 / 4] = 0; // subsequent rows move below 3D
      palette[1] = 0xFF00FF00;
    }
    if (y >= 300) vram[0xF7000 / 4 + y] = 0; // subsequent rows masked out
    renderer.DrawScanline(y);
  }
  renderer.PreRenderFrame();
  renderer.RenderFrameBottom();
  renderer.RenderFrameTop();
  assert(renderer.HasTopSurface());
  assert(frame[100 * 496 + 100] == 0xFF0000FF);
  assert(frame[250 * 496 + 100] == 0xFF00FF00);
  assert((frame[320 * 496 + 100] & 0x00FFFFFF) == 0);
  renderer.EndFrame();
  std::cout << "tile scanline capture passed\n";
}
