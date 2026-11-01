/**
 * Phase 1 Regression & Security Test Suite
 * Bluetooth Music Canvas
 */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

let passedTests = 0;
let totalTests = 0;

function assert(condition, message) {
    totalTests++;
    if (!condition) {
        console.error(`  FAIL [${totalTests}]: ${message}`);
        throw new Error(message);
    }
    passedTests++;
    console.log(`  PASS [${totalTests}]: ${message}`);
}

async function runTests() {
    console.log("==================================================");
    console.log("Starting Phase 1 Regression & Security Test Suite");
    console.log("==================================================");

    const htmlPath = path.resolve(__dirname, '../assets/index.html');
    const htmlContent = fs.readFileSync(htmlPath, 'utf8');

    // Setup virtual DOM with JSDOM
    const dom = new JSDOM(htmlContent, {
        runScripts: "dangerously",
        resources: "usable",
        url: "http://localhost/index.html",
        beforeParse(window) {
            window.requestAnimationFrame = (cb) => setTimeout(cb, 16);
            window.cancelAnimationFrame = (id) => clearTimeout(id);
        }
    });

    const window = dom.window;
    const document = window.document;

    // Wait for scripts in index.html to evaluate
    await new Promise(resolve => setTimeout(resolve, 500));

    console.log("\n--- Group 1: XSS Security & HTML Injection Protection ---");
    {
        const maliciousPayload = '<img src="x" onerror="window.__xss_pwned=true"><script>window.__xss_script=true</script><b id="injected-tag">Malicious</b>';
        
        // 1.1 Test showStreamLyric
        window.showStreamLyric(maliciousPayload);
        const imgTags = document.getElementsByTagName('img');
        let foundInjectedImg = false;
        for (let img of imgTags) {
            console.log("Found img in doc:", img.src, img.outerHTML);
            if (img.src && img.src.includes('/x')) foundInjectedImg = true;
        }
        assert(!foundInjectedImg, "showStreamLyric does not create <img> element from HTML injection");
        assert(document.getElementById('injected-tag') === null, "showStreamLyric does not parse <b> tag");
        assert(window.__xss_pwned === undefined, "window.__xss_pwned is undefined (no event execution)");
        
        const streamBox = document.querySelector('.live-stream-lyric');
        assert(streamBox !== null && streamBox.textContent === maliciousPayload, "Payload displayed as safe plain text in .live-stream-lyric");

        // 1.2 Test renderLyrics
        window.renderLyrics([{ time: 1000, text: maliciousPayload }]);
        const line0 = document.getElementById('line-0');
        assert(line0 !== null, "line-0 exists");
        assert(line0.getElementsByTagName('img').length === 0, "renderLyrics does not create <img> from payload");
        assert(line0.textContent === maliciousPayload, "renderLyrics renders payload safely as textContent");
    }

    console.log("\n--- Group 2: Missing Function getLyricSourceLabel Bug ---");
    {
        // Setup cache with netease source
        const testTrackKey = "test title - test artist";
        window.songCache[testTrackKey] = {
            parsedLyrics: [{ time: 0, text: "Line 1" }, { time: 5000, text: "Line 2" }],
            lyricSource: 'netease',
            coverUrl: 'http://example.com/cover.jpg'
        };

        let errCaught = null;
        try {
            window.onMetadataChanged({
                title: "test title",
                artist: "test artist",
                album: "test album",
                duration: 180000,
                generation: 1
            });
        } catch (e) {
            errCaught = e;
        }

        assert(errCaught === null, "onMetadataChanged executes without ReferenceError on cached song");
        const statusEl = document.getElementById('statusTagText');
        assert(statusEl !== null && statusEl.textContent.includes('网易云'), "Status tag reflects '网易云音乐'");
    }

    console.log("\n--- Group 3: Out-of-Order Same Generation, Same Source Requests ---");
    {
        // Current generation: 2
        window.onMetadataChanged({
            title: "Race Song",
            artist: "Race Artist",
            album: "Race Album",
            duration: 200000,
            generation: 2,
            trackKey: "race_track_key"
        });

        // Request 2 arrives first
        window.onOnlineResult({
            generation: 2,
            source: 'netease',
            sourceRequestId: 2,
            trackKey: "race_track_key",
            lyrics: "[00:01.00]Newer NetEase Lyric Line",
            lyricSource: "网易云音乐"
        });

        const lineEl = document.getElementById('line-0');
        assert(lineEl && lineEl.textContent === "Newer NetEase Lyric Line", "Newer request 2 lyrics applied");

        // Request 1 arrives late
        window.onOnlineResult({
            generation: 2,
            source: 'netease',
            sourceRequestId: 1,
            trackKey: "race_track_key",
            lyrics: "[00:01.00]Stale Older Lyric Line",
            lyricSource: "网易云音乐"
        });

        const lineElAfter = document.getElementById('line-0');
        assert(lineElAfter && lineElAfter.textContent === "Newer NetEase Lyric Line", "Stale older request 1 was rejected and did not overwrite newer lyrics");
    }

    console.log("\n--- Group 4: Rapid Track Switch (A -> B) Generation Isolation ---");
    {
        // User is playing Song A (gen 10)
        window.onMetadataChanged({
            title: "Song A",
            artist: "Artist A",
            album: "Album A",
            duration: 180000,
            generation: 10,
            trackKey: "song_a_key"
        });

        // User rapidly switches to Song B (gen 11)
        window.onMetadataChanged({
            title: "Song B",
            artist: "Artist B",
            album: "Album B",
            duration: 190000,
            generation: 11,
            trackKey: "song_b_key"
        });

        // Song A's late network response arrives with generation 10
        window.onOnlineResult({
            generation: 10,
            source: 'netease',
            sourceRequestId: 5,
            trackKey: "song_a_key",
            coverUrl: "http://example.com/cover_a.jpg",
            lyrics: "[00:01.00]Song A Lyrics",
            lyricSource: "网易云音乐"
        });

        // Verify Song B's cache was NOT polluted with Song A's results
        const cacheB = window.songCache["song_b_key"];
        assert(!cacheB || cacheB.coverUrl !== "http://example.com/cover_a.jpg", "Song B cache cover was NOT polluted by Song A");
        assert(!cacheB || !cacheB.rawLrc || !cacheB.rawLrc.includes("Song A"), "Song B cache lyrics were NOT polluted by Song A");
    }

    console.log("\n--- Group 5: Metadata Enrichment Does Not Switch Track or Drop Requests ---");
    {
        // Initial metadata without album or duration
        window.onMetadataChanged({
            title: "Full of You",
            artist: "polarrana",
            album: "",
            duration: 0,
            generation: 20,
            trackKey: "full_of_you_key"
        });

        // Set up in-flight request ID 1 for gen 20
        window.onOnlineResult({
            generation: 20,
            source: 'netease',
            sourceRequestId: 1,
            trackKey: "full_of_you_key",
            lyrics: "[00:10.00]Full of You Initial Lyric",
            lyricSource: "网易云音乐"
        });

        // Enrichment update arrives (album and duration filled)
        window.onMetadataChanged({
            title: "Full of You",
            artist: "polarrana",
            album: "Escapism",
            duration: 170918,
            generation: 20,
            isEnrichment: true,
            trackKey: "full_of_you_key"
        });

        const line0 = document.getElementById('line-0');
        assert(line0 && line0.textContent === "Full of You Initial Lyric", "Lyrics maintained during metadata enrichment");
        assert(window.currentTrack.album === "Escapism", "Album enriched to Escapism");
        assert(window.currentTrack.duration === 170918, "Duration enriched to 170918");
    }

    console.log("\n--- Group 6: DOM Highlight True Union of Sets ---");
    {
        // Create 120 lines
        const testLyrics = [];
        for (let i = 0; i < 120; i++) {
            testLyrics.push({ time: i * 1000, text: `Line ${i}` });
        }
        window.renderLyrics(testLyrics);

        // Advance from line 5 to line 6
        window.updateLyricHighlight(5);
        assert(document.getElementById('line-5').classList.contains('active'), "Line 5 is active");
        assert(document.getElementById('line-6').classList.contains('near-1'), "Line 6 is near-1");

        // Now move to 6
        window.updateLyricHighlight(6);
        assert(document.getElementById('line-6').classList.contains('active'), "Line 6 is now active");
        assert(document.getElementById('line-5').classList.contains('near-1'), "Line 5 transitioned to near-1");
        assert(document.getElementById('line-1').classList.contains('far'), "Line 1 transitioned to far (diff 5)");
        assert(document.getElementById('line-10').classList.contains('near-4'), "Line 10 transitioned to near-4 (diff 4)");

        // Jump from 6 to 100
        window.updateLyricHighlight(100);
        assert(document.getElementById('line-100').classList.contains('active'), "Line 100 is active after big jump");
        assert(document.getElementById('line-6').classList.contains('far'), "Line 6 is far after big jump");
        assert(document.getElementById('line-96').classList.contains('near-4'), "Line 96 is near-4");
        assert(document.getElementById('line-104').classList.contains('near-4'), "Line 104 is near-4");
        assert(document.getElementById('line-105').classList.contains('far'), "Line 105 is far");
    }

    console.log("\n==================================================");
    console.log(`Phase 1 Test Suite Summary: ${passedTests} / ${totalTests} assertions passed.`);
    console.log("==================================================");
    process.exit(0);
}

runTests().catch(err => {
    console.error("Test execution failed:", err);
    process.exit(1);
});
