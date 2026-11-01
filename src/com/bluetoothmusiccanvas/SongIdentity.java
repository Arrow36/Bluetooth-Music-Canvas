package com.bluetoothmusiccanvas;

import java.util.Locale;

/**
 * Immutable canonical identity of a media track and cache key builder.
 * Retains version tags (Live, Remix, Acoustic) and avoids cross-album cache contamination.
 */
public class SongIdentity {
    public final String title;
    public final String artist;
    public final String album;
    public final String cacheKey;

    public SongIdentity(String rawTitle, String rawArtist, String rawAlbum) {
        this.title = rawTitle != null ? rawTitle.trim() : "";
        this.artist = rawArtist != null ? rawArtist.trim() : "";
        this.album = rawAlbum != null ? rawAlbum.trim() : "";
        this.cacheKey = buildCacheKey(this.title, this.artist, this.album);
    }

    public static String buildCacheKey(String rawTitle, String rawArtist, String rawAlbum) {
        String title = normalizeForCache(rawTitle);
        String artist = normalizeForCache(rawArtist);
        String album = (rawAlbum != null) ? rawAlbum.trim().toLowerCase(Locale.ROOT) : "";
        return title + "\t" + artist + (album.isEmpty() ? "" : "\t" + album);
    }

    public static String normalizeForCache(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("^[《<“\"'「『\\s]+|[》>”\"'」』\\s]+$", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SongIdentity)) return false;
        SongIdentity that = (SongIdentity) o;
        return title.equalsIgnoreCase(that.title)
                && artist.equalsIgnoreCase(that.artist)
                && album.equalsIgnoreCase(that.album);
    }

    @Override
    public int hashCode() {
        return cacheKey.hashCode();
    }

    @Override
    public String toString() {
        return cacheKey;
    }
}
