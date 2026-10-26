/* Actual opt-in diagnostic code; all emulation/thread scheduling is stubbed. */
#define SATURN_ENABLE_SCSP_DIAGNOSTICS 1
#include "../src/scsp.c"
#include <string.h>
ScspDsp scsp_dsp;
M68K_struct *M68K;
YabEventQueue *q_scsp_frame_start = (YabEventQueue*)1;
YabEventQueue *q_scsp_finish = (YabEventQueue*)2;
u64 getM68KCounter(void) { return 1234; }
u64 getM68KDoneCounter(void) { return 789; }
int YaGetQueueSize(YabEventQueue *q) { return q==q_scsp_frame_start ? 1 : 0; }
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d\n",__LINE__); return 1; } } while (0)
int main(void) {
   new_scsp_outbuf_pos=17;
   scsp_record_phase(3,256,444,12);
   CHECK(strstr(saturn_scsp_debug_snapshot(),"\"phase\":0"));
   saturn_scsp_enable_diagnostics(true);
   scsp_record_phase(3,256,444,12);
   const char *snapshot=saturn_scsp_debug_snapshot();
   CHECK(strstr(snapshot,"\"stable\":true"));
   CHECK(strstr(snapshot,"\"phase\":3"));
   CHECK(strstr(snapshot,"\"frame\":256"));
   CHECK(strstr(snapshot,"\"pre\":444"));
   CHECK(strstr(snapshot,"\"inc\":12"));
   CHECK(strstr(snapshot,"\"pcm_pos\":17"));
   CHECK(strstr(snapshot,"\"requested\":1234"));
   CHECK(strstr(snapshot,"\"done\":789"));
   CHECK(strstr(snapshot,"\"qstart\":1"));
   saturn_scsp_enable_diagnostics(false);
   scsp_record_phase(8,0,0,0);
   CHECK(strstr(saturn_scsp_debug_snapshot(),"\"phase\":3"));
   puts("PASS: SCSP telemetry gating, consistent snapshot and counter/queue fields");
   return 0;
}
