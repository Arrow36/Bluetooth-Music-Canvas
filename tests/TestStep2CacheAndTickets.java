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

        // 6. Default white-background portrait placeholder cover filtering
        assert !MetadataFetcher.isValidCoverUrl("https://m.kugou.com/static/images/share2014/default_singer.jpg")
                : "Kugou default_singer.jpg placeholder must be rejected";
        assert !MetadataFetcher.isValidCoverUrl("http://singerimg.kugou.com/uploadpic/softhead/400/20260128/20260128120704474362.jpg")
                : "Kugou singerimg avatar must be rejected";
        assert MetadataFetcher.isValidCoverUrl("https://p4.music.126.net/wqyd40jQQLVTY9nynOwETg==/109951171031632866.jpg?param=600y600")
                : "Valid NetEase album cover must be accepted";
        System.out.println("  PASS [6]: Default white-background portrait & singer avatar URLs rejected by isValidCoverUrl");

        // 7. Provider circuit-breaker cooldown & negative cache TTL
        MetadataFetcher.resetProviderCooldowns();
        assert !MetadataFetcher.isProviderInCooldown("netease") : "Provider must start healthy";
        MetadataFetcher.markProviderDegraded("netease", "HTTP 429");
        assert MetadataFetcher.isProviderInCooldown("netease") : "Degraded provider must enter cooldown";
        MetadataFetcher.resetProviderCooldowns();
        assert !MetadataFetcher.isProviderInCooldown("netease") : "Cooldown reset must restore provider";

        MetadataFetcher.recordNegativeCache("Miss Song", "Miss Artist", "Miss Album");
        assert MetadataFetcher.isRecentlyMissed("Miss Song", "Miss Artist", "Miss Album")
                : "Negative cache must record missed track";
        MetadataFetcher.putInCache("Miss Song", "Miss Artist", "Miss Album",
                new MetadataFetcher.CachedMeta("http://cover/ok.jpg", "[00:01] Found", "LRCLIB"));
        assert !MetadataFetcher.isRecentlyMissed("Miss Song", "Miss Artist", "Miss Album")
                : "Positive cache insertion must clear negative cache entry";
        System.out.println("  PASS [7]: Provider cooldown circuit-breaker and negative cache lifecycle passed");

        // 8. Bilingual LRC merging (primary + translation/romaji)
        String primaryLrc = "[00:02.490]If you need me I'm here\n[00:07.490]染み付いた声がまだ思い出せると";
        String transLrc = "[00:02.490]如果你需要 我就在这里\n[00:07.490]还是会想起 熟悉的声音";
        String merged = MetadataFetcher.mergeBilingualLrc(primaryLrc, transLrc);
        assert merged.contains("\n[bmc:trans]\n") && merged.contains("如果你需要 我就在这里")
                : "mergeBilingualLrc must append [bmc:trans] section for valid translations";
        assert MetadataFetcher.mergeBilingualLrc(primaryLrc, "[00:02.00]//").equals(primaryLrc)
                : "mergeBilingualLrc must ignore placeholder-only translation lines";
        assert MetadataFetcher.mergeBilingualLrc(primaryLrc, "").equals(primaryLrc)
                : "mergeBilingualLrc must leave primary LRC unchanged when translation is empty";
        System.out.println("  PASS [8]: Bilingual LRC merging handles valid translations and placeholders");

        System.out.println("==================================================");
        System.out.println("All Step 2 Cache & Ticket tests PASSED!");
        System.out.println("==================================================");
    }
}
