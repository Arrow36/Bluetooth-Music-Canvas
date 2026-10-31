package com.c10h.musiccanvas;

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

public class MetadataFetcher {
    private static final String TAG = "C10H_Fetcher";

    public interface Callback {
        void onResult(String coverUrl, String lyricsLrc);
    }

    public static void fetch(final String rawTitle, final String rawArtist, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String title = cleanTitle(rawTitle);
                final String artist = cleanArtist(rawArtist);
                Log.i(TAG, "Starting metadata fetch for title: [" + title + "], artist: [" + artist + "]");

                String coverUrl = null;
                String lyricsLrc = null;

                // 1. Fetch Cover: iTunes (Fastest, highest resolution 600x600)
                try {
                    coverUrl = fetchCoverFromiTunes(title, artist);
                } catch (Exception e) {
                    Log.w(TAG, "iTunes cover search failed: " + e.getMessage());
                }

                // 2. Fetch Lyrics: LRCLIB (Direct synced LRC)
                try {
                    lyricsLrc = fetchLyricsFromLRCLIB(title, artist);
                } catch (Exception e) {
                    Log.w(TAG, "LRCLIB lyrics search failed: " + e.getMessage());
                }

                // 3. Fallback to Kugou if either cover or lyrics is missing
                if (coverUrl == null || lyricsLrc == null) {
                    try {
                        String[] kugouResult = fetchFromKugou(title, artist, coverUrl == null, lyricsLrc == null);
                        if (coverUrl == null && kugouResult[0] != null) {
                            coverUrl = kugouResult[0];
                        }
                        if (lyricsLrc == null && kugouResult[1] != null) {
                            lyricsLrc = kugouResult[1];
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Kugou fallback failed: " + e.getMessage());
                    }
                }

                Log.i(TAG, "Metadata fetch complete. Cover found=" + (coverUrl != null) + ", Lyrics found=" + (lyricsLrc != null));
                callback.onResult(coverUrl, lyricsLrc);
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

    private static String fetchCoverFromiTunes(String title, String artist) {
        try {
            String q = URLEncoder.encode((title + " " + artist).trim(), "UTF-8");
            String urlStr = "https://itunes.apple.com/search?term=" + q + "&entity=song&limit=1";
            String jsonStr = httpGet(urlStr, null);
            if (jsonStr != null) {
                JSONObject obj = new JSONObject(jsonStr);
                JSONArray results = obj.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject item = results.getJSONObject(0);
                    String art = item.optString("artworkUrl100", null);
                    if (art != null && !art.isEmpty()) {
                        String hdArt = art.replace("100x100bb", "600x600bb");
                        Log.i(TAG, "iTunes cover found: " + hdArt);
                        return hdArt;
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
