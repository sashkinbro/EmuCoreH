package com.memory_advice.hogger;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
  // Used to load the 'hogger' library on application startup.
  static {
    System.loadLibrary("hogger");
  }

  private final class CheckMemoryAdviceTask extends TimerTask {
    @Override
    public void run() {
      // Post a task to update the memory on the UI thread.
      getWindow().getDecorView().post(() -> {
        showMemoryAdvice(getMemoryAdvice(), getMemoryState());
        showMemoryAvailable(getPercentageMemoryAvailable());
      });
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    int init_retcode = initMemoryAdvice();
    if (init_retcode != 0) {
      showMemoryAdvice(String.format("Error initializing memory_advice: %d", init_retcode), 0);
      return;
    }

    showMemoryAdvice(getMemoryAdvice(), getMemoryState());
    showMemoryAvailable(getPercentageMemoryAvailable());

    Timer timer = new Timer();
    timer.scheduleAtFixedRate(new CheckMemoryAdviceTask(), 0, 1000);

    findViewById(R.id.allocate_button).setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        onAllocate();
      }
    });
    findViewById(R.id.deallocate_button).setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        onDeallocate();
      }
    });
  }

  static final long BYTES_PER_MEGABYTE = 1000000;
  static final long BYTES_TO_ADD = 100 * BYTES_PER_MEGABYTE;
  long memory_allocated;

  public void onAllocate() {
    new Thread(() -> {
      if (allocate(BYTES_TO_ADD)) {
        memory_allocated += BYTES_TO_ADD;
        updateMemoryAllocated();
      }
    }).start();
  }

  public void onDeallocate() {
    new Thread(() -> {
      if (deallocate()) {
        memory_allocated -= BYTES_TO_ADD;
        updateMemoryAllocated();
      }
    }).start();
  }

  private void updateMemoryAllocated() {
    getWindow().getDecorView().post(() -> { showMemoryAllocated(); });
  }

  private void showMemoryAdvice(String advice, int state) {
    // Example of a call to a native method
    TextView tv = findViewById(R.id.sample_text);
    tv.setText(advice);
    String stateString = "UNKNOWN";
    switch (state) {
      case 1:
        stateString = "OK";
        break;
      case 2:
        stateString = "APPROACHING_LIMIT";
        break;
      case 3:
        stateString = "CRITICAL";
        break;
    }
    TextView state_tv = findViewById(R.id.state_textview);
    state_tv.setText("Memory State: " + stateString);
  }

  private void showMemoryAllocated() {
    // Example of a call to a native method
    TextView tv = findViewById(R.id.allocated_textview);
    tv.setText(String.format("Memory Allocated: %d MB", memory_allocated / BYTES_PER_MEGABYTE));
  }

  private void showMemoryAvailable(float percentage) {
    TextView tv = findViewById(R.id.available_textview);
    tv.setText(String.format("Memory Available: %.2f%%", percentage));
  }

  public native int initMemoryAdvice();
  public native String getMemoryAdvice();
  public native int getMemoryState();
  public native float getPercentageMemoryAvailable();
  public native boolean allocate(long bytes);
  public native boolean deallocate();
}
