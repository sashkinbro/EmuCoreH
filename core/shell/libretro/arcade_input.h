#pragma once
#include <cstdint>

namespace emucoreh {
// Libretro light-gun ids and joypad ids are different, including Trigger and Coin.
inline int SystemSpGunJoypadId(unsigned id) {
    switch (id) {
        case 2: return 0;  // Trigger -> B (Dreamcast A)
        case 3: return 8;  // Auxiliary A -> A (Dreamcast B)
        case 4: return 9;  // Auxiliary B -> X
        case 6: return 3;  // Start
        case 7: return 2;  // Select / Coin
        case 8: return 1;  // Auxiliary C -> Y
        case 9: return 4;  // Up
        case 10: return 5; // Down
        case 11: return 6; // Left
        case 12: return 7; // Right
        default: return -1;
    }
}

inline bool FilterCoinPress(bool pressed, bool& accepted, unsigned& inserted, unsigned limit) {
    if (!pressed) accepted = false;
    else if (!accepted && (limit == 0 || inserted < limit)) {
        accepted = true;
        ++inserted;
    }
    return pressed && accepted;
}

inline uint32_t ApplyCabinetSwitches(uint32_t buttons, uint32_t testKey, uint32_t serviceKey,
                                    unsigned cabinet, unsigned previous) {
    if (cabinet & 1) buttons &= ~testKey;
    else if (previous & 1) buttons |= testKey;
    if (cabinet & 2) buttons &= ~serviceKey;
    else if (previous & 2) buttons |= serviceKey;
    return buttons;
}
} // namespace emucoreh
