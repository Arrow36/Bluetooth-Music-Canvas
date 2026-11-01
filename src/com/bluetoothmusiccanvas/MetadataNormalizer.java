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

        // Parse candidateTitle and performers from rawArtist if separated by '/' or ' - '
        String candidateTitle = null;
        String performers = null;
        int lastSlash = artist.lastIndexOf('/');
        if (lastSlash > 0) {
            candidateTitle = artist.substring(lastSlash + 1).trim();
            performers = artist.substring(0, lastSlash).trim();
        } else {
            int lastDash = artist.lastIndexOf(" - ");
            if (lastDash > 0) {
                candidateTitle = artist.substring(lastDash + 3).trim();
                performers = artist.substring(0, lastDash).trim();
            }
        }

        // Check if rawTitle is an explicit car lyrics placeholder or production credit
        boolean isPlaceholder = isExplicitLyricPlaceholder(title);

        // Case 1: Active car Bluetooth stream for the currently playing song
        // In car lyric mode, subsequent AVRCP updates keep the same song in Artist (performers / candidateTitle),
        // while Title carries the next line of real-time lyrics.
        if (currentTitle != null && !currentTitle.isEmpty() && candidateTitle != null && !candidateTitle.isEmpty()) {
            boolean matchesPlayingSong = candidateTitle.equalsIgnoreCase(currentTitle)
                    || (performers != null && performers.equalsIgnoreCase(currentArtist) && !candidateTitle.equalsIgnoreCase(title));

            if (matchesPlayingSong && (currentFormat == MetadataFormat.CAR_LYRICS_CONFIRMED || isPlaceholder || !candidateTitle.equalsIgnoreCase(title))) {
                String streamLine = isPlaceholder ? "" : title;
                return new NormalizedResult(
                        currentTitle,
                        (performers != null && !performers.isEmpty()) ? performers : currentArtist,
                        album,
                        duration,
                        streamLine,
                        MetadataFormat.CAR_LYRICS_CONFIRMED,
                        true
                );
            }
        }

        // Case 2: New track with candidateTitle in artist field
        if (candidateTitle != null && !candidateTitle.isEmpty() && performers != null && !performers.isEmpty()) {
            boolean titleMatchesCandidate = candidateTitle.equalsIgnoreCase(title);
            boolean albumConfirmsCandidate = (!album.isEmpty()
                    && (candidateTitle.equalsIgnoreCase(album) || album.toLowerCase(Locale.ROOT).contains(candidateTitle.toLowerCase(Locale.ROOT)))
                    && !title.equalsIgnoreCase(album));

            if (titleMatchesCandidate) {
                // Artist redundantly included the title: "Artist / Title" with Title being "Title"
                // Clean artist, format is standard song
                return new NormalizedResult(
                        title,
                        performers,
                        album,
                        duration,
                        "",
                        MetadataFormat.STANDARD,
                        false
                );
            } else if (isPlaceholder || albumConfirmsCandidate) {
                // Title is an explicit placeholder or album confirms candidate is the real track title
                return new NormalizedResult(
                        candidateTitle,
                        performers,
                        album,
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
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("纯音乐") || text.contains("请欣赏") || text.contains("没有填词")
                || text.contains("享受音乐") || text.contains("作词") || text.contains("作曲")
                || text.contains("编曲") || text.contains("制作") || text.contains("监制")
                || text.contains("混音") || text.contains("母带") || text.contains("录音")
                || text.contains("出品") || text.contains("原唱")
                || lower.contains("producer") || lower.contains("composer")
                || lower.contains("lyricist") || lower.contains("instrumental");
    }
}
