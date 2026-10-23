// SPDX-License-Identifier: GPL-3.0-or-later
#include "OSD/Thread.h"
#include "OSD/Logger.h"
#include <cassert>
#include <memory>
void model3_log(int,const char*){}
bool model3_audio(unsigned,INT16*,INT16*,INT16*,INT16*,bool){return true;}
struct Context{CMutex* mutex;CCondVar* cv;CSemaphore* ready;bool done=false;int result=0;};
int run(void* p){auto c=static_cast<Context*>(p);c->mutex->Lock();c->ready->Post();while(!c->done)c->cv->Wait(c->mutex);c->result=42;c->mutex->Unlock();return 7;}
int main(){
  assert(ErrorLog("expected failure %d",1)==FAIL);
  std::unique_ptr<CMutex> mutex(CThread::CreateMutex());
  std::unique_ptr<CCondVar> cv(CThread::CreateCondVar());
  std::unique_ptr<CSemaphore> ready(CThread::CreateSemaphore(0));
  assert(mutex&&cv&&ready&&ready->GetValue()==0);
  Context context{mutex.get(),cv.get(),ready.get()};
  std::unique_ptr<CThread> thread(CThread::CreateThread("test",run,&context));
  assert(thread);ready->Wait();mutex->Lock();context.done=true;cv->SignalAll();mutex->Unlock();
  assert(thread->Wait()==7&&context.result==42);assert(thread->Wait()==0);
  const auto before=CThread::GetTicks();CThread::Sleep(2);assert(CThread::GetTicks()-before>=1);
}
