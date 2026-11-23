// SPDX-License-Identifier: GPL-3.0-only
// Compiled only into PGO training builds (EMUCORE_PGO_MODE=generate).
// The cores are loaded with RTLD_NODELETE and the instrumentation process is
// killed, so the LLVM profile runtime never reaches its exit handler: write the
// accumulated counters explicitly whenever a game is unloaded.
#include <stdio.h>

void __llvm_profile_set_filename(const char *name);
int __llvm_profile_write_file(void);

void emucore_pgo_flush(void)
{
    static int initialised = 0;
    if (!initialised) {
        // %m merges every run of this binary into one raw profile.
        __llvm_profile_set_filename("/data/user/0/com.sbro.emucoreh/cache/pgo-" EMUCORE_PGO_NAME "-%m.profraw");
        initialised = 1;
    }
    __llvm_profile_write_file();
}
