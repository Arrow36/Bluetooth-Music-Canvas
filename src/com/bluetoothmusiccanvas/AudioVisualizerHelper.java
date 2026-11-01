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
    private int mSilenceCount = 0;
    private int mActiveCount = 0;

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
            int captureSize = Math.min(1024, range[1]);
            captureSize = Math.max(range[0], captureSize);
            mVisualizer.setCaptureSize(captureSize);

            int rate = Visualizer.getMaxCaptureRate();
            int targetRate = Math.min(rate, 25000); // 25 Hz in millihertz

            mVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    processFft(fft, samplingRate);
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

    private void processFft(byte[] fft, int samplingRate) {
        if (fft == null || fft.length < 16) return;

        long now = System.currentTimeMillis();
        if (now - mLastDispatchTime < 35) return;
        mLastDispatchTime = now;

        float fs = samplingRate > 0 ? (samplingRate / 1000.0f) : 48000.0f;
        int n = fft.length / 2;
        int fftSize = fft.length;
        float binWidth = fs / fftSize; // Hz per bin

        float[] mags = new float[n];
        mags[0] = Math.abs(fft[0]);
        for (int k = 1; k < n; k++) {
            float r = fft[2 * k];
            float i = fft[2 * k + 1];
            mags[k] = (float) Math.hypot(r, i);
        }

        // Physical frequency spectrum bands:
        // 0: Sub-bass & Bass (20Hz - 250Hz)
        // 1: Low-Mid (250Hz - 600Hz)
        // 2: Midrange (600Hz - 2000Hz)
        // 3: High-Mid (2000Hz - 6000Hz)
        // 4: Treble & Air (6000Hz - 16000Hz)
        float[] rawBands = new float[5];
        rawBands[0] = avgFreq(mags, binWidth, 20f, 250f, n);
        rawBands[1] = avgFreq(mags, binWidth, 250f, 600f, n);
        rawBands[2] = avgFreq(mags, binWidth, 600f, 2000f, n);
        rawBands[3] = avgFreq(mags, binWidth, 2000f, 6000f, n);
        rawBands[4] = avgFreq(mags, binWidth, 6000f, 16000f, n);

        float totalEnergy = rawBands[0] + rawBands[1] + rawBands[2] + rawBands[3] + rawBands[4];
        if (totalEnergy < 0.2f) {
            // Signal is effectively silent or muted by hardware output
            mSilenceCount++;
            mActiveCount = 0;

            // Decay to resting baseline 0.08f so bars don't freeze on silence
            boolean hasDecayed = false;
            for (int i = 0; i < 5; i++) {
                if (mBandSmooth[i] > 0.081f) {
                    mBandSmooth[i] = mBandSmooth[i] * 0.5f;
                    hasDecayed = true;
                } else {
                    mBandSmooth[i] = 0.08f;
                }
            }
            if (hasDecayed || mSilenceCount <= 3) {
                final float b0 = mBandSmooth[0];
                final float b1 = mBandSmooth[1];
                final float b2 = mBandSmooth[2];
                final float b3 = mBandSmooth[3];
                final float b4 = mBandSmooth[4];
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mListener != null) {
                            mListener.onFftBands(b0, b1, b2, b3, b4);
                        }
                    }
                });
            }

            if (mSilenceCount == 45) { // ~1.5s 持续静音
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (MainActivity.sInstance != null) {
                            MainActivity.sInstance.notifyAudioSilence();
                        }
                    }
                });
            }
            return;
        }

        mSilenceCount = 0;
        mActiveCount++;
        if (mActiveCount >= 3) {
            // 扬声器已持续输出真实音频（覆盖小红书、抖音等未推 MediaSession 状态的视频流）
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (MainActivity.sInstance != null) {
                        MainActivity.sInstance.notifyAudioEnergyDetected();
                    }
                }
            });
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

    private static float avgFreq(float[] mags, float binWidth, float startFreq, float endFreq, int n) {
        if (binWidth <= 0 || n <= 1) return 0f;
        int startBin = Math.max(1, Math.min(n - 1, Math.round(startFreq / binWidth)));
        int endBin = Math.max(1, Math.min(n - 1, Math.round(endFreq / binWidth)));
        if (startBin > endBin) endBin = startBin;
        return avg(mags, startBin, endBin);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
