#include <android/native_window_jni.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <swappy/swappyVk.h>

#include "cube.h"

static struct android_app_state state;
static pthread_t thread;

static void *startCubes(void *state_void_ptr) {
  struct android_app_state *state = (struct android_app_state *)state_void_ptr;
  state->running = true;
  main_loop(state);
  state->running = false;
  state->destroyRequested = false;
  return NULL;
}

JNIEXPORT void JNICALL Java_com_samples_cube_CubeActivity_nStartCube(
    JNIEnv *env, jobject clazz, jobject surface) {
  if (!surface || state.running) {
    return;
  }
  state.window = ANativeWindow_fromSurface(env, surface);
  state.clazz = (jobject)(*env)->NewGlobalRef(env, clazz);
  (*env)->GetJavaVM(env, &state.vm);

  pthread_create(&thread, NULL, startCubes, &state);
}

JNIEXPORT void JNICALL
Java_com_samples_cube_CubeActivity_nStopCube(JNIEnv *env, jobject clazz) {
  if (state.running) {
    state.destroyRequested = true;
    pthread_join(thread, NULL);
  }
}

JNIEXPORT void JNICALL Java_com_samples_cube_CubeActivity_nUpdateGpuWorkload(
    JNIEnv *env, jobject clazz, jint new_workload) {
  update_gpu_workload(new_workload);
}

JNIEXPORT void JNICALL Java_com_samples_cube_CubeActivity_nUpdateCpuWorkload(
    JNIEnv *env, jobject clazz, jint new_workload) {
  update_cpu_workload(new_workload);
}

JNIEXPORT int JNICALL Java_com_samples_cube_CubeActivity_nGetSwappyStats(
    JNIEnv *env, jobject clazz, jint stat, jint bin) {
  static bool enabled = false;
  VkSwapchainKHR swapchain = get_current_swapchain();
  if (!swapchain) {
    return -1;
  }
  if (!enabled) {
    SwappyVk_enableStats(swapchain, true);
    enabled = true;
  }

  // stats are read one by one, query once per stat
  static SwappyStats stats;
  static int stat_idx = -1;

  if (stat_idx != stat) {
    SwappyVk_getStats(swapchain, &stats);
    stat_idx = stat;
  }

  int value = 0;

  if (stats.totalFrames) {
    switch (stat) {
      case 0:
        value = stats.idleFrames[bin];
        break;
      case 1:
        value = stats.lateFrames[bin];
        break;
      case 2:
        value = stats.offsetFromPreviousFrame[bin];
        break;
      case 3:
        value = stats.latencyFrames[bin];
        break;
      default:
        return stats.totalFrames;
    }
    value = round(value * 100.0f / stats.totalFrames);
  }

  return value;
}
