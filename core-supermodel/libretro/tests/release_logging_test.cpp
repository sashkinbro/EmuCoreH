// SPDX-License-Identifier: GPL-3.0-or-later
#include "OSD/Logger.h"
#include "Util/ReleaseDiagnostics.h"
#include <cstdio>
#include <string>
static int emissions;
static std::string last;
void model3_log(int,const char* text){++emissions;last=text;}
bool model3_audio(unsigned,INT16*,INT16*,INT16*,INT16*,bool){return true;}
int main(){
  int evaluated=0;
  DebugLog("debug %d",++evaluated);
  InfoLog("info %d",++evaluated);
  MODEL3_DIAGNOSTIC_PRINTF("authored stdout sentinel %d",++evaluated);
  MODEL3_DIAGNOSTIC_FPRINTF(stderr,"authored stderr sentinel %d",++evaluated);
  MODEL3_DIAGNOSTIC_PUTS((++evaluated,"authored puts sentinel"));
  MODEL3_DIAGNOSTIC_PUTCHAR(++evaluated);
  const bool failed=ErrorLog("recoverable core error %d",++evaluated);
  if(evaluated!=0||emissions!=0||!failed){std::fprintf(stderr,"ordinary release logs evaluated=%d emitted=%d failed=%d\n",evaluated,emissions,failed);return 1;}
  FatalLog("authored terminal initialization failure");
  if(emissions!=1||last!="authored terminal initialization failure")return 2;
  FatalLog("authored repeated fatal");
  if(emissions!=1)return 3;
  ResetFatalLog();
  FatalLog("authored recovered-session fatal");
  if(emissions!=2)return 4;
  return 0;
}
