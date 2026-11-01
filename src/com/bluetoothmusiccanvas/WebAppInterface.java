package com.bluetoothmusiccanvas;

import android.webkit.JavascriptInterface;

public class WebAppInterface {
    private final MainActivity mActivity;

    public WebAppInterface(MainActivity activity) {
        this.mActivity = activity;
    }

    @JavascriptInterface
    public void togglePlayPause() {
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.sendPlayPause();
        }
    }

    @JavascriptInterface
    public void skipNext() {
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.sendNext();
        }
    }

    @JavascriptInterface
    public void skipPrevious() {
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.sendPrevious();
        }
    }

    @JavascriptInterface
    public int getVolume() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) mActivity.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (max > 0) {
                    return Math.round((cur * 100.0f) / max);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 50;
    }

    @JavascriptInterface
    public int setVolumePercent(int percent) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) mActivity.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int targetVol = Math.round((percent / 100.0f) * max);
                targetVol = Math.max(0, Math.min(max, targetVol));
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0);
                return Math.round((targetVol * 100.0f) / max);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return percent;
    }

    @JavascriptInterface
    public int changeVolumeBy(int deltaPercent) {
        return mActivity.changeVolumeBy(deltaPercent);
    }

    @JavascriptInterface
    public void restartApp() {
        mActivity.restartApp();
    }

    @JavascriptInterface
    public void onIdleStateChanged(boolean isIdle) {
        mActivity.onIdleStateChanged(isIdle);
    }

    @JavascriptInterface
    public void onUserInteraction() {
        mActivity.onUserInteraction();
    }

    @JavascriptInterface
    public void requestLyricSource(final String source, final String title, final String artist, final String album) {
        requestLyricSource(source, title, artist, album, -1);
    }

    @JavascriptInterface
    public void requestLyricSource(final String source, final String title, final String artist, final String album, final long clientGeneration) {
        if ("bluetooth".equalsIgnoreCase(source) || "auto".equalsIgnoreCase(source)) {
            return;
        }
        final long generation = (clientGeneration > 0) ? clientGeneration : mActivity.getCurrentPlaybackGeneration();
        final long reqId = mActivity.nextRequestId();
        final String trackKey = SongIdentity.buildCacheKey(title, artist, album);
        MetadataFetcher.fetchSpecificLyric(source, title, artist, album, new MetadataFetcher.Callback() {
            @Override
            public void onResult(String coverUrl, final String lyricsLrc, final String lyricSource) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mActivity.dispatchSpecificLyricResult(source, lyricsLrc, lyricSource, generation, trackKey, reqId);
                    }
                });
            }
        });
    }

    @JavascriptInterface
    public void exitToHome() {
        android.util.Log.i("WebAppInterface", "exitToHome triggered from JS");
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                home.addCategory(android.content.Intent.CATEGORY_HOME);
                home.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                mActivity.startActivity(home);
                mActivity.moveTaskToBack(true);
            }
        });
    }
}
