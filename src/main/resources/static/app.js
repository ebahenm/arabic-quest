// Arabic Quest Frontend Application

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

// Auth Header Helper
function getAuthHeader() {
    return 'Basic ' + btoa(currentUser.username + ':' + currentUser.password);
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

function initApp() {
    fetchEarnings();
    fetchEpisodes();
}

// User Switcher
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
        document.getElementById('admin-view').style.display = 'block';
        document.getElementById('student-earnings-pill').style.display = 'none';
        loadAdminDashboard();
    }
}

// Fetch Student Earnings
async function fetchEarnings() {
    try {
        const res = await fetch('/api/student/earnings', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            const data = await res.json();
            document.getElementById('val-earned').textContent = '$' + data.totalEarned.toFixed(2);
            document.getElementById('val-paid').textContent = '$' + data.totalPaid.toFixed(2);
            document.getElementById('val-owed').textContent = '$' + data.balanceOwed.toFixed(2);
            document.getElementById('progress-indicator').textContent = `${data.passedEpisodesCount}/${data.totalEpisodesCount} Passed`;
        }
    } catch (e) {
        console.warn('Earnings fetch failed:', e);
    }
}

// Fetch Episodes
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

// Render Episode List
function renderEpisodeList() {
    const container = document.getElementById('episode-list');
    container.innerHTML = '';

    episodes.forEach((ep) => {
        const div = document.createElement('div');
        div.className = 'episode-item' + (currentEpisode && currentEpisode.id === ep.id ? ' active' : '');
        div.onclick = () => selectEpisode(ep);

        const titleSpan = document.createElement('span');
        titleSpan.textContent = `Ep. ${ep.episodeNumber}: ${ep.title}`;
        div.appendChild(titleSpan);

        if (ep.passed) {
            const badge = document.createElement('span');
            badge.className = 'badge-passed';
            badge.textContent = 'PASSED ✓';
            div.appendChild(badge);
        }

        container.appendChild(div);
    });
}

// Select an Episode
function selectEpisode(ep) {
    currentEpisode = ep;
    document.getElementById('current-episode-title').textContent = `Episode ${ep.episodeNumber}: ${ep.title}`;
    renderEpisodeList();

    // Reset Quiz UI
    document.getElementById('quiz-section').style.display = 'none';
    document.getElementById('video-status-text').textContent = '⏳ Watch the episode completely to unlock the quiz!';

    // Load YouTube video
    if (ytPlayer && ytPlayer.loadVideoById) {
        ytPlayer.loadVideoById(ep.youtubeVideoId);
    }

    // Pre-fetch quiz questions
    fetchQuiz(ep.id);
}

// Fetch Quiz for Episode
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

// Render Quiz Questions into Form
function renderQuizQuestions() {
    const container = document.getElementById('questions-container');
    container.innerHTML = '';

    if (!currentQuiz || !currentQuiz.questions) {
        container.innerHTML = '<p style="color:var(--text-muted);">No quiz available for this episode yet.</p>';
        return;
    }

    currentQuiz.questions.forEach((q, idx) => {
        const card = document.createElement('div');
        card.className = 'question-card';

        const text = document.createElement('div');
        text.className = 'question-text';
        text.textContent = `${idx + 1}. ${q.questionText}`;
        card.appendChild(text);

        const optionsList = document.createElement('div');
        optionsList.className = 'options-list';

        q.options.forEach((opt) => {
            const label = document.createElement('label');
            label.className = 'option-label';

            const radio = document.createElement('input');
            radio.type = 'radio';
            radio.name = `question_${q.id}`;
            radio.value = opt.id;
            radio.required = true;

            const span = document.createElement('span');
            span.textContent = opt.optionText;

            label.appendChild(radio);
            label.appendChild(span);
            optionsList.appendChild(label);
        });

        card.appendChild(optionsList);
        container.appendChild(card);
    });
}

// Watch Mode Selection
function selectMode(mode) {
    currentMode = mode;
    document.getElementById('btn-mode-arabic').classList.toggle('selected', mode === 'ARABIC_SUB');
    document.getElementById('btn-mode-english').classList.toggle('selected', mode === 'ENGLISH_DUB');
}

// YouTube IFrame API Callback
function onYouTubeIframeAPIReady() {
    const initialVideoId = episodes.length > 0 ? episodes[0].youtubeVideoId : 'dQw4w9WgXcQ';
    ytPlayer = new YT.Player('yt-player', {
        height: '100%',
        width: '100%',
        videoId: initialVideoId,
        playerVars: {
            'playsinline': 1,
            'rel': 0
        },
        events: {
            'onStateChange': onPlayerStateChange
        }
    });
}

// Unlock quiz when video finishes
function onPlayerStateChange(event) {
    if (event.data === YT.PlayerState.ENDED) {
        devUnlockQuiz();
    }
}

// Unlock Quiz Action
function devUnlockQuiz() {
    document.getElementById('quiz-section').style.display = 'block';
    document.getElementById('video-status-text').innerHTML = '<span style="color:#10b981; font-weight:600;">🎉 Episode Finished! Quiz Unlocked below!</span>';
    document.getElementById('quiz-section').scrollIntoView({ behavior: 'smooth' });
}

// Submit Quiz
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

// Result Modal
function showResultModal(result) {
    const modal = document.getElementById('result-modal');
    const modalBox = document.getElementById('modal-box');
    const title = document.getElementById('modal-title');
    const score = document.getElementById('modal-score');
    const message = document.getElementById('modal-message');

    if (result.passed) {
        modalBox.className = 'modal-content';
        title.textContent = '🎉 Congratulations!';
        score.style.color = 'var(--primary)';
        score.textContent = result.scorePercentage.toFixed(1) + '%';
        message.textContent = result.message;
    } else {
        modalBox.className = 'modal-content failed';
        title.textContent = '❌ Almost there!';
        score.style.color = 'var(--danger)';
        score.textContent = result.scorePercentage.toFixed(1) + '%';
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
        // Fetch Brother earnings
        const earningsRes = await fetch('/api/admin/students/brother/earnings', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (earningsRes.ok) {
            const data = await earningsRes.json();
            document.getElementById('admin-student-stats').textContent = 
                `Total Earned: $${data.totalEarned.toFixed(2)} | Paid Out: $${data.totalPaid.toFixed(2)} | Balance Owed: $${data.balanceOwed.toFixed(2)} (${data.passedEpisodesCount}/${data.totalEpisodesCount} Episodes Passed)`;
            document.getElementById('payout-amount').value = data.balanceOwed > 0 ? data.balanceOwed : '';
        }

        // Fetch Attempts
        const attemptsRes = await fetch('/api/admin/attempts', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (attemptsRes.ok) {
            const attempts = await attemptsRes.json();
            const tbody = document.getElementById('admin-attempts-tbody');
            tbody.innerHTML = '';
            attempts.forEach((a) => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${a.student.fullName || a.student.username}</td>
                    <td>Ep. ${a.episode.episodeNumber} - ${a.episode.title}</td>
                    <td>${a.watchMode}</td>
                    <td>${a.scorePercentage.toFixed(1)}%</td>
                    <td style="color:${a.passed ? '#10b981':'#ef4444'}; font-weight:700;">${a.passed ? 'PASSED':'FAILED'}</td>
                    <td style="color:#f59e0b; font-weight:700;">$${a.rewardAmount.toFixed(2)}</td>
                    <td>${new Date(a.attemptedAt).toLocaleString()}</td>
                `;
                tbody.appendChild(tr);
            });
        }
    } catch (e) {
        console.warn('Admin load failed:', e);
    }
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
            alert(`Successfully recorded payout of $${parseFloat(amount).toFixed(2)}!`);
            loadAdminDashboard();
        } else {
            alert('Failed to record payout.');
        }
    } catch (e) {
        alert('Error recording payout: ' + e.message);
    }
}
