        function getVisualLength(str) {
            if (!str) return 0;
            let len = 0;
            for (let i = 0; i < str.length; i++) {
                len += str.charCodeAt(i) > 255 ? 2 : 1;
            }
            return len;
        }

        let measureCanvas = null;
        let measureCtx = null;
        function measureLyricTextWidth(text, fontSize) {
            if (!text) return 0;
            if (typeof document !== 'undefined' && document.createElement) {
                try {
                    if (!measureCanvas) {
                        measureCanvas = document.createElement('canvas');
                        measureCtx = measureCanvas ? measureCanvas.getContext('2d') : null;
                    }
                    if (measureCtx) {
                        measureCtx.font = `700 ${fontSize}px system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif`;
                        const metrics = measureCtx.measureText(text);
                        if (metrics && typeof metrics.width === 'number') {
                            return metrics.width;
                        }
                    }
                } catch (e) {}
            }
            let w = 0;
            for (let i = 0; i < text.length; i++) {
                w += text.charCodeAt(i) > 255 ? (fontSize * 1.05) : (fontSize * 0.58);
            }
            return w;
        }

        function getAvailableLyricWidth() {
            if (typeof document !== 'undefined') {
                const vp = document.getElementById('lyricsViewport');
                if (vp && vp.clientWidth > 0) {
                    return vp.clientWidth - 28;
                }
                if (typeof window !== 'undefined' && window.innerWidth > 100) {
                    return window.innerWidth * 0.52 - 28;
                }
            }
            return 560;
        }

        // 歌词字号应用与自适应重算
        function applyLyricFontSize(size, notifyReparse) {
            currentLyricFontSize = Math.max(14, Math.min(52, size));
            try {
                localStorage.setItem('bmc_lyric_font_size', currentLyricFontSize);
            } catch (e) {}
            const lineHeight = Math.max(40, Math.round(currentLyricFontSize * 2.35));
            document.documentElement.style.setProperty('--lyric-font-size', currentLyricFontSize + 'px');
            document.documentElement.style.setProperty('--lyric-line-height', lineHeight + 'px');

            const label = document.getElementById('fontSizeLabel');
            if (label) label.textContent = currentLyricFontSize + 'px';

            if (notifyReparse) {
                reapplyLyricsAtCurrentFontSize();
            } else if (activeLyricIndex >= 0) {
                updateLyricHighlight(activeLyricIndex, true);
                scrollToLyricIndex(activeLyricIndex, false);
            }
        }
        window.applyLyricFontSize = applyLyricFontSize;

        function adjustLyricFontSize(delta, e) {
            // 严格门禁：事件触发时当且仅当二级菜单处于展开状态才允许调整字号（杜绝未展开时误触）
            if (e && !document.body.classList.contains('lyric-menu-open')) {
                return;
            }
            if (e) {
                if (e.stopPropagation) e.stopPropagation();
                if (e.preventDefault) e.preventDefault();
            }

            resetIdleTimer();
            applyLyricFontSize(currentLyricFontSize + delta, true);
        }
        window.adjustLyricFontSize = adjustLyricFontSize;

        function initLyricFontWidgetTouch() {
            const widget = document.getElementById('lyricFontWidget');
            if (!widget || widget.dataset.touchInitialized) return;
            widget.dataset.touchInitialized = 'true';
            ['pointerdown', 'pointerup', 'touchstart', 'touchend'].forEach(type => {
                widget.addEventListener(type, e => e.stopPropagation(), { passive: true });
            });
            // A tap produces one click, including on WebViews with delayed
            // compatibility mouse events. Pointer/touch events only isolate UI.
            widget.addEventListener('click', e => {
                e.stopPropagation();
                if (!document.body.classList.contains('lyric-menu-open')) return;
                const button = e.target.closest('.font-btn');
                if (button) {
                    adjustLyricFontSize(button.id === 'btnFontDec' ? -1 : 1, e);
                    return;
                }
                const rect = widget.getBoundingClientRect();
                if (e.clientX > 0) {
                    adjustLyricFontSize(e.clientX < rect.left + rect.width / 2 ? -1 : 1, e);
                }
            });
        }

        // 演职员制作信息与纯音乐占位行统一过滤正则（供解析、纯音乐识别与蓝牙实时歌词校准共用）
        const CREDIT_LINE_REGEX = /^(作词|作曲|编曲|制作|制作人|监制|混音|混音师|母带|母带工程|录音|录音师|录音棚|录音室|吉他|木吉他|电吉他|贝斯|鼓|键盘|钢琴|弦乐|小提琴|大提琴|古筝|二胡|笛子|箫|琵琶|和声|和音|伴唱|演唱|女声|男声|主唱|合唱|配唱|制作统筹|统筹|企划|文案|宣传|推广|出品|出品人|发行|监制人|封面|设计|鸣谢|特别鸣谢|指导|配音|念白|旁白|语言指导|粤语指导|潮语指导|闽南语指导|方言指导|原唱|翻唱|词|曲|编|混|录|唱|op|sp|written by|composed by|arranged by|produced by|mixed by|mastered by|recorded by|recorded at|vocal|vocals|chorus|guitar|bass|drums|piano|strings|lyrics by|music by|published by)[:：\s]/i;
        const PURE_MUSIC_REGEX = /(纯音乐|请欣赏|没有填词|暂无歌词|无歌词|享受音乐|instrumental|no lyrics|pure music)/i;

        let showTranslation = true;
        try {
            showTranslation = localStorage.getItem('bmc_show_translation') !== '0';
        } catch (e) {}

        function syncTranslationMenuUI() {
            const label = document.getElementById('translationStatusText');
            if (label) {
                label.textContent = showTranslation ? '已开启' : '已关闭';
                label.style.color = showTranslation ? '#31c27c' : 'rgba(255,255,255,0.45)';
            }
        }

        function populateLyricLine(div, item) {
            if (!div || !item) return;
            if (showTranslation && item.subText) {
                div.textContent = '';
                div.dataset.hasSub = '1';
                const mainSpan = document.createElement('span');
                mainSpan.className = 'lyric-main';
                mainSpan.textContent = item.text;
                const subSpan = document.createElement('span');
                subSpan.className = 'lyric-sub';
                subSpan.textContent = item.subText;
                div.appendChild(mainSpan);
                div.appendChild(subSpan);
            } else {
                delete div.dataset.hasSub;
                div.textContent = item.text;
            }
        }

        try {
            applyLyricFontSize(currentLyricFontSize, false);
            initLyricFontWidgetTouch();
            syncTranslationMenuUI();
        } catch (e) {}
        document.addEventListener('DOMContentLoaded', function() {
            applyLyricFontSize(currentLyricFontSize, false);
            initLyricFontWidgetTouch();
            syncTranslationMenuUI();
        });
        setTimeout(initLyricFontWidgetTouch, 200);

        function reapplyLyricsAtCurrentFontSize() {
            if (!hasOfficialLyrics) return;
            let raw = currentRawLrcText;
            if ((!raw || raw.trim().length === 0) && currentSongKey && songCache[currentSongKey]) {
                raw = currentActiveSource === 'auto' ? songCache[currentSongKey].rawLrc : songCache[currentSongKey][currentActiveSource + '_rawLrc'];
            }
            if (raw && raw.trim().length > 0) {
                currentRawLrcText = raw;
                const curTime = (lyrics && lyrics[activeLyricIndex]) ? lyrics[activeLyricIndex].time : (lastPositionMs || 0);
                const parsed = parseLRC(raw);
                if (parsed && parsed.length > 0) {
                    lyrics = parsed;
                    parsedOfficialLyrics = parsed;
                    const stream = document.getElementById('lyricsStream');
                    if (stream && stream.style.display !== 'none') {
                        stream.innerHTML = '';
                        lyrics.forEach((item, index) => {
                            const div = document.createElement('div');
                            div.className = 'lyric-line far';
                            div.id = `line-${index}`;
                            populateLyricLine(div, item);
                            stream.appendChild(div);
                        });
                        let newIdx = 0;
                        for (let i = 0; i < lyrics.length; i++) {
                            if (curTime >= lyrics[i].time) newIdx = i;
                            else break;
                        }
                        activeLyricIndex = newIdx;
                        renderedLyricIndex = -1;
                        updateLyricHighlight(activeLyricIndex, true);
                        scrollToLyricIndex(activeLyricIndex, false);
                        return;
                    }
                }
            }
            if (hasOfficialLyrics && lyrics && lyrics.length > 0) {
                for (let i = 0; i < lyrics.length; i++) {
                    const lineEl = document.getElementById(`line-${i}`);
                    if (lineEl) populateLyricLine(lineEl, lyrics[i]);
                }
            }
            if (activeLyricIndex >= 0) {
                updateLyricHighlight(activeLyricIndex, true);
                scrollToLyricIndex(activeLyricIndex, false);
            }
        }
        window.reapplyLyricsAtCurrentFontSize = reapplyLyricsAtCurrentFontSize;
        let lyricResizeTimer;
        window.addEventListener('resize', () => {
            clearTimeout(lyricResizeTimer);
            lyricResizeTimer = setTimeout(reapplyLyricsAtCurrentFontSize, 120);
        });

        // 智能切分超长单行歌词：动态结合当前字号与视口真实宽度，能完整显示则绝不拆分
        function splitLongLyrics(items) {
            if (!items || items.length === 0) return [];
            const result = [];
            const limit = Math.max(1, getAvailableLyricWidth() / 1.18);
            items.forEach((item, index) => {
                const text = item.text.trim().replace(/[\u2018\u2019\u0060\u00b4]/g, "'")
                    .replace(/\b([a-zA-Z]+)\s*'\s*([a-zA-Z]+)\b/g, "$1'$2");
                const chars = Array.from(text);
                const parts = [];
                for (let start = 0; start < chars.length;) {
                    let end = start;
                    let boundary = start;
                    let line = '';
                    while (end < chars.length) {
                        const candidate = line + chars[end];
                        if (end > start && measureLyricTextWidth(candidate, currentLyricFontSize) > limit) break;
                        line = candidate;
                        if (/[\s，。！？、,;；!?]/.test(chars[end])) boundary = end + 1;
                        end++;
                    }
                    if (end < chars.length && boundary > start) end = boundary;
                    const part = chars.slice(start, end).join('').trim();
                    if (part) parts.push(part);
                    start = end;
                }
                const total = parts.reduce((sum, part) => sum + getVisualLength(part), 0);
                const next = items[index + 1];
                const duration = next ? Math.max(0, next.time - item.time) : 4000;
                let consumed = 0;
                for (let pIdx = 0; pIdx < parts.length; pIdx++) {
                    const part = parts[pIdx];
                    const row = { time: item.time + Math.floor(duration * consumed / total), text: part };
                    if (item.subText && pIdx === parts.length - 1) {
                        row.subText = item.subText;
                    }
                    result.push(row);
                    consumed += getVisualLength(part);
                }
            });
            return result;
        }

        function parseRawLrcEntries(blockText) {
            if (!blockText) return [];
            const lines = blockText.split('\n');
            const result = [];
            let globalOffsetMs = 0;

            // 1. 提取全局时间偏移标签 [offset: +/-xxx]
            const offsetReg = /\[offset:\s*([+-]?\d+)\]/i;
            for (const line of lines) {
                const offMatch = line.match(offsetReg);
                if (offMatch) {
                    globalOffsetMs = parseInt(offMatch[1], 10) || 0;
                    break;
                }
            }

            // 2. 支持无小数 [mm:ss]、变长小数 [mm:ss.s] / [mm:ss.xx] / [mm:ss.xxx] 以及分秒毫秒
            const timeReg = /\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/g;

            for (const line of lines) {
                // 忽略非歌词的元数据标签行，如 [ti:], [ar:], [al:], [by:], [offset:]
                if (/^\[(ti|ar|al|by|offset|length):/i.test(line.trim())) continue;

                const matches = [...line.matchAll(timeReg)];
                if (matches.length === 0) continue;
                const text = line.replace(timeReg, '').trim();
                if (!text) continue;

                for (const match of matches) {
                    const m = parseInt(match[1], 10);
                    const s = parseInt(match[2], 10);
                    const fracStr = match[3] || '0';
                    const ms = parseInt(fracStr.padEnd(3, '0').slice(0, 3), 10);
                    let totalTime = m * 60000 + s * 1000 + ms + globalOffsetMs;
                    if (totalTime < 0) totalTime = 0;
                    result.push({ time: totalTime, text: text });
                }
            }
            result.sort((a, b) => a.time - b.time);
            return result;
        }

        function parseLRC(lrcText) {
            if (!lrcText) return [];
            const marker = '\n[bmc:trans]\n';
            const splitIdx = lrcText.indexOf(marker);
            const primaryBlock = splitIdx >= 0 ? lrcText.slice(0, splitIdx) : lrcText;
            const transBlock = splitIdx >= 0 ? lrcText.slice(splitIdx + marker.length) : '';

            const result = parseRawLrcEntries(primaryBlock);
            if (transBlock && result.length > 0) {
                const transEntries = parseRawLrcEntries(transBlock).filter(t => {
                    const txt = (t.text || '').trim();
                    return txt && txt !== '//' && !CREDIT_LINE_REGEX.test(txt);
                });
                if (transEntries.length > 0) {
                    for (const item of result) {
                        const mainTxt = (item.text || '').trim();
                        if (!mainTxt || CREDIT_LINE_REGEX.test(mainTxt)) continue;
                        let bestTrans = null;
                        let bestDelta = 301;
                        for (const t of transEntries) {
                            const delta = Math.abs(t.time - item.time);
                            if (delta <= 300 && delta < bestDelta) {
                                bestDelta = delta;
                                bestTrans = t;
                            }
                        }
                        if (bestTrans && bestTrans.text !== mainTxt) {
                            item.subText = bestTrans.text;
                        }
                    }
                }
            }

            return isPureMusicLyric(result) ? result : splitLongLyrics(result);
        }
        window.parseLRC = parseLRC;

        // 智能优化歌名分行逻辑（古典作品乐章绑定、括号副标题语义换行、破折号断句、防末尾寡字）
        function formatSongTitle(rawTitle) {
            if (!rawTitle) return "";
            // 0. 规范化弯单双引号并紧凑英文缩写（如 "It’s" -> "It's"，杜绝 CJK 全角单引号占用大字格空白）
            let t = rawTitle.replace(/[\u2018\u2019\u0060\u00b4]/g, "'");
            t = t.replace(/[\u201c\u201d]/g, '"');
            t = t.replace(/\b([a-zA-Z]+)\s*'\s*([a-zA-Z]+)\b/g, "$1'$2");

            // 1. 标准化标点符号与其后空格：确保冒号后有空格（如 "K. 285:I. Allegro" -> "K. 285: I. Allegro"）
            t = t.replace(/:([^\s])/g, ': $1');
            // 2. 乐章罗马数字（I. / II. / III. / IV. 等）与后方速度标记之间绑定不换行空格（\u00A0），
            //    确保 "I." 和 "Allegro" 绝对保持在同一行，绝不孤立分行
            t = t.replace(/\b([IVXLCDM]+\.)\s+/gi, '$1\u00A0');
            // 3. 作品编号前缀（Op. / K. / BWV / Hob. / No.）与其编号之间绑定不换行空格
            t = t.replace(/\b(Op\.|K\.|BWV|Hob\.|No\.)\s*(\d+)/gi, '$1\u00A0$2');
            // 4. 合作歌手标识词绑定（feat. / ft. / with）
            t = t.replace(/\b(feat\.|ft\.|with)\s+/gi, '$1\u00A0');

            // 5. 智能语义断句分行（副标题、影视原声括号、版本说明、破折号断句）
            // 当歌名总字符数较长 (>= 12 个字符) 且包含括号或破折号时，在断句处引入语义换行 \n
            if (t.length >= 12) {
                // 括号副标题前插入换行（如 "好不容易 (电视剧《华灯初上》片尾曲)" -> "好不容易\n(电视剧《华灯初上》片尾曲)"）
                // 仅当括号前已有至少 2 个字符的主歌名时断句，避免标题以括号开头时首行空行
                t = t.replace(/^(.{2,}?)\s*([\(（【\[])(?=[^\)）\]】]{2,}[\)）\]】])/u, '$1\n$2');

                // 破折号副标题前插入换行（如 "青花瓷 - 伴奏" -> "青花瓷\n- 伴奏"）
                if (!t.includes('\n')) {
                    t = t.replace(/^(.{2,}?)\s+([—–\-]\s+.+)$/u, '$1\n$2');
                }
            }

            return t;
        }

