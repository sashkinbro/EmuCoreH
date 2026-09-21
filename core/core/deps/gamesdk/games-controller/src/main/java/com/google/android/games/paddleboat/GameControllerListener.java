// Copyright (C) 2021 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.android.games.paddleboat;

import android.annotation.SuppressLint;
import android.hardware.Sensor;
import android.hardware.SensorDirectChannel;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Build;
import android.util.Log;
import android.view.InputDevice;

import androidx.annotation.GuardedBy;

import java.lang.NullPointerException;
import java.util.List;

public class GameControllerListener {

    private static final String TAG = "GameControllerListener";
    // Must match Paddleboat_Motion_Data_Callback_Sensor_Index definition
    // in paddleboat.h
    private static final int INTEGRATED_SENSOR_INDEX = (0x40000000);
    private boolean reportMotionEvents;
    private boolean integratedAccelerometerActive;
    private boolean integratedGyroscopeActive;
    private int inputDeviceFlags;
    private int inputDeviceId;
    private final GameControllerManager gameControllerManager;
    private GameControllerAccelerometerListener accelerometerListener;
    private GameControllerGyroscopeListener gyroscopeListener;
    private InputDevice inputDevice;
    private final Object mLightLock = new Object();
    @GuardedBy("mLightLock")
    private LightsManager lightsManager;
    private LightsManager.LightsSession lightsSession;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private final Object mSensorLock = new Object();
    @GuardedBy("mSensorLock")
    private SensorManager sensorManager;
    public GameControllerListener(GameControllerManager gcManager, InputDevice newDevice,
                                  int newFlags, boolean motionEvents) {
        gameControllerManager = gcManager;
        inputDevice = newDevice;
        inputDeviceFlags = newFlags;
        // If a null input device is specified, we are a listener for integrated sensor
        // devices
        if (inputDevice != null) {
            inputDeviceId = inputDevice.getId();
            sensorManager = null;
        } else {
            inputDeviceId = INTEGRATED_SENSOR_INDEX;
            sensorManager = gameControllerManager.getAppSensorManager();
        }
        reportMotionEvents = motionEvents;
        integratedAccelerometerActive = false;
        integratedGyroscopeActive = false;
        lightsManager = null;
        lightsSession = null;
        accelerometer = null;
        accelerometerListener = null;
        gyroscope = null;
        gyroscopeListener = null;

        configureMotion();
    }

    public void resetListener(InputDevice newDevice, int newFlags) {
        shutdownListener();
        inputDevice = newDevice;
        inputDeviceFlags = newFlags;
        if (inputDevice != null) {
            inputDeviceId = inputDevice.getId();
        } else {
            inputDeviceId = INTEGRATED_SENSOR_INDEX;
            sensorManager = gameControllerManager.getAppSensorManager();
        }
        configureMotion();
    }

    public void shutdownListener() {
        // Called when the controller device sends a disconnected or changed event,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "shutdownListener");
            synchronized (mLightLock) {
                if (lightsSession != null) {
                    lightsSession.close();
                }
                lightsSession = null;
                lightsManager = null;
            }

            synchronized (mSensorLock) {
                if (sensorManager != null) {
                    if (accelerometerListener != null) {
                        sensorManager.unregisterListener(accelerometerListener);
                        accelerometerListener = null;
                    }
                    if (gyroscopeListener != null) {
                        sensorManager.unregisterListener((gyroscopeListener));
                        gyroscopeListener = null;
                    }
                }
                accelerometer = null;
                gyroscope = null;
                sensorManager = null;
            }
        }
        if (inputDevice != null) {
            // Only reset if we are not an integrated listener
            inputDeviceFlags = 0;
            inputDevice = null;
        }
    }

    public void setIntegratedAccelerometerActive(boolean active) {
        synchronized (mSensorLock) {
            if (sensorManager != null) {
                if (active && accelerometerListener == null) {
                    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    if (accelerometer != null) {
                        if (gameControllerManager.getPrintControllerInfo()) {
                            printSensorInformation(accelerometer, "integratedAccelerometer");
                        }
                        accelerometerListener =
                                new GameControllerAccelerometerListener(accelerometer);
                        Log.d(TAG, "registering listener for integrated accelerometer");
                        sensorManager.registerListener(accelerometerListener, accelerometer,
                                SensorManager.SENSOR_DELAY_GAME);
                    }
                } else if (!active && accelerometerListener != null) {
                    if (accelerometerListener != null) {
                        Log.d(TAG, "unregistering listener for integrated accelerometer");
                        sensorManager.unregisterListener(accelerometerListener);
                        accelerometerListener = null;
                    }
                }
                integratedAccelerometerActive = active;
            }
        }
    }

    public void setIntegratedGyroscopeActive(boolean active) {
        synchronized (mSensorLock) {
            if (sensorManager != null) {
                if (active && gyroscopeListener == null) {
                    gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                    if (gyroscope != null) {
                        if (gameControllerManager.getPrintControllerInfo()) {
                            printSensorInformation(gyroscope, "integratedGyroscope");
                        }
                        gyroscopeListener =
                                new GameControllerGyroscopeListener(gyroscope);
                        Log.d(TAG, "registering listener for integrated gyroscope");
                        sensorManager.registerListener(gyroscopeListener, gyroscope,
                                SensorManager.SENSOR_DELAY_GAME);
                    }
                } else if (!active && gyroscopeListener != null) {
                    if (gyroscopeListener != null) {
                        Log.d(TAG, "unregistering listener for integrated gyroscope");
                        sensorManager.unregisterListener(gyroscopeListener);
                        gyroscopeListener = null;
                    }
                }
                integratedGyroscopeActive = active;
            }
        }
    }

    public void setReportMotionEvents(boolean motionEventsActive) {
        reportMotionEvents = motionEventsActive;
        if (motionEventsActive) {
            configureMotion();
        } else {
            shutdownListener();
        }
    }

    public void setLight(int lightType, int lightValue) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized (mLightLock) {
                // Avoid starting a light session until we actually make changes
                // to a light
                if (lightsManager == null) {
                    configureLights();
                }
                if (lightsManager != null) {
                    for (Light currentLight : lightsManager.getLights()) {
                        if (lightType == GameControllerManager.LIGHT_TYPE_PLAYER &&
                                currentLight.getType() == Light.LIGHT_TYPE_PLAYER_ID) {
                            LightState.Builder stateBuilder = new LightState.Builder();
                            stateBuilder.setPlayerId(lightValue);
                            LightsRequest.Builder requestBuilder = new LightsRequest.Builder();
                            requestBuilder.addLight(currentLight, stateBuilder.build());
                            lightsSession.requestLights(requestBuilder.build());
                            break;
                        } else if (lightType == GameControllerManager.LIGHT_TYPE_RGB &&
                                currentLight.hasRgbControl()) {
                            LightState.Builder stateBuilder = new LightState.Builder();
                            stateBuilder.setColor(lightValue);
                            LightsRequest.Builder requestBuilder = new LightsRequest.Builder();
                            requestBuilder.addLight(currentLight, stateBuilder.build());
                            lightsSession.requestLights(requestBuilder.build());
                            break;
                        }
                    }
                }
            }
        }

    }

    private void configureLights() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if ((inputDeviceFlags & (GameControllerManager.DEVICEFLAG_LIGHT_PLAYER |
                    GameControllerManager.DEVICEFLAG_LIGHT_RGB)) != 0) {
                synchronized (mLightLock) {
                    Log.d(TAG, "configureLights");
                    lightsManager = inputDevice.getLightsManager();
                    lightsSession = lightsManager.openSession();
                }
            }
        }
    }

    private void configureMotion() {
        if (inputDevice == null) {
            // Integrated sensor reporting gets configured by the setIntegrated calls
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && reportMotionEvents) {
            synchronized (mSensorLock) {
                if (sensorManager == null) {
                    // Integrated sensor gets assigned at creation, otherwise
                    // we have to set from the device's own sensor manager
                    sensorManager = inputDevice.getSensorManager();
                }
                if ((inputDeviceFlags & (GameControllerManager.DEVICEFLAG_ACCELEROMETER |
                        GameControllerManager.DEVICEFLAG_GYROSCOPE)) != 0) {
                    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    if (accelerometer != null) {
                        if (gameControllerManager.getPrintControllerInfo()) {
                            printSensorInformation(accelerometer, "accelerometer");
                        }
                        accelerometerListener =
                                new GameControllerAccelerometerListener(accelerometer);
                        Log.d(TAG, "registering listener for accelerometer");
                        sensorManager.registerListener(accelerometerListener, accelerometer,
                                SensorManager.SENSOR_DELAY_GAME);
                    }
                    gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                    if (gyroscope != null) {
                        if (gameControllerManager.getPrintControllerInfo()) {
                            printSensorInformation(gyroscope, "gyroscope");
                        }
                        gyroscopeListener = new GameControllerGyroscopeListener(gyroscope);
                        Log.d(TAG, "registering listener for gyroscope");
                        sensorManager.registerListener(gyroscopeListener, gyroscope,
                                SensorManager.SENSOR_DELAY_GAME);
                    }
                }
            }
        }
    }

    private void printSensorInformation(Sensor sensor, String sensorName) {
        Log.d(TAG, "Registering listener for " + sensorName);
        Log.d(TAG, "Begin sensor information -----------------------------");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Log.d(TAG, "getFifoMaxEventCount: " + sensor.getFifoMaxEventCount());
            Log.d(TAG, "getFifoReservedEventCount: " + sensor.getFifoReservedEventCount());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "getHighestDirectReportRateLevel: " +
                    sensor.getHighestDirectReportRateLevel());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "getId: " + sensor.getId());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "getMaxDelay: " + sensor.getMaxDelay());
        }
        Log.d(TAG, "getMaximumRange: " + sensor.getMaximumRange());
        Log.d(TAG, "getMinDelay: " + sensor.getMinDelay());
        Log.d(TAG, "getName: " + sensor.getName());
        Log.d(TAG, "getPower: " + sensor.getPower());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "getReportingMode: " + sensor.getReportingMode());
        }
        Log.d(TAG, "getVendor: " + sensor.getVendor());
        Log.d(TAG, "getVersion: " + sensor.getVersion());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "isAdditionalInfoSupported: " + sensor.isAdditionalInfoSupported());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean canMemoryFile =
                    sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE);
            boolean canHardwareBuffer =
                    sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER);
            Log.d(TAG, "DirectChannel Memory File: " + canMemoryFile);
            Log.d(TAG, "DirectChannel Hardware Buffer: " + canHardwareBuffer);
        }
        Log.d(TAG, "End sensor information -------------------------------");
    }

    class GameControllerAccelerometerListener implements android.hardware.SensorEventListener {
        private final Sensor listenerAccelerometer;

        GameControllerAccelerometerListener(Sensor newAccelerometer) {
            listenerAccelerometer = newAccelerometer;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (listenerAccelerometer != null) {
                synchronized (listenerAccelerometer) {
                    if (event.sensor == listenerAccelerometer) {
                        gameControllerManager.onMotionData(inputDeviceId,
                                GameControllerManager.MOTION_ACCELEROMETER, event.timestamp,
                                event.values[0], event.values[1], event.values[2]);
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }

    class GameControllerGyroscopeListener implements android.hardware.SensorEventListener {
        private final Sensor listenerGyroscope;

        GameControllerGyroscopeListener(Sensor newGyroscope) {
            listenerGyroscope = newGyroscope;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (listenerGyroscope != null) {
                synchronized (listenerGyroscope) {
                    if (event.sensor == listenerGyroscope) {
                        gameControllerManager.onMotionData(inputDeviceId,
                                GameControllerManager.MOTION_GYROSCOPE, event.timestamp,
                                event.values[0], event.values[1], event.values[2]);
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    }
}
