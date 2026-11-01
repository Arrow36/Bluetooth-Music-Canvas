package com.bluetoothmusiccanvas;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

public class MainActivity extends Activity {
    public static final String TAG = "MainActivity";
    public static MainActivity sInstance;
    private static java.lang.ref.WeakReference<MainActivity> sInstanceRef = new java.lang.ref.WeakReference<>(null);

    public static MainActivity getInstance() {
        MainActivity act = sInstanceRef.get();
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            return act;
        }
        return null;
    }

    private WebView mWebView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPageLoaded = false;
    private AudioVisualizerHelper mVisualizerHelper;

    public static String sCachedTitle = "";
    public static String sCachedArtist = "";
    public static String sCachedAlbum = "";
    public static long sCachedDuration = 0;
    public static String sCachedStreamLyric = "";
    public static String sCachedCoverUrl = "";
    public static String sLastValidMusicCoverUrl = "";
    public static boolean sIsAmbientAudioMode = false;
    public static String sCachedLyrics = "";
    public static String sCachedLyricSource = "";
    public static boolean sCachedIsPlaying = false;
    public static long sCachedPosition = 0;
    public static String sLastFetchedKey = "";

    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dispatchVolumeChanged();
        }
    };

    private ContentObserver mVolumeObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        sInstanceRef = new java.lang.ref.WeakReference<>(this);

        // Dismiss floating mini view if active
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.hideFloatingView();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        setContentView(R.layout.activity_main);
        mWebView = findViewById(R.id.webview);

        // Completely disable all scrollbars and overscroll bounces
        mWebView.setHorizontalScrollBarEnabled(false);
        mWebView.setVerticalScrollBarEnabled(false);
        mWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mWebView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        mWebView.setBackgroundColor(0xFF0B0D10);
        mWebView.addJavascriptInterface(new WebAppInterface(this), "AndroidApp");
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d("WebConsole", cm.message() + " [" + cm.lineNumber() + "]");
                return true;
            }
        });
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mPageLoaded = true;
                if (!sCachedTitle.isEmpty()) {
                    dispatchMetadata(sCachedTitle, sCachedArtist, sCachedAlbum, sCachedDuration, sCachedStreamLyric);
                    if (!sCachedCoverUrl.isEmpty() || !sCachedLyrics.isEmpty()) {
                        dispatchOnlineResult(sCachedCoverUrl, sCachedLyrics, sCachedLyricSource);
                    }
                    dispatchPlaybackState(sCachedIsPlaying, sCachedPosition, 1.0f);
                }
                if (MediaMonitorService.sInstance != null) {
                    MediaMonitorService.sInstance.syncToActivity(MainActivity.this);
                } else {
                    dispatchBluetoothStatus(MediaMonitorService.getBluetoothInfo(MainActivity.this));
                }
                dispatchVolumeChanged();
                initVisualizer();
            }
        });

        // Register volume listeners to sync phone physical volume presses
        try {
            IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            registerReceiver(mVolumeReceiver, vf);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            mVolumeObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    dispatchVolumeChanged();
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI, true, mVolumeObserver);
        } catch (Exception e) {
            e.printStackTrace();
        }

        mWebView.loadUrl("file:///android_asset/index.html");
    }

    public void dispatchVolumeChanged() {
        if (!mPageLoaded || mWebView == null) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                final int pct = max > 0 ? Math.round((cur * 100.0f) / max) : cur;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null && mPageLoaded) {
                            mWebView.evaluateJavascript("window.onSystemVolumeChanged && window.onSystemVolumeChanged(" + pct + ");", null);
                        }
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int changeVolumeBy(int deltaPercent) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int step = Math.round((deltaPercent / 100.0f) * max);
                if (step == 0 && deltaPercent > 0) step = 1;
                if (step == 0 && deltaPercent < 0) step = -1;
                int target = Math.max(0, Math.min(max, cur + step));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                return max > 0 ? Math.round((target * 100.0f) / max) : target;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 50;
    }

    public void restartApp() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = getApplicationContext();
                    Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        PendingIntent restartIntent = PendingIntent.getActivity(
                                ctx, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
                        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                        if (am != null) {
                            am.set(AlarmManager.RTC, System.currentTimeMillis() + 400, restartIntent);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                finishAffinity();
                System.exit(0);
            }
        });
    }

    public void onIdleStateChanged(final boolean isIdle) {
        // App-level idle callback hook
    }

    public void onUserInteraction() {
        // App-level user interaction callback hook
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.hideFloatingView();
            MediaMonitorService.sInstance.syncToActivity(this);
        }
        hideSystemUI();
    }

    public void initVisualizer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 101);
                    return;
                }
            }
            if (mVisualizerHelper == null) {
                mVisualizerHelper = new AudioVisualizerHelper(new AudioVisualizerHelper.OnWaveDataListener() {
                    @Override
                    public void onFftBands(final float b0, final float b1, final float b2, final float b3, final float b4,
                                           final float b5, final float b6, final float b7, final float b8) {
                        if (mWebView != null && mPageLoaded) {
                            mWebView.evaluateJavascript("window.onAudioSpectrum && window.onAudioSpectrum("
                                    + b0 + "," + b1 + "," + b2 + "," + b3 + "," + b4 + ","
                                    + b5 + "," + b6 + "," + b7 + "," + b8 + ");", null);
                        }
                    }
                });
            }
            mVisualizerHelper.start();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            initVisualizer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.hideFloatingView();
        }
        if (mVisualizerHelper != null) {
            mVisualizerHelper.start();
        }
        hideSystemUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVisualizerHelper != null) {
            mVisualizerHelper.stop();
        }
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.onUserDismissed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        if (sInstance == this) {
            sInstance = null;
        }
        sInstanceRef.clear();
        if (mVisualizerHelper != null) {
            mVisualizerHelper.stop();
            mVisualizerHelper = null;
        }
        try {
            unregisterReceiver(mVolumeReceiver);
        } catch (Exception ignored) {}
        if (mVolumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(mVolumeObserver);
            } catch (Exception ignored) {}
            mVolumeObserver = null;
        }
        if (mWebView != null) {
            mWebView.destroy();
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private long mCurrentPlaybackGeneration = 0;
    private final java.util.concurrent.atomic.AtomicLong mRequestIdCounter = new java.util.concurrent.atomic.AtomicLong(0);

    public long getCurrentPlaybackGeneration() {
        return mCurrentPlaybackGeneration;
    }

    public long nextRequestId() {
        return mRequestIdCounter.incrementAndGet();
    }

    public void dispatchSpecificLyricResult(final String source, final String lyricsLrc, final String lyricSource) {
        dispatchSpecificLyricResult(source, lyricsLrc, lyricSource, mCurrentPlaybackGeneration,
                MetadataFetcher.buildCacheKey(sCachedTitle, sCachedArtist, sCachedAlbum), 0);
    }

    public void dispatchSpecificLyricResult(final String source, final String lyricsLrc, final String lyricSource,
                                           final long generation, final String trackKey, final long sourceRequestId) {
        if (!mPageLoaded || mWebView == null) return;
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("source", source);
                    obj.put("lyrics", lyricsLrc != null ? lyricsLrc : "");
                    obj.put("lyricSource", lyricSource != null ? lyricSource : "");
                    obj.put("generation", generation);
                    obj.put("trackKey", trackKey != null ? trackKey : "");
                    obj.put("sourceRequestId", sourceRequestId);
                    mWebView.evaluateJavascript("window.onSpecificLyricResult && window.onSpecificLyricResult(" + obj.toString() + ");", null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void notifyAudioEnergyDetected() {
        MediaStateStore.MediaSnapshot snap = MediaStateStore.getInstance().getSnapshot();
        // If an active Bluetooth song is already loaded, do NOT allow TV ambient audio to hijack/overwrite it
        if (snap != null && snap.hasTrack()) {
            return;
        }
        if (!sCachedIsPlaying) {
            // 蓝牙会话处于暂停或停止且无有效曲目，TV 扬声器检测到实际音频输出（小红书/短视频/外部流）
            sIsAmbientAudioMode = true;
            sCachedIsPlaying = true;
            dispatchPlaybackState(true, 0, 1.0f);

            if (sCachedTitle == null || !sCachedTitle.equals("蓝牙媒体音频")) {
                dispatchMetadata("蓝牙媒体音频", "视频 / 外部音频", "", 0, "", 0, "", false);
                dispatchOnlineResult(sLastValidMusicCoverUrl, "", "视频模式");
            }
        }
    }

    public void notifyAudioSilence() {
        if (sIsAmbientAudioMode && sCachedIsPlaying) {
            sIsAmbientAudioMode = false;
            dispatchPlaybackState(false, 0, 0f);
        }
    }

    public void dispatchMetadata(final String title, final String artist, final String album, final long duration, final String streamLyric) {
        MediaStateStore.MediaSnapshot snap = MediaStateStore.getInstance().getSnapshot();
        long gen = (snap != null && snap.playbackGeneration > 0) ? snap.playbackGeneration : mCurrentPlaybackGeneration;
        String trackKey = (snap != null && !snap.songCacheKey.isEmpty()) ? snap.songCacheKey : SongIdentity.buildCacheKey(title, artist, album);
        dispatchMetadata(title, artist, album, duration, streamLyric, gen, trackKey, false);
    }

    public void dispatchMetadata(final String title, final String artist, final String album,
                                final long duration, final String streamLyric,
                                final long generation, final String trackKey, final boolean isEnrichment) {
        if (!"蓝牙媒体音频".equals(title)) {
            sIsAmbientAudioMode = false;
        }
        mCurrentPlaybackGeneration = generation;

        sCachedTitle = title;
        sCachedArtist = artist;
        sCachedAlbum = album;
        sCachedDuration = duration;
        sCachedStreamLyric = streamLyric;

        MediaStateStore.MediaSnapshot snap = MediaStateStore.getInstance().getSnapshot();
        final boolean isVideo = (snap != null && snap.isVideo())
                || MediaClassifier.classify(title, artist, album, duration, MetadataNormalizer.MetadataFormat.UNKNOWN).isVideo();
        final String contentKind = (snap != null && snap.classification != null) ? snap.classification.kind.name() : (isVideo ? "VIDEO" : "MUSIC");
        final String evidenceLevel = (snap != null && snap.classification != null) ? snap.classification.evidenceLevel.name() : "HEURISTIC";
        final String reasonCode = (snap != null && snap.classification != null) ? snap.classification.reasonCode : "";

        if (!mPageLoaded || mWebView == null) return;
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;

        // 1. 先向前端发布元数据快照，建立当前播放代次与身份
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("title", title);
                    obj.put("artist", artist);
                    obj.put("album", album);
                    obj.put("duration", duration);
                    obj.put("streamLyric", streamLyric != null ? streamLyric : "");
                    obj.put("isVideo", isVideo);
                    obj.put("contentKind", contentKind);
                    obj.put("evidenceLevel", evidenceLevel);
                    obj.put("reasonCode", reasonCode);
                    obj.put("generation", generation);
                    obj.put("trackKey", trackKey);
                    obj.put("isEnrichment", isEnrichment);
                    mWebView.evaluateJavascript("window.onMetadataChanged && window.onMetadataChanged(" + obj.toString() + ");", null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // 2. 如果是同一首歌的元数据增量补全，不重新触发网络检索
        if (isEnrichment) {
            return;
        }

        // 2. 再分发或检索封面与歌词 (在 mHandler 串行队列中排在 onMetadataChanged 之后)
        if (isVideo) {
            sCachedCoverUrl = sLastValidMusicCoverUrl;
            sCachedLyrics = "";
            sCachedLyricSource = "视频模式";
            dispatchOnlineResult(sCachedCoverUrl, "", "视频模式", generation, trackKey, 0, "video");
        } else {
            MetadataFetcher.CachedMeta cached = MetadataFetcher.getCached(title, artist, album);
            if (cached != null && (cached.coverUrl != null || cached.lyricsLrc != null)) {
                sCachedCoverUrl = cached.coverUrl != null ? cached.coverUrl : "";
                if (sCachedCoverUrl != null && !sCachedCoverUrl.isEmpty()) {
                    sLastValidMusicCoverUrl = sCachedCoverUrl;
                }
                sCachedLyrics = cached.lyricsLrc != null ? cached.lyricsLrc : "";
                sCachedLyricSource = cached.lyricSource != null ? cached.lyricSource : "";
                dispatchOnlineResult(sCachedCoverUrl, sCachedLyrics, sCachedLyricSource, generation, trackKey, 0, "auto");
            } else if (!trackKey.isEmpty() && (!trackKey.equals(sLastFetchedKey) || (sCachedCoverUrl.isEmpty() && sCachedLyrics.isEmpty()))) {
                sLastFetchedKey = trackKey;
                final long reqId = mRequestIdCounter.incrementAndGet();
                MetadataFetcher.fetch(title, artist, album, duration, new MetadataFetcher.Callback() {
                    @Override
                    public void onResult(final String coverUrl, final String lyricsLrc, final String lyricSource) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                                if (generation != mCurrentPlaybackGeneration) {
                                    Log.d(TAG, "Discarded stale fetch callback for gen " + generation + " (current is " + mCurrentPlaybackGeneration + ")");
                                    return;
                                }
                                sCachedCoverUrl = coverUrl != null ? coverUrl : "";
                                if (sCachedCoverUrl != null && !sCachedCoverUrl.isEmpty()) {
                                    sLastValidMusicCoverUrl = sCachedCoverUrl;
                                }
                                sCachedLyrics = lyricsLrc != null ? lyricsLrc : "";
                                sCachedLyricSource = lyricSource != null ? lyricSource : "";
                                dispatchOnlineResult(sCachedCoverUrl, sCachedLyrics, sCachedLyricSource, generation, trackKey, reqId, "auto");
                            }
                        });
                    }
                });
            }
        }
    }

    public void dispatchOnlineResult(final String coverUrl, final String lyrics, final String lyricSource,
                                     final long generation, final String trackKey, final long sourceRequestId, final String source) {
        if (!mPageLoaded || mWebView == null) return;
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("coverUrl", coverUrl != null ? coverUrl : "");
                    obj.put("lyrics", lyrics != null ? lyrics : "");
                    obj.put("lyricSource", lyricSource != null ? lyricSource : "");
                    obj.put("generation", generation);
                    obj.put("trackKey", trackKey != null ? trackKey : "");
                    obj.put("sourceRequestId", sourceRequestId);
                    obj.put("source", source != null ? source : "auto");
                    mWebView.evaluateJavascript("window.onOnlineResult && window.onOnlineResult(" + obj.toString() + ");", null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void dispatchOnlineResult(final String coverUrl, final String lyrics, final String lyricSource) {
        dispatchOnlineResult(coverUrl, lyrics, lyricSource, mCurrentPlaybackGeneration,
                MetadataFetcher.buildCacheKey(sCachedTitle, sCachedArtist, sCachedAlbum), 0, "auto");
    }

    public void dispatchPlaybackState(final boolean isPlaying, final long position, final float speed) {
        boolean stateChanged = (sCachedIsPlaying != isPlaying);
        sCachedIsPlaying = isPlaying;
        sCachedPosition = position;

        if (mVisualizerHelper != null && isPlaying) {
            mVisualizerHelper.start();
        }

        if (!mPageLoaded || mWebView == null) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("isPlaying", isPlaying);
                    obj.put("position", position);
                    obj.put("speed", speed);
                    mWebView.evaluateJavascript("window.onPlaybackStateChanged && window.onPlaybackStateChanged(" + obj.toString() + ");", null);
                    if (!isPlaying) {
                        mWebView.evaluateJavascript("window.onAudioSpectrum && window.onAudioSpectrum(0, 0, 0, 0, 0);", null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void dispatchBluetoothStatus(final JSONObject obj) {
        if (!mPageLoaded || mWebView == null || obj == null) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mWebView.evaluateJavascript("window.onBluetoothStatusChanged && window.onBluetoothStatusChanged(" + obj.toString() + ");", null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
