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
package com.google.androidgamesdk;

import static android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
import static android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.androidgamesdk.gametextinput.GameTextInput;
import com.google.androidgamesdk.gametextinput.InputConnection;
import com.google.androidgamesdk.gametextinput.Listener;
import com.google.androidgamesdk.gametextinput.Settings;
import com.google.androidgamesdk.gametextinput.State;
import dalvik.system.BaseDexClassLoader;
import java.io.File;

public class GameActivity extends AppCompatActivity implements SurfaceHolder.Callback2, Listener,
                                                               OnApplyWindowInsetsListener,
                                                               OnGlobalLayoutListener {
  private static final String LOG_TAG = "GameActivity";

  private static final String DEFAULT_NATIVE_LIB_NAME = "main";

  /**
   * Optional meta-that can be in the manifest for this component, specifying
   * the name of the native shared library to load.  If not specified,
   * {@link #DEFAULT_NATIVE_LIB_NAME} is used.
   */
  public static final String META_DATA_LIB_NAME = "android.app.lib_name";

  private static final String KEY_NATIVE_SAVED_STATE = "android:native_state";

  protected int contentViewId;

  private EditorInfo imeEditorInfo;

  private boolean softwareKeyboardVisible = false;

  /**
   * The SurfaceView used by default for displaying the game and getting a text input.
   * You can override its creation in `onCreateSurfaceView`.
   * This can be null, usually if you override `onCreateSurfaceView` to render on the whole activity
   * window.
   */
  protected InputEnabledSurfaceView mSurfaceView;

  protected boolean processMotionEvent(MotionEvent event) {
    int action = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? event.getActionButton() : 0;
    int cls = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ? event.getClassification() : 0;

    return onTouchEventNative(mNativeHandle, event, event.getPointerCount(), event.getHistorySize(),
        event.getDeviceId(), event.getSource(), event.getAction(), event.getEventTime(),
        event.getDownTime(), event.getFlags(), event.getMetaState(), action, event.getButtonState(),
        cls, event.getEdgeFlags(), event.getXPrecision(), event.getYPrecision());
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (processMotionEvent(event)) {
      return true;
    } else {
      return super.onTouchEvent(event);
    }
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    if (processMotionEvent(event)) {
      return true;
    } else {
      return super.onGenericMotionEvent(event);
    }
  }

  @Override
  public boolean onKeyUp(final int keyCode, KeyEvent event) {
    if (onKeyUpNative(mNativeHandle, event)) {
      return true;
    } else {
      return super.onKeyUp(keyCode, event);
    }
  }

  @Override
  public boolean onKeyDown(final int keyCode, KeyEvent event) {
    if (onKeyDownNative(mNativeHandle, event)) {
      return true;
    } else {
      return super.onKeyDown(keyCode, event);
    }
  }

  // Called when the IME has changed the input
  @Override
  public void stateChanged(State newState, boolean dismissed) {
    onTextInputEventNative(mNativeHandle, newState);
  }

  @Override
  public void onGlobalLayout() {
    mSurfaceView.getLocationInWindow(mLocation);
    int w = mSurfaceView.getWidth();
    int h = mSurfaceView.getHeight();

    if (mLocation[0] != mLastContentX || mLocation[1] != mLastContentY || w != mLastContentWidth
        || h != mLastContentHeight) {
      mLastContentX = mLocation[0];
      mLastContentY = mLocation[1];
      mLastContentWidth = w;
      mLastContentHeight = h;

      if (!mDestroyed) {
        onContentRectChangedNative(
            mNativeHandle, mLastContentX, mLastContentY, mLastContentWidth, mLastContentHeight);
      }
    }
  }

  // Called when we want to set the input state, e.g. before first showing the IME
  public void setTextInputState(State s) {
    if (mSurfaceView == null)
      return;

    if (mSurfaceView.mInputConnection == null)
      Log.w(LOG_TAG, "No input connection has been set yet");
    else
      mSurfaceView.mInputConnection.setState(s);
  }

  private long mNativeHandle;

  private SurfaceHolder mCurSurfaceHolder;

  protected final int[] mLocation = new int[2];
  protected int mLastContentX;
  protected int mLastContentY;
  protected int mLastContentWidth;
  protected int mLastContentHeight;

  protected boolean mDestroyed;

  protected native long initializeNativeCode(String internalDataPath, String obbPath,
      String externalDataPath, AssetManager assetMgr, byte[] savedState, Configuration config);

  protected native String getDlError();

  protected native void terminateNativeCode(long handle);

  protected native void onStartNative(long handle);

  protected native void onResumeNative(long handle);

  protected native byte[] onSaveInstanceStateNative(long handle);

  protected native void onPauseNative(long handle);

  protected native void onStopNative(long handle);

  protected native void onConfigurationChangedNative(long handle, Configuration newConfig);

  protected native void onTrimMemoryNative(long handle, int level);

  protected native void onWindowFocusChangedNative(long handle, boolean focused);

  protected native void onSurfaceCreatedNative(long handle, Surface surface);

  protected native void onSurfaceChangedNative(
      long handle, Surface surface, int format, int width, int height);

  protected native void onSurfaceRedrawNeededNative(long handle, Surface surface);

  protected native void onSurfaceDestroyedNative(long handle);

  protected native boolean onTouchEventNative(long handle, MotionEvent motionEvent,
      int pointerCount, int historySize, int deviceId, int source, int action, long eventTime,
      long downTime, int flags, int metaState, int actionButton, int buttonState,
      int classification, int edgeFlags, float precisionX, float precisionY);

  protected native boolean onKeyDownNative(long handle, KeyEvent keyEvent);

  protected native boolean onKeyUpNative(long handle, KeyEvent keyEvent);

  protected native void onTextInputEventNative(long handle, State softKeyboardEvent);

  protected native void setInputConnectionNative(long handle, InputConnection c);

  protected native void onWindowInsetsChangedNative(long handle);

  protected native void onContentRectChangedNative(long handle, int x, int y, int w, int h);

  protected native void onSoftwareKeyboardVisibilityChangedNative(long handle, boolean visible);

  protected native void onEditorActionNative(long handle, int action);

  /**
   * Get the pointer to the C `GameActivity` struct associated to this activity.
   * @return the pointer to the C `GameActivity` struct associated to this activity.
   */
  public long getGameActivityNativeHandle() {
    return this.mNativeHandle;
  }

  /**
   * Called to create the SurfaceView when the game will be rendered. It should be stored in
   * the mSurfaceView field, and its ID in contentViewId (if applicable).
   *
   * You can also redefine this to not create a SurfaceView at all,
   * and call `getWindow().takeSurface(this);` instead if you want to render on the whole activity
   * window.
   */
  protected InputEnabledSurfaceView createSurfaceView() {
    return new InputEnabledSurfaceView(this);
  }

  /**
   * You can override this function if you want to customize its behaviour,
   * but if you only want to substitute the default surface view with your derived class,
   * override createSurfaceView() instead.
   */
  protected void onCreateSurfaceView() {
    mSurfaceView = createSurfaceView();

    if (mSurfaceView == null) {
      return;
    }

    FrameLayout frameLayout = new FrameLayout(this);
    contentViewId = ViewCompat.generateViewId();
    frameLayout.setId(contentViewId);
    frameLayout.addView(mSurfaceView);

    setContentView(frameLayout);
    frameLayout.requestFocus();

    mSurfaceView.getHolder().addCallback(
        this); // Register as a callback for the rendering of the surface, so that we can pass this
               // surface to the native code

    // Note that in order for system window inset changes to be useful, the activity must call
    // WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    // Otherwise, the view will always be inside any system windows.

    // Listen for insets changes
    ViewCompat.setOnApplyWindowInsetsListener(mSurfaceView, this);
  }

  /**
   * Called to set up the window after the SurfaceView is created. Override this if you want to
   * change the Format (default is `PixelFormat.RGB_565`) or the Soft Input Mode (default is
   * `WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED |
   * WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE`).
   */
  protected void onSetUpWindow() {
    getWindow().setFormat(PixelFormat.RGB_565);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    onCreateSurfaceView();

    if (mSurfaceView != null) {
      mSurfaceView.getViewTreeObserver().addOnGlobalLayoutListener(this);
    }

    onSetUpWindow();

    String libname = new String(DEFAULT_NATIVE_LIB_NAME);
    ActivityInfo ai;
    try {
      ai = getPackageManager().getActivityInfo(
          getIntent().getComponent(), PackageManager.GET_META_DATA);
      if (ai.metaData != null) {
        String ln = ai.metaData.getString(META_DATA_LIB_NAME);
        if (ln != null)
          libname = ln;
      }
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException("Error getting activity info", e);
    }

    String fullLibname = "lib" + libname + ".so";
    Log.i(LOG_TAG, "Looking for library " + fullLibname);

    BaseDexClassLoader classLoader = (BaseDexClassLoader) getClassLoader();
    String path = classLoader.findLibrary(libname);

    if (path != null) {
      Log.i(LOG_TAG, "Found library " + fullLibname + ". Loading...");

      // Load the native library so that native functions are registered, even if GameActivity
      // is not sub-classing a Java activity that uses System.loadLibrary(<libname>).
      System.loadLibrary(libname);
    } else if (!libname.equals(DEFAULT_NATIVE_LIB_NAME)) {
      throw new IllegalArgumentException("unable to find native library " + fullLibname
          + " using classloader: " + classLoader.toString());
    } else {
      // Assume the application already loads the library explicitly.
      Log.i(LOG_TAG,
          "Application should have loaded the native library " + fullLibname
              + " explicitly by now. ");
    }

    byte[] nativeSavedState =
        savedInstanceState != null ? savedInstanceState.getByteArray(KEY_NATIVE_SAVED_STATE) : null;
    File extDir = null;
    File[] extPaths = getExternalFilesDirs(null);
    if (extPaths != null && extPaths.length > 0) {
      extDir = extPaths[0];
    }
    mNativeHandle = initializeNativeCode(getAbsolutePath(getFilesDir()),
        getAbsolutePath(getObbDir()), getAbsolutePath(extDir), getAssets(), nativeSavedState,
        getResources().getConfiguration());

    if (mNativeHandle == 0) {
      throw new UnsatisfiedLinkError(
          "Unable to initialize native code \"" + path + "\": " + getDlError());
    }

    // Set up the input connection
    if (mSurfaceView != null) {
      setInputConnectionNative(mNativeHandle, mSurfaceView.mInputConnection);
    }

    super.onCreate(savedInstanceState);
  }

  private static String getAbsolutePath(File file) {
    return (file != null) ? file.getAbsolutePath() : null;
  }

  @Override
  protected void onDestroy() {
    mDestroyed = true;
    if (mCurSurfaceHolder != null) {
      onSurfaceDestroyedNative(mNativeHandle);
      mCurSurfaceHolder = null;
    }

    terminateNativeCode(mNativeHandle);
    super.onDestroy();
  }

  @Override
  protected void onPause() {
    super.onPause();
    onPauseNative(mNativeHandle);
  }

  @Override
  protected void onResume() {
    super.onResume();
    onResumeNative(mNativeHandle);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    byte[] state = onSaveInstanceStateNative(mNativeHandle);
    if (state != null) {
      outState.putByteArray(KEY_NATIVE_SAVED_STATE, state);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    onStartNative(mNativeHandle);
  }

  @Override
  protected void onStop() {
    super.onStop();
    onStopNative(mNativeHandle);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (!mDestroyed) {
      onConfigurationChangedNative(mNativeHandle, newConfig);
    }
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (!mDestroyed) {
      onTrimMemoryNative(mNativeHandle, level);
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (!mDestroyed) {
      onWindowFocusChangedNative(mNativeHandle, hasFocus);
    }
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    if (!mDestroyed) {
      mCurSurfaceHolder = holder;
      onSurfaceCreatedNative(mNativeHandle, holder.getSurface());
    }
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (!mDestroyed) {
      mCurSurfaceHolder = holder;
      onSurfaceChangedNative(mNativeHandle, holder.getSurface(), format, width, height);
    }
  }

  @Override
  public void surfaceRedrawNeeded(SurfaceHolder holder) {
    if (!mDestroyed) {
      mCurSurfaceHolder = holder;
      onSurfaceRedrawNeededNative(mNativeHandle, holder.getSurface());
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    mCurSurfaceHolder = null;
    if (!mDestroyed) {
      onSurfaceDestroyedNative(mNativeHandle);
    }
  }

  @Keep
  void setWindowFlags(int flags, int mask) {
    getWindow().setFlags(flags, mask);
  }

  void setWindowFormat(int format) {
    getWindow().setFormat(format);
  }

  @Override
  public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
    this.onImeInsetsChanged(insets.getInsets(WindowInsetsCompat.Type.ime()));
    boolean keyboardVisible = isSoftwareKeyboardVisible(insets);
    if (keyboardVisible != softwareKeyboardVisible) {
      softwareKeyboardVisible = keyboardVisible;
      onSoftwareKeyboardVisibilityChanged(keyboardVisible);
    }
    onWindowInsetsChangedNative(mNativeHandle);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
      // Pass through to the view - we don't want to handle the insets, just observe them.
      v.onApplyWindowInsets(insets.toWindowInsets());
    }
    return insets;
  }

  @Keep
  public Insets getWindowInsets(int type) {
    WindowInsetsCompat allInsets = ViewCompat.getRootWindowInsets(mSurfaceView);
    if (allInsets == null)
      return null;
    Insets insets = allInsets.getInsets(type);
    if (insets == Insets.NONE)
      return null;
    else
      return insets;
  }

  @Keep
  public Insets getWaterfallInsets() {
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mSurfaceView);
    if (insets == null)
      return null;
    DisplayCutoutCompat cutout = insets.getDisplayCutout();
    if (cutout != null)
      return cutout.getWaterfallInsets();
    else
      return null;
  }

  // From the text input Listener.
  // Do nothing as we already handle inset events above.
  @Override
  public void onImeInsetsChanged(Insets insets) {
    Log.v(LOG_TAG, "onImeInsetsChanged from Text Listener");
  }

  // From the text input Listener.
  @Override
  public void onSoftwareKeyboardVisibilityChanged(boolean visible) {
    onSoftwareKeyboardVisibilityChangedNative(mNativeHandle, visible);
  }

  // From the text input Listener.
  // Called when editor action is performed.
  @Override
  public void onEditorAction(int action) {
    onEditorActionNative(mNativeHandle, action);
  }

  /**
   * Get the EditorInfo structure used to initialize the IME when it is requested.
   * The default is to forward key requests to the app (InputType.TYPE_NULL) and to
   * have no action button (IME_ACTION_NONE).
   * See https://developer.android.com/reference/android/view/inputmethod/EditorInfo.
   */
  public EditorInfo getImeEditorInfo() {
    if (imeEditorInfo == null) {
      imeEditorInfo = new EditorInfo();
      // Provide safe defaults here.
      imeEditorInfo.inputType = InputType.TYPE_CLASS_TEXT;
      imeEditorInfo.actionId = IME_ACTION_DONE;
      imeEditorInfo.imeOptions = IME_ACTION_DONE | IME_FLAG_NO_FULLSCREEN;
    }
    return imeEditorInfo;
  }

  /**
   * Set the EditorInfo structure used to initialize the IME when it is requested.
   * Set the inputType and actionId in order to customize how the IME behaves.
   * See https://developer.android.com/reference/android/view/inputmethod/EditorInfo.
   */
  @Keep
  public void setImeEditorInfo(EditorInfo info) {
    imeEditorInfo = info;
    mSurfaceView.mInputConnection.setEditorInfo(info);
  }

  /**
   * Set the inpuType and actionId fields of the EditorInfo structure used to
   * initialize the IME when it is requested.
   * This is called from the native side by GameActivity_setImeEditorInfo.
   * See https://developer.android.com/reference/android/view/inputmethod/EditorInfo.
   */
  @Keep
  public void setImeEditorInfoFields(int inputType, int actionId, int imeOptions) {
    EditorInfo info = getImeEditorInfo();
    info.inputType = inputType;
    info.actionId = actionId;
    info.imeOptions = imeOptions;
    mSurfaceView.mInputConnection.setEditorInfo(info);
  }

  protected class InputEnabledSurfaceView extends SurfaceView {
    public InputEnabledSurfaceView(GameActivity context) {
      super(context);
      EditorInfo editorInfo = context.getImeEditorInfo();
      mInputConnection = new InputConnection(context, this,
          new Settings(editorInfo,
              // Handle key events for InputType.TYPE_NULL:
              /*forwardKeyEvents=*/editorInfo.inputType == InputType.TYPE_NULL))
                             .setListener(context);
    }

    InputConnection mInputConnection;

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
      if (!mInputConnection.getSoftKeyboardActive()) {
        return null;
      }
      if (outAttrs != null) {
        GameTextInput.copyEditorInfo(mInputConnection.getEditorInfo(), outAttrs);
      }
      return mInputConnection;
    }

    public EditorInfo getEditorInfo() {
      return mInputConnection.getEditorInfo();
    }

    public void setEditorInfo(EditorInfo e) {
      mInputConnection.setEditorInfo(e);
    }
  }

  private boolean isSoftwareKeyboardVisible(WindowInsetsCompat insets) {
    if (insets == null) {
      return false;
    }

    return insets.isVisible(WindowInsetsCompat.Type.ime());
  }
}
