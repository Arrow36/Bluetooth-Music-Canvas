        
        // 真实音频频谱与智能节拍律动驱动引擎
        let lastFftTime = 0;
        let realSpectrum = [0.08, 0.08, 0.08, 0.08, 0.08];
        const waveBars = [];
        let eqBarsAtBaseline = true;
        let lastSyncedSpectrumVisible = null;
        Object.defineProperty(window, 'lastSyncedSpectrumVisible', {
            get: () => lastSyncedSpectrumVisible,
            set: (v) => { lastSyncedSpectrumVisible = v; },
            configurable: true
        });

        let musicPermissionVisible = false;
        let musicPermissionTimer;
        function syncSpectrumVisibility() {
            const emptyBox = document.getElementById('emptyLyrics');
            const largeViz = document.getElementById('largeVisualizer');
            const emptyVisible = !!(emptyBox && emptyBox.style.display !== 'none' && (!hasOfficialLyrics || emptyBox.style.opacity !== '0'));
            const largeVisible = !!(largeViz && largeViz.style.display === 'flex');
            const needSpectrum = emptyVisible || largeVisible;
            if (needSpectrum !== lastSyncedSpectrumVisible) {
                if (window.AndroidApp && window.AndroidApp.setSpectrumVisible) {
                    try {
                        window.AndroidApp.setSpectrumVisible(needSpectrum);
                        lastSyncedSpectrumVisible = needSpectrum;
                    } catch (err) {}
                }
            }
            const wantsMusicPermission = largeVisible && !!currentTrack.title;
            if (wantsMusicPermission !== musicPermissionVisible) {
                musicPermissionVisible = wantsMusicPermission;
                clearTimeout(musicPermissionTimer);
                if (window.AndroidApp && window.AndroidApp.updateMusicPermission) {
                    if (wantsMusicPermission) {
                        musicPermissionTimer = setTimeout(() => window.AndroidApp.updateMusicPermission(true, false), 900);
                    } else {
                        window.AndroidApp.updateMusicPermission(false, false);
                    }
                }
            }
            return { emptyVisible, largeVisible, needSpectrum };
        }
        window.syncSpectrumVisibility = syncSpectrumVisibility;

        function getEqualizerBars() {
            if (waveBars.length < 5) {
                const eq = document.getElementById('equalizerWave');
                if (eq) {
                    const bars = eq.querySelectorAll('.wave-bar');
                    waveBars.length = 0;
                    bars.forEach(b => waveBars.push(b));
                }
            }
            return waveBars;
        }

        // Android 原生 Visualizer 实时回调：接收 9 个真实物理频段 (0.08 ~ 1.0)
        window.onAudioSpectrum = function(b0, b1, b2, b3, b4, b5, b6, b7, b8) {
            lastFftTime = performance.now();
            const isSilenceCall = !isPlaying || (!b0 && !b1 && !b2 && !b3 && !b4);
            const s9 = isPlaying ? [
                Math.max(0.08, Math.min(1.0, b0 || 0)),
                Math.max(0.08, Math.min(1.0, b1 || 0)),
                Math.max(0.08, Math.min(1.0, b2 || 0)),
                Math.max(0.08, Math.min(1.0, b3 || 0)),
                Math.max(0.08, Math.min(1.0, b4 || 0)),
                Math.max(0.08, Math.min(1.0, b5 || b3 || 0)),
                Math.max(0.08, Math.min(1.0, b6 || b4 || 0)),
                Math.max(0.08, Math.min(1.0, b7 || b4 || 0)),
                Math.max(0.08, Math.min(1.0, b8 || b4 || 0))
            ] : [0.08, 0.08, 0.08, 0.08, 0.08, 0.08, 0.08, 0.08, 0.08];
            realSpectrum = [s9[0], s9[2], s9[4], s9[6], s9[8]];

            const emptyBox = document.getElementById('emptyLyrics');
            const largeViz = document.getElementById('largeVisualizer');
            const emptyVisible = !emptyBox || emptyBox.style.display !== 'none';
            const largeVisible = !!(largeViz && largeViz.style.display === 'flex');

            // 仅在检索占位频谱可见或静音归零时更新 5 柱迷你均衡器 DOM，杜绝隐藏态 25Hz 无效重绘
            if (emptyVisible || isSilenceCall) {
                const bars = getEqualizerBars();
                if (bars.length >= 5) {
                    for (let i = 0; i < 5; i++) {
                        bars[i].style.height = (s9[i * 2] * 100) + '%';
                    }
                    eqBarsAtBaseline = isSilenceCall;
                }
            }

            // 仅在大号律动频谱可见或静音归零时更新 9 柱大频谱 DOM
            if (largeVisible || isSilenceCall) {
                const lbars = getLargeWaveBars();
                if (lbars.length >= 9) {
                    for (let i = 0; i < 9; i++) {
                        lbars[i].style.height = (s9[i] * 100) + '%';
                    }
                }
            }
        };

