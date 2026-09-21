/*
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

#include <android/native_window_jni.h>
#include <jni.h>
#include <unistd.h>

#include <condition_variable>
#include <sstream>

#include "lite/dev_tuningfork.pb.h"
#include "lite/tuningfork.pb.h"
#include "protobuf_util.h"
#include "swappy/swappyGL.h"
#include "swappy/swappyGL_extra.h"
#include "tuningfork/tuningfork.h"

#define LOG_TAG "experimentsdemo"
#include <random>

#include "Log.h"
#include "Renderer.h"

using ::com::google::tuningfork::Annotation;
using ::com::google::tuningfork::FidelityParams;
using ::com::google::tuningfork::Settings;

namespace proto_tf = com::google::tuningfork;
namespace tf = tuningfork;
using namespace samples;

bool swappy_enabled = false;

namespace {

constexpr TuningFork_InstrumentKey TFTICK_CHOREOGRAPHER =
    TFTICK_USERDEFINED_BASE;

std::string ReplaceReturns(const std::string &s) {
  std::string r = s;
  for (int i = 0; i < r.length(); ++i) {
    if (r[i] == '\n') r[i] = ',';
    if (r[i] == '\r') r[i] = ' ';
  }
  return r;
}

void SplitAndLog(const std::string &s) {
  std::stringstream to_log;
  const int max_line_len = 300;
  int nparts = s.length() / max_line_len + 1;
  for (int i = 0; i < nparts; ++i) {
    std::stringstream msg;
    msg << "(TGE" << (i + 1) << '/' << nparts << ')';
    msg << s.substr(i * max_line_len, max_line_len);
    ALOGI("%s", msg.str().c_str());
  }
}

void UploadCallback(const char *tuningfork_log_event, size_t n) {
  if (tuningfork_log_event) {
    std::string evt(tuningfork_log_event, n);
    SplitAndLog(evt);
  }
}

static bool sLoading = false;
static int sLevel = proto_tf::LEVEL_1;
extern "C" void SetAnnotations() {
  if (proto_tf::Level_IsValid(sLevel)) {
    Annotation a;
    a.set_level((proto_tf::Level)sLevel);
    auto ser = tf::TuningFork_CProtobufSerialization_Alloc(a);
    if (TuningFork_setCurrentAnnotation(&ser) != TUNINGFORK_ERROR_OK) {
      ALOGW("Bad annotation");
    }
    TuningFork_CProtobufSerialization_free(&ser);
  }
}

std::mutex mutex;
std::condition_variable cv;
bool setFPs = false;
extern "C" void FidelityParamsCallback(
    const TuningFork_CProtobufSerialization *params) {
  FidelityParams p;
  // Set default values
  p.set_num_spheres(20);
  p.set_tesselation_percent(50);
  std::vector<uint8_t> params_ser(params->bytes, params->bytes + params->size);
  tf::Deserialize(params_ser, p);
  int nSpheres = p.num_spheres();
  int tesselation = p.tesselation_percent();
  Renderer::getInstance()->setQuality(nSpheres, tesselation);
  setFPs = true;
  cv.notify_one();
}

void WaitForFidelityParams() {
  std::unique_lock<std::mutex> lock(mutex);
  cv.wait(lock, [] { return setFPs; });
}

std::thread tf_thread;
jobject tf_activity;

void InitTf(JNIEnv *env, jobject activity) {
  SwappyGL_init(env, activity);
  swappy_enabled = SwappyGL_isEnabled();
  TuningFork_Settings settings{};
  if (swappy_enabled) {
    settings.swappy_tracer_fn = &SwappyGL_injectTracer;
    settings.swappy_version = Swappy_version();
  }
  settings.fidelity_params_callback = FidelityParamsCallback;
#ifndef NDEBUG
  settings.endpoint_uri_override = "http://localhost:9000";
#endif
  TuningFork_ErrorCode err = TuningFork_init(&settings, env, activity);
  if (err == TUNINGFORK_ERROR_OK) {
    TuningFork_reportLifecycleEvent(TUNINGFORK_STATE_ONCREATE);
    TuningFork_setUploadCallback(UploadCallback);
    SetAnnotations();
    // Test disabling and enabling memory recording
    TuningFork_enableMemoryRecording(false);
    TuningFork_enableMemoryRecording(true);
  } else {
    ALOGW("Error initializing TuningFork: %d", err);
  }
  // If we don't wait for fidelity params here, the download thread will set
  // them after we
  //   have already started rendering with a different set of parameters.
  // In a real game, we'd initialize all the other assets before waiting.
  WaitForFidelityParams();
}

void InitTfFromNewThread(JavaVM *vm) {
  JNIEnv *env;
  int status = vm->AttachCurrentThread(&env, NULL);
  InitTf(env, tf_activity);
  vm->DetachCurrentThread();
}
}  // anonymous namespace

extern "C" {

// initFromNewThread parameter is for testing
JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_initTuningFork(
    JNIEnv *env, jobject activity, jboolean initFromNewThread) {
  if (initFromNewThread) {
    tf_activity = env->NewGlobalRef(activity);
    JavaVM *vm;
    env->GetJavaVM(&vm);
    tf_thread = std::thread(InitTfFromNewThread, vm);
  } else {
    InitTf(env, activity);
  }
}

static TuningFork_LoadingEventHandle inter_level_loading_handle;
static TuningFork_LoadingTimeMetadata inter_level_loading_metadata;
JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_onChoreographer(
    JNIEnv * /*env*/, jclass clz, jlong /*frameTimeNanos*/) {
  TuningFork_frameTick(TFTICK_CHOREOGRAPHER);
  // Switch levels and loading state according to the number of ticks we've had.
  constexpr int COUNT_NEXT_LEVEL_START_LOADING = 80;
  constexpr int COUNT_NEXT_LEVEL_STOP_LOADING = 90;
  static int tick_count = 0;
  ++tick_count;
  if (tick_count >= COUNT_NEXT_LEVEL_START_LOADING) {
    if (tick_count >= COUNT_NEXT_LEVEL_STOP_LOADING) {
      // Loading finished
      TuningFork_stopRecordingLoadingTime(inter_level_loading_handle);
      sLoading = false;
      tick_count = 0;
    } else {
      if (!sLoading) {
        // Loading next level
        sLoading = true;
        Annotation a;
        ++sLevel;
        if (sLevel > proto_tf::Level_MAX) sLevel = proto_tf::LEVEL_1;
        a.set_level((proto_tf::Level)sLevel);
        auto ser = tf::TuningFork_CProtobufSerialization_Alloc(a);
        inter_level_loading_metadata.state =
            TuningFork_LoadingTimeMetadata::LoadingState::INTER_LEVEL;
        TuningFork_startRecordingLoadingTime(
            &inter_level_loading_metadata,
            sizeof(TuningFork_LoadingTimeMetadata), &ser,
            &inter_level_loading_handle);
        TuningFork_CProtobufSerialization_free(&ser);
      }
    }
    SetAnnotations();
  }
}
JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_resize(
    JNIEnv *env, jclass /*clz*/, jobject surface, jint width, jint height) {
  ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
  Renderer::getInstance()->setWindow(window, static_cast<int32_t>(width),
                                     static_cast<int32_t>(height));
}
JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_clearSurface(
    JNIEnv * /*env*/, jclass /*clz*/) {
  Renderer::getInstance()->setWindow(nullptr, 0, 0);
}
JNIEXPORT void JNICALL Java_com_tuningfork_experimentsdemo_TFTestActivity_start(
    JNIEnv * /*env*/, jclass /*clz*/) {
  TuningFork_reportLifecycleEvent(TUNINGFORK_STATE_ONSTART);
  Renderer::getInstance()->start();
}
JNIEXPORT void JNICALL Java_com_tuningfork_experimentsdemo_TFTestActivity_stop(
    JNIEnv * /*env*/, jclass /*clz*/) {
  TuningFork_reportLifecycleEvent(TUNINGFORK_STATE_ONSTOP);
  Renderer::getInstance()->stop();
  // Call flush here to upload any histograms when the app goes to the
  // background.
  auto ret = TuningFork_flush();
  ALOGI("TuningFork_flush returned %d", ret);
}

JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_destroy(JNIEnv * /*env*/,
                                                           jclass /*clz*/) {
  TuningFork_reportLifecycleEvent(TUNINGFORK_STATE_ONDESTROY);
  TuningFork_destroy();
}

JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_raiseSignal(JNIEnv *env,
                                                               jclass clz,
                                                               jint signal) {
  std::stringstream ss;
  ss << std::this_thread::get_id();
  ALOGI("raiseSignal %d: [pid: %d], [tid: %d], [thread_id: %s])", signal,
        getpid(), gettid(), ss.str().c_str());
  raise(signal);
}

JNIEXPORT void JNICALL
Java_com_tuningfork_experimentsdemo_TFTestActivity_setFidelityParameters(
    JNIEnv *env, jclass clz) {
  // Simulate the user changing quality settings in the game
  static std::mt19937 gen;
  static std::uniform_int_distribution<> dis(1, 100);
  FidelityParams p;
  p.set_num_spheres(dis(gen));
  p.set_tesselation_percent(dis(gen));
  auto params = tf::TuningFork_CProtobufSerialization_Alloc(p);
  TuningFork_setFidelityParameters(&params);
  FidelityParamsCallback(&params);
  TuningFork_CProtobufSerialization_free(&params);
}
}
