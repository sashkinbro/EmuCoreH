// SPDX-License-Identifier: GPL-2.0-or-later
#ifndef EMUCOREH_VDP2_TILE_ROW_CACHE_H
#define EMUCOREH_VDP2_TILE_ROW_CACHE_H

#include <cstdint>

// A rotation parameter has its own TileFetcher, so each one needs its own
// cached cell and row. Rotation overrun masks ignore the low three X bits,
// and all row-dependent fields use the full Y coordinate. The cache is local
// to one scanline, during which the renderer processes no VRAM writes.
class RotatedTileRowCache {
 public:
  template<unsigned Bpp, typename Fetcher>
  bool Fetch(Fetcher& fetcher, unsigned parameter, bool bitmap,
             std::uint32_t x, std::uint32_t y) {
    const std::uint32_t cell = x >> 3;
    if (!valid_[parameter] || cell_[parameter] != cell || y_[parameter] != y) {
      valid_[parameter] = true;
      cell_[parameter] = cell;
      y_[parameter] = y;
      outside_[parameter] = fetcher.template Fetch<Bpp>(bitmap, x, y);
    }
    return outside_[parameter];
  }

 private:
  bool valid_[2] = {};
  std::uint32_t cell_[2] = {};
  std::uint32_t y_[2] = {};
  bool outside_[2] = {};
};

#endif
