package tests;

import com.bluetoothmusiccanvas.MetadataFetcher;
import com.bluetoothmusiccanvas.SongIdentity;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.Deflater;

public class TestStep2CacheAndTickets {

    public static void main(String[] args) throws Exception {
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

        // 9. HTML entity unescaping, KRC binary XOR+zlib decryption & bilingual/romaji assembly
        assert "If you need me I'm here & waiting".equals(
                MetadataFetcher.unescapeLyricEntities("If you need me I&apos;m here &amp; waiting"))
                : "unescapeLyricEntities must decode XML/HTML entities";

        String sampleKrc = "[ti:Tell me]\n"
                + "[1654,3100]<0,500,0>If <500,500,0>you <1000,500,0>need I&apos;m here\n"
                + "[4829,2500]<0,500,0>染み付いた声\n";
        byte[] sampleUtf8 = sampleKrc.getBytes("UTF-8");
        Deflater deflater = new Deflater();
        deflater.setInput(sampleUtf8);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] zbuf = new byte[512];
        while (!deflater.finished()) {
            compressed.write(zbuf, 0, deflater.deflate(zbuf));
        }
        deflater.end();
        byte[] zBytes = compressed.toByteArray();
        int[] krcKey = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
        byte[] encryptedKrc = new byte[4 + zBytes.length];
        encryptedKrc[0] = 'k';
        encryptedKrc[1] = 'r';
        encryptedKrc[2] = 'c';
        encryptedKrc[3] = '1';
        for (int i = 0; i < zBytes.length; i++) {
            encryptedKrc[4 + i] = (byte) ((zBytes[i] & 0xFF) ^ krcKey[i & 15]);
        }
        String decryptedKrc = MetadataFetcher.decryptKrcBytes(encryptedKrc);
        assert sampleKrc.equals(decryptedKrc) : "decryptKrcBytes must round-trip XOR+zlib KRC payload";

        String krcBilingual = MetadataFetcher.buildBilingualLrcFromKrcParts(
                decryptedKrc, Arrays.asList("如果你需要 我就在这里", "//"), null);
        assert krcBilingual != null
                && krcBilingual.contains("[00:01.654]If you need I'm here")
                && krcBilingual.contains("\n[bmc:trans]\n")
                && krcBilingual.contains("[00:01.654]如果你需要 我就在这里")
                && !krcBilingual.contains("//")
                : "buildBilingualLrcFromKrcParts must emit timestamped bilingual LRC and skip //";

        String krcRomaji = MetadataFetcher.buildBilingualLrcFromKrcParts(
                "[7921,2000]<0,500,0>染み付いた声が\n",
                null,
                Collections.singletonList("shi mi tsu i ta ko e ga"));
        assert krcRomaji != null
                && krcRomaji.contains("[00:07.921]染み付いた声が")
                && krcRomaji.contains("[00:07.921]shi mi tsu i ta ko e ga")
                : "buildBilingualLrcFromKrcParts must fall back to romaji for Japanese/Korean tracks";
        System.out.println("  PASS [9]: Entity unescaping, KRC XOR+zlib decryption & bilingual assembly passed");

        System.out.println("==================================================");
        System.out.println("All Step 2 Cache & Ticket tests PASSED!");
        System.out.println("==================================================");
    }
}
