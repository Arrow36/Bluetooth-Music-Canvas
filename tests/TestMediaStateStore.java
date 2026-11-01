package tests;

import com.bluetoothmusiccanvas.MediaStateStore;
import com.bluetoothmusiccanvas.MetadataNormalizer;

public class TestMediaStateStore {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("Starting MediaStateStore & Normalizer Test Suite");
        System.out.println("==================================================");

        MediaStateStore store = new MediaStateStore();
        long epoch = store.getSessionEpoch();

        // 1. Initial State
        assert store.getPlaybackGeneration() == 0 : "Initial generation must be 0";

        // 2. Load Song A
        MediaStateStore.MediaSnapshot snap1 = store.handleMetadata(epoch, "カタオモイ", "Aimer", "daydream", 207000, "id1");
        assert snap1 != null : "Snapshot 1 should not be null";
        assert snap1.playbackGeneration == 1 : "Generation must be 1";
        assert snap1.title.equals("カタオモイ") : "Title must match";
        assert snap1.artist.equals("Aimer") : "Artist must match";
        System.out.println("  PASS [1]: First track loaded with generation 1");

        // 3. Stale session event must be dropped
        MediaStateStore.MediaSnapshot staleSnap = store.handleMetadata(epoch - 1, "Stale Song", "Old Artist", "", 1000, "stale");
        assert staleSnap == null : "Stale epoch event must be dropped";
        assert store.getPlaybackGeneration() == 1 : "Generation must remain 1";
        System.out.println("  PASS [2]: Stale session event dropped");

        // 4. Exact duplicate metadata notification from AVRCP must NOT increment generation
        MediaStateStore.MediaSnapshot dupSnap = store.handleMetadata(epoch, "カタオモイ", "Aimer", "daydream", 207000, "id1");
        assert dupSnap == null : "Exact duplicate must be ignored (returns null)";
        assert store.getPlaybackGeneration() == 1 : "Generation must still be 1 after duplicate";
        System.out.println("  PASS [3]: Duplicate AVRCP broadcast ignored without bumping generation");

        // 5. Metadata enrichment (duration was 0, now known)
        MediaStateStore store2 = new MediaStateStore();
        long epoch2 = store2.getSessionEpoch();
        store2.handleMetadata(epoch2, "Song Enrichment", "Artist", "", 0, "id2");
        assert store2.getPlaybackGeneration() == 1;
        MediaStateStore.MediaSnapshot enrichSnap = store2.handleMetadata(epoch2, "Song Enrichment", "Artist", "New Album", 180000, "id2");
        assert enrichSnap != null : "Enrichment snapshot should be produced";
        assert enrichSnap.changeType == MediaStateStore.ChangeType.METADATA_ENRICHMENT : "ChangeType must be METADATA_ENRICHMENT";
        assert store2.getPlaybackGeneration() == 1 : "Generation must remain 1 on enrichment";
        assert enrichSnap.album.equals("New Album") : "Album enriched";
        assert enrichSnap.duration == 180000 : "Duration enriched";
        System.out.println("  PASS [4]: Metadata enrichment updates fields without bumping generation");

        // 6. Rapid A -> B -> A switching
        MediaStateStore store3 = new MediaStateStore();
        long epoch3 = store3.getSessionEpoch();
        MediaStateStore.MediaSnapshot sA1 = store3.handleMetadata(epoch3, "Song A", "Artist X", "", 100, "1");
        assert sA1.playbackGeneration == 1;
        MediaStateStore.MediaSnapshot sB = store3.handleMetadata(epoch3, "Song B", "Artist Y", "", 100, "2");
        assert sB.playbackGeneration == 2;
        MediaStateStore.MediaSnapshot sA2 = store3.handleMetadata(epoch3, "Song A", "Artist X", "", 100, "1");
        assert sA2.playbackGeneration == 3 : "A -> B -> A must increment generation to 3";
        System.out.println("  PASS [5]: Rapid A -> B -> A track switching increments generation properly");

        // 7. Punctuation songs must NOT be inverted as car lyrics
        MetadataNormalizer.NormalizedResult normPunct = MetadataNormalizer.normalize(
                "Hello, Goodbye", "The Beatles / John Lennon", "Magical Mystery Tour", 210000,
                "", "", MetadataNormalizer.MetadataFormat.UNKNOWN
        );
        assert normPunct != null;
        assert normPunct.title.equals("Hello, Goodbye") : "Title with comma must not be inverted, got: " + normPunct.title;
        assert normPunct.artist.equals("The Beatles / John Lennon") : "Artist must be preserved";
        assert normPunct.format == MetadataNormalizer.MetadataFormat.STANDARD : "Must be STANDARD format";
        System.out.println("  PASS [6]: Song with punctuation correctly preserved without false inversion");

        // 8. Real car Bluetooth lyric stream
        MetadataNormalizer.NormalizedResult carInit = MetadataNormalizer.normalize(
                "纯音乐，请欣赏", "王菲 / 如愿", "如愿", 240000,
                "", "", MetadataNormalizer.MetadataFormat.UNKNOWN
        );
        assert carInit != null;
        assert carInit.title.equals("如愿") : "Car lyric candidate title restored as real title, got: " + carInit.title;
        assert carInit.artist.equals("王菲") : "Performer restored as artist, got: " + carInit.artist;
        assert carInit.format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED : "Format must be CAR_LYRICS_CONFIRMED";
        System.out.println("  PASS [7]: Car Bluetooth lyric track placeholder properly identified");

        // Subsequent line of car stream lyric for same track
        MetadataNormalizer.NormalizedResult carLine2 = MetadataNormalizer.normalize(
                "你是 岁月长河 星火", "王菲 / 如愿", "如愿", 240000,
                "如愿", "王菲", MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED
        );
        assert carLine2 != null;
        assert carLine2.isStreamLyricUpdate : "Must be marked as stream lyric update";
        assert carLine2.title.equals("如愿") : "Title remains 如愿";
        assert carLine2.streamLyric.equals("你是 岁月长河 星火") : "Stream lyric line captured";
        System.out.println("  PASS [8]: Car Bluetooth dynamic lyric stream line captured without replacing song title");

        System.out.println("==================================================");
        System.out.println("All MediaStateStore & Normalizer tests PASSED!");
        System.out.println("==================================================");
    }
}
