package com.samples.cube;

import android.app.Activity;
import android.content.Intent;
import android.graphics.text.LineBreaker;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.text.Layout;
import android.util.Log;
import android.view.Choreographer;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

public class CubeActivity
    extends Activity implements Choreographer.FrameCallback, SurfaceHolder.Callback {
    private static final String GPU_WORKLOAD = "com.samples.GPU_WORKLOAD";
    private static final String CPU_WORKLOAD = "com.samples.CPU_WORKLOAD";
    private static final String APP_NAME = "CubeActivity";

    private LinearLayout settingsLayout;

    private SeekBar gpuWorkSeekBar;
    private TextView gpuWorkText;
    private SeekBar cpuWorkSeekBar;
    private TextView cpuWorkText;

    // Used to load the 'cube' library on application startup.
    static {
        try {
            System.loadLibrary("cube");
        } catch (Exception e) {
            Log.e(APP_NAME, "Native code library failed to load.\n" + e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cube);
        settingsLayout = findViewById(R.id.settingsLayout);

        setupVersionInfo();
        setupGpuWorkSeekBar();
        setupCpuWorkSeekBar();

        SurfaceView surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);

        Intent intent = getIntent();
        String action = intent.getAction();
        switch (action) {
            case Intent.ACTION_MAIN:
                updateGpuWork(0);
                updateCpuWork(0);
                break;
            case Intent.ACTION_SEND:
                handleSendIntent(intent);
                break;
            default:
                Log.e(APP_NAME, "Unknown intent received: " + action);
                break;
        }

        mInfoOverlay = findViewById(R.id.info_overlay);
        buildSwappyStatsGrid();
    }

    private void toggleOptionsPanel() {
        if (settingsLayout.getVisibility() == View.GONE) {
            settingsLayout.setVisibility(View.VISIBLE);
        } else {
            settingsLayout.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            handleSendIntent(intent);
        }
    }

    private void handleSendIntent(Intent intent) {
        String gpuWorkStr = intent.getStringExtra(GPU_WORKLOAD);
        if (gpuWorkStr != null) {
            updateGpuWork(Integer.parseInt(gpuWorkStr));
            Log.d(APP_NAME, "GPU work changed by intent. gpu_workload: " + gpuWorkStr);
        }
        String cpuWorkStr = intent.getStringExtra(CPU_WORKLOAD);
        if (cpuWorkStr != null) {
            updateCpuWork(Integer.parseInt(cpuWorkStr));
            Log.d(APP_NAME, "CPU work changed by intent. cpu_workload: " + cpuWorkStr);
        }
    }

    private void setupVersionInfo() {
        TextView versionInfoText = findViewById(R.id.textViewVersionInfo);

        versionInfoText.setText(String.format("Cube v%s", BuildConfig.VERSION_NAME));
        versionInfoText.setTextSize(20);
    }

    private void setupGpuWorkSeekBar() {
        gpuWorkSeekBar = findViewById(R.id.seekBarGpuWork);
        gpuWorkText = findViewById(R.id.textViewGpuWork);

        gpuWorkSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }

                int newGpuWork = linearToExponential(progress);
                updateGpuWork(newGpuWork, true);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateGpuWork(int newGpuWork) {
        updateGpuWork(newGpuWork, false);
    }

    private void updateGpuWork(int newGpuWork, boolean fromSeekBar) {
        gpuWorkText.setText(String.format("GPU Work: %,d", newGpuWork));
        if (!fromSeekBar) {
            gpuWorkSeekBar.setProgress(exponentialToLinear(newGpuWork));
        }
        nUpdateGpuWorkload(newGpuWork);
    }

    private void setupCpuWorkSeekBar() {
        cpuWorkSeekBar = findViewById(R.id.seekBarCpuWork);
        cpuWorkText = findViewById(R.id.textViewCpuWork);

        cpuWorkSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }

                int newCpuWork = linearToExponential(progress);
                updateCpuWork(newCpuWork, true);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateCpuWork(int newCpuWork) {
        updateCpuWork(newCpuWork, false);
    }

    private void updateCpuWork(int newCpuWork, boolean fromSeekBar) {
        cpuWorkText.setText(String.format("CPU Work: %,d", newCpuWork));
        if (!fromSeekBar) {
            cpuWorkSeekBar.setProgress(exponentialToLinear(newCpuWork));
        }
        nUpdateCpuWorkload(newCpuWork);
    }

    static private int linearToExponential(int linearValue) {
        return linearValue == 0 ? 0 : (int) Math.pow(10, (double) linearValue / 1000.0);
    }

    static private int exponentialToLinear(int exponentialValue) {
        return exponentialValue == 0 ? 0 : (int) Math.log10(exponentialValue) * 1000;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(APP_NAME, "Surface created.");
        Surface surface = holder.getSurface();
        nStartCube(surface);
        mIsRunning = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(APP_NAME, "Surface destroyed.");
        nStopCube();
        mIsRunning = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    /**
     * Native methods that are implemented by the 'cube' native library,
     * which is packaged with this application.
     */
    public native void nStartCube(Surface holder);
    public native void nStopCube();
    public native void nUpdateGpuWorkload(int newWorkload);
    public native void nUpdateCpuWorkload(int newWorkload);
    public native int nGetSwappyStats(int stat, int bin);

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
        inflater.inflate(R.menu.menu_cube, menu);

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
                toggleOptionsPanel();
                return true;
            });
        }

        return true;
    }

    private void configureGridCell(TextView cell) {
        // A bunch of optimizations to reduce the cost of setText
        cell.setEllipsize(null);
        cell.setMaxLines(1);
        cell.setSingleLine(true);
        if (VERSION.SDK_INT >= VERSION_CODES.Q) {
            cell.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
        }
        cell.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);

        cell.setTextAppearance(R.style.InfoTextSmall);
        cell.setText("0");
    }

    private void buildSwappyStatsGrid() {
        GridLayout infoGrid = findViewById(R.id.swappy_stats_grid);

        // Add the header row
        GridLayout.Spec headerRowSpec = GridLayout.spec(0);
        for (int column = 0; column < mSwappyGrid[0].length; ++column) {
            TextView cell = new TextView(getApplicationContext());
            GridLayout.Spec colSpec = GridLayout.spec(column, 1.0f);
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
                TextView cell = new TextView(getApplicationContext());
                GridLayout.Spec colSpec = GridLayout.spec(column, 1.0f);
                cell.setLayoutParams(new GridLayout.LayoutParams(rowSpec, colSpec));
                cell.setTextAppearance(R.style.InfoTextSmall);
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

    private void updateSwappyStatsBin(int row, int bin, int value) {
        if (value == mLastSwappyStatsValues[row - 1][bin]) {
            return;
        }
        mLastSwappyStatsValues[row - 1][bin] = value;
        mSwappyGrid[row][bin + 1].setText(String.valueOf(value) + "%");
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        long now = System.nanoTime();
        if (mIsRunning) {
            Choreographer.getInstance().postFrameCallback(this);
        }
        if (now - mLastDumpTime > SWAPPY_GET_STATS_PERIOD) {
            for (int stat = 0; stat < 4; ++stat) {
                for (int bin = 0; bin < SWAPPY_STATS_BIN_COUNT; ++bin) {
                    updateSwappyStatsBin(stat + 1, bin, nGetSwappyStats(stat, bin));
                }
            }
            TextView appOffsetView = findViewById(R.id.swappy_stats);
            appOffsetView.setText(
                String.format(Locale.US, "SwappyStats: %d Total Frames", nGetSwappyStats(-1, 0)));
            // Trim off excess precision so we don't drift forward over time
            mLastDumpTime = now - (now % SWAPPY_GET_STATS_PERIOD);
        }
    }

    private boolean mInfoOverlayEnabled = false;
    private MenuItem mInfoOverlayButton;
    private LinearLayout mInfoOverlay;
    private final TextView[][] mSwappyGrid = new TextView[5][7];
    private final int[][] mLastSwappyStatsValues = new int[4][6];
    private static final int SWAPPY_STATS_BIN_COUNT = 6;
    private static final long SWAPPY_GET_STATS_PERIOD = 1000000000; // 1s in ns.
    private long mLastDumpTime;
    private boolean mIsRunning;
}
