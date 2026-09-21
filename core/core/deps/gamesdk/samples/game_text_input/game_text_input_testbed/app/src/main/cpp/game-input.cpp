/*
 * Copyright (C) 2021 The Android Open Source Project
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
#include <android/log.h>
#include <jni.h>

#include <memory>

#define LOG_TAG "game-input"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__);

// Import the GameTextInput interface:
#include <game-text-input/gametextinput.h>

static GameTextInput *gameTextInput = nullptr;
static jobject activity = nullptr;
static jmethodID setDisplayedText = nullptr;

static void onEvent(void *context, const GameTextInputState *state) {
  ALOGD("OnEvent");
  ALOGD("String entered is: %s, len: %d", state->text_UTF8, state->text_length);
  JNIEnv *env = (JNIEnv *)context;

  jstring currentText = env->NewStringUTF(state->text_UTF8);
  env->CallVoidMethod(activity, setDisplayedText, currentText,
                      state->selection.start, state->selection.end);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_onCreated(JNIEnv *env,
                                                      jobject thiz) {
  ALOGD("Calling GameTextInput_init...");
  gameTextInput = GameTextInput_init(env, 0);
  activity = env->NewGlobalRef(thiz);
  ALOGD("activity is %p...", thiz);
  jclass activityClass =
      env->FindClass("com/gametextinput/testbed/MainActivity");

  setDisplayedText = env->GetMethodID(activityClass, "setDisplayedText",
                                      "(Ljava/lang/String;II)V");
  env->DeleteLocalRef(activityClass);
  ALOGD("setDisplayedText is %p...", setDisplayedText);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_InputEnabledTextView_onTextInputEventNative(
    JNIEnv *env, jobject thiz, jobject soft_keyboard_event) {
  ALOGD("Calling GameTextInput_processEvent...");
  ALOGD("activity is in this method %p.", thiz);
  GameTextInput_processEvent(gameTextInput, soft_keyboard_event);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_setInputConnectionNative(
    JNIEnv *env, jobject thiz, jobject inputConnection) {
  ALOGD("Calling GameTextInput_setInputConnection...");
  GameTextInput_setInputConnection(gameTextInput, inputConnection);
  GameTextInput_setEventCallback(gameTextInput, onEvent, env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_showIme(JNIEnv *env, jobject thiz) {
  GameTextInput_showIme(gameTextInput, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_hideIme(JNIEnv *env, jobject thiz) {
  GameTextInput_hideIme(gameTextInput, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_restartInput(JNIEnv *env,
                                                         jobject thiz) {
  GameTextInput_restartInput(gameTextInput);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_sendSelectionToStart(JNIEnv *env,
                                                                 jobject thiz) {
  GameTextInput_getState(
      gameTextInput,
      [](void *context, const GameTextInputState *state) {
        GameTextInputState state_copy = *state;
        state_copy.selection = {0, 0};
        GameTextInput_setState(static_cast<GameTextInput *>(context),
                               &state_copy);
      },
      (void *)gameTextInput);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gametextinput_testbed_MainActivity_sendSelectionToEnd(JNIEnv *env,
                                                               jobject thiz) {
  GameTextInput_getState(
      gameTextInput,
      [](void *context, const GameTextInputState *state) {
        GameTextInputState state_copy = *state;
        state_copy.selection = {(int)state->text_length,
                                (int)state->text_length};
        GameTextInput_setState(static_cast<GameTextInput *>(context),
                               &state_copy);
      },
      (void *)gameTextInput);
}
