// Turathtona (تراثُنا) Student & Beta Sandbox Application Logic — Hardened & Anti-Cheat Verified

// Purge any legacy localStorage entries from older versions
['aq_auth_token', 'aq_role', 'aq_username', 'aq_fullname'].forEach((k) => {
    try { localStorage.removeItem(k); } catch (_) {}
});

const PLAYLIST_ID = 'PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB';
const STANDARD_WATCH_MODE = 'ARABIC_SUB';
const DEFAULT_REQUIRED_WATCH_SECONDS = 1800; // 30 minutes cap
const isBetaRoute = window.location.pathname === '/beta' || window.location.pathname.startsWith('/beta/');

let currentUser = null;
let hasBetaAccess = false;
let betaUnlockAllEpisodes = false;

let currentEpisode = null;
let episodes = [];
let currentQuiz = null;

// YouTube IFrame API & Live Playback State
let ytPlayer = null;
let ytReady = false;
let ytApiRequested = false;
let pendingPlayerBuild = null;
let currentLoadedVideoId = null;

let serverVerifiedSeconds = 0;
let requiredWatchSeconds = 1800;
let pendingWatchedDeltaSeconds = 0;
let lastPlayerTime = 0;
let isPlayerCurrentlyPlaying = false;
let heartbeatInFlight = false;

let playbackTickTimer = null;
let cooldownInterval = null;
let cooldownRemainingSecs = 0;
let lockToastTimer = null;

function apiHeaders(includeJson = false) {
    const h = { 'X-Requested-With': 'XMLHttpRequest' };
    if (includeJson) {
        h['Content-Type'] = 'application/json';
    }
    return h;
}

function betaQuerySuffix(prefix = '?') {
    if (isBetaRoute && hasBetaAccess && betaUnlockAllEpisodes) {
        return `${prefix}betaUnlock=true`;
    }
    return '';
}

document.addEventListener('DOMContentLoaded', () => {
    startLocalPlaybackMonitor();
    initApp();
});

async function initApp() {
    try {
        const meRes = await fetch('/api/auth/me', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (!meRes.ok) {
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
            return;
        }
        currentUser = await meRes.json();
        hasBetaAccess = Boolean(currentUser.betaTester || currentUser.role === 'ROLE_ADMIN');

        if (isBetaRoute && !hasBetaAccess) {
            window.location.replace('/');
            return;
        }

        const displayEl = document.getElementById('student-name-display');
        if (displayEl) {
            const prefix = isBetaRoute ? '🧪 ' : '🎓 ';
            displayEl.textContent = prefix + (currentUser.fullName || currentUser.username);
        }

        if (isBetaRoute && hasBetaAccess) {
            document.title = '🧪 Beta Sandbox • Turathtona';
            const betaBar = document.getElementById('beta-sandbox-bar');
            if (betaBar) betaBar.style.display = 'flex';
        } else if (!isBetaRoute && hasBetaAccess) {
            const shortcut = document.getElementById('btn-enter-beta-shortcut');
            if (shortcut) shortcut.style.display = 'inline-flex';
        }

        await Promise.all([fetchEarnings(), fetchEpisodes()]);
    } catch (e) {
        window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
    }
}

async function handleStudentLogout() {
    try {
        await fetch('/api/auth/logout', {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
    } catch (_) {}
    window.location.href = '/login';
}

async function fetchEarnings() {
    try {
        const res = await fetch('/api/student/earnings', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const data = await res.json();
            document.getElementById('val-earned').textContent = '$' + Number(data.totalEarned).toFixed(2);
            document.getElementById('val-paid').textContent = '$' + Number(data.totalPaid).toFixed(2);
            document.getElementById('val-owed').textContent = '$' + Number(data.balanceOwed).toFixed(2);

            const total = data.totalEpisodesCount > 0 ? data.totalEpisodesCount : 1;
            const percent = Math.min(100, Math.round((data.passedEpisodesCount / total) * 100));

            document.getElementById('badge-course-count').textContent = `${data.passedEpisodesCount}/${data.totalEpisodesCount}`;
            document.getElementById('progress-percent-label').textContent = `${percent}%`;
            document.getElementById('progress-bar-fill').style.width = `${percent}%`;
        } else if (res.status === 401) {
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
        }
    } catch (e) {
        console.warn('Earnings fetch failed:', e);
    }
}

async function fetchEpisodes() {
    try {
        const res = await fetch('/api/student/episodes' + betaQuerySuffix('?'), {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            episodes = await res.json();
            if (currentEpisode) {
                const refreshed = episodes.find((e) => e.id === currentEpisode.id);
                if (refreshed && !refreshed.locked) {
                    currentEpisode = refreshed;
                    applyWatchStatus(refreshed.watchStatus, refreshed.passed);
                } else if (refreshed && refreshed.locked) {
                    const fallbackUnlocked = episodes.find((e) => !e.locked) || episodes[0];
                    if (fallbackUnlocked) selectEpisode(fallbackUnlocked);
                }
            }
            renderEpisodeList();
            if (episodes.length > 0 && !currentEpisode) {
                const firstActive = episodes.find((e) => !e.passed && !e.locked)
                    || episodes.find((e) => !e.locked)
                    || episodes[0];
                selectEpisode(firstActive);
            }
        } else if (res.status === 401) {
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
        }
    } catch (e) {
        console.warn('Episodes fetch failed:', e);
    }
}

function showLockedEpisodeToast(ep) {
    const toast = document.getElementById('episode-lock-toast');
    if (!toast) return;
    const prereq = ep.unlockAfterEpisodeNumber || (ep.episodeNumber - 1);
    toast.textContent = `🔒 Episode ${ep.episodeNumber} is locked! Pass Episode ${prereq} first to unlock the next video in order.`;
    toast.style.display = 'block';
    toast.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    if (lockToastTimer) clearTimeout(lockToastTimer);
    lockToastTimer = setTimeout(() => {
        toast.style.display = 'none';
    }, 6500);
}

function renderEpisodeList() {
    const container = document.getElementById('episode-list');
    container.textContent = '';

    episodes.forEach((ep) => {
        const isCurrent = Boolean(currentEpisode && currentEpisode.id === ep.id);
        const card = document.createElement('div');
        card.className = 'episode-card'
            + (isCurrent ? ' active' : '')
            + (ep.locked ? ' locked' : '');

        card.onclick = () => {
            if (ep.locked) {
                showLockedEpisodeToast(ep);
                return;
            }
            selectEpisode(ep);
        };

        const pill = document.createElement('div');
        pill.className = 'ep-num-pill';
        pill.textContent = ep.locked ? '🔒' : String(ep.episodeNumber);
        card.appendChild(pill);

        const info = document.createElement('div');
        info.className = 'ep-info';

        const title = document.createElement('div');
        title.className = 'ep-title';
        title.textContent = ep.title;
        info.appendChild(title);

        const tag = document.createElement('div');
        tag.className = 'ep-tag';
        if (ep.passed) {
            tag.textContent = 'Completed • Quiz Passed';
        } else if (ep.locked) {
            tag.textContent = `Pass Episode ${ep.unlockAfterEpisodeNumber || (ep.episodeNumber - 1)} first`;
        } else {
            const mins = Math.max(1, Math.round((ep.durationSeconds || 1200) / 60));
            tag.textContent = `~${mins} min • +$15.00 Reward`;
        }
        info.appendChild(tag);

        card.appendChild(info);

        if (ep.passed) {
            const badge = document.createElement('div');
            badge.className = 'ep-badge-passed';
            badge.textContent = '✓ PASSED';
            card.appendChild(badge);
        } else if (ep.locked) {
            const badge = document.createElement('div');
            badge.className = 'ep-badge-locked';
            badge.textContent = `🔒 Ep. ${ep.unlockAfterEpisodeNumber || (ep.episodeNumber - 1)}`;
            card.appendChild(badge);
        }

        container.appendChild(card);
    });
}

function selectEpisode(ep) {
    if (ep.locked) {
        showLockedEpisodeToast(ep);
        return;
    }

    const toast = document.getElementById('episode-lock-toast');
    if (toast) toast.style.display = 'none';

    pendingWatchedDeltaSeconds = 0;
    isPlayerCurrentlyPlaying = false;
    lastPlayerTime = 0;

    currentEpisode = ep;
    document.getElementById('current-episode-title').textContent = ep.title;

    if (ep.watchStatus) {
        applyWatchStatus(ep.watchStatus, ep.passed);
    } else {
        serverVerifiedSeconds = 0;
        requiredWatchSeconds = Math.min(DEFAULT_REQUIRED_WATCH_SECONDS, Math.max(60, ep.durationSeconds || DEFAULT_REQUIRED_WATCH_SECONDS));
        document.getElementById('quiz-section').style.display = 'none';
    }

    renderEpisodeList();
    updateExternalYoutubeLink();

    const cleanVid = extractYouTubeId(ep.youtubeVideoId);
    mountYouTubePlayer(cleanVid, 0, false);

    fetchVocabGuide(ep.id);
    fetchQuiz(ep.id);
}

function updateExternalYoutubeLink() {
    if (!currentEpisode) return;
    const cleanVid = extractYouTubeId(currentEpisode.youtubeVideoId);
    const ytExternalLink = document.getElementById('btn-yt-external-link');
    if (!ytExternalLink) return;

    ytExternalLink.href = `https://www.youtube.com/watch?v=${encodeURIComponent(cleanVid)}&list=${PLAYLIST_ID}`;
}

function formatClock(totalSeconds) {
    const s = Math.max(0, Math.floor(totalSeconds || 0));
    const mins = Math.floor(s / 60);
    const secs = s % 60;
    return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

function applyWatchStatus(status, episodePassed) {
    if (!status) return;

    serverVerifiedSeconds = status.verifiedWatchSeconds || 0;
    requiredWatchSeconds = status.requiredWatchSeconds || DEFAULT_REQUIRED_WATCH_SECONDS;

    updateExternalYoutubeLink();
    renderLiveWatchBar(status, episodePassed);

    const quizSection = document.getElementById('quiz-section');
    if (episodePassed || status.quizUnlocked) {
        quizSection.style.display = 'block';
    } else {
        quizSection.style.display = 'none';
    }

    startCooldownTicker(status.retryCooldownRemainingSeconds || 0);
}

function renderLiveWatchBar(statusOverride = null, episodePassedOverride = null) {
    if (!currentEpisode) return;
    const status = statusOverride || currentEpisode.watchStatus || {};
    const episodePassed = episodePassedOverride !== null ? episodePassedOverride : Boolean(currentEpisode.passed);

    const required = Math.max(1, requiredWatchSeconds || status.requiredWatchSeconds || DEFAULT_REQUIRED_WATCH_SECONDS);
    const liveWatched = Math.min(required, serverVerifiedSeconds + pendingWatchedDeltaSeconds);
    const pct = episodePassed ? 100 : Math.min(100, Math.round((liveWatched / required) * 100));

    const statusLabel = document.getElementById('watch-status-label');
    const fill = document.getElementById('watch-progress-fill');

    if (fill) fill.style.width = pct + '%';
    if (!statusLabel) return;

    if (episodePassed) {
        statusLabel.textContent = '✓ Episode Passed — 100% Comprehension Quiz Completed';
    } else if (status.quizUnlocked || liveWatched >= required) {
        statusLabel.textContent = `✓ Watch Target Reached (${formatClock(liveWatched)} / ${formatClock(required)}) — 20-Question Quiz Unlocked Below!`;
    } else {
        const statePrefix = isPlayerCurrentlyPlaying ? '▶️ Watching Live:' : '⏸️ Watch Progress (Paused):';
        statusLabel.textContent = `${statePrefix} ${formatClock(liveWatched)} / ${formatClock(required)} (${pct}%)`;
    }
}

// ============================================================================
// YOUTUBE IFRAME API & REAL PLAYBACK WATCH TRACKER
// ============================================================================

function extractYouTubeId(raw) {
    if (!raw) return 'EXrpLeyeSeU';
    const s = String(raw).trim();
    if (s.includes('youtu.be/')) return s.split('youtu.be/')[1].split(/[?&/#]/)[0];
    if (s.includes('v=')) return s.split('v=')[1].split(/[?&#]/)[0];
    if (s.includes('/embed/')) return s.split('/embed/')[1].split(/[?&/#]/)[0];
    if (s.includes('/shorts/')) return s.split('/shorts/')[1].split(/[?&/#]/)[0];
    return s;
}

function getSafeCurrentTime() {
    try {
        if (ytReady && ytPlayer && typeof ytPlayer.getCurrentTime === 'function') {
            return Math.max(0, Number(ytPlayer.getCurrentTime() || 0));
        }
    } catch (_) {}
    return 0;
}

function getSafeDurationSeconds() {
    try {
        if (ytReady && ytPlayer && typeof ytPlayer.getDuration === 'function') {
            const d = Math.round(Number(ytPlayer.getDuration() || 0));
            return d >= 60 ? d : null;
        }
    } catch (_) {}
    return null;
}

function mountYouTubePlayer(videoId, startSeconds = 0, autoplay = false) {
    const cleanId = extractYouTubeId(videoId);
    pendingPlayerBuild = { videoId: cleanId, startSeconds, autoplay };

    if (!(window.YT && window.YT.Player)) {
        ensureYouTubeIframeScriptLoaded();
        return;
    }

    buildPlayerFromPending();
}

function ensureYouTubeIframeScriptLoaded() {
    window.onYouTubeIframeAPIReady = () => {
        buildPlayerFromPending();
    };
    if (window.YT && window.YT.Player) {
        buildPlayerFromPending();
        return;
    }
    if (!ytApiRequested) {
        ytApiRequested = true;
        const tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        tag.async = true;
        document.head.appendChild(tag);
    }
}

function buildPlayerFromPending() {
    if (!pendingPlayerBuild || !(window.YT && window.YT.Player)) return;
    const { videoId, startSeconds, autoplay } = pendingPlayerBuild;
    pendingPlayerBuild = null;

    const wrapper = document.getElementById('player-wrapper');
    if (!wrapper) return;

    if (ytPlayer && typeof ytPlayer.destroy === 'function') {
        try { ytPlayer.destroy(); } catch (_) {}
    }
    ytPlayer = null;
    ytReady = false;

    wrapper.textContent = '';
    const mountDiv = document.createElement('div');
    mountDiv.id = 'yt-player-mount';
    wrapper.appendChild(mountDiv);

    currentLoadedVideoId = videoId;

    const playerVars = {
        enablejsapi: 1,
        playsinline: 1,
        rel: 0,
        origin: window.location.origin
    };
    if (startSeconds > 0) {
        playerVars.start = Math.floor(startSeconds);
    }
    if (autoplay) {
        playerVars.autoplay = 1;
    }

    try {
        ytPlayer = new YT.Player('yt-player-mount', {
            width: '100%',
            height: '100%',
            videoId: videoId,
            playerVars: playerVars,
            events: {
                onReady: onPlayerReady,
                onStateChange: onPlayerStateChange
            }
        });
    } catch (err) {
        console.warn('YT.Player creation error:', err);
    }
}

function onPlayerReady() {
    ytReady = true;
    lastPlayerTime = getSafeCurrentTime();
    applyCaptionsPreference();
}

function applyCaptionsPreference() {
    if (!ytReady || !ytPlayer) return;
    try {
        if (typeof ytPlayer.loadModule === 'function') {
            ytPlayer.loadModule('captions');
        }
        if (typeof ytPlayer.setOption === 'function') {
            ytPlayer.setOption('captions', 'track', { languageCode: 'en' });
        }
    } catch (_) {}
}

function onPlayerStateChange(event) {
    if (!window.YT || !window.YT.PlayerState) return;

    if (event.data === YT.PlayerState.PLAYING) {
        isPlayerCurrentlyPlaying = true;
        lastPlayerTime = getSafeCurrentTime();
        applyCaptionsPreference();
        renderLiveWatchBar();

        // Initialize server heartbeat timestamp immediately when playback starts
        flushWatchHeartbeat({
            playing: true,
            videoEnded: false,
            external: false,
            videoDurationSeconds: getSafeDurationSeconds()
        });
    } else if (event.data === YT.PlayerState.PAUSED) {
        isPlayerCurrentlyPlaying = false;
        renderLiveWatchBar();
        if (pendingWatchedDeltaSeconds > 0) {
            flushWatchHeartbeat({
                playing: false,
                videoEnded: false,
                external: false,
                videoDurationSeconds: getSafeDurationSeconds()
            });
        }
    } else if (event.data === YT.PlayerState.ENDED) {
        isPlayerCurrentlyPlaying = false;
        renderLiveWatchBar();
        flushWatchHeartbeat({
            playing: false,
            videoEnded: true,
            external: false,
            videoDurationSeconds: getSafeDurationSeconds()
        });
    }
}

function startLocalPlaybackMonitor() {
    if (playbackTickTimer) clearInterval(playbackTickTimer);

    playbackTickTimer = setInterval(() => {
        if (!currentEpisode || !ytReady || !ytPlayer || typeof ytPlayer.getPlayerState !== 'function') {
            return;
        }
        if (!window.YT || !window.YT.PlayerState) return;

        let state;
        let curTime;
        try {
            state = ytPlayer.getPlayerState();
            curTime = Number(ytPlayer.getCurrentTime() || 0);
        } catch (_) {
            return;
        }

        if (state === YT.PlayerState.PLAYING) {
            isPlayerCurrentlyPlaying = true;
            const delta = curTime - lastPlayerTime;
            lastPlayerTime = curTime;

            // Only credit real sequential video playback (0.15s..2.75s per 1s tick).
            // Dragging/scrubbing the seekbar forward produces delta > 2.75s and is ignored!
            if (delta > 0.15 && delta <= 2.75) {
                pendingWatchedDeltaSeconds += 1;
                renderLiveWatchBar();

                const targetRemaining = Math.max(0, requiredWatchSeconds - serverVerifiedSeconds);
                if (!heartbeatInFlight && (pendingWatchedDeltaSeconds >= 5 || (targetRemaining > 0 && pendingWatchedDeltaSeconds >= targetRemaining))) {
                    flushWatchHeartbeat({
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
                renderLiveWatchBar();
            }
            lastPlayerTime = curTime;
        }
    }, 1000);
}

async function flushWatchHeartbeat(opts = {}) {
    if (!currentEpisode) return;

    const deltaToSend = pendingWatchedDeltaSeconds;
    pendingWatchedDeltaSeconds = Math.max(0, pendingWatchedDeltaSeconds - deltaToSend);

    heartbeatInFlight = true;
    try {
        const bodyPayload = {
            watchMode: STANDARD_WATCH_MODE,
            external: false,
            videoEnded: Boolean(opts.videoEnded),
            watchedDeltaSeconds: deltaToSend,
            currentPositionSeconds: Math.floor(getSafeCurrentTime()),
            playing: Boolean(opts.playing)
        };

        const res = await fetch(`/api/student/episodes/${currentEpisode.id}/heartbeat` + betaQuerySuffix('?'), {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify(bodyPayload)
        });

        if (res.ok) {
            const wasUnlocked = Boolean(currentEpisode.watchStatus && currentEpisode.watchStatus.quizUnlocked);
            const status = await res.json();
            currentEpisode.watchStatus = status;
            applyWatchStatus(status, currentEpisode.passed);

            if (!wasUnlocked && status.quizUnlocked && !currentEpisode.passed) {
                if (typeof confetti === 'function') {
                    confetti({ particleCount: 55, spread: 60, origin: { y: 0.7 } });
                }
            }
        } else if (deltaToSend > 0) {
            pendingWatchedDeltaSeconds += deltaToSend;
        }
    } catch (e) {
        if (deltaToSend > 0) {
            pendingWatchedDeltaSeconds += deltaToSend;
        }
        console.warn('Heartbeat sync warning:', e);
    } finally {
        heartbeatInFlight = false;
    }
}

// ============================================================================
// BETA SANDBOX CONTROLS (/beta — Strictly Gated to Admin & Beta Testers)
// ============================================================================

function setBetaStatusMessage(msg) {
    const el = document.getElementById('beta-status-msg');
    if (el) el.textContent = msg;
}

async function betaInstantUnlockQuiz() {
    if (!currentEpisode || !hasBetaAccess) return;
    try {
        const res = await fetch(`/api/beta/episodes/${currentEpisode.id}/unlock-watch?watchMode=${encodeURIComponent(STANDARD_WATCH_MODE)}`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const status = await res.json();
            pendingWatchedDeltaSeconds = 0;
            currentEpisode.watchStatus = status;
            applyWatchStatus(status, currentEpisode.passed);
            setBetaStatusMessage(`✓ 10m watch target unlocked instantly for Ep. ${currentEpisode.episodeNumber}.`);
            document.getElementById('quiz-section').scrollIntoView({ behavior: 'smooth', block: 'start' });
        } else {
            const err = await res.json().catch(() => ({}));
            alert(err.error || 'Beta unlock failed');
        }
    } catch (e) {
        alert('Error: ' + e.message);
    }
}

async function betaToggleUnlockAllEpisodes() {
    if (!hasBetaAccess) return;
    betaUnlockAllEpisodes = !betaUnlockAllEpisodes;
    const btn = document.getElementById('btn-beta-unlock-all');
    if (btn) {
        btn.textContent = betaUnlockAllEpisodes ? '🔓 Unlock All Episodes: ON' : '🔓 Unlock All Episodes: OFF';
        btn.classList.toggle('active-toggle', betaUnlockAllEpisodes);
    }
    setBetaStatusMessage(betaUnlockAllEpisodes
        ? '🔓 All 15 episodes unlocked for free navigation in Beta Sandbox.'
        : '🔒 Strict sequential progression active (Episode N requires Episode N-1 passed).');
    await fetchEpisodes();
}

async function betaClearCooldown() {
    if (!currentEpisode || !hasBetaAccess) return;
    try {
        const res = await fetch(`/api/beta/episodes/${currentEpisode.id}/clear-cooldown`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            startCooldownTicker(0);
            setBetaStatusMessage(`✓ Retry cooldown cleared for Episode ${currentEpisode.episodeNumber}.`);
            await fetchEpisodes();
        }
    } catch (e) {
        console.warn('Clear cooldown failed:', e);
    }
}

async function betaResetMyProgress() {
    if (!hasBetaAccess) return;
    if (!confirm('Reset ALL of your own Beta/Test watch timers, passed episodes, and test balance back to $0.00? (Other student accounts are untouched.)')) {
        return;
    }
    try {
        const res = await fetch('/api/beta/reset-progress', {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            pendingWatchedDeltaSeconds = 0;
            serverVerifiedSeconds = 0;
            currentEpisode = null;
            await Promise.all([fetchEarnings(), fetchEpisodes()]);
            setBetaStatusMessage('✓ Your beta test progress & $0.00 balance have been reset!');
        } else {
            const err = await res.json().catch(() => ({}));
            alert(err.error || 'Reset failed');
        }
    } catch (e) {
        alert('Reset failed: ' + e.message);
    }
}

// ============================================================================
// COOLDOWN, VOCABULARY, & QUIZ SUBMISSION
// ============================================================================

function startCooldownTicker(seconds) {
    if (cooldownInterval) {
        clearInterval(cooldownInterval);
        cooldownInterval = null;
    }
    cooldownRemainingSecs = Math.max(0, Math.floor(seconds || 0));
    renderCooldownBanner();

    if (cooldownRemainingSecs > 0) {
        cooldownInterval = setInterval(() => {
            cooldownRemainingSecs = Math.max(0, cooldownRemainingSecs - 1);
            renderCooldownBanner();
            if (cooldownRemainingSecs <= 0) {
                clearInterval(cooldownInterval);
                cooldownInterval = null;
                if (currentEpisode) fetchQuiz(currentEpisode.id);
            }
        }, 1000);
    }
}

function renderCooldownBanner() {
    const banner = document.getElementById('cooldown-banner');
    const submitBtn = document.getElementById('btn-submit-quiz');
    if (!banner || !submitBtn) return;

    if (cooldownRemainingSecs > 0) {
        banner.style.display = 'block';
        banner.textContent = `⏳ Review Cooldown Active: Please review the lesson! You can retry the quiz in ${formatClock(cooldownRemainingSecs)}.`;
        submitBtn.disabled = true;
        submitBtn.style.opacity = '0.5';
    } else {
        banner.style.display = 'none';
        submitBtn.disabled = false;
        submitBtn.style.opacity = '1';
    }
}

async function fetchVocabGuide(episodeId) {
    const container = document.getElementById('vocab-grid');
    if (!container) return;
    container.textContent = 'Loading vocabulary study guide...';

    try {
        const res = await fetch(`/api/student/episodes/${episodeId}/vocab-guide`, {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const words = await res.json();
            container.textContent = '';
            words.forEach((w) => {
                const card = document.createElement('div');
                card.className = 'vocab-card';

                const ar = document.createElement('div');
                ar.className = 'vocab-arabic';
                ar.textContent = w.arabic || '';

                const tr = document.createElement('div');
                tr.className = 'vocab-translit';
                tr.textContent = w.translit || '';

                const en = document.createElement('div');
                en.className = 'vocab-english';
                en.textContent = w.english || '';

                const note = document.createElement('div');
                note.className = 'vocab-note';
                note.textContent = w.note || '';

                card.appendChild(ar);
                card.appendChild(tr);
                card.appendChild(en);
                card.appendChild(note);
                container.appendChild(card);
            });
        }
    } catch (e) {
        console.warn('Vocab fetch failed:', e);
    }
}

async function fetchQuiz(episodeId) {
    try {
        const res = await fetch(`/api/student/quizzes/${episodeId}` + betaQuerySuffix('?'), {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            currentQuiz = await res.json();
            renderQuizQuestions();
        } else {
            currentQuiz = null;
        }
    } catch (e) {
        console.warn('Quiz fetch failed:', e);
    }
}

function updateQuizAnsweredCounter() {
    const answeredBadge = document.getElementById('quiz-answered-badge');
    if (!answeredBadge || !currentQuiz || !currentQuiz.questions) return;
    const total = currentQuiz.questions.length;
    let answered = 0;
    currentQuiz.questions.forEach((q) => {
        if (document.querySelector(`input[name="question_${q.id}"]:checked`)) {
            answered++;
        }
    });
    answeredBadge.textContent = `Answered: ${answered} / ${total}`;
}

function renderQuizQuestions() {
    const container = document.getElementById('questions-container');
    const submitBtn = document.getElementById('btn-submit-quiz');
    container.textContent = '';

    if (!currentQuiz || currentQuiz.ready === false || !currentQuiz.questions || currentQuiz.questions.length === 0) {
        const p = document.createElement('p');
        p.style.color = 'var(--text-muted)';
        p.style.padding = '1rem';
        p.textContent = (currentQuiz && currentQuiz.message) ? currentQuiz.message : 'Quiz not ready yet';
        container.appendChild(p);
        if (submitBtn) {
            submitBtn.style.display = 'none';
        }
        return;
    }

    if (submitBtn) {
        submitBtn.style.display = 'inline-flex';
    }

    const totalQ = currentQuiz.questions.length;
    const passPct = Math.round(currentQuiz.passingScorePercentage || 100);
    const requiredCorrect = Math.ceil((passPct / 100) * totalQ);

    const quizHeading = document.getElementById('quiz-heading-title');
    if (quizHeading) {
        quizHeading.textContent = `Comprehension Quiz (${totalQ} Questions)`;
    }
    const passBadge = document.getElementById('quiz-pass-badge');
    if (passBadge) {
        passBadge.textContent = `Pass Mark: ${passPct}% (${requiredCorrect}/${totalQ})`;
    }

    currentQuiz.questions.forEach((q, idx) => {
        const block = document.createElement('div');
        block.className = 'question-block';

        const heading = document.createElement('div');
        heading.className = 'question-heading';
        heading.textContent = `السؤال ${idx + 1} (${idx + 1}/${totalQ}): ${q.questionText}`;
        block.appendChild(heading);

        const grid = document.createElement('div');
        grid.className = 'options-grid';

        q.options.forEach((opt) => {
            const label = document.createElement('label');
            label.className = 'option-pill';

            const radio = document.createElement('input');
            radio.type = 'radio';
            radio.name = `question_${q.id}`;
            radio.value = String(opt.id);
            radio.required = true;
            radio.addEventListener('change', updateQuizAnsweredCounter);

            const span = document.createElement('span');
            span.textContent = opt.optionText;

            label.appendChild(radio);
            label.appendChild(span);
            grid.appendChild(label);
        });

        block.appendChild(grid);
        container.appendChild(block);
    });

    updateQuizAnsweredCounter();
}

async function handleQuizSubmit(event) {
    event.preventDefault();
    if (!currentQuiz || !currentEpisode) return;

    const submitBtn = document.getElementById('btn-submit-quiz');
    submitBtn.disabled = true;

    const answers = {};
    currentQuiz.questions.forEach((q) => {
        const checked = document.querySelector(`input[name="question_${q.id}"]:checked`);
        if (checked) {
            answers[q.id] = parseInt(checked.value, 10);
        }
    });

    const payload = {
        episodeId: currentEpisode.id,
        watchMode: STANDARD_WATCH_MODE,
        answers: answers
    };

    try {
        const res = await fetch('/api/student/quizzes/submit' + betaQuerySuffix('?'), {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify(payload)
        });

        if (res.ok) {
            const result = await res.json();
            await Promise.all([fetchEarnings(), fetchEpisodes(), fetchQuiz(currentEpisode.id)]);
            showResultModal(result);
        } else {
            const err = await res.json().catch(() => ({}));
            if (res.status === 429 && err.retryAfterSeconds) {
                startCooldownTicker(err.retryAfterSeconds);
            }
            alert(err.error || 'Unable to submit quiz.');
        }
    } catch (e) {
        alert('Submission error: ' + e.message);
    } finally {
        if (cooldownRemainingSecs <= 0) {
            submitBtn.disabled = false;
        }
    }
}

function showResultModal(result) {
    const modal = document.getElementById('result-modal');
    const dialog = document.getElementById('modal-dialog');
    const title = document.getElementById('modal-title');
    const score = document.getElementById('modal-score');
    const message = document.getElementById('modal-message');
    const nextBtn = document.getElementById('btn-modal-next-ep');

    if (result.passed) {
        dialog.className = 'modal-dialog';
        title.textContent = '🎉 Congratulations!';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;

        const nextEp = findNextSequentialEpisode();
        if (nextBtn) {
            nextBtn.style.display = nextEp ? 'inline-flex' : 'none';
        }

        if (typeof confetti === 'function') {
            confetti({ particleCount: 120, spread: 80, origin: { y: 0.6 } });
        }
    } else {
        dialog.className = 'modal-dialog failed';
        title.textContent = '❌ Review & Retry Later';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;
        if (nextBtn) nextBtn.style.display = 'none';
    }

    modal.style.display = 'flex';
}

function findNextSequentialEpisode() {
    if (!currentEpisode || !episodes.length) return null;
    const idx = episodes.findIndex((e) => e.id === currentEpisode.id);
    if (idx >= 0 && idx + 1 < episodes.length && !episodes[idx + 1].locked) {
        return episodes[idx + 1];
    }
    return null;
}

function advanceToNextUnlockedEpisode() {
    closeModal();
    const nextEp = findNextSequentialEpisode();
    if (nextEp) {
        selectEpisode(nextEp);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }
}

function closeModal() {
    document.getElementById('result-modal').style.display = 'none';
}
