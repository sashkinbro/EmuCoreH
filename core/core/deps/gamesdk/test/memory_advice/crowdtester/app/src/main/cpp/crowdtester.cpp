#include <jni.h>
#include <memory_advice/memory_advice.h>
#include <memory_advice/memory_advice_debug.h>

#include <list>
#include <mutex>
#include <random>
#include <sstream>
#include <string>

extern "C" JNIEXPORT jint JNICALL
Java_com_memory_1advice_crowdtester_MainActivity_initMemoryAdvice(
    JNIEnv* env, jobject activity) {
    auto init_error_code = MemoryAdvice_init(env, activity);
    return init_error_code;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_memory_1advice_crowdtester_MainActivity_getMemoryAdvice(
    JNIEnv* env, jobject activity) {
    MemoryAdvice_JsonSerialization advice;
    MemoryAdvice_getAdvice(&advice);
    jstring ret = env->NewStringUTF(advice.json);
    MemoryAdvice_JsonSerialization_free(&advice);
    return ret;
}
static std::list<std::unique_ptr<char[]>> allocated_bytes_list;
static std::mutex allocated_mutex;
static void FillRandom(char* bytes, size_t size) {
    std::minstd_rand rng;
    int32_t* ptr = reinterpret_cast<int32_t*>(bytes);
    std::generate(ptr, ptr + size / 4, [&]() -> int32_t { return rng(); });
    // Don't worry about filling the last few bytes if size isn't a multiple
    // of 4.
}
extern "C" JNIEXPORT bool JNICALL
Java_com_memory_1advice_crowdtester_MainActivity_allocate(JNIEnv* env,
                                                          jobject activity,
                                                          jlong nbytes) {
    std::lock_guard<std::mutex> guard(allocated_mutex);
    try {
        auto allocated_bytes = new char[nbytes];
        // Note that without setting the memory, the available memory figure
        // doesn't go down.
        FillRandom(allocated_bytes, nbytes);
        allocated_bytes_list.push_back(
            std::unique_ptr<char[]>{allocated_bytes});
        return true;
    } catch (std::bad_alloc& ex) {
        return false;
    }
}