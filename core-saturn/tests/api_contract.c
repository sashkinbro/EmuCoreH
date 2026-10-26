#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
int main(int argc, char **argv) {
  if (argc != 2) return 2;
  void *lib = dlopen(argv[1], RTLD_NOW);
  if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 3; }
  bool (*save)(void*,size_t)=dlsym(lib,"retro_serialize");
  bool (*load)(const void*,size_t)=dlsym(lib,"retro_unserialize");
  int (*probe)(const char*)=dlsym(lib,"saturn_probe_disc");
  int (*probe_fd)(int)=dlsym(lib,"saturn_probe_disc_fd");
  char data[32]={0};
  if(save(data,sizeof(data)) || load(data,sizeof(data))) { puts("FAIL: states accepted before first frame"); return 4; }
  if(!probe || !probe_fd) { puts("FAIL: missing disc probes"); return 5; }
  if(probe(NULL)!=0 || probe_fd(-1)!=0) return 6;
  const char *path="/data/local/tmp/emucoreh-saturn-probe.bin";
  FILE *f=fopen(path,"wb"); if(!f)return 7;
  fwrite("SEGA SEGASATURN ",1,16,f); fclose(f);
  int fd=open(path,O_RDONLY); if(fd<0)return 8;
  if(probe(path)!=2 || probe_fd(fd)!=2 || lseek(fd,0,SEEK_CUR)!=0)return 9;
  close(fd); unlink(path);
  puts("PASS: Saturn early-state, disc-probe and fd isolation contracts");
  dlclose(lib); return 0;
}
