/*
 * Copyright 2020 The Android Open Source Project
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

package com.swappy.testapp;

import android.text.method.ScrollingMovementMethod;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
  TextView mTextView;
  Thread mTestThread;
  Timer mTimer;
  Boolean mDone;
  String mResult;

  // Used to load the test app's native library on application startup.
  static {
    System.loadLibrary("swappy-test");
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    mTextView = findViewById(R.id.sample_text);
    mTextView.setMovementMethod(new ScrollingMovementMethod());
    mDone = false;
    startTestsOnSeparateThread();
    mTimer = new Timer();
    mTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        TimerMethod();
      }
    }, 0, 1000);
  }
  private void TimerMethod()
  {
    //This method is called directly by the timer
    //and runs in the same thread as the timer.

    //We call the method that will work with the UI
    //through the runOnUiThread method.
    this.runOnUiThread(()->{
      if (mDone) {
        mTextView.setText(mResult);
        mTimer.cancel();
      } else {
        mTextView.setText(testSummarySoFar());
      }
    });
  }

  @Override
  protected void onDestroy() {
    try {
      mTestThread.join();
    } catch (InterruptedException e) {
      //
    }
    super.onDestroy();
  }
  void startTestsOnSeparateThread() {
    mTestThread = new Thread(()->{
      mResult = runTests();
      mDone = true;
    });
    mTestThread.start();
  }


  /**
   * Native methods implemented in app's native library,
   * which is packaged with this application.
   */
  public native String runTests();
  public native String testSummarySoFar();
}
