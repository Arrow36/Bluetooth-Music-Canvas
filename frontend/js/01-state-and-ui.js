        // 彻底禁止屏幕拖动引起的 WebView 滚动
        document.addEventListener('touchmove', function(e) {
            e.preventDefault();
        }, { passive: false });

        let currentTrack = { title: "", artist: "", album: "", duration: 0, streamLyric: "" };
        window.currentTrack = currentTrack;
        let isPlaying = false;
        let lastPositionMs = 0;
        let lastSyncTime = performance.now();
        let lyrics = [];
        let parsedOfficialLyrics = [];
        let hasOfficialLyrics = false;
        let activeLyricIndex = -1;
        let renderedLyricIndex = -1;
        let currentRawLrcText = "";
        let currentLyricFontSize = 20;
        try {
            const savedSize = parseInt(localStorage.getItem('bmc_lyric_font_size'), 10);
            if (savedSize >= 14 && savedSize <= 52) {
                currentLyricFontSize = savedSize;
            }
        } catch (e) {}
        let currentCoverUrl = "";
        let lastValidMusicCoverUrl = "";
        let lyricMode = 'official'; // 'official' | 'bluetooth'
        
        // 歌曲元数据与歌词内存缓存表（避免返回桌面重新点击时重复请求与闪白，上限 50 首防长时运行内存膨胀）
        const MAX_SONG_CACHE_ENTRIES = 50;
        const songCache = {};
        window.songCache = songCache;

        function ensureSongCacheEntry(key) {
            if (!key) return {};
            if (!songCache[key]) {
                const keys = Object.keys(songCache);
                if (keys.length >= MAX_SONG_CACHE_ENTRIES) {
                    delete songCache[keys[0]];
                }
                songCache[key] = {};
            }
            return songCache[key];
        }

        Object.defineProperty(window, 'lastPositionMs', {
            get: () => lastPositionMs,
            set: (v) => { lastPositionMs = v; },
            configurable: true
        });
        Object.defineProperty(window, 'lastSyncTime', {
            get: () => lastSyncTime,
            set: (v) => { lastSyncTime = v; },
            configurable: true
        });
        Object.defineProperty(window, 'activeLyricIndex', {
            get: () => activeLyricIndex,
            set: (v) => { activeLyricIndex = v; },
            configurable: true
        });
        Object.defineProperty(window, 'renderedLyricIndex', {
            get: () => renderedLyricIndex,
            set: (v) => { renderedLyricIndex = v; },
            configurable: true
        });
        Object.defineProperty(window, 'hasOfficialLyrics', {
            get: () => hasOfficialLyrics,
            set: (v) => { hasOfficialLyrics = v; },
            configurable: true
        });
        Object.defineProperty(window, 'lyrics', {
            get: () => lyrics,
            set: (v) => { lyrics = v; },
            configurable: true
        });

        function toggleDeviceInfo(e) {
            if (e && e.stopPropagation) e.stopPropagation();
            closeLyricMenu();
            const dropdown = document.getElementById('deviceDropdown');
            const pill = document.getElementById('devicePill');
            if (dropdown) {
                const isOpen = dropdown.classList.toggle('show');
                if (pill) {
                    if (isOpen) pill.classList.add('active');
                    else pill.classList.remove('active');
                }
            }
            document.body.classList.remove('idle');
            resetIdleTimer();
        }

        // 点击或触摸外部区域自动关闭设备详情浮层
        function handleOutsideDismiss(e) {
            const wrapper = document.getElementById('deviceCardWrapper');
            if (wrapper && !wrapper.contains(e.target)) {
                const dropdown = document.getElementById('deviceDropdown');
                const pill = document.getElementById('devicePill');
                if (dropdown && dropdown.classList.contains('show')) {
                    dropdown.classList.remove('show');
                    if (pill) pill.classList.remove('active');
                }
            }
        }
        document.addEventListener('pointerdown', handleOutsideDismiss);
        document.addEventListener('touchstart', handleOutsideDismiss, { passive: true });
        document.addEventListener('click', handleOutsideDismiss);

        try { localStorage.removeItem('canvas_perf_mode'); } catch (err) {}
        document.body.classList.remove('perf-mode');

        // 免 ADB 首次启动权限自检引导回调
        function openPermGuide(e) {
            if (e && e.stopPropagation) e.stopPropagation();
            if (window.AndroidApp && window.AndroidApp.openNotificationListenerSettings) {
                window.AndroidApp.openNotificationListenerSettings();
            }
        }
        window.openPermGuide = openPermGuide;

        let notificationDeferred = false;
        function dismissNotificationGuide() {
            notificationDeferred = true;
            document.getElementById('permGuideBtn').classList.remove('show');
        }
        window.onPermissionStatusChanged = function(granted) {
            const btn = document.getElementById('permGuideBtn');
            if (!btn) return;
            if (granted === false && !notificationDeferred) {
                if (!btn.classList.contains('show')) {
                    btn.classList.add('show');
                    document.getElementById('notificationSettings').focus();
                }
            } else {
                btn.classList.remove('show');
            }
        };

        // 接收来自 Java 原生层的蓝牙设备状态与音频协议数据
        window.onBluetoothStatusChanged = function(data) {
            console.log("Bluetooth status:", data);
            if (!data) return;

            const statusText = document.getElementById('deviceStatusText');
            const dropName = document.getElementById('dropdownDeviceName');
            const dropMac = document.getElementById('dropdownDeviceMac');
            const dropBadge = document.getElementById('dropdownStatusBadge');
            const dropDot = document.getElementById('dropdownStatusDot');

            if (data.connected) {
                if (statusText) statusText.textContent = '已连接';
                if (dropBadge) {
                    dropBadge.textContent = '已连接';
                    dropBadge.style.background = 'rgba(76, 217, 100, 0.2)';
                    dropBadge.style.color = '#4cd964';
                }
                if (dropDot) dropDot.style.background = '#4cd964';
                if (dropName) dropName.textContent = data.deviceName || '已连接设备';
                if (dropMac) dropMac.textContent = data.deviceAddress || '—';
            } else if (data.connectionState === 'off') {
                if (statusText) statusText.textContent = '蓝牙已关闭';
                if (dropBadge) {
                    dropBadge.textContent = '已关闭';
                    dropBadge.style.background = 'rgba(255, 59, 48, 0.2)';
                    dropBadge.style.color = '#ff3b30';
                }
                if (dropDot) dropDot.style.background = '#ff3b30';
                if (dropName) dropName.textContent = '蓝牙已关闭';
                if (dropMac) dropMac.textContent = '—';
            } else {
                // 蓝牙已配对但未连接，或处于等待连接状态
                if (statusText) statusText.textContent = '未连接';
                if (dropBadge) {
                    dropBadge.textContent = '未连接';
                    dropBadge.style.background = 'rgba(255, 204, 0, 0.2)';
                    dropBadge.style.color = '#ffcc00';
                }
                if (dropDot) dropDot.style.background = '#8e8e93';
                if (dropName) dropName.textContent = (data.deviceName && data.deviceName !== '—') ? data.deviceName + ' (未连接)' : '—';
                if (dropMac) dropMac.textContent = data.deviceAddress || '—';
            }

            // 更新左下角音频规格
            if (data.codec) {
                const el = document.getElementById('specCodec');
                if (el) el.textContent = data.codec;
            }
            if (data.sampleRate) {
                const el = document.getElementById('specRate');
                if (el) el.textContent = data.sampleRate;
            }
            const bitsEl = document.getElementById('specBits');
            const bitsSlash = document.getElementById('specBitsSlash');
            if (bitsEl) {
                if (data.bitDepth && data.bitDepth.trim() !== '') {
                    bitsEl.textContent = data.bitDepth;
                    bitsEl.style.display = 'inline';
                    if (bitsSlash) bitsSlash.style.display = 'inline';
                } else {
                    bitsEl.style.display = 'none';
                    if (bitsSlash) bitsSlash.style.display = 'none';
                }
            }
            if (data.channels) {
                const el = document.getElementById('specChannels');
                if (el) el.textContent = data.channels;
            }
        };

        function updateStatusTag(text, dotColor) {
            const tagText = document.getElementById('statusTagText');
            const dot = document.getElementById('sourceDot');
            if (tagText) tagText.textContent = text;
            if (dot && dotColor) {
                dot.style.backgroundColor = dotColor;
                dot.style.boxShadow = `0 0 10px ${dotColor}`;
            }
        }

        function isValidCoverUrl(url) {
            if (!url || typeof url !== 'string') return false;
            const u = url.trim().toLowerCase();
            if (!u) return false;
            if (u.includes('default_singer') || u.includes('default_album') || u.includes('default_cover') ||
                u.includes('default_avatar') || u.includes('share2014/default') || u.includes('singerimg.kugou.com') ||
                u.includes('nocover') || u.includes('no_cover')) {
                return false;
            }
            return true;
        }

        // 离屏微缩降采样画布缓存：将专辑图先降采样至 16x16 再双线性插值放大到 64x64，
        // 零 CORS 跨域报错（不调用 toDataURL/getImageData），每首歌仅计算一次（<0.5ms），播放期间 GPU 模糊着色器零消耗！
        let offscreenBlurSampleCanvas = null;
        function bakeAmbientCanvas(imgEl) {
            const canvas = document.getElementById('ambientCanvas');
            if (!canvas || !canvas.getContext || !imgEl || !imgEl.naturalWidth) return false;
            try {
                const ctx = canvas.getContext('2d');
                if (!ctx) return false;
                if (!offscreenBlurSampleCanvas) {
                    offscreenBlurSampleCanvas = document.createElement('canvas');
                    offscreenBlurSampleCanvas.width = 16;
                    offscreenBlurSampleCanvas.height = 16;
                }
                const sCtx = offscreenBlurSampleCanvas.getContext('2d');
                if (!sCtx) return false;
                sCtx.imageSmoothingEnabled = true;
                sCtx.clearRect(0, 0, 16, 16);
                sCtx.drawImage(imgEl, 0, 0, 16, 16);
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                ctx.imageSmoothingEnabled = true;
                if ('filter' in ctx) {
                    ctx.filter = 'blur(4px) saturate(1.75) brightness(0.32)';
                }
                ctx.drawImage(offscreenBlurSampleCanvas, 0, 0, canvas.width, canvas.height);
                if ('filter' in ctx) {
                    ctx.filter = 'none';
                }
                // 叠加柔和暗角遮罩，保证高饱和度封面下前景歌词依然清晰通透
                ctx.fillStyle = 'rgba(10, 12, 16, 0.42)';
                ctx.fillRect(0, 0, canvas.width, canvas.height);
                canvas.style.opacity = '1';
                return true;
            } catch (e) {
                return false;
            }
        }

        function setCoverImage(url) {
            currentCoverUrl = isValidCoverUrl(url) ? url.trim() : "";
            const img = document.getElementById('vinylCoverImg');
            const canvas = document.getElementById('ambientCanvas');
            if (currentCoverUrl) {
                img.onload = () => {
                    img.style.opacity = '1';
                    if (!bakeAmbientCanvas(img) && canvas) canvas.style.opacity = '0';
                };
                img.onerror = () => {
                    img.style.opacity = '0';
                    if (canvas) canvas.style.opacity = '0';
                };
                img.src = currentCoverUrl;
            } else {
                img.style.opacity = '0';
                img.src = '';
                if (canvas) canvas.style.opacity = '0';
            }
        }

        
        let toastTimeout = null;
        function showToastTip(msg) {
            const toast = document.getElementById('toastTip');
            if (!toast) return;
            toast.textContent = msg;
            toast.classList.add('show');
            if (toastTimeout) clearTimeout(toastTimeout);
            toastTimeout = setTimeout(() => {
                toast.classList.remove('show');
            }, 2500);
        }

        function formatTime(ms) {
            if (!ms || ms < 0 || isNaN(ms)) return "00:00";
            const totalSec = Math.floor(ms / 1000);
            const m = Math.floor(totalSec / 60);
            const s = totalSec % 60;
            return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
        }

