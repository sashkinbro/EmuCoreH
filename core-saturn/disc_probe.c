// SPDX-License-Identifier: GPL-3.0-only
#include <libretro.h>
#include <libchdr/chd.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#define PROBE_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SaturnProbe", __VA_ARGS__)
#else
#define PROBE_LOG(...) ((void)0)
#endif

#define PROBE_BYTES (64 * 1024)
static const char signature[] = "SEGA SEGASATURN ";

static size_t find_signature(const unsigned char *bytes, size_t size) {
    if (size < sizeof(signature) - 1) return SIZE_MAX;
    for (size_t offset = 0; offset + sizeof(signature) - 1 <= size; ++offset) {
        if (memcmp(bytes + offset, signature, sizeof(signature) - 1) == 0) return offset;
    }
    return SIZE_MAX;
}

/* Reads the first 64 KiB of a seekable stream and decompresses the first CHD
   hunk when needed. The stream remains owned by the caller. */
static size_t read_header_stream(FILE *file, const char *label,
                                 unsigned char *bytes, size_t capacity) {
    if (!file || !bytes || capacity == 0) return 0;
    if (fseek(file, 0, SEEK_SET) != 0) {
        PROBE_LOG("seek failed label=%s errno=%d", label, errno);
        return 0;
    }
    size_t read_size = fread(bytes, 1, capacity, file);
    if (read_size < 8) {
        PROBE_LOG("short read label=%s size=%zu errno=%d", label, read_size, errno);
        return 0;
    }
    if (memcmp(bytes, "MComprHD", 8) != 0) {
        return read_size;
    }

    if (fseek(file, 0, SEEK_SET) != 0) return 0;
    chd_file *chd = NULL;
    const chd_error open_error = chd_open_file(file, CHD_OPEN_READ, NULL, &chd);
    if (open_error != CHDERR_NONE) {
        PROBE_LOG("chd_open_file failed label=%s error=%d", label, (int)open_error);
        return 0;
    }
    const chd_header *info = chd_get_header(chd);
    size_t result = 0;
    /* CD CHDs normally use 2352/2448-byte sectors. Bound allocations for
       untrusted files, and search the whole first hunk because the sector
       layout may include subcode or a codec-specific prefix. */
    if (info && info->hunkbytes >= 32 && info->hunkbytes <= 16 * 1024 * 1024) {
        unsigned char *hunk = malloc(info->hunkbytes);
        if (hunk) {
            const chd_error read_error = chd_read(chd, 0, hunk);
            if (read_error == CHDERR_NONE) {
                result = capacity < info->hunkbytes ? capacity : info->hunkbytes;
                memcpy(bytes, hunk, result);
                const size_t found = find_signature(hunk, result);
                PROBE_LOG("chd hunk label=%s hunkbytes=%u unitbytes=%u signature=%zd",
                          label, info->hunkbytes, info->unitbytes,
                          found == SIZE_MAX ? -1 : (ssize_t)found);
            } else {
                PROBE_LOG("chd_read failed label=%s error=%d", label, (int)read_error);
            }
            free(hunk);
        } else {
            PROBE_LOG("chd hunk allocation failed label=%s hunkbytes=%u", label, info->hunkbytes);
        }
    } else {
        PROBE_LOG("chd invalid hunk label=%s hunkbytes=%u", label, info ? info->hunkbytes : 0);
    }
    /* chd_open_file does not own a caller-provided stream. */
    chd_close(chd);
    return result;
}

static size_t read_header_path(const char *path, unsigned char *bytes, size_t capacity) {
    if (!path || !*path) return 0;
    FILE *file = fopen(path, "rb");
    if (!file) {
        PROBE_LOG("open failed path=%s errno=%d", path, errno);
        return 0;
    }
    const size_t result = read_header_stream(file, path, bytes, capacity);
    fclose(file);
    return result;
}

/* ContentResolver descriptors cannot always be reopened through /proc/self/fd;
   duplicate the grant itself instead of reopening its path. */
static size_t read_header_fd(int fd, unsigned char *bytes, size_t capacity) {
    if (fd < 0) return 0;
    const int copy = dup(fd);
    if (copy < 0) {
        PROBE_LOG("dup failed fd=%d errno=%d", fd, errno);
        return 0;
    }
    FILE *file = fdopen(copy, "rb");
    if (!file) {
        PROBE_LOG("fdopen failed fd=%d errno=%d", fd, errno);
        close(copy);
        return 0;
    }
    const size_t result = read_header_stream(file, "fd", bytes, capacity);
    fclose(file);
    return result;
}

static size_t read_header_any(const char *path, unsigned char *bytes, size_t capacity) {
    int fd = -1;
    if (path && sscanf(path, "/proc/self/fd/%d", &fd) == 1) {
        return read_header_fd(fd, bytes, capacity);
    }
    return read_header_path(path, bytes, capacity);
}

RETRO_API int saturn_probe_disc(const char *path) {
    unsigned char *header = malloc(PROBE_BYTES);
    if (!header) return 0;
    const size_t length = read_header_any(path, header, PROBE_BYTES);
    const int result = find_signature(header, length) != SIZE_MAX ? 2 : 0;
    free(header);
    return result;
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
    unsigned char *header = malloc(PROBE_BYTES);
    if (!header) return 0;
    const size_t length = read_header_any(path, header, PROBE_BYTES);
    const size_t base = find_signature(header, length);
    int result = 0;
    if (base != SIZE_MAX && length >= base + 0x100) {
        copy_ascii(title, title_size, header + base + 0x60, 112);
        copy_ascii(serial, serial_size, header + base + 0x20, 10);
        result = 2;
    }
    free(header);
    return result;
}

RETRO_API int saturn_probe_disc_fd(int fd) {
    unsigned char *header = malloc(PROBE_BYTES);
    if (!header) return 0;
    const size_t length = read_header_fd(fd, header, PROBE_BYTES);
    const int result = find_signature(header, length) != SIZE_MAX ? 2 : 0;
    free(header);
    return result;
}
