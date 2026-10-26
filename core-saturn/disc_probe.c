// SPDX-License-Identifier: GPL-3.0-only
#include <libretro.h>
#include <libchdr/chd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int identify_header(const unsigned char *bytes, size_t size) {
    static const char signature[] = "SEGA SEGASATURN ";
    if (size >= 16 && memcmp(bytes, signature, 16) == 0) return 2;
    if (size >= 32 && memcmp(bytes + 16, signature, 16) == 0) return 2;
    return 0;
}

/* A separate file/CHD handle for every call: no emulator or CD globals. */
static size_t read_header(const char *path, unsigned char *bytes, size_t capacity) {
    if (!path || !*path) return 0;
    FILE *file = fopen(path, "rb");
    if (!file) return 0;
    size_t read_size = fread(bytes, 1, capacity, file);
    fclose(file);
    if (read_size < 8) return 0;
    if (memcmp(bytes, "MComprHD", 8) != 0) return read_size;
    chd_file *chd = NULL;
    if (chd_open(path, CHD_OPEN_READ, NULL, &chd) != CHDERR_NONE) return 0;
    const chd_header *info = chd_get_header(chd);
    size_t result = 0;
    /* CD CHDs normally use 2448-byte sectors. Bound allocations for untrusted files. */
    if (info && info->hunkbytes >= 32 && info->hunkbytes <= 16 * 1024 * 1024) {
        unsigned char *hunk = malloc(info->hunkbytes);
        if (hunk) {
            if (chd_read(chd, 0, hunk) == CHDERR_NONE) {
                result = capacity < info->hunkbytes ? capacity : info->hunkbytes;
                memcpy(bytes, hunk, result);
            }
            free(hunk);
        }
    }
    chd_close(chd);
    return result;
}

RETRO_API int saturn_probe_disc(const char *path) {
    unsigned char header[32];
    return identify_header(header, read_header(path, header, sizeof(header)));
}

static void copy_ascii(char *destination, size_t capacity, const unsigned char *source, size_t length) {
    if (!destination || !capacity) return;
    destination[0] = 0;
    for (size_t i = 0; i < length; ++i) {
        if (source[i] && (source[i] < 32 || source[i] > 126)) return;
    }
    while (length && (source[length - 1] == 0 || source[length - 1] == ' ')) --length;
    while (length && (*source == 0 || *source == ' ')) { ++source; --length; }
    if (length >= capacity) length = capacity - 1;
    for (size_t i = 0; i < length; ++i) destination[i] = source[i] ? (char)source[i] : ' ';
    destination[length] = 0;
}

/* Metadata uses independent handles, including /proc/self/fd for borrowed SAF FDs. */
RETRO_API int saturn_disc_metadata(const char *path, char *title, size_t title_size, char *serial, size_t serial_size) {
    unsigned char header[272];
    const size_t length = read_header(path, header, sizeof(header));
    if (identify_header(header, length) != 2) return 0;
    const size_t base = memcmp(header, "SEGA SEGASATURN ", 16) == 0 ? 0 : 16;
    if (length < base + 0x100) return 0;
    copy_ascii(title, title_size, header + base + 0x60, 112);
    copy_ascii(serial, serial_size, header + base + 0x20, 10);
    return 2;
}

RETRO_API int saturn_probe_disc_fd(int fd) {
    if (fd < 0) return 0;
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    return saturn_probe_disc(path);
}
