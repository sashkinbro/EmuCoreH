/*
 * Copyright 2018 The Android Open Source Project
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

package com.prefabulated.bouncyball;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.preference.PreferenceManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class OrbitActivity
    extends AppCompatActivity implements Choreographer.FrameCallback, SurfaceHolder.Callback {
  // Used to load the 'bouncyball' library on application startup.
  static {
    System.loadLibrary("bouncyball");
  }

  private static final long ONE_MS_IN_NS = 1000000;
  private static final long ONE_S_IN_NS = 1000 * ONE_MS_IN_NS;

  private static final String LOG_TAG = "OrbitActivity.java";

  private void configureGridCell(AppCompatTextView cell) {
    // A bunch of optimizations to reduce the cost of setText
    cell.setEllipsize(null);
    cell.setMaxLines(1);
    cell.setSingleLine(true);
    if (android.os.Build.VERSION.SDK_INT >= 23) {
      cell.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
      cell.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }

    cell.setTextAppearance(getApplicationContext(), R.style.InfoTextSmall);
    cell.setText("0");
  }

  private static GridLayout.Spec apiAgnosticSpec(int start) {
    if (Build.VERSION.SDK_INT >= 21) {
      return GridLayout.spec(start, 1.0f);
    } else {
      return GridLayout.spec(start);
    }
  }

  private void buildChoreographerInfoGrid() {
    GridLayout infoGrid = findViewById(R.id.choreographer_info_grid);

    // Add the header row
    GridLayout.Spec headerRowSpec = GridLayout.spec(0);
    for (int column = 0; column < mChoreographerInfoGrid[0].length; ++column) {
      AppCompatTextView cell = new AppCompatTextView(getApplicationContext());
      GridLayout.Spec colSpec = apiAgnosticSpec(column);
      cell.setLayoutParams(new GridLayout.LayoutParams(headerRowSpec, colSpec));
      configureGridCell(cell);

      if (column == 0) {
        cell.setText("");
      } else {
        cell.setText(String.format(Locale.US, "%d", column - 1));
      }
      infoGrid.addView(cell);
      mChoreographerInfoGrid[0][column] = cell;
    }

    // Add the data rows
    for (int row = 1; row < mChoreographerInfoGrid.length; ++row) {
      GridLayout.Spec rowSpec = GridLayout.spec(row);

      for (int column = 0; column < mChoreographerInfoGrid[row].length; ++column) {
        AppCompatTextView cell = new AppCompatTextView(getApplicationContext());
        GridLayout.Spec colSpec = apiAgnosticSpec(column);
        cell.setLayoutParams(new GridLayout.LayoutParams(rowSpec, colSpec));
        cell.setTextAppearance(getApplicationContext(), R.style.InfoTextSmall);
        configureGridCell(cell);

        if (column == 0) {
          switch (row) {
            case 1:
              cell.setText(R.string.grid_1s_arr);
              break;
            case 2:
              cell.setText(R.string.grid_1s_ts);
              break;
            case 3:
              cell.setText(R.string.grid_1m_arr);
              break;
            case 4:
              cell.setText(R.string.grid_1m_ts);
              break;
          }
        }
        infoGrid.addView(cell);
        mChoreographerInfoGrid[row][column] = cell;
      }
    }

    for (TextView[] row : mChoreographerInfoGrid) {
      for (TextView column : row) {
        column.setWidth(infoGrid.getWidth() / infoGrid.getColumnCount());
      }
    }
  }

  private void buildSwappyStatsGrid() {
    GridLayout infoGrid = findViewById(R.id.swappy_stats_grid);

    // Add the header row
    GridLayout.Spec headerRowSpec = GridLayout.spec(0);
    for (int column = 0; column < mSwappyGrid[0].length; ++column) {
      AppCompatTextView cell = new AppCompatTextView(getApplicationContext());
      GridLayout.Spec colSpec = apiAgnosticSpec(column);
      cell.setLayoutParams(new GridLayout.LayoutParams(headerRowSpec, colSpec));
      configureGridCell(cell);

      if (column == 0) {
        cell.setText("");
      } else {
        cell.setText(String.format(Locale.US, "%d", column - 1));
      }
      infoGrid.addView(cell);
      mSwappyGrid[0][column] = cell;
    }

    // Add the data rows
    for (int row = 1; row < mSwappyGrid.length; ++row) {
      GridLayout.Spec rowSpec = GridLayout.spec(row);

      for (int column = 0; column < mSwappyGrid[row].length; ++column) {
        AppCompatTextView cell = new AppCompatTextView(getApplicationContext());
        GridLayout.Spec colSpec = apiAgnosticSpec(column);
        cell.setLayoutParams(new GridLayout.LayoutParams(rowSpec, colSpec));
        cell.setTextAppearance(getApplicationContext(), R.style.InfoTextSmall);
        configureGridCell(cell);

        if (column == 0) {
          switch (row) {
            case 1:
              cell.setText(R.string.idle_frames);
              break;
            case 2:
              cell.setText(R.string.late_frames);
              break;
            case 3:
              cell.setText(R.string.offset_frames);
              break;
            case 4:
              cell.setText(R.string.latency_frames);
              break;
          }
        } else {
          cell.setText("0%");
        }
        infoGrid.addView(cell);
        mSwappyGrid[row][column] = cell;
      }
    }

    for (TextView[] row : mSwappyGrid) {
      for (TextView column : row) {
        column.setWidth(infoGrid.getWidth() / infoGrid.getColumnCount());
      }
    }
  }

  class TestCase {
    boolean use_affinity;
    boolean hot_pocket;
    boolean use_auto_swap_interval;
    int workload;
    int duration;
    Timer timer;
  }
  private List<TestCase> testCases;

  private boolean loadScript(String s) {
    try {
      testCases = new ArrayList<TestCase>();
      JSONArray ja = new JSONArray(s);
      for (int i = 0; i < ja.length(); ++i) {
        JSONObject o = ja.getJSONObject(i);
        TestCase t = new TestCase();
        if (o.has("use_affinity"))
          t.use_affinity = o.getBoolean("use_affinity");
        if (o.has("hot_pocket"))
          t.hot_pocket = o.getBoolean("hot_pocket");
        if (o.has("use_auto_swap_interval"))
          t.use_auto_swap_interval = o.getBoolean("use_auto_swap_interval");
        if (o.has("workload"))
          t.workload = o.getInt("workload");
        if (o.has("duration"))
          t.duration = o.getInt("duration");
        testCases.add(t);
      }
      return true;
    } catch (Exception e) {
      Log.e(LOG_TAG, "Couldn't parse test script");
      return false;
    }
  }

  private void runScript() {
    if (testCases != null) {
      int ms = 1000;
      for (TestCase t : testCases) {
        t.timer = new Timer();
        t.timer.schedule(new TimerTask() {
          public void run() {
            nSetPreference("use_affinity", t.use_affinity ? "true" : "false");
            nSetPreference("hot_pocket", t.hot_pocket ? "true" : "false");
            nSetAutoSwapInterval(t.use_auto_swap_interval);
            nSetWorkload(t.workload);
            Log.i(LOG_TAG,
                String.format("Task: auto swap = %d, workload = %d",
                    t.use_auto_swap_interval ? 1 : 0, t.workload));
          }
        }, ms);
        ms += t.duration * 1000;
      }
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_orbit);

    // See if there is an intent with a test script
    Intent start_intent = getIntent();
    if (start_intent.hasExtra("TEST_SCRIPT")) {
      String test_data = start_intent.getStringExtra("TEST_SCRIPT");
      Log.i(LOG_TAG, String.format("Intent test script (%d) %s", test_data.length(), test_data));
      loadScript(test_data);
    }

    // Get display metrics

    WindowManager wm = getWindowManager();
    @SuppressWarnings("deprecation") Display display = wm.getDefaultDisplay();
    float refreshRateHz = display.getRefreshRate();
    Log.i(LOG_TAG, String.format("Refresh rate: %.1f Hz", refreshRateHz));
    long refreshPeriodNanos = (long) (ONE_S_IN_NS / refreshRateHz);
    long appVsyncOffsetNanos = 0;
    long sfVsyncOffsetNanos = 0;
    if (Build.VERSION.SDK_INT >= 21) {
      appVsyncOffsetNanos = display.getAppVsyncOffsetNanos();
      sfVsyncOffsetNanos =
          refreshPeriodNanos - (display.getPresentationDeadlineNanos() - ONE_MS_IN_NS);
    }
    // Initialize UI

    SurfaceView surfaceView = findViewById(R.id.surface_view);
    surfaceView.getHolder().addCallback(this);

    mInfoOverlay = findViewById(R.id.info_overlay);
    mInfoOverlay.setBackgroundColor(0x80000000);

    buildChoreographerInfoGrid();
    buildSwappyStatsGrid();

    TextView appOffsetView = findViewById(R.id.app_offset);
    appOffsetView.setText(String.format(
        Locale.US, "App Offset: %.1f ms", appVsyncOffsetNanos / (float) ONE_MS_IN_NS));
    TextView sfOffsetView = findViewById(R.id.sf_offset);
    sfOffsetView.setText(
        String.format(Locale.US, "SF Offset: %.1f ms", sfVsyncOffsetNanos / (float) ONE_MS_IN_NS));

    try {
      this.setTitle("BouncyBall v"
          + getApplicationContext()
                .getPackageManager()
                .getPackageInfo(getPackageName(), 0)
                .versionName
          + " (" + String.valueOf(nGetSwappyVersion()) + ")");
    } catch (Exception e) {
      // Ignore
    }

    // Initialize the native renderer

    nInit(refreshPeriodNanos);

    runScript();
  }

  private void infoOverlayToggle() {
    if (mInfoOverlay == null) {
      return;
    }

    mInfoOverlayEnabled = !mInfoOverlayEnabled;
    if (mInfoOverlayEnabled) {
      mInfoOverlay.setVisibility(View.VISIBLE);
      mInfoOverlayButton.setIcon(R.drawable.ic_info_solid_white_24dp);
    } else {
      mInfoOverlay.setVisibility(View.INVISIBLE);
      mInfoOverlayButton.setIcon(R.drawable.ic_info_outline_white_24dp);
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu_orbit, menu);

    mInfoOverlayButton = menu.findItem(R.id.info_overlay_button);
    if (mInfoOverlayButton != null) {
      mInfoOverlayButton.setOnMenuItemClickListener((MenuItem item) -> {
        infoOverlayToggle();
        return true;
      });
    }

    MenuItem settingsButton = menu.findItem(R.id.settings_item);
    if (settingsButton != null) {
      settingsButton.setOnMenuItemClickListener((MenuItem) -> {
        Intent intent = new Intent();
        intent.setClass(getApplicationContext(), SettingsActivity.class);
        startActivity(intent);
        return true;
      });
    }

    return true;
  }

  @Override
  protected void onStart() {
    super.onStart();

    SharedPreferences sharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    for (String key : sharedPreferences.getAll().keySet()) {
      if (key.equals("use_affinity")) {
        nSetPreference(key, sharedPreferences.getBoolean(key, true) ? "true" : "false");
        continue;
      } else if (key.equals("hot_pocket")) {
        nSetPreference(key, sharedPreferences.getBoolean(key, false) ? "true" : "false");
        continue;
      } else if (key.equals("use_auto_swap_interval")) {
        nSetAutoSwapInterval(sharedPreferences.getBoolean(key, true));
        continue;
      } else if (key.equals("workload")) {
        nSetWorkload(sharedPreferences.getInt(key, 0));
        continue;
      } else if (key.equals("swappy_enable")) {
        nEnableSwappy(sharedPreferences.getBoolean(key, true));
        continue;
      } else if (key.equals("use_auto_pipeline")) {
        nSetAutoPipeline(sharedPreferences.getBoolean(key, true));
        continue;
      } else if (key.equals("buffer_stuffing_fix")) {
        nSetBufferStuffingFixWait(sharedPreferences.getInt(key, 0));
        continue;
      } else if (key.equals("frame_pacing_enabled")) {
        nSetEnableFramePacing(sharedPreferences.getBoolean(key, true));
        continue;
      } else if (key.equals("blocking_wait_enabled")) {
        nSetEnableBlockingWait(sharedPreferences.getBoolean(key, true));
        continue;
      }
      nSetPreference(key, sharedPreferences.getString(key, null));
    }

    mIsRunning = true;
    nStart();
    Choreographer.getInstance().postFrameCallback(this);
  }

  @Override
  protected void onStop() {
    super.onStop();

    mIsRunning = false;
    nStop();
    clearChoreographerInfo();
  }

  private int deltaToBin(long delta) {
    long framePeriodNanos = 16666666;
    return (int) ((delta + framePeriodNanos / 2) / framePeriodNanos);
  }

  private void clearChoreographerInfo() {
    for (int i = 0; i < CHOREOGRAPHER_INFO_BIN_COUNT; ++i) {
      mArrivalBinsLastSecond[i] = 0;
      mArrivalBinsLastMinute[i] = 0;
      mTimestampBinsLastSecond[i] = 0;
      mTimestampBinsLastMinute[i] = 0;
    }
    mArrivalBinQueue.clear();
    mTimestampBinQueue.clear();
  }

  private void updateChoregrapherInfoBin(int row, int bin, int value) {
    if (value == mLastChoreographerInfoValues[row - 1][bin]) {
      return;
    }

    mLastChoreographerInfoValues[row - 1][bin] = value;
    mChoreographerInfoGrid[row][bin + 1].setText(String.valueOf(value));
  }

  private void updateSwappyStatsBin(int row, int bin, int value) {
    if (value == mLastSwappyStatsValues[row - 1][bin]) {
      return;
    }

    mLastSwappyStatsValues[row - 1][bin] = value;
    mSwappyGrid[row][bin + 1].setText(String.valueOf(value) + "%");
  }

  private void dumpBins() {
    mArrivalBinQueue.add(mArrivalBinsLastSecond.clone());
    mTimestampBinQueue.add(mTimestampBinsLastSecond.clone());

    for (int bin = 0; bin < CHOREOGRAPHER_INFO_BIN_COUNT; ++bin) {
      mArrivalBinsLastMinute[bin] += mArrivalBinsLastSecond[bin];
      mTimestampBinsLastMinute[bin] += mTimestampBinsLastSecond[bin];
    }

    if (mArrivalBinQueue.size() > 60) {
      int[] oldestArrivalBin = mArrivalBinQueue.remove();
      int[] oldestTimestampBin = mTimestampBinQueue.remove();
      for (int bin = 0; bin < CHOREOGRAPHER_INFO_BIN_COUNT; ++bin) {
        mArrivalBinsLastMinute[bin] -= oldestArrivalBin[bin];
        mTimestampBinsLastMinute[bin] -= oldestTimestampBin[bin];
      }
    }

    for (int bin = 0; bin < CHOREOGRAPHER_INFO_BIN_COUNT; ++bin) {
      updateChoregrapherInfoBin(1, bin, mArrivalBinsLastSecond[bin]);
      updateChoregrapherInfoBin(2, bin, mTimestampBinsLastSecond[bin]);
      updateChoregrapherInfoBin(3, bin, mArrivalBinsLastMinute[bin]);
      updateChoregrapherInfoBin(4, bin, mTimestampBinsLastMinute[bin]);
    }

    for (int stat = 0; stat < 4; ++stat) {
      for (int bin = 0; bin < SWAPPY_STATS_BIN_COUNT; ++bin) {
        updateSwappyStatsBin(stat + 1, bin, nGetSwappyStats(stat, bin));
      }
    }
    TextView appOffsetView = findViewById(R.id.swappy_stats);
    appOffsetView.setText(
        String.format(Locale.US, "SwappyStats: %d Total Frames", nGetSwappyStats(-1, 0)));

    for (int bin = 0; bin < CHOREOGRAPHER_INFO_BIN_COUNT; ++bin) {
      mArrivalBinsLastSecond[bin] = 0;
      mTimestampBinsLastSecond[bin] = 0;
    }
  }

  @Override
  public void doFrame(long frameTimeNanos) {
    TextView fpsView = findViewById(R.id.fps);
    float fps = nGetAverageFps();
    fpsView.setText(String.format(Locale.US, "Frame rate: %.1f Hz (%.2f ms)", fps, 1e3f / fps));

    TextView frameTimeView = findViewById(R.id.frameTime);
    float frameTime = nGetPipelineFrameTimeNS();
    float frameTimeStdDev = nGetPipelineFrameTimeStdDevNS();
    frameTimeView.setText(String.format(Locale.US, "Max(cpu,gpu) time: %.2f +- %.2f ms",
        frameTime * 1e-6f, frameTimeStdDev * 1e-6f));

    TextView refreshPeriodView = findViewById(R.id.refreshPeriod);
    float refreshPeriodNS = nGetRefreshPeriodNS();
    refreshPeriodView.setText(String.format(Locale.US, "Refresh rate: %.2f Hz (%.2f ms)",
        1e9f / refreshPeriodNS, refreshPeriodNS * 1e-6f));

    TextView swapIntervalView = findViewById(R.id.swapInterval);
    float swapIntervalNS = nGetSwapIntervalNS();
    swapIntervalView.setText(String.format(
        Locale.US, "Swap rate: %.2f Hz (%.2f ms)", 1e9f / swapIntervalNS, swapIntervalNS * 1e-6f));

    long now = System.nanoTime();

    if (mIsRunning) {
      Choreographer.getInstance().postFrameCallback(this);
    }

    long arrivalDelta = now - mLastArrivalTime;
    long timestampDelta = now - mLastFrameTimestamp;
    mLastArrivalTime = now;
    mLastFrameTimestamp = frameTimeNanos;

    mArrivalBinsLastSecond[Math.min(deltaToBin(arrivalDelta), mArrivalBinsLastSecond.length - 1)] +=
        1;
    mTimestampBinsLastSecond[Math.min(
        deltaToBin(timestampDelta), mTimestampBinsLastSecond.length - 1)] += 1;

    if (now - mLastDumpTime > 1000000000) {
      dumpBins();
      // Trim off excess precision so we don't drift forward over time
      mLastDumpTime = now - (now % 1000000000);
    }
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    // Do nothing here, waiting for surfaceChanged instead
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    Surface surface = holder.getSurface();
    nSetSurface(surface, width, height);
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    nClearSurface();
  }

  public native void nInit(long initialSwapIntervalNS);
  public native void nSetSurface(Surface surface, int width, int height);
  public native void nClearSurface();
  public native void nStart();
  public native void nStop();
  public native void nSetPreference(String key, String value);
  public native void nSetWorkload(int load);
  public native void nEnableSwappy(boolean enabled);
  public native void nSetAutoSwapInterval(boolean enabled);
  public native void nSetAutoPipeline(boolean enabled);
  public native void nSetBufferStuffingFixWait(int n_frames);
  public native float nGetAverageFps();
  public native float nGetRefreshPeriodNS();
  public native float nGetSwapIntervalNS();
  public native float nGetPipelineFrameTimeNS();
  public native float nGetPipelineFrameTimeStdDevNS();
  public native int nGetSwappyStats(int stat, int bin);
  public native long nGetSwappyVersion();
  public native void nSetEnableFramePacing(boolean enabled);
  public native void nSetEnableBlockingWait(boolean enabled);

  private MenuItem mInfoOverlayButton;

  private LinearLayout mInfoOverlay;
  private final TextView[][] mChoreographerInfoGrid = new TextView[5][7];
  private final int[][] mLastChoreographerInfoValues = new int[4][6];

  private final TextView[][] mSwappyGrid = new TextView[5][7];
  private final int[][] mLastSwappyStatsValues = new int[4][6];

  private boolean mIsRunning;
  private boolean mInfoOverlayEnabled = false;

  private final ExecutorService mChoreographerExecutor = Executors.newSingleThreadExecutor();
  private final Handler mUIThreadHandler = new Handler(Looper.getMainLooper());

  private static final int CHOREOGRAPHER_INFO_BIN_COUNT = 6;
  private static final int SWAPPY_STATS_BIN_COUNT = 6;
  private long mLastDumpTime;
  private long mLastArrivalTime;
  private long mLastFrameTimestamp;
  private final int[] mArrivalBinsLastSecond = new int[CHOREOGRAPHER_INFO_BIN_COUNT];
  private final int[] mArrivalBinsLastMinute = new int[CHOREOGRAPHER_INFO_BIN_COUNT];
  private final Queue<int[]> mArrivalBinQueue = new ArrayDeque<>(60);
  private final int[] mTimestampBinsLastSecond = new int[CHOREOGRAPHER_INFO_BIN_COUNT];
  private final int[] mTimestampBinsLastMinute = new int[CHOREOGRAPHER_INFO_BIN_COUNT];
  private final Queue<int[]> mTimestampBinQueue = new ArrayDeque<>(60);
}
