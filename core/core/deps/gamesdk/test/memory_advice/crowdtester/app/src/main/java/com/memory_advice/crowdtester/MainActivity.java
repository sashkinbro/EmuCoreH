package com.memory_advice.crowdtester;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.apps.internal.games.memoryadvice.MemoryAdvisor;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    // Used to load app's native libraries on application startup.
    static {
        System.loadLibrary("memory_advice");
        System.loadLibrary("crowdtester");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initMemoryAdvice();
        MemoryAdvisor javaAdvisor = new MemoryAdvisor(this);

        double javaPrediction = 0;
        double nativePrediction = 0;

        int count = 0;
        double difference = 0;

        while (javaPrediction < 0.5) {
            try {
                javaPrediction = new JSONObject(javaAdvisor.getAdvice())
                                     .getJSONObject("metrics")
                                     .getDouble("predictedAvailable");
                nativePrediction = new JSONObject(getMemoryAdvice())
                                       .getJSONObject("metrics")
                                       .getDouble("predictedAvailable");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            onAllocate();
            count++;
            difference += Math.abs(javaPrediction - nativePrediction);
        }

        int percentage = (int) (100 * difference / count);

        TextView tv = findViewById(R.id.text1);
        TextView tv2 = findViewById(R.id.text2);
        if (percentage == 0) {
            tv.setText("Test: SUCCESSFUL");
        } else {
            tv.setText("Test: FAILED");
        }
        tv2.setText("Percent Difference: " + percentage);
    }
    static final long BYTES_PER_MEGABYTE = 1000000;
    static final long BYTES_TO_ADD = 100 * BYTES_PER_MEGABYTE;
    long memory_allocated;
    public void onAllocate() {
        if (allocate(BYTES_TO_ADD)) {
            memory_allocated += BYTES_TO_ADD;
        }
    }
    public native int initMemoryAdvice();
    public native String getMemoryAdvice();
    public native boolean allocate(long bytes);
}
