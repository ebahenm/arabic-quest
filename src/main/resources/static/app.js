// Arabic Quest Student Application Logic

let authToken = localStorage.getItem('aq_auth_token');
let currentMode = 'ARABIC_SUB';
let currentEpisode = null;
let episodes = [];
let currentQuiz = null;
let ytPlayer = null;

// Auth check: If not logged in, redirect to Student Login
if (!authToken) {
    window.location.href = 'login.html';
}

function getAuthHeader() {
    return 'Basic ' + authToken;
}

document.addEventListener('DOMContentLoaded', () => {
    const studentName = localStorage.getItem('aq_fullname') || localStorage.getItem('aq_username') || 'Student';
    const displayEl = document.getElementById('student-name-display');
    if (displayEl) displayEl.textContent = '🎓 ' + studentName;

    initApp();
});

function initApp() {
    fetchEarnings();
    fetchEpisodes();
}

function handleStudentLogout() {
    localStorage.removeItem('aq_auth_token');
    localStorage.removeItem('aq_role');
    localStorage.removeItem('aq_username');
    localStorage.removeItem('aq_fullname');
    window.location.href = 'login.html';
}

// Fetch Student Earnings & Update Progress Bar
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
            
            const total = data.totalEpisodesCount > 0 ? data.totalEpisodesCount : 1;
            const percent = Math.min(100, Math.round((data.passedEpisodesCount / total) * 100));
            
            document.getElementById('badge-course-count').textContent = `${data.passedEpisodesCount}/${data.totalEpisodesCount}`;
            document.getElementById('progress-percent-label').textContent = `${percent}%`;
            document.getElementById('progress-bar-fill').style.width = `${percent}%`;
        } else if (res.status === 401) {
            handleStudentLogout();
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

    // Reset Quiz UI
    document.getElementById('quiz-section').style.display = 'none';

    // Update Watch on YouTube External Link
    const ytExternalLink = document.getElementById('btn-yt-external-link');
    if (ytExternalLink) {
        ytExternalLink.href = `https://www.youtube.com/watch?v=${ep.youtubeVideoId}&list=PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB`;
    }

    // Load in YouTube embedded player with CC policy
    if (ytPlayer && ytPlayer.loadVideoById) {
        ytPlayer.loadVideoById({
            videoId: ep.youtubeVideoId,
            suggestedQuality: 'hd720'
        });
    }

    // Fetch Vocabulary Guide & Quiz
    fetchVocabGuide(ep.id);
    fetchQuiz(ep.id);
}

// Fetch Vocabulary Guide for Episode
async function fetchVocabGuide(episodeId) {
    const container = document.getElementById('vocab-grid');
    if (!container) return;
    container.innerHTML = '<p style="color:var(--text-muted); font-size:0.85rem;">Loading vocabulary study guide...</p>';

    try {
        const res = await fetch(`/api/student/episodes/${episodeId}/vocab-guide`, {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            const words = await res.json();
            container.innerHTML = '';
            words.forEach((w) => {
                const card = document.createElement('div');
                card.className = 'vocab-card';
                card.innerHTML = `
                    <div class="vocab-arabic">${w.arabic}</div>
                    <div class="vocab-translit">${w.translit}</div>
                    <div class="vocab-english">${w.english}</div>
                    <div class="vocab-note">${w.note}</div>
                `;
                container.appendChild(card);
            });
        }
    } catch (e) {
        console.warn('Vocab fetch failed:', e);
    }
}

// Fetch Quiz (Auto-generates with AI on backend if missing)
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

// Regenerate Quiz with AI on Demand
async function regenerateQuizWithAi() {
    if (!currentEpisode) return;
    const container = document.getElementById('questions-container');
    container.innerHTML = '<p style="color:#c4b5fd; font-size:0.9rem; padding:1.5rem; text-align:center;">🤖 AI is analyzing the episode and generating new questions...</p>';

    try {
        const res = await fetch(`/api/student/quizzes/generate/${currentEpisode.id}`, {
            method: 'POST',
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            currentQuiz = await res.json();
            renderQuizQuestions();
        } else {
            alert('Failed to regenerate quiz.');
        }
    } catch (e) {
        alert('Error: ' + e.message);
    }
}

// Render Quiz Questions
function renderQuizQuestions() {
    const container = document.getElementById('questions-container');
    container.innerHTML = '';

    if (!currentQuiz || !currentQuiz.questions || currentQuiz.questions.length === 0) {
        container.innerHTML = '<p style="color:var(--text-muted); padding:1rem;">No quiz ready. Click [AI Regenerate Quiz] to generate one.</p>';
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
            'modestbranding': 1,
            'cc_load_policy': 1, // Enable closed captions / subtitles automatically
            'hl': 'en'
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
    const quizSection = document.getElementById('quiz-section');
    quizSection.style.display = 'block';
    quizSection.scrollIntoView({ behavior: 'smooth' });

    // Little celebration for finishing video
    if (typeof confetti === 'function') {
        confetti({
            particleCount: 50,
            spread: 50,
            origin: { y: 0.7 }
        });
    }
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

        // Big Confetti Celebration
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
