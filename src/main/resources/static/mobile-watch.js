// Mobile episode watch-tracking guards (visibility/screen-lock pause, fullscreen & orientation continuity, and keyboard-safe sticky bar).
(function () {
    'use strict';

    let wasPlayingBeforeHide = false;
    let fullscreenStallTicks = 0;
    let lastFullscreenCheckTime = -1;

    function ensureMobileWatchNoticeEl() {
        let notice = document.getElementById('mobile-watch-notice');
        if (notice) return notice;
        const strip = document.querySelector('.watch-progress-strip');
        if (!strip) return null;
        notice = document.createElement('div');
        notice.id = 'mobile-watch-notice';
        notice.className = 'mobile-watch-notice';
        notice.setAttribute('role', 'status');
        notice.setAttribute('aria-live', 'polite');
        strip.appendChild(notice);
        return notice;
    }

    function showMobileWatchNotice(message) {
        const notice = ensureMobileWatchNoticeEl();
        if (!notice) return;
        notice.textContent = message;
        notice.classList.add('visible');
    }

    function hideMobileWatchNotice() {
        const notice = document.getElementById('mobile-watch-notice');
        if (!notice) return;
        notice.textContent = '';
        notice.classList.remove('visible');
    }

    function isDocumentInFullscreen() {
        return Boolean(
            document.fullscreenElement ||
            document.webkitFullscreenElement ||
            document.mozFullScreenElement ||
            document.msFullscreenElement
        );
    }

    function handleVisibilityOrLockChange() {
        if (typeof currentEpisode === 'undefined' || !currentEpisode) return;

        if (document.hidden) {
            wasPlayingBeforeHide = Boolean(typeof isPlayerCurrentlyPlaying !== 'undefined' && isPlayerCurrentlyPlaying);
            if (typeof isPlayerCurrentlyPlaying !== 'undefined') {
                isPlayerCurrentlyPlaying = false;
            }
            if (typeof getSafeCurrentTime === 'function' && typeof lastPlayerTime !== 'undefined') {
                lastPlayerTime = getSafeCurrentTime();
            }
            if (typeof pendingWatchedDeltaSeconds !== 'undefined' && pendingWatchedDeltaSeconds > 0 && typeof flushWatchHeartbeat === 'function') {
                flushWatchHeartbeat({
                    playing: false,
                    videoEnded: false,
                    external: false,
                    videoDurationSeconds: typeof getSafeDurationSeconds === 'function' ? getSafeDurationSeconds() : null
                });
            }
            if (typeof pausePlayerSafely === 'function') {
                pausePlayerSafely();
            }
            if (typeof renderLiveWatchBar === 'function') {
                renderLiveWatchBar();
            }
            showMobileWatchNotice('📱 Keep this tab open and your screen unlocked while watching — background or locked-screen time is not counted.');
        } else {
            if (typeof getSafeCurrentTime === 'function' && typeof lastPlayerTime !== 'undefined') {
                lastPlayerTime = getSafeCurrentTime();
            }
            if (wasPlayingBeforeHide) {
                showMobileWatchNotice('📱 Playback was paused when this tab was hidden or your screen locked. Keep this tab open and press Play to continue earning watch time.');
                wasPlayingBeforeHide = false;
            }
            if (typeof renderLiveWatchBar === 'function') {
                renderLiveWatchBar();
            }
        }
    }

    function handleFullscreenOrOrientationChange() {
        if (typeof currentEpisode === 'undefined' || !currentEpisode) return;
        fullscreenStallTicks = 0;
        if (typeof getSafeCurrentTime === 'function' && typeof lastPlayerTime !== 'undefined') {
            const cur = getSafeCurrentTime();
            if (cur > 0) {
                lastPlayerTime = cur;
            }
        }
        if (typeof renderLiveWatchBar === 'function') {
            renderLiveWatchBar();
        }
    }

    function monitorFullscreenReadability() {
        if (typeof currentEpisode === 'undefined' || !currentEpisode || document.hidden) return;
        if (!isDocumentInFullscreen()) {
            fullscreenStallTicks = 0;
            lastFullscreenCheckTime = -1;
            return;
        }
        if (typeof ytReady === 'undefined' || !ytReady || typeof ytPlayer === 'undefined' || !ytPlayer) {
            showMobileWatchNotice('⚠️ Fullscreen player state cannot be read right now. Exit fullscreen and play inline so your watch time is counted.');
            return;
        }
        try {
            const state = typeof ytPlayer.getPlayerState === 'function' ? ytPlayer.getPlayerState() : null;
            const cur = typeof ytPlayer.getCurrentTime === 'function' ? Number(ytPlayer.getCurrentTime()) : NaN;
            if (state === null || Number.isNaN(cur)) {
                showMobileWatchNotice('⚠️ Fullscreen player state cannot be read on this device. Exit fullscreen and play inline so watch time is recorded.');
                return;
            }
            if (window.YT && window.YT.PlayerState && state === window.YT.PlayerState.PLAYING) {
                if (lastFullscreenCheckTime >= 0 && Math.abs(cur - lastFullscreenCheckTime) < 0.05) {
                    fullscreenStallTicks++;
                    if (fullscreenStallTicks >= 3) {
                        showMobileWatchNotice('⚠️ Fullscreen playback timer is not updating on this browser. Please exit fullscreen and play inline so no watch time is lost.');
                    }
                } else {
                    fullscreenStallTicks = 0;
                }
                lastFullscreenCheckTime = cur;
            }
        } catch (_) {
            showMobileWatchNotice('⚠️ Fullscreen player state cannot be read on this browser. Exit fullscreen and play inline so watch time is recorded.');
        }
    }

    function wireSoftKeyboardStickyGuard() {
        document.addEventListener('focusin', function (e) {
            const t = e.target;
            if (!t) return;
            const tag = (t.tagName || '').toUpperCase();
            if (tag === 'TEXTAREA' || (tag === 'INPUT' && t.type !== 'radio' && t.type !== 'checkbox')) {
                document.body.classList.add('keyboard-open');
            }
        });
        document.addEventListener('focusout', function () {
            setTimeout(function () {
                const active = document.activeElement;
                const tag = active ? (active.tagName || '').toUpperCase() : '';
                if (tag !== 'TEXTAREA' && !(tag === 'INPUT' && active.type !== 'radio' && active.type !== 'checkbox')) {
                    document.body.classList.remove('keyboard-open');
                }
            }, 120);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        ensureMobileWatchNoticeEl();
        wireSoftKeyboardStickyGuard();
        document.addEventListener('visibilitychange', handleVisibilityOrLockChange);
        window.addEventListener('pagehide', handleVisibilityOrLockChange);
        document.addEventListener('fullscreenchange', handleFullscreenOrOrientationChange);
        document.addEventListener('webkitfullscreenchange', handleFullscreenOrOrientationChange);
        window.addEventListener('orientationchange', handleFullscreenOrOrientationChange);
        window.addEventListener('resize', handleFullscreenOrOrientationChange);
        setInterval(monitorFullscreenReadability, 2000);
    });

    window.MobileWatchGuard = {
        showNotice: showMobileWatchNotice,
        hideNotice: hideMobileWatchNotice
    };
})();
