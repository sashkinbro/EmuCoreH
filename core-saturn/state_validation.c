/* SPDX-License-Identifier: GPL-3.0-only */
#include "state_validation.h"
#include <stdint.h>
#include <string.h>
static uint32_t read_le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 |
           (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
int saturn_state_has_supported_chunks(const void *buffer, size_t size) {
    static const char tags[][4] = {
        {'C','A','R','T'}, {'C','S','2',' '}, {'M','S','H','2'}, {'S','S','H','2'},
        {'S','C','S','P'}, {'S','C','U',' '}, {'S','M','P','C'}, {'V','D','P','1'},
        {'V','D','P','2'}, {'O','T','H','R'}
    };
    static const uint32_t versions[] = {1, 4, 5, 5, 5, 4, 3, 1, 1, 2};
    const unsigned char *bytes = buffer;
    if (!bytes || size < 20 || memcmp(bytes, "YSS\1", 4) != 0 ||
        read_le32(bytes + 4) != 2 || read_le32(bytes + 8) != size - 20) return 0;
    size_t offset = 20;
    for (size_t i = 0; i < sizeof(versions) / sizeof(versions[0]); ++i) {
        if (size - offset < 12 || memcmp(bytes + offset, tags[i], 4) != 0 ||
            read_le32(bytes + offset + 4) != versions[i]) return 0;
        const uint32_t length = read_le32(bytes + offset + 8);
        offset += 12;
        if (length > size - offset) return 0;
        offset += length;
    }
    return offset == size;
}
