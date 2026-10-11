#include "arcade_input.h"
#include <cassert>
#include <iostream>

int main() {
    bool down = false;
    unsigned inserted = 0;
    assert(emucoreh::FilterCoinPress(true, down, inserted, 1));
    assert(emucoreh::FilterCoinPress(true, down, inserted, 1));
    assert(inserted == 1);
    assert(!emucoreh::FilterCoinPress(false, down, inserted, 1));
    assert(!emucoreh::FilterCoinPress(true, down, inserted, 1));
    assert(inserted == 1);
    assert(emucoreh::FilterCoinPress(true, down, inserted, 0));
    assert(inserted == 2);
    auto buttons = emucoreh::ApplyCabinetSwitches(0xffffffffu, 0x40000u, 0x4000u, 3, 0);
    assert(buttons == 0xfffbbfffu);
    buttons = emucoreh::ApplyCabinetSwitches(buttons, 0x40000u, 0x4000u, 0, 3);
    assert(buttons == 0xffffffffu);
    // Ordinary legacy inputs survive when no dedicated cabinet button owned them.
    assert(emucoreh::ApplyCabinetSwitches(0xfffbbfffu, 0x40000u, 0x4000u, 0, 0) == 0xfffbbfffu);
    assert(emucoreh::SystemSpGunJoypadId(2) == 0); // Trigger is Button A, not Coin.
    assert(emucoreh::SystemSpGunJoypadId(7) == 2); // Select is Coin.
    assert(emucoreh::SystemSpGunJoypadId(3) == 8); // Auxiliary A is Button B.
    std::cout << "Cabinet switches and coin-limit tests passed\n";
}
