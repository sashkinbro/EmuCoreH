package com.google.android.apps.internal.games.memoryadvice_exampleclient;

import android.app.Activity;
import android.os.Bundle;
import com.google.android.apps.internal.games.memoryadvice.MemoryAdvisor;
import com.google.android.apps.internal.games.memoryadvice.MemoryWatcher;
import java.util.Map;

public class MainActivity extends Activity {
  private MemoryAdvisor memoryAdvisor;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    memoryAdvisor = new MemoryAdvisor(this);
    // Delay between polls.
    int delay = 250;

    MemoryWatcher memoryWatcher =
        new MemoryWatcher(memoryAdvisor, delay, new MemoryWatcher.DefaultClient() {
          @Override
          public void newState(MemoryAdvisor.MemoryState state) {}
        });

    Map<String, Object> deviceInfo = memoryAdvisor.getDeviceInfo();

    System.out.println(deviceInfo);

    Map<String, Object> advice = memoryAdvisor.getAdvice();

    MemoryAdvisor.MemoryState memoryState = MemoryAdvisor.getMemoryState(advice);

    long availabilityEstimate = MemoryAdvisor.availabilityEstimate(advice);

    System.out.println(advice);
  }

  @Override
  public void onTrimMemory(int level) {
    memoryAdvisor.setOnTrim(level);
    super.onTrimMemory(level);
  }
}
