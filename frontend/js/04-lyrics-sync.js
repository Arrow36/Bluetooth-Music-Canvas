        // ===== 歌词视口手势上下拖拽滑动与 3 秒柔顺回滚 =====
        let isManualScrolling = false;
        let isDraggingLyrics = false;
        let dragStartY = 0;
        let dragStartTranslateY = 0;
        let currentTranslateY = 0;
        let manualScrollResumeTimer = null;

        function updateLyricHighlight(targetIndex, instant) {
            const oldIndex = renderedLyricIndex;
            const lines = document.querySelectorAll('.lyric-line');
            if (!lines || lines.length === 0) return;

            if (oldIndex < 0 || (instant && oldIndex === targetIndex)) {
                // 首次高亮或全量初始化
                for (let idx = 0; idx < lines.length; idx++) {
                    const el = lines[idx];
                    const dist = Math.abs(idx - targetIndex);
                    if (instant) {
                        el.style.transition = 'none';
                    } else if (el.style.transition) {
                        el.style.transition = '';
                    }
                    let newClass = 'lyric-line';
                    if (dist === 0) {
                        newClass += ' active';
                    } else {
                        if (dist <= 4) {
                            newClass += ` near near-${dist}`;
                        } else {
                            newClass += ' far';
                        }
                    }
                    el.className = newClass;
                }
            } else if (oldIndex !== targetIndex) {
                // 真正邻域并集：[oldIndex-4, oldIndex+4] ∪ [targetIndex-4, targetIndex+4]
                const affectedIndices = new Set();
                const total = lines.length;
                for (let i = oldIndex - 4; i <= oldIndex + 4; i++) {
                    if (i >= 0 && i < total) affectedIndices.add(i);
                }
                for (let i = targetIndex - 4; i <= targetIndex + 4; i++) {
                    if (i >= 0 && i < total) affectedIndices.add(i);
                }
                affectedIndices.forEach(idx => {
                    const el = lines[idx];
                    if (!el) return;
                    const dist = Math.abs(idx - targetIndex);
                    if (instant) {
                        el.style.transition = 'none';
                    } else if (el.style.transition) {
                        el.style.transition = '';
                    }
                    let newClass = 'lyric-line';
                    if (dist === 0) {
                        newClass += ' active';
                    } else {
                        if (dist <= 4) {
                            newClass += ` near near-${dist}`;
                        } else {
                            newClass += ' far';
                        }
                    }
                    el.className = newClass;
                });
            }
            renderedLyricIndex = targetIndex;
            activeLyricIndex = targetIndex;
        }

        function scrollToLyricIndex(index, animated) {
            const stream = document.getElementById('lyricsStream');
            if (!stream) return;
            const targetEl = document.getElementById(`line-${index}`);
            if (!targetEl) return;
            const baseH = parseFloat(document.documentElement.style.getPropertyValue('--lyric-line-height')) || 50;
            const extraOffset = (targetEl.offsetHeight > baseH + 8)
                ? Math.round((targetEl.offsetHeight - baseH) / 2)
                : 0;
            const targetOffset = targetEl.offsetTop + extraOffset;
            currentTranslateY = -targetOffset;
            if (animated) {
                stream.style.transition = 'transform 0.85s cubic-bezier(0.22, 1, 0.36, 1)';
            } else {
                stream.style.transition = 'none';
            }
            stream.style.transform = `translateY(-${targetOffset}px)`;
        }

        let isLyricFading = false;
        let lyricFadeTimer = null;

        // 统一歌词淡出淡入过渡动画（复用于：1. 手指拖拽松开归位 2. 手机拖动进度条 seek 跨行跳转）
        function triggerLyricFadeTransition(targetIdx) {
            const stream = document.getElementById('lyricsStream');
            if (!stream) {
                isManualScrolling = false;
                return;
            }
            const destIndex = (typeof targetIdx === 'number' && targetIdx >= 0) ? targetIdx : activeLyricIndex;
            if (destIndex < 0 || !lyrics || lyrics.length === 0) return;

            if (lyricFadeTimer) clearTimeout(lyricFadeTimer);
            isLyricFading = true;

            // 1. 平滑淡出视口 (0.22s)
            stream.style.transition = 'opacity 0.22s cubic-bezier(0.4, 0, 1, 1)';
            stream.style.opacity = '0';

            lyricFadeTimer = setTimeout(() => {
                // 2. 在隐身状态下瞬移到目标高亮行与视口偏移
                updateLyricHighlight(destIndex, true);
                scrollToLyricIndex(destIndex, false);

                // 3. 强制重排并等待渲染帧后平滑淡入 (0.32s)
                void stream.offsetHeight;
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        isManualScrolling = false;
                        isLyricFading = false;
                        stream.style.transition = 'opacity 0.32s cubic-bezier(0, 0, 0.2, 1), transform 0.85s cubic-bezier(0.22, 1, 0.36, 1)';
                        stream.style.opacity = '1';
                    });
                });
            }, 240);
        }
        window.triggerLyricFadeTransition = triggerLyricFadeTransition;

        function fadeTransitionToCurrentLyric() {
            triggerLyricFadeTransition(activeLyricIndex);
        }

        function getLyricLineStepPx() {
            const firstLine = document.getElementById('line-0');
            if (firstLine && firstLine.offsetHeight > 15) {
                return firstLine.offsetHeight;
            }
            return 50;
        }

        function initLyricsDragGesture() {
            const viewport = document.getElementById('lyricsViewport');
            const stream = document.getElementById('lyricsStream');
            if (!viewport || !stream) return;

            viewport.addEventListener('pointerdown', function(e) {
                if (lyricMode !== 'official' || !hasOfficialLyrics || !lyrics || lyrics.length === 0) return;
                dragStartY = e.clientY;
                dragStartTranslateY = currentTranslateY;
                isDraggingLyrics = false;
                clearTimeout(manualScrollResumeTimer);
                // 若在淡入淡出中再次触碰屏幕，立即恢复满不透明度
                stream.style.opacity = '1';
            });

            viewport.addEventListener('pointermove', function(e) {
                if (lyricMode !== 'official' || !hasOfficialLyrics || !lyrics || lyrics.length === 0) return;
                const deltaY = e.clientY - dragStartY;
                if (!isDraggingLyrics && Math.abs(deltaY) > 5) {
                    isDraggingLyrics = true;
                    isManualScrolling = true;
                    stream.style.transition = 'none';
                }

                if (isDraggingLyrics) {
                    const stepPx = getLyricLineStepPx();
                    let targetY = dragStartTranslateY + deltaY;
                    const maxOffset = -(lyrics.length - 1) * stepPx;
                    // 阻尼弹性边界
                    if (targetY > 100) targetY = 100 + (targetY - 100) * 0.3;
                    if (targetY < maxOffset - 100) targetY = maxOffset - 100 + (targetY - (maxOffset - 100)) * 0.3;

                    currentTranslateY = targetY;
                    stream.style.transform = `translateY(${targetY}px)`;

                    // 动态高亮当前视口中心对齐的歌词行
                    const previewIndex = Math.max(0, Math.min(lyrics.length - 1, Math.round(-targetY / stepPx)));
                    updateLyricHighlight(previewIndex);
                }
            });

            const onDragEnd = function(e) {
                if (!isDraggingLyrics) return;
                isDraggingLyrics = false;
                resetIdleTimer();

                // 手松开 3 秒后：不滚回，优雅淡出后淡入当前播放行
                clearTimeout(manualScrollResumeTimer);
                manualScrollResumeTimer = setTimeout(() => {
                    fadeTransitionToCurrentLyric();
                }, 3000);
            };

            viewport.addEventListener('pointerup', onDragEnd);
            viewport.addEventListener('pointercancel', onDragEnd);
        }
        document.addEventListener('DOMContentLoaded', initLyricsDragGesture);
        setTimeout(initLyricsDragGesture, 200);

        // 智能判断歌词是否为纯音乐（覆盖“作词：... 作曲：... 纯音乐，请欣赏”、无填词、或纯制作演职员信息）
        function isPureMusicLyric(lrcArray) {
            if (!lrcArray || lrcArray.length === 0) return true;

            let realSingingLines = 0;
            let hasPureMusicMarker = false;

            for (const item of lrcArray) {
                const text = (item.text || "").trim();
                if (!text) continue;

                if (PURE_MUSIC_REGEX.test(text)) {
                    hasPureMusicMarker = true;
                    continue;
                }

                if (CREDIT_LINE_REGEX.test(text)) {
                    // 演职员名单/制作信息行，非歌词唱词
                    continue;
                }

                // 属于实际唱词
                realSingingLines++;
            }

            // 1. 若出现了明确的纯音乐占位文案，只要实际唱词行 <= 2，即确认为纯音乐
            if (hasPureMusicMarker && realSingingLines <= 2) {
                return true;
            }

            // 2. 若全篇只包含作词/作曲等演职员信息而无任何实际唱词，也归为纯音乐
            if (realSingingLines === 0) {
                return true;
            }

            return false;
        }

        // 蓝牙实时歌词与网络拉取官方歌词的双层时间窗口与权威校准引擎
        let isOfficialLyricVerified = false;
        let lastHardwarePosSyncTime = 0;

        function normalizeLyricForMatch(text) {
            return String(text || "")
                .toLowerCase()
                .replace(/[\u2018\u2019\u0060\u00b4]/g, "'")
                .replace(/'(ve|re|ll|d|m|s)\b/g, "")
                .replace(/\bgotta\b/g, "gotto")
                .replace(/\bwanna\b/g, "wantto")
                .replace(/\bgonna\b/g, "goingto")
                .replace(/[^\w\u4e00-\u9fa5]/g, "");
        }

        function evaluateLyricMatch(lClean, sClean) {
            if (!lClean || !sClean) return 0; // 0: 不匹配, 3: 精确匹配, 2: 高置信子串匹配
            if (lClean === sClean) return 3;
            if (lClean.includes(sClean) || sClean.includes(lClean)) {
                const minLen = Math.min(lClean.length, sClean.length);
                const maxLen = Math.max(lClean.length, sClean.length);
                if (minLen >= 2 && (minLen / maxLen) >= 0.65) {
                    return 2;
                }
            }
            return 0;
        }

        function crossCheckLyrics(streamLyric) {
            if (!streamLyric || !hasOfficialLyrics || !lyrics || lyrics.length === 0) return;
            if (currentTrack && currentTrack.isVideo) return;
            const trimmedStream = String(streamLyric).trim();
            if (!trimmedStream) return;
            // 忽略纯音乐与演职员制作信息行（如“演唱：...”、“录音师：...”、“录音棚：...”、“潮语指导：...”），防止误将播放时钟拽回片头
            if (PURE_MUSIC_REGEX.test(trimmedStream) || CREDIT_LINE_REGEX.test(trimmedStream)) return;
            if (trimmedStream.includes("纯音乐") || trimmedStream.includes("请欣赏") || trimmedStream.includes("作词") || trimmedStream.includes("作曲")) return;

            // 纯净文本标准化（去除标点、符号、空格，统一小写与常见英语缩略词如 we've/we、gotta/got to）
            const sClean = normalizeLyricForMatch(trimmedStream);
            if (sClean.length < 2) return;

            const curEstimated = lastPositionMs + (isPlaying ? (performance.now() - lastSyncTime) * playbackSpeed : 0);

            // 第一层（优先窗口）：在当前估计进度前后 ±15 秒窗口内全局评估所有匹配候选行，
            // 严禁命中第一个候选就提前 break（否则如《Sign of the Times》中密集重复的 "The bullets" / "We gotta get away"
            // 会始终命中窗口最前方的历史旧句，导致歌词与进度条反复回跳并同硬件时钟拉锯乱跳）。
            const WINDOW_MS = 15000;
            let matchedIndex = -1;
            let bestWindowScoreDist = Infinity;

            for (let i = 0; i < lyrics.length; i++) {
                const rawLine = (lyrics[i].text || "").trim();
                if (CREDIT_LINE_REGEX.test(rawLine)) continue;
                const lTime = lyrics[i].time;
                if (lTime >= curEstimated - WINDOW_MS && lTime <= curEstimated + WINDOW_MS) {
                    const lClean = normalizeLyricForMatch(rawLine);
                    const matchType = evaluateLyricMatch(lClean, sClean);
                    if (matchType > 0) {
                        let scoreDist = Math.abs(lTime - curEstimated);
                        // 精确匹配绝对优先于子串包含匹配（如 "Stop your crying" 优先于 "Just stop your crying"）
                        if (matchType < 3) {
                            scoreDist += 3500;
                        }
                        // 顺向播放单调偏置：对落后于当前已激活行 (i < activeLyricIndex) 的历史重复句施加逆向惩罚，
                        // 防止每 4 秒重复一次的相同短句误回跳到上一句
                        if (activeLyricIndex >= 0 && i < activeLyricIndex && curEstimated >= (lyrics[activeLyricIndex].time - 2000)) {
                            scoreDist += 4500;
                        }
                        if (scoreDist < bestWindowScoreDist) {
                            bestWindowScoreDist = scoreDist;
                            matchedIndex = i;
                        }
                    }
                }
            }

            // 第二层（全局兜底窗口）：仅当优先窗口未命中，且硬件 AVRCP 时钟未处于实时高频同步锁定态时，
            // 使用严格的高置信度匹配寻找全局最近候选行（杜绝如 01:04 的长句因细微词差落入全局窗口后，
            // 误命中 04:20 处的短句引发跨分钟远距离乱跳）。
            const hardwarePosLocked = (lastHardwarePosSyncTime > 0 && (performance.now() - lastHardwarePosSyncTime) < 3500 && lastPositionMs > 0);
            if (matchedIndex < 0 && !hardwarePosLocked) {
                let bestFallbackScoreDist = Infinity;
                for (let i = 0; i < lyrics.length; i++) {
                    const rawLine = (lyrics[i].text || "").trim();
                    if (CREDIT_LINE_REGEX.test(rawLine)) continue;
                    const lClean = normalizeLyricForMatch(rawLine);
                    const matchType = evaluateLyricMatch(lClean, sClean);
                    // 全局跨窗口兜底要求精确匹配或极高长度占比 (>= 0.8)
                    const lenRatio = (lClean && sClean) ? (Math.min(lClean.length, sClean.length) / Math.max(lClean.length, sClean.length)) : 0;
                    if (matchType === 3 || (matchType === 2 && lenRatio >= 0.8)) {
                        const scoreDist = Math.abs(lyrics[i].time - curEstimated) + (matchType < 3 ? 3500 : 0);
                        if (scoreDist < bestFallbackScoreDist) {
                            bestFallbackScoreDist = scoreDist;
                            matchedIndex = i;
                        }
                    }
                }
            }

            if (matchedIndex >= 0) {
                isOfficialLyricVerified = true;
                const matchedTime = lyrics[matchedIndex].time;
                const timeDiff = Math.abs(matchedTime - curEstimated);

                // 防回跳保护：当播放时钟正常顺向推进且偏差在合理范围内（<= 3500ms）时，
                // 若蓝牙延后或重复推送了上一句歌词（matchedIndex < activeLyricIndex），绝不将已顺向推进的歌词行往回拽，
                // 避免与 60fps updateTimeline 产生“跳回上一行 -> 16ms 后又跳回下一行”的乒乓闪跳。
                if (activeLyricIndex >= 0 && matchedIndex < activeLyricIndex && timeDiff <= 3500 && curEstimated >= (lyrics[activeLyricIndex].time - 3500)) {
                    return;
                }

                activeLyricIndex = matchedIndex;
                if (!isManualScrolling) {
                    updateLyricHighlight(activeLyricIndex);
                    scrollToLyricIndex(activeLyricIndex, true);
                }
                // 仅当估算时钟与匹配行时间偏差超过阈值时才重设播放时钟，
                // 杜绝正常播放时因行首时间戳回写导致进度条倒退跳变（如 00:06 跳回 00:04）
                const reanchorThreshold = hardwarePosLocked ? 5000 : 2500;
                if (isPlaying && matchedTime > 0 && timeDiff > reanchorThreshold) {
                    lastPositionMs = matchedTime;
                    lastSyncTime = performance.now();
                }
            }
        }
        window.crossCheckLyrics = crossCheckLyrics;

        function renderLyrics(lrcArray) {
            lyrics = lrcArray;
            if (lrcArray && parsedRawLrcMap.has(lrcArray)) {
                currentRawLrcText = parsedRawLrcMap.get(lrcArray);
            }
            lastHardwarePosSyncTime = 0;
            const stream = document.getElementById('lyricsStream');
            const emptyBox = document.getElementById('emptyLyrics');
            const emptyText = document.getElementById('emptyText');
            const largeViz = document.getElementById('largeVisualizer');
            isManualScrolling = false;
            clearTimeout(manualScrollResumeTimer);

            if (currentTrack && currentTrack.isVideo) {
                if (stream) {
                    stream.innerHTML = '';
                    stream.style.display = 'none';
                }
                if (emptyBox) emptyBox.style.display = 'none';
                if (largeViz) largeViz.style.display = 'flex';
                syncSpectrumVisibility();
                return;
            }

            if (currentActiveSource === 'visualizer') {
                if (stream) stream.style.display = 'none';
                if (emptyBox) emptyBox.style.display = 'none';
                if (largeViz) largeViz.style.display = 'flex';
                syncSpectrumVisibility();
                return;
            }

            // 智能识别：无论是“本身就没歌词”，还是歌词内容全部是“纯音乐请欣赏”等占位文案，
            // 一律自动跳转“律动氛围”（大号 9 频段声波律动），且绝不记忆该临时跳转！
            // 下一首歌如果有歌词，将无缝自动跳回用户之前选择的歌词来源！
            const isPureMusic = isPureMusicLyric(lyrics);

            if (isPureMusic) {
                hasOfficialLyrics = false;
                activeLyricIndex = -1;
                renderedLyricIndex = -1;
                if (currentTrack.streamLyric && !isPureMusicLyric([{ text: currentTrack.streamLyric }])) {
                    if (emptyBox) emptyBox.style.display = 'none';
                    if (largeViz) largeViz.style.display = 'none';
                    if (stream) stream.style.display = 'block';
                    showStreamLyric(currentTrack.streamLyric);
                } else {
                    // 自动跳转律动氛围（大号 9 频段声波律动），不显示任何文字！
                    if (emptyBox) emptyBox.style.display = 'none';
                    if (stream) {
                        stream.innerHTML = '';
                        stream.style.display = 'none';
                    }
                    if (largeViz) largeViz.style.display = 'flex';
                    updateStatusTag('纯音乐', '#888888');
                    syncSpectrumVisibility();
                }
                return;
            }

            hasOfficialLyrics = true;
            activeLyricIndex = 0;
            renderedLyricIndex = -1;

            // 1. 提示框与大号频谱平滑隐藏
            if (largeViz) largeViz.style.display = 'none';
            if (emptyBox) {
                emptyBox.style.transition = 'opacity 0.25s ease-out';
                emptyBox.style.opacity = '0';
                setTimeout(() => {
                    if (hasOfficialLyrics && emptyBox) {
                        emptyBox.style.display = 'none';
                        syncSpectrumVisibility();
                    }
                }, 260);
            }
            syncSpectrumVisibility();

            // 2. 新歌词直接从第0行开始，平滑淡入展示 (0.35s)
            if (stream) {
                stream.innerHTML = '';
                stream.style.display = 'block';
                stream.style.transition = 'none';
                stream.style.opacity = '0';
                stream.style.transform = 'translateY(0px)';
                currentTranslateY = 0;

                lyrics.forEach((item, index) => {
                    const div = document.createElement('div');
                    div.className = 'lyric-line' + (index === 0 ? ' active' : (index <= 4 ? ` near near-${index}` : ' far'));
                    div.id = `line-${index}`;
                    populateLyricLine(div, item);
                    stream.appendChild(div);
                });

                // 强制重排后淡入新歌词
                void stream.offsetWidth;
                stream.style.transition = 'opacity 0.35s ease-in, transform 0.85s cubic-bezier(0.22, 1, 0.36, 1)';
                stream.style.opacity = '1';
            }
        }

        function showStreamLyric(text) {
            if (currentActiveSource === 'auto' && isPureMusicLyric([{ text }])) {
                renderLyrics([]);
                return;
            }
            const stream = document.getElementById('lyricsStream');
            const emptyBox = document.getElementById('emptyLyrics');
            const largeViz = document.getElementById('largeVisualizer');
            if (largeViz) largeViz.style.display = 'none';
            if (emptyBox) emptyBox.style.display = 'none';
            syncSpectrumVisibility();
            if (stream) {
                stream.style.display = 'block';
                stream.style.opacity = '1';
                stream.style.transform = 'translateY(-24px)';
                stream.textContent = '';
                const box = document.createElement('div');
                box.className = 'live-stream-box';
                const lyric = document.createElement('div');
                lyric.className = 'live-stream-lyric';
                let cleanText = (text || '当前蓝牙源暂未投送歌词').trim();
                cleanText = cleanText.replace(/[\u2018\u2019\u0060\u00b4]/g, "'");
                cleanText = cleanText.replace(/\b([a-zA-Z]+)\s*'\s*([a-zA-Z]+)\b/g, "$1'$2");
                lyric.textContent = cleanText;
                box.appendChild(lyric);
                stream.appendChild(box);
            }
            updateStatusTag('蓝牙实时歌词', '#4a90e2');
        }

        // 歌词源代表色与信息字典（新增律动氛围选项）
        const SOURCE_META = {
            netease: { name: '网易云音乐', color: '#ff3a3a', label: '网易云音乐', id: 'netease' },
            qq: { name: 'QQ音乐', color: '#31c27c', label: 'QQ音乐', id: 'qq' },
            kugou: { name: '酷狗音乐', color: '#0084ff', label: '酷狗音乐', id: 'kugou' },
            lrclib: { name: 'LRCLIB 全球库', color: '#a855f7', label: 'LRCLIB', id: 'lrclib' },
            bluetooth: { name: '蓝牙实时歌词', color: '#38bdf8', label: '蓝牙实时歌词', id: 'bluetooth' },
            visualizer: { name: '律动氛围', color: '#00f2fe', label: '律动氛围', id: 'visualizer' },
            auto: { name: '自动推荐', color: '#f59e0b', label: '自动匹配', id: 'auto' }
        };

        // 记忆歌词源偏好：切歌时严格保持用户手动选择的来源，不自动回退
        let currentActiveSource = localStorage.getItem('preferred_lyric_source') || 'auto';
        let userExplicitMode = (currentActiveSource !== 'auto') ? currentActiveSource : null;

        function getSourceMeta(src) {
            if (!src) return SOURCE_META.auto;
            const s = src.toLowerCase();
            if (s.includes('网易') || s.includes('netease')) return SOURCE_META.netease;
            if (s.includes('qq') || s.includes('tencent')) return SOURCE_META.qq;
            if (s.includes('酷狗') || s.includes('kugou')) return SOURCE_META.kugou;
            if (s.includes('lrclib')) return SOURCE_META.lrclib;
            if (s.includes('律动') || s.includes('visualizer') || s.includes('氛围')) return SOURCE_META.visualizer;
            if (s.includes('蓝牙') || s.includes('bluetooth') || s.includes('stream')) return SOURCE_META.bluetooth;
            return { name: src, color: '#31c27c', label: src, id: 'auto' };
        }

        function getLyricSourceLabel(src) {
            const meta = getSourceMeta(src);
            return meta ? (meta.label || meta.name) : '官方歌词';
        }

        // 播放代次与请求序列号管理（根治切歌竞态、乱序到达与缓存污染）
        let currentPlaybackGeneration = 0;
        let lastSourceRequestId = {};
        let currentSongKey = '';

        function toggleLyricMenu(e) {
            if (e) {
                if (e.target && e.target.closest && e.target.closest('#lyricFontWidget, .lyric-font-widget, .font-btn, #btnFontDec, #btnFontInc, #fontSizeLabel')) {
                    return; // 点击字号控件区域绝不触发歌词源菜单的开闭切换
                }
                if (e.stopPropagation) e.stopPropagation();
            }
            const dropdown = document.getElementById('deviceDropdown');
            const pill = document.getElementById('devicePill');
            if (dropdown) dropdown.classList.remove('show');
            if (pill) pill.classList.remove('active');

            const menu = document.getElementById('lyricSourceMenu');
            if (!menu) return;
            const isOpen = menu.classList.toggle('show');
            document.body.classList.toggle('lyric-menu-open', isOpen);
            updateMenuCheckmarks();
            document.body.classList.remove('idle');
            resetIdleTimer();
        }

        function closeLyricMenu() {
            const menu = document.getElementById('lyricSourceMenu');
            if (menu) menu.classList.remove('show');
            document.body.classList.remove('lyric-menu-open');
            resetIdleTimer();
        }

        function updateMenuCheckmarks() {
            const keys = ['auto', 'netease', 'qq', 'kugou', 'lrclib', 'bluetooth', 'visualizer'];
            keys.forEach(k => {
                const item = document.getElementById('menuItem-' + k);
                if (item) {
                    if (k === currentActiveSource) {
                        item.classList.add('active');
                    } else {
                        item.classList.remove('active');
                    }
                }
            });
            syncTranslationMenuUI();
        }

        function toggleTranslation(e) {
            if (e && e.stopPropagation) e.stopPropagation();
            showTranslation = !showTranslation;
            try {
                localStorage.setItem('bmc_show_translation', showTranslation ? '1' : '0');
            } catch (err) {}
            syncTranslationMenuUI();
            reapplyLyricsAtCurrentFontSize();
            showToastTip(showTranslation ? '译文显示：已开启' : '译文显示：已关闭');
            resetIdleTimer();
        }
        window.toggleTranslation = toggleTranslation;
        window.isTranslationEnabled = () => showTranslation;

        // 点击页面其它任意地方自动收起二级菜单
        document.addEventListener('click', function(e) {
            const menu = document.getElementById('lyricSourceMenu');
            if (!menu || !menu.classList.contains('show')) return;
            const btn = document.getElementById('statusTag');
            const fontWidget = document.getElementById('lyricFontWidget');
            if (!menu.contains(e.target) && (!btn || !btn.contains(e.target)) && (!fontWidget || !fontWidget.contains(e.target))) {
                closeLyricMenu();
            }
        });

        // 获取大号律动频谱条
        let largeWaveBars = [];
        function getLargeWaveBars() {
            if (largeWaveBars.length === 0) {
                const container = document.getElementById('largeWave');
                if (container) {
                    largeWaveBars = Array.from(container.getElementsByClassName('large-bar'));
                }
            }
            return largeWaveBars;
        }

        function applyLyricSourceForCurrentTrack(sourceId, isUserTriggered) {
            const trackKey = currentSongKey || (currentTrack && currentTrack.trackKey) || ((currentTrack ? currentTrack.title : '') + '\t' + (currentTrack ? currentTrack.artist : '')).toLowerCase().trim();
            const cached = ensureSongCacheEntry(trackKey);

            const largeViz = document.getElementById('largeVisualizer');
            const stream = document.getElementById('lyricsStream');
            const emptyBox = document.getElementById('emptyLyrics');

            // 模式 1：律动氛围（大号频谱波形动画）
            if (sourceId === 'visualizer') {
                lyricMode = 'visualizer';
                userExplicitMode = 'visualizer';
                if (stream) stream.style.display = 'none';
                if (emptyBox) emptyBox.style.display = 'none';
                if (largeViz) largeViz.style.display = 'flex';
                updateStatusTag(SOURCE_META.visualizer.label, SOURCE_META.visualizer.color);
                syncSpectrumVisibility();
                return;
            }

            // 非律动氛围：隐藏大号频谱，恢复歌词视口
            if (largeViz) largeViz.style.display = 'none';
            if (stream) stream.style.display = 'block';
            syncSpectrumVisibility();

            // 模式 2：蓝牙实时歌词
            if (sourceId === 'bluetooth') {
                lyricMode = 'bluetooth';
                userExplicitMode = 'bluetooth';
                const streamText = currentTrack.streamLyric || (cached && cached.streamLyric) || "当前蓝牙源暂未投送歌词";
                showStreamLyric(streamText);
                updateStatusTag(SOURCE_META.bluetooth.label, SOURCE_META.bluetooth.color);
                return;
            }

            // 模式 3：自动推荐最佳匹配
            if (sourceId === 'auto') {
                userExplicitMode = null;
                if (cached.parsedLyrics && cached.parsedLyrics.length > 0) {
                    lyricMode = 'official';
                    currentRawLrcText = cached.rawLrc || '';
                    parsedOfficialLyrics = currentRawLrcText ? parseLRC(currentRawLrcText) : cached.parsedLyrics;
                    hasOfficialLyrics = true;
                    renderLyrics(parsedOfficialLyrics);
                    const meta = getSourceMeta(cached.lyricSource);
                    updateStatusTag(meta.label, meta.color);
                } else if (currentTrack.streamLyric) {
                    lyricMode = 'bluetooth';
                    showStreamLyric(currentTrack.streamLyric);
                    updateStatusTag(SOURCE_META.bluetooth.label, SOURCE_META.bluetooth.color);
                } else {
                    renderLyrics([]);
                    updateStatusTag('纯音乐 / 暂无歌词', '#888888');
                }
                return;
            }

            // 模式 4：用户手动指定外部歌词源 (netease / qq / kugou / lrclib)
            lyricMode = 'official';
            userExplicitMode = sourceId;
            const meta = SOURCE_META[sourceId] || SOURCE_META.auto;

            // 若当前歌曲已缓存过此源的歌词，立即呈现
            if (cached[sourceId + '_lyrics']) {
                currentRawLrcText = cached[sourceId + '_rawLrc'] || '';
                parsedOfficialLyrics = currentRawLrcText ? parseLRC(currentRawLrcText) : cached[sourceId + '_lyrics'];
                hasOfficialLyrics = true;
                renderLyrics(parsedOfficialLyrics);
                updateStatusTag(meta.label, meta.color);
                return;
            }

            // 否则向原生层发起精准网络检索（静默更新右下角胶囊，不再弹出居中 Toast）
            updateStatusTag('检索' + meta.label + '中...', meta.color);

            if (window.AndroidApp && window.AndroidApp.requestLyricSource) {
                window.AndroidApp.requestLyricSource(
                    sourceId,
                    currentTrack.title || "",
                    currentTrack.artist || "",
                    currentTrack.album || "",
                    currentPlaybackGeneration
                );
            }
        }

        function selectLyricSource(sourceId, e) {
            if (e && e.stopPropagation) e.stopPropagation();
            currentActiveSource = sourceId;
            userExplicitMode = (sourceId !== 'auto') ? sourceId : null;
            if (currentTrack && sourceId !== 'visualizer') {
                currentTrack.isVideo = false;
            }
            try {
                localStorage.setItem('preferred_lyric_source', sourceId);
            } catch (err) {}
            closeLyricMenu();
            updateMenuCheckmarks();

            applyLyricSourceForCurrentTrack(sourceId, true);
            if (sourceId === 'visualizer' && window.AndroidApp && window.AndroidApp.updateMusicPermission) {
                window.AndroidApp.updateMusicPermission(true, true);
            }
            // 切换歌词来源后，严格启动 6 秒自动淡出控件计时
            resetIdleTimer();
        }

