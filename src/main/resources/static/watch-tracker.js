// Real playback watch tracker, keepalive/retry heartbeat flushing, and role-aware progress bar labels.
(function () {
    'use strict';

    var lastTickWallMs = 0;
    var retryTimer = null;
    var retryAttempts = 0;

    function canSeeNumericClock() {
        if (typeof isBetaRoute !== 'undefined' && isBetaRoute) return true;
        if (typeof hasBetaAccess !== 'undefined' && hasBetaAccess) return true;
        if (typeof currentUser !== 'undefined' && currentUser) {
            if (currentUser.role === 'ROLE_ADMIN' || currentUser.betaTester) return true;
        }
        return false;
    }

    function clearRetryTimer() {
        if (retryTimer) {
            clearTimeout(retryTimer);
            retryTimer = null;
        }
    }

    function resetEpisodeState() {
        lastTickWallMs = 0;
        retryAttempts = 0;
        clearRetryTimer();
    }

    function renderWatchBar(statusOverride, episodePassedOverride) {
        if (typeof currentEpisode === 'undefined' || !currentEpisode) return;
        var status = statusOverride || currentEpisode.watchStatus || {};
        var episodePassed = episodePassedOverride !== null && episodePassedOverride !== undefined
            ? Boolean(episodePassedOverride)
            : Boolean(currentEpisode.passed);

        var required = Math.max(1, requiredWatchSeconds || status.requiredWatchSeconds || DEFAULT_REQUIRED_WATCH_SECONDS);
        var liveWatched = Math.min(required, (serverVerifiedSeconds || 0) + (pendingWatchedDeltaSeconds || 0));
        var pct = episodePassed ? 100 : Math.min(100, Math.round((liveWatched / required) * 100));

        var fill = document.getElementById('watch-progress-fill');
        var statusLabel = document.getElementById('watch-status-label');
        var subnote = document.getElementById('watch-live-subnote');
        if (fill) fill.style.width = pct + '%';
        if (subnote) {
            subnote.style.display = 'none';
            subnote.textContent = '';
        }
        if (!statusLabel) return;

        if (!canSeeNumericClock()) {
            if (episodePassed) {
                statusLabel.textContent = 'Episode passed';
            } else if (status.quizUnlocked || liveWatched >= required) {
                statusLabel.textContent = 'Quiz unlocked';
            } else if (isPlayerCurrentlyPlaying) {
                statusLabel.textContent = 'Watching';
            } else {
                statusLabel.textContent = 'Paused';
            }
            return;
        }

        if (episodePassed) {
            statusLabel.textContent = '✓ Episode Passed — Comprehension Quiz & Written Reflection Approved';
        } else if (status.quizUnlocked || liveWatched >= required) {
            statusLabel.textContent = '✓ Watch Target Reached (' + formatClock(liveWatched) + ' / ' + formatClock(required) + ') — 10-Question Quiz Unlocked Below!';
        } else {
            var prefix = isPlayerCurrentlyPlaying ? '▶️ Watching Live:' : '⏸️ Watch Progress (Paused):';
            statusLabel.textContent = prefix + ' ' + formatClock(liveWatched) + ' / ' + formatClock(required) + ' (' + pct + '%)';
        }
    }

    function getSafePlaybackRate() {
        try {
            if (ytReady && ytPlayer && typeof ytPlayer.getPlaybackRate === 'function') {
                var r = Number(ytPlayer.getPlaybackRate());
                if (r >= 0.25 && r <= 2.0) return r;
            }
        } catch (_) {}
        return 1.0;
    }

    function tickPlayback() {
        if (typeof currentEpisode === 'undefined' || !currentEpisode || !ytReady || !ytPlayer || typeof ytPlayer.getPlayerState !== 'function') {
            lastTickWallMs = 0;
            return;
        }
        if (!window.YT || !window.YT.PlayerState) return;

        var state;
        var curTime;
        try {
            state = ytPlayer.getPlayerState();
            curTime = Number(ytPlayer.getCurrentTime() || 0);
        } catch (_) {
            lastTickWallMs = 0;
            return;
        }

        if (document.hidden) {
            isPlayerCurrentlyPlaying = false;
            lastPlayerTime = curTime;
            lastTickWallMs = 0;
            return;
        }

        var nowMs = Date.now();
        if (state === window.YT.PlayerState.PLAYING) {
            isPlayerCurrentlyPlaying = true;
            if (lastTickWallMs <= 0) {
                lastTickWallMs = nowMs;
                lastPlayerTime = curTime;
                renderWatchBar();
                return;
            }
            var elapsedWallSeconds = Math.max(0, (nowMs - lastTickWallMs) / 1000);
            var delta = curTime - lastPlayerTime;
            var rate = getSafePlaybackRate();
            lastTickWallMs = nowMs;
            lastPlayerTime = curTime;

            // Credit real played time only; large forward jumps are seeking and earn nothing.
            if (delta > 0.05 && delta <= elapsedWallSeconds * rate + 1.5) {
                pendingWatchedDeltaSeconds += delta;
                renderWatchBar();

                var targetRemaining = Math.max(0, requiredWatchSeconds - serverVerifiedSeconds);
                if (!heartbeatInFlight && (pendingWatchedDeltaSeconds >= 5 || (targetRemaining > 0 && pendingWatchedDeltaSeconds >= targetRemaining))) {
                    flushHeartbeat({
                        playing: true,
                        videoEnded: false,
                        external: false,
                        videoDurationSeconds: getSafeDurationSeconds()
                    });
                }
            }
        } else {
            if (isPlayerCurrentlyPlaying) {
                isPlayerCurrentlyPlaying = false;
                renderWatchBar();
            }
            lastTickWallMs = 0;
            lastPlayerTime = curTime;
        }
    }

    function scheduleRetry() {
        if (retryTimer || typeof currentEpisode === 'undefined' || !currentEpisode) return;
        retryAttempts = Math.min(retryAttempts + 1, 5);
        var delayMs = Math.min(30000, 3000 * retryAttempts);
        retryTimer = setTimeout(function () {
            retryTimer = null;
            if (typeof currentEpisode !== 'undefined' && currentEpisode && !document.hidden && pendingWatchedDeltaSeconds >= 1) {
                flushHeartbeat({ playing: isPlayerCurrentlyPlaying, videoEnded: false, external: false });
            }
        }, delayMs);
    }

    async function flushHeartbeat(opts) {
        opts = opts || {};
        if (typeof currentEpisode === 'undefined' || !currentEpisode) return;

        var wholeDelta = Math.floor(pendingWatchedDeltaSeconds || 0);
        pendingWatchedDeltaSeconds = Math.max(0, (pendingWatchedDeltaSeconds || 0) - wholeDelta);
        var posSec = Math.floor(typeof getSafeCurrentTime === 'function' ? getSafeCurrentTime() : 0);
        var useKeepalive = Boolean(opts.keepalive || document.hidden);

        var url = '/api/student/episodes/' + currentEpisode.id + '/heartbeat' + betaQuerySuffix('?');
        var jsonBody = JSON.stringify({
            watchMode: DEFAULT_WATCH_MODE,
            external: false,
            videoEnded: Boolean(opts.videoEnded),
            watchedDeltaSeconds: wholeDelta,
            currentPositionSeconds: posSec,
            playing: Boolean(opts.playing)
        });

        if (useKeepalive) {
            try {
                fetch(url, {
                    method: 'POST',
                    credentials: 'same-origin',
                    keepalive: true,
                    headers: apiHeaders(true),
                    body: jsonBody
                }).catch(function () {
                    if (wholeDelta > 0) {
                        pendingWatchedDeltaSeconds += wholeDelta;
                        scheduleRetry();
                    }
                });
            } catch (_) {
                if (navigator && typeof navigator.sendBeacon === 'function') {
                    var sent = navigator.sendBeacon(url, new Blob([jsonBody], { type: 'application/json' }));
                    if (!sent && wholeDelta > 0) pendingWatchedDeltaSeconds += wholeDelta;
                } else if (wholeDelta > 0) {
                    pendingWatchedDeltaSeconds += wholeDelta;
                }
            }
            return;
        }

        heartbeatInFlight = true;
        try {
            var res = await fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: apiHeaders(true),
                body: jsonBody
            });
            if (res.ok) {
                retryAttempts = 0;
                clearRetryTimer();
                var wasUnlocked = Boolean(currentEpisode.watchStatus && currentEpisode.watchStatus.quizUnlocked);
                var wasStarted = Boolean(currentEpisode.watchStatus && currentEpisode.watchStatus.watchStarted);
                var status = await res.json();
                currentEpisode.watchStatus = status;
                applyWatchStatus(status, currentEpisode.passed);

                if ((!wasStarted && status.watchStarted) || (!wasUnlocked && status.quizUnlocked)) {
                    fetchEpisodes();
                }
                if (!wasUnlocked && status.quizUnlocked && !currentEpisode.passed && typeof confetti === 'function') {
                    confetti({ particleCount: 55, spread: 60, origin: { y: 0.7 } });
                }
            } else if (wholeDelta > 0) {
                pendingWatchedDeltaSeconds += wholeDelta;
                scheduleRetry();
            }
        } catch (_) {
            if (wholeDelta > 0) {
                pendingWatchedDeltaSeconds += wholeDelta;
                scheduleRetry();
            }
        } finally {
            heartbeatInFlight = false;
        }
    }

    function flushOnLeave() {
        lastTickWallMs = 0;
        if (typeof currentEpisode === 'undefined' || !currentEpisode) return;
        var hasDelta = Math.floor(pendingWatchedDeltaSeconds || 0) > 0;
        var posSec = Math.floor(typeof getSafeCurrentTime === 'function' ? getSafeCurrentTime() : 0);
        if (hasDelta || posSec > 0) {
            flushHeartbeat({ playing: false, videoEnded: false, external: false, keepalive: true });
        }
    }

    document.addEventListener('visibilitychange', function () {
        if (document.hidden) flushOnLeave();
    });
    window.addEventListener('pagehide', flushOnLeave);

    window.WatchTracker = {
        resetEpisodeState: resetEpisodeState,
        renderWatchBar: renderWatchBar,
        tickPlayback: tickPlayback,
        flushHeartbeat: flushHeartbeat
    };
})();
