/* Compile actual CS2 state routines with function-section GC; no disc/BIOS required. */
#include "../src/cs2.c"

/* Unused machine services satisfy host linkers that retain public sections. */
yabsys_struct yabsys;
cartridge_struct *CartridgeArea;
CDInterface DummyCD;
CDInterface *CDCoreList[] = {NULL};
void ScuSendExternalInterrupt00(void) {}
void SmpcRecheckRegion(void) {}
void YabSetError(int type, const void *extra) { (void)type; (void)extra; }
void JapModemDeInit(void) {}
void NetlinkDeInit(void) {}
int NetlinkInit(const char *ip, const char *port) { (void)ip; (void)port; return 0; }
int JapModemInit(const char *ip, const char *port) { (void)ip; (void)port; return 0; }
int YabauseQuickLoadGame(void) { return 0; }
void ScspReceiveCDDA(const u8 *sector) { (void)sector; }
void NetlinkExec(u32 timing) { (void)timing; }
void JapModemExec(u32 timing) { (void)timing; }

static int disc_status(void) { return CDCORE_NORMAL; }
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)

int main(void)
{
   static Cs2 state;
   CDInterface disc = {0};
   disc.GetStatus = disc_status;
   Cs2Area = &state;
   Cs2Area->cdi = &disc;
   Cs2Reset();
   Cs2Area->datatranstype = CDB_DATATRANSTYPE_GETSECTOR;
   Cs2Area->datatranspartition = &state.partition[3];
   Cs2Area->datatranspartitionnum = 3;
   Cs2Area->datasectstotrans = 1;
   state.partition[3].numblocks = 1;
   state.partition[3].block[0] = &state.block[0];
   state.partition[3].blocknum[0] = 0;
   state.block[0].size = 8;
   memcpy(state.block[0].data, "CD-state", 8);
   FILE *fp = tmpfile();
   CHECK(fp);
   int size = Cs2SaveState(fp);
   CHECK(size > 0);
   Cs2Area->datatranspartition = &state.partition[7];
   CHECK(fseek(fp, 12, SEEK_SET) == 0);
   CHECK(Cs2LoadState(fp, 4, size) == size);
   CHECK(Cs2Area->datatranspartition == &state.partition[3]);
   unsigned char output[4];
   Cs2RapidCopyT1(output, 1);
   CHECK(memcmp(output, "CD-s", 4) == 0);
   Cs2Area->datatranspartition = NULL;
   CHECK(fseek(fp, 12, SEEK_SET) == 0);
   CHECK(Cs2LoadState(fp, 4, size) == size);
   CHECK(Cs2Area->datatranspartition == &state.partition[3]);
   Cs2RapidCopyT1(output, 1);
   CHECK(memcmp(output, "CD-s", 4) == 0);
   fclose(fp);

   /* GET-THEN-DELETE used to change the pointer without changing its index. */
   state.partition[5].numblocks = 1;
   state.reg.CR2 = 0;
   state.reg.CR3 = 5 << 8;
   state.reg.CR4 = 1;
   Cs2GetThenDeleteSectorData();
   CHECK(state.datatranspartition == &state.partition[5]);
   CHECK(state.datatranspartitionnum == 5);

   /* Never form a pointer outside the partition array from imported state. */
   state.datatranspartitionnum = MAX_SELECTORS;
   fp = tmpfile(); CHECK(fp);
   size = Cs2SaveState(fp); CHECK(size > 0);
   CHECK(fseek(fp, 12, SEEK_SET) == 0);
   CHECK(Cs2LoadState(fp, 4, size) < 0);
   fclose(fp);

   Cs2Reset();
   CHECK(state.datatranspartition == NULL && state.datatranspartitionnum == 0xFF);
   fp = tmpfile(); CHECK(fp);
   size = Cs2SaveState(fp); CHECK(size > 0);
   state.datatranspartition = &state.partition[7];
   CHECK(fseek(fp, 12, SEEK_SET) == 0);
   CHECK(Cs2LoadState(fp, 4, size) == size);
   CHECK(state.datatranspartition == NULL);
   fclose(fp);
   puts("PASS: actual CS2 transfer restore, data read, GET-DELETE index and invalid-index rejection");
   return 0;
}
