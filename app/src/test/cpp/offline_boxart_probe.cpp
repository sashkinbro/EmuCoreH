// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
// GPU-free Android probe: load the existing core and extract an owned synthetic
// GD-ROM texture before retro_init, retro_load_game, or retro_run is ever called.
//
// Prerequisite: build :app:assembleDebug. Then reproduce from the repository
// root in PowerShell without installing the app or changing its data:
// $sdk = "$env:LOCALAPPDATA/Android/Sdk"
// $nativeLibs = 'app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a'
// & "$sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++.cmd" -std=c++17 -Wall -Wextra -I app/src/main/cpp -I core/core/deps/libretro-common/include -I core/core/deps/stb app/src/test/cpp/offline_boxart_probe.cpp app/src/main/cpp/storage_vfs.cpp -ldl -o "$env:TEMP/offline_boxart_probe"
// $adb = "$sdk/platform-tools/adb.exe"
// & $adb devices -l
// $transport = 4 # Choose a currently connected arm64 device's transport_id.
// $remote = '/data/local/tmp/emucoreh-offline-cover-probe-20261015'
// & $adb -t $transport shell mkdir -p $remote
// & $adb -t $transport push "$env:TEMP/offline_boxart_probe" "$remote/offline_boxart_probe"
// & $adb -t $transport push "$nativeLibs/flycast_libretro.so" "$remote/flycast_libretro.so"
// & $adb -t $transport push "$nativeLibs/libc++_shared.so" "$remote/libc++_shared.so"
// & $adb -t $transport shell chmod 755 "$remote/offline_boxart_probe"
// foreach ($kind in @('gdi','chd','Chd','CHD')) { foreach ($format in @('rectangle','twiddled','vq')) { & $adb -t $transport shell "LD_LIBRARY_PATH=$remote $remote/offline_boxart_probe $remote/flycast_libretro.so $remote $format $kind"; if ($LASTEXITCODE -ne 0) { throw "Probe failed: $format $kind" } } }
// All generated fixtures and output stay in $remote; each invocation is a fresh
// process. This probes actual native VFS I/O, not a Java DocumentsProvider grant.
// CHD/Chd verify the library recognizes file extensions independently of case.
#include "storage_vfs.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <vector>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#include <stb_image.h>

static void le16(unsigned char* p, unsigned value) {
    p[0] = value;
    p[1] = value >> 8;
}
static void le32(unsigned char* p, unsigned value) {
    for (unsigned i = 0; i < 4; ++i) p[i] = value >> (8 * i);
}
static void both32(unsigned char* p, unsigned value) {
    le32(p, value);
    for (unsigned i = 0; i < 4; ++i) p[4 + i] = value >> (24 - 8 * i);
}
static void be32(unsigned char* p, unsigned value) {
    for (unsigned i = 0; i < 4; ++i) p[i] = value >> (24 - 8 * i);
}
static void be64(unsigned char* p, uint64_t value) {
    for (unsigned i = 0; i < 8; ++i) p[i] = value >> (56 - 8 * i);
}
static unsigned morton(unsigned x, unsigned y) {
    unsigned result = 0;
    for (unsigned bit = 0; bit < 3; ++bit)
        result |= ((y >> bit) & 1) << (2 * bit) | ((x >> bit) & 1) << (2 * bit + 1);
    return result;
}
static unsigned directoryRecord(unsigned char* p, unsigned lba, unsigned size,
        const char* name, unsigned nameLength, bool directory) {
    const unsigned length = 33 + nameLength + (nameLength % 2 == 0);
    p[0] = length;
    both32(p + 2, lba);
    both32(p + 10, size);
    p[25] = directory ? 2 : 0;
    le16(p + 28, 1);
    p[31] = 1;
    p[32] = nameLength;
    std::memcpy(p + 33, name, nameLength);
    return length;
}
static void writeFile(const std::string& path, const void* bytes, size_t length) {
    FILE* file = std::fopen(path.c_str(), "wb");
    assert(file != nullptr && std::fwrite(bytes, 1, length, file) == length);
    assert(std::fclose(file) == 0);
}
static void makeOwnedChd(const std::string& path, const std::vector<unsigned char>& track) {
    // Uncompressed CHDv5 with sparse zero hunks and three GD-ROM tracks.
    // Only 24 owned high-density sectors occupy storage; no chdman or ROM.
    constexpr unsigned sectorSize = 2448;
    constexpr unsigned hunkSize = 4 * sectorSize;
    constexpr unsigned logicalFrames = 45000 + 24;
    constexpr unsigned hunkCount = logicalFrames / 4;
    constexpr unsigned mapOffset = 124;
    constexpr unsigned metadataOffset = mapOffset + hunkCount * 4;
    const std::string metadata[] = {
        "TRACK:1 TYPE:MODE1 SUBTYPE:NONE FRAMES:300 PAD:0 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0",
        "TRACK:2 TYPE:AUDIO SUBTYPE:NONE FRAMES:44700 PAD:0 PREGAP:0 PGTYPE:AUDIO PGSUB:NONE POSTGAP:0",
        "TRACK:3 TYPE:MODE1 SUBTYPE:NONE FRAMES:24 PAD:0 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0"
    };
    unsigned metadataEnd = metadataOffset;
    for (const auto& value : metadata) metadataEnd += 16 + value.size() + 1;
    const unsigned dataOffset = ((metadataEnd + hunkSize - 1) / hunkSize) * hunkSize;
    std::vector<unsigned char> chd(dataOffset + 6 * hunkSize);
    std::memcpy(chd.data(), "MComprHD", 8);
    be32(chd.data() + 8, 124);
    be32(chd.data() + 12, 5);
    be64(chd.data() + 32, uint64_t(logicalFrames) * sectorSize);
    be64(chd.data() + 40, mapOffset);
    be64(chd.data() + 48, metadataOffset);
    be32(chd.data() + 56, hunkSize);
    be32(chd.data() + 60, sectorSize);
    unsigned offset = metadataOffset;
    for (unsigned i = 0; i < 3; ++i) {
        std::memcpy(chd.data() + offset, "CHGD", 4);
        const unsigned length = metadata[i].size() + 1;
        be32(chd.data() + offset + 4, 0x01000000 | length);
        be64(chd.data() + offset + 8, i == 2 ? 0 : offset + 16 + length);
        std::memcpy(chd.data() + offset + 16, metadata[i].c_str(), length);
        offset += 16 + length;
    }
    for (unsigned hunk = 0; hunk < 6; ++hunk)
        be32(chd.data() + mapOffset + (45000 / 4 + hunk) * 4, dataOffset / hunkSize + hunk);
    for (unsigned sector = 0; sector < 24; ++sector)
        std::memcpy(chd.data() + dataOffset + sector * sectorSize, track.data() + sector * 2048, 2048);
    writeFile(path, chd.data(), chd.size());
}
static std::string makeOwnedDisc(const std::string& directory, const std::string& format, const std::string& kind) {
    std::vector<unsigned char> track(24 * 2048);
    std::memset(track.data(), ' ', 256);
    std::memcpy(track.data(), "SEGA SEGAKATANA ", 16);
    std::memcpy(track.data() + 16, "SEGA ENTERPRISES", 16);
    std::memcpy(track.data() + 37, "GD-ROM", 6);
    std::memcpy(track.data() + 64, "T-OWNED01", 9);
    const char title[] = "EMUCOREH OWNED OFFLINE TEST";
    std::memcpy(track.data() + 128, title, sizeof(title) - 1);

    auto* pvd = track.data() + 16 * 2048;
    pvd[0] = 1;
    std::memcpy(pvd + 1, "CD001", 5);
    pvd[6] = 1;
    const char dot = 0;
    directoryRecord(pvd + 156, 45020, 2048, &dot, 1, true);

    auto* root = track.data() + 20 * 2048;
    const unsigned textureSize = format == "vq" ? 2048 + 16 : 8 * 8 * 2;
    directoryRecord(root, 45021, 16 + textureSize, "0GDTEX.PVR;1", 12, false);
    auto* pvr = track.data() + 21 * 2048;
    std::memcpy(pvr, "PVRT", 4);
    le32(pvr + 4, 8 + textureSize);
    pvr[8] = 1; // RGB565
    pvr[9] = format == "vq" ? 3 : format == "twiddled" ? 1 : 9;
    le16(pvr + 12, 8);
    le16(pvr + 14, 8);
    if (format == "vq") {
        for (unsigned i = 0; i < 4; ++i) {
            le16(pvr + 16 + i * 2, 0xf800);
            le16(pvr + 24 + i * 2, 0x001f);
        }
        for (unsigned y = 0; y < 4; ++y)
            for (unsigned x = 0; x < 4; ++x)
                pvr[16 + 2048 + morton(x, y)] = y < 2 ? 0 : 1;
    } else {
        for (unsigned y = 0; y < 8; ++y)
            for (unsigned x = 0; x < 8; ++x)
                le16(pvr + 16 + (format == "twiddled" ? morton(x, y) : y * 8 + x) * 2,
                    y < 4 ? 0xf800 : 0x001f);
    }
    writeFile(directory + "/track03.bin", track.data(), track.size());
    std::vector<unsigned char> lowDensity(32 * 2048);
    writeFile(directory + "/track01.bin", lowDensity.data(), lowDensity.size());
    std::vector<unsigned char> audio(4 * 2352);
    writeFile(directory + "/track02.raw", audio.data(), audio.size());
    const char gdi[] = "3\n1 0 4 2048 track01.bin 0\n2 300 0 2352 track02.raw 0\n3 45000 4 2048 track03.bin 0\n";
    const std::string path = directory + "/owned-offline.gdi";
    writeFile(path, gdi, sizeof(gdi) - 1);
    const auto chdPath = directory + "/owned-offline." + (kind == "gdi" ? "chd" : kind);
    makeOwnedChd(chdPath, track);
    return kind == "gdi" ? path : chdPath;
}

static unsigned environmentCalls;
static bool environment(unsigned command, void* data) {
    ++environmentCalls;
    if (command == RETRO_ENVIRONMENT_GET_VFS_INTERFACE)
        return GetStorageVfs(static_cast<retro_vfs_interface_info*>(data));
    if (command == RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION) {
        *static_cast<unsigned*>(data) = 2;
        return true;
    }
    return false;
}

int main(int argc, char** argv) {
    assert(argc == 5);
    const std::string format = argv[3];
    const std::string kind = argv[4];
    assert(format == "rectangle" || format == "twiddled" || format == "vq");
    assert(kind == "gdi" || kind == "chd" || kind == "Chd" || kind == "CHD");
    const auto disc = makeOwnedDisc(argv[2], format, kind);
    const std::string png = std::string(argv[2]) + "/owned-" + format + "-" + kind + ".png";
    void* core = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!core) { std::fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    const auto setEnvironment = reinterpret_cast<void (*)(retro_environment_t)>(dlsym(core, "retro_set_environment"));
    const auto scrape = reinterpret_cast<int (*)(const char*, const char*, char*, int, char*, int)>(
        dlsym(core, "emucoreh_scrape_disc"));
    assert(setEnvironment && scrape);
    setEnvironment(environment);
    assert(environmentCalls != 0);
    char title[128] = {};
    char product[16] = {};
    const int result = scrape(disc.c_str(), png.c_str(), title, sizeof(title), product, sizeof(product));
    std::printf("fresh-process scrape result=%d title='%s' product='%s'\n", result, title, product);
    std::fflush(stdout);
    assert(result == 3);
    assert(!std::strcmp(title, "EMUCOREH OWNED OFFLINE TEST"));
    assert(!std::strcmp(product, "T-OWNED01"));
    FILE* image = std::fopen(png.c_str(), "rb");
    unsigned char header[24] = {};
    assert(image && std::fread(header, 1, sizeof(header), image) == sizeof(header));
    std::fclose(image);
    const unsigned char signature[] = {137, 80, 78, 71, 13, 10, 26, 10};
    assert(!std::memcmp(header, signature, sizeof(signature)));
    assert(header[19] == 8 && header[23] == 8);
    int width = 0, height = 0, channels = 0;
    auto* pixels = stbi_load(png.c_str(), &width, &height, &channels, 4);
    assert(pixels && width == 8 && height == 8);
    for (unsigned y = 0; y < 8; ++y)
        for (unsigned x = 0; x < 8; ++x) {
            const auto* pixel = pixels + (y * 8 + x) * 4;
            assert(pixel[1] == 0 && pixel[3] == 255);
            assert(y < 4 ? pixel[0] >= 248 && pixel[2] == 0 : pixel[0] == 0 && pixel[2] >= 248);
        }
    stbi_image_free(pixels);
    std::printf("PASS extracted and verified all 8x8 PNG pixels before any emulator/game/renderer/network session: %s\n", png.c_str());
    dlclose(core);
}
