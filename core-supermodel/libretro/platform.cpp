#define MODEL3_LOGGER_IMPLEMENTATION 1
// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-3.0-or-later
// Portable OSD implementation: no SDL Activity, video, audio or JNI ownership.
#include "OSD/Thread.h"
#include "OSD/Audio.h"
#include "OSD/Video.h"
#include "OSD/Outputs.h"
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <cstdarg>
#include <cstdio>
#include <memory>
#include <utility>
extern void model3_log(int level,const char* message);
extern bool model3_audio(unsigned,INT16*,INT16*,INT16*,INT16*,bool);
namespace {
#if !defined(MODEL3_RELEASE_SILENT_LOGS)
void log_message(int level,const char* fmt,va_list args){char buf[2048];vsnprintf(buf,sizeof(buf),fmt,args);model3_log(level,buf);}
#endif
std::atomic_flag fatal_logged = ATOMIC_FLAG_INIT;
struct Thread { std::thread worker; int result=0; };
struct Semaphore { std::mutex mutex;std::condition_variable cv;UINT32 value;explicit Semaphore(UINT32 n):value(n){} };
thread_local std::string thread_error;
}
// Core initializers return ErrorLog(...) directly: FAIL is true, success is zero.
void ResetFatalLog(){fatal_logged.clear();}
void FatalLog(const char* message){
#if defined(MODEL3_RELEASE_SILENT_LOGS)
  if(fatal_logged.test_and_set())return;
#endif
  model3_log(3,message);
}
#if defined(MODEL3_RELEASE_SILENT_LOGS)
bool ErrorLog(const char*,...){return FAIL;}
void InfoLog(const char*,...){}
void DebugLog(const char*,...){}
#else
bool ErrorLog(const char* fmt,...){va_list ap;va_start(ap,fmt);log_message(3,fmt,ap);va_end(ap);return FAIL;}
void InfoLog(const char* fmt,...){va_list ap;va_start(ap,fmt);log_message(1,fmt,ap);va_end(ap);}
void DebugLog(const char* fmt,...){va_list ap;va_start(ap,fmt);log_message(0,fmt,ap);va_end(ap);}
#endif
void CThread::Sleep(UINT32 n){std::this_thread::sleep_for(std::chrono::milliseconds(n));}
UINT32 CThread::GetTicks(){static const auto start=std::chrono::steady_clock::now();return static_cast<UINT32>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count());}
CThread* CThread::CreateThread(const std::string& name,ThreadStart fn,void* param){try{auto impl=std::make_unique<Thread>();auto p=impl.get();p->worker=std::thread([p,fn,param]{p->result=fn(param);});return new CThread(name,impl.release());}catch(const std::exception& e){thread_error=e.what();return nullptr;}}
CThread::CThread(const std::string& name,void* impl):m_name(name),m_impl(impl){}
CThread::~CThread(){Wait();}
const std::string& CThread::GetName()const{return m_name;}
UINT32 CThread::GetId(){auto p=static_cast<Thread*>(m_impl);return p?static_cast<UINT32>(std::hash<std::thread::id>{}(p->worker.get_id())):0;}
int CThread::Wait(){auto p=static_cast<Thread*>(m_impl);if(!p)return 0;if(p->worker.joinable())p->worker.join();int n=p->result;delete p;m_impl=nullptr;return n;}
const char* CThread::GetLastError(){return thread_error.c_str();}
CSemaphore* CThread::CreateSemaphore(UINT32 n){try{return new CSemaphore(new Semaphore(n));}catch(...){return nullptr;}}
CMutex* CThread::CreateMutex(){try{return new CMutex(new std::mutex);}catch(...){return nullptr;}}
CCondVar* CThread::CreateCondVar(){try{return new CCondVar(new std::condition_variable);}catch(...){return nullptr;}}
CSemaphore::CSemaphore(void* p):m_impl(p){}
CSemaphore::~CSemaphore(){delete static_cast<Semaphore*>(m_impl);}
UINT32 CSemaphore::GetValue(){auto p=static_cast<Semaphore*>(m_impl);std::lock_guard<std::mutex> lock(p->mutex);return p->value;}
bool CSemaphore::Wait(){auto p=static_cast<Semaphore*>(m_impl);std::unique_lock<std::mutex> lock(p->mutex);p->cv.wait(lock,[p]{return p->value>0;});--p->value;return true;}
bool CSemaphore::Post(){auto p=static_cast<Semaphore*>(m_impl);{std::lock_guard<std::mutex> lock(p->mutex);++p->value;}p->cv.notify_one();return true;}
CMutex::CMutex(void* p):m_impl(p){}
CMutex::~CMutex(){delete static_cast<std::mutex*>(m_impl);}
bool CMutex::Lock(){static_cast<std::mutex*>(m_impl)->lock();return true;}
bool CMutex::Unlock(){static_cast<std::mutex*>(m_impl)->unlock();return true;}
CCondVar::CCondVar(void* p):m_impl(p){}
CCondVar::~CCondVar(){delete static_cast<std::condition_variable*>(m_impl);}
bool CCondVar::Wait(CMutex* mutex){std::unique_lock<std::mutex> lock(*static_cast<std::mutex*>(mutex->m_impl),std::adopt_lock);static_cast<std::condition_variable*>(m_impl)->wait(lock);lock.release();return true;}
bool CCondVar::Signal(){static_cast<std::condition_variable*>(m_impl)->notify_one();return true;}
bool CCondVar::SignalAll(){static_cast<std::condition_variable*>(m_impl)->notify_all();return true;}
const char* COutputs::s_outputNames[]={"pause","LampStart","LampView1","LampView2","LampView3","LampView4","LampLeader","RawDrive","RawLamps","BillDigit1","BillDigit2","BillDigit3","BillDigit4","BillDigit5"};
COutputs::COutputs(){for(int i=0;i<NUM_OUTPUTS;i++){m_first[i]=false;m_values[i]=0;}}
COutputs::~COutputs()=default;
const Game& COutputs::GetGame()const{return m_game;}
const char* COutputs::GetOutputName(EOutputs out){return out>=0&&out<NUM_OUTPUTS?s_outputNames[out]:"unknown";}
EOutputs COutputs::GetOutputByName(const char*){return OutputUnknown;}
void COutputs::SetGame(const Game& game){m_game=game;}
UINT8 COutputs::GetValue(EOutputs out)const{return out>=0&&out<NUM_OUTPUTS?m_values[out]:0;}
void COutputs::SetValue(EOutputs out,UINT8 value){if(out<0||out>=NUM_OUTPUTS)return;auto old=m_values[out];m_values[out]=value;m_first[out]=true;SendOutput(out,old,value);}
bool COutputs::HasValue(EOutputs out){return out>=0&&out<NUM_OUTPUTS&&m_first[out];}
void SetAudioCallback(AudioCallbackFPtr,void*){}
void SetAudioEnabled(bool){}
void SetAudioType(Game::AudioTypes){}
bool OpenAudio(const Util::Config::Node&){return true;}
void CloseAudio(){}
bool OutputAudio(unsigned n,INT16* lf,INT16* rf,INT16* lr,INT16* rr,bool flip){return model3_audio(n,lf,rf,lr,rr,flip);}
bool BeginFrameVideo(){return true;}
void EndFrameVideo(){}
