// SPDX-License-Identifier: GPL-2.0-or-later
#include <cassert>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <random>

using uint8 = uint8_t;
using uint16 = uint16_t;
using uint32 = uint32_t;
#define INLINE inline
#include "vdp2_common.h"
static uint16 VRAM[262144];
static uint16 DummyTileNT[8 * 8 * 4 / sizeof(uint16)] = {};
static uint8 VRAM_Mode, RDBS_Mode, HRes, VCPRegs[4][8];
static uint16 BGON;
#include "vdp2_tile_fetcher_fixture.inc"
#include "vdp2_tile_row_cache.h"

template<unsigned Bpp>
void check(std::mt19937& random) {
    for (unsigned trial = 0; trial < 800; ++trial) {
        if ((trial & 15) == 0) for (auto& v : VRAM) v = static_cast<uint16>(random());
        VRAM_Mode = random() & 3; RDBS_Mode = random() & 255; BGON = random() & 0x30; HRes = random() & 7;
        for (auto& bank : VCPRegs) for (auto& slot : bank) slot = random() & 15;
        TileFetcher<true> reference[2]{};
        TileFetcher<true> cached[2]{};
        const bool bitmap = (random() & 1) != 0;
        for (unsigned n = 0; n < 2; ++n) {
            auto& t = reference[n];
            t.CRAOffs = random() & 0x7ff; t.BMSCC = random() & 1; t.BMSPR = random() & 1;
            t.BMPalNo = random() & 0x7f; t.BMSize = random() & 3; t.PlaneSize = random() & 3;
            t.PlaneOver = random() & 3; t.PlaneOverChar = random(); t.PNDSize = random() & 1;
            t.CharSize = random() & 1; t.AuxMode = random() & 1; t.Supp = random() & 0x3ff;
            uint8 regs[16]; for (auto& v : regs) v = random();
            t.Start(4 + n, bitmap, random() & 7, regs);
            cached[n] = t;
        }
        RotatedTileRowCache cache;
        uint32 x[2] = {uint32(random() & 4095), uint32(random() & 4095)};
        uint32 y[2] = {uint32(random() & 4095), uint32(random() & 4095)};
        for (unsigned i = 0; i < 500; ++i) {
            const unsigned n = random() & 1;
            switch (random() % 5) {
                case 0: x[n] += random() % 3; break;
                case 1: x[n] -= random() % 3; break;
                case 2: y[n] += random() % 3; break;
                case 3: x[n] = random() & 4095; y[n] = random() & 4095; break;
                default: x[n] = (x[n] & ~7u) | (random() & 7); break;
            }
            const bool a = reference[n].template Fetch<Bpp>(bitmap, x[n], y[n]);
            const bool b = cache.template Fetch<Bpp>(cached[n], n, bitmap, x[n], y[n]);
            const auto& r = reference[n]; const auto& c = cached[n];
            if (a != b || r.pcco != c.pcco || r.spr != c.spr || r.scc != c.scc ||
                r.tile_vrb != c.tile_vrb || r.cellx_xor != c.cellx_xor) {
                std::fprintf(stderr, "mismatch bpp=%u trial=%u pixel=%u map=%u x=%u y=%u bitmap=%d\\n", Bpp, trial, i, n, x[n], y[n], bitmap);
                std::abort();
            }
        }
    }
}
int main() {
    std::mt19937 random(0x53415455);
    check<4>(random); check<8>(random); check<16>(random); check<32>(random);
    std::puts("PASS: rotating tile cache matches production fetch for 1.6 million mixed pixels");
}
