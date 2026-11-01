package tests;

import com.bluetoothmusiccanvas.MetadataFetcher;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestPhase3Logic {
    private static int assertionCount = 0;

    private static void assertTrue(boolean condition, String msg) {
        assertionCount++;
        if (!condition) {
            System.err.println("FAIL [" + assertionCount + "]: " + msg);
            throw new AssertionError(msg);
        }
        System.out.println("  PASS [" + assertionCount + "]: " + msg);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("Starting Phase 3 Architecture & Lifecycle Test Suite");
        System.out.println("==================================================");

        // Group 1: LRU 50-Item Capacity Bounded Cache
        System.out.println("\n--- Group 1: LRU 50-Item Capacity Bounded Cache ---");
        {
            Field cacheField = MetadataFetcher.class.getDeclaredField("sCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, MetadataFetcher.CachedMeta> cache = (Map<String, MetadataFetcher.CachedMeta>) cacheField.get(null);
            cache.clear();

            for (int i = 1; i <= 60; i++) {
                MetadataFetcher.CachedMeta meta = new MetadataFetcher.CachedMeta("http://cover/" + i, "[00:01] Lyric " + i, "Source" + i);
                MetadataFetcher.putInCache("Song " + i, "Artist " + i, "Album " + i, meta);
            }

            assertTrue(cache.size() <= 50, "Cache size is bounded at <= 50 (actual: " + cache.size() + ")");
            // Oldest items (e.g. Song 1) should have been evicted by LRU
            String oldestKey = MetadataFetcher.buildCacheKey("Song 1", "Artist 1", "Album 1");
            assertTrue(!cache.containsKey(oldestKey), "Eldest entry Song 1 was evicted");

            // Newest item (Song 60) must still exist
            String newestKey = MetadataFetcher.buildCacheKey("Song 60", "Artist 60", "Album 60");
            assertTrue(cache.containsKey(newestKey), "Latest entry Song 60 is present in cache");
        }

        // Group 2: Metadata Enrichment Key Association
        System.out.println("\n--- Group 2: Metadata Enrichment Key Association ---");
        {
            MetadataFetcher.CachedMeta meta = new MetadataFetcher.CachedMeta("http://cover/test", "[00:05] Hello", "NetEase");
            MetadataFetcher.putInCache("Full of You", "Escapism", "Original Album", meta);

            // Lookup with album
            MetadataFetcher.CachedMeta foundWithAlbum = MetadataFetcher.getCached("Full of You", "Escapism", "Original Album");
            assertTrue(foundWithAlbum != null && foundWithAlbum.coverUrl.equals("http://cover/test"), "Hit cache with album");

            // Lookup without album (e.g. before album was received)
            MetadataFetcher.CachedMeta foundWithoutAlbum = MetadataFetcher.getCached("Full of You", "Escapism", "");
            assertTrue(foundWithoutAlbum != null && foundWithoutAlbum.coverUrl.equals("http://cover/test"), "Hit cache without album");
        }

        // Group 3: In-flight Network Request Deduplication & Coalescence
        System.out.println("\n--- Group 3: In-Flight Request Deduplication ---");
        {
            Field inFlightField = MetadataFetcher.class.getDeclaredField("sInFlightRequests");
            inFlightField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> inFlightMap = (Map<String, ?>) inFlightField.get(null);

            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicInteger callbackCount = new AtomicInteger(0);

            // Fire two identical fetches concurrently for a non-cached song
            MetadataFetcher.fetch("NonExistentTrackUnique123", "Artist456", "Album789", 123456, new MetadataFetcher.Callback() {
                @Override
                public void onResult(String coverUrl, String lyricsLrc, String lyricSource) {
                    callbackCount.incrementAndGet();
                    latch.countDown();
                }
            });

            MetadataFetcher.fetch("NonExistentTrackUnique123", "Artist456", "Album789", 123456, new MetadataFetcher.Callback() {
                @Override
                public void onResult(String coverUrl, String lyricsLrc, String lyricSource) {
                    callbackCount.incrementAndGet();
                    latch.countDown();
                }
            });

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            assertTrue(finished, "Both coalesced requests completed within timeout");
            assertTrue(callbackCount.get() == 2, "Both callbacks were invoked (count = 2)");
            assertTrue(inFlightMap.isEmpty(), "In-flight map cleanly emptied after completion");
        }

        System.out.println("\n==================================================");
        System.out.println("Phase 3 Test Suite Summary: " + assertionCount + " assertions passed.");
        System.out.println("==================================================");
    }
}
