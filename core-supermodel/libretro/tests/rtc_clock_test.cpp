// SPDX-License-Identifier: GPL-3.0-or-later
#include "Model3/RTCClock.h"
#include <cassert>
int main(){
  using Model3Clock::Calendar;
  Calendar clock{2024,2,28,3,23,59,59,59};assert(clock.Valid());
  clock.AdvanceFrame();assert(clock.day==29&&clock.hour==0&&clock.second==0&&clock.frame==0&&clock.weekday==4);
  clock={2023,2,28,2,23,59,59,59};clock.AdvanceFrame();assert(clock.month==3&&clock.day==1);
  clock={2099,12,31,4,23,59,59,59};clock.AdvanceFrame();assert(clock.year==2100&&clock.month==1&&clock.day==1&&clock.weekday==5);
  assert(!Calendar::Leap(1900)&&Calendar::Leap(2000)&&!Calendar::Leap(2100));
  clock={2026,10,23,5,18,58,23,55};const Calendar saved=clock;
  for(int i=0;i<12;i++)clock.AdvanceFrame();
  const Calendar future=clock;
  clock=saved;for(int i=0;i<12;i++)clock.AdvanceFrame();
  assert(clock.second==24&&clock.frame==7&&clock.Read(0)==4&&clock.Read(1)==2);
  assert(clock.year==future.year&&clock.month==future.month&&clock.day==future.day&&clock.hour==future.hour&&clock.minute==future.minute&&clock.second==future.second&&clock.frame==future.frame);
  clock.day=32;assert(!clock.Valid());clock=saved;clock.frame=60;assert(!clock.Valid());
  clock=saved;clock.month=0;assert(!clock.Valid());
}
