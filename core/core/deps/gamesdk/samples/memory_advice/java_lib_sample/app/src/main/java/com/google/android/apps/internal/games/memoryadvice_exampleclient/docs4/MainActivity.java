package com.google.android.apps.internal.games.memoryadvice_exampleclient.docs4;

import android.app.Activity;
import com.google.android.apps.internal.games.memoryadvice.MemoryAdvisor;
import com.google.android.apps.internal.games.memoryadvice.MemoryWatcher;
import java.util.Map;

class MyActivity extends Activity {
  private MemoryAdvisor memoryAdvisor;
  // ...
  void myMethod() {
    Map<String, Object> advice = memoryAdvisor.getAdvice();
    MemoryWatcher memoryWatcher =
        new MemoryWatcher(memoryAdvisor, 250, new MemoryWatcher.DefaultClient() {
          @Override
          public void newState(MemoryAdvisor.MemoryState memoryState) {
            switch (memoryState) {
              case OK:
                // The application can safely allocate significant memory.
                break;
              case APPROACHING_LIMIT:
                // The application should not allocate significant memory.
                break;
              case CRITICAL:
                // The application should free memory as soon as possible, until the memory state
                // changes.
                break;
              case BACKGROUNDED:
                // The application has been put into the background.
                break;
            }
          }
        });
  }
}