/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <dlfcn.h>
#include <jni.h>

#include <cstdlib>

#include "Log.h"
#include "core/tuningfork_utils.h"
#include "jni/jni_helper.h"
#include "tuningfork/tuningfork.h"
#define LOG_TAG "UnityTuningfork"

using namespace tuningfork;

namespace {
enum SwappyBackend { Swappy_None = 0, Swappy_OpenGL = 1, Swappy_Vulkan = 2 };

typedef SwappyBackend (*UnitySwappyTracerFn)(const SwappyTracer*);
typedef uint32_t (*UnitySwappyVersion)();
typedef bool (*SwappyIsEnabled)();

template <class T>
T findFunction(const char* lib_name, const char* function_name) {
    void* lib = dlopen(lib_name, RTLD_NOW);
    if (lib == nullptr) return nullptr;

    ALOGI("%s is found", lib_name);
    T fn = reinterpret_cast<T>(dlsym(lib, function_name));
    if (fn != nullptr) {
        ALOGI("%s is found", function_name);
    } else {
        ALOGW("%s is not found", function_name);
    }
    return fn;
}

static SwappyTracerFn s_swappy_tracer_fn = nullptr;
static UnitySwappyTracerFn s_unity_swappy_tracer_fn = nullptr;
static bool s_swappy_enabled = false;
static uint32_t s_swappy_version = 0;

void UnityTracer(const SwappyTracer* tracer) {
    SwappyBackend swappyBackend = s_unity_swappy_tracer_fn(tracer);
    ALOGI("Swappy backend: %d", swappyBackend);
    if (swappyBackend == Swappy_None) s_swappy_enabled = false;
}

// Return swappy tracer for opengl.
// There is no tracer for Vulkan in that version of swappy.
SwappyTracerFn findTracerSwappy() {
    auto tracer =
        findFunction<SwappyTracerFn>("libswappy.so", "Swappy_injectTracer");
    if (tracer == nullptr) return nullptr;

    auto swappyIsEnabledFunc =
        findFunction<SwappyIsEnabled>("libswappy.so", "Swappy_isEnabled");
    if (swappyIsEnabledFunc == nullptr) return nullptr;

    bool swappyIsEnabled = swappyIsEnabledFunc();
    ALOGI("Swappy version 0_1 isEnabled: [%d]", swappyIsEnabled);
    if (swappyIsEnabled) return tracer;
    return nullptr;
}

UnitySwappyTracerFn findUnityTracer() {
    auto tracer = findFunction<UnitySwappyTracerFn>("libunity.so",
                                                    "UnitySwappy_injectTracer");
    auto version_fn =
        findFunction<UnitySwappyVersion>("libunity.so", "UnitySwappy_version");
    if (version_fn != nullptr) {
        s_swappy_version = version_fn();
        ALOGI("Unity Swappy version: [%d]", s_swappy_version);
    }
    return tracer;
}

bool findSwappy() {
    s_unity_swappy_tracer_fn = findUnityTracer();

    if (s_unity_swappy_tracer_fn != nullptr) {
        s_swappy_tracer_fn = UnityTracer;
    }
    if (s_swappy_tracer_fn == nullptr) {
        s_swappy_tracer_fn = findTracerSwappy();
    }
    return s_swappy_tracer_fn != nullptr;
}

}  // anonymous namespace

namespace jni = gamesdk::jni;

extern "C" {

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    if (vm == nullptr) return -1;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass activityThread = env->FindClass("android/app/ActivityThread");
    jmethodID currentActivityThread =
        env->GetStaticMethodID(activityThread, "currentActivityThread",
                               "()Landroid/app/ActivityThread;");
    jmethodID getApplication = env->GetMethodID(
        activityThread, "getApplication", "()Landroid/app/Application;");

    jobject activityThreadObj =
        env->CallStaticObjectMethod(activityThread, currentActivityThread);
    jobject context = env->CallObjectMethod(activityThreadObj, getApplication);

    jni::Init(env, context);
    return JNI_VERSION_1_6;
}

TuningFork_ErrorCode Unity_TuningFork_init_with_settings(
    TuningFork_Settings* settings) {
    s_swappy_enabled = findSwappy();
    if (s_swappy_enabled) {
        settings->swappy_tracer_fn = s_swappy_tracer_fn;
    }
    settings->swappy_version = s_swappy_version;
    // In API <= 23, the memory portion of the api_key sent from C#
    // becomes corrupted for some reason and it results in failure
    // to connect to the endpoint.
    // Introducing this workaround to correctly read the api_key
    // set in tuningfork_settings.bin.
    settings->api_key = nullptr;
    return TuningFork_init(settings, jni::Env(), jni::AppContextGlobalRef());
}

TuningFork_ErrorCode Unity_TuningFork_init(
    TuningFork_FidelityParamsCallback fidelity_params_callback,
    const TuningFork_CProtobufSerialization* training_fidelity_params,
    const char* endpoint_uri_override) {
    s_swappy_enabled = findSwappy();
    TuningFork_Settings settings{};
    if (s_swappy_enabled) {
        settings.swappy_tracer_fn = s_swappy_tracer_fn;
    }
    settings.fidelity_params_callback = fidelity_params_callback;
    settings.training_fidelity_params = training_fidelity_params;
    settings.endpoint_uri_override = endpoint_uri_override;
    settings.swappy_version = s_swappy_version;
    return TuningFork_init(&settings, jni::Env(), jni::AppContextGlobalRef());
}

bool Unity_TuningFork_swappyIsEnabled() { return s_swappy_enabled; }

TuningFork_ErrorCode Unity_TuningFork_findFidelityParamsInApk(
    const char* filename, TuningFork_CProtobufSerialization* fp) {
    return TuningFork_findFidelityParamsInApk(
        jni::Env(), jni::AppContextGlobalRef(), filename, fp);
}

TuningFork_ErrorCode Unity_TuningFork_saveOrDeleteFidelityParamsFile(
    TuningFork_CProtobufSerialization* fps) {
    return TuningFork_saveOrDeleteFidelityParamsFile(
        jni::Env(), jni::AppContextGlobalRef(), fps);
}
}  // extern "C" {
