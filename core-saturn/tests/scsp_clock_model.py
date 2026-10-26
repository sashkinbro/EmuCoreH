"""Host model using the exact current clock and sample-step function bodies.

No game/BIOS or worker thread is used. This checks PAL/NTSC sample cadence and
fractional-state replay, not PAL game compatibility. Requires host GCC in PATH.
"""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]

def function(path, signature):
    source = path.read_text(encoding="utf-8")
    begin = source.index(signature)
    opening = source.index("{", begin)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[begin:end]

clock = function(root / "src/yabause.c", "u32 get_cycles_per_line_division(")
step = function(root / "src/scsp.c", "void new_scsp_exec(s32 cycles)")
body = r'''
#include <stdint.h>
#include <stdio.h>
typedef uint32_t u32; typedef uint64_t u64; typedef int32_t s32;
#define SCSP_FRACTIONAL_BITS 20
static int new_scsp_cycles;
static u64 samples;
static void new_scsp_run_sample(void) { ++samples; }
'''+clock+'\n'+step+r'''
static void frames(int fps,int lines,int count,u32 *fraction) {
  u32 inc=get_cycles_per_line_division(44100*512,fps,lines,10);
  for(int frame=0;frame<count;++frame)for(int dec=0;dec<lines*10;++dec) {
    *fraction+=inc;
    new_scsp_exec(*fraction>>SCSP_FRACTIONAL_BITS);
    *fraction&=(1u<<SCSP_FRACTIONAL_BITS)-1;
  }
}
int main(void) {
  const int fps[]={50,60}, lines[]={313,263};
  for(int mode=0;mode<2;++mode) {
    u32 fraction=0; new_scsp_cycles=0; samples=0;
    frames(fps[mode],lines[mode],1200,&fraction);
    u64 expected=(u64)44100*1200/fps[mode];
    if(samples!=expected) return 1;
    u32 saved_fraction=fraction; int saved_cycles=new_scsp_cycles;
    u64 base=samples;
    frames(fps[mode],lines[mode],12,&fraction);
    u64 replay_samples=samples-base; u32 replay_fraction=fraction;
    int replay_cycles=new_scsp_cycles;
    fraction=saved_fraction; new_scsp_cycles=saved_cycles; samples=base;
    frames(fps[mode],lines[mode],12,&fraction);
    if(samples-base!=replay_samples || fraction!=replay_fraction ||
       new_scsp_cycles!=replay_cycles) return 2;
    printf("PASS: %dHz/%d lines: %llu samples over 1200 frames, exact 12-frame fractional replay\n",
      fps[mode],lines[mode],(unsigned long long)expected);
  }
  return 0;
}
'''
build = root / "build"
build.mkdir(exist_ok=True)
source = build / "scsp_clock_model.c"
binary = build / ("scsp_clock_model.exe" if os.name == "nt" else "scsp_clock_model")
source.write_text(body, encoding="utf-8")
subprocess.run(["gcc", "-std=c11", "-O2", str(source), "-o", str(binary)], check=True)
subprocess.run([str(binary)], check=True)
