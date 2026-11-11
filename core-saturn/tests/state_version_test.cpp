// SPDX-License-Identifier: GPL-2.0-or-later
// Compile with the core's mednafen/state.cpp and libretro-common/include.
#include <cassert>
#include <cstdlib>
#include <cstdarg>
#include <libretro.h>
#include "../mednafen/state.h"

static unsigned observed_load = 0;
static void TestLog(retro_log_level, const char*, ...) {}
retro_log_printf_t log_cb = TestLog;

int LibRetro_StateAction(StateMem*, const unsigned load, const bool) {
    observed_load = load;
    return 1;
}

int main() {
    constexpr uint32_t version = 0x00102202;
    StateMem state = {};
    assert(MDFNSS_SaveSM(&state, version, nullptr, nullptr, nullptr));
    assert(observed_load == 0);
    state.loc = 0;
    assert(MDFNSS_LoadSM(&state, version));
    assert(observed_load == version); // Components must not receive boolean 1.
    observed_load = 0;
    state.loc = 0;
    assert(!MDFNSS_LoadSM(&state, version ^ 1));
    assert(observed_load == 0); // Reject other versions before touching guest state.
    free(state.data);
}
