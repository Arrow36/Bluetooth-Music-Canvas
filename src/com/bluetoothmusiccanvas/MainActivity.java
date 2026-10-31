package com.bluetoothmusiccanvas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

public class MainActivity extends Activity {
    public static MainActivity sInstance;

    private WebView mWebView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPageLoaded = false;

    private static String sCachedTitle = "";
    private static String sCachedArtist = "";
    private static String sCachedAlbum = "";
    private static long sCachedDuration = 0;
    private static String sCachedStreamLyric = "";
    private static String sCachedCoverUrl = "";
    private static String sCachedLyrics = "";
    private static boolean sCachedIsPlaying = false;
    private static long sCachedPosition = 0;
    private static String sLastFetchedKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

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
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        mWebView.setBackgroundColor(0xFF0B0D10);
        mWebView.addJavascriptInterface(new WebAppInterface(this), "AndroidApp");
        mWebView.setWebChromeClient(new WebChromeClient());
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mPageLoaded = true;
                if (!sCachedTitle.isEmpty()) {
                    dispatchMetadata(sCachedTitle, sCachedArtist, sCachedAlbum, sCachedDuration, sCachedStreamLyric);
                    if (!sCachedCoverUrl.isEmpty() || !sCachedLyrics.isEmpty()) {
                        dispatchOnlineResult(sCachedCoverUrl, sCachedLyrics);
                    }
                    dispatchPlaybackState(sCachedIsPlaying, sCachedPosition, 1.0f);
                }
                if (MediaMonitorService.sInstance != null) {
                    MediaMonitorService.sInstance.syncToActivity(MainActivity.this);
                } else {
                    dispatchBluetoothStatus(MediaMonitorService.getBluetoothInfo(MainActivity.this));
                }
            }
        });

        mWebView.loadUrl("file:///android_asset/index.html");
        initEdgeCover();
    }

    private View mEdgeCoverView = null;
    private WindowManager mCoverWindowManager = null;

    private void initEdgeCover() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                return;
            }
            mEdgeCoverView = new View(this);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(0xFF0B0D10);
            gd.setCornerRadii(new float[]{0, 0, 16, 16, 16, 16, 0, 0});
            mEdgeCoverView.setBackground(gd);
            mEdgeCoverView.setAlpha(0.0f);

            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) (18 * getResources().getDisplayMetrics().density),
                    (int) (110 * getResources().getDisplayMetrics().density),
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            lp.x = 0;
            lp.y = 0;

            mCoverWindowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
            if (mCoverWindowManager != null) {
                mCoverWindowManager.addView(mEdgeCoverView, lp);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onIdleStateChanged(final boolean isIdle) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mEdgeCoverView != null) {
                    mEdgeCoverView.animate()
                            .alpha(isIdle ? 1.0f : 0.0f)
                            .setDuration(isIdle ? 800 : 200)
                            .start();
                }
            }
        });
    }

    public void onUserInteraction() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mEdgeCoverView != null && mEdgeCoverView.getAlpha() > 0.0f) {
                    mEdgeCoverView.animate().alpha(0.0f).setDuration(200).start();
                }
            }
        });
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

    @Override
    protected void onResume() {
        super.onResume();
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.hideFloatingView();
        }
        hideSystemUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (MediaMonitorService.sInstance != null) {
            MediaMonitorService.sInstance.onUserDismissed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sInstance = null;
        if (mWebView != null) {
            mWebView.destroy();
        }
        if (mEdgeCoverView != null && mCoverWindowManager != null) {
            try {
                mCoverWindowManager.removeView(mEdgeCoverView);
            } catch (Exception ignored) {}
            mEdgeCoverView = null;
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

    private void dispatchOnlineResult(final String coverUrl, final String lyricsLrc) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mWebView != null && mPageLoaded) {
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("coverUrl", coverUrl != null ? coverUrl : "");
                        obj.put("lyrics", lyricsLrc != null ? lyricsLrc : "");
                        mWebView.evaluateJavascript("window.onOnlineResult && window.onOnlineResult(" + obj.toString() + ");", null);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    public void dispatchMetadata(final String title, final String artist, final String album, final long duration, final String streamLyric) {
        sCachedTitle = title;
        sCachedArtist = artist;
        sCachedAlbum = album;
        sCachedDuration = duration;
        sCachedStreamLyric = streamLyric;

        final String key = (title + " - " + artist).toLowerCase().trim();
        MetadataFetcher.CachedMeta cached = MetadataFetcher.getCached(title, artist);
        if (cached != null && (cached.coverUrl != null || cached.lyricsLrc != null)) {
            sCachedCoverUrl = cached.coverUrl != null ? cached.coverUrl : "";
            sCachedLyrics = cached.lyricsLrc != null ? cached.lyricsLrc : "";
            dispatchOnlineResult(sCachedCoverUrl, sCachedLyrics);
        } else if (!key.isEmpty() && (!key.equals(sLastFetchedKey) || (sCachedCoverUrl.isEmpty() && sCachedLyrics.isEmpty()))) {
            sLastFetchedKey = key;
            MetadataFetcher.fetch(title, artist, new MetadataFetcher.Callback() {
                @Override
                public void onResult(final String coverUrl, final String lyricsLrc) {
                    sCachedCoverUrl = coverUrl != null ? coverUrl : "";
                    sCachedLyrics = lyricsLrc != null ? lyricsLrc : "";
                    dispatchOnlineResult(sCachedCoverUrl, sCachedLyrics);
                }
            });
        }

        if (!mPageLoaded || mWebView == null) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("title", title);
                    obj.put("artist", artist);
                    obj.put("album", album);
                    obj.put("duration", duration);
                    obj.put("streamLyric", streamLyric != null ? streamLyric : "");
                    mWebView.evaluateJavascript("window.onMetadataChanged && window.onMetadataChanged(" + obj.toString() + ");", null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void dispatchPlaybackState(final boolean isPlaying, final long position, final float speed) {
        sCachedIsPlaying = isPlaying;
        sCachedPosition = position;

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
