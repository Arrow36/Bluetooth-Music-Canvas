package tests;

import com.bluetoothmusiccanvas.MetadataFetcher;
import com.bluetoothmusiccanvas.SongIdentity;

public class TestStep2CacheAndTickets {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("Starting Step 2 Cache & Request Ticket Test Suite");
        System.out.println("==================================================");

        // 1. Unified Cache Key Format
        String key1 = SongIdentity.buildCacheKey("Song Title", "Artist Name", "Album Name");
        String key2 = MetadataFetcher.buildCacheKey("Song Title", "Artist Name", "Album Name");
        assert key1.equals(key2) : "SongIdentity and MetadataFetcher keys must match exactly";
        assert key1.equals("song title\tartist name\talbum name") : "Key format must be title\tartist\talbum";
        System.out.println("  PASS [1]: Canonical cache key unified between SongIdentity and MetadataFetcher");

        // 2. Cross-album cache isolation
        MetadataFetcher.CachedMeta metaA = new MetadataFetcher.CachedMeta("http://coverA.jpg", "Lyric A", "netease");
        MetadataFetcher.putInCache("Song A", "Singer X", "Album 1", metaA);

        MetadataFetcher.CachedMeta cachedA = MetadataFetcher.getCached("Song A", "Singer X", "Album 1");
        assert cachedA != null : "Album 1 should hit cache";
        assert "http://coverA.jpg".equals(cachedA.coverUrl) : "Cover A matches";

        // Querying Album 2 for the same song MUST NOT return Album 1's cache!
        MetadataFetcher.CachedMeta cachedB = MetadataFetcher.getCached("Song A", "Singer X", "Album 2");
        assert cachedB == null : "Cross-album pollution check: Album 2 must NOT hit Album 1's cache!";
        System.out.println("  PASS [2]: Cross-album cache contamination prevented (Album 2 misses Album 1)");

        // 3. Fallback when album is empty or unknown
        MetadataFetcher.CachedMeta metaGeneric = new MetadataFetcher.CachedMeta("http://generic.jpg", "Generic Lyric", "qq");
        MetadataFetcher.putInCache("Generic Song", "Generic Artist", "", metaGeneric);

        MetadataFetcher.CachedMeta retrievedGeneric = MetadataFetcher.getCached("Generic Song", "Generic Artist", "");
        assert retrievedGeneric != null : "Empty album query must hit empty album cache";
        assert "http://generic.jpg".equals(retrievedGeneric.coverUrl);
        System.out.println("  PASS [3]: Empty-album query correctly hits generic cache entry");

        // 4. Case-insensitivity and whitespace trim in cache keys
        MetadataFetcher.CachedMeta retrievedCase = MetadataFetcher.getCached("  SONG A  ", "  singer x  ", "  album 1  ");
        assert retrievedCase != null : "Cache lookup must be case-insensitive and trimmed";
        assert "http://coverA.jpg".equals(retrievedCase.coverUrl);
        System.out.println("  PASS [4]: Cache lookup handles case and whitespace normalization");

        // 5. Album enrichment inherits initial empty-album cache entry without duplicate network fetch
        MetadataFetcher.CachedMeta enrichedHit = MetadataFetcher.getCached("Generic Song", "Generic Artist", "Enriched Album");
        assert enrichedHit != null : "Enriched album query should inherit generic (empty-album) cache entry";
        assert "http://generic.jpg".equals(enrichedHit.coverUrl);
        System.out.println("  PASS [5]: Album enrichment inherits generic empty-album cache entry without re-fetch");

        System.out.println("==================================================");
        System.out.println("All Step 2 Cache & Ticket tests PASSED!");
        System.out.println("==================================================");
    }
}
