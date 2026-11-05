package com.bluetoothmusiccanvas;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.media.AudioManager;
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
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class MediaMonitorService extends NotificationListenerService {
    private static final String TAG = "BTMusicService";
    public static MediaMonitorService sInstance;

    private MediaSessionManager mSessionManager;
    private MediaController mActiveController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsPlaying = false;
    private PowerManager.WakeLock mWakeLock;

    // WindowManager for Mini Floating Vinyl Capsule
    private WindowManager mWindowManager;
    private View mFloatingView;
    private boolean mIsFloatingShown = false;
    private boolean mUserDismissed = false; // Set to true when user clicks "返回桌面"

    // Persistent drag coordinates
    private SharedPreferences mPrefs;
    private int mFloatingX = 40;
    private int mFloatingY = 120;

    // Album cover caching & dynamic vinyl disc (decoded lazily only when floating view is shown)
    private Bitmap mCurrentCoverBitmap = null;
    private Bitmap mGeneratedVinylBitmap = null;
    private String mCurrentCoverUrl = "";
    private String mLoadedBitmapUrl = "";
    private String mLastFetchedCoverKey = "";

    // Metadata caching
    private String mRealTitle = "";
    private String mRealArtist = "";
    private String mRealAlbum = "";
    private String mStreamLyric = "";
    private long mDuration = 0;
    private long mCurrentPosition = 0;
    private long mPlaybackGeneration = 0;

    // Real Bluetooth & Audio hardware profile proxies (A2DP_SINK = 11, A2DP = 2)
    private static final int PROFILE_A2DP_SINK = 11;
    private static volatile android.bluetooth.BluetoothProfile sA2dpSinkProxy = null;
    private static volatile android.bluetooth.BluetoothProfile sA2dpSourceProxy = null;
    private static volatile Object sCachedSinkAudioConfig = null;
    private static volatile Object sCachedCodecConfig = null;
    private static volatile String sCachedNegotiatedCodec = "";
    private static volatile String sCachedNegotiatedPeer = "";
    private static volatile long sLastCodecProbeTimeMs = 0;

    private final android.bluetooth.BluetoothProfile.ServiceListener mProfileListener =
            new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                    Log.i(TAG, "BluetoothProfile connected: profile=" + profile + " proxy=" + proxy);
                    if (profile == PROFILE_A2DP_SINK) {
                        sA2dpSinkProxy = proxy;
                    } else if (profile == android.bluetooth.BluetoothProfile.A2DP) {
                        sA2dpSourceProxy = proxy;
                    }
                    MainActivity act = MainActivity.getInstance();
                    if (act != null) {
                        act.dispatchBluetoothStatus(getBluetoothInfo(MediaMonitorService.this));
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == PROFILE_A2DP_SINK) {
                        sA2dpSinkProxy = null;
                    } else if (profile == android.bluetooth.BluetoothProfile.A2DP) {
                        sA2dpSourceProxy = null;
                    }
                }
            };

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action)
                    || "android.bluetooth.device.action.ACL_DISCONNECTED".equals(action)
                    || "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                    || "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                    || "android.bluetooth.a2dp-sink.profile.action.AUDIO_CONFIG_CHANGED".equals(action)
                    || "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED".equals(action)
                    || "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED".equals(action)
                    || "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED".equals(action)) {
                Log.i(TAG, "Bluetooth state/audio config changed (" + action + ")");
                if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action)) {
                    mUserDismissed = false;
                }
                if ("android.bluetooth.device.action.ACL_DISCONNECTED".equals(action)) {
                    sCachedSinkAudioConfig = null;
                    sCachedCodecConfig = null;
                    sCachedNegotiatedCodec = "";
                    sCachedNegotiatedPeer = "";
                }
                if ("android.bluetooth.a2dp-sink.profile.action.AUDIO_CONFIG_CHANGED".equals(action)) {
                    Object cfg = intent.getParcelableExtra("android.bluetooth.a2dp-sink.profile.extra.AUDIO_CONFIG");
                    if (cfg != null) {
                        sCachedSinkAudioConfig = cfg;
                    }
                }
                if ("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED".equals(action)) {
                    Object status = intent.getParcelableExtra("android.bluetooth.codec.extra.CODEC_STATUS");
                    if (status != null) {
                        try {
                            sCachedCodecConfig = status.getClass().getMethod("getCodecConfig").invoke(status);
                        } catch (Exception ignored) {}
                    }
                }
                MainActivity act = MainActivity.getInstance();
                if (act != null) {
                    act.dispatchBluetoothStatus(getBluetoothInfo(context));
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

        @Override
        public void onSessionDestroyed() {
            Log.i(TAG, "MediaSession destroyed");
            MediaStateStore.getInstance().startNewSession("destroyed");
            handlePlaybackState(null);
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
        mPrefs = getSharedPreferences("floating_widget_prefs", Context.MODE_PRIVATE);
        mFloatingX = mPrefs.getInt("pos_x", 40);
        mFloatingY = mPrefs.getInt("pos_y", 120);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "BluetoothMusicCanvas:WakeLock");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        filter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.AUDIO_CONFIG_CHANGED");
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED");
        filter.addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED");
        registerReceiver(mBluetoothReceiver, filter);

        try {
            android.bluetooth.BluetoothAdapter btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (btAdapter != null) {
                btAdapter.getProfileProxy(this, mProfileListener, PROFILE_A2DP_SINK);
                btAdapter.getProfileProxy(this, mProfileListener, android.bluetooth.BluetoothProfile.A2DP);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to bind Bluetooth profile proxies: " + t.getMessage());
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
        try {
            unregisterReceiver(mBluetoothReceiver);
        } catch (Exception ignored) {}
        try {
            android.bluetooth.BluetoothAdapter btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (btAdapter != null) {
                if (sA2dpSinkProxy != null) {
                    btAdapter.closeProfileProxy(PROFILE_A2DP_SINK, sA2dpSinkProxy);
                    sA2dpSinkProxy = null;
                }
                if (sA2dpSourceProxy != null) {
                    btAdapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, sA2dpSourceProxy);
                    sA2dpSourceProxy = null;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void inspectControllers(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            if (mActiveController != null) {
                try {
                    mActiveController.unregisterCallback(mControllerCallback);
                } catch (Exception ignored) {}
                mActiveController = null;
            }
            MediaStateStore.getInstance().startNewSession("empty");
            handlePlaybackState(null);
            return;
        }

        MediaController preferred = null;
        for (MediaController c : controllers) {
            if ("com.android.bluetooth".equals(c.getPackageName())) {
                preferred = c;
                break;
            }
        }
        if (preferred == null) preferred = controllers.get(0);

        boolean tokenChanged = (mActiveController == null)
                || !mActiveController.getSessionToken().equals(preferred.getSessionToken());

        if (tokenChanged) {
            if (mActiveController != null) {
                try {
                    mActiveController.unregisterCallback(mControllerCallback);
                } catch (Exception ignored) {}
            }
            mActiveController = preferred;
            MediaStateStore.getInstance().startNewSession(preferred.getSessionToken().toString());
            mActiveController.registerCallback(mControllerCallback, mHandler);
            Log.i(TAG, "Bound to controller: " + mActiveController.getPackageName() + " token: " + mActiveController.getSessionToken());
            handlePlaybackState(mActiveController.getPlaybackState());
            handleMetadata(mActiveController.getMetadata());
        }
    }

    private void handlePlaybackState(PlaybackState state) {
        long currentEpoch = MediaStateStore.getInstance().getSessionEpoch();
        if (state == null) {
            MediaStateStore.getInstance().updatePlaybackState(currentEpoch, false, 0, 0f, System.currentTimeMillis());
            mIsPlaying = false;
            updateFloatingRotationState();
            MainActivity act = MainActivity.getInstance();
            if (act != null) {
                act.dispatchPlaybackState(false, 0, 0f);
            }
            return;
        }
        int playbackState = state.getState();
        long position = state.getPosition();
        float speed = state.getPlaybackSpeed();
        if (Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0f) {
            speed = 1.0f;
        }

        boolean isPlaying = (playbackState == PlaybackState.STATE_PLAYING);
        long updateTime = state.getLastPositionUpdateTime();
        MediaStateStore.getInstance().updatePlaybackState(currentEpoch, isPlaying, position, speed, updateTime);

        long now = android.os.SystemClock.elapsedRealtime();
        long estimatedPos = position;

        // Only extrapolate if state is playing, position is known (>= 0), updateTime is valid and in the past
        if (isPlaying && position >= 0 && updateTime > 0 && now >= updateTime) {
            estimatedPos += (long) ((now - updateTime) * speed);
        }

        // Clamp bounds: lower bound 0 if position is valid, upper bound mDuration only if mDuration > 0
        if (estimatedPos < 0) {
            estimatedPos = 0;
        }
        if (mDuration > 0 && estimatedPos > mDuration) {
            estimatedPos = mDuration;
        }
        mCurrentPosition = estimatedPos;

        if (isPlaying) {
            boolean wasPlaying = mIsPlaying;
            mIsPlaying = true;
            updateFloatingRotationState();

            // Wake screen if sleeping
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire(5000);
            }

            // Auto popup full screen canvas when playing music
            if (mUserDismissed) {
                showFloatingView();
            } else {
                hideFloatingView();
                if (MainActivity.getInstance() == null) {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                }
            }

            MainActivity act = MainActivity.getInstance();
            if (act != null) {
                act.dispatchPlaybackState(true, estimatedPos, speed);
                if (!wasPlaying || sCachedSinkAudioConfig == null) {
                    act.dispatchBluetoothStatus(getBluetoothInfo(this));
                }
            }
        } else if (playbackState == PlaybackState.STATE_PAUSED || playbackState == PlaybackState.STATE_STOPPED) {
            mIsPlaying = false;
            updateFloatingRotationState();
            MainActivity act = MainActivity.getInstance();
            if (act != null) {
                act.dispatchPlaybackState(false, estimatedPos, speed);
            }
        }
    }

    private void onNoMetadataAudio() {
        // Only enter ambient/video stream mode if playback is genuinely active!
        // Never pollute idle/boot/disconnected state with "蓝牙媒体音频".
        if (!mIsPlaying) {
            return;
        }
        Log.i(TAG, "Audio stream active with no track metadata (video/app stream, e.g. xhs/tiktok)");
        mStreamLyric = "";
        mRealTitle = "蓝牙媒体音频";
        mRealArtist = "视频 / 媒体声音";
        mRealAlbum = "";
        mDuration = 0;
        mCurrentCoverUrl = "";
        mCurrentCoverBitmap = null;
        mGeneratedVinylBitmap = null;
        mLoadedBitmapUrl = "";
        updateFloatingViewContent();
        MainActivity act = MainActivity.getInstance();
        if (act != null) {
            act.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, "");
            act.dispatchOnlineResult("", "", "视频模式");
            act.dispatchPlaybackState(mIsPlaying, 0, 1.0f);
        }
    }

    private void handleMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            onNoMetadataAudio();
            return;
        }
        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String mediaId = null;
        try {
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        } catch (Exception ignored) {}

        if (rawTitle == null || rawTitle.trim().isEmpty() || "Not Provided".equalsIgnoreCase(rawTitle.trim())) {
            onNoMetadataAudio();
            return;
        }

        long currentEpoch = MediaStateStore.getInstance().getSessionEpoch();
        MediaStateStore.MediaSnapshot snapshot = MediaStateStore.getInstance().handleMetadata(
                currentEpoch, rawTitle, rawArtist, album, duration, mediaId
        );

        if (snapshot == null) {
            // Duplicate notification from AVRCP or stale session event:
            // Do NOT increment generation, do NOT wipe progress, do NOT re-fetch online!
            return;
        }

        mRealTitle = snapshot.title;
        mRealArtist = snapshot.artist;
        mRealAlbum = snapshot.album;
        mDuration = snapshot.duration;
        mStreamLyric = snapshot.streamLyric;
        mPlaybackGeneration = snapshot.playbackGeneration;

        MainActivity act = MainActivity.getInstance();

        if (snapshot.changeType == MediaStateStore.ChangeType.STREAM_LYRIC) {
            Log.i(TAG, "Bluetooth stream lyric updated: [" + mStreamLyric + "]");
            if (act != null) {
                act.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric,
                        snapshot.playbackGeneration, snapshot.songCacheKey, true);
            }
            updateFloatingViewContent();
            return;
        }

        if (snapshot.changeType == MediaStateStore.ChangeType.METADATA_ENRICHMENT) {
            Log.i(TAG, "Metadata enriched for same track: album=[" + mRealAlbum + "] duration=" + mDuration);
            if (act != null) {
                act.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric,
                        snapshot.playbackGeneration, snapshot.songCacheKey, true);
            }
            updateFloatingViewContent();
            return;
        }

        // snapshot.changeType == MediaStateStore.ChangeType.NEW_TRACK
        final long capturedGen = snapshot.playbackGeneration;
        mUserDismissed = false;
        mCurrentPosition = 0;
        mCurrentCoverUrl = "";
        Log.i(TAG, "New song loaded (gen " + capturedGen + "): [" + mRealTitle + "] by [" + mRealArtist + "]");

        // Check local album artwork first
        Bitmap localArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (localArt == null) {
            localArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        boolean hasStream = mStreamLyric != null && !mStreamLyric.trim().isEmpty();
        if (localArt != null) {
            mCurrentCoverBitmap = localArt;
            mLoadedBitmapUrl = "local:" + capturedGen;
            mGeneratedVinylBitmap = null;
            updateFloatingViewContent();
        } else {
            // 切歌或进入视频且无本地内嵌封面时，先重置为空白黑胶盘面，防止残留上一首歌或默认人像封面
            mCurrentCoverBitmap = null;
            mGeneratedVinylBitmap = null;
            mLoadedBitmapUrl = "";
            if (act == null && (hasStream || !MetadataFetcher.isVideoTrack(mRealTitle, mRealArtist, mRealAlbum, mDuration))) {
            // Only fetch directly from service when MainActivity is not active;
            // when MainActivity is active, it fetches once and notifies onOnlineCoverResolved().
            final String key = snapshot.songCacheKey;
            if (!key.isEmpty() && !key.equals(mLastFetchedCoverKey)) {
                mLastFetchedCoverKey = key;
                MetadataFetcher.fetch(mRealTitle, mRealArtist, mRealAlbum, mDuration, new MetadataFetcher.Callback() {
                    @Override
                    public void onResult(final String coverUrl, final String lyricsLrc, final String lyricSource) {
                        if (coverUrl != null && !coverUrl.isEmpty()) {
                            onOnlineCoverResolved(coverUrl, capturedGen);
                        }
                    }
                });
            }
            }
        }

        updateFloatingViewContent();

        if (act != null) {
            act.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric,
                    snapshot.playbackGeneration, snapshot.songCacheKey, false);
        }
    }

    public void onOnlineCoverResolved(final String coverUrl, final long generation) {
        if (coverUrl == null || coverUrl.isEmpty()) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (generation != mPlaybackGeneration) return;
                mCurrentCoverUrl = coverUrl;
                if (mIsFloatingShown) {
                    loadCoverBitmapAsync(coverUrl, generation);
                }
            }
        });
    }

    private void loadCoverBitmapAsync(final String coverUrl, final long capturedGen) {
        if (coverUrl == null || coverUrl.isEmpty()) return;
        if (coverUrl.equals(mLoadedBitmapUrl) && mCurrentCoverBitmap != null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(coverUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    InputStream is = conn.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp != null) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (capturedGen == mPlaybackGeneration) {
                                    mCurrentCoverBitmap = bmp;
                                    mLoadedBitmapUrl = coverUrl;
                                    mGeneratedVinylBitmap = null;
                                    updateFloatingViewContent();
                                } else {
                                    Log.d(TAG, "Discarded stale floating cover for gen " + capturedGen + " (current is " + mPlaybackGeneration + ")");
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load floating cover: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    public void syncToActivity(MainActivity activity) {
        MediaStateStore.MediaSnapshot snap = MediaStateStore.getInstance().getSnapshot();
        if (snap != null && snap.hasTrack()) {
            Log.i(TAG, "Syncing current track to MainActivity from MediaStateStore: " + snap.title);
            activity.dispatchMetadata(snap.title, snap.artist, snap.album, snap.duration, snap.streamLyric,
                    snap.playbackGeneration, snap.songCacheKey, false);
            long now = android.os.SystemClock.elapsedRealtime();
            long estPos = snap.clock.getEstimatedPositionMs(now);
            if (snap.duration > 0 && estPos > snap.duration) estPos = snap.duration;
            activity.dispatchPlaybackState(snap.clock.isPlaying, estPos, snap.clock.speed);
        } else if (!mRealTitle.isEmpty() && (!"蓝牙媒体音频".equals(mRealTitle) || mIsPlaying)) {
            activity.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric,
                    mPlaybackGeneration, SongIdentity.buildCacheKey(mRealTitle, mRealArtist, mRealAlbum), false);
            activity.dispatchPlaybackState(mIsPlaying, mCurrentPosition, 1.0f);
        }
        activity.dispatchBluetoothStatus(getBluetoothInfo(this));
    }

    /**
     * 从 Android Bluedroid 协议栈底层状态 (dumpsys bluetooth_manager) 解析当前活动连接的真实 AVDTP 协商编码器：
     * 1. 若 A2DP Source 处于活动态，读取 "Current Codec: <SBC|AAC|aptX|aptX-HD|LDAC>"；
     * 2. 若 A2DP Sink 处于活动态，解析对应 peer 的 BTA AV Stream Control Block 中各 SEP 的
     *    "SEP AVDTP handle: <H>" -> "Codec: <C>" 映射表，并与当前激活流句柄 "AVDTP handle: <H>" 精确匹配。
     */
    private static String probeActiveA2dpCodec(String peerAddress) {
        String normPeer = (peerAddress != null) ? peerAddress.trim().toLowerCase(java.util.Locale.US) : "";
        if (!sCachedNegotiatedCodec.isEmpty() && normPeer.equals(sCachedNegotiatedPeer)) {
            return sCachedNegotiatedCodec;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastCodecProbeTimeMs < 5000 && sLastCodecProbeTimeMs > 0 && normPeer.equals(sCachedNegotiatedPeer)) {
            return sCachedNegotiatedCodec;
        }
        sLastCodecProbeTimeMs = now;
        sCachedNegotiatedPeer = normPeer;

        java.lang.Process proc = null;
        java.io.BufferedReader reader = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"dumpsys", "bluetooth_manager"});
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()));

            java.util.Map<Integer, String> sepCodecMap = new java.util.HashMap<>();
            boolean inMatchingBtaPeer = false;
            int currentSepHandle = -1;
            String currentCodec = "";
            String line;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Current Codec:")) {
                    String val = trimmed.substring("Current Codec:".length()).trim();
                    if (!val.isEmpty() && !"None".equalsIgnoreCase(val)) {
                        currentCodec = val;
                    }
                } else if (trimmed.startsWith("BTA ID:")) {
                    String lower = trimmed.toLowerCase(java.util.Locale.US);
                    if (!normPeer.isEmpty() && !"—".equals(normPeer)) {
                        inMatchingBtaPeer = lower.contains(normPeer);
                    } else {
                        inMatchingBtaPeer = !lower.contains("00:00:00:00:00:00");
                    }
                    sepCodecMap.clear();
                    currentSepHandle = -1;
                } else if (inMatchingBtaPeer) {
                    if (trimmed.startsWith("SEP AVDTP handle:")) {
                        try {
                            currentSepHandle = Integer.parseInt(trimmed.substring("SEP AVDTP handle:".length()).trim());
                        } catch (NumberFormatException ignored) {
                            currentSepHandle = -1;
                        }
                    } else if (trimmed.startsWith("Codec:") && currentSepHandle >= 0) {
                        String c = trimmed.substring("Codec:".length()).trim();
                        if (!c.isEmpty()) {
                            sepCodecMap.put(currentSepHandle, c);
                        }
                    } else if (trimmed.startsWith("AVDTP handle:")) {
                        try {
                            int activeAvdtpHandle = Integer.parseInt(trimmed.substring("AVDTP handle:".length()).trim());
                            String matchedCodec = sepCodecMap.get(activeAvdtpHandle);
                            if (matchedCodec != null && !matchedCodec.isEmpty()) {
                                currentCodec = matchedCodec;
                                break;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            if (!currentCodec.isEmpty()) {
                sCachedNegotiatedCodec = currentCodec;
                return currentCodec;
            }
        } catch (Throwable t) {
            Log.w(TAG, "probeActiveA2dpCodec failed: " + t.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
        return "";
    }

    private static String mapAudioFormatToBitDepth(int audioFormat) {
        switch (audioFormat) {
            case android.media.AudioFormat.ENCODING_PCM_8BIT:
                return "8bit";
            case android.media.AudioFormat.ENCODING_PCM_16BIT:
                return "16bit";
            case 21: // AudioFormat.ENCODING_PCM_24BIT_PACKED
                return "24bit";
            case 22: // AudioFormat.ENCODING_PCM_32BIT
            case android.media.AudioFormat.ENCODING_PCM_FLOAT:
                return "32bit";
            default:
                return "";
        }
    }

    private static String mapChannelConfigToLabel(int channelConfig) {
        if (channelConfig == android.media.AudioFormat.CHANNEL_IN_STEREO
                || channelConfig == android.media.AudioFormat.CHANNEL_OUT_STEREO
                || channelConfig == 0x03) {
            return "立体声";
        }
        if (channelConfig == android.media.AudioFormat.CHANNEL_IN_MONO
                || channelConfig == android.media.AudioFormat.CHANNEL_OUT_MONO
                || channelConfig == 0x01) {
            return "单声道";
        }
        int bits = Integer.bitCount(channelConfig);
        if (bits == 2) return "立体声";
        if (bits == 1) return "单声道";
        if (bits == 6) return "5.1声道";
        if (bits == 8) return "7.1声道";
        if (bits > 2) return bits + "声道";
        return "";
    }

    public static org.json.JSONObject getBluetoothInfo(Context context) {
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            boolean isConnected = false;
            String connectionState = "disconnected";
            String name = "—";
            String address = "—";
            android.bluetooth.BluetoothDevice connectedDevice = null;

            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                connectionState = "off";
                name = "蓝牙已关闭";
            } else {
                // 1. 优先从已绑定的 A2DP Sink / Source Profile Proxy 获取真实活动连接设备
                if (sA2dpSinkProxy != null) {
                    try {
                        List<android.bluetooth.BluetoothDevice> sinkDevs = sA2dpSinkProxy.getConnectedDevices();
                        if (sinkDevs != null && !sinkDevs.isEmpty()) {
                            connectedDevice = sinkDevs.get(0);
                        }
                    } catch (Throwable ignored) {}
                }
                if (connectedDevice == null && sA2dpSourceProxy != null) {
                    try {
                        List<android.bluetooth.BluetoothDevice> srcDevs = sA2dpSourceProxy.getConnectedDevices();
                        if (srcDevs != null && !srcDevs.isEmpty()) {
                            connectedDevice = srcDevs.get(0);
                        }
                    } catch (Throwable ignored) {}
                }

                java.util.Set<android.bluetooth.BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null && !bonded.isEmpty()) {
                    String firstBondedName = null;
                    String firstBondedAddress = null;

                    for (android.bluetooth.BluetoothDevice d : bonded) {
                        if (d != null) {
                            if (firstBondedName == null) {
                                firstBondedName = d.getName();
                                firstBondedAddress = d.getAddress();
                            }
                            boolean connected = (connectedDevice != null && connectedDevice.equals(d));
                            if (!connected) {
                                try {
                                    java.lang.reflect.Method isConnectedMethod = d.getClass().getMethod("isConnected");
                                    Object res = isConnectedMethod.invoke(d);
                                    if (res instanceof Boolean && (Boolean) res) {
                                        connected = true;
                                    }
                                } catch (Exception ignored) {}
                            }

                            if (connected) {
                                isConnected = true;
                                connectedDevice = d;
                                connectionState = "connected";
                                name = d.getName() != null ? d.getName() : "已连接设备";
                                address = d.getAddress() != null ? d.getAddress() : "—";
                                break;
                            }
                        }
                    }

                    if (!isConnected && firstBondedName != null) {
                        // 设备已配对但尚未建立连接链路
                        connectionState = "disconnected";
                        name = firstBondedName;
                        address = firstBondedAddress != null ? firstBondedAddress : "—";
                    }
                }
            }

            // 2. 真实读取蓝牙协议栈与音频硬件参数（绝不使用写死假常量）
            String codecLabel = "";
            String sampleRateLabel = "";
            String bitDepthLabel = "";
            String channelsLabel = "";
            int detectedHz = 0;
            String specSource = "none";

            // 2.1 尝试从 BluetoothA2dp.getCodecStatus (BluetoothCodecConfig) 读取真实协商编码器 (SBC/AAC/aptX/LDAC) 与规格
            Object codecCfg = sCachedCodecConfig;
            if (codecCfg == null && sA2dpSourceProxy != null) {
                try {
                    Object codecStatus = null;
                    try {
                        java.lang.reflect.Method m = sA2dpSourceProxy.getClass().getMethod("getCodecStatus", android.bluetooth.BluetoothDevice.class);
                        codecStatus = m.invoke(sA2dpSourceProxy, connectedDevice);
                    } catch (NoSuchMethodException e) {
                        java.lang.reflect.Method m = sA2dpSourceProxy.getClass().getMethod("getCodecStatus");
                        codecStatus = m.invoke(sA2dpSourceProxy);
                    }
                    if (codecStatus != null) {
                        codecCfg = codecStatus.getClass().getMethod("getCodecConfig").invoke(codecStatus);
                        if (codecCfg != null) sCachedCodecConfig = codecCfg;
                    }
                } catch (Throwable ignored) {}
            }
            if (codecCfg != null && isConnected) {
                try {
                    int codecType = (Integer) codecCfg.getClass().getMethod("getCodecType").invoke(codecCfg);
                    switch (codecType) {
                        case 0: codecLabel = "SBC"; break;
                        case 1: codecLabel = "AAC"; break;
                        case 2: codecLabel = "aptX"; break;
                        case 3: codecLabel = "aptX HD"; break;
                        case 4: codecLabel = "LDAC"; break;
                    }
                    int srMask = (Integer) codecCfg.getClass().getMethod("getSampleRate").invoke(codecCfg);
                    if ((srMask & 1) != 0) detectedHz = 44100;
                    else if ((srMask & 2) != 0) detectedHz = 48000;
                    else if ((srMask & 4) != 0) detectedHz = 88200;
                    else if ((srMask & 8) != 0) detectedHz = 96000;
                    else if ((srMask & 16) != 0) detectedHz = 176400;
                    else if ((srMask & 32) != 0) detectedHz = 192000;

                    int bpsMask = (Integer) codecCfg.getClass().getMethod("getBitsPerSample").invoke(codecCfg);
                    if ((bpsMask & 1) != 0) bitDepthLabel = "16bit";
                    else if ((bpsMask & 2) != 0) bitDepthLabel = "24bit";
                    else if ((bpsMask & 4) != 0) bitDepthLabel = "32bit";

                    int chMask = (Integer) codecCfg.getClass().getMethod("getChannelMode").invoke(codecCfg);
                    if ((chMask & 2) != 0) channelsLabel = "立体声";
                    else if ((chMask & 1) != 0) channelsLabel = "单声道";
                    specSource = "BluetoothCodecConfig";
                } catch (Throwable ignored) {}
            }

            // 2.2 尝试从 BluetoothA2dpSink.getAudioConfig(device) (BluetoothAudioConfig) 读取蓝牙接收端真实解码流规格
            if (isConnected) {
                Object sinkAudioCfg = null;
                if (sA2dpSinkProxy != null && connectedDevice != null) {
                    try {
                        java.lang.reflect.Method getAudioCfg = sA2dpSinkProxy.getClass().getMethod("getAudioConfig", android.bluetooth.BluetoothDevice.class);
                        sinkAudioCfg = getAudioCfg.invoke(sA2dpSinkProxy, connectedDevice);
                        if (sinkAudioCfg != null) {
                            sCachedSinkAudioConfig = sinkAudioCfg;
                        }
                    } catch (Throwable ignored) {}
                }
                if (sinkAudioCfg == null) {
                    sinkAudioCfg = sCachedSinkAudioConfig;
                }
                if (sinkAudioCfg != null) {
                    try {
                        int sr = (Integer) sinkAudioCfg.getClass().getMethod("getSampleRate").invoke(sinkAudioCfg);
                        int fmt = (Integer) sinkAudioCfg.getClass().getMethod("getAudioFormat").invoke(sinkAudioCfg);
                        int chCfg = (Integer) sinkAudioCfg.getClass().getMethod("getChannelConfig").invoke(sinkAudioCfg);
                        if (sr > 0) detectedHz = sr;
                        String mappedBits = mapAudioFormatToBitDepth(fmt);
                        if (!mappedBits.isEmpty()) bitDepthLabel = mappedBits;
                        String mappedCh = mapChannelConfigToLabel(chCfg);
                        if (!mappedCh.isEmpty()) channelsLabel = mappedCh;
                        specSource = "BluetoothAudioConfig(" + sinkAudioCfg + ")";
                    } catch (Throwable ignored) {}
                }
                if (codecLabel.isEmpty()) {
                    String btaCodec = probeActiveA2dpCodec(address);
                    if (!btaCodec.isEmpty()) {
                        codecLabel = "A2DP · " + btaCodec;
                        specSource += "+BTA_AV(" + btaCodec + ")";
                    } else if (sA2dpSinkProxy != null) {
                        codecLabel = "A2DP";
                    }
                }
            }

            // 2.3 从 Android AudioManager 真实输出硬件设备 (AudioDeviceInfo) 与 Visualizer 补齐未由蓝牙层提供的物理参数
            if (context != null) {
                try {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.media.AudioDeviceInfo[] outDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                        if (outDevices != null && outDevices.length > 0) {
                            android.media.AudioDeviceInfo activeDev = outDevices[0];
                            for (android.media.AudioDeviceInfo dev : outDevices) {
                                int t = dev.getType();
                                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                        || t == android.media.AudioDeviceInfo.TYPE_HDMI
                                        || t == android.media.AudioDeviceInfo.TYPE_HDMI_ARC
                                        || t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
                                        || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                                        || t == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                    activeDev = dev;
                                    if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) break;
                                }
                            }

                            if (codecLabel.isEmpty()) {
                                if (isConnected) {
                                    codecLabel = "A2DP";
                                } else {
                                    int t = activeDev.getType();
                                    if (t == android.media.AudioDeviceInfo.TYPE_HDMI || t == android.media.AudioDeviceInfo.TYPE_HDMI_ARC) {
                                        codecLabel = "HDMI";
                                    } else if (t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) {
                                        codecLabel = "USB";
                                    } else {
                                        codecLabel = "PCM";
                                    }
                                }
                            }

                            if (bitDepthLabel.isEmpty()) {
                                int[] encodings = activeDev.getEncodings();
                                if (encodings != null && encodings.length > 0) {
                                    for (int enc : encodings) {
                                        String b = mapAudioFormatToBitDepth(enc);
                                        if (!b.isEmpty()) {
                                            bitDepthLabel = b;
                                            if ("16bit".equals(b) || "24bit".equals(b)) break;
                                        }
                                    }
                                }
                            }

                            if (channelsLabel.isEmpty()) {
                                int[] counts = activeDev.getChannelCounts();
                                if (counts != null && counts.length > 0) {
                                    boolean hasStereo = false;
                                    int maxCh = 0;
                                    for (int c : counts) {
                                        if (c == 2) hasStereo = true;
                                        if (c > maxCh) maxCh = c;
                                    }
                                    if (hasStereo) channelsLabel = "立体声";
                                    else if (maxCh == 1) channelsLabel = "单声道";
                                    else if (maxCh > 2) channelsLabel = maxCh + "声道";
                                }
                                if (channelsLabel.isEmpty()) {
                                    int[] masks = activeDev.getChannelMasks();
                                    if (masks != null && masks.length > 0) {
                                        channelsLabel = mapChannelConfigToLabel(masks[0]);
                                    }
                                }
                            }

                            if (detectedHz <= 0) {
                                int[] rates = activeDev.getSampleRates();
                                if (rates != null && rates.length == 1 && rates[0] > 0) {
                                    detectedHz = rates[0];
                                }
                            }
                            if ("none".equals(specSource)) {
                                specSource = "AudioDeviceInfo(type=" + activeDev.getType() + ")";
                            }
                        }
                    }

                    if (detectedHz <= 0) {
                        detectedHz = AudioVisualizerHelper.getLastSamplingRateHz();
                    }
                    if (detectedHz <= 0 && am != null) {
                        String prop = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                        if (prop != null && !prop.trim().isEmpty()) {
                            detectedHz = Integer.parseInt(prop.trim());
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (detectedHz > 0) {
                if (detectedHz % 1000 == 0) {
                    sampleRateLabel = (detectedHz / 1000) + ".0kHz";
                } else {
                    sampleRateLabel = String.format(java.util.Locale.US, "%.1fkHz", detectedHz / 1000.0f);
                }
            }

            Log.i(TAG, "Real Audio/BT Spec resolved [" + specSource + "]: codec=" + codecLabel
                    + ", sampleRate=" + sampleRateLabel + ", bitDepth=" + bitDepthLabel + ", channels=" + channelsLabel);

            obj.put("connected", isConnected);
            obj.put("connectionState", connectionState);
            obj.put("deviceName", name);
            obj.put("deviceAddress", address);
            obj.put("codec", codecLabel);
            obj.put("sampleRate", sampleRateLabel);
            obj.put("bitDepth", bitDepthLabel);
            obj.put("channels", channelsLabel);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return obj;
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
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    private void updateFloatingRotationState() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mFloatingView == null || !mIsFloatingShown) return;
                ImageView vinylIv = mFloatingView.findViewById(R.id.floatingVinylIcon);
                if (vinylIv == null) return;
                if (mIsPlaying) {
                    if (vinylIv.getAnimation() == null) {
                        RotateAnimation rotate = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                        rotate.setDuration(7000);
                        rotate.setRepeatCount(Animation.INFINITE);
                        rotate.setInterpolator(new LinearInterpolator());
                        vinylIv.startAnimation(rotate);
                    }
                } else {
                    vinylIv.clearAnimation();
                }
            }
        });
    }

    private void updateFloatingViewContent() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mFloatingView == null || !mIsFloatingShown) return;

                TextView titleTv = mFloatingView.findViewById(R.id.floatingTrackTitle);
                TextView artistTv = mFloatingView.findViewById(R.id.floatingTrackArtist);
                ImageView vinylIv = mFloatingView.findViewById(R.id.floatingVinylIcon);

                if (titleTv != null) {
                    String title = !mRealTitle.isEmpty() ? mRealTitle : "蓝牙音乐";
                    titleTv.setText(title);
                    titleTv.setSelected(true); // Triggers continuous marquee scrolling
                }

                if (artistTv != null) {
                    if (mRealArtist != null && !mRealArtist.isEmpty()) {
                        artistTv.setText(mRealArtist);
                        artistTv.setVisibility(View.VISIBLE);
                    } else {
                        artistTv.setVisibility(View.GONE);
                    }
                }

                if (vinylIv != null) {
                    if (mGeneratedVinylBitmap == null) {
                        int px = (int) (44 * getResources().getDisplayMetrics().density);
                        mGeneratedVinylBitmap = createVinylBitmap(mCurrentCoverBitmap, px);
                    }
                    vinylIv.setImageBitmap(mGeneratedVinylBitmap);
                }
            }
        });
    }

    /**
     * Create a realistic circular vinyl record bitmap with concentric grooves
     * and the current song's album cover embedded in the center spindle label.
     */
    private Bitmap createVinylBitmap(Bitmap cover, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float center = size / 2.0f;
        float radius = size / 2.0f;

        // 1. Dark outer vinyl disc
        paint.setColor(Color.parseColor("#121316"));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(center, center, radius, paint);

        // 2. Realistic concentric micro-grooves
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        for (float r = radius * 0.94f; r > radius * 0.58f; r -= size * 0.045f) {
            paint.setColor(Color.parseColor("#1E2026"));
            canvas.drawCircle(center, center, r, paint);
            paint.setColor(Color.parseColor("#0A0B0E"));
            canvas.drawCircle(center, center, r - 1.0f, paint);
        }

        // 3. Center Label (Radius ~ 55% of disc)
        float labelRadius = radius * 0.55f;
        if (cover != null) {
            BitmapShader shader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            float scale = (labelRadius * 2.0f) / Math.min(cover.getWidth(), cover.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(center - (cover.getWidth() * scale) / 2.0f,
                                center - (cover.getHeight() * scale) / 2.0f);
            shader.setLocalMatrix(matrix);

            Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            coverPaint.setShader(shader);
            canvas.drawCircle(center, center, labelRadius, coverPaint);

            // Subtle dark rim around cover label
            paint.setColor(Color.parseColor("#40000000"));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.0f);
            canvas.drawCircle(center, center, labelRadius, paint);
        } else {
            // Default warm coral center label if no cover available
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#E06D53"));
            canvas.drawCircle(center, center, labelRadius, paint);
        }

        // 4. Center spindle hole
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#08090A"));
        canvas.drawCircle(center, center, radius * 0.08f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.parseColor("#444955"));
        paint.setStrokeWidth(1.5f);
        canvas.drawCircle(center, center, radius * 0.08f, paint);

        return out;
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

                    final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            layoutType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                    );

                    params.gravity = Gravity.TOP | Gravity.START;
                    params.x = mFloatingX;
                    params.y = mFloatingY;

                    mFloatingView = LayoutInflater.from(MediaMonitorService.this).inflate(R.layout.floating_vinyl, null);

                    mWindowManager.addView(mFloatingView, params);
                    mIsFloatingShown = true;

                    // Lazily decode cover bitmap if URL is ready
                    if (!mCurrentCoverUrl.isEmpty() && (!mCurrentCoverUrl.equals(mLoadedBitmapUrl) || mCurrentCoverBitmap == null)) {
                        loadCoverBitmapAsync(mCurrentCoverUrl, mPlaybackGeneration);
                    }

                    // Update live track title, artist and album vinyl cover
                    updateFloatingViewContent();
                    updateFloatingRotationState();

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
                                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                                        isMove = true;
                                        params.x = initialX + dx;
                                        params.y = initialY + dy;
                                        mWindowManager.updateViewLayout(mFloatingView, params);
                                    }
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    if (isMove) {
                                        // Save dragged position permanently
                                        mFloatingX = params.x;
                                        mFloatingY = params.y;
                                        if (mPrefs != null) {
                                            mPrefs.edit().putInt("pos_x", mFloatingX).putInt("pos_y", mFloatingY).apply();
                                        }
                                        Log.i(TAG, "Floating position saved: (" + mFloatingX + ", " + mFloatingY + ")");
                                    } else {
                                        restoreFullScreen();
                                    }
                                    return true;
                            }
                            return false;
                        }
                    });

                    Log.i(TAG, "Floating vinyl capsule shown at (" + params.x + ", " + params.y + ")");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show floating window: " + e.getMessage());
                }
            }
        });
    }

    public void hideFloatingView() {
        mUserDismissed = false;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mIsFloatingShown && mFloatingView != null && mWindowManager != null) {
                    try {
                        ImageView vinylIv = mFloatingView.findViewById(R.id.floatingVinylIcon);
                        if (vinylIv != null) {
                            vinylIv.clearAnimation();
                        }
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
