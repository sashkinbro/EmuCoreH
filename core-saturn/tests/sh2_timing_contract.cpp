/* Isolated contract for the production inline timing snapshot methods.
 * Test-only construction avoids executable JIT allocation and platform threads. */
#include "sh2core.h"
#include "sh2_dynarec_devmiyax/DynarecSh2.h"
#include <cstring>
#include <cstdio>
DynarecSh2::DynarecSh2() {
   m_pDynaSh2 = new tagSH2{};
   pre_exe_count_ = memcycle_ = 0;
   one_step_ = false;
   m_pDynaSh2->getmemlong = 0x12345678;
}
DynarecSh2::~DynarecSh2() { delete m_pDynaSh2; }
int main() {
   DynarecSh2 core;
   const u32 expected[5] = {37, 211, 174, 9, 1};
   const u32 disturbed[5] = {0, 0, 0, 0, 0};
   u32 actual[5];
   core.LoadTiming(expected);
   core.SaveTiming(actual);
   if (std::memcmp(expected, actual, sizeof(actual))) return 1;
   core.LoadTiming(disturbed);
   core.LoadTiming(expected);
   core.SaveTiming(actual);
   if (std::memcmp(expected, actual, sizeof(actual))) return 2;
   std::puts("PASS: SH2 overshoot, cycle, exit, memory and single-step timing roundtrip");
   return 0;
}
