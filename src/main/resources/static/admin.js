// Arabic Quest Admin Command Center Logic

let authToken = localStorage.getItem('aq_auth_token');
let userRole = localStorage.getItem('aq_role');
let username = localStorage.getItem('aq_username');
let lastOwedAmount = 0;
let episodes = [];
let allStudents = [];
let selectedStudent = null;

// Enforce Admin Auth
if (!authToken || userRole !== 'ROLE_ADMIN') {
    window.location.href = 'admin-login.html';
}

function getAuthHeader() {
    return 'Basic ' + authToken;
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('admin-user-display').textContent = '🛡️ ' + (username || 'Admin');
    initAdmin();
});

async function initAdmin() {
    await loadStudents();
    await loadAdminDashboard();
    await loadEpisodesForAi();
}

function handleLogout() {
    localStorage.removeItem('aq_auth_token');
    localStorage.removeItem('aq_role');
    localStorage.removeItem('aq_username');
    localStorage.removeItem('aq_fullname');
    window.location.href = 'admin-login.html';
}

// Load Students for Dropdown
async function loadStudents() {
    try {
        const res = await fetch('/api/admin/students', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            const users = await res.json();
            // Filter students or all users
            allStudents = users.filter(u => u.role === 'ROLE_STUDENT');
            if (allStudents.length === 0) {
                allStudents = users; // Fallback
            }

            const select = document.getElementById('admin-student-select');
            select.innerHTML = '';
            allStudents.forEach((s) => {
                const opt = document.createElement('option');
                opt.value = s.id;
                opt.textContent = `${s.fullName} (${s.username})`;
                select.appendChild(opt);
            });

            if (allStudents.length > 0) {
                selectedStudent = allStudents[0];
                updateSelectedStudentHeader();
            }
        }
    } catch (e) {
        console.warn('Failed to load students:', e);
    }
}

function handleStudentSelectChange() {
    const selId = parseInt(document.getElementById('admin-student-select').value, 10);
    selectedStudent = allStudents.find(s => s.id === selId) || allStudents[0];
    updateSelectedStudentHeader();
    loadAdminDashboard();
}

function updateSelectedStudentHeader() {
    if (!selectedStudent) return;
    document.getElementById('current-student-title').textContent = `${selectedStudent.fullName} (${selectedStudent.username})`;
    document.getElementById('payout-title-label').textContent = `Issue Payout to ${selectedStudent.fullName}`;
}

// Load Metrics & Attempts
async function loadAdminDashboard() {
    if (!selectedStudent) return;

    try {
        const earningsRes = await fetch(`/api/admin/students/${selectedStudent.username}/earnings`, {
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
        } else if (earningsRes.status === 401 || earningsRes.status === 403) {
            handleLogout();
            return;
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

// Load episodes for AI dropdown
async function loadEpisodesForAi() {
    try {
        const res = await fetch('/api/student/episodes', {
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            episodes = await res.json();
            const select = document.getElementById('ai-episode-select');
            select.innerHTML = '';
            episodes.forEach((ep) => {
                const opt = document.createElement('option');
                opt.value = ep.id;
                opt.textContent = `Ep. ${ep.episodeNumber}: ${ep.title}`;
                select.appendChild(opt);
            });
        }
    } catch (e) {
        console.warn('Failed to load episodes:', e);
    }
}

// Generate AI Quiz
async function handleGenerateAiQuiz() {
    const episodeId = document.getElementById('ai-episode-select').value;
    if (!episodeId) return;

    const btn = document.getElementById('btn-run-ai');
    const resultBox = document.getElementById('ai-result-box');
    const resultContent = document.getElementById('ai-result-content');

    btn.disabled = true;
    btn.innerHTML = '<span>⏳ Generating Quiz with AI...</span>';
    resultBox.style.display = 'none';

    try {
        const res = await fetch(`/api/admin/episodes/${episodeId}/generate-quiz`, {
            method: 'POST',
            headers: { 'Authorization': getAuthHeader() }
        });

        if (res.ok) {
            const quiz = await res.json();
            resultBox.style.display = 'block';
            let html = `<strong>Generated ${quiz.questions.length} Questions for Episode:</strong><ol style="margin-left:1.5rem; margin-top:0.5rem;">`;
            quiz.questions.forEach((q) => {
                html += `<li><strong>${q.questionText}</strong> (${q.options.length} options)</li>`;
            });
            html += `</ol>`;
            resultContent.innerHTML = html;
        } else {
            alert('Failed to generate quiz. Check server logs.');
        }
    } catch (e) {
        alert('AI generation error: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<span>✨ Generate AI Quiz Now</span>';
    }
}

// Add New Episode
async function handleAddEpisodeSubmit(e) {
    e.preventDefault();
    const epNum = parseInt(document.getElementById('new-ep-num').value, 10);
    const title = document.getElementById('new-ep-title').value.trim();
    const videoId = document.getElementById('new-ep-videoid').value.trim();

    try {
        const res = await fetch('/api/admin/episodes', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': getAuthHeader()
            },
            body: JSON.stringify({
                playlistId: 1,
                episodeNumber: epNum,
                title: title,
                youtubeVideoId: videoId
            })
        });

        if (res.ok) {
            alert(`✓ Successfully added Episode ${epNum}: "${title}"!`);
            document.getElementById('form-add-episode').reset();
            loadEpisodesForAi();
            loadAdminDashboard();
        } else {
            alert('Failed to add episode.');
        }
    } catch (err) {
        alert('Error: ' + err.message);
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
    if (!selectedStudent) return;
    const amount = document.getElementById('payout-amount').value;
    if (!amount || parseFloat(amount) <= 0) {
        alert('Please enter a valid payout amount.');
        return;
    }

    try {
        const res = await fetch(`/api/admin/payouts?studentId=${selectedStudent.id}&amount=${encodeURIComponent(amount)}&note=Transfer via Turathtona`, {
            method: 'POST',
            headers: { 'Authorization': getAuthHeader() }
        });
        if (res.ok) {
            alert(`✓ Successfully recorded payout of $${parseFloat(amount).toFixed(2)} to ${selectedStudent.fullName}!`);
            loadAdminDashboard();
        } else {
            alert('Failed to record payout.');
        }
    } catch (e) {
        alert('Error recording payout: ' + e.message);
    }
}
