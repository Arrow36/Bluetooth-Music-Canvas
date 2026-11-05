package com.bluetoothmusiccanvas;

import java.util.Locale;

/**
 * Normalizes AVRCP metadata across diverse Bluetooth players (NetEase, QQ Music, Kugou, Apple Music, Spotify).
 * Handles car Bluetooth AVRCP lyric delivery ("车载蓝牙歌词") safely without misinterpreting
 * standard songs containing punctuation as lyrics.
 */
public class MetadataNormalizer {

    public enum MetadataFormat {
        STANDARD,
        CAR_LYRICS_CANDIDATE,
        CAR_LYRICS_CONFIRMED,
        UNKNOWN
    }

    public static class NormalizedResult {
        public final String title;
        public final String artist;
        public final String album;
        public final long duration;
        public final String streamLyric;
        public final MetadataFormat format;
        public final boolean isStreamLyricUpdate;

        public NormalizedResult(String title, String artist, String album, long duration,
                                String streamLyric, MetadataFormat format, boolean isStreamLyricUpdate) {
            this.title = title != null ? title.trim() : "";
            this.artist = artist != null ? artist.trim() : "";
            this.album = album != null ? album.trim() : "";
            this.duration = Math.max(0, duration);
            this.streamLyric = streamLyric != null ? streamLyric.trim() : "";
            this.format = format;
            this.isStreamLyricUpdate = isStreamLyricUpdate;
        }
    }

    public static class CandidateTrack {
        public final String title;
        public final String artist;
        public final String album;
        public final boolean isEmDashCarFormat;

        public CandidateTrack(String title, String artist, String album, boolean isEmDashCarFormat) {
            this.title = title != null ? title.trim() : "";
            this.artist = artist != null ? artist.trim() : "";
            this.album = album != null ? album.trim() : "";
            this.isEmDashCarFormat = isEmDashCarFormat;
        }
    }

    /**
     * Parses candidate title, artist, and optional album from raw artist field.
     * Supports:
     * 1. NetEase format: "Artist / Title"
     * 2. QQ Music / Kugou format: "Title — Artist" or "Title — Artist · Album"
     */
    public static CandidateTrack parseCandidate(String rawArtist, String rawAlbum) {
        if (rawArtist == null || rawArtist.trim().isEmpty()) {
            return null;
        }
        String artist = rawArtist.trim();
        String album = rawAlbum != null ? rawAlbum.trim() : "";

        // 1. NetEase format: "Artist / Title"
        int slashIndex = artist.lastIndexOf('/');
        if (slashIndex > 0 && slashIndex < artist.length() - 1) {
            String candidateTitle = artist.substring(slashIndex + 1).trim();
            String performers = artist.substring(0, slashIndex).trim();
            if (!candidateTitle.isEmpty() && !performers.isEmpty()) {
                return new CandidateTrack(candidateTitle, performers, album, false);
            }
        }

        // 2. QQ Music / Kugou dash format: "Title — Artist" or "Title — Artist · Album"
        // Supports em-dash '—' (\u2014), en-dash '–' (\u2013), double dash '——', or ' - '
        int dashIndex = -1;
        int dashLen = 0;
        boolean isEmDash = false;
        String[] dashSeparators = new String[]{" — ", "—", " – ", "–", "——", " - "};
        for (String sep : dashSeparators) {
            int idx = artist.indexOf(sep);
            if (idx > 0 && idx < artist.length() - sep.length()) {
                dashIndex = idx;
                dashLen = sep.length();
                if (sep.contains("—") || sep.contains("–")) {
                    isEmDash = true;
                }
                break;
            }
        }

        if (dashIndex > 0) {
            String part1 = artist.substring(0, dashIndex).trim();
            String part2 = artist.substring(dashIndex + dashLen).trim();

            // Extract optional album after ' · ' or '・' or ' • ' in part2
            String extraAlbum = album;
            String[] dotSeparators = new String[]{" · ", " ·", "· ", "・", " • "};
            for (String dot : dotSeparators) {
                int dotIdx = part2.indexOf(dot);
                if (dotIdx > 0 && dotIdx < part2.length() - dot.length()) {
                    extraAlbum = part2.substring(dotIdx + dot.length()).trim();
                    part2 = part2.substring(0, dotIdx).trim();
                    break;
                }
            }

            // In Chinese car AVRCP dash format, part1 is song title, part2 is artist (e.g. "花开满篱院 — 谭松韵")
            // If part2 matches album, part2 is title and part1 is artist
            if (!album.isEmpty() && (cleanTitle(part2).equalsIgnoreCase(cleanTitle(album))
                    || cleanTitle(album).contains(cleanTitle(part2)))) {
                return new CandidateTrack(part2, part1, extraAlbum, isEmDash);
            }

            return new CandidateTrack(part1, part2, extraAlbum, isEmDash);
        }

        return null;
    }

    /**
     * Normalizes raw AVRCP metadata into standard title, artist, album, and stream lyric.
     *
     * @param rawTitle       Title from MediaMetadata
     * @param rawArtist      Artist from MediaMetadata
     * @param rawAlbum       Album from MediaMetadata
     * @param duration       Duration in milliseconds
     * @param currentTitle   Current established track title in state store
     * @param currentArtist  Current established track artist in state store
     * @param currentFormat  Current metadata format in state store
     * @return NormalizedResult or null if input is empty/invalid
     */
    public static NormalizedResult normalize(String rawTitle, String rawArtist, String rawAlbum, long duration,
                                             String currentTitle, String currentArtist, MetadataFormat currentFormat) {
        if (rawTitle == null || rawTitle.trim().isEmpty() || "Not Provided".equalsIgnoreCase(rawTitle.trim())) {
            return null;
        }

        String title = rawTitle.trim();
        String artist = rawArtist != null ? rawArtist.trim() : "";
        String album = rawAlbum != null ? rawAlbum.trim() : "";
        duration = Math.max(0, duration);

        CandidateTrack candidate = parseCandidate(artist, album);
        String candidateTitle = candidate != null ? candidate.title : null;
        String performers = candidate != null ? candidate.artist : null;
        String resolvedAlbum = (candidate != null && !candidate.album.isEmpty()) ? candidate.album : album;
        boolean isEmDashCarFormat = candidate != null && candidate.isEmDashCarFormat;

        // Check if rawTitle is an explicit car lyrics placeholder or production credit
        boolean isPlaceholder = isExplicitLyricPlaceholder(title);

        String cleanCandidate = cleanTitle(candidateTitle);
        String cleanAlbum = cleanTitle(resolvedAlbum);
        boolean hasSubtitle = candidateTitle != null && candidateTitle.matches(".*[\\(（\\[].+[\\)）\\]].*");

        boolean albumConfirmsCandidate = false;
        boolean albumConfirmsPerformer = false;
        String cleanPerformers = cleanTitle(performers);
        if (!resolvedAlbum.isEmpty() && candidateTitle != null && !title.equalsIgnoreCase(resolvedAlbum)) {
            if (candidateTitle.equalsIgnoreCase(resolvedAlbum)
                    || cleanCandidate.equalsIgnoreCase(cleanAlbum)
                    || cleanCandidate.equalsIgnoreCase(resolvedAlbum)
                    || candidateTitle.equalsIgnoreCase(cleanAlbum)) {
                albumConfirmsCandidate = true;
            } else if (!cleanAlbum.isEmpty() && !cleanCandidate.isEmpty()) {
                String lowerCandidate = cleanCandidate.toLowerCase(Locale.ROOT);
                String lowerAlbum = cleanAlbum.toLowerCase(Locale.ROOT);
                if (lowerCandidate.contains(lowerAlbum) || lowerAlbum.contains(lowerCandidate)) {
                    albumConfirmsCandidate = true;
                }
            }
        }
        if (!resolvedAlbum.isEmpty() && performers != null && !title.equalsIgnoreCase(resolvedAlbum)) {
            if (performers.equalsIgnoreCase(resolvedAlbum)
                    || cleanPerformers.equalsIgnoreCase(cleanAlbum)
                    || cleanPerformers.equalsIgnoreCase(resolvedAlbum)
                    || performers.equalsIgnoreCase(cleanAlbum)) {
                albumConfirmsPerformer = true;
            } else if (!cleanAlbum.isEmpty() && !cleanPerformers.isEmpty()) {
                String lowerPerf = cleanPerformers.toLowerCase(Locale.ROOT);
                String lowerAlbum = cleanAlbum.toLowerCase(Locale.ROOT);
                if (lowerPerf.contains(lowerAlbum) || lowerAlbum.contains(lowerPerf)) {
                    albumConfirmsPerformer = true;
                }
            }
        }

        boolean titleContainsCandidate = false;
        if (candidateTitle != null && !cleanCandidate.isEmpty()) {
            String lowerTitle = cleanTitle(title).toLowerCase(Locale.ROOT);
            String lowerCandidate = cleanCandidate.toLowerCase(Locale.ROOT);
            if (lowerTitle.contains(lowerCandidate) && !lowerTitle.equals(lowerCandidate)) {
                titleContainsCandidate = true;
            }
        }

        boolean isLyricLike = isLyricLineLike(title);

        // Case 1: Active car Bluetooth stream for the currently playing song
        // In car lyric mode, subsequent AVRCP updates keep the same song in Artist (performers / candidateTitle),
        // while Title carries the next line of real-time lyrics.
        if (currentTitle != null && !currentTitle.isEmpty() && candidateTitle != null && !candidateTitle.isEmpty()) {
            String cleanCur = cleanTitle(currentTitle);
            boolean matchesPlayingSong = candidateTitle.equalsIgnoreCase(currentTitle)
                    || cleanCandidate.equalsIgnoreCase(cleanCur)
                    || candidateTitle.toLowerCase(Locale.ROOT).contains(cleanCur.toLowerCase(Locale.ROOT))
                    || currentTitle.toLowerCase(Locale.ROOT).contains(cleanCandidate.toLowerCase(Locale.ROOT))
                    || (performers != null && performers.equalsIgnoreCase(currentArtist) && !candidateTitle.equalsIgnoreCase(title));

            if (matchesPlayingSong && (currentFormat == MetadataFormat.CAR_LYRICS_CONFIRMED || isPlaceholder || albumConfirmsCandidate || albumConfirmsPerformer || titleContainsCandidate || isLyricLike || !candidateTitle.equalsIgnoreCase(title))) {
                return new NormalizedResult(
                        currentTitle,
                        (performers != null && !performers.isEmpty()) ? performers : currentArtist,
                        resolvedAlbum,
                        duration,
                        title,
                        MetadataFormat.CAR_LYRICS_CONFIRMED,
                        true
                );
            }
        }

        // Case 2: New track with candidateTitle in artist field
        if (candidateTitle != null && !candidateTitle.isEmpty() && performers != null && !performers.isEmpty()) {
            boolean titleMatchesCandidate = candidateTitle.equalsIgnoreCase(title)
                    || cleanCandidate.equalsIgnoreCase(cleanTitle(title));

            if (titleMatchesCandidate) {
                // Artist redundantly included the title: "Artist / Title" with Title being "Title"
                // Clean artist, format is standard song
                return new NormalizedResult(
                        candidateTitle,
                        performers,
                        resolvedAlbum,
                        duration,
                        "",
                        MetadataFormat.STANDARD,
                        false
                );
            } else if (isPlaceholder || albumConfirmsCandidate || albumConfirmsPerformer || titleContainsCandidate || hasSubtitle || isEmDashCarFormat || isLyricLike) {
                // Title is an explicit placeholder, album confirms candidate/performer, title contains candidate, candidate has subtitle, em-dash car format, or title is lyric-line-like
                return new NormalizedResult(
                        candidateTitle,
                        performers,
                        resolvedAlbum,
                        duration,
                        isPlaceholder ? "" : title,
                        MetadataFormat.CAR_LYRICS_CONFIRMED,
                        false
                );
            } else {
                // Standard multi-artist track (e.g. "fun. / Janelle Monáe", "Aimer / chelly")
                // Do NOT invert punctuation or arbitrary tracks
                return new NormalizedResult(
                        title,
                        artist,
                        album,
                        duration,
                        "",
                        MetadataFormat.STANDARD,
                        false
                );
            }
        }

        // Case 3: Standard single artist track
        return new NormalizedResult(
                title,
                artist,
                album,
                duration,
                "",
                MetadataFormat.STANDARD,
                false
        );
    }

    /**
     * Checks if a title string contains known lyric stream placeholders or production credits.
     * Only explicit phrases are treated as placeholders; punctuation alone is NEVER a placeholder.
     */
    public static boolean isExplicitLyricPlaceholder(String text) {
        if (text == null || text.isEmpty()) return false;
        String trimmed = text.trim();
        if (trimmed.equals("//") || trimmed.equals("...") || trimmed.equals("—") || trimmed.equals("-")
                || trimmed.startsWith("//") || trimmed.equals("Not Provided")) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return trimmed.contains("纯音乐") || trimmed.contains("请欣赏") || trimmed.contains("没有填词")
                || trimmed.contains("享受音乐") || trimmed.contains("作词") || trimmed.contains("作曲")
                || trimmed.contains("编曲") || trimmed.contains("制作") || trimmed.contains("监制")
                || trimmed.contains("混音") || trimmed.contains("母带") || trimmed.contains("录音")
                || trimmed.contains("出品") || trimmed.contains("原唱") || trimmed.contains("和声")
                || trimmed.contains("编写") || trimmed.contains("策划") || trimmed.contains("统筹")
                || trimmed.contains("吉他") || trimmed.contains("贝斯") || trimmed.contains("弦乐")
                || trimmed.contains("伴奏") || trimmed.contains("缩混")
                || trimmed.contains("OP：") || trimmed.contains("OP:") || trimmed.contains("SP：") || trimmed.contains("SP:")
                || lower.contains("producer") || lower.contains("composer")
                || lower.contains("lyricist") || lower.contains("instrumental")
                || lower.contains("arranger");
    }

    /**
     * Evaluates whether a string has typical characteristics of a dynamic lyric line.
     * Used when connecting mid-song to prevent interpreting the current active lyric line as the song title.
     * Punctuation alone (e.g. "Hello, Goodbye") does NOT make a title lyric-like.
     */
    public static boolean isLyricLineLike(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        if (isExplicitLyricPlaceholder(t)) return true;
        // 1. Chinese spacing between words (standard in NetEase/QQ car dynamic lyrics to mark phrasing/cadence)
        if (t.matches(".*[\\u4e00-\\u9fa5]+\\s+[\\u4e00-\\u9fa5]+.*")) return true;
        // 2. Trailing ellipsis, tilde, or Chinese comma with phrasing space
        if (t.contains("...") || t.contains("…") || t.contains("~") || t.contains("——")) return true;
        if (t.contains("，") && t.contains(" ")) return true;
        // 3. Parenthetical backing vocals: e.g. "（away）", "(away)"
        if (t.matches(".*[\\(（\\[].*(away|chorus|verse|hook|[\\u4e00-\\u9fa5]).*[\\)）\\]].*")) return true;
        // 4. Typical Western lyric openers and conversational phrases
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("i ") || lower.startsWith("i'm ") || lower.startsWith("i’m ")
                || lower.startsWith("it's ") || lower.startsWith("it’s ")
                || lower.startsWith("we ") || lower.startsWith("we're ") || lower.startsWith("we've ")
                || lower.startsWith("you ") || lower.startsWith("you're ") || lower.startsWith("baby ")
                || lower.startsWith("oh ") || lower.startsWith("yeah ") || lower.startsWith("cause ")
                || lower.contains(" don't ") || lower.contains(" can't ") || lower.contains(" won't ")
                || lower.contains(" gotta ") || lower.contains(" wanna ") || lower.contains(" gonna ")
                || lower.contains(" alright") || lower.contains(" forever") || lower.contains(" tonight")) {
            return true;
        }
        // 5. Long multi-word sentence (> 32 chars with spaces)
        if (t.length() > 32 && t.contains(" ")) return true;
        return false;
    }

    public static String cleanTitle(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s*[\\(（\\[].*?[\\)）\\]]", "").trim();
    }
}
