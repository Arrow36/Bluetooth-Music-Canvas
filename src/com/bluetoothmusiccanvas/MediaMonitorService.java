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

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action)
                    || "android.bluetooth.device.action.ACL_DISCONNECTED".equals(action)
                    || "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                    || "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                    || "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED".equals(action)) {
                Log.i(TAG, "Bluetooth state changed (" + action + ")");
                if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action)) {
                    mUserDismissed = false;
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
        filter.addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED");
        registerReceiver(mBluetoothReceiver, filter);

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
        // 保留之前的有效封面 Bitmap，在视频模式不置为 null
        updateFloatingViewContent();
        MainActivity act = MainActivity.getInstance();
        if (act != null) {
            act.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, "");
            act.dispatchOnlineResult(MainActivity.sLastValidMusicCoverUrl, "", "视频模式");
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
        } else if (act == null && (hasStream || !MetadataFetcher.isVideoTrack(mRealTitle, mRealArtist, mRealAlbum, mDuration))) {
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

    public static org.json.JSONObject getBluetoothInfo(Context context) {
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            boolean isConnected = false;
            String connectionState = "disconnected";
            String name = "—";
            String address = "—";

            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                connectionState = "off";
                name = "蓝牙已关闭";
            } else {
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
                            boolean connected = false;
                            try {
                                java.lang.reflect.Method isConnectedMethod = d.getClass().getMethod("isConnected");
                                Object res = isConnectedMethod.invoke(d);
                                if (res instanceof Boolean && (Boolean) res) {
                                    connected = true;
                                }
                            } catch (Exception ignored) {}

                            if (connected) {
                                isConnected = true;
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

            String sampleRateLabel = "48.0kHz";
            int detectedHz = AudioVisualizerHelper.getLastSamplingRateHz();
            if (detectedHz <= 0 && context != null) {
                try {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
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

            obj.put("connected", isConnected);
            obj.put("connectionState", connectionState);
            obj.put("deviceName", name);
            obj.put("deviceAddress", address);
            obj.put("codec", "A2DP");
            obj.put("sampleRate", sampleRateLabel);
            obj.put("bitDepth", "16bit");
            obj.put("channels", "立体声");
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
