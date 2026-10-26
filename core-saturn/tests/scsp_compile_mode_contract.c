/* Compile/run both branches: legacy without a define, then SATURN_SYNCHRONOUS_SCSP=1. */
#include "scsp.h"
#include <stdio.h>
#if defined(SATURN_SYNCHRONOUS_SCSP) && SATURN_SYNCHRONOUS_SCSP
#ifdef ASYNC_SCSP
#error "Synchronous experiment must exclude every ASYNC_SCSP branch"
#endif
int main(void) { puts("PASS: synchronous SCSP experiment excludes async branches"); return 0; }
#else
#ifndef ASYNC_SCSP
#error "Legacy build must select upstream asynchronous SCSP"
#endif
int main(void) { puts("PASS: legacy SCSP compile branch remains asynchronous"); return 0; }
#endif
