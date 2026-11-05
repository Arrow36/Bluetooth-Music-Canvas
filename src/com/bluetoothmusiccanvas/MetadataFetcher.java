package com.bluetoothmusiccanvas;

import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final Map<String, CachedMeta> sCache = Collections.synchronizedMap(
        new LinkedHashMap<String, CachedMeta>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedMeta> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        }
    );

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
                        lyricsLrc = fetchFromQQ(title, fullArtist, duration);
                        actualSource = "QQ音乐";
                    } else if ("kugou".equalsIgnoreCase(source)) {
                        String[] res = fetchFromKugou(title, fullArtist, false, true, duration);
                        lyricsLrc = res[1];
                        actualSource = "酷狗音乐";
                    } else if ("lrclib".equalsIgnoreCase(source)) {
                        lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist);
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

                String coverUrl = null;
                String lyricsLrc = null;
                String lyricSource = "";

                // 1. 优先从网易云音乐 (NetEase) 检索官方高清封面与同步歌词 (带多候选评分机制及时长校验)
                try {
                    String[] neteaseRes = fetchFromNetEase(title, fullArtist, album, duration);
                    if (neteaseRes[0] != null && !neteaseRes[0].isEmpty()) {
                        coverUrl = neteaseRes[0];
                    }
                    if (neteaseRes[1] != null && !neteaseRes[1].isEmpty()) {
                        lyricsLrc = neteaseRes[1];
                        lyricSource = "网易云音乐";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "NetEase search failed: " + e.getMessage());
                }

                // 2. 备用封面：iTunes (600x600 官方高保真，带候选评分及时长校验)
                if (coverUrl == null) {
                    try {
                        coverUrl = fetchCoverFromiTunes(title, fullArtist, album, duration);
                    } catch (Exception e) {
                        Log.w(TAG, "iTunes cover search failed: " + e.getMessage());
                    }
                }

                // 3. 备用歌词：QQ音乐 / 酷狗音乐 / LRCLIB
                if (lyricsLrc == null) {
                    boolean hasChinese = false;
                    for (char c : title.toCharArray()) {
                        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
                        if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                            hasChinese = true;
                            break;
                        }
                    }

                    if (hasChinese) {
                        // 中文歌曲优先备选 QQ 音乐，其次酷狗音乐，最后 LRCLIB
                        try {
                            String qqLrc = fetchFromQQ(title, fullArtist, duration);
                            if (qqLrc != null && !qqLrc.isEmpty()) {
                                lyricsLrc = qqLrc;
                                lyricSource = "QQ音乐";
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "QQ search failed: " + e.getMessage());
                        }

                        if (lyricsLrc == null) {
                            try {
                                String[] kugouRes = fetchFromKugou(title, fullArtist, coverUrl == null, true, duration);
                                if (coverUrl == null && kugouRes[0] != null) {
                                    coverUrl = kugouRes[0];
                                }
                                if (kugouRes[1] != null && !kugouRes[1].isEmpty()) {
                                    lyricsLrc = kugouRes[1];
                                    lyricSource = "酷狗音乐";
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Kugou search failed: " + e.getMessage());
                            }
                        }

                        if (lyricsLrc == null) {
                            try {
                                lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist);
                                if (lyricsLrc != null && !lyricsLrc.isEmpty()) {
                                    lyricSource = "LRCLIB";
                                }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        // 外文歌曲备选 LRCLIB -> QQ音乐 -> 酷狗音乐
                        try {
                            lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist);
                            if (lyricsLrc != null && !lyricsLrc.isEmpty()) {
                                lyricSource = "LRCLIB";
                            }
                        } catch (Exception ignored) {}

                        if (lyricsLrc == null) {
                            try {
                                String qqLrc = fetchFromQQ(title, fullArtist, duration);
                                if (qqLrc != null && !qqLrc.isEmpty()) {
                                    lyricsLrc = qqLrc;
                                    lyricSource = "QQ音乐";
                                }
                            } catch (Exception ignored) {}
                        }

                        if (lyricsLrc == null) {
                            try {
                                String[] kugouRes = fetchFromKugou(title, fullArtist, coverUrl == null, true, duration);
                                if (coverUrl == null && kugouRes[0] != null) {
                                    coverUrl = kugouRes[0];
                                }
                                if (kugouRes[1] != null && !kugouRes[1].isEmpty()) {
                                    lyricsLrc = kugouRes[1];
                                    lyricSource = "酷狗音乐";
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                // 4. 若封面仍缺失，从酷狗兜底
                if (coverUrl == null) {
                    try {
                        String[] kugouRes = fetchFromKugou(title, fullArtist, true, false, duration);
                        if (kugouRes[0] != null) {
                            coverUrl = kugouRes[0];
                        }
                    } catch (Exception ignored) {}
                }

                if (!key.isEmpty() && (coverUrl != null || lyricsLrc != null)) {
                    CachedMeta meta = new CachedMeta(coverUrl, lyricsLrc, lyricSource);
                    putInCache(rawTitle, rawArtist, rawAlbum, meta);
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
        // 3. 剥离尾部括号中的版本说明，如 (Live), (Remix), [伴奏], （官方版）等
        t = t.replaceAll("\\s*[\\(（【\\[].*$", "").trim();
        return t;
    }

    private static String cleanArtist(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("^[《<“\"'「『\\s]+|[》>”\"'」』\\s]+$", "").trim();
        clean = clean.replaceAll("\\s*[\\(（【\\[].*$", "").trim();
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
        clean = clean.replaceAll("\\s*[\\(（【\\[].*$", "").trim();
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

    private static String fetchCoverFromiTunes(String title, String artist, String album, long duration) {
        try {
            String firstArtist = cleanArtist(artist);
            String q = URLEncoder.encode((title + " " + firstArtist).trim(), "UTF-8");
            String urlStr = "https://itunes.apple.com/search?term=" + q + "&entity=song&limit=10";
            String jsonStr = httpGet(urlStr, null);
            if (jsonStr != null) {
                JSONObject obj = new JSONObject(jsonStr);
                JSONArray results = obj.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject bestItem = null;
                    int bestScore = -9999;
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        String t = item.optString("trackName", "");
                        String a = item.optString("artistName", "");
                        String alb = item.optString("collectionName", "");
                        long cDur = item.optLong("trackTimeMillis", 0);
                        List<String> artists = new ArrayList<>();
                        artists.add(a);
                        int score = scoreCandidate(t, artists, alb, cDur, title, artist, album, duration);
                        if (score > bestScore) {
                            bestScore = score;
                            bestItem = item;
                        }
                    }
                    if (bestItem != null && bestScore >= 50) {
                        String art = bestItem.optString("artworkUrl100", null);
                        if (art != null && !art.isEmpty()) {
                            String hdArt = art.replace("100x100bb", "600x600bb");
                            Log.i(TAG, "iTunes cover found (score " + bestScore + "): " + hdArt);
                            return hdArt;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchCoverFromiTunes error: " + e.getMessage());
        }
        return null;
    }

    private static String fetchLyricsFromLRCLIB(String title, String artist) {
        try {
            String qTitle = URLEncoder.encode(title.trim(), "UTF-8");
            String qArtist = URLEncoder.encode(artist.trim(), "UTF-8");
            String urlStr = "https://lrclib.net/api/get?track_name=" + qTitle + "&artist_name=" + qArtist;
            String jsonStr = httpGet(urlStr, "MusicCanvas/1.0");
            if (jsonStr != null) {
                JSONObject obj = new JSONObject(jsonStr);
                String synced = obj.optString("syncedLyrics", null);
                if (synced != null && !synced.isEmpty()) {
                    Log.i(TAG, "LRCLIB synced lyrics found (" + synced.length() + " chars)");
                    return synced;
                }
                String plain = obj.optString("plainLyrics", null);
                if (plain != null && !plain.isEmpty()) {
                    return plain;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchLyricsFromLRCLIB error: " + e.getMessage());
        }
        return null;
    }

    private static String[] fetchFromKugou(String title, String artist, boolean needCover, boolean needLyric, long duration) {
        String[] out = new String[2]; // [0]=cover, [1]=lyrics
        try {
            String firstArtist = cleanArtist(artist);
            String qText = (!firstArtist.isEmpty() ? (title + " " + firstArtist) : title).trim();
            String q = URLEncoder.encode(qText, "UTF-8");
            String searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=" + q + "&page=1&pagesize=5";
            String searchJson = httpGet(searchUrl, null);
            if (searchJson == null) return out;

            JSONObject sObj = new JSONObject(searchJson);
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
                List<String> sArtists = new ArrayList<>();
                sArtists.add(singer);
                int sc = scoreCandidate(sName, sArtists, sAlb, cDur, title, artist, "", duration);
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
                    String dJson = httpGet(dUrl, null);
                    if (dJson != null) {
                        JSONObject dObj = new JSONObject(dJson);
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

            // 2. Lyrics
            if (needLyric) {
                try {
                    String lSearchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=" + q + "&duration=0&hash=" + hash;
                    String lSearchJson = httpGet(lSearchUrl, null);
                    if (lSearchJson != null) {
                        JSONObject lsObj = new JSONObject(lSearchJson);
                        JSONArray candidates = lsObj.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            JSONObject cand = candidates.getJSONObject(0);
                            String candId = cand.optString("id", "");
                            String accessKey = cand.optString("accesskey", "");
                            if (!candId.isEmpty() && !accessKey.isEmpty()) {
                                String dlUrl = "http://krcs.kugou.com/download?ver=1&client=mobi&id=" + candId + "&accesskey=" + accessKey + "&fmt=lrc&charset=utf8";
                                String dlJson = httpGet(dlUrl, null);
                                if (dlJson != null) {
                                    JSONObject dlObj = new JSONObject(dlJson);
                                    String b64Content = dlObj.optString("content", "");
                                    if (!b64Content.isEmpty()) {
                                        byte[] bytes = Base64.decode(b64Content, Base64.DEFAULT);
                                        out[1] = new String(bytes, "UTF-8");
                                        Log.i(TAG, "Kugou lyrics downloaded (" + out[1].length() + " chars)");
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

    private static String fetchFromQQ(String title, String artist, long duration) {
        try {
            String firstArtist = cleanArtist(artist);
            List<String> queries = new ArrayList<>();
            if (!firstArtist.isEmpty()) {
                queries.add(title + " " + firstArtist);
            }
            if (!artist.isEmpty() && !artist.equalsIgnoreCase(firstArtist)) {
                queries.add(title + " " + artist);
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
                String searchJson = httpGetWithHeaders(searchUrl, headers);
                if (searchJson == null) continue;

                JSONObject sObj = new JSONObject(searchJson);
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

                if (bestScore >= 120) {
                    break;
                }
            }

            if (bestSong != null && bestScore >= 50) {
                String songmid = bestSong.optString("songmid", "");
                if (!songmid.isEmpty()) {
                    String lrcUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + songmid + "&format=json&nobase64=0";
                    String lrcJson = httpGetWithHeaders(lrcUrl, headers);
                    if (lrcJson != null) {
                        JSONObject lObj = new JSONObject(lrcJson);
                        String lyricB64 = lObj.optString("lyric", "");
                        if (!lyricB64.isEmpty()) {
                            byte[] bytes = Base64.decode(lyricB64, Base64.DEFAULT);
                            String lrc = new String(bytes, "UTF-8");
                            Log.i(TAG, "QQ lyrics found (score " + bestScore + ", " + lrc.length() + " chars)");
                            return lrc;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFromQQ error: " + e.getMessage());
        }
        return null;
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
            }
            if (album != null && !album.trim().isEmpty()) {
                queries.add(title + " " + album.trim());
            }
            queries.add(title);
            // Classical piece with movement: also query base title + firstArtist
            int colonIdx = title.indexOf(':');
            if (colonIdx > 0 && !firstArtist.isEmpty()) {
                queries.add(title.substring(0, colonIdx).trim() + " " + firstArtist);
            }

            JSONObject bestSong = null;
            int bestScore = -9999;

            for (String q : queries) {
                if (q == null || q.trim().isEmpty()) continue;
                String searchUrl = "https://music.163.com/api/cloudsearch/pc?s="
                        + URLEncoder.encode(q.trim(), "UTF-8")
                        + "&type=1&offset=0&limit=15";
                String jsonStr = httpGetWithHeaders(searchUrl, headers);
                if (jsonStr == null || jsonStr.isEmpty()) continue;

                JSONObject obj = new JSONObject(jsonStr);
                JSONObject result = obj.optJSONObject("result");
                if (result == null) continue;
                JSONArray songs = result.optJSONArray("songs");
                if (songs == null || songs.length() == 0) continue;

                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    String sName = song.optString("name", "");
                    long cDur = song.optLong("dt", 0);
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
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestSong = song;
                    }
                }

                // If high confidence match, stop query cascade
                if (bestScore >= 120) {
                    break;
                }
            }

            if (bestSong != null && bestScore >= 50) {
                long songId = bestSong.optLong("id");
                JSONObject albObj = bestSong.optJSONObject("al");
                if (albObj == null) albObj = bestSong.optJSONObject("album");
                if (albObj != null) {
                    String picUrl = albObj.optString("picUrl");
                    if (picUrl != null && !picUrl.isEmpty()) {
                        if (picUrl.startsWith("http://")) {
                            picUrl = "https://" + picUrl.substring(7);
                        }
                        coverUrl = picUrl + "?param=600y600";
                    }
                }

                // 获取歌词
                if (songId > 0) {
                    String lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=" + songId + "&lv=-1&kv=-1&tv=-1";
                    String lyricJson = httpGetWithHeaders(lyricUrl, headers);
                    if (lyricJson != null && !lyricJson.isEmpty()) {
                        JSONObject lrcObj = new JSONObject(lyricJson);
                        JSONObject lrc = lrcObj.optJSONObject("lrc");
                        if (lrc != null) {
                            lyrics = lrc.optString("lyric");
                        }
                    }
                }
                Log.i(TAG, "NetEase matched song id: " + songId + ", score: " + bestScore + ", cover: " + coverUrl);
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFromNetEase error: " + e.getMessage());
        }
        return new String[]{coverUrl, lyrics};
    }

    private static String httpGetWithHeaders(String urlStr, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
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
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET failed for " + urlStr + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private static String httpGet(String urlStr, String customUA) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            String ua = (customUA != null) ? customUA : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            conn.setRequestProperty("User-Agent", ua);

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
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET failed for " + urlStr + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
