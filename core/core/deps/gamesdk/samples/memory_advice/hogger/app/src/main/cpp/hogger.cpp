#include <jni.h>
#include <memory_advice/memory_advice.h>
#include <memory_advice/memory_advice_debug.h>

#include <cassert>
#include <list>
#include <mutex>
#include <random>
#include <sstream>
#include <string>

#define LOG_TAG "Hogger"
#include "Log.h"

static int TEST_USER_DATA;
static int TEST_USER_DATA2;
constexpr int callback_waittime_ms = 2000;
constexpr int callback2_waittime_ms = 5000;

void callback(MemoryAdvice_MemoryState state, void* context) {
  assert(context == &TEST_USER_DATA);
  ALOGI("State is: %d", state);
  // Test unregistering and registering.
  MemoryAdvice_unregisterWatcher(callback);
  MemoryAdvice_registerWatcher(callback_waittime_ms, callback, &TEST_USER_DATA);
}

void callback2(MemoryAdvice_MemoryState state, void* context) {
  assert(context == &TEST_USER_DATA2);
  ALOGI("State (callback 2) is: %d", state);
  // Test unregistering and registering.
  MemoryAdvice_unregisterWatcher(callback2);
  MemoryAdvice_registerWatcher(callback2_waittime_ms, callback2,
                               &TEST_USER_DATA2);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_memory_1advice_hogger_MainActivity_initMemoryAdvice(JNIEnv* env,
                                                             jobject activity) {
  auto init_error_code = MemoryAdvice_init(env, activity);
  if (init_error_code == MEMORYADVICE_ERROR_OK) {
    MemoryAdvice_registerWatcher(callback_waittime_ms, callback,
                                 &TEST_USER_DATA);
    MemoryAdvice_registerWatcher(callback2_waittime_ms, callback2,
                                 &TEST_USER_DATA2);

    assert(MemoryAdvice_test() == 0);
  }
  return init_error_code;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_memory_1advice_hogger_MainActivity_getMemoryAdvice(JNIEnv* env,
                                                            jobject activity) {
  MemoryAdvice_JsonSerialization advice;
  MemoryAdvice_getAdvice(&advice);
  ALOGE("Advice is: %s", advice.json);
  jstring ret = env->NewStringUTF(advice.json);
  MemoryAdvice_JsonSerialization_free(&advice);
  return ret;
}
extern "C" JNIEXPORT jfloat JNICALL
Java_com_memory_1advice_hogger_MainActivity_getPercentageMemoryAvailable(
    JNIEnv* env, jobject activity) {
  return MemoryAdvice_getPercentageAvailableMemory();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_memory_1advice_hogger_MainActivity_getMemoryState(JNIEnv* env,
                                                           jobject activity) {
  return MemoryAdvice_getMemoryState();
}

static std::list<std::unique_ptr<char[]>> allocated_bytes_list;
static std::mutex allocated_mutex;

static void FillRandom(char* bytes, size_t size) {
  std::minstd_rand rng;
  int32_t* ptr = reinterpret_cast<int32_t*>(bytes);
  std::generate(ptr, ptr + size / 4, [&]() -> int32_t { return rng(); });
  // Don't worry about filling the last few bytes if size isn't a multiple of 4.
}

extern "C" JNIEXPORT bool JNICALL
Java_com_memory_1advice_hogger_MainActivity_allocate(JNIEnv* env,
                                                     jobject activity,
                                                     jlong nbytes) {
  std::lock_guard<std::mutex> guard(allocated_mutex);
  try {
    auto allocated_bytes = new char[nbytes];
    // Note that without setting the memory, the available memory figure doesn't
    // go down.
    FillRandom(allocated_bytes, nbytes);
    allocated_bytes_list.push_back(std::unique_ptr<char[]>{allocated_bytes});
    return true;
  } catch (std::bad_alloc& ex) {
    ALOGE("Can't allocate any more memory");
    return false;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_memory_1advice_hogger_MainActivity_deallocate(JNIEnv* env,
                                                       jobject activity) {
  std::lock_guard<std::mutex> guard(allocated_mutex);
  if (allocated_bytes_list.size() > 0) {
    allocated_bytes_list.pop_back();
    return true;
  } else {
    return false;
  }
}
