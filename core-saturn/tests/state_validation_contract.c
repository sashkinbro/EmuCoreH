#include "../state_validation.h"
#include <stdint.h>
#include <stdio.h>
#include <string.h>
static void put32(unsigned char *p, uint32_t value) {
    for (unsigned i = 0; i < 4; ++i) p[i] = value >> (8 * i);
}
int main(void) {
    /* Authored chunk headers, not emulator/game state. */
    const char *tags[] = {"CART", "CS2 ", "MSH2", "SSH2", "SCSP", "SCU ", "SMPC", "VDP1", "VDP2", "OTHR"};
    const unsigned versions[] = {1,4,5,5,5,4,3,1,1,2};
    unsigned char state[140] = {0};
    memcpy(state, "YSS\1", 4); put32(state + 4, 2); put32(state + 8, 120);
    for (unsigned i = 0; i < 10; ++i) {
        memcpy(state + 20 + i * 12, tags[i], 4);
        put32(state + 24 + i * 12, versions[i]);
    }
    if (!saturn_state_has_supported_chunks(state, sizeof(state))) return 1;
    for (unsigned i = 0; i < 10; ++i) {
        put32(state + 24 + i * 12, versions[i] + 1);
        if (saturn_state_has_supported_chunks(state, sizeof(state))) return 2;
        put32(state + 24 + i * 12, versions[i]);
    }
    put32(state + 28, UINT32_MAX);
    if (saturn_state_has_supported_chunks(state, sizeof(state))) return 3;
    put32(state + 28, 0);
    for (size_t n = 0; n < sizeof(state); ++n)
        if (saturn_state_has_supported_chunks(state, n)) return 4;
    if (saturn_state_has_supported_chunks(NULL, sizeof(state))) return 5;
    puts("PASS: Saturn unsupported chunk versions and truncated envelopes are rejected before restore");
    return 0;
}
