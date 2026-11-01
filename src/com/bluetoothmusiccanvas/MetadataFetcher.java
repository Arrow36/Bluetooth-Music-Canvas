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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedMeta> sCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public interface Callback {
        void onResult(String coverUrl, String lyricsLrc, String lyricSource);
    }

    public static CachedMeta getCached(String rawTitle, String rawArtist) {
        return getCached(rawTitle, rawArtist, "");
    }

    public static CachedMeta getCached(String rawTitle, String rawArtist, String rawAlbum) {
        String title = cleanTitle(rawTitle);
        String artist = cleanArtist(rawArtist);
        String album = (rawAlbum != null) ? rawAlbum.trim() : "";
        String key = (title + " - " + artist + (album.isEmpty() ? "" : " - " + album)).toLowerCase().trim();
        CachedMeta m = sCache.get(key);
        if (m != null) return m;
        String fallbackKey = (title + " - " + artist).toLowerCase().trim();
        return sCache.get(fallbackKey);
    }

    public static void fetch(final String rawTitle, final String rawArtist, final Callback callback) {
        fetch(rawTitle, rawArtist, "", callback);
    }

    public static void fetchSpecificLyric(final String source, final String rawTitle, final String rawArtist, final String rawAlbum, final Callback callback) {
        final String title = cleanTitle(rawTitle);
        final String fullArtist = (rawArtist != null) ? rawArtist.trim() : "";
        final String album = (rawAlbum != null) ? rawAlbum.trim() : "";

        new Thread(new Runnable() {
            @Override
            public void run() {
                String lyricsLrc = null;
                String actualSource = "";
                try {
                    if ("netease".equalsIgnoreCase(source)) {
                        String[] res = fetchFromNetEase(title, fullArtist, album);
                        lyricsLrc = res[1];
                        actualSource = "网易云音乐";
                    } else if ("qq".equalsIgnoreCase(source)) {
                        lyricsLrc = fetchFromQQ(title, fullArtist);
                        actualSource = "QQ音乐";
                    } else if ("kugou".equalsIgnoreCase(source)) {
                        String[] res = fetchFromKugou(title, fullArtist, false, true);
                        lyricsLrc = res[1];
                        actualSource = "酷狗音乐";
                    } else if ("lrclib".equalsIgnoreCase(source)) {
                        lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist);
                        actualSource = "LRCLIB";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "fetchSpecificLyric error: " + e.getMessage());
                }
                callback.onResult(null, lyricsLrc, actualSource);
            }
        }).start();
    }

    public static void fetch(final String rawTitle, final String rawArtist, final String rawAlbum, final Callback callback) {
        final String title = cleanTitle(rawTitle);
        final String fullArtist = (rawArtist != null) ? rawArtist.trim() : "";
        final String album = (rawAlbum != null) ? rawAlbum.trim() : "";
        final String key = (title + " - " + fullArtist + (album.isEmpty() ? "" : " - " + album)).toLowerCase().trim();

        if (title.isEmpty() || title.contains("纯音乐") || title.contains("请欣赏") || title.equals("未知歌曲") || title.equals("Not Provided")) {
            Log.i(TAG, "Skipping fetch for placeholder title: [" + title + "]");
            callback.onResult(null, null, "");
            return;
        }

        if (!key.isEmpty() && sCache.containsKey(key)) {
            CachedMeta cached = sCache.get(key);
            Log.i(TAG, "Instant cache hit for key: [" + key + "], source: " + cached.lyricSource);
            callback.onResult(cached.coverUrl, cached.lyricsLrc, cached.lyricSource);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Starting metadata fetch for title: [" + title + "], artist: [" + fullArtist + "], album: [" + album + "]");

                String coverUrl = null;
                String lyricsLrc = null;
                String lyricSource = "";

                // 1. 优先从网易云音乐 (NetEase) 检索官方高清封面与同步歌词 (带多候选评分机制)
                try {
                    String[] neteaseRes = fetchFromNetEase(title, fullArtist, album);
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

                // 2. 备用封面：iTunes (600x600 官方高保真，带候选评分)
                if (coverUrl == null) {
                    try {
                        coverUrl = fetchCoverFromiTunes(title, fullArtist, album);
                    } catch (Exception e) {
                        Log.w(TAG, "iTunes cover search failed: " + e.getMessage());
                    }
                }

                // 3. 备用歌词：酷狗音乐或 LRCLIB
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
                        // 中文歌曲备选酷狗音乐
                        try {
                            String[] kugouRes = fetchFromKugou(title, fullArtist, coverUrl == null, true);
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

                        if (lyricsLrc == null) {
                            try {
                                lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist);
                                if (lyricsLrc != null && !lyricsLrc.isEmpty()) {
                                    lyricSource = "LRCLIB";
                                }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        // 外文歌曲备选 LRCLIB
                        try {
                            lyricsLrc = fetchLyricsFromLRCLIB(title, fullArtist);
                            if (lyricsLrc != null && !lyricsLrc.isEmpty()) {
                                lyricSource = "LRCLIB";
                            }
                        } catch (Exception ignored) {}

                        if (lyricsLrc == null) {
                            try {
                                String[] kugouRes = fetchFromKugou(title, fullArtist, coverUrl == null, true);
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
                        String[] kugouRes = fetchFromKugou(title, fullArtist, true, false);
                        if (kugouRes[0] != null) {
                            coverUrl = kugouRes[0];
                        }
                    } catch (Exception ignored) {}
                }

                if (!key.isEmpty() && (coverUrl != null || lyricsLrc != null)) {
                    sCache.put(key, new CachedMeta(coverUrl, lyricsLrc, lyricSource));
                }

                Log.i(TAG, "Metadata fetch complete. Cover found=" + (coverUrl != null) + ", Lyrics found=" + (lyricsLrc != null) + " (" + lyricSource + ")");
                callback.onResult(coverUrl, lyricsLrc, lyricSource);
            }
        }).start();
    }

    private static String cleanTitle(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*[\\(（【\\[].*$", "").trim();
    }

    private static String cleanArtist(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("\\s*[\\(（【\\[].*$", "").trim();
        // If multiple artists separated by slash, take primary or replace with space
        int slash = clean.indexOf('/');
        if (slash > 0) {
            clean = clean.substring(0, slash).trim();
        }
        return clean;
    }

    private static int scoreCandidate(String candidateTitle, List<String> candidateArtists, String candidateAlbum,
                                      String reqTitle, String reqArtist, String reqAlbum) {
        int score = 0;
        String cTitle = (candidateTitle != null) ? candidateTitle.toLowerCase().trim() : "";
        String rTitle = (reqTitle != null) ? reqTitle.toLowerCase().trim() : "";

        // 1. Title match
        if (cTitle.equals(rTitle)) {
            score += 150;
        } else if (cTitle.contains(rTitle) || rTitle.contains(cTitle)) {
            score += 80;
        } else {
            String cBase = cTitle.split("[:(]")[0].trim();
            String rBase = rTitle.split("[:(]")[0].trim();
            if (!cBase.isEmpty() && !rBase.isEmpty() && (cBase.contains(rBase) || rBase.contains(cBase))) {
                score += 40;
            }
        }

        // 2. Classical movement & opus checks (critical for classical music with many movements)
        String[] classicalTokens = new String[]{
                ": i.", ": ii.", ": iii.", ": iv.", "allegro", "andante", "adagio", "presto", "largo", "vivace", "menuetto", "rondo", "rondeau"
        };
        for (String tok : classicalTokens) {
            if (rTitle.contains(tok)) {
                if (cTitle.contains(tok)) {
                    score += 40;
                } else {
                    score -= 50; // Different movement in the same piece
                }
            }
        }

        // 3. Artist match
        if (reqArtist != null && !reqArtist.trim().isEmpty()) {
            String rArt = reqArtist.toLowerCase();
            boolean anyMatched = false;
            if (candidateArtists != null) {
                for (String ca : candidateArtists) {
                    if (ca == null) continue;
                    String caLow = ca.toLowerCase().trim();
                    if (!caLow.isEmpty() && (rArt.contains(caLow) || caLow.contains(rArt))) {
                        score += 60;
                        anyMatched = true;
                    }
                }
            }
            if (!anyMatched) {
                // If candidate doesn't match any performer from requested artists, heavily penalize
                score -= 80;
            }
        }

        // 4. Album match
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

        return score;
    }

    private static String fetchCoverFromiTunes(String title, String artist, String album) {
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
                        List<String> artists = new ArrayList<>();
                        artists.add(a);
                        int score = scoreCandidate(t, artists, alb, title, artist, album);
                        if (score > bestScore) {
                            bestScore = score;
                            bestItem = item;
                        }
                    }
                    if (bestItem != null && bestScore > -50) {
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

    private static String[] fetchFromKugou(String title, String artist, boolean needCover, boolean needLyric) {
        String[] out = new String[2]; // [0]=cover, [1]=lyrics
        try {
            String q = URLEncoder.encode((title + " " + artist).trim(), "UTF-8");
            String searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=" + q + "&page=1&pagesize=1";
            String searchJson = httpGet(searchUrl, null);
            if (searchJson == null) return out;

            JSONObject sObj = new JSONObject(searchJson);
            JSONObject data = sObj.optJSONObject("data");
            if (data == null) return out;
            JSONArray info = data.optJSONArray("info");
            if (info == null || info.length() == 0) return out;

            JSONObject song = info.getJSONObject(0);
            String hash = song.optString("hash", "");
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
                            out[0] = img.replace("{size}", "400");
                            Log.i(TAG, "Kugou cover found: " + out[0]);
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

    private static String fetchFromQQ(String title, String artist) {
        try {
            String q = URLEncoder.encode((title + " " + artist).trim(), "UTF-8");
            String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=1&w=" + q + "&format=json";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://y.qq.com/");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            String searchJson = httpGetWithHeaders(searchUrl, headers);
            if (searchJson == null) return null;

            JSONObject sObj = new JSONObject(searchJson);
            JSONObject data = sObj.optJSONObject("data");
            if (data == null) return null;
            JSONObject songObj = data.optJSONObject("song");
            if (songObj == null) return null;
            JSONArray list = songObj.optJSONArray("list");
            if (list == null || list.length() == 0) return null;

            JSONObject firstSong = list.getJSONObject(0);
            String songmid = firstSong.optString("songmid", "");
            if (songmid.isEmpty()) return null;

            String lrcUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + songmid + "&format=json&nobase64=0";
            String lrcJson = httpGetWithHeaders(lrcUrl, headers);
            if (lrcJson == null) return null;

            JSONObject lObj = new JSONObject(lrcJson);
            String lyricB64 = lObj.optString("lyric", "");
            if (!lyricB64.isEmpty()) {
                byte[] bytes = Base64.decode(lyricB64, Base64.DEFAULT);
                String lrc = new String(bytes, "UTF-8");
                Log.i(TAG, "QQ lyrics found (" + lrc.length() + " chars)");
                return lrc;
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchFromQQ error: " + e.getMessage());
        }
        return null;
    }

    private static String[] fetchFromNetEase(String title, String artist, String album) {
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

                    int sc = scoreCandidate(sName, sArtists, sAlbumName, title, artist, album);
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

            if (bestSong != null && bestScore > -50) {
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
