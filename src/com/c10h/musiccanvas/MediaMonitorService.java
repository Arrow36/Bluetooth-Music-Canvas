package com.c10h.musiccanvas;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaMonitorService extends NotificationListenerService {
    private static final String TAG = "C10H_MusicService";
    public static MediaMonitorService sInstance;

    private MediaSessionManager mSessionManager;
    private MediaController mActiveController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsPlaying = false;
    private PowerManager.WakeLock mWakeLock;

    // WindowManager for Mini Floating Vinyl Icon
    private WindowManager mWindowManager;
    private View mFloatingView;
    private boolean mIsFloatingShown = false;
    private boolean mUserDismissed = false; // Set to true when user clicks "返回桌面"

    // Metadata caching
    private String mRealTitle = "";
    private String mRealArtist = "";
    private String mRealAlbum = "";
    private String mStreamLyric = "";
    private long mDuration = 0;
    private long mCurrentPosition = 0;

    // Pattern for "车载蓝牙歌词" e.g. Artist: "王菲/如愿 (电影《我和我的父辈》主题推广曲)"
    private static final Pattern BT_LYRIC_PATTERN = Pattern.compile("^([^/]+)/([^\\(（]+)");

    private final Runnable mAutoCloseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsPlaying) {
                Log.i(TAG, "Music paused for > 10s, auto-dismissing");
                hideFloatingView();
                if (MainActivity.sInstance != null) {
                    MainActivity.sInstance.finish();
                }
            }
        }
    };

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            handlePlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            handleMetadata(metadata);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    inspectControllers(controllers);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "C10H:MusicWakeLock");
        }
        Log.i(TAG, "MediaMonitorService created");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "NotificationListenerService connected, registering MediaSessionManager");
        mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mSessionManager != null) {
            ComponentName cn = new ComponentName(this, MediaMonitorService.class);
            try {
                mSessionManager.addOnActiveSessionsChangedListener(mSessionListener, cn);
                inspectControllers(mSessionManager.getActiveSessions(cn));
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for getActiveSessions: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        hideFloatingView();
        if (mSessionManager != null) {
            mSessionManager.removeOnActiveSessionsChangedListener(mSessionListener);
        }
        if (mActiveController != null) {
            mActiveController.unregisterCallback(mControllerCallback);
        }
    }

    private void inspectControllers(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) return;
        MediaController preferred = null;
        for (MediaController c : controllers) {
            if ("com.android.bluetooth".equals(c.getPackageName())) {
                preferred = c;
                break;
            }
        }
        if (preferred == null) preferred = controllers.get(0);

        if (mActiveController != preferred) {
            if (mActiveController != null) {
                mActiveController.unregisterCallback(mControllerCallback);
            }
            mActiveController = preferred;
            mActiveController.registerCallback(mControllerCallback);
            Log.i(TAG, "Bound to controller: " + mActiveController.getPackageName());
            handlePlaybackState(mActiveController.getPlaybackState());
            handleMetadata(mActiveController.getMetadata());
        }
    }

    private void handlePlaybackState(PlaybackState state) {
        if (state == null) return;
        int playbackState = state.getState();
        long position = state.getPosition();
        float speed = state.getPlaybackSpeed();
        mCurrentPosition = position;

        if (playbackState == PlaybackState.STATE_PLAYING) {
            mIsPlaying = true;
            mHandler.removeCallbacks(mAutoCloseRunnable);

            // If user previously clicked "返回桌面", DO NOT force popup! Keep floating icon!
            if (mUserDismissed) {
                showFloatingView();
                return;
            }

            // Otherwise, wake screen and launch full screen canvas
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire(3000);
            }

            if (MainActivity.sInstance == null) {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            }

            if (MainActivity.sInstance != null) {
                MainActivity.sInstance.dispatchPlaybackState(true, position, speed);
            }
            hideFloatingView();

        } else if (playbackState == PlaybackState.STATE_PAUSED || playbackState == PlaybackState.STATE_STOPPED) {
            mIsPlaying = false;
            if (MainActivity.sInstance != null) {
                MainActivity.sInstance.dispatchPlaybackState(false, position, speed);
            }
            // Auto close after 10s of inactivity
            mHandler.removeCallbacks(mAutoCloseRunnable);
            mHandler.postDelayed(mAutoCloseRunnable, 10000);
        }
    }

    private void handleMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        if (rawTitle == null || "Not Provided".equalsIgnoreCase(rawTitle.trim())) {
            return;
        }

        rawTitle = rawTitle.trim();
        rawArtist = rawArtist != null ? rawArtist.trim() : "";
        album = album != null ? album.trim() : "";

        // Smart detection for "车载蓝牙歌词":
        // e.g. rawArtist="王菲/如愿 (电影《我和我的父辈》主题推广曲)", rawTitle="我愿活成你的愿"
        // e.g. rawArtist="Disney/Shakira/Zoo (From \"Zootopia 2\"/Soundtrack Version)", rawTitle="In a place where anyone..."
        String cleanArtist = rawArtist.replaceAll("\\s*[\\(（【\\[].*$", "").trim();
        int lastSlash = cleanArtist.lastIndexOf('/');
        if (lastSlash > 0) {
            mRealArtist = cleanArtist.substring(0, lastSlash).trim();
            mRealTitle = cleanArtist.substring(lastSlash + 1).trim();
            mStreamLyric = rawTitle; // The rawTitle is the real-time lyric line!
        } else {
            mRealTitle = rawTitle;
            mRealArtist = rawArtist;
            mStreamLyric = "";
        }
        mRealAlbum = album;
        mDuration = duration;

        Log.i(TAG, "Parsed track: [" + mRealTitle + "] by [" + mRealArtist + "], lyric=[" + mStreamLyric + "]");

        if (MainActivity.sInstance != null) {
            MainActivity.sInstance.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric);
        }
    }

    public void syncToActivity(MainActivity activity) {
        if (!mRealTitle.isEmpty()) {
            Log.i(TAG, "Syncing current track to MainActivity: " + mRealTitle);
            activity.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric);
            activity.dispatchPlaybackState(mIsPlaying, mCurrentPosition, 1.0f);
        }
    }

    // Called when user clicks "返回桌面" or minimizes
    public void onUserDismissed() {
        mUserDismissed = true;
        if (mIsPlaying) {
            showFloatingView();
        }
    }

    // Called when user clicks the floating icon to restore full screen
    public void restoreFullScreen() {
        mUserDismissed = false;
        hideFloatingView();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void showFloatingView() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mIsFloatingShown || mWindowManager == null) return;
                try {
                    int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;

                    int size = (int) (64 * getResources().getDisplayMetrics().density);
                    final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            size,
                            size,
                            layoutType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                    );

                    params.gravity = Gravity.TOP | Gravity.START;
                    params.x = 40;
                    params.y = 120;

                    mFloatingView = LayoutInflater.from(MediaMonitorService.this).inflate(R.layout.floating_vinyl, null);

                    ImageView vinylIcon = mFloatingView.findViewById(R.id.floatingVinylIcon);
                    RotateAnimation rotate = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    rotate.setDuration(8000);
                    rotate.setRepeatCount(Animation.INFINITE);
                    rotate.setInterpolator(new LinearInterpolator());
                    vinylIcon.startAnimation(rotate);

                    mFloatingView.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX, initialY;
                        private float initialTouchX, initialTouchY;
                        private boolean isMove = false;

                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = params.x;
                                    initialY = params.y;
                                    initialTouchX = event.getRawX();
                                    initialTouchY = event.getRawY();
                                    isMove = false;
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    int dx = (int) (event.getRawX() - initialTouchX);
                                    int dy = (int) (event.getRawY() - initialTouchY);
                                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                                        isMove = true;
                                        params.x = initialX + dx;
                                        params.y = initialY + dy;
                                        mWindowManager.updateViewLayout(mFloatingView, params);
                                    }
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    if (!isMove) {
                                        restoreFullScreen();
                                    }
                                    return true;
                            }
                            return false;
                        }
                    });

                    mWindowManager.addView(mFloatingView, params);
                    mIsFloatingShown = true;
                    Log.i(TAG, "Floating mini vinyl icon shown");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show floating window: " + e.getMessage());
                }
            }
        });
    }

    public void hideFloatingView() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mIsFloatingShown && mFloatingView != null && mWindowManager != null) {
                    try {
                        mWindowManager.removeView(mFloatingView);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    mFloatingView = null;
                    mIsFloatingShown = false;
                    Log.i(TAG, "Floating mini vinyl icon removed");
                }
            }
        });
    }

    public void sendPlayPause() {
        if (mActiveController != null && mActiveController.getTransportControls() != null) {
            if (mIsPlaying) {
                mActiveController.getTransportControls().pause();
            } else {
                mActiveController.getTransportControls().play();
            }
        }
    }

    public void sendNext() {
        if (mActiveController != null && mActiveController.getTransportControls() != null) {
            mActiveController.getTransportControls().skipToNext();
        }
    }

    public void sendPrevious() {
        if (mActiveController != null && mActiveController.getTransportControls() != null) {
            mActiveController.getTransportControls().skipToPrevious();
        }
    }
}
