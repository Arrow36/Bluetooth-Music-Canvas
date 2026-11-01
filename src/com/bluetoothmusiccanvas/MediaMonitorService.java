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
import java.util.regex.Pattern;

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

    // Album cover caching & dynamic vinyl disc
    private Bitmap mCurrentCoverBitmap = null;
    private Bitmap mGeneratedVinylBitmap = null;
    private String mLastFetchedCoverKey = "";

    // Metadata caching
    private String mRealTitle = "";
    private String mRealArtist = "";
    private String mLastRawArtist = "";
    private String mRealAlbum = "";
    private String mStreamLyric = "";
    private long mDuration = 0;
    private long mCurrentPosition = 0;
    private long mPlaybackGeneration = 0;
    private String mLastMediaId = null;

    // Pattern for "车载蓝牙歌词" e.g. Artist: "王菲/如愿 (电影《我和我的父辈》主题推广曲)"
    private static final Pattern BT_LYRIC_PATTERN = Pattern.compile("^([^/]+)/([^\\(（]+)");

    private static int sLastKnownBattery = -1;

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if ("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(action)
                    || "org.codeaurora.intent.action.BATTERY_LEVEL_PARENT".equals(action)) {
                int level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
                if (level < 0) {
                    level = intent.getIntExtra("level", -1);
                }
                if (level >= 0) {
                    sLastKnownBattery = level;
                    Log.i(TAG, "Bluetooth battery broadcast received: " + level + "%");
                    if (MainActivity.sInstance != null) {
                        MainActivity.sInstance.dispatchBluetoothStatus(getBluetoothInfo(context));
                    }
                }
            } else if ("android.bluetooth.device.action.ACL_CONNECTED".equals(action)
                    || "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)
                    || "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED".equals(action)) {
                Log.i(TAG, "Bluetooth connected / connection state changed, resetting user dismissal");
                mUserDismissed = false;
                if (MainActivity.sInstance != null) {
                    MainActivity.sInstance.dispatchBluetoothStatus(getBluetoothInfo(context));
                }
            }
        }
    };

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
        mPrefs = getSharedPreferences("floating_widget_prefs", Context.MODE_PRIVATE);
        mFloatingX = mPrefs.getInt("pos_x", 40);
        mFloatingY = mPrefs.getInt("pos_y", 120);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "BluetoothMusicCanvas:WakeLock");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        filter.addAction("org.codeaurora.intent.action.BATTERY_LEVEL_PARENT");
        filter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
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

            // Wake screen if sleeping
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire(5000);
            }

            // Auto popup full screen canvas when playing music
            if (mUserDismissed) {
                showFloatingView();
            } else {
                hideFloatingView();
                if (MainActivity.sInstance == null) {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                }
            }

            if (MainActivity.sInstance != null) {
                MainActivity.sInstance.dispatchPlaybackState(true, position, speed);
            }
        } else if (playbackState == PlaybackState.STATE_PAUSED || playbackState == PlaybackState.STATE_STOPPED) {
            mIsPlaying = false;
            mHandler.removeCallbacks(mAutoCloseRunnable);
            if (MainActivity.sInstance != null) {
                MainActivity.sInstance.dispatchPlaybackState(false, position, speed);
            }
            // 用户体验优化：暂停音乐后，保留当前全屏界面，不自动退出
        }
    }

    private void onNoMetadataAudio() {
        Log.i(TAG, "Audio stream active with no track metadata (video/app stream, e.g. xhs/tiktok)");
        mStreamLyric = "";
        mRealTitle = "蓝牙媒体音频";
        mRealArtist = "视频 / 媒体声音";
        mRealAlbum = "";
        mDuration = 0;
        mLastRawArtist = "";
        // 保留之前的有效封面 Bitmap，在视频模式不置为 null
        updateFloatingViewContent();
        if (MainActivity.sInstance != null) {
            MainActivity.sInstance.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, "");
            MainActivity.sInstance.dispatchOnlineResult(MainActivity.sLastValidMusicCoverUrl, "", "视频模式");
            MainActivity.sInstance.dispatchPlaybackState(true, 0, 1.0f);
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

        if (rawTitle == null || rawTitle.trim().isEmpty() || "Not Provided".equalsIgnoreCase(rawTitle.trim())) {
            onNoMetadataAudio();
            return;
        }

        rawTitle = rawTitle.trim();
        rawArtist = rawArtist != null ? rawArtist.trim() : "";
        album = album != null ? album.trim() : "";

        String candidateTitle = null;
        String performers = null;
        int lastSlash = rawArtist.lastIndexOf('/');
        if (lastSlash > 0) {
            candidateTitle = rawArtist.substring(lastSlash + 1).trim();
            performers = rawArtist.substring(0, lastSlash).trim();
        } else {
            int lastDash = rawArtist.lastIndexOf(" - ");
            if (lastDash > 0) {
                candidateTitle = rawArtist.substring(lastDash + 3).trim();
                performers = rawArtist.substring(0, lastDash).trim();
            }
        }

        // 智能检测古典乐与车载蓝牙 AVRCP 歌词投送中的元数据倒置与占位符：
        // 国内网易云、QQ音乐、酷狗等主流播放器在开启“车载蓝牙歌词”时，统一规范为：
        // Title 字段：实时滚动投送动态歌词（或纯音乐占位文案）
        // Artist 字段：格式固定为 "歌手 / 真实歌名"（如 "polarrana/Full of You (Acoustic Version)"）
        String mediaId = null;
        try {
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        } catch (Exception ignored) {}

        boolean isPlaceholderOrLyric = false;
        if (rawTitle != null && !rawTitle.isEmpty()) {
            String lower = rawTitle.toLowerCase();
            // 1. 中英文常见占位符、演职员名单关键词
            if (rawTitle.contains("纯音乐") || rawTitle.contains("请欣赏") || rawTitle.contains("没有填词")
                    || rawTitle.contains("享受音乐") || rawTitle.contains("作词") || rawTitle.contains("作曲")
                    || rawTitle.contains("编曲") || rawTitle.contains("制作") || rawTitle.contains("监制")
                    || rawTitle.contains("混音") || rawTitle.contains("母带") || rawTitle.contains("录音")
                    || rawTitle.contains("出品") || rawTitle.contains("原唱") || lower.contains("producer")
                    || lower.contains("composer") || lower.contains("lyricist") || lower.contains("instrumental")) {
                isPlaceholderOrLyric = true;
            }
            // 2. 标点符号特征（整句歌词常带标点，且长度通常不少于12字）
            else if ((rawTitle.contains("，") || rawTitle.contains("。") || rawTitle.contains("！")
                    || rawTitle.contains("？") || rawTitle.contains("~") || rawTitle.contains("…")
                    || rawTitle.contains(",") || rawTitle.contains("!") || rawTitle.contains("?"))
                    && rawTitle.length() >= 12) {
                isPlaceholderOrLyric = true;
            }
        }

        // 判断当前包是否属于“同一首歌的车载蓝牙动态歌词更新”
        boolean isCarBluetoothStreamForSameSong = false;
        if (candidateTitle != null && !candidateTitle.isEmpty() && !mRealTitle.isEmpty()) {
            if (candidateTitle.equalsIgnoreCase(mRealTitle)
                    || candidateTitle.contains(mRealTitle)
                    || mRealTitle.contains(candidateTitle)) {
                // 当前歌曲未切换，仅手机在动态投送下一句歌词
                isCarBluetoothStreamForSameSong = true;
            }
        }

        boolean isSameSongIdentity = !mRealTitle.isEmpty() && mRealTitle.equalsIgnoreCase(rawTitle)
                && (mRealArtist.equalsIgnoreCase(rawArtist) || (performers != null && mRealArtist.equalsIgnoreCase(performers)));

        if (isCarBluetoothStreamForSameSong) {
            // 同一首歌的实时歌词流更新：绝不更换歌名、绝不重新触发线上歌词搜索！
            if (!rawTitle.contains("纯音乐") && !rawTitle.contains("请欣赏")) {
                mStreamLyric = rawTitle;
                Log.i(TAG, "Bluetooth stream lyric updated: [" + mStreamLyric + "]");
            }
            if (performers != null && !performers.isEmpty()) {
                mRealArtist = performers;
            }
            if (duration > 0) {
                mDuration = duration;
            }
        } else if (isSameSongIdentity && (((mRealAlbum.isEmpty() && !album.isEmpty())) || (mDuration <= 0 && duration > 0))) {
            // 元数据补全 (如专辑或时长延迟到达)，更新信息并通知界面，绝不递增代次
            if (!album.isEmpty()) mRealAlbum = album;
            if (duration > 0) mDuration = duration;
            if (MainActivity.sInstance != null) {
                MainActivity.sInstance.dispatchMetadata(mRealTitle, mRealArtist, mRealAlbum, mDuration, mStreamLyric);
            }
            return;
        } else {
            // 新曲目载入
            mPlaybackGeneration++;
            final long capturedGen = mPlaybackGeneration;

            if (candidateTitle != null && !candidateTitle.isEmpty() && performers != null && !performers.isEmpty()) {
                boolean titleMatchesCandidate = candidateTitle.equalsIgnoreCase(rawTitle);
                boolean albumConfirmsCandidate = (!album.isEmpty() && (candidateTitle.equalsIgnoreCase(album) || album.toLowerCase().contains(candidateTitle.toLowerCase())) && !rawTitle.equalsIgnoreCase(album));

                if (titleMatchesCandidate) {
                    // Artist 中冗余包含了真实歌名（例如 "歌手 / 歌名" 但 Title 已经是真实歌名），清洗 Artist 字段
                    rawArtist = performers;
                    mStreamLyric = "";
                } else if (isPlaceholderOrLyric || albumConfirmsCandidate) {
                    // Title 是歌词或占位文案，真实歌名在 Artist 的斜杠后
                    Log.i(TAG, "Restored inverted title from artist: [" + candidateTitle + "] performers=[" + performers + "] streamLyric=[" + rawTitle + "]");
                    mStreamLyric = rawTitle;
                    rawTitle = candidateTitle;
                    rawArtist = performers;
                } else {
                    // 纯多艺术家歌曲（如 "fun. / Janelle Monae"），rawTitle 即真实歌名，保留完整多歌手列表
                    mStreamLyric = "";
                }
            } else {
                // 无斜杠的普通歌曲或视频曲目切换，彻底清空上一首歌的遗留歌词
                mStreamLyric = "";
            }

            mUserDismissed = false;
            mLastRawArtist = rawArtist;
            mRealTitle = rawTitle;
            mRealArtist = rawArtist;
            mRealAlbum = album;
            mDuration = duration;
            mLastMediaId = mediaId;
            Log.i(TAG, "New song loaded (gen " + capturedGen + "): [" + mRealTitle + "] by [" + mRealArtist + "]");

            // Check local album artwork first
            Bitmap localArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (localArt == null) {
                localArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            boolean hasStream = mStreamLyric != null && !mStreamLyric.trim().isEmpty();
            if (localArt != null) {
                mCurrentCoverBitmap = localArt;
                mGeneratedVinylBitmap = null;
                updateFloatingViewContent();
            } else if (hasStream || !MetadataFetcher.isVideoTrack(mRealTitle, mRealArtist, mRealAlbum, mDuration)) {
                // Fetch online cover for floating vinyl (skip for video tracks)
                final String key = MetadataFetcher.buildCacheKey(mRealTitle, mRealArtist, mRealAlbum);
                if (!key.isEmpty() && !key.equals(mLastFetchedCoverKey)) {
                    mLastFetchedCoverKey = key;
                    MetadataFetcher.fetch(mRealTitle, mRealArtist, mRealAlbum, mDuration, new MetadataFetcher.Callback() {
                        @Override
                        public void onResult(final String coverUrl, final String lyricsLrc, final String lyricSource) {
                            if (coverUrl != null && !coverUrl.isEmpty()) {
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            URL url = new URL(coverUrl);
                                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                                        }
                                    }
                                }).start();
                            }
                        }
                    });
                }
            }
        }

        updateFloatingViewContent();

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
        activity.dispatchBluetoothStatus(getBluetoothInfo(this));
    }

    public static org.json.JSONObject getBluetoothInfo(Context context) {
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            String name = "XY";
            String address = "C8:1F:E8:E2:CC:9D";
            int battery = -1;

            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                java.util.Set<android.bluetooth.BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (android.bluetooth.BluetoothDevice d : bonded) {
                        if (d != null) {
                            if (d.getName() != null && !d.getName().isEmpty()) {
                                name = d.getName();
                            }
                            if (d.getAddress() != null && !d.getAddress().isEmpty()) {
                                address = d.getAddress();
                            }
                            try {
                                java.lang.reflect.Method getBattery = d.getClass().getMethod("getBatteryLevel");
                                Integer bat = (Integer) getBattery.invoke(d);
                                if (bat != null && bat >= 0) {
                                    battery = bat;
                                }
                            } catch (Exception ignored) {}
                            break;
                        }
                    }
                }
            }

            if (battery < 0 && sLastKnownBattery >= 0) {
                battery = sLastKnownBattery;
            }

            obj.put("deviceName", name);
            obj.put("deviceAddress", address.isEmpty() ? "C8:1F:E8:E2:CC:9D" : address);
            obj.put("batteryLevel", battery); // If phone doesn't report battery over A2DP, value is -1
            obj.put("codec", "AAC");
            obj.put("sampleRate", "48 kHz");
            obj.put("bitDepth", "24-Bit");
            obj.put("channels", "双声道立体声");
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

                    // Update live track title, artist and album vinyl cover
                    updateFloatingViewContent();

                    // Continuous smooth rotating vinyl animation
                    ImageView vinylIcon = mFloatingView.findViewById(R.id.floatingVinylIcon);
                    RotateAnimation rotate = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                    rotate.setDuration(7000);
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

                    mWindowManager.addView(mFloatingView, params);
                    mIsFloatingShown = true;
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
