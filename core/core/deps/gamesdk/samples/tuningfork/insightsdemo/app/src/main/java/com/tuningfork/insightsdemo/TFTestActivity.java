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

package com.tuningfork.insightsdemo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.gms.common.GoogleApiAvailability;

public class TFTestActivity
    extends AppCompatActivity implements Choreographer.FrameCallback, SurfaceHolder.Callback {
  static {
    System.loadLibrary("insights-demo");
  }
  public native void initTuningFork(boolean initFromNewThread);
  public static native void resize(Surface surface, int width, int height);
  public static native void clearSurface();
  public static native void onChoreographer(long t);
  public static native void start();
  public static native void stop();
  public static native void destroy();
  public static native void raiseSignal(int signal);
  public static native void setFidelityParameters();
  public static native long nGetTuningforkVersion();

  private SurfaceView view;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // gLView = new TFTestGLSurfaceView(this);
    view = new SurfaceView(this);
    setContentView(view);
    getSupportActionBar().setTitle(R.string.main_heading);
    view.getHolder().addCallback(this);
    View buttons = getWindow().getLayoutInflater().inflate(R.layout.buttons, null);
    addContentView(buttons, new ViewGroup.LayoutParams(view.getLayoutParams()));

    CheckGMS();
    initTuningFork(false);

    try {
      getSupportActionBar().setTitle("Insights v"
          + getApplicationContext()
                .getPackageManager()
                .getPackageInfo(getPackageName(), 0)
                .versionName
          + " (" + String.valueOf(nGetTuningforkVersion()) + ")");
    } catch (Exception e) {
      // Ignore
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    start();
    Choreographer.getInstance().postFrameCallback(this);
  }

  @Override
  protected void onStop() {
    super.onStop();
    stop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    destroy();
  }

  @Override
  public void doFrame(long t) {
    onChoreographer(t);
    Choreographer.getInstance().postFrameCallback(this);
    android.content.res.AssetManager a = getAssets();
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    // Do nothing here, waiting for surfaceChanged instead
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    Surface surface = holder.getSurface();
    resize(surface, width, height);
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    clearSurface();
  }

  public void OnClick_Crash(View view) {
    /**
     * SIGILL 4
     * SIGTRAP 5
     * SIGABRT 6
     * SIGBUS 7
     * SIGFPE 8
     * SIGSEGV 11
     */

    raiseSignal(4);
  }

  public void OnClick_SetFidelityParameters(View view) {
    setFidelityParameters();
  }

  private void CheckGMS() {
    GoogleApiAvailability availability = GoogleApiAvailability.getInstance();
    int status = availability.isGooglePlayServicesAvailable(this.getApplicationContext());
    int version = GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE;

    Log.i("Tuningfork Clearcut", "CheckGMS status: " + status);
    Log.i("TUningfork Clearcut", "CheckGMS version: " + version);
  }
}
