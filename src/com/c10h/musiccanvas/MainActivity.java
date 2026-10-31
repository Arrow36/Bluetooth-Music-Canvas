package com.c10h.musiccanvas;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private String mCachedTitle = "";
    private String mCachedArtist = "";
    private String mCachedAlbum = "";
    private long mCachedDuration = 0;
    private String mCachedStreamLyric = "";
    private boolean mCachedIsPlaying = false;
    private long mCachedPosition = 0;

    private String mLastFetchedKey = "";

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
                if (MediaMonitorService.sInstance != null) {
                    MediaMonitorService.sInstance.syncToActivity(MainActivity.this);
                } else if (!mCachedTitle.isEmpty()) {
                    dispatchMetadata(mCachedTitle, mCachedArtist, mCachedAlbum, mCachedDuration, mCachedStreamLyric);
                    dispatchPlaybackState(mCachedIsPlaying, mCachedPosition, 1.0f);
                }
            }
        });

        mWebView.loadUrl("file:///android_asset/index.html");
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

    public void dispatchMetadata(final String title, final String artist, final String album, final long duration, final String streamLyric) {
        mCachedTitle = title;
        mCachedArtist = artist;
        mCachedAlbum = album;
        mCachedDuration = duration;
        mCachedStreamLyric = streamLyric;

        final String key = (title + " - " + artist).toLowerCase().trim();
        if (!key.isEmpty() && !key.equals(mLastFetchedKey)) {
            mLastFetchedKey = key;
            MetadataFetcher.fetch(title, artist, new MetadataFetcher.Callback() {
                @Override
                public void onResult(final String coverUrl, final String lyricsLrc) {
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
        mCachedIsPlaying = isPlaying;
        mCachedPosition = position;

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
}
