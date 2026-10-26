/* Actual static SCSP register routines, with unrelated host facilities pruned. */
#include "../src/scsp.c"
#include <string.h>
ScspDsp scsp_dsp;
M68K_struct *M68K;
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d\n",__LINE__); return 1; } } while (0)
int main(void) {
   static const u32 aliases[] = { 0x02, 0x402, 0x442, 0x1402 };
   unsigned char expected[sizeof(scsp_reg)];
   scsp_isr = scsp_reg;
   scsp_ccr = scsp_reg + 0x400;
   scsp_dcr = scsp_reg + 0x700;
   use_new_scsp = 1;
   for (unsigned test=0;test<sizeof(aliases)/sizeof(aliases[0]);++test) {
      memset(scsp_reg,0xa5,sizeof(scsp_reg));
      memcpy(expected,scsp_reg,sizeof(expected));
      expected[0x400]=0x35; expected[0x401]=0x01;
      scsp_set_w(aliases[test],0x0135);
      CHECK(memcmp(expected,scsp_reg,sizeof(expected))==0);
      CHECK(scsp_get_w(aliases[test])==0x0135);
      CHECK(scsp.rbl==2 && scsp.rbp==0x35);
   }
   for (unsigned test=0;test<sizeof(aliases)/sizeof(aliases[0]);++test) {
      memset(scsp_reg,0xa5,sizeof(scsp_reg));
      memcpy(expected,scsp_reg,sizeof(expected));
      expected[0x400]=0x35;
      scsp_set_b(aliases[test]+1,0x35);
      CHECK(memcmp(expected,scsp_reg,sizeof(expected))==0);
      CHECK(scsp_get_b(aliases[test]+1)==0x35);
      CHECK(scsp.rbp==0x35);
   }
   puts("PASS: SCSP control-register aliases preserve DSP mirror and roundtrip getters");
   return 0;
}
