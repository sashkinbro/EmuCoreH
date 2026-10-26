/* Actual SCSP-v5 pipeline serializer and actual DSP execution, authored data. */
#include "scspdsp.h"
#include <string.h>
#include <stdio.h>
ScspDsp scsp_dsp;
u8 *SoundRam;
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d\n",__LINE__); return 1; } } while (0)
int main(void) {
   ScspDsp expected = {0}, restored;
   unsigned char ram_a[0x80000] = {0}, ram_b[0x80000] = {0};
   expected.need_nofl = 1;
   expected.product = -123456789123LL;
   expected.read_value = 0x123456;
   expected.write_value = 0x2345;
   expected.read_pending = 2;
   expected.write_pending = 1;
   expected.shift_reg = 0x1234567;
   expected.io_addr = 19;
   expected.rbl = 8192;
   expected.mdec_ct = 3;
   restored = expected;
   FILE *fp = tmpfile(); CHECK(fp);
   CHECK(ScspDspSavePipeline(&expected,fp)==0);
   CHECK(ftell(fp)==32); /* Seven fields; the signed product occupies 8 bytes. */
   restored.need_nofl = restored.product = restored.read_value = restored.write_value = 0;
   restored.read_pending = restored.write_pending = restored.shift_reg = 0;
   rewind(fp); CHECK(ScspDspLoadPipeline(&restored,fp)==0);
   CHECK(memcmp(&expected,&restored,sizeof(expected))==0);
   for (int step=0;step<128;++step) {
      ScspDspExec(&expected,step,ram_a);
      ScspDspExec(&restored,step,ram_b);
      CHECK(memcmp(&expected,&restored,sizeof(expected))==0);
   }
   CHECK(memcmp(ram_a,ram_b,sizeof(ram_a))==0);
   /* A short local pipeline tail must fail without partial state mutation. */
   fclose(fp); fp=tmpfile(); CHECK(fp);
   fwrite("short",1,5,fp); rewind(fp);
   expected = restored;
   CHECK(ScspDspLoadPipeline(&restored,fp)==-1);
   CHECK(memcmp(&expected,&restored,sizeof(expected))==0);
   fclose(fp);
   puts("PASS: SCSP-v5 DSP pipeline roundtrip, execution and truncated-tail rejection");
   return 0;
}
