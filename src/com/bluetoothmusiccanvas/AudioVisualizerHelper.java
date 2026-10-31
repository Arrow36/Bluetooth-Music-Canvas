package com.bluetoothmusiccanvas;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class AudioVisualizerHelper {
    private static final String TAG = "AudioVisualizer";
    private Visualizer mVisualizer;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mIsRunning = false;
    private OnWaveDataListener mListener;
    private long mLastDispatchTime = 0;

    // AGC (Automatic Gain Control) & Dynamic Range Peak Trackers for 5 bands
    private final float[] mBandPeaks = new float[]{1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    private final float[] mBandSmooth = new float[]{0.08f, 0.08f, 0.08f, 0.08f, 0.08f};

    public interface OnWaveDataListener {
        void onFftBands(float b0, float b1, float b2, float b3, float b4);
    }

    public AudioVisualizerHelper(OnWaveDataListener listener) {
        this.mListener = listener;
    }

    public synchronized void start() {
        if (mIsRunning && mVisualizer != null) return;
        try {
            if (mVisualizer != null) {
                try { mVisualizer.release(); } catch (Throwable ignored) {}
                mVisualizer = null;
            }

            // Audio session 0 captures the system audio output mix
            mVisualizer = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = Math.max(128, range[0]);
            if (captureSize > 256) captureSize = 256;
            mVisualizer.setCaptureSize(captureSize);

            int rate = Visualizer.getMaxCaptureRate();
            int targetRate = Math.min(rate, 25000); // 25 Hz in millihertz

            mVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    processFft(fft);
                }
            }, targetRate, false, true);

            mVisualizer.setEnabled(true);
            mIsRunning = true;
            Log.i(TAG, "Visualizer enabled successfully! captureSize=" + captureSize);
        } catch (Throwable t) {
            Log.e(TAG, "Cannot start Visualizer(0): " + t.getMessage(), t);
            mVisualizer = null;
            mIsRunning = false;
        }
    }

    public synchronized void stop() {
        mIsRunning = false;
        if (mVisualizer != null) {
            try {
                mVisualizer.setEnabled(false);
                mVisualizer.release();
            } catch (Throwable ignored) {}
            mVisualizer = null;
            Log.i(TAG, "Visualizer stopped and released");
        }
    }

    private void processFft(byte[] fft) {
        if (fft == null || fft.length < 16) return;

        long now = System.currentTimeMillis();
        if (now - mLastDispatchTime < 35) return;
        mLastDispatchTime = now;

        int n = fft.length / 2;
        float[] mags = new float[n];
        mags[0] = Math.abs(fft[0]);
        for (int k = 1; k < n; k++) {
            float r = fft[2 * k];
            float i = fft[2 * k + 1];
            mags[k] = (float) Math.hypot(r, i);
        }

        // Group into 5 frequency spectrum bands:
        // 0: Sub-bass & Bass (bins 1..3)
        // 1: Low-Mid (bins 4..7)
        // 2: Midrange (bins 8..15)
        // 3: High-Mid (bins 16..27)
        // 4: Treble & Air (bins 28..n-1)
        float[] rawBands = new float[5];
        rawBands[0] = avg(mags, 1, 3);
        rawBands[1] = avg(mags, 4, 7);
        rawBands[2] = avg(mags, 8, 15);
        rawBands[3] = avg(mags, 16, 27);
        rawBands[4] = avg(mags, 28, n - 1);

        float totalEnergy = rawBands[0] + rawBands[1] + rawBands[2] + rawBands[3] + rawBands[4];
        if (totalEnergy < 0.2f) {
            // Signal is effectively silent or muted by hardware output
            return;
        }

        // AGC & Adaptive Peak Tracking:
        // Automatically normalizes relative loudness across frequency bands independent of volume slider!
        final float[] outBands = new float[5];
        for (int i = 0; i < 5; i++) {
            if (rawBands[i] > mBandPeaks[i]) {
                mBandPeaks[i] = rawBands[i];
            } else {
                mBandPeaks[i] = Math.max(0.5f, mBandPeaks[i] * 0.965f);
            }

            float norm = rawBands[i] / Math.max(0.6f, mBandPeaks[i]);
            norm = (float) Math.pow(norm, 1.3);
            mBandSmooth[i] = mBandSmooth[i] * 0.30f + norm * 0.70f;
            outBands[i] = clamp(mBandSmooth[i], 0.10f, 1.0f);
        }

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) {
                    mListener.onFftBands(outBands[0], outBands[1], outBands[2], outBands[3], outBands[4]);
                }
            }
        });
    }

    private static float avg(float[] arr, int s, int e) {
        if (s >= arr.length) return 0f;
        if (e >= arr.length) e = arr.length - 1;
        if (s > e) return arr[s];
        float sum = 0;
        for (int i = s; i <= e; i++) sum += arr[i];
        return sum / (e - s + 1);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
