        // ===== 右上角返回桌面叉叉：短按返回桌面，长按3秒重启App =====
        let closePressTimer = null;
        let closePressStartTime = 0;
        let isRestartTriggered = false;
        const closeBtn = document.getElementById('closeBtn');

        function startClosePress(e) {
            if (e) {
                if (e.cancelable && e.type === 'touchstart') e.preventDefault();
                e.stopPropagation();
            }
            if (closePressTimer) return; // 避免 touch 与 pointer 重复触发
            isRestartTriggered = false;
            closePressStartTime = performance.now();
            if (closeBtn) closeBtn.classList.add('pressing');

            clearTimeout(closePressTimer);
            closePressTimer = setTimeout(function() {
                isRestartTriggered = true;
                if (closeBtn) closeBtn.classList.remove('pressing');
                const toast = document.getElementById('toastTip');
                if (toast) toast.classList.add('show');
                if (window.AndroidApp && window.AndroidApp.restartApp) {
                    window.AndroidApp.restartApp();
                }
            }, 3000);
        }

        function cancelClosePress(e) {
            if (e) e.stopPropagation();
            if (!closePressTimer && closePressStartTime === 0) return;
            clearTimeout(closePressTimer);
            closePressTimer = null;
            if (closeBtn) closeBtn.classList.remove('pressing');

            if (isRestartTriggered) return;

            const elapsed = performance.now() - closePressStartTime;
            if (closePressStartTime > 0 && elapsed < 2500) {
                triggerExit(e);
            }
            closePressStartTime = 0;
        }

        if (closeBtn) {
            closeBtn.addEventListener('pointerdown', startClosePress);
            closeBtn.addEventListener('pointerup', cancelClosePress);
            closeBtn.addEventListener('pointercancel', cancelClosePress);
            closeBtn.addEventListener('pointerleave', cancelClosePress);
            closeBtn.addEventListener('touchstart', startClosePress, { passive: false });
            closeBtn.addEventListener('touchend', cancelClosePress);
            closeBtn.addEventListener('touchcancel', cancelClosePress);
            closeBtn.addEventListener('mousedown', startClosePress);
            closeBtn.addEventListener('mouseup', cancelClosePress);
        }

        // ===== 二级菜单全局辅助函数 =====
        function isAnySecondaryMenuOpen() {
            const dropdown = document.getElementById('deviceDropdown');
            const lyricMenu = document.getElementById('lyricSourceMenu');
            return !!((dropdown && dropdown.classList.contains('show')) || (lyricMenu && lyricMenu.classList.contains('show')));
        }
        window.isAnySecondaryMenuOpen = isAnySecondaryMenuOpen;

        function closeAllSecondaryMenus() {
            const dropdown = document.getElementById('deviceDropdown');
            const pill = document.getElementById('devicePill');
            if (dropdown) dropdown.classList.remove('show');
            if (pill) pill.classList.remove('active');

            const lyricMenu = document.getElementById('lyricSourceMenu');
            if (lyricMenu) lyricMenu.classList.remove('show');
            document.body.classList.remove('lyric-menu-open');
            clearRemoteMenuFocus();
        }
        window.closeAllSecondaryMenus = closeAllSecondaryMenus;
        window.toggleLyricMenu = toggleLyricMenu;

        // ===== 电视遥控器 (D-Pad 方向键 / 确认键 / 菜单键) 与触控双栖导航支持 =====
        const LYRIC_MENU_ORDER = ['auto', 'netease', 'qq', 'kugou', 'lrclib', 'bluetooth', 'visualizer', 'translation'];
        let remoteMenuFocusIdx = -1;

        function clearRemoteMenuFocus() {
            remoteMenuFocusIdx = -1;
            const items = document.querySelectorAll('.source-menu-item.remote-focus');
            for (let i = 0; i < items.length; i++) {
                items[i].classList.remove('remote-focus');
            }
        }

        function updateRemoteMenuFocus(idx) {
            const menu = document.getElementById('lyricSourceMenu');
            if (!menu || !menu.classList.contains('show')) return;
            const total = LYRIC_MENU_ORDER.length;
            remoteMenuFocusIdx = ((idx % total) + total) % total;
            const targetId = 'menuItem-' + LYRIC_MENU_ORDER[remoteMenuFocusIdx];
            const items = menu.querySelectorAll('.source-menu-item');
            for (let i = 0; i < items.length; i++) {
                if (items[i].id === targetId) {
                    items[i].classList.add('remote-focus');
                } else {
                    items[i].classList.remove('remote-focus');
                }
            }
        }

        function stepLyricPreviewByRemote(deltaLines) {
            if (lyricMode !== 'official' || !hasOfficialLyrics || !lyrics || lyrics.length === 0) return;
            const stream = document.getElementById('lyricsStream');
            if (!stream) return;
            isManualScrolling = true;
            stream.style.opacity = '1';
            const baseIdx = (renderedLyricIndex >= 0) ? renderedLyricIndex : Math.max(0, activeLyricIndex);
            const nextIdx = Math.max(0, Math.min(lyrics.length - 1, baseIdx + deltaLines));
            updateLyricHighlight(nextIdx);
            scrollToLyricIndex(nextIdx, true);
            clearTimeout(manualScrollResumeTimer);
            manualScrollResumeTimer = setTimeout(() => {
                fadeTransitionToCurrentLyric();
            }, 3000);
        }

        window.handleRemoteKey = function(keyCode) {
            // 若权限引导弹窗正处于居中展示状态，电视遥控器按确认键 (OK/Enter) 直接跳转系统权限设置页
            const permBtn = document.getElementById('permGuideBtn');
            if (permBtn && permBtn.classList.contains('show')) {
                if (keyCode === 23 || keyCode === 66) {
                    if (document.activeElement === document.getElementById('notificationLater')) dismissNotificationGuide();
                    else openPermGuide();
                } else if ([19, 20, 21, 22].includes(keyCode)) {
                    const next = document.activeElement.id === 'notificationLater' ? 'notificationSettings' : 'notificationLater';
                    document.getElementById(next).focus();
                }
                return true;
            }

            // 任何遥控器按键先唤醒 UI 并重置沉浸计时
            if (document.body.classList.contains('idle')) {
                setIdleState(false);
                // 沉浸状态下按确认键仅唤醒四角控件，避免盲按意外暂停音乐
                if (keyCode === 23 || keyCode === 66) return true;
            } else {
                resetIdleTimer();
            }

            const lyricMenu = document.getElementById('lyricSourceMenu');
            const isLyricMenuOpen = !!(lyricMenu && lyricMenu.classList.contains('show'));

            switch (keyCode) {
                case 19: // KEYCODE_DPAD_UP
                    if (isLyricMenuOpen) {
                        updateRemoteMenuFocus(remoteMenuFocusIdx <= 0 ? LYRIC_MENU_ORDER.length - 1 : remoteMenuFocusIdx - 1);
                    } else {
                        stepLyricPreviewByRemote(-1);
                    }
                    return true;
                case 20: // KEYCODE_DPAD_DOWN
                    if (isLyricMenuOpen) {
                        updateRemoteMenuFocus(remoteMenuFocusIdx < 0 ? 0 : remoteMenuFocusIdx + 1);
                    } else {
                        stepLyricPreviewByRemote(1);
                    }
                    return true;
                case 21: // KEYCODE_DPAD_LEFT
                    if (isLyricMenuOpen) {
                        adjustLyricFontSize(-1);
                    } else {
                        triggerPrev();
                        showToastTip('⏮ 上一曲');
                    }
                    return true;
                case 22: // KEYCODE_DPAD_RIGHT
                    if (isLyricMenuOpen) {
                        adjustLyricFontSize(1);
                    } else {
                        triggerNext();
                        showToastTip('⏭ 下一曲');
                    }
                    return true;
                case 23: // KEYCODE_DPAD_CENTER
                case 66: // KEYCODE_ENTER
                    if (isLyricMenuOpen) {
                        const src = LYRIC_MENU_ORDER[remoteMenuFocusIdx >= 0 ? remoteMenuFocusIdx : 0];
                        if (src === 'translation') {
                            toggleTranslation();
                        } else {
                            selectLyricSource(src);
                        }
                    } else {
                        triggerPlayPause();
                    }
                    return true;
                case 82: // KEYCODE_MENU
                    toggleLyricMenu();
                    if (lyricMenu && lyricMenu.classList.contains('show')) {
                        updateRemoteMenuFocus(0);
                    }
                    return true;
                default:
                    return false;
            }
        };

        // 兼容标准 Web 键盘事件（外接键盘 / 蓝牙遥控器 HID 映射）与遥控器长按 OK 键
        let enterKeyDownTimer = null;
        let enterLongPressTriggered = false;

        document.addEventListener('keydown', function(e) {
            if (!e) return;
            const keyMap = {
                'ArrowUp': 19,
                'ArrowDown': 20,
                'ArrowLeft': 21,
                'ArrowRight': 22,
                'ContextMenu': 82,
                'm': 82,
                'M': 82
            };
            if (e.key === 'Escape' || e.key === 'Backspace') {
                if (isAnySecondaryMenuOpen()) {
                    closeAllSecondaryMenus();
                    e.preventDefault();
                }
                return;
            }
            if (e.key === 'Enter' || e.key === ' ') {
                if (!e.repeat) {
                    enterLongPressTriggered = false;
                    clearTimeout(enterKeyDownTimer);
                    enterKeyDownTimer = setTimeout(function() {
                        enterLongPressTriggered = true;
                        window.handleRemoteKey(82);
                    }, 500);
                }
                return;
            }
            if (keyMap[e.key] !== undefined) {
                e.preventDefault();
                window.handleRemoteKey(keyMap[e.key]);
            }
        });

        document.addEventListener('keyup', function(e) {
            if (e.key === 'Enter' || e.key === ' ') {
                clearTimeout(enterKeyDownTimer);
                if (enterLongPressTriggered) {
                    enterLongPressTriggered = false;
                    e.preventDefault();
                    return;
                }
                e.preventDefault();
                window.handleRemoteKey(23);
            }
        });

        // ===== 沉浸模式：6秒（二级菜单展开时15秒）无操作自动渐隐，支持单击屏幕任意空白处手动隐藏/唤醒 =====
        const IDLE_TIMEOUT_MS = 6000;
        const MENU_IDLE_TIMEOUT_MS = 15000;
        let idleTimer = null;

        function setIdleState(isIdle) {
            if (isIdle) {
                closeAllSecondaryMenus();
                document.body.classList.add('idle');
                clearTimeout(idleTimer);
                if (window.AndroidApp && window.AndroidApp.onIdleStateChanged) {
                    window.AndroidApp.onIdleStateChanged(true);
                }
            } else {
                document.body.classList.remove('idle');
                if (window.AndroidApp && window.AndroidApp.onIdleStateChanged) {
                    window.AndroidApp.onIdleStateChanged(false);
                }
                if (window.AndroidApp && window.AndroidApp.onUserInteraction) {
                    window.AndroidApp.onUserInteraction();
                }
                resetIdleTimer();
            }
        }

        function resetIdleTimer() {
            clearTimeout(idleTimer);
            const timeout = isAnySecondaryMenuOpen() ? MENU_IDLE_TIMEOUT_MS : IDLE_TIMEOUT_MS;
            idleTimer = setTimeout(function() {
                setIdleState(true);
            }, timeout);
        }

        // 单击屏幕手动激活淡出 / 唤醒
        let tapDownX = 0;
        let tapDownY = 0;
        let tapDownTime = 0;
        let isTapCandidate = false;
        let lastTapHandledTime = 0;

        function handleTapStart(x, y) {
            tapDownX = x;
            tapDownY = y;
            tapDownTime = performance.now();
            isTapCandidate = true;
        }

        function handleTapEnd(x, y, target) {
            const now = performance.now();
            if (now - lastTapHandledTime < 400) {
                return; // 过滤 400ms 内重复合成事件（pointerup, touchend）
            }
            lastTapHandledTime = now;

            const elapsed = now - tapDownTime;
            const dist = Math.hypot(x - tapDownX, y - tapDownY);

            // 检查当前是否有二级菜单（歌词源菜单或输出设备下拉框）展开
            const isMenuOpen = isAnySecondaryMenuOpen();

            // 判断是否真正命中了可交互的控件元素（严禁将整行 top-bar / bottom-bar 或四周大范围作为控件）
            let isInteractive = false;
            if (target && target.closest) {
                if (isMenuOpen) {
                    isInteractive = !!target.closest('button, .btn-ctrl, .vol-btn, .volume-widget, .source-tag, .device-pill, .device-dropdown, .lyric-source-menu, #closeBtn, .font-btn, .lyric-font-widget');
                } else {
                    isInteractive = !!target.closest('button, .btn-ctrl, .vol-btn, .volume-widget, .source-tag, .device-pill, .device-dropdown, #closeBtn');
                }
            }

            if (isTapCandidate && elapsed < 500 && dist < 20) {
                isTapCandidate = false;
                if (!isInteractive) {
                    // 点击空白区域：原汁原味统一的全局“点击唤醒，再点击就隐藏”触控逻辑
                    if (document.body.classList.contains('idle')) {
                        // 1. 休眠沉浸状态下：点击任何空白/非按钮区域，立即唤醒控件！
                        setIdleState(false);
                    } else if (isMenuOpen) {
                        // 2. 二级菜单展开时：点击任何空白区域，仅收起二级菜单，保持常驻控件唤醒
                        closeAllSecondaryMenus();
                        resetIdleTimer();
                    } else {
                        // 3. 常规唤醒状态下：再点击空白区域，立即隐藏界面进入沉浸！
                        setIdleState(true);
                    }
                    return;
                }
            }

            // 点击或交互了具体控件
            if (document.body.classList.contains('idle')) {
                setIdleState(false);
            } else {
                resetIdleTimer();
            }
        }

        document.addEventListener('pointerdown', function(e) {
            handleTapStart(e.clientX, e.clientY);
        }, { passive: true });

        document.addEventListener('pointermove', function(e) {
            if (isTapCandidate) {
                const dist = Math.hypot(e.clientX - tapDownX, e.clientY - tapDownY);
                if (dist > 20) {
                    isTapCandidate = false;
                    if (document.body.classList.contains('idle')) {
                        setIdleState(false);
                    } else {
                        resetIdleTimer();
                    }
                }
            }
        }, { passive: true });

        document.addEventListener('pointerup', function(e) {
            handleTapEnd(e.clientX, e.clientY, e.target);
        }, { passive: true });

        // Fallback for touch devices if PointerEvent doesn't fire
        document.addEventListener('touchstart', function(e) {
            if (e.touches && e.touches[0]) {
                handleTapStart(e.touches[0].clientX, e.touches[0].clientY);
            }
        }, { passive: true });

        document.addEventListener('touchend', function(e) {
            if (e.changedTouches && e.changedTouches[0]) {
                handleTapEnd(e.changedTouches[0].clientX, e.changedTouches[0].clientY, e.target);
            }
        }, { passive: true });

        // 点击屏幕空白处保底事件（兼容各种输入设备与电视遥控器）
        document.addEventListener('click', function(e) {
            const now = performance.now();
            if (now - lastTapHandledTime < 400) return; // 过滤重复合成点击
            lastTapHandledTime = now;

            const isMenuOpen = isAnySecondaryMenuOpen();
            let isInteractive = false;
            if (e.target && e.target.closest) {
                if (isMenuOpen) {
                    isInteractive = !!e.target.closest('button, .btn-ctrl, .vol-btn, .volume-widget, .source-tag, .device-pill, .device-dropdown, .lyric-source-menu, #closeBtn, .font-btn, .lyric-font-widget');
                } else {
                    isInteractive = !!e.target.closest('button, .btn-ctrl, .vol-btn, .volume-widget, .source-tag, .device-pill, .device-dropdown, #closeBtn');
                }
            }

            if (!isInteractive) {
                if (document.body.classList.contains('idle')) {
                    setIdleState(false);
                } else if (isMenuOpen) {
                    closeAllSecondaryMenus();
                    resetIdleTimer();
                } else {
                    setIdleState(true);
                }
            }
        });

        resetIdleTimer();

        // ===== 常驻垂直音量控制组件交互与系统联动 =====
        let currentVolumePct = 50;
        let volForceTimeout = null;

        function updateVolumeDisplay(pct) {
            currentVolumePct = Math.max(0, Math.min(100, Math.round(pct)));
            const fill = document.getElementById('volFill');
            const text = document.getElementById('volPctText');
            if (fill) fill.style.height = currentVolumePct + '%';
            if (text) text.textContent = currentVolumePct + '%';
        }

        function handleVolumeStep(delta, e) {
            if (e) {
                if (e.preventDefault) e.preventDefault();
                if (e.stopPropagation) e.stopPropagation();
            }
            resetIdleTimer();
            if (window.AndroidApp && window.AndroidApp.changeVolumeBy) {
                const res = window.AndroidApp.changeVolumeBy(delta);
                updateVolumeDisplay(res);
            } else if (window.AndroidApp && window.AndroidApp.setVolumePercent) {
                const target = Math.max(0, Math.min(100, currentVolumePct + delta));
                const res = window.AndroidApp.setVolumePercent(target);
                updateVolumeDisplay(res);
            } else {
                updateVolumeDisplay(currentVolumePct + delta);
            }
        }

        // 手机端物理按键/蓝牙调整音量时的系统级事件同步：仅淡入音量条，四个角落的控件保持隐藏
        window.onSystemVolumeChanged = function(newPct) {
            updateVolumeDisplay(newPct);
            const widget = document.getElementById('volumeWidget');
            if (widget) {
                widget.classList.add('force-show');
                clearTimeout(volForceTimeout);
                volForceTimeout = setTimeout(function() {
                    widget.classList.remove('force-show');
                }, 3000);
            }
        };

        // 页面初始化时拉取当前系统音量
        if (window.AndroidApp && window.AndroidApp.getVolume) {
            try {
                updateVolumeDisplay(window.AndroidApp.getVolume());
            } catch (err) {}
        }
