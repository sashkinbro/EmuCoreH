// SPDX-License-Identifier: GPL-2.0-or-later
#include "../release_diagnostics.h"
#include "../src/debug.h"
#include <string.h>
int main(void) {
    int evaluated = 0;
#if defined(SATURN_RELEASE_SILENT_DIAGNOSTICS)
    SATURN_DIAGNOSTIC_PRINTF("authored Saturn console sentinel %d", ++evaluated);
    SATURN_DIAGNOSTIC_FPRINTF(stderr, "authored Saturn stderr sentinel %d", ++evaluated);
    SATURN_DIAGNOSTIC_PUTS((++evaluated, "authored Saturn puts sentinel"));
    SATURN_DIAGNOSTIC_PUTCHAR(++evaluated);
    LOG("authored Saturn debug sentinel %d", ++evaluated);
    LOG_IF(++evaluated, "authored conditional sentinel");
    if (evaluated != 0) return 1;
#else
    SATURN_DIAGNOSTIC_PRINTF("debug console retained %d\n", ++evaluated);
    if (evaluated != 1) return 2;
#endif
    /* Legitimate stream serialization remains available in both variants. */
    FILE *state = tmpfile();
    if (!state) return 3;
    if (fprintf(state, "state:%d", 42) != 8) return 4;
    rewind(state);
    char data[9] = {0};
    if (fread(data, 1, 8, state) != 8 || strcmp(data, "state:42")) return 5;
    fclose(state);
    return 0;
}
