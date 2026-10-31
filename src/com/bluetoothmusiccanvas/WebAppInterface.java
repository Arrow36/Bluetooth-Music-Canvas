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
    public void exitToHome() {
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mActivity.finish();
            }
        });
    }
}
