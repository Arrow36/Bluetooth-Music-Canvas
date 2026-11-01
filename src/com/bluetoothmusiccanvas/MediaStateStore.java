package com.bluetoothmusiccanvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for Bluetooth playback state, metadata, and generation lifecycle.
 * All mutations occur sequentially on the designated Handler/State thread.
 */
public class MediaStateStore {

    public enum ChangeType {
        SESSION_RESET,
        NEW_TRACK,
        METADATA_ENRICHMENT,
        STREAM_LYRIC,
        PLAYBACK_STATE,
        AUDIO_ENERGY
    }

    public static class PlaybackClock {
        public final boolean isPlaying;
        public final long positionMs;
        public final long lastUpdateTimeMs;
        public final float speed;
        public final boolean positionKnown;

        public PlaybackClock(boolean isPlaying, long positionMs, long lastUpdateTimeMs, float speed, boolean positionKnown) {
            this.isPlaying = isPlaying;
            this.positionMs = Math.max(0, positionMs);
            this.lastUpdateTimeMs = lastUpdateTimeMs > 0 ? lastUpdateTimeMs : System.currentTimeMillis();
            this.speed = (speed > 0 && !Float.isNaN(speed)) ? speed : 1.0f;
            this.positionKnown = positionKnown;
        }

        public long getEstimatedPositionMs(long nowMs) {
            if (!isPlaying || !positionKnown || positionMs < 0) {
                return positionMs;
            }
            long delta = nowMs - lastUpdateTimeMs;
            if (delta <= 0) return positionMs;
            return positionMs + (long) (delta * speed);
        }
    }

    public static class MediaSnapshot {
        public final long sessionEpoch;
        public final long playbackGeneration;
        public final long snapshotRevision;
        public final String title;
        public final String artist;
        public final String album;
        public final long duration;
        public final String streamLyric;
        public final String songCacheKey;
        public final MetadataNormalizer.MetadataFormat format;
        public final PlaybackClock clock;
        public final boolean isAmbientAudio;
        public final ChangeType changeType;

        public MediaSnapshot(long sessionEpoch, long playbackGeneration, long snapshotRevision,
                             String title, String artist, String album, long duration,
                             String streamLyric, String songCacheKey,
                             MetadataNormalizer.MetadataFormat format,
                             PlaybackClock clock, boolean isAmbientAudio,
                             ChangeType changeType) {
            this.sessionEpoch = sessionEpoch;
            this.playbackGeneration = playbackGeneration;
            this.snapshotRevision = snapshotRevision;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.duration = Math.max(0, duration);
            this.streamLyric = streamLyric != null ? streamLyric : "";
            this.songCacheKey = songCacheKey != null ? songCacheKey : "";
            this.format = format != null ? format : MetadataNormalizer.MetadataFormat.UNKNOWN;
            this.clock = clock != null ? clock : new PlaybackClock(false, 0, System.currentTimeMillis(), 1.0f, false);
            this.isAmbientAudio = isAmbientAudio;
            this.changeType = changeType;
        }

        public boolean hasTrack() {
            return !title.isEmpty();
        }
    }

    public interface Listener {
        void onMediaStateChanged(MediaSnapshot snapshot);
    }

    private static MediaStateStore sInstance;

    public static synchronized MediaStateStore getInstance() {
        if (sInstance == null) {
            sInstance = new MediaStateStore();
        }
        return sInstance;
    }

    // State fields
    private long mSessionEpoch = 1;
    private long mPlaybackGeneration = 0;
    private long mSnapshotRevision = 0;

    private String mTitle = "";
    private String mArtist = "";
    private String mAlbum = "";
    private long mDuration = 0;
    private String mStreamLyric = "";
    private String mSongCacheKey = "";
    private MetadataNormalizer.MetadataFormat mFormat = MetadataNormalizer.MetadataFormat.UNKNOWN;

    private PlaybackClock mClock = new PlaybackClock(false, 0, System.currentTimeMillis(), 1.0f, false);
    private boolean mIsAmbientAudio = false;

    private final List<Listener> mListeners = new ArrayList<>();

    public synchronized void addListener(Listener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public synchronized void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public synchronized long getSessionEpoch() {
        return mSessionEpoch;
    }

    public synchronized long getPlaybackGeneration() {
        return mPlaybackGeneration;
    }

    public synchronized long getSnapshotRevision() {
        return mSnapshotRevision;
    }

    public synchronized MediaSnapshot getSnapshot() {
        return buildSnapshot(ChangeType.PLAYBACK_STATE);
    }

    private MediaSnapshot buildSnapshot(ChangeType type) {
        return new MediaSnapshot(
                mSessionEpoch,
                mPlaybackGeneration,
                mSnapshotRevision,
                mTitle,
                mArtist,
                mAlbum,
                mDuration,
                mStreamLyric,
                mSongCacheKey,
                mFormat,
                mClock,
                mIsAmbientAudio,
                type
        );
    }

    private void notifyListeners(MediaSnapshot snapshot) {
        for (Listener l : new ArrayList<>(mListeners)) {
            try {
                l.onMediaStateChanged(snapshot);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Starts a new session (e.g. Bluetooth connection state change or MediaController change).
     * Increments sessionEpoch and drops any pending in-flight requests from the older session.
     */
    public synchronized MediaSnapshot startNewSession(String sessionToken) {
        mSessionEpoch++;
        mSnapshotRevision++;
        // Retain playback clock paused
        mClock = new PlaybackClock(false, 0, System.currentTimeMillis(), 1.0f, false);
        mIsAmbientAudio = false;
        MediaSnapshot snapshot = buildSnapshot(ChangeType.SESSION_RESET);
        notifyListeners(snapshot);
        return snapshot;
    }

    /**
     * Processes incoming AVRCP metadata.
     * Guarantees:
     * - Stale session events are dropped.
     * - Identical duplicate notifications do NOT bump generation, do NOT reset clock, and do NOT emit duplicate events.
     * - Metadata enrichment (album/duration) updates fields without bumping generation.
     * - Stream lyrics update dynamic lyric line without bumping generation.
     * - Genuinely new tracks bump playbackGeneration and reset clock position.
     */
    public synchronized MediaSnapshot handleMetadata(long sessionEpoch, String rawTitle, String rawArtist,
                                                      String rawAlbum, long duration, String mediaId) {
        if (sessionEpoch != mSessionEpoch) {
            // Stale session event; drop immediately
            return null;
        }

        MetadataNormalizer.NormalizedResult normalized = MetadataNormalizer.normalize(
                rawTitle, rawArtist, rawAlbum, duration, mTitle, mArtist, mFormat
        );

        if (normalized == null || normalized.title.isEmpty()) {
            return null;
        }

        boolean sameSongIdentity = mTitle.equalsIgnoreCase(normalized.title)
                && mArtist.equalsIgnoreCase(normalized.artist);

        if (normalized.isStreamLyricUpdate && sameSongIdentity) {
            // Case 1: Stream lyric line update for current song
            if (mStreamLyric.equals(normalized.streamLyric)) {
                return null; // duplicate line
            }
            mStreamLyric = normalized.streamLyric;
            mSnapshotRevision++;
            MediaSnapshot snapshot = buildSnapshot(ChangeType.STREAM_LYRIC);
            notifyListeners(snapshot);
            return snapshot;
        }

        if (sameSongIdentity) {
            // Case 2: Metadata received for currently playing song
            boolean isDuplicate = mAlbum.equalsIgnoreCase(normalized.album)
                    && (mDuration == normalized.duration || normalized.duration <= 0);

            if (isDuplicate) {
                // Exact duplicate notification: ignore completely!
                return null;
            }

            boolean isEnrichment = (mAlbum.isEmpty() && !normalized.album.isEmpty())
                    || (mDuration <= 0 && normalized.duration > 0);

            if (isEnrichment) {
                if (!normalized.album.isEmpty()) mAlbum = normalized.album;
                if (normalized.duration > 0) mDuration = normalized.duration;
                mSongCacheKey = SongIdentity.buildCacheKey(mTitle, mArtist, mAlbum);
                mSnapshotRevision++;
                MediaSnapshot snapshot = buildSnapshot(ChangeType.METADATA_ENRICHMENT);
                notifyListeners(snapshot);
                return snapshot;
            }
        }

        // Case 3: Genuinely new track loaded (or A -> B -> A transition)
        mPlaybackGeneration++;
        mSnapshotRevision++;
        mTitle = normalized.title;
        mArtist = normalized.artist;
        mAlbum = normalized.album;
        mDuration = normalized.duration;
        mStreamLyric = normalized.streamLyric;
        mFormat = normalized.format;
        mSongCacheKey = SongIdentity.buildCacheKey(mTitle, mArtist, mAlbum);
        mIsAmbientAudio = false;

        // Reset clock position for new song
        mClock = new PlaybackClock(mClock.isPlaying, 0, System.currentTimeMillis(), mClock.speed, false);

        MediaSnapshot snapshot = buildSnapshot(ChangeType.NEW_TRACK);
        notifyListeners(snapshot);
        return snapshot;
    }

    /**
     * Updates playback state (playing, position, speed).
     */
    public synchronized MediaSnapshot updatePlaybackState(long sessionEpoch, boolean isPlaying,
                                                         long positionMs, float speed, long updateTimeMs) {
        if (sessionEpoch != mSessionEpoch) {
            return null;
        }

        boolean posKnown = positionMs >= 0;
        PlaybackClock newClock = new PlaybackClock(
                isPlaying,
                positionMs >= 0 ? positionMs : mClock.positionMs,
                updateTimeMs > 0 ? updateTimeMs : System.currentTimeMillis(),
                speed,
                posKnown || mClock.positionKnown
        );

        if (mClock.isPlaying == newClock.isPlaying
                && Math.abs(mClock.positionMs - newClock.positionMs) < 100
                && Math.abs(mClock.speed - newClock.speed) < 0.01f) {
            return null; // no meaningful change
        }

        mClock = newClock;
        mSnapshotRevision++;
        MediaSnapshot snapshot = buildSnapshot(ChangeType.PLAYBACK_STATE);
        notifyListeners(snapshot);
        return snapshot;
    }

    /**
     * Updates ambient audio detection (from TV speaker / external audio).
     */
    public synchronized MediaSnapshot setAmbientAudioActive(boolean active) {
        if (mIsAmbientAudio == active) return null;
        mIsAmbientAudio = active;
        mSnapshotRevision++;
        MediaSnapshot snapshot = buildSnapshot(ChangeType.AUDIO_ENERGY);
        notifyListeners(snapshot);
        return snapshot;
    }
}
