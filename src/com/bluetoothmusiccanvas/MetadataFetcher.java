package com.bluetoothmusiccanvas;

import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

public class MetadataFetcher {
    private static final String TAG = "BTMusicFetcher";

    public static class CachedMeta {
        public final String coverUrl;
        public final String lyricsLrc;
        public final String lyricSource;
        public final long timestamp;

        public CachedMeta(String coverUrl, String lyricsLrc, String lyricSource) {
            this.coverUrl = coverUrl;
            this.lyricsLrc = lyricsLrc;
            this.lyricSource = lyricSource;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final int MAX_CACHE_ENTRIES = 50;
    private static final long COOLDOWN_MS = 120_000L;
    private static final long NEGATIVE_CACHE_TTL_MS = 180_000L;

    private static final Map<String, CachedMeta> sCache = Collections.synchronizedMap(
        new LinkedHashMap<String, CachedMeta>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedMeta> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        }
    );

    private static final Map<String, Long> sNegativeCache = Collections.synchronizedMap(
        new LinkedHashMap<String, Long>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        }
    );

    private static final ConcurrentHashMap<String, Long> sProviderCooldownUntil = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> sThreadHadNetworkError = new ThreadLocal<>();

    private static final ExecutorService sThreadPool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "MetaFetchPool-" + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private static final Map<String, List<Callback>> sInFlightRequests = new HashMap<>();

    public interface Callback {
        void onResult(String coverUrl, String lyricsLrc, String lyricSource);
    }

    public static String buildCacheKey(String rawTitle, String rawArtist, String rawAlbum) {
        return SongIdentity.buildCacheKey(rawTitle, rawArtist, rawAlbum);
    }

    public static String normalizeTitleForCache(String s) {
        return SongIdentity.normalizeForCache(s);
    }

    public static String normalizeArtistForCache(String s) {
        return SongIdentity.normalizeForCache(s);
    }

    public static CachedMeta getCached(String rawTitle, String rawArtist) {
        return getCached(rawTitle, rawArtist, "");
    }

    public static CachedMeta getCached(String rawTitle, String rawArtist, String rawAlbum) {
        String key = SongIdentity.buildCacheKey(rawTitle, rawArtist, rawAlbum);
        CachedMeta m = sCache.get(key);
        if (m != null) return m;
        // If album was enriched after an initial empty-album fetch for the same song,
        // allow inheriting the generic (empty-album) entry without cross-pollinating between distinct albums.
        if (rawAlbum != null && !rawAlbum.trim().isEmpty()) {
            String genericKey = SongIdentity.buildCacheKey(rawTitle, rawArtist, "");
            CachedMeta genericMeta = sCache.get(genericKey);
            if (genericMeta != null) {
                sCache.put(key, genericMeta);
                return genericMeta;
            }
        }
        return null;
    }

    public static void putInCache(String rawTitle, String rawArtist, String rawAlbum, CachedMeta meta) {
        if (meta == null) return;
        String key = SongIdentity.buildCacheKey(rawTitle, rawArtist, rawAlbum);
        sCache.put(key, meta);
        sNegativeCache.remove(key);
    }

    /** Marks a provider as degraded (e.g. HTTP 429/403/5xx or anti-bot JSON error) for 120s cooldown. */
    public static void markProviderDegraded(String provider, String reason) {
        if (provider == null || provider.trim().isEmpty()) return;
        String key = provider.trim().toLowerCase(Locale.ROOT);
        sProviderCooldownUntil.put(key, System.currentTimeMillis() + COOLDOWN_MS);
        Log.w(TAG, "Provider [" + key + "] degraded (" + reason + "), entering 120s cooldown");
    }

    public static boolean isProviderInCooldown(String provider) {
        if (provider == null || provider.trim().isEmpty()) return false;
        String key = provider.trim().toLowerCase(Locale.ROOT);
        Long until = sProviderCooldownUntil.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            sProviderCooldownUntil.remove(key, until);
            return false;
        }
        return true;
    }

    public static void resetProviderCooldowns() {
        sProviderCooldownUntil.clear();
    }

    public static boolean isRecentlyMissed(String rawTitle, String rawArtist, String rawAlbum) {
        String key = SongIdentity.buildCacheKey(rawTitle, rawArtist, rawAlbum);
        if (key.isEmpty()) return false;
        Long ts = sNegativeCache.get(key);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts >= NEGATIVE_CACHE_TTL_MS) {
            sNegativeCache.remove(key);
            return false;
        }
        return true;
    }

    public static void recordNegativeCache(String rawTitle, String rawArtist, String rawAlbum) {
        String key = SongIdentity.buildCacheKey(rawTitle, rawArtist, rawAlbum);
        if (!key.isEmpty()) {
            sNegativeCache.put(key, System.currentTimeMillis());
        }
    }

    /** Give ambiguous "artist/song" metadata a chance to deliver the next lyric
     * before looking up the provisional title. Real multi-artist credits still
     * get their normal lookup when the title remains stable. */
    public static void fetchForTrack(final long generation, final String title, final String artist,
                                     final String album, final long duration, final Callback callback) {
        MediaStateStore store = MediaStateStore.getInstance();
        MediaStateStore.MediaSnapshot track = store.getSnapshot();
        if (track.format == MetadataNormalizer.MetadataFormat.STANDARD &&
                MetadataNormalizer.parseCandidate(artist, album) != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                MediaStateStore.MediaSnapshot current = store.getSnapshot();
                if (current.sessionEpoch == track.sessionEpoch && current.playbackGeneration == generation
                        && current.title.equals(title) && current.artist.equals(artist)) {
                    fetch(title, artist, album, duration, callback);
                }
            }, 8000);
        } else {
            fetch(title, artist, album, duration, callback);
        }
    }

    public static void fetch(final String rawTitle, final String rawArtist, final Callback callback) {
        fetch(rawTitle, rawArtist, "", callback);
    }

    public static void fetchSpecificLyric(final String source, final String rawTitle, final String rawArtist, final String rawAlbum, final Callback callback) {
        fetchSpecificLyric(source, rawTitle, rawArtist, rawAlbum, 0, callback);
    }

    public static void fetchSpecificLyric(final String source, final String rawTitle, final String rawArtist, final String rawAlbum, final long duration, final Callback callback) {
        final String title = cleanTitle(rawTitle);
        final String fullArtist = (rawArtist != null) ? rawArtist.trim() : "";
        final String album = (rawAlbum != null) ? rawAlbum.trim() : "";

        sThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                String lyricsLrc = null;
                String actualSource = "";
                try {
                    if ("netease".equalsIgnoreCase(source)) {
                        String[] res = fetchFromNetEase(title, fullArtist, album, duration);
                        lyricsLrc = res[1];
                        actualSource = "网易云音乐";
                    } else if ("qq".equalsIgnoreCase(source)) {
                        String[] res = fetchFromQQ(title, fullArtist, duration);
                        lyricsLrc = res[1];
                        actualSource = "QQ音乐";
                    } else if ("kugou".equalsIgnoreCase(source)) {
                        String[] res = fetchFromKugou(title, fullArtist, false, true, duration);
                        lyricsLrc = res[1];
                        actualSource = "酷狗音乐";
                    } else if ("lrclib".equalsIgnoreCase(source)) {
                        lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist, album, duration);
                        actualSource = "LRCLIB";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "fetchSpecificLyric error: " + e.getMessage());
                }
                if (callback != null) {
                    callback.onResult(null, lyricsLrc, actualSource);
                }
            }
        });
    }

    public static boolean isValidCoverUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        if (u.isEmpty()) return false;
        // 过滤酷狗/各平台默认占位白底人像图 (default_singer.jpg) 及歌手纯头像占位图
        if (u.contains("default_singer") || u.contains("default_album") || u.contains("default_cover")
                || u.contains("default_avatar") || u.contains("share2014/default")
                || u.contains("singerimg.kugou.com") || u.contains("nocover") || u.contains("no_cover")) {
            return false;
        }
        return true;
    }

    public static boolean isVideoTrack(String rawTitle, String rawArtist, String rawAlbum, long duration) {
        MediaStateStore.MediaSnapshot snap = MediaStateStore.getInstance().getSnapshot();
        if (snap != null && rawTitle != null && snap.title.equalsIgnoreCase(rawTitle.trim())
                && snap.format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED) {
            return false;
        }
        return MediaClassifier.classify(rawTitle, rawArtist, rawAlbum, duration, MetadataNormalizer.MetadataFormat.UNKNOWN).isVideo();
    }

    public static void fetch(final String rawTitle, final String rawArtist, final String rawAlbum, final Callback callback) {
        fetch(rawTitle, rawArtist, rawAlbum, 0, callback);
    }

    public static void fetch(final String rawTitle, final String rawArtist, final String rawAlbum, final long duration, final Callback callback) {
        if (isVideoTrack(rawTitle, rawArtist, rawAlbum, duration)) {
            Log.i(TAG, "Skipping fetch for video track: [" + rawTitle + "]");
            if (callback != null) callback.onResult(null, null, "");
            return;
        }

        final String title = cleanTitle(rawTitle);
        final String fullArtist = (rawArtist != null) ? rawArtist.trim() : "";
        final String album = (rawAlbum != null) ? rawAlbum.trim() : "";
        final String key = buildCacheKey(rawTitle, rawArtist, rawAlbum);

        if (title.isEmpty() || title.contains("纯音乐") || title.contains("请欣赏") || title.equals("未知歌曲") || title.equals("Not Provided")) {
            Log.i(TAG, "Skipping fetch for placeholder title: [" + title + "]");
            if (callback != null) callback.onResult(null, null, "");
            return;
        }

        CachedMeta cached = getCached(rawTitle, rawArtist, rawAlbum);
        if (cached != null) {
            Log.i(TAG, "Instant cache hit for key: [" + key + "], source: " + cached.lyricSource);
            if (callback != null) callback.onResult(cached.coverUrl, cached.lyricsLrc, cached.lyricSource);
            return;
        }

        if (isRecentlyMissed(rawTitle, rawArtist, rawAlbum)) {
            Log.i(TAG, "Negative cache hit for key: [" + key + "], skipping repeat fetch");
            if (callback != null) callback.onResult(null, null, "");
            return;
        }

        // Network request deduplication in-flight using canonical track key
        final String inFlightKey = key;
        synchronized (sInFlightRequests) {
            List<Callback> list = sInFlightRequests.get(inFlightKey);
            if (list != null) {
                if (callback != null) list.add(callback);
                return;
            }
            list = new ArrayList<>();
            if (callback != null) list.add(callback);
            sInFlightRequests.put(inFlightKey, list);
        }

        sThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Starting metadata fetch for title: [" + title + "], artist: [" + fullArtist + "], album: [" + album + "]");
                sThreadHadNetworkError.set(Boolean.FALSE);

                String coverUrl = null;
                String lyricsLrc = null;
                String lyricSource = "";
                boolean skippedOrDegraded = false;

                // 1. 优先从网易云音乐检索高清封面与同步歌词 (带多候选评分机制及时长校验)
                if (!isProviderInCooldown("netease")) {
                    try {
                        String[] neteaseRes = fetchFromNetEase(title, fullArtist, album, duration);
                        if (neteaseRes[0] != null && !neteaseRes[0].isEmpty()) {
                            coverUrl = neteaseRes[0];
                        }
                        if (neteaseRes[1] != null && !neteaseRes[1].isEmpty()) {
                            lyricsLrc = neteaseRes[1];
                            lyricSource = "网易云音乐";
                        }
                        if (isProviderInCooldown("netease")) skippedOrDegraded = true;
                    } catch (Exception e) {
                        Log.w(TAG, "NetEase search failed: " + e.getMessage());
                        skippedOrDegraded = true;
                    }
                } else {
                    Log.i(TAG, "Skipping NetEase fetch (provider in cooldown)");
                    skippedOrDegraded = true;
                }

                // 2. 备用封面：iTunes Search 公开 API (600x600 高保真，带候选评分及时长校验)
                if (coverUrl == null) {
                    if (!isProviderInCooldown("itunes")) {
                        try {
                            coverUrl = fetchCoverFromiTunes(title, fullArtist, album, duration);
                            if (isProviderInCooldown("itunes")) skippedOrDegraded = true;
                        } catch (Exception e) {
                            Log.w(TAG, "iTunes cover search failed: " + e.getMessage());
                            skippedOrDegraded = true;
                        }
                    } else {
                        Log.i(TAG, "Skipping iTunes cover fetch (provider in cooldown)");
                        skippedOrDegraded = true;
                    }
                }

                // 3. 备用歌词：QQ音乐 / 酷狗音乐 / LRCLIB
                if (lyricsLrc == null) {
                    boolean hasChinese = containsCjk(title, false);

                    if (hasChinese) {
                        // 中文歌曲优先备选 QQ 音乐，其次酷狗音乐，最后 LRCLIB
                        if (!isProviderInCooldown("qq")) {
                            try {
                                String[] qqRes = fetchFromQQ(title, fullArtist, duration);
                                if (coverUrl == null && isValidCoverUrl(qqRes[0])) {
                                    coverUrl = qqRes[0];
                                }
                                if (qqRes[1] != null && !qqRes[1].isEmpty()) {
                                    lyricsLrc = qqRes[1];
                                    lyricSource = "QQ音乐";
                                }
                                if (isProviderInCooldown("qq")) skippedOrDegraded = true;
                            } catch (Exception e) {
                                Log.w(TAG, "QQ search failed: " + e.getMessage());
                                skippedOrDegraded = true;
                            }
                        } else {
                            skippedOrDegraded = true;
                        }

                        if (lyricsLrc == null) {
                            if (!isProviderInCooldown("kugou")) {
                                try {
                                    String[] kugouRes = fetchFromKugou(title, fullArtist, coverUrl == null, true, duration);
                                    if (coverUrl == null && kugouRes[0] != null) {
                                        coverUrl = kugouRes[0];
                                    }
                                    if (kugouRes[1] != null && !kugouRes[1].isEmpty()) {
                                        lyricsLrc = kugouRes[1];
                                        lyricSource = "酷狗音乐";
                                    }
                                    if (isProviderInCooldown("kugou")) skippedOrDegraded = true;
                                } catch (Exception e) {
                                    Log.w(TAG, "Kugou search failed: " + e.getMessage());
                                    skippedOrDegraded = true;
                                }
                            } else {
                                skippedOrDegraded = true;
                            }
                        }

                        if (lyricsLrc == null) {
                            if (!isProviderInCooldown("lrclib")) {
                                try {
                                    lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist, album, duration);
                                    if (lyricsLrc != null && !lyricsLrc.isEmpty()) {
                                        lyricSource = "LRCLIB";
                                    }
                                    if (isProviderInCooldown("lrclib")) skippedOrDegraded = true;
                                } catch (Exception ignored) {
                                    skippedOrDegraded = true;
                                }
                            } else {
                                skippedOrDegraded = true;
                            }
                        }
                    } else {
                        // 外文歌曲优先备选公开 API LRCLIB -> QQ音乐 -> 酷狗音乐
                        if (!isProviderInCooldown("lrclib")) {
                            try {
                                lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist, album, duration);
                                if (lyricsLrc != null && !lyricsLrc.isEmpty()) {
                                    lyricSource = "LRCLIB";
                                }
                                if (isProviderInCooldown("lrclib")) skippedOrDegraded = true;
                            } catch (Exception ignored) {
                                skippedOrDegraded = true;
                            }
                        } else {
                            skippedOrDegraded = true;
                        }

                        if (lyricsLrc == null) {
                            if (!isProviderInCooldown("qq")) {
                                try {
                                    String[] qqRes = fetchFromQQ(title, fullArtist, duration);
                                    if (coverUrl == null && isValidCoverUrl(qqRes[0])) {
                                        coverUrl = qqRes[0];
                                    }
                                    if (qqRes[1] != null && !qqRes[1].isEmpty()) {
                                        lyricsLrc = qqRes[1];
                                        lyricSource = "QQ音乐";
                                    }
                                    if (isProviderInCooldown("qq")) skippedOrDegraded = true;
                                } catch (Exception ignored) {
                                    skippedOrDegraded = true;
                                }
                            } else {
                                skippedOrDegraded = true;
                            }
                        }

                        if (lyricsLrc == null) {
                            if (!isProviderInCooldown("kugou")) {
                                try {
                                    String[] kugouRes = fetchFromKugou(title, fullArtist, coverUrl == null, true, duration);
                                    if (coverUrl == null && kugouRes[0] != null) {
                                        coverUrl = kugouRes[0];
                                    }
                                    if (kugouRes[1] != null && !kugouRes[1].isEmpty()) {
                                        lyricsLrc = kugouRes[1];
                                        lyricSource = "酷狗音乐";
                                    }
                                    if (isProviderInCooldown("kugou")) skippedOrDegraded = true;
                                } catch (Exception ignored) {
                                    skippedOrDegraded = true;
                                }
                            } else {
                                skippedOrDegraded = true;
                            }
                        }
                    }
                }

                // 4. 若封面仍缺失，从酷狗兜底
                if (coverUrl == null) {
                    if (!isProviderInCooldown("kugou")) {
                        try {
                            String[] kugouRes = fetchFromKugou(title, fullArtist, true, false, duration);
                            if (kugouRes[0] != null) {
                                coverUrl = kugouRes[0];
                            }
                            if (isProviderInCooldown("kugou")) skippedOrDegraded = true;
                        } catch (Exception ignored) {
                            skippedOrDegraded = true;
                        }
                    } else {
                        skippedOrDegraded = true;
                    }
                }

                boolean hadNetErr = Boolean.TRUE.equals(sThreadHadNetworkError.get());
                sThreadHadNetworkError.remove();

                if (!key.isEmpty() && (coverUrl != null || lyricsLrc != null)) {
                    CachedMeta meta = new CachedMeta(coverUrl, lyricsLrc, lyricSource);
                    putInCache(rawTitle, rawArtist, rawAlbum, meta);
                } else if (!key.isEmpty() && !skippedOrDegraded && !hadNetErr) {
                    recordNegativeCache(rawTitle, rawArtist, rawAlbum);
                }

                Log.i(TAG, "Metadata fetch complete. Cover found=" + (coverUrl != null) + ", Lyrics found=" + (lyricsLrc != null) + " (" + lyricSource + ")");

                List<Callback> callbacks;
                synchronized (sInFlightRequests) {
                    callbacks = sInFlightRequests.remove(inFlightKey);
                }
                if (callbacks != null) {
                    for (Callback cb : callbacks) {
                        try {
                            cb.onResult(coverUrl, lyricsLrc, lyricSource);
                        } catch (Exception e) {
                            Log.w(TAG, "Callback error: " + e.getMessage());
                        }
                    }
                }
            }
        });
    }

    private static String cleanTitle(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 1. 移除首尾包裹的书名号、引号等符号: 《...》, <...>, "...", '...', “...”, 「...」
        t = t.replaceAll("^[《<“\"'「『\\s]+|[》>”\"'」』\\s]+$", "").trim();
        // 2. 剥离副标题或常见标签，如 " - 伴奏", " - Instrumental", " - Live" 等
        int dash = t.indexOf(" - ");
        if (dash > 0) {
            String part2 = t.substring(dash + 3).trim().toLowerCase();
            if (part2.contains("伴奏") || part2.contains("instrumental") || part2.contains("live") || part2.contains("remix") || part2.contains("karaoke")) {
                t = t.substring(0, dash).trim();
            }
        }
        // 3. 剥离尾部括号中的版本说明，如 (Live), (Remix), [伴奏], （官方版）等；保留括号开头的名称
        t = t.replaceAll("(?<=\\S)\\s*[\\(（【\\[].*$", "").trim();
        return t;
    }

    private static String cleanArtist(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("^[《<“\"'「『\\s]+|[》>”\"'」』\\s]+$", "").trim();
        clean = clean.replaceAll("(?<=\\S)\\s*[\\(（【\\[].*$", "").trim();
        // 提取主要艺术家：按斜杠(/)、逗号(,)、顿号(、)、与符号(&)、feat.、ft.、with 分割
        String[] parts = clean.split("\\s*[/,、&]\\s*|\\s+(?i:feat\\.|ft\\.|with)\\s+");
        if (parts.length > 0 && !parts[0].trim().isEmpty()) {
            return parts[0].trim();
        }
        return clean;
    }

    private static List<String> extractAllArtists(String s) {
        List<String> list = new ArrayList<>();
        if (s == null) return list;
        String clean = s.replaceAll("^[《<“\"'「『\\s]+|[》>”\"'」』\\s]+$", "").trim();
        clean = clean.replaceAll("(?<=\\S)\\s*[\\(（【\\[].*$", "").trim();
        String[] parts = clean.split("\\s*[/,、&]\\s*|\\s+(?i:feat\\.|ft\\.|with)\\s+");
        for (String p : parts) {
            String item = p.trim();
            if (!item.isEmpty() && !list.contains(item)) {
                list.add(item);
            }
        }
        return list;
    }

    private static int scoreCandidate(String candidateTitle, List<String> candidateArtists, String candidateAlbum, long candidateDurationMs,
                                      String reqTitle, String reqArtist, String reqAlbum, long reqDurationMs) {
        int score = 0;
        String cTitle = (candidateTitle != null) ? candidateTitle.toLowerCase().trim() : "";
        String rTitle = (reqTitle != null) ? reqTitle.toLowerCase().trim() : "";

        // 去除空格与标点后的纯净字符串对比
        String cClean = cTitle.replaceAll("[《》<>\"'“”、,，\\s]", "");
        String rClean = rTitle.replaceAll("[《》<>\"'“”、,，\\s]", "");

        // 1. 歌名匹配（硬性前提：歌名完全不沾边的候选直接淘汰，严禁仅靠歌手名或时长凑分命中）
        boolean titleMatched = false;
        if (!cClean.isEmpty() && !rClean.isEmpty()) {
            if (cClean.equals(rClean)) {
                score += 160;
                titleMatched = true;
            } else if (cClean.contains(rClean) && rClean.length() >= 2) {
                score += 85;
                titleMatched = true;
            } else if (rClean.contains(cClean)) {
                // 候选歌名被包含在请求名中：必须占比足够长，防止长句视频将几个字词误认成歌名
                if (cClean.length() >= 4 && (float) cClean.length() / rClean.length() >= 0.5f) {
                    score += 85;
                    titleMatched = true;
                }
            } else {
                String cBase = cTitle.split("[:(（【\\[]")[0].trim();
                String rBase = rTitle.split("[:(（【\\[]")[0].trim();
                if (!cBase.isEmpty() && !rBase.isEmpty() && (cBase.equals(rBase) || (cBase.contains(rBase) && rBase.length() >= 4))) {
                    score += 50;
                    titleMatched = true;
                }
            }
        }
        if (!titleMatched) {
            return -999;
        }

        // 2. 避免非用户请求的纯伴奏、卡拉OK或钢琴版
        boolean reqIsInst = rTitle.contains("伴奏") || rTitle.contains("instrumental") || rTitle.contains("纯音乐") || rTitle.contains("karaoke");
        boolean candIsInst = cTitle.contains("伴奏") || cTitle.contains("instrumental") || cTitle.contains("karaoke") || cTitle.contains("piano version");
        if (!reqIsInst && candIsInst) {
            score -= 75;
        }

        // 3. 避免翻唱（若未请求翻唱）
        boolean reqIsCover = rTitle.contains("翻唱") || rTitle.contains("cover");
        boolean candIsCover = cTitle.contains("翻唱") || cTitle.contains("cover");
        if (!reqIsCover && candIsCover) {
            score -= 60;
        }

        // 4. 原唱录音室版微弱优先于现场版（Live）
        boolean reqIsLive = rTitle.contains("live") || rTitle.contains("现场") || rTitle.contains("演唱会");
        boolean candIsLive = cTitle.contains("live") || cTitle.contains("现场") || cTitle.contains("演唱会");
        if (!reqIsLive && candIsLive) {
            score -= 20;
        }

        // 5. 古典音乐乐章与速度标记精确匹配（防止多乐章选错乐章）
        String[] classicalTokens = new String[]{
                ": i.", ": ii.", ": iii.", ": iv.", "allegro", "andante", "adagio", "presto", "largo", "vivace", "menuetto", "rondo", "rondeau"
        };
        for (String tok : classicalTokens) {
            if (rTitle.contains(tok)) {
                if (cTitle.contains(tok)) {
                    score += 40;
                } else {
                    score -= 60; // 同一作品中的不同乐章，大幅降分
                }
            }
        }

        // 6. 歌手匹配（兼容多歌手合作场景）
        boolean artistMatched = false;
        if (reqArtist != null && !reqArtist.trim().isEmpty()) {
            List<String> reqArtistList = extractAllArtists(reqArtist);
            if (candidateArtists != null && !candidateArtists.isEmpty()) {
                for (String ca : candidateArtists) {
                    if (ca == null) continue;
                    String caLow = ca.toLowerCase().trim();
                    if (caLow.isEmpty()) continue;
                    for (String ra : reqArtistList) {
                        String raLow = ra.toLowerCase().trim();
                        if (caLow.equals(raLow)) {
                            score += 90;
                            artistMatched = true;
                            break;
                        } else if (caLow.contains(raLow) || raLow.contains(caLow)) {
                            score += 65;
                            artistMatched = true;
                            break;
                        }
                    }
                    if (artistMatched) break;
                }
            }
            if (!artistMatched) {
                // 若候选曲目不含任何请求中的歌手名，严厉惩罚
                score -= 80;
            }
        }

        // 7. 专辑匹配
        if (reqAlbum != null && !reqAlbum.trim().isEmpty()) {
            String rAlb = reqAlbum.toLowerCase().trim();
            String cAlb = (candidateAlbum != null) ? candidateAlbum.toLowerCase().trim() : "";
            if (!cAlb.isEmpty()) {
                if (cAlb.equals(rAlb)) {
                    score += 80;
                } else if (cAlb.contains(rAlb) || rAlb.contains(cAlb)) {
                    score += 45;
                }
            }
        }

        // 8. 时长精准匹配校验（Duration Delta Check）：识别视频与音乐的终极利器
        if (reqDurationMs > 15000 && candidateDurationMs > 15000) {
            long diffSec = Math.abs(reqDurationMs - candidateDurationMs) / 1000;
            if (diffSec <= 4) {
                score += 70; // 几乎完全吻合（正规音乐同一版本）
            } else if (diffSec <= 22) {
                score += 35; // 正常误差区间（可能为MV带片头片尾、Radio/Album Edit）
            } else if (diffSec <= 40) {
                score -= 40; // 存在较明显时长出入
            } else {
                // 时长相差超过40秒：严重时长不符！
                if (diffSec > 75) {
                    score -= 300; // 巨大偏差（如十几分钟的视频解说 vs 几分钟的歌），直接打入深渊
                } else {
                    score -= 130;
                }
            }

            // 严厉的硬性安全门禁：如果时长相差超过45秒且歌手未完全匹配，直接否决该候选
            if (diffSec > 45 && !artistMatched && (reqAlbum == null || reqAlbum.isEmpty())) {
                score = -999;
            }
        }

        return score;
    }

    private static boolean containsCjk(String text, boolean includeKanaAndHangul) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
            if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                return true;
            }
            if (includeKanaAndHangul && ((c >= '\u3040' && c <= '\u30ff') || (c >= '\uac00' && c <= '\ud7af'))) {
                return true;
            }
        }
        return false;
    }

    private static String queryItunesStore(String queryEncoded, String country, String title, String artist, String album, long duration) throws Exception {
        String urlStr = "https://itunes.apple.com/search?term=" + queryEncoded + "&entity=song&limit=10"
                + (country != null && !country.isEmpty() ? "&country=" + country : "");
        HttpResult res = httpGetResponse(urlStr, null, null);
        if (res.isServerOrRateLimit()) {
            markProviderDegraded("itunes", "HTTP " + res.code);
            return null;
        }
        if (res.code != 200 || res.body == null) return null;

        JSONObject obj = new JSONObject(res.body);
        JSONArray results = obj.optJSONArray("results");
        if (results == null || results.length() == 0) return null;

        JSONObject bestItem = null;
        int bestScore = -9999;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.getJSONObject(i);
            String t = item.optString("trackName", "");
            String a = item.optString("artistName", "");
            String alb = item.optString("collectionName", "");
            long cDur = item.optLong("trackTimeMillis", 0);
            int score = scoreCandidate(t, Collections.singletonList(a), alb, cDur, title, artist, album, duration);
            if (score > bestScore) {
                bestScore = score;
                bestItem = item;
            }
        }
        if (bestItem != null && bestScore >= 50) {
            String art = bestItem.optString("artworkUrl100", null);
            if (art != null && !art.isEmpty()) {
                String hdArt = art.replace("100x100bb", "600x600bb");
                Log.i(TAG, "iTunes cover found (country=" + (country != null ? country : "global") + ", score " + bestScore + "): " + hdArt);
                return hdArt;
            }
        }
        return null;
    }

    private static String fetchCoverFromiTunes(String title, String artist, String album, long duration) {
        try {
            String firstArtist = cleanArtist(artist);
            String q = URLEncoder.encode((title + " " + firstArtist).trim(), "UTF-8");
            boolean hasCjk = containsCjk(title + " " + artist, true);
            if (hasCjk) {
                String hkCover = queryItunesStore(q, "HK", title, artist, album, duration);
                if (hkCover != null || isProviderInCooldown("itunes")) {
                    return hkCover;
                }
            }
            return queryItunesStore(q, null, title, artist, album, duration);
        } catch (Exception e) {
            Log.w(TAG, "fetchCoverFromiTunes error: " + e.getMessage());
        }
        return null;
    }

    private static String fetchLyricsFromLRCLIB(String title, String artist, String album, long duration) {
        final String lrclibUA = "BluetoothMusicCanvas/1.1";
        String plainFallback = null;
        try {
            String firstArtist = cleanArtist(artist);
            String qTitle = URLEncoder.encode(title.trim(), "UTF-8");

            // Step A: Direct exact lookup when artist is known
            if (!firstArtist.isEmpty()) {
                String qArtist = URLEncoder.encode(firstArtist, "UTF-8");
                String urlStr = "https://lrclib.net/api/get?track_name=" + qTitle + "&artist_name=" + qArtist;
                HttpResult getRes = httpGetResponse(urlStr, null, lrclibUA);
                if (getRes.isServerOrRateLimit()) {
                    markProviderDegraded("lrclib", "HTTP " + getRes.code);
                    return null;
                }
                if (getRes.code == 200 && getRes.body != null) {
                    JSONObject obj = new JSONObject(getRes.body);
                    if (!obj.optBoolean("instrumental", false)) {
                        long itemDurMs = Math.round(obj.optDouble("duration", 0) * 1000);
                        boolean durOk = (duration <= 15000 || itemDurMs <= 15000 || Math.abs(duration - itemDurMs) <= 25000);
                        if (durOk) {
                            String synced = obj.optString("syncedLyrics", "");
                            if (!synced.isEmpty()) {
                                Log.i(TAG, "LRCLIB exact synced lyrics found (" + synced.length() + " chars)");
                                return synced;
                            }
                            String plain = obj.optString("plainLyrics", "");
                            if (!plain.isEmpty()) {
                                plainFallback = plain;
                            }
                        }
                    }
                }
            }

            // Step B: Official LRCLIB /api/search fallback with candidate scoring
            String searchUrl = !firstArtist.isEmpty()
                    ? "https://lrclib.net/api/search?track_name=" + qTitle + "&artist_name=" + URLEncoder.encode(firstArtist, "UTF-8")
                    : "https://lrclib.net/api/search?q=" + qTitle;
            HttpResult searchRes = httpGetResponse(searchUrl, null, lrclibUA);
            if (searchRes.isServerOrRateLimit()) {
                markProviderDegraded("lrclib", "search HTTP " + searchRes.code);
                return plainFallback;
            }
            if (searchRes.code == 200 && searchRes.body != null) {
                JSONArray arr = new JSONArray(searchRes.body);
                String lowerReq = title.toLowerCase(Locale.ROOT);
                boolean reqIsInst = lowerReq.contains("伴奏") || lowerReq.contains("instrumental")
                        || lowerReq.contains("纯音乐") || lowerReq.contains("karaoke");

                String bestLyrics = null;
                int bestScore = -9999;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (item.optBoolean("instrumental", false) && !reqIsInst) continue;
                    String synced = item.optString("syncedLyrics", "");
                    String plain = item.optString("plainLyrics", "");
                    if (synced.isEmpty() && plain.isEmpty()) continue;

                    String tName = item.optString("trackName", "");
                    String aName = item.optString("artistName", "");
                    String albName = item.optString("albumName", "");
                    long cDur = Math.round(item.optDouble("duration", 0) * 1000);

                    int sc = scoreCandidate(tName, Collections.singletonList(aName), albName, cDur, title, artist, album, duration);
                    if (!synced.isEmpty()) {
                        sc += 25;
                    }
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestLyrics = !synced.isEmpty() ? synced : plain;
                    }
                }
                if (bestLyrics != null && bestScore >= 50) {
                    Log.i(TAG, "LRCLIB search lyrics matched (score " + bestScore + ", " + bestLyrics.length() + " chars)");
                    return bestLyrics;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchLyricsFromLRCLIB error: " + e.getMessage());
        }
        return plainFallback;
    }

    private static String[] fetchFromKugou(String title, String artist, boolean needCover, boolean needLyric, long duration) {
        String[] out = new String[2]; // [0]=cover, [1]=lyrics
        try {
            String firstArtist = cleanArtist(artist);
            String qText = (!firstArtist.isEmpty() ? (title + " " + firstArtist) : title).trim();
            String q = URLEncoder.encode(qText, "UTF-8");
            String searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=" + q + "&page=1&pagesize=5";
            HttpResult sRes = httpGetResponse(searchUrl, null, null);
            if (sRes.isBlockedOrError()) {
                markProviderDegraded("kugou", "HTTP " + sRes.code);
                return out;
            }
            if (sRes.code != 200 || sRes.body == null) return out;

            JSONObject sObj = new JSONObject(sRes.body);
            int errcode = sObj.optInt("errcode", 0);
            if (errcode != 0) {
                markProviderDegraded("kugou", "errcode=" + errcode);
                return out;
            }
            JSONObject data = sObj.optJSONObject("data");
            if (data == null) return out;
            JSONArray info = data.optJSONArray("info");
            if (info == null || info.length() == 0) return out;

            JSONObject bestSong = null;
            int bestScore = -9999;
            for (int i = 0; i < info.length(); i++) {
                JSONObject song = info.getJSONObject(i);
                String sName = song.optString("songname", "");
                String singer = song.optString("singername", "");
                String sAlb = song.optString("album_name", "");
                long cDurSec = song.optLong("duration", 0);
                if (cDurSec == 0) cDurSec = song.optLong("Duration", 0);
                long cDur = cDurSec * 1000;
                int sc = scoreCandidate(sName, Collections.singletonList(singer), sAlb, cDur, title, artist, "", duration);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestSong = song;
                }
            }

            if (bestSong == null || bestScore < 50) {
                return out; // 低置信度或严重时长不匹配，果断放弃错误匹配
            }
            String hash = bestSong.optString("hash", "");
            if (hash.isEmpty()) return out;

            // 1. Cover
            if (needCover) {
                try {
                    String dUrl = "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash;
                    HttpResult dRes = httpGetResponse(dUrl, null, null);
                    if (dRes.code == 200 && dRes.body != null) {
                        JSONObject dObj = new JSONObject(dRes.body);
                        String img = dObj.optString("imgUrl", "");
                        if (!img.isEmpty()) {
                            String resolvedImg = img.replace("{size}", "400");
                            if (isValidCoverUrl(resolvedImg)) {
                                out[0] = resolvedImg;
                                Log.i(TAG, "Kugou cover found: " + out[0]);
                            } else {
                                Log.i(TAG, "Rejected placeholder/avatar Kugou cover: " + resolvedImg);
                            }
                        }
                    }
                } catch (Exception ec) {
                    Log.w(TAG, "Kugou cover error: " + ec.getMessage());
                }
            }

            // 2. Lyrics (HTTPS krcs.kugou.com, prefer KRC for bilingual/romaji, fallback to LRC)
            if (needLyric) {
                try {
                    String lSearchUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=" + q + "&duration=0&hash=" + hash;
                    HttpResult lSearchRes = httpGetResponse(lSearchUrl, null, null);
                    if (lSearchRes.isBlockedOrError()) {
                        markProviderDegraded("kugou", "krcs HTTP " + lSearchRes.code);
                        return out;
                    }
                    if (lSearchRes.code == 200 && lSearchRes.body != null) {
                        JSONObject lsObj = new JSONObject(lSearchRes.body);
                        JSONArray candidates = lsObj.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            JSONObject chosenCand = null;
                            int scanLimit = Math.min(3, candidates.length());
                            for (int ci = 0; ci < scanLimit; ci++) {
                                JSONObject c = candidates.optJSONObject(ci);
                                if (c == null) continue;
                                String cid = c.optString("id", "");
                                String ckey = c.optString("accesskey", "");
                                if (!cid.isEmpty() && !ckey.isEmpty() && c.optInt("krctype", 0) == 1) {
                                    chosenCand = c;
                                    break;
                                }
                            }
                            if (chosenCand == null) {
                                chosenCand = candidates.getJSONObject(0);
                            }
                            String candId = chosenCand.optString("id", "");
                            String accessKey = chosenCand.optString("accesskey", "");
                            if (!candId.isEmpty() && !accessKey.isEmpty()) {
                                String krcUrl = "https://krcs.kugou.com/download?ver=1&client=mobi&id=" + candId + "&accesskey=" + accessKey + "&fmt=krc&charset=utf8";
                                HttpResult krcRes = httpGetResponse(krcUrl, null, null);
                                if (krcRes.isBlockedOrError()) {
                                    markProviderDegraded("kugou", "krcs krc download HTTP " + krcRes.code);
                                    return out;
                                }
                                if (krcRes.code == 200 && krcRes.body != null) {
                                    JSONObject krcObj = new JSONObject(krcRes.body);
                                    String b64Krc = krcObj.optString("content", "");
                                    if (!b64Krc.isEmpty()) {
                                        String parsedKrc = decodeKrcBase64ToLrc(b64Krc);
                                        if (parsedKrc != null && !parsedKrc.isEmpty()) {
                                            out[1] = parsedKrc;
                                            Log.i(TAG, "Kugou KRC lyrics decoded (" + out[1].length() + " chars)");
                                        }
                                    }
                                }

                                if (out[1] == null || out[1].isEmpty()) {
                                    String dlUrl = "https://krcs.kugou.com/download?ver=1&client=mobi&id=" + candId + "&accesskey=" + accessKey + "&fmt=lrc&charset=utf8";
                                    HttpResult dlRes = httpGetResponse(dlUrl, null, null);
                                    if (dlRes.isBlockedOrError()) {
                                        markProviderDegraded("kugou", "krcs lrc download HTTP " + dlRes.code);
                                        return out;
                                    }
                                    if (dlRes.code == 200 && dlRes.body != null) {
                                        JSONObject dlObj = new JSONObject(dlRes.body);
                                        String b64Content = dlObj.optString("content", "");
                                        if (!b64Content.isEmpty()) {
                                            byte[] bytes = Base64.decode(b64Content, Base64.DEFAULT);
                                            out[1] = unescapeLyricEntities(new String(bytes, "UTF-8"));
                                            Log.i(TAG, "Kugou LRC fallback downloaded (" + out[1].length() + " chars)");
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception el) {
                    Log.w(TAG, "Kugou lyrics error: " + el.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFromKugou error: " + e.getMessage());
        }
        return out;
    }

    private static final int[] KRC_XOR_KEY = {
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105
    };
    private static final Pattern KRC_LINE_PATTERN = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");

    public static String unescapeLyricEntities(String s) {
        if (s == null || !s.contains("&")) return s;
        return s.replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&lt;", "<")
                .replace("&#60;", "<")
                .replace("&gt;", ">")
                .replace("&#62;", ">")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&#38;", "&");
    }

    public static String decryptKrcBytes(byte[] raw) {
        if (raw == null || raw.length <= 4) return null;
        if (raw[0] != 'k' || raw[1] != 'r' || raw[2] != 'c' || raw[3] != '1') {
            return null;
        }
        int len = raw.length - 4;
        byte[] xored = new byte[len];
        for (int i = 0; i < len; i++) {
            xored[i] = (byte) ((raw[i + 4] & 0xFF) ^ KRC_XOR_KEY[i & 15]);
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(xored);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(len * 2);
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    bos.write(buf, 0, count);
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "decryptKrcBytes failed: " + e.getMessage());
            return null;
        } finally {
            inflater.end();
        }
    }

    public static String formatLrcTimestamp(long ms) {
        long clamped = Math.max(0L, ms);
        long mins = clamped / 60000L;
        long secs = (clamped % 60000L) / 1000L;
        long millis = clamped % 1000L;
        return String.format(Locale.US, "[%02d:%02d.%03d]", mins, secs, millis);
    }

    public static String buildBilingualLrcFromKrcParts(String krcText, List<String> transLines, List<String> romaLines) {
        if (krcText == null || krcText.isEmpty()) return null;
        StringBuilder primarySb = new StringBuilder();
        StringBuilder transSb = new StringBuilder();
        StringBuilder romaSb = new StringBuilder();

        String[] rawLines = krcText.split("\\r?\\n");
        int lineIdx = 0;
        for (String rawLine : rawLines) {
            Matcher m = KRC_LINE_PATTERN.matcher(rawLine.trim());
            if (!m.matches()) continue;

            long startMs = 0L;
            try {
                startMs = Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {}
            String ts = formatLrcTimestamp(startMs);
            String cleanMain = unescapeLyricEntities(m.group(3).replaceAll("<[^>]*>", "")).trim();
            if (!cleanMain.isEmpty()) {
                primarySb.append(ts).append(cleanMain).append('\n');
            }
            if (transLines != null && lineIdx < transLines.size()) {
                String tr = transLines.get(lineIdx);
                tr = (tr != null) ? unescapeLyricEntities(tr).trim() : "";
                if (!tr.isEmpty() && !"//".equals(tr)) {
                    transSb.append(ts).append(tr).append('\n');
                }
            }
            if (romaLines != null && lineIdx < romaLines.size()) {
                String rm = romaLines.get(lineIdx);
                rm = (rm != null) ? unescapeLyricEntities(rm).trim() : "";
                if (!rm.isEmpty() && !"//".equals(rm)) {
                    romaSb.append(ts).append(rm).append('\n');
                }
            }
            lineIdx++;
        }

        String primary = primarySb.toString().trim();
        if (primary.isEmpty()) return null;

        String merged = mergeBilingualLrc(primary, transSb.toString().trim());
        if ((merged == null || merged.equals(primary)) && containsKanaOrHangul(primary)) {
            merged = mergeBilingualLrc(primary, romaSb.toString().trim());
        }
        return merged;
    }

    private static String decodeKrcBase64ToLrc(String b64Content) {
        try {
            byte[] raw = Base64.decode(b64Content, Base64.DEFAULT);
            String krcText = decryptKrcBytes(raw);
            if (krcText == null || krcText.isEmpty()) return null;

            List<String> transLines = null;
            List<String> romaLines = null;

            int langIdx = krcText.indexOf("[language:");
            if (langIdx >= 0) {
                int endIdx = krcText.indexOf(']', langIdx + 10);
                if (endIdx > langIdx + 10) {
                    String langB64 = krcText.substring(langIdx + 10, endIdx).trim();
                    try {
                        String langJson = new String(Base64.decode(langB64, Base64.DEFAULT), "UTF-8");
                        JSONObject langObj = new JSONObject(langJson);
                        JSONArray contentArr = langObj.optJSONArray("content");
                        if (contentArr != null) {
                            for (int i = 0; i < contentArr.length(); i++) {
                                JSONObject item = contentArr.optJSONObject(i);
                                if (item == null) continue;
                                int type = item.optInt("type", -1);
                                JSONArray lcArr = item.optJSONArray("lyricContent");
                                if (lcArr == null) continue;
                                if (type == 1 && transLines == null) {
                                    transLines = extractKrcLanguageRows(lcArr, false);
                                } else if (type == 0 && romaLines == null) {
                                    romaLines = extractKrcLanguageRows(lcArr, true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "decodeKrc language tag parse error: " + e.getMessage());
                    }
                }
            }
            return buildBilingualLrcFromKrcParts(krcText, transLines, romaLines);
        } catch (Exception e) {
            Log.w(TAG, "decodeKrcBase64ToLrc error: " + e.getMessage());
            return null;
        }
    }

    private static List<String> extractKrcLanguageRows(JSONArray lcArr, boolean collapseWhitespace) {
        List<String> rows = new ArrayList<>(lcArr.length());
        for (int r = 0; r < lcArr.length(); r++) {
            JSONArray rowArr = lcArr.optJSONArray(r);
            if (rowArr == null) {
                rows.add("");
                continue;
            }
            StringBuilder rowSb = new StringBuilder();
            for (int k = 0; k < rowArr.length(); k++) {
                rowSb.append(rowArr.optString(k, ""));
            }
            String joined = rowSb.toString();
            if (collapseWhitespace) {
                joined = joined.replaceAll("\\s+", " ").trim();
            } else {
                joined = joined.trim();
            }
            rows.add(joined);
        }
        return rows;
    }

    public static String mergeBilingualLrc(String primaryLrc, String transLrc) {
        if (primaryLrc == null || primaryLrc.trim().isEmpty() || transLrc == null || transLrc.trim().isEmpty()) {
            return primaryLrc;
        }
        boolean hasValidLine = false;
        String[] lines = transLrc.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.matches("(?i)^\\[(ti|ar|al|by|offset|length):.*")) continue;
            if (trimmed.matches(".*\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?\\].*")) {
                String text = trimmed.replaceAll("\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?\\]", "").trim();
                if (!text.isEmpty() && !"//".equals(text)) {
                    hasValidLine = true;
                    break;
                }
            }
        }
        if (!hasValidLine) {
            return primaryLrc;
        }
        return primaryLrc.trim() + "\n[bmc:trans]\n" + transLrc.trim();
    }

    private static boolean containsKanaOrHangul(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\uac00' && c <= '\ud7af')) {
                return true;
            }
        }
        return false;
    }

    private static String[] fetchFromQQ(String title, String artist, long duration) {
        String[] out = new String[2]; // [0]=coverUrl, [1]=lyrics
        try {
            String firstArtist = cleanArtist(artist);
            List<String> queries = new ArrayList<>();
            if (!firstArtist.isEmpty()) {
                queries.add(title + " " + firstArtist);
            }
            queries.add(title);

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://y.qq.com/");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            JSONObject bestSong = null;
            int bestScore = -9999;

            for (String qText : queries) {
                if (qText == null || qText.trim().isEmpty()) continue;
                String q = URLEncoder.encode(qText.trim(), "UTF-8");
                String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=" + q + "&format=json";
                HttpResult searchRes = httpGetResponse(searchUrl, headers, null);
                if (searchRes.isBlockedOrError()) {
                    markProviderDegraded("qq", "HTTP " + searchRes.code);
                    break;
                }
                if (searchRes.code != 200 || searchRes.body == null) continue;

                JSONObject sObj = new JSONObject(searchRes.body);
                int qqCode = sObj.optInt("code", 0);
                if (qqCode != 0) {
                    markProviderDegraded("qq", "code=" + qqCode);
                    break;
                }
                JSONObject data = sObj.optJSONObject("data");
                if (data == null) continue;
                JSONObject songObj = data.optJSONObject("song");
                if (songObj == null) continue;
                JSONArray list = songObj.optJSONArray("list");
                if (list == null || list.length() == 0) continue;

                for (int i = 0; i < list.length(); i++) {
                    JSONObject song = list.getJSONObject(i);
                    String sName = song.optString("songname", "");
                    String sAlbum = song.optString("albumname", "");
                    long cDur = song.optLong("interval", 0) * 1000;
                    List<String> sArtists = new ArrayList<>();
                    JSONArray singers = song.optJSONArray("singer");
                    if (singers != null) {
                        for (int k = 0; k < singers.length(); k++) {
                            JSONObject sg = singers.getJSONObject(k);
                            sArtists.add(sg.optString("name", ""));
                        }
                    }

                    int sc = scoreCandidate(sName, sArtists, sAlbum, cDur, title, artist, "", duration);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestSong = song;
                    }
                }

                if (bestScore >= 85) {
                    break;
                }
            }

            if (bestSong != null && bestScore >= 50) {
                String albummid = bestSong.optString("albummid", "");
                if (!albummid.isEmpty()) {
                    out[0] = "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albummid + ".jpg";
                }
                String songmid = bestSong.optString("songmid", "");
                long songid = bestSong.optLong("songid", 0);
                if (!songmid.isEmpty()) {
                    // Primary: musicu.fcg GetPlayLyricInfo (delivers complete Chinese translations)
                    try {
                        JSONObject param = new JSONObject();
                        param.put("songMID", songmid);
                        if (songid > 0) param.put("songID", songid);
                        param.put("crypt", 0);
                        param.put("ct", 19);
                        param.put("cv", 1873);
                        param.put("roma", 0);
                        param.put("trans", 1);
                        param.put("type", 0);

                        JSONObject lyricReq = new JSONObject();
                        lyricReq.put("module", "music.musichallSong.PlayLyricInfo");
                        lyricReq.put("method", "GetPlayLyricInfo");
                        lyricReq.put("param", param);

                        JSONObject rootReq = new JSONObject();
                        rootReq.put("lyric", lyricReq);

                        HttpResult musicuRes = httpPostJsonResponse("https://u.y.qq.com/cgi-bin/musicu.fcg", rootReq.toString(), headers, null);
                        if (musicuRes.code == 200 && musicuRes.body != null) {
                            JSONObject rootResp = new JSONObject(musicuRes.body);
                            JSONObject lyricWrap = rootResp.optJSONObject("lyric");
                            JSONObject lData = (lyricWrap != null) ? lyricWrap.optJSONObject("data") : null;
                            if (lData != null) {
                                String lyricB64 = lData.optString("lyric", "");
                                if (!lyricB64.isEmpty()) {
                                    String primaryLrc = unescapeLyricEntities(new String(Base64.decode(lyricB64, Base64.DEFAULT), "UTF-8"));
                                    String transB64 = lData.optString("trans", "");
                                    if (!transB64.isEmpty()) {
                                        try {
                                            String transLrc = unescapeLyricEntities(new String(Base64.decode(transB64, Base64.DEFAULT), "UTF-8"));
                                            transLrc = transLrc.replaceAll("(?m)^\\[kana:[^\\]]*\\]\\r?\\n?", "");
                                            primaryLrc = mergeBilingualLrc(primaryLrc, transLrc);
                                        } catch (Exception ignored) {}
                                    }
                                    out[1] = primaryLrc;
                                    Log.i(TAG, "QQ musicu lyrics found (score " + bestScore + ", " + out[1].length() + " chars)");
                                }
                            }
                        }
                    } catch (Exception eq) {
                        Log.w(TAG, "QQ musicu lyric error: " + eq.getMessage());
                    }

                    // Fallback: legacy fcg_query_lyric_new.fcg endpoint
                    if (out[1] == null || out[1].isEmpty()) {
                        String lrcUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + songmid + "&format=json&nobase64=0";
                        HttpResult lrcRes = httpGetResponse(lrcUrl, headers, null);
                        if (lrcRes.isBlockedOrError()) {
                            markProviderDegraded("qq", "lyric HTTP " + lrcRes.code);
                        } else if (lrcRes.code == 200 && lrcRes.body != null) {
                            JSONObject lObj = new JSONObject(lrcRes.body);
                            String lyricB64 = lObj.optString("lyric", "");
                            if (!lyricB64.isEmpty()) {
                                byte[] bytes = Base64.decode(lyricB64, Base64.DEFAULT);
                                String primaryLrc = unescapeLyricEntities(new String(bytes, "UTF-8"));
                                String transB64 = lObj.optString("trans", "");
                                if (!transB64.isEmpty()) {
                                    try {
                                        String transLrc = unescapeLyricEntities(new String(Base64.decode(transB64, Base64.DEFAULT), "UTF-8"));
                                        transLrc = transLrc.replaceAll("(?m)^\\[kana:[^\\]]*\\]\\r?\\n?", "");
                                        primaryLrc = mergeBilingualLrc(primaryLrc, transLrc);
                                    } catch (Exception ignored) {}
                                }
                                out[1] = primaryLrc;
                                Log.i(TAG, "QQ fallback lyrics found (score " + bestScore + ", " + out[1].length() + " chars)");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFromQQ error: " + e.getMessage());
        }
        return out;
    }

    private static JSONObject pickBestNetEaseSong(JSONArray songs, String title, String artist, String album,
                                                  long duration, int[] bestScoreOut, JSONObject currentBest) {
        JSONObject best = currentBest;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            String sName = song.optString("name", "");
            long cDur = song.optLong("dt", 0);
            if (cDur <= 0) cDur = song.optLong("duration", 0);
            JSONObject sAlbumObj = song.optJSONObject("al");
            if (sAlbumObj == null) sAlbumObj = song.optJSONObject("album");
            String sAlbumName = (sAlbumObj != null) ? sAlbumObj.optString("name", "") : "";

            List<String> sArtists = new ArrayList<>();
            JSONArray arArr = song.optJSONArray("ar");
            if (arArr == null) arArr = song.optJSONArray("artists");
            if (arArr != null) {
                for (int a = 0; a < arArr.length(); a++) {
                    JSONObject arItem = arArr.optJSONObject(a);
                    if (arItem != null) {
                        sArtists.add(arItem.optString("name", ""));
                    }
                }
            }

            int sc = scoreCandidate(sName, sArtists, sAlbumName, cDur, title, artist, album, duration);
            if (sc > bestScoreOut[0]) {
                bestScoreOut[0] = sc;
                best = song;
            }
        }
        return best;
    }

    private static String[] fetchFromNetEase(String title, String artist, String album, long duration) {
        String coverUrl = null;
        String lyrics = null;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://music.163.com/");
            headers.put("Cookie", "os=pc");

            String firstArtist = cleanArtist(artist);
            List<String> queries = new ArrayList<>();
            if (!firstArtist.isEmpty()) {
                queries.add(title + " " + firstArtist);
                int colonIdx = title.indexOf(':');
                if (colonIdx > 0) {
                    queries.add(title.substring(0, colonIdx).trim() + " " + firstArtist);
                } else {
                    queries.add(title);
                }
            } else {
                queries.add(title);
            }

            JSONObject bestSong = null;
            int[] bestScore = new int[]{-9999};

            for (String q : queries) {
                if (q == null || q.trim().isEmpty()) continue;
                String encodedQ = URLEncoder.encode(q.trim(), "UTF-8");
                String primaryUrl = "https://music.163.com/api/cloudsearch/pc?s=" + encodedQ + "&type=1&offset=0&limit=15";
                HttpResult res = httpGetResponse(primaryUrl, headers, null);
                JSONObject obj = null;
                boolean primaryOk = false;

                if (res.code == 200 && res.body != null && !res.body.isEmpty()) {
                    obj = new JSONObject(res.body);
                    if (obj.optInt("code", 200) == 200) {
                        primaryOk = true;
                    } else {
                        Log.w(TAG, "NetEase cloudsearch returned JSON code=" + obj.optInt("code") + ", trying backup endpoint");
                    }
                } else {
                    Log.w(TAG, "NetEase cloudsearch HTTP " + res.code + ", trying backup endpoint");
                }

                if (!primaryOk) {
                    String backupUrl = "https://music.163.com/api/search/get/web?s=" + encodedQ + "&type=1&offset=0&limit=10";
                    HttpResult res2 = httpGetResponse(backupUrl, headers, null);
                    if (res2.isBlockedOrError()) {
                        markProviderDegraded("netease", "HTTP " + res2.code);
                        break;
                    }
                    if (res2.code == 200 && res2.body != null && !res2.body.isEmpty()) {
                        JSONObject obj2 = new JSONObject(res2.body);
                        int code2 = obj2.optInt("code", 200);
                        if (code2 != 200) {
                            markProviderDegraded("netease", "backup code=" + code2);
                            break;
                        }
                        obj = obj2;
                    } else if (res.isBlockedOrError()) {
                        markProviderDegraded("netease", "primary HTTP " + res.code + " / backup HTTP " + res2.code);
                        break;
                    }
                }

                if (obj == null) continue;
                JSONObject result = obj.optJSONObject("result");
                if (result == null) continue;
                JSONArray songs = result.optJSONArray("songs");
                if (songs == null || songs.length() == 0) continue;

                bestSong = pickBestNetEaseSong(songs, title, artist, album, duration, bestScore, bestSong);
                if (bestScore[0] >= 85) {
                    break;
                }
            }

            if (bestSong != null && bestScore[0] >= 50) {
                long songId = bestSong.optLong("id");
                JSONObject albObj = bestSong.optJSONObject("al");
                if (albObj == null) albObj = bestSong.optJSONObject("album");
                if (albObj != null) {
                    String picUrl = albObj.optString("picUrl");
                    if (picUrl != null && !picUrl.isEmpty()) {
                        if (picUrl.startsWith("http://")) {
                            picUrl = "https://" + picUrl.substring(7);
                        }
                        String candidateCover = picUrl + "?param=600y600";
                        if (isValidCoverUrl(candidateCover)) {
                            coverUrl = candidateCover;
                        }
                    }
                }

                if (songId > 0) {
                    String lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=" + songId + "&lv=-1&kv=-1&tv=-1&rv=-1";
                    HttpResult lyricRes = httpGetResponse(lyricUrl, headers, null);
                    if (lyricRes.isBlockedOrError()) {
                        markProviderDegraded("netease", "lyric HTTP " + lyricRes.code);
                    } else if (lyricRes.code == 200 && lyricRes.body != null && !lyricRes.body.isEmpty()) {
                        JSONObject lrcObj = new JSONObject(lyricRes.body);
                        int lrcCode = lrcObj.optInt("code", 200);
                        if (lrcCode != 200) {
                            markProviderDegraded("netease", "lyric code=" + lrcCode);
                        } else {
                            JSONObject lrc = lrcObj.optJSONObject("lrc");
                            if (lrc != null) {
                                String primary = unescapeLyricEntities(lrc.optString("lyric", ""));
                                JSONObject tObj = lrcObj.optJSONObject("tlyric");
                                String tlyric = (tObj != null) ? unescapeLyricEntities(tObj.optString("lyric", "")) : "";
                                JSONObject rObj = lrcObj.optJSONObject("romalrc");
                                String romalrc = (rObj != null) ? unescapeLyricEntities(rObj.optString("lyric", "")) : "";

                                String merged = mergeBilingualLrc(primary, tlyric);
                                if ((merged == null || merged.equals(primary)) && containsKanaOrHangul(primary)) {
                                    merged = mergeBilingualLrc(primary, romalrc);
                                }
                                lyrics = merged;
                            }
                        }
                    }
                }
                Log.i(TAG, "NetEase matched song id: " + songId + ", score: " + bestScore[0] + ", cover: " + coverUrl);
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFromNetEase error: " + e.getMessage());
        }
        return new String[]{coverUrl, lyrics};
    }

    private static class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean isServerOrRateLimit() {
            return code == 429 || code >= 500;
        }

        boolean isBlockedOrError() {
            return code == 429 || code == 403 || code >= 500;
        }
    }

    private static HttpResult httpGetResponse(String urlStr, Map<String, String> headers, String customUA) {
        return executeHttpRequest("GET", urlStr, null, headers, customUA);
    }

    private static HttpResult httpPostJsonResponse(String urlStr, String jsonBody, Map<String, String> headers, String customUA) {
        return executeHttpRequest("POST", urlStr, jsonBody, headers, customUA);
    }

    private static HttpResult executeHttpRequest(String method, String urlStr, String jsonBody, Map<String, String> headers, String customUA) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            String ua = (customUA != null) ? customUA : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            conn.setRequestProperty("User-Agent", ua);
            if ("POST".equals(method)) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            }
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            if (jsonBody != null && "POST".equals(method)) {
                conn.setDoOutput(true);
                byte[] bytes = jsonBody.getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    os.close();
                }
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return new HttpResult(code, sb.toString());
            }
            return new HttpResult(code, null);
        } catch (Exception e) {
            sThreadHadNetworkError.set(Boolean.TRUE);
            Log.w(TAG, "HTTP " + method + " failed for " + urlStr + ": " + e.getMessage());
            return new HttpResult(-1, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
