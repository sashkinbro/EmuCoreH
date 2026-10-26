/* Exercise actual SCSP serialization, including rejection before CPU restore. */
#define IMPROVED_SAVESTATES 1
#include "../src/scsp.c"
#include <string.h>
ScspDsp scsp_dsp;
u8 *SoundRam;
ScspInternal *ScspInternalVars;
u32 saved_scsp_cycles;
volatile u64 saved_m68k_cycles;
static unsigned cpu_loads;
static void cpu_save(FILE *fp) { unsigned char scalar[264]={0}; fwrite(scalar,1,sizeof(scalar),fp); }
static void cpu_load(FILE *fp) { unsigned char scalar[264]; fread(scalar,1,sizeof(scalar),fp); ++cpu_loads; }
static M68K_struct cpu;
M68K_struct *M68K=&cpu;
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d\n",__LINE__); return 1; } } while (0)
int main(void) {
   static unsigned char ram[0x80000];
   static ScspInternal internal;
   SoundRam=ram; ScspInternalVars=&internal;
   scsp_isr=scsp_reg; scsp_ccr=scsp_reg+0x400; scsp_dcr=scsp_reg+0x700;
   scsp.scsp_ram=SoundRam;
   cpu.SaveState=cpu_save; cpu.LoadState=cpu_load;
   scspsoundbufsize=7350; scspsoundlen=735; scsplines=263;
   CHECK(scsp_alloc_bufs()==0);
   for(int i=0;i<32;++i)scsp.slot[i].enxt=scsp_env_null_next;
   FILE *fp=tmpfile(); CHECK(fp);
   use_new_scsp=0;
   CHECK(SoundSaveState(fp)==-1 && ftell(fp)==0);
   use_new_scsp=1;
   int size=SoundSaveState(fp); CHECK(size>900000);
   rewind(fp); char tag[4]; unsigned version,length,mode;
   CHECK(fread(tag,1,4,fp)==4 && memcmp(tag,"SCSP",4)==0);
   CHECK(fread(&version,4,1,fp)==1 && version==6);
   CHECK(fread(&length,4,1,fp)==1 && length==(unsigned)size-12);
   CHECK(fread(&mode,4,1,fp)==1 && mode==saturn_state_current_audio_mode());
   CHECK(fseek(fp,12,SEEK_SET)==0);
   CHECK(SoundLoadState(fp,6,length)==(int)length && cpu_loads==1);
   /* Mismatched mode and old backend must never reach the CPU loader. */
   unsigned bad[]={mode^1,0,1,mode|4};
   for(unsigned i=0;i<sizeof(bad)/sizeof(bad[0]);++i) {
      CHECK(fseek(fp,12,SEEK_SET)==0); fwrite(&bad[i],4,1,fp);
      rewind(fp); CHECK(fseek(fp,12,SEEK_SET)==0);
      IsM68KRunning=0x5a; savedcycles=12345; ram[7]=0x6b;
      CHECK(SoundLoadState(fp,6,length)==-1);
      CHECK(cpu_loads==1 && IsM68KRunning==0x5a && savedcycles==12345 && ram[7]==0x6b);
   }
   CHECK(fseek(fp,12,SEEK_SET)==0);
   CHECK(SoundLoadState(fp,5,length)==-1 && cpu_loads==1 && ftell(fp)==12);
   CHECK(SoundLoadState(fp,6,3)==-1 && cpu_loads==1 && ftell(fp)==12);
   fclose(fp); free(scspchannel[0].data32); free(scspchannel[1].data32);
   puts("PASS: actual SCSP-v6 save/load and mode rejection preserve CPU/RAM before mutation");
   return 0;
}
