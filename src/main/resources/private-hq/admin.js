// Turathtona Secret Admin Command Center — Organized 5-Tab Interface (Hardened Cookie/JWT + XSS-Safe DOM)

const HQ_LOGIN_PATH = '{{HQ_LOGIN_PATH}}';

let currentAdmin = null;
let allStudents = [];
let selectedStudent = null;
let episodes = [];
let cachedAttempts = [];
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
    if (!el) return;
    while (el.firstChild) {
        el.removeChild(el.firstChild);
    }
}

function switchAdminTab(tabKey) {
    document.querySelectorAll('.admin-tab-btn').forEach((btn) => {
        btn.classList.toggle('active', btn.getAttribute('data-tab') === tabKey);
    });
    document.querySelectorAll('.admin-tab-panel').forEach((panel) => {
        panel.classList.toggle('active', panel.id === `tab-panel-${tabKey}`);
    });
}

document.addEventListener('DOMContentLoaded', () => {
    wireEvents();
    initAdmin();
});

function wireEvents() {
    document.getElementById('btn-logout').addEventListener('click', handleLogout);

    // Tab navigation buttons
    document.querySelectorAll('.admin-tab-btn').forEach((btn) => {
        btn.addEventListener('click', () => {
            switchAdminTab(btn.getAttribute('data-tab'));
        });
    });

    document.getElementById('btn-jump-password').addEventListener('click', () => {
        switchAdminTab('settings');
        const el = document.getElementById('admin-current-pw');
        if (el) el.focus();
    });

    document.getElementById('btn-jump-edit-student').addEventListener('click', () => {
        switchAdminTab('students');
        if (selectedStudent) {
            populateEditStudentForm(selectedStudent);
            document.getElementById('reset-student-pw-box').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    });

    document.getElementById('admin-student-select').addEventListener('change', handleStudentSelectChange);
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
    document.getElementById('audit-student-filter').addEventListener('change', renderAttemptsTable);
    document.getElementById('form-change-admin-pw').addEventListener('submit', handleChangeAdminPassword);

    const exportBtn = document.getElementById('btn-export-qbank');
    if (exportBtn) exportBtn.addEventListener('click', handleExportQuestionBank);
    const importBtn = document.getElementById('btn-import-qbank');
    if (importBtn) importBtn.addEventListener('click', handleImportQuestionBank);
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
        const adminUsernameInput = document.getElementById('admin-new-username');
        if (adminUsernameInput) {
            adminUsernameInput.value = currentAdmin.username || '';
        }

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

    const countBadge = document.getElementById('tab-badge-students');
    if (countBadge) countBadge.textContent = String(allStudents.length);

    renderStudentsDirectoryTable();
    populateAuditFilterDropdown();

    const select = document.getElementById('admin-student-select');
    clearElement(select);

    if (allStudents.length === 0) {
        selectedStudent = null;
        const emptyOpt = document.createElement('option');
        emptyOpt.value = '';
        emptyOpt.textContent = 'No students created yet';
        select.appendChild(emptyOpt);

        document.getElementById('current-student-title').textContent = 'No Student Accounts Yet';
        document.getElementById('payout-title-label').textContent = '💸 Issue Payout to Student';
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

function renderStudentsDirectoryTable() {
    const tbody = document.getElementById('admin-students-tbody');
    clearElement(tbody);

    if (!allStudents || allStudents.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 5;
        td.style.textAlign = 'center';
        td.style.color = 'var(--text-muted)';
        td.style.padding = '1.5rem';
        td.textContent = 'No student accounts registered yet. Create your first student above!';
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    allStudents.forEach((s) => {
        const tr = document.createElement('tr');

        const tdName = document.createElement('td');
        const strong = document.createElement('strong');
        strong.textContent = s.fullName;
        tdName.appendChild(strong);

        const tdUser = document.createElement('td');
        const code = document.createElement('code');
        code.textContent = s.username;
        tdUser.appendChild(code);

        const tdAccess = document.createElement('td');
        const badge = document.createElement('span');
        badge.style.fontSize = '0.76rem';
        badge.style.fontWeight = '700';
        badge.style.padding = '0.2rem 0.55rem';
        badge.style.borderRadius = '999px';
        if (s.betaTester) {
            badge.style.background = 'rgba(139, 92, 246, 0.22)';
            badge.style.color = '#ddd6fe';
            badge.textContent = '🧪 Student + Beta Access';
        } else {
            badge.style.background = 'rgba(16, 185, 129, 0.16)';
            badge.style.color = '#6ee7b7';
            badge.textContent = '🎓 Standard Student';
        }
        tdAccess.appendChild(badge);

        const tdCreated = document.createElement('td');
        tdCreated.style.color = 'var(--text-muted)';
        tdCreated.style.fontSize = '0.82rem';
        tdCreated.textContent = s.createdAt ? new Date(s.createdAt).toLocaleDateString() : '—';

        const tdActions = document.createElement('td');
        tdActions.style.display = 'flex';
        tdActions.style.gap = '0.5rem';

        const editBtn = document.createElement('button');
        editBtn.type = 'button';
        editBtn.className = 'btn-ghost';
        editBtn.style.padding = '0.32rem 0.65rem';
        editBtn.style.fontSize = '0.78rem';
        editBtn.textContent = '✏️ Edit';
        editBtn.addEventListener('click', () => {
            selectedStudent = s;
            const sel = document.getElementById('admin-student-select');
            if (sel) sel.value = String(s.id);
            updateSelectedStudentHeader();
            populateEditStudentForm(s);
            document.getElementById('reset-student-pw-box').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        });

        const ledgerBtn = document.createElement('button');
        ledgerBtn.type = 'button';
        ledgerBtn.className = 'btn-ghost';
        ledgerBtn.style.padding = '0.32rem 0.65rem';
        ledgerBtn.style.fontSize = '0.78rem';
        ledgerBtn.textContent = '💸 Ledger';
        ledgerBtn.addEventListener('click', () => {
            selectedStudent = s;
            const sel = document.getElementById('admin-student-select');
            if (sel) sel.value = String(s.id);
            updateSelectedStudentHeader();
            loadAdminDashboard();
            switchAdminTab('overview');
        });

        tdActions.appendChild(editBtn);
        tdActions.appendChild(ledgerBtn);

        tr.appendChild(tdName);
        tr.appendChild(tdUser);
        tr.appendChild(tdAccess);
        tr.appendChild(tdCreated);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);
    });
}

function populateAuditFilterDropdown() {
    const filter = document.getElementById('audit-student-filter');
    if (!filter) return;
    const prev = filter.value || 'ALL';
    clearElement(filter);

    const allOpt = document.createElement('option');
    allOpt.value = 'ALL';
    allOpt.textContent = 'All Students';
    filter.appendChild(allOpt);

    allStudents.forEach((s) => {
        const opt = document.createElement('option');
        opt.value = s.username;
        opt.textContent = `${s.fullName} (${s.username})`;
        filter.appendChild(opt);
    });

    if ([...filter.options].some((o) => o.value === prev)) {
        filter.value = prev;
    }
}

function populateEditStudentForm(student) {
    if (!student) return;
    document.getElementById('editing-student-indicator').textContent = `${student.fullName} (${student.username})`;
    document.getElementById('edit-student-username').value = student.username || '';
    document.getElementById('edit-student-fullname').value = student.fullName || '';
    document.getElementById('reset-student-newpw').value = '';
    const betaCheckbox = document.getElementById('edit-student-beta');
    if (betaCheckbox) betaCheckbox.checked = Boolean(student.betaTester);
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
    document.getElementById('payout-title-label').textContent = `💸 Issue Payout to ${selectedStudent.fullName}`;
    populateEditStudentForm(selectedStudent);
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
        cachedAttempts = await res.json() || [];
        renderAttemptsTable();
    } catch (e) {
        console.warn('Attempts audit load error:', e);
    }
}

function renderAttemptsTable() {
    const tbody = document.getElementById('admin-attempts-tbody');
    clearElement(tbody);

    const filterVal = (document.getElementById('audit-student-filter') || {}).value || 'ALL';
    const filtered = filterVal === 'ALL'
        ? cachedAttempts
        : cachedAttempts.filter((a) => a.studentUsername === filterVal);

    if (!filtered || filtered.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 6;
        td.style.textAlign = 'center';
        td.style.color = 'var(--text-muted)';
        td.style.padding = '2rem';
        td.textContent = 'No quiz attempts logged for this selection yet.';
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    filtered.forEach((a) => {
        const tr = document.createElement('tr');

        const tdStudent = document.createElement('td');
        const strongStudent = document.createElement('strong');
        strongStudent.textContent = a.studentFullName || a.studentUsername;
        tdStudent.appendChild(strongStudent);

        const tdEp = document.createElement('td');
        tdEp.style.fontFamily = "'Cairo', sans-serif";
        tdEp.textContent = `Ep. ${a.episodeNumber}: ${a.episodeTitle}`;

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
        tr.appendChild(tdScore);
        tr.appendChild(tdStatus);
        tr.appendChild(tdReward);
        tr.appendChild(tdTime);
        tbody.appendChild(tr);
    });
}

async function loadEpisodes() {
    try {
        const res = await apiFetch('/api/admin/episodes');
        if (!res.ok) return;
        episodes = await res.json();

        const epCountBadge = document.getElementById('tab-badge-episodes');
        if (epCountBadge) epCountBadge.textContent = String(episodes.length);

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

            const tdLen = document.createElement('td');
            tdLen.style.color = 'var(--text-muted)';
            tdLen.style.fontSize = '0.82rem';
            const mins = Math.round((ep.durationSeconds || 1200) / 60);
            tdLen.textContent = `~${mins} min`;

            const tdVid = document.createElement('td');
            const ytLink = document.createElement('a');
            ytLink.href = `https://www.youtube.com/watch?v=${encodeURIComponent(ep.youtubeVideoId)}`;
            ytLink.target = '_blank';
            ytLink.rel = 'noopener noreferrer';
            ytLink.style.color = '#93c5fd';
            ytLink.textContent = ep.youtubeVideoId + ' ↗';
            tdVid.appendChild(ytLink);

            const tdAction = document.createElement('td');
            const delBtn = document.createElement('button');
            delBtn.type = 'button';
            delBtn.className = 'btn-danger-outline';
            delBtn.textContent = 'Archive / Remove';
            delBtn.addEventListener('click', () => handleDeleteEpisode(ep));
            tdAction.appendChild(delBtn);

            tr.appendChild(tdNum);
            tr.appendChild(tdTitle);
            tr.appendChild(tdLen);
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

    const newUsername = document.getElementById('edit-student-username').value.trim();
    const fullName = document.getElementById('edit-student-fullname').value.trim();
    const newPassword = document.getElementById('reset-student-newpw').value;
    const betaTester = Boolean(document.getElementById('edit-student-beta').checked);

    if (newPassword && newPassword.length < 8) {
        showFeedback('reset-student-pw-msg', 'New password must be at least 8 characters.', true);
        return;
    }

    try {
        const res = await apiFetch(`/api/admin/students/${selectedStudent.id}/update-account`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ newUsername, fullName, newPassword, betaTester })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            document.getElementById('reset-student-newpw').value = '';
            showFeedback('reset-student-pw-msg', `✓ Saved changes for ${data.fullName} (${data.username}).`);
            await loadStudents(data.id);
            await loadAdminDashboard();
        } else {
            showFeedback('reset-student-pw-msg', data.error || 'Failed to update student account.', true);
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

    const newUsername = document.getElementById('admin-new-username').value.trim();
    const currentPassword = document.getElementById('admin-current-pw').value;
    const newPassword = document.getElementById('admin-new-pw').value;
    const confirmPassword = document.getElementById('admin-confirm-pw').value;

    if (newPassword && newPassword !== confirmPassword) {
        showFeedback('change-admin-pw-msg', 'New password and confirmation do not match.', true);
        return;
    }

    const btn = document.getElementById('btn-change-admin-pw');
    btn.disabled = true;

    try {
        const res = await apiFetch('/api/auth/update-credentials', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ currentPassword, newUsername, newPassword })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            if (data.username) {
                currentAdmin.username = data.username;
                document.getElementById('admin-user-display').textContent = '🛡️ ' + data.username;
                document.getElementById('admin-new-username').value = data.username;
            }
            document.getElementById('admin-current-pw').value = '';
            document.getElementById('admin-new-pw').value = '';
            document.getElementById('admin-confirm-pw').value = '';
            if (newPassword) {
                document.getElementById('default-password-banner').style.display = 'none';
            }
            showFeedback('change-admin-pw-msg', `✓ Admin login updated! Current username: "${currentAdmin.username}".`);
        } else {
            showFeedback('change-admin-pw-msg', data.error || 'Failed to update admin login.', true);
        }
    } catch (err) {
        showFeedback('change-admin-pw-msg', 'Connection error: ' + err.message, true);
    } finally {
        btn.disabled = false;
    }
}

async function handleExportQuestionBank() {
    hideFeedback('qbank-backup-msg');
    try {
        const res = await apiFetch('/api/admin/question-bank/export');
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            showFeedback('qbank-backup-msg', err.error || 'Export failed.', true);
            return;
        }
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'turathtona-question-bank.private.json';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        showFeedback('qbank-backup-msg', '✓ Question bank exported to turathtona-question-bank.private.json');
    } catch (err) {
        showFeedback('qbank-backup-msg', 'Export error: ' + err.message, true);
    }
}

async function handleImportQuestionBank() {
    hideFeedback('qbank-backup-msg');
    const fileInput = document.getElementById('input-import-qbank-file');
    if (!fileInput || !fileInput.files || fileInput.files.length === 0) {
        showFeedback('qbank-backup-msg', 'Please select a JSON backup file first.', true);
        return;
    }
    const file = fileInput.files[0];
    const btn = document.getElementById('btn-import-qbank');
    btn.disabled = true;
    try {
        const rawText = await file.text();
        let parsed;
        try {
            parsed = JSON.parse(rawText);
        } catch (_) {
            showFeedback('qbank-backup-msg', 'Selected file is not valid JSON.', true);
            return;
        }
        const res = await apiFetch('/api/admin/question-bank/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(parsed)
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            fileInput.value = '';
            showFeedback('qbank-backup-msg',
                `✓ Imported ${data.coursesImported || 0} course(s), ${data.episodesImported || 0} episode(s), and ${data.questionsImported || 0} question(s).`);
            await loadEpisodes();
            await loadAdminDashboard();
        } else {
            showFeedback('qbank-backup-msg', data.error || 'Import failed.', true);
        }
    } catch (err) {
        showFeedback('qbank-backup-msg', 'Import error: ' + err.message, true);
    } finally {
        btn.disabled = false;
    }
}
