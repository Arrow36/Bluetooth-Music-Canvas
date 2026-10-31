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
