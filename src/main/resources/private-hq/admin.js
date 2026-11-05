// Turathtona Secret Admin Command Center (Hardened Cookie/JWT + Zero-innerHTML XSS-Safe DOM)

const HQ_LOGIN_PATH = '{{HQ_LOGIN_PATH}}';

let currentAdmin = null;
let allStudents = [];
let selectedStudent = null;
let episodes = [];
let lastOwedAmount = 0;

// Purge any legacy plaintext credentials from older client versions
try {
    ['aq_auth_token', 'aq_username', 'aq_fullname', 'aq_role'].forEach((k) => localStorage.removeItem(k));
} catch (_) {}

function apiFetch(url, options = {}) {
    const headers = Object.assign({ 'X-Requested-With': 'XMLHttpRequest' }, options.headers || {});
    return fetch(url, Object.assign({}, options, {
        credentials: 'same-origin',
        headers
    }));
}

function showFeedback(elId, message, isError = false) {
    const el = document.getElementById(elId);
    if (!el) return;
    el.textContent = message;
    el.className = 'feedback-msg ' + (isError ? 'err' : 'ok');
}

function hideFeedback(elId) {
    const el = document.getElementById(elId);
    if (!el) return;
    el.className = 'feedback-msg';
    el.textContent = '';
}

function clearElement(el) {
    while (el.firstChild) {
        el.removeChild(el.firstChild);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    wireEvents();
    initAdmin();
});

function wireEvents() {
    document.getElementById('btn-logout').addEventListener('click', handleLogout);

    document.getElementById('btn-jump-password').addEventListener('click', () => {
        document.getElementById('password-security-card').scrollIntoView({ behavior: 'smooth' });
        document.getElementById('admin-current-pw').focus();
    });

    document.getElementById('admin-student-select').addEventListener('change', handleStudentSelectChange);

    document.getElementById('btn-toggle-reset-pw').addEventListener('click', () => {
        const box = document.getElementById('reset-student-pw-box');
        box.style.display = box.style.display === 'none' ? 'block' : 'none';
    });

    document.getElementById('form-reset-student-pw').addEventListener('submit', handleResetStudentPassword);

    document.querySelectorAll('.preset-btn[data-preset]').forEach((btn) => {
        btn.addEventListener('click', () => {
            document.getElementById('payout-amount').value = btn.getAttribute('data-preset');
        });
    });

    document.getElementById('btn-preset-full').addEventListener('click', () => {
        document.getElementById('payout-amount').value = lastOwedAmount > 0 ? Number(lastOwedAmount).toFixed(2) : '';
    });

    document.getElementById('btn-record-payout').addEventListener('click', handleRecordPayout);
    document.getElementById('form-create-student').addEventListener('submit', handleCreateStudentSubmit);
    document.getElementById('btn-run-ai').addEventListener('click', handleGenerateAiQuiz);
    document.getElementById('form-add-episode').addEventListener('submit', handleAddEpisodeSubmit);
    document.getElementById('btn-refresh-audit').addEventListener('click', loadAdminDashboard);
    document.getElementById('form-change-admin-pw').addEventListener('submit', handleChangeAdminPassword);
}

async function initAdmin() {
    try {
        const meRes = await apiFetch('/api/auth/me');
        if (!meRes.ok) {
            window.location.replace(HQ_LOGIN_PATH);
            return;
        }
        currentAdmin = await meRes.json();
        if (!currentAdmin || currentAdmin.role !== 'ROLE_ADMIN') {
            window.location.replace(HQ_LOGIN_PATH);
            return;
        }

        document.getElementById('admin-user-display').textContent = '🛡️ ' + (currentAdmin.username || 'Admin');

        if (currentAdmin.defaultPasswordWarning) {
            document.getElementById('default-password-banner').style.display = 'flex';
        }

        await Promise.all([
            loadStudents(),
            loadEpisodes()
        ]);
        await loadAdminDashboard();
    } catch (_) {
        window.location.replace(HQ_LOGIN_PATH);
    }
}

async function handleLogout() {
    try {
        await apiFetch('/api/auth/logout', { method: 'POST' });
    } catch (_) {}
    window.location.replace(HQ_LOGIN_PATH);
}

async function loadStudents(preferStudentId = null) {
    const res = await apiFetch('/api/admin/students');
    if (res.status === 401 || res.status === 403) {
        window.location.replace(HQ_LOGIN_PATH);
        return;
    }
    if (!res.ok) return;

    const users = await res.json();
    allStudents = users.filter((u) => u.role === 'ROLE_STUDENT');

    const select = document.getElementById('admin-student-select');
    clearElement(select);

    if (allStudents.length === 0) {
        selectedStudent = null;
        const emptyOpt = document.createElement('option');
        emptyOpt.value = '';
        emptyOpt.textContent = 'No students created yet';
        select.appendChild(emptyOpt);

        document.getElementById('current-student-title').textContent = 'No Student Accounts Yet';
        document.getElementById('payout-title-label').textContent = 'Issue Payout to Student';
        document.getElementById('admin-metric-earned').textContent = '$0.00';
        document.getElementById('admin-metric-paid').textContent = '$0.00';
        document.getElementById('admin-metric-owed').textContent = '$0.00';
        document.getElementById('admin-metric-episodes').textContent = '0 / ' + episodes.length;
        return;
    }

    allStudents.forEach((s) => {
        const opt = document.createElement('option');
        opt.value = String(s.id);
        opt.textContent = `${s.fullName} (${s.username})`;
        select.appendChild(opt);
    });

    const targetId = preferStudentId || (selectedStudent ? selectedStudent.id : allStudents[0].id);
    selectedStudent = allStudents.find((s) => s.id === targetId) || allStudents[0];
    select.value = String(selectedStudent.id);
    updateSelectedStudentHeader();
}

function handleStudentSelectChange() {
    const selId = parseInt(document.getElementById('admin-student-select').value, 10);
    selectedStudent = allStudents.find((s) => s.id === selId) || null;
    updateSelectedStudentHeader();
    loadAdminDashboard();
}

function updateSelectedStudentHeader() {
    if (!selectedStudent) return;
    document.getElementById('current-student-title').textContent = `${selectedStudent.fullName} (${selectedStudent.username})`;
    document.getElementById('payout-title-label').textContent = `Issue Payout to ${selectedStudent.fullName}`;
}

async function loadAdminDashboard() {
    if (selectedStudent) {
        try {
            const earningsRes = await apiFetch(`/api/admin/students/${encodeURIComponent(selectedStudent.username)}/earnings`);
            if (earningsRes.status === 401 || earningsRes.status === 403) {
                window.location.replace(HQ_LOGIN_PATH);
                return;
            }
            if (earningsRes.ok) {
                const data = await earningsRes.json();
                lastOwedAmount = Number(data.balanceOwed || 0);
                document.getElementById('admin-metric-earned').textContent = '$' + Number(data.totalEarned || 0).toFixed(2);
                document.getElementById('admin-metric-paid').textContent = '$' + Number(data.totalPaid || 0).toFixed(2);
                document.getElementById('admin-metric-owed').textContent = '$' + lastOwedAmount.toFixed(2);
                document.getElementById('admin-metric-episodes').textContent = `${data.passedEpisodesCount} / ${data.totalEpisodesCount}`;
                document.getElementById('payout-amount').value = lastOwedAmount > 0 ? lastOwedAmount.toFixed(2) : '';
            }
        } catch (e) {
            console.warn('Earnings load error:', e);
        }
    }

    await Promise.all([
        loadPayoutsLedger(),
        loadAttemptsAuditLog()
    ]);
}

async function loadPayoutsLedger() {
    try {
        const res = await apiFetch('/api/admin/payouts');
        if (!res.ok) return;
        const payouts = await res.json();
        const tbody = document.getElementById('admin-payouts-tbody');
        clearElement(tbody);

        if (!payouts || payouts.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 4;
            td.style.textAlign = 'center';
            td.style.color = 'var(--text-muted)';
            td.style.padding = '1.5rem';
            td.textContent = 'No payouts recorded yet.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        payouts.forEach((p) => {
            const tr = document.createElement('tr');

            const tdStudent = document.createElement('td');
            const strongName = document.createElement('strong');
            strongName.textContent = p.studentFullName || p.studentUsername;
            tdStudent.appendChild(strongName);

            const tdAmount = document.createElement('td');
            tdAmount.style.color = '#10b981';
            tdAmount.style.fontWeight = '800';
            tdAmount.textContent = '$' + Number(p.amount || 0).toFixed(2);

            const tdNote = document.createElement('td');
            tdNote.textContent = p.note || 'Payout';

            const tdDate = document.createElement('td');
            tdDate.style.color = 'var(--text-muted)';
            tdDate.style.fontSize = '0.82rem';
            tdDate.textContent = p.paidAt ? new Date(p.paidAt).toLocaleString() : (p.createdAt ? new Date(p.createdAt).toLocaleString() : '');

            tr.appendChild(tdStudent);
            tr.appendChild(tdAmount);
            tr.appendChild(tdNote);
            tr.appendChild(tdDate);
            tbody.appendChild(tr);
        });
    } catch (e) {
        console.warn('Payouts ledger load error:', e);
    }
}

async function loadAttemptsAuditLog() {
    try {
        const res = await apiFetch('/api/admin/attempts');
        if (!res.ok) return;
        const attempts = await res.json();
        const tbody = document.getElementById('admin-attempts-tbody');
        clearElement(tbody);

        if (!attempts || attempts.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 7;
            td.style.textAlign = 'center';
            td.style.color = 'var(--text-muted)';
            td.style.padding = '2rem';
            td.textContent = 'No quiz attempts logged yet.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        attempts.forEach((a) => {
            const tr = document.createElement('tr');

            const tdStudent = document.createElement('td');
            const strongStudent = document.createElement('strong');
            strongStudent.textContent = a.studentFullName || a.studentUsername;
            tdStudent.appendChild(strongStudent);

            const tdEp = document.createElement('td');
            tdEp.style.fontFamily = "'Cairo', sans-serif";
            tdEp.textContent = `Ep. ${a.episodeNumber}: ${a.episodeTitle}`;

            const tdMode = document.createElement('td');
            const modeSpan = document.createElement('span');
            modeSpan.style.fontSize = '0.8rem';
            modeSpan.style.background = 'rgba(255,255,255,0.06)';
            modeSpan.style.padding = '0.2rem 0.5rem';
            modeSpan.style.borderRadius = '4px';
            modeSpan.textContent = a.watchMode;
            tdMode.appendChild(modeSpan);

            const tdScore = document.createElement('td');
            const strongScore = document.createElement('strong');
            strongScore.textContent = `${Number(a.scorePercentage || 0).toFixed(0)}%`;
            tdScore.appendChild(strongScore);

            const tdStatus = document.createElement('td');
            const statusSpan = document.createElement('span');
            statusSpan.style.color = a.passed ? '#10b981' : '#ef4444';
            statusSpan.style.fontWeight = '800';
            statusSpan.style.fontSize = '0.82rem';
            statusSpan.textContent = a.passed ? '✓ PASSED' : '✗ FAILED';
            tdStatus.appendChild(statusSpan);

            const tdReward = document.createElement('td');
            tdReward.style.color = '#f59e0b';
            tdReward.style.fontWeight = '800';
            tdReward.textContent = '+$' + Number(a.rewardAmount || 0).toFixed(2);

            const tdTime = document.createElement('td');
            tdTime.style.color = 'var(--text-muted)';
            tdTime.style.fontSize = '0.82rem';
            tdTime.textContent = a.attemptedAt ? new Date(a.attemptedAt).toLocaleString() : '';

            tr.appendChild(tdStudent);
            tr.appendChild(tdEp);
            tr.appendChild(tdMode);
            tr.appendChild(tdScore);
            tr.appendChild(tdStatus);
            tr.appendChild(tdReward);
            tr.appendChild(tdTime);
            tbody.appendChild(tr);
        });
    } catch (e) {
        console.warn('Attempts audit load error:', e);
    }
}

async function loadEpisodes() {
    try {
        const res = await apiFetch('/api/admin/episodes');
        if (!res.ok) return;
        episodes = await res.json();

        // Populate AI dropdown
        const select = document.getElementById('ai-episode-select');
        clearElement(select);
        episodes.forEach((ep) => {
            const opt = document.createElement('option');
            opt.value = String(ep.id);
            opt.textContent = `Ep. ${ep.episodeNumber}: ${ep.title}`;
            select.appendChild(opt);
        });

        // Populate active episodes management table
        const tbody = document.getElementById('admin-episodes-tbody');
        clearElement(tbody);
        episodes.forEach((ep) => {
            const tr = document.createElement('tr');

            const tdNum = document.createElement('td');
            const strongNum = document.createElement('strong');
            strongNum.textContent = `#${ep.episodeNumber}`;
            tdNum.appendChild(strongNum);

            const tdTitle = document.createElement('td');
            tdTitle.style.fontFamily = "'Cairo', sans-serif";
            tdTitle.textContent = ep.title;

            const tdVid = document.createElement('td');
            const codeEl = document.createElement('code');
            codeEl.textContent = ep.youtubeVideoId;
            tdVid.appendChild(codeEl);

            const tdAction = document.createElement('td');
            const delBtn = document.createElement('button');
            delBtn.type = 'button';
            delBtn.className = 'btn-danger-outline';
            delBtn.textContent = 'Archive / Remove';
            delBtn.addEventListener('click', () => handleDeleteEpisode(ep));
            tdAction.appendChild(delBtn);

            tr.appendChild(tdNum);
            tr.appendChild(tdTitle);
            tr.appendChild(tdVid);
            tr.appendChild(tdAction);
            tbody.appendChild(tr);
        });
    } catch (e) {
        console.warn('Failed to load episodes:', e);
    }
}

async function handleRecordPayout() {
    hideFeedback('payout-feedback-msg');
    if (!selectedStudent) {
        showFeedback('payout-feedback-msg', 'Create and select a student account first.', true);
        return;
    }

    const rawAmount = document.getElementById('payout-amount').value;
    const parsedAmount = parseFloat(rawAmount);
    if (!rawAmount || Number.isNaN(parsedAmount) || parsedAmount <= 0) {
        showFeedback('payout-feedback-msg', 'Please enter a positive payout amount greater than $0.00.', true);
        return;
    }

    const note = document.getElementById('payout-note').value.trim() || 'Transfer via Turathtona';
    const btn = document.getElementById('btn-record-payout');
    btn.disabled = true;

    try {
        const res = await apiFetch('/api/admin/payouts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                studentId: selectedStudent.id,
                amount: Number(parsedAmount.toFixed(2)),
                note
            })
        });

        if (res.ok) {
            document.getElementById('payout-note').value = '';
            showFeedback('payout-feedback-msg', `✓ Recorded $${parsedAmount.toFixed(2)} payout to ${selectedStudent.fullName}.`);
            await loadAdminDashboard();
        } else {
            const err = await res.json().catch(() => ({}));
            showFeedback('payout-feedback-msg', err.error || 'Failed to record payout.', true);
        }
    } catch (e) {
        showFeedback('payout-feedback-msg', 'Connection error: ' + e.message, true);
    } finally {
        btn.disabled = false;
    }
}

async function handleCreateStudentSubmit(e) {
    e.preventDefault();
    hideFeedback('create-student-msg');

    const fullName = document.getElementById('new-student-fullname').value.trim();
    const username = document.getElementById('new-student-username').value.trim();
    const password = document.getElementById('new-student-password').value;
    const btn = document.getElementById('btn-create-student');

    btn.disabled = true;
    btn.textContent = 'Creating Account...';

    try {
        const res = await apiFetch('/api/admin/students', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fullName, username, password })
        });

        if (res.ok) {
            const created = await res.json();
            showFeedback('create-student-msg', `✓ Student account for "${created.fullName}" (${created.username}) created!`);
            document.getElementById('form-create-student').reset();
            await loadStudents(created.id);
            await loadAdminDashboard();
        } else {
            const err = await res.json().catch(() => ({}));
            showFeedback('create-student-msg', err.error || 'Failed to create student account.', true);
        }
    } catch (err) {
        showFeedback('create-student-msg', 'Connection error: ' + err.message, true);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Create Student Account ➔';
    }
}

async function handleResetStudentPassword(e) {
    e.preventDefault();
    hideFeedback('reset-student-pw-msg');
    if (!selectedStudent) {
        showFeedback('reset-student-pw-msg', 'Select a student first.', true);
        return;
    }

    const newPassword = document.getElementById('reset-student-newpw').value;
    try {
        const res = await apiFetch(`/api/admin/students/${selectedStudent.id}/reset-password`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ newPassword })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            document.getElementById('form-reset-student-pw').reset();
            showFeedback('reset-student-pw-msg', '✓ ' + (data.message || 'Student password updated.'));
        } else {
            showFeedback('reset-student-pw-msg', data.error || 'Failed to reset password.', true);
        }
    } catch (err) {
        showFeedback('reset-student-pw-msg', 'Connection error: ' + err.message, true);
    }
}

async function handleGenerateAiQuiz() {
    const episodeId = document.getElementById('ai-episode-select').value;
    if (!episodeId) return;

    const btn = document.getElementById('btn-run-ai');
    const resultBox = document.getElementById('ai-result-box');
    const resultTitle = document.getElementById('ai-result-title');
    const resultContent = document.getElementById('ai-result-content');

    btn.disabled = true;
    btn.textContent = '⏳ Generating Quiz with Gemini AI...';
    resultBox.style.display = 'none';
    clearElement(resultContent);

    try {
        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/generate-quiz`, {
            method: 'POST'
        });

        if (res.ok) {
            const quiz = await res.json();
            resultTitle.textContent = `✓ Generated ${quiz.questions.length} Shuffled Questions (Passing Score: ${quiz.passingScorePercentage}%)`;

            const ol = document.createElement('ol');
            ol.style.marginLeft = '1.5rem';
            ol.style.marginTop = '0.5rem';

            quiz.questions.forEach((q) => {
                const li = document.createElement('li');
                li.style.marginBottom = '0.65rem';

                const qStrong = document.createElement('strong');
                qStrong.style.color = '#f8fafc';
                qStrong.textContent = q.questionText;
                li.appendChild(qStrong);

                const ul = document.createElement('ul');
                ul.style.marginLeft = '1.25rem';
                ul.style.marginTop = '0.25rem';
                (q.options || []).forEach((opt) => {
                    const optLi = document.createElement('li');
                    optLi.style.fontSize = '0.84rem';
                    optLi.style.color = opt.isCorrect ? '#6ee7b7' : 'var(--text-muted)';
                    optLi.textContent = (opt.isCorrect ? '✓ ' : '• ') + opt.optionText;
                    ul.appendChild(optLi);
                });
                li.appendChild(ul);
                ol.appendChild(li);
            });

            resultContent.appendChild(ol);
            resultBox.style.display = 'block';
        } else {
            const err = await res.json().catch(() => ({}));
            resultTitle.textContent = 'AI Quiz Generation Failed';
            resultContent.textContent = err.error || 'Could not generate quiz. Verify GEMINI_API_KEY configuration.';
            resultBox.style.display = 'block';
        }
    } catch (e) {
        resultTitle.textContent = 'Connection Error';
        resultContent.textContent = e.message;
        resultBox.style.display = 'block';
    } finally {
        btn.disabled = false;
        btn.textContent = '✨ Generate AI Quiz Now';
    }
}

async function handleAddEpisodeSubmit(e) {
    e.preventDefault();
    hideFeedback('add-episode-msg');

    const episodeNumber = parseInt(document.getElementById('new-ep-num').value, 10);
    const title = document.getElementById('new-ep-title').value.trim();
    const youtubeVideoId = document.getElementById('new-ep-videoid').value.trim();
    const btn = document.getElementById('btn-add-episode');

    btn.disabled = true;

    try {
        const res = await apiFetch('/api/admin/episodes', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ episodeNumber, title, youtubeVideoId })
        });

        if (res.ok) {
            showFeedback('add-episode-msg', `✓ Added Episode #${episodeNumber}: ${title}`);
            document.getElementById('form-add-episode').reset();
            await loadEpisodes();
            await loadAdminDashboard();
        } else {
            const err = await res.json().catch(() => ({}));
            showFeedback('add-episode-msg', err.error || 'Failed to add episode.', true);
        }
    } catch (err) {
        showFeedback('add-episode-msg', 'Connection error: ' + err.message, true);
    } finally {
        btn.disabled = false;
    }
}

async function handleDeleteEpisode(ep) {
    if (!window.confirm(`Archive / remove Episode #${ep.episodeNumber} ("${ep.title}") from active course? Existing student earnings will be preserved.`)) {
        return;
    }

    try {
        const res = await apiFetch(`/api/admin/episodes/${ep.id}`, {
            method: 'DELETE'
        });
        if (res.ok) {
            showFeedback('add-episode-msg', `✓ Episode #${ep.episodeNumber} archived/removed.`);
            await loadEpisodes();
            await loadAdminDashboard();
        } else {
            const err = await res.json().catch(() => ({}));
            showFeedback('add-episode-msg', err.error || 'Failed to remove episode.', true);
        }
    } catch (err) {
        showFeedback('add-episode-msg', 'Connection error: ' + err.message, true);
    }
}

async function handleChangeAdminPassword(e) {
    e.preventDefault();
    hideFeedback('change-admin-pw-msg');

    const currentPassword = document.getElementById('admin-current-pw').value;
    const newPassword = document.getElementById('admin-new-pw').value;
    const confirmPassword = document.getElementById('admin-confirm-pw').value;

    if (newPassword !== confirmPassword) {
        showFeedback('change-admin-pw-msg', 'New password and confirmation do not match.', true);
        return;
    }

    const btn = document.getElementById('btn-change-admin-pw');
    btn.disabled = true;

    try {
        const res = await apiFetch('/api/auth/change-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ currentPassword, newPassword })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            document.getElementById('form-change-admin-pw').reset();
            document.getElementById('default-password-banner').style.display = 'none';
            showFeedback('change-admin-pw-msg', '✓ ' + (data.message || 'Master password updated!'));
        } else {
            showFeedback('change-admin-pw-msg', data.error || 'Failed to update password.', true);
        }
    } catch (err) {
        showFeedback('change-admin-pw-msg', 'Connection error: ' + err.message, true);
    } finally {
        btn.disabled = false;
    }
}
