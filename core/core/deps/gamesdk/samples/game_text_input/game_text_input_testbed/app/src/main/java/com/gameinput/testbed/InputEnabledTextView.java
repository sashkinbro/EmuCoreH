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
package com.gametextinput.testbed;

import static android.view.inputmethod.EditorInfo.IME_ACTION_NONE;
import static android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import androidx.core.graphics.Insets;
import com.google.androidgamesdk.gametextinput.GameTextInput;
import com.google.androidgamesdk.gametextinput.InputConnection;
import com.google.androidgamesdk.gametextinput.Listener;
import com.google.androidgamesdk.gametextinput.Settings;
import com.google.androidgamesdk.gametextinput.State;

public class InputEnabledTextView extends View implements Listener {
  private static final String LOG_TAG = "InputEnabledTextView";

  public InputConnection mInputConnection;

  public InputEnabledTextView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public InputEnabledTextView(Context context) {
    super(context);
  }

  public void createInputConnection(int inputType) {
    EditorInfo editorInfo = new EditorInfo();
    // Note that if you use TYPE_CLASS_TEXT, the IME may fill the whole window because we
    // are in landscape and the events aren't reflected back to it, so you can't see what
    // you're typing. This needs fixing.
    editorInfo.inputType = inputType;
    editorInfo.actionId = IME_ACTION_NONE;
    // IME_FLAG_NO_FULLSCREEN is needed to avoid the IME UI covering the whole display
    // and presenting an 'Execute' button in landscape mode.
    editorInfo.imeOptions = IME_FLAG_NO_FULLSCREEN;
    mInputConnection = new InputConnection(this.getContext(), this, new Settings(editorInfo, true))
                           .setListener(this);
  }

  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    Log.d(LOG_TAG, "onCreateInputConnection. isActive: "
        + mInputConnection.getSoftKeyboardActive());
    if(!mInputConnection.getSoftKeyboardActive()) {
      return null;
    }
    if (outAttrs != null) {
      GameTextInput.copyEditorInfo(mInputConnection.getEditorInfo(), outAttrs);
    }
    return mInputConnection;
  }

  // Called when the IME has changed the input
  @Override
  public void stateChanged(State newState, boolean dismissed) {
    Log.d(LOG_TAG, "stateChanged: " + newState.text
        + ", s: " + newState.selectionStart + "-" + newState.selectionEnd
        + ", cr: " + newState.composingRegionStart + "-" + newState.composingRegionEnd
    );
    onTextInputEventNative(newState);
  }

  @Override
  public void onEditorAction(int action) {
    Log.d(LOG_TAG, "onEditorAction: " + action);
  }

  @Override
  public void onImeInsetsChanged(Insets insets) {
    Log.d(LOG_TAG, "insetsChanged: " + insets);
  }

  @Override
  public void onSoftwareKeyboardVisibilityChanged(boolean visible) {
    Log.d(LOG_TAG, "onSoftwareKeyboardVisibilityChanged: " + visible);
  }

  private native void onTextInputEventNative(State softKeyboardEvent);
}
