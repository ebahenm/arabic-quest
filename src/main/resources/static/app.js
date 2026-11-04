// Arabic Quest Frontend Application Logic

let currentUser = {
    username: 'brother',
    password: 'learn123',
    role: 'STUDENT'
};

let currentMode = 'ARABIC_SUB';
let currentEpisode = null;
let episodes = [];
let currentQuiz = null;
let ytPlayer = null;
let lastOwedAmount = 0;

// Base Auth Header
function getAuthHeader() {
    return 'Basic ' + btoa(currentUser.username + ':' + currentUser.password);
}

// App Initialization
document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

function initApp() {
    fetchEarnings();
    fetchEpisodes();
}

// Role Switcher
function handleUserSwitch() {
    const sel = document.getElementById('user-selector').value;
    if (sel === 'brother') {
        currentUser = { username: 'brother', password: 'learn123', role: 'STUDENT' };
        document.getElementById('student-view').style.display = 'grid';
        document.getElementById('admin-view').style.display = 'none';
        document.getElementById('student-earnings-pill').style.display = 'flex';
        fetchEarnings();
        fetchEpisodes();
    } else {
        currentUser = { username: 'admin', password: 'admin123', role: 'ADMIN' };
        document.getElementById('student-view').style.display = 'none';
        document.getElementById('admin-view').style.display = 'grid';
        document.getElementById('student-earnings-pill').style.display = 'none';
        loadAdminDashboard();
    }
}

// Fetch Student Earnings & Update Progress Bar
async function fetchEarnings() {
    try {
        const res = await fetch('/api/student/earnings', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            const data = await res.json();
            lastOwedAmount = data.balanceOwed;
            document.getElementById('val-earned').textContent = '$' + data.totalEarned.toFixed(2);
            document.getElementById('val-paid').textContent = '$' + data.totalPaid.toFixed(2);
            document.getElementById('val-owed').textContent = '$' + data.balanceOwed.toFixed(2);
            
            const total = data.totalEpisodesCount > 0 ? data.totalEpisodesCount : 1;
            const percent = Math.min(100, Math.round((data.passedEpisodesCount / total) * 100));
            
            document.getElementById('badge-course-count').textContent = `${data.passedEpisodesCount}/${data.totalEpisodesCount}`;
            document.getElementById('progress-percent-label').textContent = `${percent}%`;
            document.getElementById('progress-bar-fill').style.width = `${percent}%`;
        }
    } catch (e) {
        console.warn('Earnings fetch failed:', e);
    }
}

// Fetch Course Episodes
async function fetchEpisodes() {
    try {
        const res = await fetch('/api/student/episodes', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            episodes = await res.json();
            renderEpisodeList();
            if (episodes.length > 0 && !currentEpisode) {
                selectEpisode(episodes[0]);
            }
        }
    } catch (e) {
        console.warn('Episodes fetch failed:', e);
    }
}

// Render Episode List Cards
function renderEpisodeList() {
    const container = document.getElementById('episode-list');
    container.innerHTML = '';

    episodes.forEach((ep) => {
        const card = document.createElement('div');
        card.className = 'episode-card' + (currentEpisode && currentEpisode.id === ep.id ? ' active' : '');
        card.onclick = () => selectEpisode(ep);

        const pill = document.createElement('div');
        pill.className = 'ep-num-pill';
        pill.textContent = ep.episodeNumber;
        card.appendChild(pill);

        const info = document.createElement('div');
        info.className = 'ep-info';
        
        const title = document.createElement('div');
        title.className = 'ep-title';
        title.textContent = ep.title;
        info.appendChild(title);

        const tag = document.createElement('div');
        tag.className = 'ep-tag';
        tag.textContent = ep.passed ? 'Status: Completed' : 'Reward: +$15.00';
        info.appendChild(tag);

        card.appendChild(info);

        if (ep.passed) {
            const badge = document.createElement('div');
            badge.className = 'ep-badge-passed';
            badge.innerHTML = '<span>✓</span> PASSED';
            card.appendChild(badge);
        }

        container.appendChild(card);
    });
}

// Select Episode
function selectEpisode(ep) {
    currentEpisode = ep;
    document.getElementById('current-episode-title').textContent = ep.title;
    renderEpisodeList();

    // Reset Quiz
    document.getElementById('quiz-section').style.display = 'none';
    document.getElementById('video-status-text').textContent = 'Watch the episode to completion to unlock the comprehension quiz!';

    // Load in YouTube player
    if (ytPlayer && ytPlayer.loadVideoById) {
        ytPlayer.loadVideoById(ep.youtubeVideoId);
    }

    fetchQuiz(ep.id);
}

// Fetch Quiz
async function fetchQuiz(episodeId) {
    try {
        const res = await fetch(`/api/student/quizzes/${episodeId}`, {
            headers: { 'Authorization': getAuthHeader() }
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

// Render Quiz Questions
function renderQuizQuestions() {
    const container = document.getElementById('questions-container');
    container.innerHTML = '';

    if (!currentQuiz || !currentQuiz.questions || currentQuiz.questions.length === 0) {
        container.innerHTML = '<p style="color:var(--text-muted); padding:1rem;">No quiz configured for this episode yet.</p>';
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
            radio.value = opt.id;
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

// Watch Mode Switch
function selectMode(mode) {
    currentMode = mode;
    document.getElementById('btn-mode-arabic').classList.toggle('active', mode === 'ARABIC_SUB');
    document.getElementById('btn-mode-english').classList.toggle('active', mode === 'ENGLISH_DUB');
}

// YouTube Player Ready Hook
function onYouTubeIframeAPIReady() {
    const initialVideoId = episodes.length > 0 ? episodes[0].youtubeVideoId : 'p1qG6FqfJ8w';
    ytPlayer = new YT.Player('yt-player', {
        height: '100%',
        width: '100%',
        videoId: initialVideoId,
        playerVars: {
            'playsinline': 1,
            'rel': 0,
            'modestbranding': 1
        },
        events: {
            'onStateChange': onPlayerStateChange
        }
    });
}

// Automatically Unlock Quiz When Video Finishes
function onPlayerStateChange(event) {
    if (event.data === YT.PlayerState.ENDED) {
        devUnlockQuiz();
    }
}

// Unlock Quiz Action
function devUnlockQuiz() {
    document.getElementById('quiz-section').style.display = 'block';
    document.getElementById('video-status-text').innerHTML = 
        '<strong style="color:var(--primary);">🎉 Episode completed! The quiz is now unlocked below!</strong>';
    document.getElementById('quiz-section').scrollIntoView({ behavior: 'smooth' });
}

// Submit Quiz Form
async function handleQuizSubmit(event) {
    event.preventDefault();
    if (!currentQuiz || !currentEpisode) return;

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
            headers: {
                'Content-Type': 'application/json',
                'Authorization': getAuthHeader()
            },
            body: JSON.stringify(payload)
        });

        if (res.ok) {
            const result = await res.json();
            showResultModal(result);
            fetchEarnings();
            fetchEpisodes();
        } else {
            alert('Failed to submit quiz. Please try again.');
        }
    } catch (e) {
        alert('Submission error: ' + e.message);
    }
}

// Result Modal with Confetti
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

        // Confetti Celebration
        if (typeof confetti === 'function') {
            confetti({
                particleCount: 120,
                spread: 80,
                origin: { y: 0.6 }
            });
        }
    } else {
        dialog.className = 'modal-dialog failed';
        title.textContent = '❌ Try Again!';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;
    }

    modal.style.display = 'flex';
}

function closeModal() {
    document.getElementById('result-modal').style.display = 'none';
}

// Admin Dashboard Functions
async function loadAdminDashboard() {
    try {
        const earningsRes = await fetch('/api/admin/students/brother/earnings', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (earningsRes.ok) {
            const data = await earningsRes.json();
            lastOwedAmount = data.balanceOwed;
            document.getElementById('admin-metric-earned').textContent = '$' + data.totalEarned.toFixed(2);
            document.getElementById('admin-metric-paid').textContent = '$' + data.totalPaid.toFixed(2);
            document.getElementById('admin-metric-owed').textContent = '$' + data.balanceOwed.toFixed(2);
            document.getElementById('admin-metric-episodes').textContent = `${data.passedEpisodesCount} / ${data.totalEpisodesCount}`;
            document.getElementById('payout-amount').value = data.balanceOwed > 0 ? data.balanceOwed : '';
        }

        const attemptsRes = await fetch('/api/admin/attempts', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (attemptsRes.ok) {
            const attempts = await attemptsRes.json();
            const tbody = document.getElementById('admin-attempts-tbody');
            tbody.innerHTML = '';
            
            if (attempts.length === 0) {
                tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:var(--text-muted); padding:2rem;">No quiz attempts logged yet.</td></tr>';
            } else {
                attempts.slice().reverse().forEach((a) => {
                    const tr = document.createElement('tr');
                    tr.innerHTML = `
                        <td><strong>${a.student.fullName || a.student.username}</strong></td>
                        <td style="font-family:'Cairo';">Ep. ${a.episode.episodeNumber}: ${a.episode.title}</td>
                        <td><span style="font-size:0.8rem; background:rgba(255,255,255,0.06); padding:0.2rem 0.5rem; border-radius:4px;">${a.watchMode}</span></td>
                        <td><strong>${a.scorePercentage.toFixed(0)}%</strong></td>
                        <td>
                            <span style="color:${a.passed ? '#10b981':'#ef4444'}; font-weight:800; font-size:0.82rem;">
                                ${a.passed ? '✓ PASSED' : '✗ FAILED'}
                            </span>
                        </td>
                        <td style="color:#f59e0b; font-weight:800;">+$${a.rewardAmount.toFixed(2)}</td>
                        <td style="color:var(--text-muted); font-size:0.82rem;">${new Date(a.attemptedAt).toLocaleString()}</td>
                    `;
                    tbody.appendChild(tr);
                });
            }
        }
    } catch (e) {
        console.warn('Admin load failed:', e);
    }
}

// Payout Presets
function setPayoutInput(val) {
    document.getElementById('payout-amount').value = val;
}

function setPayoutFullOwed() {
    document.getElementById('payout-amount').value = lastOwedAmount > 0 ? lastOwedAmount : 0;
}

async function handleRecordPayout() {
    const amount = document.getElementById('payout-amount').value;
    if (!amount || parseFloat(amount) <= 0) {
        alert('Please enter a valid payout amount.');
        return;
    }

    try {
        const res = await fetch(`/api/admin/payouts?studentId=2&amount=${encodeURIComponent(amount)}&note=Cash Transfer`, {
            method: 'POST',
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            alert(`✓ Successfully recorded payout of $${parseFloat(amount).toFixed(2)} to Brother!`);
            loadAdminDashboard();
        } else {
            alert('Failed to record payout.');
        }
    } catch (e) {
        alert('Error recording payout: ' + e.message);
    }
}
