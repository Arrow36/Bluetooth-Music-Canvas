package com.bluetoothmusiccanvas;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserve local fine adjustment only when AudioService attributes a repeated
 * phone volume command to Bluetooth. Unknown sources retain system behaviour. */
final class BluetoothVolumeSync {
    private static final String TAG = "CanvasVolume";
    private static final Pattern SET_MUSIC = Pattern.compile(
            "setStreamVolume\\(stream:STREAM_MUSIC index:(\\d+) flags:[^)]*\\) from (\\S+)");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("CanvasVolume");
    private final Handler worker;
    private final AudioManager audio;
    private final String packageName;
    private int revision;
    private String activeSince;
    private int knownPhone = -1;

    BluetoothVolumeSync(Context context) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        packageName = context.getPackageName();
        thread.start();
        worker = new Handler(thread.getLooper());
        reset();
    }

    private static String stamp(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss:SSS", Locale.US).format(new Date(millis));
    }

    void reset() {
        revision++;
        knownPhone = -1;
        activeSince = stamp(System.currentTimeMillis());
        worker.removeCallbacksAndMessages(null);
    }

    void close() {
        reset();
        thread.quitSafely();
    }

    void changed(int before, int after) {
        final int event = ++revision;
        worker.removeCallbacksAndMessages(null);
        if (audio == null || before < 0 || after < 0 || before == after) return;
        final long received = System.currentTimeMillis();
        final String since = activeSince;
        worker.post(() -> {
            History history = readHistory(since);
            if (history == null) return;
            main.post(() -> {
                if (event != revision) return;
                int prior = history.previousPhone >= 0 ? history.previousPhone : knownPhone;
                if (history.lastPhone >= 0) knownPhone = history.lastPhone;
                if (!history.lastIsPhone || history.lastPhone != after || prior != after
                        || !history.localAdjustment || !history.deliveryMatches(after, received)) return;
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != after
                        || (Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC))) return;
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0);
                Log.i(TAG, "Repeated Bluetooth volume " + after + ", restoring TV volume " + before);
            });
        });
    }

    private static final class History {
        int previousPhone = -1;
        int lastPhone = -1;
        boolean lastIsPhone;
        boolean localAdjustment;
        boolean localAfterPhone;
        String delivery = "";

        boolean deliveryMatches(int index, long received) {
            return delivery.length() >= 18 && delivery.contains("sending VOLUME_CHANGED stream:STREAM_MUSIC index:" + index + " ")
                    && delivery.substring(0, 18).compareTo(stamp(received - 1800)) >= 0;
        }
    }

    private History readHistory(String since) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("/system/bin/dumpsys", "-t", "2", "audio").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                History result = new History();
                boolean section = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Events log: volume changes")) { section = true; continue; }
                    if (!section) continue;
                    if (line.trim().isEmpty() || line.startsWith("Events log:")) break;
                    if (line.length() < 18) continue;
                    if (line.contains("sending VOLUME_CHANGED stream:STREAM_MUSIC ")) {
                        result.delivery = line;
                        continue;
                    }
                    Matcher set = SET_MUSIC.matcher(line);
                    if (set.find() && "com.android.bluetooth".equals(set.group(2))) {
                        result.previousPhone = result.lastPhone;
                        result.lastPhone = Integer.parseInt(set.group(1));
                        result.localAdjustment = result.localAfterPhone;
                        result.localAfterPhone = false;
                        result.lastIsPhone = true;
                        result.delivery = "";
                    } else if (line.contains("adjustSuggestedStreamVolume(") || line.contains("adjustStreamVolume(")
                            || line.contains("setStreamVolume(stream:STREAM_MUSIC")) {
                        result.lastIsPhone = false;
                        result.localAfterPhone = line.substring(0, 18).compareTo(since) >= 0
                                && (line.endsWith("from " + packageName)
                                || (line.contains("from android/WindowManager ") && (line.contains("dir:ADJUST_RAISE") || line.contains("dir:ADJUST_LOWER"))));
                    }
                }
                return section ? result : null;
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioService event history unavailable", e);
            return null;
        } finally {
            if (proc != null) proc.destroy();
        }
    }
}
