// Turathtona (تراثُنا) Student Application Logic — Hardened & Anti-Cheat Verified

// Purge any legacy insecure localStorage entries
localStorage.removeItem('aq_auth_token');
localStorage.removeItem('aq_role');
localStorage.removeItem('aq_username');
localStorage.removeItem('aq_fullname');

let currentMode = 'ARABIC_SUB';
let currentEpisode = null;
let episodes = [];
let currentQuiz = null;
let ytPlayer = null;
let ytReady = false;
let ytApiRequested = false;
let currentLoadedVideoId = null;
let heartbeatTimer = null;
let externalPollTimer = null;
let cooldownInterval = null;
let cooldownRemainingSecs = 0;

function apiHeaders(includeJson = false) {
    const h = { 'X-Requested-With': 'XMLHttpRequest' };
    if (includeJson) {
        h['Content-Type'] = 'application/json';
    }
    return h;
}

document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

async function initApp() {
    try {
        const meRes = await fetch('/api/auth/me', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (!meRes.ok) {
            window.location.href = '/login';
            return;
        }
        const me = await meRes.json();
        const displayEl = document.getElementById('student-name-display');
        if (displayEl) {
            displayEl.textContent = '🎓 ' + (me.fullName || me.username);
        }
        await Promise.all([fetchEarnings(), fetchEpisodes()]);
    } catch (e) {
        window.location.href = '/login';
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
            window.location.href = '/login';
        }
    } catch (e) {
        console.warn('Earnings fetch failed:', e);
    }
}

async function fetchEpisodes() {
    try {
        const res = await fetch('/api/student/episodes', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            episodes = await res.json();
            if (currentEpisode) {
                const refreshed = episodes.find(e => e.id === currentEpisode.id);
                if (refreshed) {
                    currentEpisode = refreshed;
                    applyWatchStatus(refreshed.watchStatus, refreshed.passed);
                }
            }
            renderEpisodeList();
            if (episodes.length > 0 && !currentEpisode) {
                selectEpisode(episodes[0]);
            }
        } else if (res.status === 401) {
            window.location.href = '/login';
        }
    } catch (e) {
        console.warn('Episodes fetch failed:', e);
    }
}

function renderEpisodeList() {
    const container = document.getElementById('episode-list');
    container.textContent = '';

    episodes.forEach((ep) => {
        const card = document.createElement('div');
        card.className = 'episode-card' + (currentEpisode && currentEpisode.id === ep.id ? ' active' : '');
        card.onclick = () => selectEpisode(ep);

        const pill = document.createElement('div');
        pill.className = 'ep-num-pill';
        pill.textContent = String(ep.episodeNumber);
        card.appendChild(pill);

        const info = document.createElement('div');
        info.className = 'ep-info';

        const title = document.createElement('div');
        title.className = 'ep-title';
        title.textContent = ep.title;
        info.appendChild(title);

        const tag = document.createElement('div');
        tag.className = 'ep-tag';
        tag.textContent = ep.passed ? 'Status: Completed' : 'Up to +$15.00 Reward';
        info.appendChild(tag);

        card.appendChild(info);

        if (ep.passed) {
            const badge = document.createElement('div');
            badge.className = 'ep-badge-passed';
            badge.textContent = '✓ PASSED';
            card.appendChild(badge);
        }

        container.appendChild(card);
    });
}

function selectEpisode(ep) {
    stopWatchTimers();
    currentEpisode = ep;
    document.getElementById('current-episode-title').textContent = ep.title;
    renderEpisodeList();

    const cleanVid = extractYouTubeId(ep.youtubeVideoId);
    const ytExternalLink = document.getElementById('btn-yt-external-link');
    if (ytExternalLink) {
        ytExternalLink.href = `https://www.youtube.com/watch?v=${encodeURIComponent(cleanVid)}&list=PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB`;
    }

    loadVideoIntoPlayer(cleanVid);

    if (ep.watchStatus) {
        applyWatchStatus(ep.watchStatus, ep.passed);
    } else {
        document.getElementById('quiz-section').style.display = 'none';
    }

    fetchVocabGuide(ep.id);
    fetchQuiz(ep.id);
}

function formatClock(totalSeconds) {
    const s = Math.max(0, Math.floor(totalSeconds || 0));
    const mins = Math.floor(s / 60);
    const secs = s % 60;
    return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

function applyWatchStatus(status, episodePassed) {
    if (!status) return;

    if (status.modeLocked && status.lockedWatchMode) {
        currentMode = status.lockedWatchMode;
    }
    updateModeButtonsUI(status.modeLocked, episodePassed);

    const verified = status.verifiedWatchSeconds || 0;
    const required = status.requiredWatchSeconds || 600;
    const pct = required > 0 ? Math.min(100, Math.round((verified / required) * 100)) : 100;

    const statusLabel = document.getElementById('watch-status-label');
    const lockedBadge = document.getElementById('locked-mode-badge');
    const resetBtn = document.getElementById('btn-reset-mode');
    const fill = document.getElementById('watch-progress-fill');

    fill.style.width = (episodePassed ? 100 : pct) + '%';

    if (episodePassed) {
        statusLabel.textContent = '✓ Episode Passed — Comprehension Quiz Unlocked';
        lockedBadge.textContent = '';
        resetBtn.style.display = 'none';
    } else if (status.quizUnlocked) {
        statusLabel.textContent = `✓ Watch Requirement Met (${formatClock(verified)} / ${formatClock(required)}) — Quiz Unlocked!`;
        lockedBadge.textContent = status.modeLocked
            ? `🔒 Mode: ${currentMode === 'ARABIC_SUB' ? 'Arabic + Subs (+$15)' : 'English Dub (+$10)'}`
            : '';
        resetBtn.style.display = status.modeLocked ? 'inline-block' : 'none';
    } else {
        statusLabel.textContent = `⏱️ Watch Progress: ${formatClock(verified)} / ${formatClock(required)} verified`;
        lockedBadge.textContent = status.modeLocked
            ? `🔒 Mode Locked: ${currentMode === 'ARABIC_SUB' ? 'Arabic + Subs (+$15)' : 'English Dub (+$10)'}`
            : 'Select viewing mode before playing';
        resetBtn.style.display = status.modeLocked ? 'inline-block' : 'none';
    }

    const quizSection = document.getElementById('quiz-section');
    if (episodePassed || status.quizUnlocked) {
        quizSection.style.display = 'block';
    } else {
        quizSection.style.display = 'none';
    }

    startCooldownTicker(status.retryCooldownRemainingSeconds || 0);
}

function updateModeButtonsUI(isLocked, episodePassed) {
    const arBtn = document.getElementById('btn-mode-arabic');
    const enBtn = document.getElementById('btn-mode-english');
    arBtn.classList.toggle('active', currentMode === 'ARABIC_SUB');
    enBtn.classList.toggle('active', currentMode === 'ENGLISH_DUB');

    const disableSwitch = Boolean(isLocked && !episodePassed);
    if (disableSwitch) {
        arBtn.style.opacity = currentMode === 'ARABIC_SUB' ? '1' : '0.5';
        enBtn.style.opacity = currentMode === 'ENGLISH_DUB' ? '1' : '0.5';
    } else {
        arBtn.style.opacity = '1';
        enBtn.style.opacity = '1';
    }
}

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
                fetchQuiz(currentEpisode.id);
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

function selectMode(mode) {
    if (!currentEpisode) {
        currentMode = mode;
        updateModeButtonsUI(false, false);
        return;
    }

    const ws = currentEpisode.watchStatus;
    if (ws && ws.modeLocked && !currentEpisode.passed && ws.lockedWatchMode !== mode) {
        handleSwitchLockedMode(mode);
        return;
    }

    currentMode = mode;
    updateModeButtonsUI(ws ? ws.modeLocked : false, currentEpisode.passed);
}

async function handleSwitchLockedMode(targetMode) {
    if (!currentEpisode) return;
    const nextMode = targetMode || (currentMode === 'ARABIC_SUB' ? 'ENGLISH_DUB' : 'ARABIC_SUB');
    const label = nextMode === 'ARABIC_SUB' ? 'Arabic + English Subs (+$15)' : 'English Dub (+$10)';
    if (!confirm(`Switch viewing mode to "${label}"? This will reset your watch timer for this episode to 00:00.`)) {
        return;
    }

    stopWatchTimers();
    try {
        const res = await fetch(`/api/student/episodes/${currentEpisode.id}/reset-session?watchMode=${encodeURIComponent(nextMode)}`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const status = await res.json();
            currentMode = nextMode;
            currentEpisode.watchStatus = status;
            applyWatchStatus(status, currentEpisode.passed);
        }
    } catch (e) {
        console.warn('Reset session failed:', e);
    }
}

async function sendWatchHeartbeat(external = false, videoEnded = false) {
    if (!currentEpisode) return;
    try {
        const res = await fetch(`/api/student/episodes/${currentEpisode.id}/heartbeat`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify({
                watchMode: currentMode,
                external: external,
                videoEnded: videoEnded
            })
        });
        if (res.ok) {
            const wasUnlocked = currentEpisode.watchStatus && currentEpisode.watchStatus.quizUnlocked;
            const status = await res.json();
            currentEpisode.watchStatus = status;
            applyWatchStatus(status, currentEpisode.passed);

            if (!wasUnlocked && status.quizUnlocked && !currentEpisode.passed) {
                if (externalPollTimer) {
                    clearInterval(externalPollTimer);
                    externalPollTimer = null;
                }
                if (typeof confetti === 'function') {
                    confetti({ particleCount: 50, spread: 55, origin: { y: 0.7 } });
                }
            }
        }
    } catch (e) {
        console.warn('Heartbeat failed:', e);
    }
}

function handleStartExternalWatch() {
    if (!currentEpisode) return;
    sendWatchHeartbeat(true, false);
    if (externalPollTimer) {
        clearInterval(externalPollTimer);
    }
    externalPollTimer = setInterval(() => {
        sendWatchHeartbeat(true, false);
    }, 15000);
}

function stopWatchTimers() {
    if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }
    if (externalPollTimer) {
        clearInterval(externalPollTimer);
        externalPollTimer = null;
    }
}

// XSS-Safe Vocabulary Rendering
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
        const res = await fetch(`/api/student/quizzes/${episodeId}`, {
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

function renderQuizQuestions() {
    const container = document.getElementById('questions-container');
    container.textContent = '';

    if (!currentQuiz || !currentQuiz.questions || currentQuiz.questions.length === 0) {
        const p = document.createElement('p');
        p.style.color = 'var(--text-muted)';
        p.style.padding = '1rem';
        p.textContent = 'No quiz configured for this episode yet.';
        container.appendChild(p);
        return;
    }

    currentQuiz.questions.forEach((q, idx) => {
        const block = document.createElement('div');
        block.className = 'question-block';

        const heading = document.createElement('div');
        heading.className = 'question-heading';
        heading.textContent = `السؤال ${idx + 1}: ${q.questionText}`;
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

            const span = document.createElement('span');
            span.textContent = opt.optionText;

            label.appendChild(radio);
            label.appendChild(span);
            grid.appendChild(label);
        });

        block.appendChild(grid);
        container.appendChild(block);
    });
}

function extractYouTubeId(raw) {
    if (!raw) return 'EXrpLeyeSeU';
    const s = String(raw).trim();
    if (s.includes('youtu.be/')) return s.split('youtu.be/')[1].split(/[?&/#]/)[0];
    if (s.includes('v=')) return s.split('v=')[1].split(/[?&#]/)[0];
    if (s.includes('/embed/')) return s.split('/embed/')[1].split(/[?&/#]/)[0];
    if (s.includes('/shorts/')) return s.split('/shorts/')[1].split(/[?&/#]/)[0];
    return s;
}

function buildEmbedUrl(videoId) {
    const cleanId = encodeURIComponent(extractYouTubeId(videoId));
    const origin = encodeURIComponent(window.location.origin);
    return `https://www.youtube.com/embed/${cleanId}?enablejsapi=1&playsinline=1&rel=0&cc_load_policy=1&hl=en&origin=${origin}`;
}

function loadVideoIntoPlayer(videoId) {
    const cleanId = extractYouTubeId(videoId);
    const iframe = document.getElementById('yt-player');
    if (!iframe) return;

    if (currentLoadedVideoId === cleanId && iframe.src && iframe.src.includes(`/embed/${cleanId}`)) {
        return;
    }
    currentLoadedVideoId = cleanId;

    if (ytReady && ytPlayer && typeof ytPlayer.cueVideoById === 'function') {
        try {
            ytPlayer.cueVideoById(cleanId);
            return;
        } catch (err) {
            console.warn('cueVideoById fallback to iframe src:', err);
        }
    }

    ytReady = false;
    ytPlayer = null;
    iframe.onload = () => {
        attachYtPlayerToIframe();
    };
    iframe.src = buildEmbedUrl(cleanId);
    ensureYouTubeIframeScriptLoaded();
}

function ensureYouTubeIframeScriptLoaded() {
    window.onYouTubeIframeAPIReady = () => {
        attachYtPlayerToIframe();
    };
    if (window.YT && window.YT.Player) {
        attachYtPlayerToIframe();
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

function attachYtPlayerToIframe() {
    if (ytPlayer) return;
    if (!(window.YT && window.YT.Player)) return;

    const iframe = document.getElementById('yt-player');
    if (!iframe || !iframe.src || iframe.src === 'about:blank' || !iframe.src.includes('youtube.com/embed/')) {
        return;
    }

    try {
        ytPlayer = new YT.Player('yt-player', {
            events: {
                'onReady': () => {
                    ytReady = true;
                },
                'onStateChange': onPlayerStateChange
            }
        });
    } catch (e) {
        console.warn('YT.Player attach warning:', e);
    }
}

function onPlayerStateChange(event) {
    if (!window.YT || !window.YT.PlayerState) return;
    if (event.data === YT.PlayerState.PLAYING) {
        sendWatchHeartbeat(false, false);
        if (!heartbeatTimer) {
            heartbeatTimer = setInterval(() => {
                sendWatchHeartbeat(false, false);
            }, 10000);
        }
    } else {
        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
            heartbeatTimer = null;
        }
        if (event.data === YT.PlayerState.ENDED) {
            sendWatchHeartbeat(false, true);
        }
    }
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
        watchMode: currentMode,
        answers: answers
    };

    try {
        const res = await fetch('/api/student/quizzes/submit', {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify(payload)
        });

        if (res.ok) {
            const result = await res.json();
            showResultModal(result);
            await Promise.all([fetchEarnings(), fetchEpisodes(), fetchQuiz(currentEpisode.id)]);
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

    if (result.passed) {
        dialog.className = 'modal-dialog';
        title.textContent = '🎉 Congratulations!';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;

        if (typeof confetti === 'function') {
            confetti({ particleCount: 120, spread: 80, origin: { y: 0.6 } });
        }
    } else {
        dialog.className = 'modal-dialog failed';
        title.textContent = '❌ Review & Retry Later';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;
    }

    modal.style.display = 'flex';
}

function closeModal() {
    document.getElementById('result-modal').style.display = 'none';
}
