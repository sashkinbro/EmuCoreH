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
    const unsigned versions[] = {1,4,5,5,6,4,3,1,1,2};
    unsigned char state[144] = {0};
    unsigned offsets[10];
    unsigned cursor=20;
    memcpy(state, "YSS\1", 4); put32(state + 4, 2); put32(state + 8, 124);
    for (unsigned i = 0; i < 10; ++i) {
        offsets[i]=cursor;
        memcpy(state + cursor, tags[i], 4);
        put32(state + cursor + 4, versions[i]);
        if(i==4) {
            put32(state+cursor+8,4);
            put32(state+cursor+12,saturn_state_current_audio_mode());
            cursor+=4;
        }
        cursor+=12;
    }
    if (!saturn_state_has_supported_chunks(state, sizeof(state))) return 1;
    for (unsigned i = 0; i < 10; ++i) {
        put32(state + offsets[i]+4, versions[i] + 1);
        if (saturn_state_has_supported_chunks(state, sizeof(state))) return 2;
        put32(state + offsets[i]+4, versions[i]);
    }
    const unsigned mode_offset=offsets[4]+12;
    put32(state+mode_offset,saturn_state_current_audio_mode()^1);
    if(saturn_state_has_supported_chunks(state,sizeof(state))) return 6;
    put32(state+mode_offset,0); /* Unsupported old sound backend. */
    if(saturn_state_has_supported_chunks(state,sizeof(state))) return 7;
    put32(state+mode_offset,saturn_state_current_audio_mode());
    put32(state+offsets[4]+4,5); /* Unreleased v5 had no execution-mode identity. */
    if(saturn_state_has_supported_chunks(state,sizeof(state))) return 8;
    put32(state+offsets[4]+4,6);
    put32(state + 28, UINT32_MAX);
    if (saturn_state_has_supported_chunks(state, sizeof(state))) return 3;
    put32(state + 28, 0);
    for (size_t n = 0; n < sizeof(state); ++n)
        if (saturn_state_has_supported_chunks(state, n)) return 4;
    if (saturn_state_has_supported_chunks(NULL, sizeof(state))) return 5;
    puts("PASS: Saturn wrong-mode, old-backend, v5 and truncated states rejected before restore");
    return 0;
}
