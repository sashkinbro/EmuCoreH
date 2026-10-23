// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
namespace Model3Clock {
// Local calendar seeded once at startup. Advance with the frontend's 60 Hz
// guest frames, so wall-clock pauses and state replay cannot change guest input.
struct Calendar {
  uint32_t year=1970,month=1,day=1,weekday=4,hour=0,minute=0,second=0,frame=0;
  static bool Leap(uint32_t y){return y%4==0&&(y%100!=0||y%400==0);}
  static uint32_t Days(uint32_t y,uint32_t m){
    static constexpr uint32_t days[]={31,28,31,30,31,30,31,31,30,31,30,31};
    return m==2?28+Leap(y):m>=1&&m<=12?days[m-1]:0;
  }
  bool Valid()const{return year>=1900&&year<=9999&&month>=1&&month<=12&&day>=1&&day<=Days(year,month)&&weekday<7&&hour<24&&minute<60&&second<60&&frame<60;}
  void AdvanceFrame(){
    if(++frame<60)return;
    frame=0;
    if(++second<60)return;
    second=0;
    if(++minute<60)return;
    minute=0;
    if(++hour<24)return;
    hour=0;weekday=(weekday+1)%7;
    if(++day<=Days(year,month))return;
    day=1;
    if(++month<=12)return;
    month=1;
    if(++year>9999)year=1900;
  }
  uint8_t Read(unsigned reg)const {
    switch(reg&15){
      case 0:return second%10;case 1:return second/10;
      case 2:return minute%10;case 3:return minute/10;
      case 4:return hour%10;case 5:return hour/10;
      case 6:return day%10;case 7:return day/10;
      case 8:return month%10;case 9:return month/10;
      case 10:return year%10;case 11:return year/10%10;
      case 12:return weekday;default:return 0;
    }
  }
};
static_assert(sizeof(Calendar)==32,"RTC state is eight little-endian uint32 fields");
}
