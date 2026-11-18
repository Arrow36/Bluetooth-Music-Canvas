        // 接收来自 Android 原生层针对特定源的检索结果（完全静默化，不弹居中窗）
        window.onSpecificLyricResult = function(data) {
            if (!data) return;
            if (typeof data.generation === 'number' && data.generation !== currentPlaybackGeneration) {
                return;
            }
            const source = data.source;
            if (source && typeof data.sourceRequestId === 'number') {
                if (lastSourceRequestId[source] !== undefined && data.sourceRequestId < lastSourceRequestId[source]) {
                    return;
                }
                lastSourceRequestId[source] = data.sourceRequestId;
            }
            const rawLrc = data.lyrics;
            const lyricSource = data.lyricSource;
            const meta = SOURCE_META[source] || getSourceMeta(lyricSource);

            const trackKey = data.trackKey || currentSongKey || ((currentTrack ? currentTrack.title : '') + '\t' + (currentTrack ? currentTrack.artist : '')).toLowerCase().trim();
            ensureSongCacheEntry(trackKey);

            if (rawLrc && rawLrc.trim().length > 0) {
                const parsed = parseLRC(rawLrc);
                if (parsed.length > 0) {
                    songCache[trackKey][source + '_lyrics'] = parsed;
                    songCache[trackKey][source + '_rawLrc'] = rawLrc;
                    if (userExplicitMode === source) {
                        currentRawLrcText = rawLrc;
                        parsedOfficialLyrics = parsed;
                        hasOfficialLyrics = true;
                        renderLyrics(parsed);
                        updateStatusTag(meta.label, meta.color);
                    }
                    return;
                }
            }

            // 未找到该源歌词
            if (userExplicitMode === source) {
                if (currentTrack.streamLyric) {
                    showStreamLyric(currentTrack.streamLyric);
                    updateStatusTag(data.degraded ? '蓝牙歌词 (原源受限)' : '蓝牙歌词 (原源无词)', SOURCE_META.bluetooth.color);
                } else {
                    renderLyrics([]);
                    updateStatusTag(meta.label + (data.degraded ? ' (接口受限)' : ' (无词)'), '#888888');
                }
            }
        };

        // 接收来自 Java 原生多源高速检索的网络封面与歌词 (NetEase / QQ / Kugou / iTunes / LRCLIB)
        window.onOnlineResult = function(data) {
            if (!data) return;
            if (typeof data.generation === 'number' && data.generation !== currentPlaybackGeneration) {
                // 彻底丢弃非当前代次结果，防止切歌迟到回调污染！
                return;
            }
            if (data.source && typeof data.sourceRequestId === 'number') {
                if (lastSourceRequestId[data.source] !== undefined && data.sourceRequestId < lastSourceRequestId[data.source]) {
                    // 彻底丢弃同源乱序到达的旧请求结果！
                    return;
                }
                lastSourceRequestId[data.source] = data.sourceRequestId;
            }

            // 核心保护：若当前曲目处于视频模式，严禁被滞后的旧音乐歌词渲染覆盖大号律动频谱，且黑胶保持空白盘面
            if (currentTrack && currentTrack.isVideo) {
                const stream = document.getElementById('lyricsStream');
                const emptyBox = document.getElementById('emptyLyrics');
                const largeViz = document.getElementById('largeVisualizer');
                if (stream) {
                    stream.textContent = '';
                    stream.style.display = 'none';
                }
                if (emptyBox) emptyBox.style.display = 'none';
                if (largeViz) largeViz.style.display = 'flex';
                syncSpectrumVisibility();
                setCoverImage('');
                return;
            }

            const trackKey = data.trackKey || currentSongKey || ((currentTrack ? currentTrack.title : '') + '\t' + (currentTrack ? currentTrack.artist : '')).toLowerCase().trim();
            ensureSongCacheEntry(trackKey);

            if (isValidCoverUrl(data.coverUrl) && data.lyricSource !== '视频模式') {
                lastValidMusicCoverUrl = data.coverUrl;
                songCache[trackKey].coverUrl = data.coverUrl;
                setCoverImage(data.coverUrl);
            } else if (!songCache[trackKey].coverUrl) {
                setCoverImage('');
            }
            if (data.lyricSource) {
                songCache[trackKey].lyricSource = data.lyricSource;
                currentTrack.lyricSource = data.lyricSource;
            }
            if (data.lyrics) {
                songCache[trackKey].rawLrc = data.lyrics;
                const parsed = parseLRC(data.lyrics);
                if (parsed.length > 0) {
                    songCache[trackKey].parsedLyrics = parsed;
                    // 仅当用户处于“自动推荐”模式或指定源与当前源匹配时，才更新全屏歌词展示；
                    // 若用户手动锁定了特定源（如 QQ音乐、网易云等），而此为其他来源，切歌时绝不被覆盖！
                    if (currentActiveSource === 'auto' || (data.source && data.source === currentActiveSource)) {
                        currentRawLrcText = data.lyrics;
                        parsedOfficialLyrics = parsed;
                        hasOfficialLyrics = true;
                        lyricMode = 'official';
                        renderLyrics(parsed);
                        const meta = getSourceMeta(data.lyricSource || songCache[trackKey].lyricSource);
                        updateStatusTag(meta.label, meta.color);

                        // 蓝牙投送歌词与网上拉取歌词的二次校对
                        if (currentTrack.streamLyric) {
                            crossCheckLyrics(currentTrack.streamLyric);
                        }
                        return;
                    }
                }
            }

            if (currentActiveSource === 'bluetooth') {
                if (currentTrack.streamLyric) {
                    showStreamLyric(currentTrack.streamLyric);
                }
            } else if (currentActiveSource === 'auto' && !hasOfficialLyrics) {
                renderLyrics([]);
                updateStatusTag(currentTrack.streamLyric ? '蓝牙歌词' : '纯音乐 / 暂无歌词', '#888888');
            }
        };

        window.onMetadataChanged = function(data) {
            if (!data || !data.title) return;
            console.log("[JS] onMetadataChanged: title=" + data.title + " isVideo=" + data.isVideo);

            const title = data.title;
            const artist = data.artist || "未知歌手";
            const album = data.album || "";
            const streamLyric = data.streamLyric || "";
            const isVideo = !!data.isVideo;

            if (data.isEnrichment) {
                // 元数据补全或蓝牙流式歌词推送：绝不能切歌、绝不能递增代次、绝不能重置时钟与歌词！
                if (streamLyric) {
                    currentTrack.streamLyric = streamLyric;
                    if (data.trackKey && songCache[data.trackKey]) {
                        songCache[data.trackKey].streamLyric = streamLyric;
                    }
                    crossCheckLyrics(streamLyric);
                    if (userExplicitMode === 'bluetooth' || (!userExplicitMode && !hasOfficialLyrics)) {
                        showStreamLyric(streamLyric);
                    }
                }
                if (album && (!currentTrack.album || currentTrack.album === '')) {
                    currentTrack.album = album;
                    let artistAlbumText = (artist && artist !== "未知歌手" && !artist.toLowerCase().includes("bluetooth"))
                        ? artist.replace(/\s*\/\s*/g, ' / ')
                        : (isVideo ? "蓝牙音频播放" : "未知歌手");
                    if (!title.toLowerCase().includes(album.toLowerCase()) && !album.toLowerCase().includes(title.toLowerCase())) {
                        artistAlbumText = `${artistAlbumText} · ${album}`;
                    }
                    const songArtistEl = document.getElementById('songArtist');
                    if (songArtistEl) songArtistEl.textContent = artistAlbumText;
                }
                if (data.duration && data.duration > 0) {
                    currentTrack.duration = data.duration;
                    durationMs = data.duration;
                    const timeTotal = document.getElementById('timeTotal');
                    if (timeTotal) timeTotal.textContent = formatTime(data.duration);
                }
                if (data.trackKey) currentSongKey = data.trackKey;
                return;
            }

            const isNewSong = (currentTrack.title !== title || currentTrack.artist !== artist);
            if (isNewSong) {
                lastSourceRequestId = {};
            }
            currentPlaybackGeneration = (typeof data.generation === 'number') ? data.generation : (currentPlaybackGeneration + 1);
            currentSongKey = data.trackKey || (songCache[(title + " - " + artist).toLowerCase().trim()] ? (title + " - " + artist).toLowerCase().trim() : (title + "\t" + artist + (album ? "\t" + album : "")).toLowerCase().trim());
            currentTrack = data;
            window.currentTrack = currentTrack;

            document.getElementById('songTitle').textContent = formatSongTitle(title);
            let artistAlbumText = (artist && artist !== "未知歌手" && !artist.toLowerCase().includes("bluetooth"))
                ? artist.replace(/\s*\/\s*/g, ' / ')
                : (isVideo ? "蓝牙音频播放" : "未知歌手");

            if (album && !title.toLowerCase().includes(album.toLowerCase()) && !album.toLowerCase().includes(title.toLowerCase())) {
                artistAlbumText = `${artistAlbumText} · ${album}`;
            }
            document.getElementById('songArtist').textContent = artistAlbumText;
            document.getElementById('timeTotal').textContent = formatTime(data.duration);

            const trackKey = currentSongKey;
            ensureSongCacheEntry(trackKey);
            if (streamLyric) songCache[trackKey].streamLyric = streamLyric;

            if (isVideo) {
                // 视频音轨模式：不提取标题搜歌词与封面，不展示“正在检索”，直接呈现大号 9 频段声波律动
                hasOfficialLyrics = false;
                parsedOfficialLyrics = [];
                activeLyricIndex = -1;
                renderedLyricIndex = -1;
                const stream = document.getElementById('lyricsStream');
                const emptyBox = document.getElementById('emptyLyrics');
                const largeViz = document.getElementById('largeVisualizer');
                if (stream) {
                    stream.innerHTML = '';
                    stream.style.display = 'none';
                }
                if (emptyBox) emptyBox.style.display = 'none';
                if (largeViz) largeViz.style.display = 'flex';
                syncSpectrumVisibility();
                updateStatusTag('视频模式', '#5ac8fa');
                setCoverImage('');
                return;
            }

            if (isNewSong) {
                // 1. 彻底重置播放时钟与进度，杜绝切歌后继承上一首歌末尾进度
                currentRawLrcText = "";
                lastPositionMs = 0;
                lastSyncTime = performance.now();
                lastHardwarePosSyncTime = 0;
                activeLyricIndex = 0;
                renderedLyricIndex = -1;
                lastRenderedSec = 0;
                lastRenderedPct = 0;
                const progressBar = document.getElementById('progressBar');
                if (progressBar) progressBar.style.width = '0%';
                const timeCurrent = document.getElementById('timeCurrent');
                if (timeCurrent) timeCurrent.textContent = '00:00';

                // 2. 切歌过渡动画：淡出上一首歌词流，同时淡入检索提示
                const stream = document.getElementById('lyricsStream');
                const emptyBox = document.getElementById('emptyLyrics');
                const emptyText = document.getElementById('emptyText');
                const largeViz = document.getElementById('largeVisualizer');

                if (largeViz) largeViz.style.display = 'none';

                if (stream) {
                    stream.style.transition = 'opacity 0.25s ease-out';
                    stream.style.opacity = '0';
                }

                if (emptyBox) {
                    if (emptyText) emptyText.textContent = '正在检索官方歌词与封面...';
                    emptyBox.style.display = 'flex';
                    emptyBox.style.transition = 'none';
                    emptyBox.style.opacity = '0';
                    void emptyBox.offsetWidth;
                    emptyBox.style.transition = 'opacity 0.3s ease-in';
                    emptyBox.style.opacity = '1';
                }

                hasOfficialLyrics = false;
                syncSpectrumVisibility();
                parsedOfficialLyrics = [];
                activeLyricIndex = -1;
                renderedLyricIndex = -1;

                // 核心：严格保持用户手动选定的歌词源偏好，切歌时不回退为默认
                updateMenuCheckmarks();

                const cached = songCache[trackKey];
                if (cached && isValidCoverUrl(cached.coverUrl)) {
                    lastValidMusicCoverUrl = cached.coverUrl;
                    setCoverImage(cached.coverUrl);
                } else {
                    // 新曲目尚未检索到封面或无封面时，立即重置为空白黑胶，杜绝残留上一首歌或占位人像图
                    setCoverImage('');
                }

                if (currentActiveSource !== 'auto') {
                    // 用户锁定了特定来源（如 QQ音乐 / 网易云音乐 / 酷狗 / 蓝牙实时歌词）
                    applyLyricSourceForCurrentTrack(currentActiveSource, false);
                } else {
                    // 默认自动推荐模式
                    userExplicitMode = null;
                    if (cached && cached.parsedLyrics && cached.parsedLyrics.length > 0) {
                        currentRawLrcText = cached.rawLrc || '';
                        parsedOfficialLyrics = currentRawLrcText ? parseLRC(currentRawLrcText) : cached.parsedLyrics;
                        hasOfficialLyrics = true;
                        lyricMode = 'official';
                        if (cached.lyricSource) currentTrack.lyricSource = cached.lyricSource;
                        renderLyrics(parsedOfficialLyrics);
                        const label = getLyricSourceLabel(cached.lyricSource);
                        updateStatusTag(label, '#1db954');
                    } else {
                        renderLyrics([]);
                        updateStatusTag('正在匹配歌词与封面...', '#f5a623');
                    }
                }
            }

            if (streamLyric) {
                // 蓝牙投送歌词与网上拉取歌词二次校验
                crossCheckLyrics(streamLyric);

                if (userExplicitMode === 'bluetooth' || (!userExplicitMode && !hasOfficialLyrics)) {
                    showStreamLyric(streamLyric);
                }
            }
        };

        let playbackSpeed = 1.0;
        window.playbackSpeed = playbackSpeed;
        let manualPlaybackLockUntil = 0;
        let manualTargetPlayState = null;

        window.onPlaybackStateChanged = function(data) {
            if (!data) return;
            const pos = (typeof data.position === 'number') ? data.position : 0;
            if (pos >= 0) {
                const isBigSeek = isPlaying && lastPositionMs > 0 && Math.abs(pos - lastPositionMs) > 3000;
                lastPositionMs = pos;
                lastSyncTime = performance.now();
                if (pos > 0) {
                    lastHardwarePosSyncTime = lastSyncTime;
                }
                if (isBigSeek && lyrics && lyrics.length > 0 && !isManualScrolling) {
                    const LYRIC_ADVANCE_OFFSET_MS = 450;
                    const lyricPos = pos + LYRIC_ADVANCE_OFFSET_MS;
                    let seekTargetIdx = -1;
                    for (let i = 0; i < lyrics.length; i++) {
                        if (lyricPos >= lyrics[i].time) seekTargetIdx = i;
                        else break;
                    }
                    if (seekTargetIdx >= 0 && seekTargetIdx !== activeLyricIndex) {
                        activeLyricIndex = seekTargetIdx;
                        triggerLyricFadeTransition(seekTargetIdx);
                    }
                }
            }
            const incomingSpeed = (typeof data.speed === 'number' && Number.isFinite(data.speed) && data.speed > 0) ? data.speed : 1.0;
            playbackSpeed = incomingSpeed;
            window.playbackSpeed = playbackSpeed;

            const incomingPlaying = !!data.isPlaying;

            // 针对手动点击播控的防抖保护窗口（1.5秒）：
            // 过滤手机端蓝牙 AVRCP 尚未完成状态切换前由系统回调投送的回声脏数据，
            // 杜绝唱针在点击暂停时“先起针、又被回声落针、最后又起针”的抽动假死现象。
            if (performance.now() < manualPlaybackLockUntil) {
                if (incomingPlaying === manualTargetPlayState) {
                    manualPlaybackLockUntil = 0;
                    setOptimisticPlaybackState(incomingPlaying);
                }
                return;
            }

            setOptimisticPlaybackState(incomingPlaying);
        };

        let cachedProgressBarEl = null;
        let cachedTimeCurrentEl = null;
        let cachedTonearmEl = null;
        let lastRenderedSec = -1;
        let lastRenderedPct = -1;
        let lastRenderedTonearmAngle = -999;
        let timelineRafId = null;

        function updateTimeline() {
            timelineRafId = null;
            if (document.hidden) {
                return;
            }

            let currentPos = lastPositionMs;
            if (isPlaying) {
                const delta = (performance.now() - lastSyncTime) * playbackSpeed;
                currentPos += delta;
            }
            if (currentPos < 0) currentPos = 0;
            if (currentTrack.duration > 0 && currentPos > currentTrack.duration) {
                currentPos = currentTrack.duration;
            }

            // 更新播放进度条（带脏值检测，避免 60fps 重复触发 DOM 布局与文本重绘）
            if (currentTrack.duration > 0) {
                const pct = Math.min(100, Math.max(0, (currentPos / currentTrack.duration) * 100));
                if (Math.abs(pct - lastRenderedPct) >= 0.15) {
                    lastRenderedPct = pct;
                    if (!cachedProgressBarEl) cachedProgressBarEl = document.getElementById('progressBar');
                    if (cachedProgressBarEl) cachedProgressBarEl.style.width = pct.toFixed(2) + '%';
                }
                const sec = Math.floor(currentPos / 1000);
                if (sec !== lastRenderedSec) {
                    lastRenderedSec = sec;
                    if (!cachedTimeCurrentEl) cachedTimeCurrentEl = document.getElementById('timeCurrent');
                    if (cachedTimeCurrentEl) cachedTimeCurrentEl.textContent = formatTime(currentPos);
                }
            }

            // 真实唱机唱针物理轨迹（带角度阈值检测，避免 60fps 重复重启 0.35s CSS transition）
            if (!cachedTonearmEl) cachedTonearmEl = document.getElementById('tonearm');
            if (cachedTonearmEl) {
                let targetAngle = -16;
                if (isPlaying) {
                    let progressRatio = 0;
                    if (currentTrack.duration > 0) {
                        progressRatio = Math.min(1.0, Math.max(0.0, currentPos / currentTrack.duration));
                    }
                    targetAngle = -10 + progressRatio * 10; // 0% at -10deg (outer groove) -> 100% at 0deg (inner groove)
                }
                if (Math.abs(targetAngle - lastRenderedTonearmAngle) >= 0.12) {
                    lastRenderedTonearmAngle = targetAngle;
                    cachedTonearmEl.style.transform = isPlaying ? `rotate(${targetAngle.toFixed(2)}deg)` : 'rotate(-16deg)';
                }
            }

            // 官方歌词流处理
            if (lyricMode === 'official' && hasOfficialLyrics && lyrics && lyrics.length > 0) {
                // 人眼预读习惯与蓝牙音频传输缓冲补偿（提前 450ms 响应，消除滞后感）
                const LYRIC_ADVANCE_OFFSET_MS = 450;
                const lyricPos = currentPos + LYRIC_ADVANCE_OFFSET_MS;

                let targetIndex = -1;
                for (let i = 0; i < lyrics.length; i++) {
                    if (lyricPos >= lyrics[i].time) {
                        targetIndex = i;
                    } else {
                        break;
                    }
                }

                // 严格单调防抖迟滞锁：
                // 严禁因手机蓝牙 AVRCP 传输延时微弱抖动导致 targetIndex 往回掉引发的歌词上下颠簸。
                // 仅当用户手动拖动倒退超过 3.5 秒时才允许回退；新歌前3秒内允许重设回开头。
                if (activeLyricIndex >= 0 && targetIndex < activeLyricIndex) {
                    if (currentPos >= 3000) {
                        const activeTime = lyrics[activeLyricIndex] ? lyrics[activeLyricIndex].time : 0;
                        if (currentPos > (activeTime - 3500)) {
                            targetIndex = activeLyricIndex;
                        }
                    }
                }

                if (targetIndex !== activeLyricIndex && targetIndex >= 0) {
                    const prevIndex = activeLyricIndex;
                    activeLyricIndex = targetIndex;
                    if (!isManualScrolling) {
                        if (prevIndex >= 0 && Math.abs(targetIndex - prevIndex) > 1) {
                            // 手机端拖动进度条跳转（跨行跳跃）：复用手拖动歌词归位的淡出淡入动画！
                            triggerLyricFadeTransition(targetIndex);
                        } else if (!isLyricFading) {
                            updateLyricHighlight(activeLyricIndex);
                            scrollToLyricIndex(activeLyricIndex, true);
                        }
                    }
                }
            }

            // 动态音乐律动波形超时归零（仅在尚未归零时执行一次 DOM 写入）
            if (!eqBarsAtBaseline) {
                const shouldReset = !isPlaying || (performance.now() - lastFftTime > 250);
                if (shouldReset) {
                    const bars = getEqualizerBars();
                    if (bars.length >= 5) {
                        for (let i = 0; i < 5; i++) {
                            bars[i].style.height = '8%';
                        }
                        eqBarsAtBaseline = true;
                    }
                }
            }

            timelineRafId = requestAnimationFrame(updateTimeline);
        }
        timelineRafId = requestAnimationFrame(updateTimeline);

        document.addEventListener('visibilitychange', function() {
            if (!document.hidden && !timelineRafId) {
                timelineRafId = requestAnimationFrame(updateTimeline);
            }
        });

        // 乐观瞬态播控（零延迟响应唱针起落与按键）
        function setOptimisticPlaybackState(playState) {
            isPlaying = playState;
            const vinyl = document.getElementById('vinylRecord');
            const playIcon = document.getElementById('playIcon');
            if (!cachedTonearmEl) cachedTonearmEl = document.getElementById('tonearm');
            const tonearm = cachedTonearmEl;

            if (isPlaying) {
                if (vinyl) vinyl.classList.add('playing');
                if (playIcon) playIcon.innerHTML = '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>';
                if (tonearm) {
                    let progressRatio = 0;
                    if (currentTrack.duration > 0) {
                        progressRatio = Math.min(1.0, Math.max(0.0, lastPositionMs / currentTrack.duration));
                    }
                    const tonearmAngle = -10 + progressRatio * 10;
                    lastRenderedTonearmAngle = tonearmAngle;
                    tonearm.style.transform = `rotate(${tonearmAngle.toFixed(2)}deg)`;
                }
            } else {
                if (vinyl) vinyl.classList.remove('playing');
                if (playIcon) playIcon.innerHTML = '<path d="M8 5v14l11-7z"/>';
                // 唱针立即起针复位，0ms 感知延迟
                if (tonearm) {
                    lastRenderedTonearmAngle = -16;
                    tonearm.style.transform = 'rotate(-16deg)';
                }
                const bars = getEqualizerBars();
                for (let i = 0; i < bars.length; i++) {
                    bars[i].style.height = '8%';
                }
                eqBarsAtBaseline = true;
            }
        }

        function triggerPlayPause(e) {
            if (e && e.stopPropagation) e.stopPropagation();
            const target = !isPlaying;
            manualTargetPlayState = target;
            manualPlaybackLockUntil = performance.now() + 1500;
            // 乐观瞬态响应：立即切换状态与动效
            setOptimisticPlaybackState(target);
            if (window.AndroidApp && window.AndroidApp.togglePlayPause) {
                window.AndroidApp.togglePlayPause();
            }
        }

        function triggerNext() {
            if (window.AndroidApp) window.AndroidApp.skipNext();
        }

        function triggerPrev() {
            if (window.AndroidApp) window.AndroidApp.skipPrevious();
        }

        function triggerExit(e) {
            if (e && e.preventDefault) e.preventDefault();
            if (e && e.stopPropagation) e.stopPropagation();
            if (window.AndroidApp) window.AndroidApp.exitToHome();
        }

