package tests;

import java.util.ArrayList;
import java.util.List;

public class TestMediaMonitorLogic {

    public static class SongState {
        public String realTitle = "";
        public String realArtist = "";
        public String realAlbum = "";
        public long duration = 0;
        public String streamLyric = "";
        public boolean isNewSong = false;
        public boolean isEnrichment = false;
        public long generation = 0;
    }

    public static class MetadataProcessor {
        private String mRealTitle = "";
        private String mRealArtist = "";
        private String mRealAlbum = "";
        private long mDuration = 0;
        private String mStreamLyric = "";
        private String mLastMediaId = null;
        private long mGeneration = 0;

        public SongState process(String rawTitle, String rawArtist, String rawAlbum, long duration, String mediaId, long currentPosition) {
            SongState res = new SongState();
            rawTitle = (rawTitle != null) ? rawTitle.trim() : "";
            rawArtist = (rawArtist != null) ? rawArtist.trim() : "";
            rawAlbum = (rawAlbum != null) ? rawAlbum.trim() : "";

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

            // Punctuation and placeholder detection
            boolean isPlaceholderOrLyric = false;
            if (rawTitle != null && !rawTitle.isEmpty()) {
                String lower = rawTitle.toLowerCase();
                if (rawTitle.contains("纯音乐") || rawTitle.contains("请欣赏") || rawTitle.contains("没有填词")
                        || rawTitle.contains("享受音乐") || rawTitle.contains("作词") || rawTitle.contains("作曲")
                        || rawTitle.contains("编曲") || rawTitle.contains("制作") || rawTitle.contains("监制")
                        || rawTitle.contains("混音") || rawTitle.contains("母带") || rawTitle.contains("录音")
                        || rawTitle.contains("出品") || rawTitle.contains("原唱") || lower.contains("producer")
                        || lower.contains("composer") || lower.contains("lyricist") || lower.contains("instrumental")) {
                    isPlaceholderOrLyric = true;
                } else if ((rawTitle.contains("，") || rawTitle.contains("。") || rawTitle.contains("！")
                        || rawTitle.contains("？") || rawTitle.contains("~") || rawTitle.contains("…")
                        || rawTitle.contains(",") || rawTitle.contains("!") || rawTitle.contains("?"))
                        && rawTitle.length() >= 12) {
                    isPlaceholderOrLyric = true;
                }
            }

            // Check if this is continuous car Bluetooth stream for the same song
            boolean isCarBluetoothStreamForSameSong = false;
            if (candidateTitle != null && !candidateTitle.isEmpty() && !mRealTitle.isEmpty()) {
                if (candidateTitle.equalsIgnoreCase(mRealTitle)
                        || candidateTitle.contains(mRealTitle)
                        || mRealTitle.contains(candidateTitle)) {
                    isCarBluetoothStreamForSameSong = true;
                }
            }

            // Check if this is metadata enrichment for currently playing song
            boolean isSameSongIdentity = !mRealTitle.isEmpty() && mRealTitle.equalsIgnoreCase(rawTitle)
                    && (mRealArtist.equalsIgnoreCase(rawArtist) || (performers != null && mRealArtist.equalsIgnoreCase(performers)));
            
            if (isCarBluetoothStreamForSameSong) {
                // Same song advancing stream lyric
                if (!rawTitle.contains("纯音乐") && !rawTitle.contains("请欣赏")) {
                    mStreamLyric = rawTitle;
                }
                if (performers != null && !performers.isEmpty()) {
                    mRealArtist = performers;
                }
                if (duration > 0) {
                    mDuration = duration;
                }
                res.isNewSong = false;
                res.isEnrichment = false;
            } else if (isSameSongIdentity && ((mRealAlbum.isEmpty() && !rawAlbum.isEmpty()) || (mDuration <= 0 && duration > 0))) {
                // Metadata enrichment (e.g. album or duration added later)
                if (!rawAlbum.isEmpty()) mRealAlbum = rawAlbum;
                if (duration > 0) mDuration = duration;
                res.isNewSong = false;
                res.isEnrichment = true;
            } else {
                // New track loaded
                mGeneration++;
                res.isNewSong = true;
                res.isEnrichment = false;

                if (candidateTitle != null && !candidateTitle.isEmpty() && performers != null && !performers.isEmpty()) {
                    boolean titleMatchesCandidate = candidateTitle.equalsIgnoreCase(rawTitle);
                    boolean albumConfirmsCandidate = (!rawAlbum.isEmpty()
                            && (candidateTitle.equalsIgnoreCase(rawAlbum) || rawAlbum.toLowerCase().contains(candidateTitle.toLowerCase()))
                            && !rawTitle.equalsIgnoreCase(rawAlbum));

                    if (titleMatchesCandidate) {
                        rawArtist = performers;
                        mStreamLyric = "";
                    } else if (isPlaceholderOrLyric || albumConfirmsCandidate) {
                        mStreamLyric = rawTitle;
                        rawTitle = candidateTitle;
                        rawArtist = performers;
                    } else {
                        // Multi-artist track (e.g. "fun. / Janelle Monae")
                        mStreamLyric = "";
                    }
                } else {
                    mStreamLyric = "";
                }

                mRealTitle = rawTitle;
                mRealArtist = rawArtist;
                mRealAlbum = rawAlbum;
                mDuration = duration;
                mLastMediaId = mediaId;
            }

            res.realTitle = mRealTitle;
            res.realArtist = mRealArtist;
            res.realAlbum = mRealAlbum;
            res.duration = mDuration;
            res.streamLyric = mStreamLyric;
            res.generation = mGeneration;
            return res;
        }
    }

    public static void main(String[] args) {
        System.out.println("Running Java MediaMonitorLogic verification...");
        MetadataProcessor proc = new MetadataProcessor();

        // Test 1: Multi-artist (fun. / Janelle Monae - We Are Young)
        SongState s1 = proc.process("We Are Young", "fun. / Janelle Monae", "Some Nights", 250000, "media_1", 0);
        if (!"We Are Young".equals(s1.realTitle)) throw new AssertionError("Expected title 'We Are Young', got: " + s1.realTitle);
        if (!"fun. / Janelle Monae".equals(s1.realArtist)) throw new AssertionError("Expected artist 'fun. / Janelle Monae', got: " + s1.realArtist);
        if (!s1.streamLyric.isEmpty()) throw new AssertionError("Expected empty stream lyric, got: " + s1.streamLyric);
        System.out.println("PASS: Multi-artist preserved without swap.");

        // Test 2: Same artist, same duration (180000ms) different song (First Song -> Second Song)
        SongState s2 = proc.process("First Song", "Test Artist", "Album 1", 180000, "media_2", 0);
        long genBefore = s2.generation;
        SongState s3 = proc.process("Second Song", "Test Artist", "Album 1", 180000, "media_3", 0);
        if (!"Second Song".equals(s3.realTitle)) throw new AssertionError("Expected title 'Second Song', got: " + s3.realTitle);
        if (!s3.isNewSong) throw new AssertionError("Second Song must be recognized as new song!");
        if (s3.generation <= genBefore) throw new AssertionError("Generation must increment on new song!");
        System.out.println("PASS: Same artist same duration track switch not swallowed.");

        // Test 3: Genuine car AVRCP stream lyric (polarrana/Full of You (Acoustic Version))
        MetadataProcessor carProc = new MetadataProcessor();
        SongState c1 = carProc.process("Was there something else before we saw each other?", "polarrana/Full of You (Acoustic Version)", "Escapism", 170918, null, 0);
        if (!"Full of You (Acoustic Version)".equals(c1.realTitle)) throw new AssertionError("Expected restored title, got: " + c1.realTitle);
        if (!"polarrana".equals(c1.realArtist)) throw new AssertionError("Expected restored artist, got: " + c1.realArtist);
        if (!"Was there something else before we saw each other?".equals(c1.streamLyric)) throw new AssertionError("Expected stream lyric restored, got: " + c1.streamLyric);
        System.out.println("PASS: Genuine car Bluetooth lyric inverted format restored.");

        // Test 3.2: Next lyric line arrives for same car song
        SongState c2 = carProc.process("It was meant to be we fell for one another.", "polarrana/Full of You (Acoustic Version)", "Escapism", 170918, null, 15000);
        if (!"Full of You (Acoustic Version)".equals(c2.realTitle)) throw new AssertionError("Title changed during stream!");
        if (c2.isNewSong) throw new AssertionError("Next stream lyric line treated as new song!");
        if (!"It was meant to be we fell for one another.".equals(c2.streamLyric)) throw new AssertionError("Stream lyric not updated!");
        System.out.println("PASS: Subsequent lyric packet recognized as same song stream.");

        // Test 4: Metadata enrichment (album added later)
        MetadataProcessor enrichProc = new MetadataProcessor();
        SongState e1 = enrichProc.process("Test Track", "Test Singer", "", 0, null, 0);
        long eGen = e1.generation;
        SongState e2 = enrichProc.process("Test Track", "Test Singer", "Great Album", 150000, null, 5000);
        if (e2.isNewSong) throw new AssertionError("Enrichment should NOT be new song!");
        if (!e2.isEnrichment) throw new AssertionError("Expected isEnrichment true!");
        if (e2.generation != eGen) throw new AssertionError("Generation changed during enrichment!");
        if (!"Great Album".equals(e2.realAlbum)) throw new AssertionError("Album not enriched!");
        if (e2.duration != 150000) throw new AssertionError("Duration not enriched!");
        System.out.println("PASS: Metadata enrichment does not bump generation or trigger new song.");

        System.out.println("ALL JAVA LOGIC VERIFICATIONS PASSED!");
    }
}
