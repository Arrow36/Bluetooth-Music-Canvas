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

        // 8. Real car Bluetooth lyric stream (NetEase style: Artist / Title)
        MetadataNormalizer.NormalizedResult carInit = MetadataNormalizer.normalize(
                "纯音乐，请欣赏", "王菲 / 如愿", "如愿", 240000,
                "", "", MetadataNormalizer.MetadataFormat.UNKNOWN
        );
        assert carInit != null;
        assert carInit.title.equals("如愿") : "Car lyric candidate title restored as real title, got: " + carInit.title;
        assert carInit.artist.equals("王菲") : "Performer restored as artist, got: " + carInit.artist;
        assert carInit.format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED : "Format must be CAR_LYRICS_CONFIRMED";
        System.out.println("  PASS [7]: NetEase car Bluetooth lyric track placeholder properly identified");

        // Subsequent line of NetEase car stream lyric for same track
        MetadataNormalizer.NormalizedResult carLine2 = MetadataNormalizer.normalize(
                "你是 岁月长河 星火", "王菲 / 如愿", "如愿", 240000,
                "如愿", "王菲", MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED
        );
        assert carLine2 != null;
        assert carLine2.isStreamLyricUpdate : "Must be marked as stream lyric update";
        assert carLine2.title.equals("如愿") : "Title remains 如愿";
        assert carLine2.streamLyric.equals("你是 岁月长河 星火") : "Stream lyric line captured";
        System.out.println("  PASS [8]: NetEase car Bluetooth dynamic lyric stream line captured without replacing song title");

        // 9. QQ Music Car Bluetooth Lyric Format: "Title — Artist" with placeholder title
        MetadataNormalizer.NormalizedResult qqInit = MetadataNormalizer.normalize(
                "制作人: 董冬冬", "花开满篱院 — 谭松韵", "兰香如故 影视剧原声带", 185000,
                "", "", MetadataNormalizer.MetadataFormat.UNKNOWN
        );
        assert qqInit != null;
        assert qqInit.title.equals("花开满篱院") : "QQ Music real title extracted, got: " + qqInit.title;
        assert qqInit.artist.equals("谭松韵") : "QQ Music real artist extracted, got: " + qqInit.artist;
        assert qqInit.album.equals("兰香如故 影视剧原声带") : "Album preserved, got: " + qqInit.album;
        assert qqInit.format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED : "Must be CAR_LYRICS_CONFIRMED";
        assert !qqInit.isStreamLyricUpdate : "First track event is not a stream lyric update";
        System.out.println("  PASS [9]: QQ Music car Bluetooth lyric initial track identified with title and artist separated by em-dash");

        // 10. QQ Music subsequent lyric lines (production credits and lyrics)
        MetadataNormalizer.NormalizedResult qqLine2 = MetadataNormalizer.normalize(
                "和声编写/和声:陈无二", "花开满篱院 — 谭松韵", "兰香如故 影视剧原声带", 185000,
                "花开满篱院", "谭松韵", MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED
        );
        assert qqLine2 != null;
        assert qqLine2.isStreamLyricUpdate : "Must be marked as stream lyric update";
        assert qqLine2.title.equals("花开满篱院") : "Title remains 花开满篱院";
        assert qqLine2.streamLyric.equals("和声编写/和声:陈无二") : "Stream lyric captured correctly, got: " + qqLine2.streamLyric;

        MetadataNormalizer.NormalizedResult qqLine3 = MetadataNormalizer.normalize(
                "花开满篱院 芬芳了流年", "花开满篱院 — 谭松韵", "兰香如故 影视剧原声带", 185000,
                "花开满篱院", "谭松韵", MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED
        );
        assert qqLine3 != null;
        assert qqLine3.isStreamLyricUpdate : "Lyric line is stream lyric update";
        assert qqLine3.title.equals("花开满篱院") : "Title remains steady";
        assert qqLine3.streamLyric.equals("花开满篱院 芬芳了流年") : "Actual lyric line captured";
        System.out.println("  PASS [10]: QQ Music subsequent dynamic lyric lines update streamLyric without resetting track");

        // 11. QQ Music format with embedded album after dot: "Title — Artist · Album"
        MetadataNormalizer.NormalizedResult qqWithAlbum = MetadataNormalizer.normalize(
                "制作人: 董冬冬", "我来书写我的名 — 王铮亮 · 兰香如故 影视剧原声带", "", 210000,
                "", "", MetadataNormalizer.MetadataFormat.UNKNOWN
        );
        assert qqWithAlbum != null;
        assert qqWithAlbum.title.equals("我来书写我的名") : "Title extracted: " + qqWithAlbum.title;
        assert qqWithAlbum.artist.equals("王铮亮") : "Artist extracted: " + qqWithAlbum.artist;
        assert qqWithAlbum.album.equals("兰香如故 影视剧原声带") : "Album extracted from artist field: " + qqWithAlbum.album;
        assert qqWithAlbum.format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED;
        System.out.println("  PASS [11]: QQ Music embedded album with middle dot parsed properly");

        // 12. MediaStateStore handles QQ Music stream lyrics end-to-end without bumping generation
        MediaStateStore qqStore = new MediaStateStore();
        long qqEpoch = qqStore.getSessionEpoch();
        MediaStateStore.MediaSnapshot qqSnap1 = qqStore.handleMetadata(
                qqEpoch, "制作人: 董冬冬", "花开满篱院 — 谭松韵", "兰香如故 影视剧原声带", 185000, "qq1"
        );
        assert qqSnap1 != null;
        assert qqSnap1.playbackGeneration == 1 : "First track generation must be 1";
        assert qqSnap1.title.equals("花开满篱院") : "Real title is 花开满篱院";
        assert qqSnap1.artist.equals("谭松韵") : "Real artist is 谭松韵";

        MediaStateStore.MediaSnapshot qqSnap2 = qqStore.handleMetadata(
                qqEpoch, "和声编写/和声:陈无二", "花开满篱院 — 谭松韵", "兰香如故 影视剧原声带", 185000, "qq1"
        );
        assert qqSnap2 != null;
        assert qqSnap2.changeType == MediaStateStore.ChangeType.STREAM_LYRIC : "ChangeType must be STREAM_LYRIC";
        assert qqSnap2.playbackGeneration == 1 : "Generation must NOT bump on lyric line";
        assert qqSnap2.streamLyric.equals("和声编写/和声:陈无二");

        MediaStateStore.MediaSnapshot qqSnap3 = qqStore.handleMetadata(
                qqEpoch, "花开满篱院 芬芳了流年", "花开满篱院 — 谭松韵", "兰香如故 影视剧原声带", 185000, "qq1"
        );
        assert qqSnap3 != null;
        assert qqSnap3.changeType == MediaStateStore.ChangeType.STREAM_LYRIC;
        assert qqSnap3.playbackGeneration == 1 : "Generation still 1";
        assert qqSnap3.streamLyric.equals("花开满篱院 芬芳了流年");
        System.out.println("  PASS [12]: MediaStateStore end-to-end handles QQ Music car Bluetooth lyrics with steady generation 1");

        // 13. NetEase mid-song Bluetooth connection or app restart:
        // When connecting in the middle of a track, Title carries an active lyric line (e.g. "It's a sign of the times")
        // and Artist is "Harry Styles/Sign of the Times". Must immediately extract real title "Sign of the Times".
        MetadataNormalizer.NormalizedResult neteaseMidSong = MetadataNormalizer.normalize(
                "It's a sign of the times", "Harry Styles/Sign of the Times", "Harry Styles", 340000,
                "", "", MetadataNormalizer.MetadataFormat.UNKNOWN
        );
        assert neteaseMidSong != null;
        assert neteaseMidSong.title.equals("Sign of the Times") : "Real title must be Sign of the Times, got: " + neteaseMidSong.title;
        assert neteaseMidSong.artist.equals("Harry Styles") : "Performer must be Harry Styles, got: " + neteaseMidSong.artist;
        assert neteaseMidSong.album.equals("Harry Styles");
        assert neteaseMidSong.streamLyric.equals("It's a sign of the times") : "Active lyric captured as streamLyric, got: " + neteaseMidSong.streamLyric;
        assert neteaseMidSong.format == MetadataNormalizer.MetadataFormat.CAR_LYRICS_CONFIRMED;
        System.out.println("  PASS [13]: NetEase mid-song connection correctly extracts real title instead of dynamic lyric");

        // 14. NetEase mid-song end-to-end MediaStateStore session
        MediaStateStore neteaseStore = new MediaStateStore();
        long neEpoch = neteaseStore.getSessionEpoch();
        MediaStateStore.MediaSnapshot neSnap1 = neteaseStore.handleMetadata(
                neEpoch, "It's a sign of the times", "Harry Styles/Sign of the Times", "Harry Styles", 340000, "ne1"
        );
        assert neSnap1 != null;
        assert neSnap1.title.equals("Sign of the Times");
        assert neSnap1.artist.equals("Harry Styles");
        assert neSnap1.playbackGeneration == 1;

        MediaStateStore.MediaSnapshot neSnap2 = neteaseStore.handleMetadata(
                neEpoch, "We got to, we got to（away）", "Harry Styles/Sign of the Times", "Harry Styles", 340000, "ne1"
        );
        assert neSnap2 != null;
        assert neSnap2.changeType == MediaStateStore.ChangeType.STREAM_LYRIC;
        assert neSnap2.playbackGeneration == 1;
        assert neSnap2.title.equals("Sign of the Times");
        assert neSnap2.streamLyric.equals("We got to, we got to（away）");
        System.out.println("  PASS [14]: NetEase mid-song stream lyrics maintain steady track title and generation across subsequent lines");

        System.out.println("==================================================");
        System.out.println("All MediaStateStore & Normalizer tests PASSED!");
        System.out.println("==================================================");
    }
}
