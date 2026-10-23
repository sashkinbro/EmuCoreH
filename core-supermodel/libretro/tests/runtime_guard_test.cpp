// SPDX-License-Identifier: GPL-3.0-or-later
#include "runtime_guard.h"
#include <cstdlib>
#include <cstdio>
#include <stdexcept>
#include <string>
static void check(bool value, const char* detail) { if(!value){std::fprintf(stderr,"FAIL: %s\n",detail);std::exit(1);} }
int main() {
  model3retro::RuntimeGuard guard;
  int initializes=0,frames=0,reports=0;
  std::string diagnostic;
  auto good=[&]()->const char*{++initializes;return nullptr;};
  auto frame=[&]{++frames;};
  auto report=[&](const char* reason){++reports;diagnostic=reason;};
  guard.Run(false,good,frame,report);
  check(initializes==0&&frames==0&&reports==0&&!guard.Failed(),"absent context is a normal wait");
  guard.Run(true,good,frame,report);
  check(initializes==1&&frames==1&&reports==0,"successful frame stays live");
  guard.Run(true,[]()->const char*{return "presenter initialization failed";},frame,report);
  check(guard.Failed()&&reports==1&&frames==1&&diagnostic=="presenter initialization failed","failed graphics reports fatal and skips guest frame");
  guard.Run(true,good,frame,report);
  check(initializes==1&&frames==1&&reports==1,"fatal latch suppresses retries and duplicate shutdown");
  guard.Reset();
  guard.Run(true,[]()->const char*{throw std::runtime_error("renderer setup exception");},frame,report);
  check(guard.Failed()&&reports==2&&diagnostic=="renderer setup exception","context reset exception reports fatal");
  guard.Reset();
  guard.Run(true,good,[]{throw std::runtime_error("guest callback exception");},report);
  check(guard.Failed()&&reports==3&&diagnostic=="guest callback exception","frame exception reports fatal");
  guard.Reset();guard.Run(true,good,frame,report);
  check(!guard.Failed()&&frames==2&&reports==3,"new load clears latch");
  std::puts("PASS: production Model3 runtime guard");
}
