/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.incallui;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class is used to listen to the accelerometer to monitor the
 * orientation of the phone. The client of this class is notified when
 * the orientation changes between horizontal and vertical.
 */
public class AccelerometerListener {
    private static final String TAG = "AccelerometerListener";
    private static final boolean DEBUG = true;
    private static final boolean VDEBUG = false;

    private SensorManager mSensorManager;
    private Sensor mSensor;

    // mOrientation is the orientation value most recently reported to the client.
    private int mOrientation;

    // mPendingOrientation is the latest orientation computed based on the sensor value.
    // This is sent to the client after a rebounce delay, at which point it is copied to
    // mOrientation.
    private int mPendingOrientation;

    private ChangeListener mListener;

    // Device orientation
    public static final int ORIENTATION_UNKNOWN = 0;
    public static final int ORIENTATION_VERTICAL = 1;
    public static final int ORIENTATION_HORIZONTAL = 2;

    private static final int ORIENTATION_CHANGED = 1234;
    private static final int FACE_UP_CHANGED = 1235;

    private static final int VERTICAL_DEBOUNCE = 100;
    private static final int HORIZONTAL_DEBOUNCE = 500;
    private static final double VERTICAL_ANGLE = 50.0;

    // Flip detection
    private static final int FACE_UP_GRAVITY_THRESHOLD = 7;
    private static final int FACE_DOWN_GRAVITY_THRESHOLD = -7;
    private static final int SENSOR_SAMPLES = 3;
    private static final int MIN_ACCEPT_COUNT = SENSOR_SAMPLES - 1;

    private boolean mWasFaceUp;
    private boolean[] mSamples = new boolean[SENSOR_SAMPLES];
    private int mSampleIndex;

    public interface ChangeListener {
        void onOrientationChanged(int orientation);
        void onDeviceFlipped(boolean faceDown);
    }

    public AccelerometerListener(Context context) {
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public AccelerometerListener(Context context, ChangeListener listener) {
        setListener(listener);
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public void setListener(ChangeListener listener) {
        mListener = listener;
    }

    public void enable(boolean enable) {
        if (DEBUG) Log.d(TAG, "enable(" + enable + ")");
        synchronized (this) {
            if (enable) {
                mOrientation = ORIENTATION_UNKNOWN;
                mPendingOrientation = ORIENTATION_UNKNOWN;
                mWasFaceUp = false;
                resetFlipSamples();
                mSensorManager.registerListener(mSensorListener, mSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                mSensorManager.unregisterListener(mSensorListener);
                mHandler.removeMessages(ORIENTATION_CHANGED);
            }
        }
    }

    private void resetFlipSamples() {
        for (int i = 0; i < SENSOR_SAMPLES; i++) {
            mSamples[i] = false;
        }
    }

    private boolean filterFlipSamples() {
        int trues = 0;
        for (int i = 0; i < mSamples.length; i++) {
            if (mSamples[i]) {
                ++trues;
            }
        }
        return trues >= MIN_ACCEPT_COUNT;
    }

    private void setOrientation(int orientation) {
        synchronized (this) {
            if (mPendingOrientation == orientation) {
                // Pending orientation has not changed, so do nothing.
                return;
            }

            // Cancel any pending messages.
            // We will either start a new timer or cancel alltogether
            // if the orientation has not changed.
            mHandler.removeMessages(ORIENTATION_CHANGED);

            if (mOrientation != orientation) {
                // Set timer to send an event if the orientation has changed since its
                // previously reported value.
                mPendingOrientation = orientation;
                final Message m = mHandler.obtainMessage(ORIENTATION_CHANGED);
                // set delay to our debounce timeout
                int delay = (orientation == ORIENTATION_VERTICAL ? VERTICAL_DEBOUNCE
                                                                 : HORIZONTAL_DEBOUNCE);
                mHandler.sendMessageDelayed(m, delay);
            } else {
                // no message is pending
                mPendingOrientation = ORIENTATION_UNKNOWN;
            }
        }
    }

    private void setIsFaceUp(boolean faceUp) {
        synchronized (this) {
            if (mWasFaceUp != faceUp) {
                mHandler.removeMessages(FACE_UP_CHANGED);
                mHandler.obtainMessage(FACE_UP_CHANGED, faceUp ? 1 : 0, 0).sendToTarget();
                mWasFaceUp = faceUp;
                resetFlipSamples();
            }
        }
    }

    private void onSensorEvent(double x, double y, double z) {
        if (VDEBUG) Log.d(TAG, "onSensorEvent(" + x + ", " + y + ", " + z + ")");

        // If some values are exactly zero, then likely the sensor is not powered up yet.
        // ignore these events to avoid false horizontal positives.
        if (x == 0.0 || y == 0.0 || z == 0.0) return;

        // magnitude of the acceleration vector projected onto XY plane
        final double xy = Math.hypot(x, y);
        // compute the vertical angle
        double angle = Math.atan2(xy, z);
        // convert to degrees
        angle = angle * 180.0 / Math.PI;
        final int orientation = (angle >  VERTICAL_ANGLE ? ORIENTATION_VERTICAL : ORIENTATION_HORIZONTAL);
        if (VDEBUG) Log.d(TAG, "angle: " + angle + " orientation: " + orientation);
        setOrientation(orientation);

        boolean nowFaceUp, wasFaceUp;
        synchronized (this) {
            nowFaceUp = wasFaceUp = mWasFaceUp;
        }

        if (!wasFaceUp) {
            // Check if its face up enough.
            mSamples[mSampleIndex] = z > FACE_UP_GRAVITY_THRESHOLD;
        } else {
            // Check if its face down enough.
            mSamples[mSampleIndex] = z < FACE_DOWN_GRAVITY_THRESHOLD;
        }
        if (filterFlipSamples()) {
            nowFaceUp = !wasFaceUp;
        }

        mSampleIndex = ((mSampleIndex + 1) % SENSOR_SAMPLES);
        setIsFaceUp(nowFaceUp);
    }

    SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onSensorEvent(event.values[0], event.values[1], event.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // ignore
        }
    };

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case ORIENTATION_CHANGED:
                synchronized (this) {
                    mOrientation = mPendingOrientation;
                    if (DEBUG) {
                        Log.d(TAG, "orientation: " +
                            (mOrientation == ORIENTATION_HORIZONTAL ? "horizontal"
                                : (mOrientation == ORIENTATION_VERTICAL ? "vertical"
                                    : "unknown")));
                    }
                    if (mListener != null) {
                        mListener.onOrientationChanged(mOrientation);
                    }
                }
                break;
            case FACE_UP_CHANGED:
                mListener.onDeviceFlipped(msg.arg1 == 0);
                break;
            }
        }
    };
}
