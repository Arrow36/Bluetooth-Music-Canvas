/**
 * Phase 2 Regression Test Suite:
 * Clock extrapolation, playback speed, LRC parser robustness,
 * double-layer window lyric sync, and spectrum baseline honesty.
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
    console.log("Starting Phase 2 Regression Test Suite");
    console.log("==================================================");

    const htmlPath = path.resolve(__dirname, '../assets/index.html');
    const htmlContent = fs.readFileSync(htmlPath, 'utf8');

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

    await new Promise(resolve => setTimeout(resolve, 500));

    console.log("\n--- Group 1: Robust LRC Parser (Decimals, Missing Decimals & Offset) ---");
    {
        const parseLRC = window.parseLRC;

        // 1.1 Standard decimals [mm:ss.xx]
        const lrc1 = "[01:23.45]Standard Line";
        const r1 = parseLRC(lrc1);
        assert(r1.length === 1 && r1[0].time === 83450 && r1[0].text === "Standard Line", "Parses [01:23.45] as 83450ms");

        // 1.2 Missing decimals [mm:ss] (critical review requirement)
        const lrc2 = "[00:10]Missing Decimals Line\n[01:05]Second Missing Decimals Line";
        const r2 = parseLRC(lrc2);
        assert(r2.length === 2, "Parses timestamps without decimals [00:10]");
        assert(r2[0].time === 10000 && r2[0].text === "Missing Decimals Line", "[00:10] is exactly 10000ms");
        assert(r2[1].time === 65000 && r2[1].text === "Second Missing Decimals Line", "[01:05] is exactly 65000ms");

        // 1.3 Variable 1-digit and 3-digit decimals
        const lrc3 = "[00:05.1]One Digit Frac\n[00:05.123]Three Digit Frac";
        const r3 = parseLRC(lrc3);
        assert(r3.length === 2, "Parses 1-digit and 3-digit fractions");
        assert(r3[0].time === 5100, "[00:05.1] is 5100ms");
        assert(r3[1].time === 5123, "[00:05.123] is 5123ms");

        // 1.4 Offset tag [offset:+500] and [offset:-200]
        const lrc4 = "[ti:Test Title]\n[ar:Test Artist]\n[offset:+500]\n[00:10.00]Shifted Line";
        const r4 = parseLRC(lrc4);
        assert(r4.length === 1 && r4[0].time === 10500, "[offset:+500] shifts 10000ms to 10500ms");

        const lrc5 = "[offset:-200]\n[00:01.00]Negative Shift Line\n[00:00.10]Clamped Line";
        const r5 = parseLRC(lrc5);
        assert(r5[0].time === 0, "Negative offset clamped at 0 for early timestamps");
        assert(r5[1].time === 800, "[00:01.00] shifted by -200ms becomes 800ms");
    }

    console.log("\n--- Group 2: Playback Speed & Clock Extrapolation ---");
    {
        // Set track duration 200,000ms
        window.onMetadataChanged({
            title: "Speed Test Track",
            artist: "Speed Artist",
            album: "Speed Album",
            duration: 200000,
            generation: 1
        });

        // 2.1 speed = 1.0x
        window.onPlaybackStateChanged({ isPlaying: true, position: 10000, speed: 1.0 });
        assert(window.playbackSpeed === 1.0, "playbackSpeed is 1.0");

        // 2.2 speed = 1.5x
        window.onPlaybackStateChanged({ isPlaying: true, position: 10000, speed: 1.5 });
        assert(window.playbackSpeed === 1.5, "playbackSpeed updated to 1.5");

        // 2.3 speed = 2.0x
        window.onPlaybackStateChanged({ isPlaying: true, position: 10000, speed: 2.0 });
        assert(window.playbackSpeed === 2.0, "playbackSpeed updated to 2.0");

        // 2.4 Invalid speed (NaN, 0, -1, Infinity) falls back safely
        window.onPlaybackStateChanged({ isPlaying: true, position: 10000, speed: NaN });
        assert(window.playbackSpeed === 1.0, "NaN speed falls back to 1.0");

        window.onPlaybackStateChanged({ isPlaying: true, position: 10000, speed: -1.0 });
        assert(window.playbackSpeed === 1.0, "Negative speed falls back to 1.0");

        // 2.5 Paused state
        window.onPlaybackStateChanged({ isPlaying: false, position: 15000, speed: 1.0 });
        assert(!window.isPlaying, "isPlaying is false when paused");
    }

    console.log("\n--- Group 3: Double-Layer Window Lyric Synchronization ---");
    {
        const chorusLyrics = [
            { time: 10000, text: "Was there something else before we met" },
            { time: 42000, text: "My emptiness is full of you," },
            { time: 48000, text: "You're everywhere no matter what I do" },
            { time: 80000, text: "Bridge section line" },
            { time: 94000, text: "My emptiness is full of you," },
            { time: 100000, text: "You're everywhere no matter what I do" }
        ];

        window.renderLyrics(chorusLyrics);
        window.hasOfficialLyrics = true;
        window.lyrics = chorusLyrics;

        // 3.1 When estimated position is at 92,000ms (~01:32)
        window.lastPositionMs = 92000;
        window.lastSyncTime = window.performance.now();
        window.isPlaying = true;
        window.activeLyricIndex = 2; // Was previously on bridge

        // Phone broadcasts dynamic lyric for second chorus: "My emptiness is full of you,"
        window.crossCheckLyrics("My emptiness is full of you,");

        // Must match line index 4 (94,000ms), NOT line index 1 (42,000ms)!
        assert(window.activeLyricIndex === 4, "Priority ±15s window matches second chorus at 94000ms, not first chorus at 42000ms");

        // 3.2 When position jumped / drifted
        window.lastPositionMs = 12000;
        window.lastSyncTime = window.performance.now();
        window.activeLyricIndex = 0;

        window.crossCheckLyrics("My emptiness is full of you,");
        assert(window.activeLyricIndex === 1, "Priority window matches first chorus at 42000ms when near 12000ms-40000ms");

        // 3.3 Repeated dense lines & contraction differences (Sign of the Times regression)
        const signLyrics = [
            { time: 48760, text: "We never learn, we been here before" },
            { time: 52480, text: "Why are we always stuck and running from" },
            { time: 56350, text: "The bullets?" },
            { time: 59910, text: "The bullets" },
            { time: 64680, text: "We never learn, we been here before" },
            { time: 68510, text: "Why are we always stuck and running from" },
            { time: 71980, text: "The bullets?" },
            { time: 75820, text: "The bullets" },
            { time: 260870, text: "We've been here before" }
        ];
        window.renderLyrics(signLyrics);
        window.hasOfficialLyrics = true;
        window.lyrics = signLyrics;

        // At 01:04.67 (64,672ms), phone sends "We never learn, we've been here before" (with 've)
        window.onPlaybackStateChanged({ isPlaying: true, position: 64672, speed: 1.0 });
        window.activeLyricIndex = 3;
        window.crossCheckLyrics("We never learn, we've been here before");
        assert(window.activeLyricIndex === 4, "Matches [01:04.68] 'We never learn, we been here before' (index 4), NOT [04:20.87] 'We've been here before' (index 8)");

        // At 01:12.79 (72,790ms), phone sends "The bullets?" -> must match index 6 (71,980ms), NOT index 3 (59,910ms)
        window.onPlaybackStateChanged({ isPlaying: true, position: 72790, speed: 1.0 });
        window.activeLyricIndex = 5;
        window.crossCheckLyrics("The bullets?");
        assert(window.activeLyricIndex === 6, "Closest match in ±15s window selects [01:11.98] 'The bullets?' (index 6), NOT [00:59.91] (index 3)");

        // At 01:16.79 (76,797ms), phone sends "The bullets" -> must match index 7 (75,820ms), NOT index 6 (71,980ms)
        window.onPlaybackStateChanged({ isPlaying: true, position: 76797, speed: 1.0 });
        window.activeLyricIndex = 6;
        window.crossCheckLyrics("The bullets");
        assert(window.activeLyricIndex === 7, "Closest match in ±15s window selects [01:15.82] 'The bullets' (index 7), NOT [01:11.98] (index 6)");
    }

    console.log("\n--- Group 4: Spectrum Baseline & No Fake Sine Wave on Silence ---");
    {
        // When paused or silent, bars must remain at resting baseline 0.08
        window.onAudioSpectrum(0, 0, 0, 0, 0);
        const bars = window.getEqualizerBars();
        for (let i = 0; i < 5; i++) {
            assert(bars[i].style.transform.includes('0.08'), `Bar ${i} is at resting baseline 0.08 on silence`);
        }
    }

    console.log("\n--- Group 5: Credit Filtering, Clock Anti-Jump & Spectrum Visibility Gating ---");
    {
        let lastBridgeSpectrumVisible = null;
        window.AndroidApp = {
            setSpectrumVisible(v) {
                lastBridgeSpectrumVisible = v;
            }
        };

        const teaLyrics = [
            { time: 1000, text: "演唱：陈佳" },
            { time: 2000, text: "录音师：廖志茂" },
            { time: 3000, text: "录音棚：广州乐喜录音棚" },
            { time: 4000, text: "潮语指导：林欢欢" },
            { time: 18000, text: "月下煮茶香满院" },
            { time: 24000, text: "微风轻拂夜色静" }
        ];

        window.renderLyrics(teaLyrics);
        window.hasOfficialLyrics = true;
        window.lyrics = teaLyrics;
        window.onPlaybackStateChanged({ isPlaying: true, position: 6100, speed: 1.0 });
        window.activeLyricIndex = 4;

        // 5.1 Credit lines in streamLyric must be ignored and never pull activeLyricIndex or lastPositionMs back
        window.crossCheckLyrics("录音棚：广州乐喜录音棚");
        assert(window.activeLyricIndex === 4, "Credit line '录音棚：...' is ignored by crossCheckLyrics");
        assert(window.lastPositionMs === 6100, "lastPositionMs remains at 6100ms (no backward jump to 3000ms)");

        window.crossCheckLyrics("潮语指导：林欢欢");
        assert(window.activeLyricIndex === 4, "Credit line '潮语指导：...' is ignored by crossCheckLyrics");
        assert(window.lastPositionMs === 6100, "lastPositionMs remains at 6100ms (no backward jump to 4000ms)");

        // 5.2 Small drift (<2.5s) updates lyric highlight without jumping progress clock backward
        window.onPlaybackStateChanged({ isPlaying: true, position: 19200, speed: 1.0 });
        window.crossCheckLyrics("月下煮茶香满院");
        assert(window.activeLyricIndex === 4, "Singing line matched at 18000ms");
        assert(window.lastPositionMs === 19200, "Small 1.2s delta does not jump lastPositionMs backward from 19200ms to 18000ms");

        // 5.3 Spectrum visibility bridge notifies false after official lyrics hide emptyBox, and true on visualizer mode
        await new Promise(resolve => setTimeout(resolve, 300));
        assert(lastBridgeSpectrumVisible === false, "AndroidApp.setSpectrumVisible(false) called when official scrolling lyrics are active");

        window.selectLyricSource('visualizer');
        assert(lastBridgeSpectrumVisible === true, "AndroidApp.setSpectrumVisible(true) called when switching to visualizer mode");
        window.selectLyricSource('auto');
    }

    console.log("\n==================================================");
    console.log(`Phase 2 Test Suite Summary: ${passedTests} / ${totalTests} assertions passed.`);
    console.log("==================================================");
    process.exit(0);
}

runTests().catch(err => {
    console.error("Phase 2 test execution failed:", err);
    process.exit(1);
});
