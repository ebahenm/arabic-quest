// Turathtona Secret Admin Command Center — Organized 5-Tab Interface (Hardened Cookie/JWT + XSS-Safe DOM)

const HQ_LOGIN_PATH = '{{HQ_LOGIN_PATH}}';

let currentAdmin = null;
let allStudents = [];
let selectedStudent = null;
let episodes = [];
let adminCourses = [];
let selectedAdminCourseId = null;
let lastYoutubePreview = null;
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
    const adminEmb = document.getElementById('admin-header-emblem-img');
    if (adminEmb) {
        adminEmb.addEventListener('error', () => {
            adminEmb.style.display = 'none';
            if (adminEmb.parentElement) adminEmb.parentElement.textContent = '🛡️';
        });
    }
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
    const adjForm = document.getElementById('form-balance-adjustment');
    if (adjForm) adjForm.addEventListener('submit', handleBalanceAdjustmentSubmit);
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

    // Review Queue events
    const reviewFilter = document.getElementById('review-status-filter');
    if (reviewFilter) reviewFilter.addEventListener('change', loadReviewQueue);
    const refreshReviewsBtn = document.getElementById('btn-refresh-reviews');
    if (refreshReviewsBtn) refreshReviewsBtn.addEventListener('click', loadReviewQueue);

    // Transcript & Question Bank Manager events
    const transcriptInput = document.getElementById('ai-transcript-input');
    if (transcriptInput) {
        transcriptInput.addEventListener('input', () => {
            const countEl = document.getElementById('ai-transcript-char-count');
            if (countEl) countEl.textContent = `${transcriptInput.value.length.toLocaleString()} / 60,000 chars`;
        });
    }
    const aiEpisodeSelect = document.getElementById('ai-episode-select');
    if (aiEpisodeSelect) aiEpisodeSelect.addEventListener('change', loadEpisodeQuestionBank);
    const qbankStatus = document.getElementById('qbank-filter-status');
    if (qbankStatus) qbankStatus.addEventListener('change', loadEpisodeQuestionBank);
    const qbankType = document.getElementById('qbank-filter-type');
    if (qbankType) qbankType.addEventListener('change', loadEpisodeQuestionBank);
    const qbankReported = document.getElementById('qbank-filter-reported');
    if (qbankReported) qbankReported.addEventListener('change', loadEpisodeQuestionBank);
    const qbankRefresh = document.getElementById('btn-qbank-refresh');
    if (qbankRefresh) qbankRefresh.addEventListener('click', loadEpisodeQuestionBank);
    const qbankBulkApprove = document.getElementById('btn-qbank-bulk-approve');
    if (qbankBulkApprove) qbankBulkApprove.addEventListener('click', handleBulkApproveDrafts);

    const mcqForm = document.getElementById('form-upsert-mcq');
    if (mcqForm) mcqForm.addEventListener('submit', handleSaveMcq);
    const cancelMcqBtn = document.getElementById('btn-cancel-mcq-edit');
    if (cancelMcqBtn) cancelMcqBtn.addEventListener('click', resetMcqForm);

    const openqForm = document.getElementById('form-upsert-openq');
    if (openqForm) openqForm.addEventListener('submit', handleSaveOpenQuestion);
    const cancelOpenqBtn = document.getElementById('btn-cancel-openq-edit');
    if (cancelOpenqBtn) cancelOpenqBtn.addEventListener('click', resetOpenQuestionForm);

    const refreshOverridesBtn = document.getElementById('btn-refresh-student-overrides');
    if (refreshOverridesBtn) {
        refreshOverridesBtn.addEventListener('click', () => {
            if (selectedStudent) loadStudentEpisodeOverrides(selectedStudent.id);
        });
    }

    // Phase 5: YouTube Course Import & Course Management events
    const ytPreviewBtn = document.getElementById('btn-yt-preview');
    if (ytPreviewBtn) ytPreviewBtn.addEventListener('click', handlePreviewYouTubePlaylist);
    const ytDraftForm = document.getElementById('form-yt-save-draft');
    if (ytDraftForm) ytDraftForm.addEventListener('submit', handleSaveYouTubeDraftCourse);
    const courseSelect = document.getElementById('admin-course-select');
    if (courseSelect) courseSelect.addEventListener('change', handleAdminCourseSelectChange);
    const newManualCourseBtn = document.getElementById('btn-new-manual-course');
    if (newManualCourseBtn) newManualCourseBtn.addEventListener('click', handleCreateManualDraftCourse);
    const refreshCoursesBtn = document.getElementById('btn-refresh-courses');
    if (refreshCoursesBtn) refreshCoursesBtn.addEventListener('click', () => loadAdminCourses(selectedAdminCourseId));
    const publishToggleBtn = document.getElementById('btn-course-publish-toggle');
    if (publishToggleBtn) publishToggleBtn.addEventListener('click', handleToggleCoursePublish);
    const resyncBtn = document.getElementById('btn-course-resync');
    if (resyncBtn) resyncBtn.addEventListener('click', handleResyncCourse);
    const archiveToggleBtn = document.getElementById('btn-course-archive-toggle');
    if (archiveToggleBtn) archiveToggleBtn.addEventListener('click', handleToggleCourseArchive);
    const editCourseForm = document.getElementById('form-edit-course');
    if (editCourseForm) editCourseForm.addEventListener('submit', handleSaveCourseSettings);
    const inlineEpForm = document.getElementById('form-edit-episode-inline');
    if (inlineEpForm) inlineEpForm.addEventListener('submit', handleSaveInlineEpisodeEdit);
    const cancelEpEditBtn = document.getElementById('btn-cancel-ep-edit');
    if (cancelEpEditBtn) {
        cancelEpEditBtn.addEventListener('click', () => {
            const box = document.getElementById('edit-episode-inline-box');
            if (box) box.style.display = 'none';
        });
    }
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
        const pendingMetricEl = document.getElementById('admin-metric-pending');
        if (pendingMetricEl) pendingMetricEl.textContent = '$0.00';
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
        if (typeof window.enhanceStudentRow === 'function') {
            window.enhanceStudentRow(tdActions, tdAccess, s);
        }

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
    const ovInd = document.getElementById('override-student-indicator');
    if (ovInd) ovInd.textContent = `${student.fullName} (${student.username})`;
    document.getElementById('edit-student-username').value = student.username || '';
    document.getElementById('edit-student-fullname').value = student.fullName || '';
    document.getElementById('reset-student-newpw').value = '';
    const betaCheckbox = document.getElementById('edit-student-beta');
    if (betaCheckbox) betaCheckbox.checked = Boolean(student.betaTester);
    loadStudentEpisodeOverrides(student.id);
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
                const pendingMetricEl = document.getElementById('admin-metric-pending');
                if (pendingMetricEl) {
                    pendingMetricEl.textContent = '$' + Number(data.pendingReviewAmount || 0).toFixed(2);
                }
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
        loadAttemptsAuditLog(),
        loadReviewQueue(),
        selectedStudent ? loadStudentAdjustments(selectedStudent.id) : Promise.resolve()
    ]);
    if (typeof window.refreshAdminOverviewExtras === 'function') {
        await window.refreshAdminOverviewExtras(selectedStudent, lastOwedAmount);
    }
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
            td.colSpan = 5;
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
            if (typeof window.appendPayoutActionCell === 'function') {
                window.appendPayoutActionCell(tr, tdAmount, p);
            }
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

        // Populate AI Question Bank episode dropdown
        const select = document.getElementById('ai-episode-select');
        const prevSelectedEpId = select ? select.value : '';
        clearElement(select);
        episodes.forEach((ep) => {
            const opt = document.createElement('option');
            opt.value = String(ep.id);
            const prefix = ep.courseTitle ? `[${ep.courseTitle}] ` : '';
            const inactiveNote = ep.enabled === false ? ' (Inactive)' : '';
            opt.textContent = `${prefix}Ep. ${ep.episodeNumber}: ${ep.title}${inactiveNote}`;
            select.appendChild(opt);
        });
        if (prevSelectedEpId && [...select.options].some((o) => o.value === prevSelectedEpId)) {
            select.value = prevSelectedEpId;
        }
        await loadEpisodeQuestionBank();
        await loadAdminCourses(selectedAdminCourseId);
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
    const betaEl = document.getElementById('edit-student-beta');
    const betaTester = betaEl ? Boolean(betaEl.checked) : Boolean(selectedStudent.betaTester);

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

    const transcriptEl = document.getElementById('ai-transcript-input');
    const transcript = transcriptEl ? transcriptEl.value.trim() : '';
    const btn = document.getElementById('btn-run-ai');
    const resultBox = document.getElementById('ai-result-box');
    const resultTitle = document.getElementById('ai-result-title');
    const resultContent = document.getElementById('ai-result-content');

    resultBox.style.display = 'none';
    clearElement(resultContent);

    if (!transcript) {
        resultTitle.textContent = 'Transcript Required';
        resultContent.textContent = 'Paste the episode transcript above first before generating a question bank.';
        resultBox.style.display = 'block';
        return;
    }

    btn.disabled = true;
    btn.textContent = '⏳ Running 2-Pass Gemini Generation & Verification...';

    try {
        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/generate-bank`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ transcript })
        });

        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            resultTitle.textContent = `✓ Saved ${data.savedDraftMcqs || 0} Draft MCQs & ${data.savedDraftOpenQuestions || 0} Draft Open Questions`;
            resultContent.textContent =
                `Pass 1 generated ${data.pass1McqCandidates || 0} MCQ candidates and ${data.pass1OpenCandidates || 0} open prompts. `
                + `Pass 2 verified them against the transcript and dropped ${data.droppedInVerificationOrDedup || 0} unverified/duplicate items. `
                + 'Review and approve drafts in the Question Bank Manager below before students can take the quiz.';
            resultBox.style.display = 'block';
            await loadEpisodeQuestionBank();
        } else {
            resultTitle.textContent = 'AI Question Bank Generation Failed';
            resultContent.textContent = data.error || 'Could not generate question bank. Verify GEMINI_API_KEY and quota.';
            resultBox.style.display = 'block';
        }
    } catch (e) {
        resultTitle.textContent = 'Connection Error';
        resultContent.textContent = e.message;
        resultBox.style.display = 'block';
    } finally {
        btn.disabled = false;
        btn.textContent = '✨ Generate Draft Bank from Transcript (2-Pass)';
    }
}

// ============================================================================
// WRITTEN ANSWERS REVIEW QUEUE & AI GRADE SUGGESTION
// ============================================================================

async function loadReviewQueue() {
    const listEl = document.getElementById('open-answers-review-list');
    const filterEl = document.getElementById('review-status-filter');
    const badgeEl = document.getElementById('tab-badge-reviews');
    if (!listEl) return;

    const statusVal = filterEl ? filterEl.value : 'UNREAD';
    try {
        const [filteredRes, countRes] = await Promise.all([
            apiFetch(`/api/admin/open-answers?status=${encodeURIComponent(statusVal)}`),
            apiFetch('/api/admin/open-answers/unread-count')
        ]);
        if (!filteredRes.ok) return;

        const items = await filteredRes.json();
        if (countRes && countRes.ok) {
            const countData = await countRes.json();
            const unread = Number(countData.unreadCount || 0);
            if (badgeEl) badgeEl.textContent = String(unread);
            const pill = document.getElementById('unread-reviews-badge');
            if (pill) {
                pill.textContent = `${unread} unread`;
                pill.style.display = unread > 0 ? 'inline-block' : 'none';
            }
        }

        clearElement(listEl);
        if (!items || items.length === 0) {
            const emptyP = document.createElement('p');
            emptyP.style.color = 'var(--text-muted)';
            emptyP.style.padding = '1.25rem';
            emptyP.style.textAlign = 'center';
            emptyP.textContent = 'No written answer submissions match this filter.';
            listEl.appendChild(emptyP);
            return;
        }

        items.forEach((sub) => {
            listEl.appendChild(buildReviewSubmissionCard(sub));
        });
    } catch (err) {
        console.warn('Review queue load failed:', err);
    }
}

function buildReviewSubmissionCard(sub) {
    const card = document.createElement('div');
    card.style.background = 'var(--bg-subtle)';
    card.style.border = '1px solid var(--border)';
    card.style.borderRadius = 'var(--radius-md)';
    card.style.padding = '1.15rem 1.35rem';
    card.style.display = 'flex';
    card.style.flexDirection = 'column';
    card.style.gap = '0.75rem';

    const topRow = document.createElement('div');
    topRow.style.display = 'flex';
    topRow.style.justifyContent = 'space-between';
    topRow.style.alignItems = 'center';
    topRow.style.flexWrap = 'wrap';
    topRow.style.gap = '0.5rem';

    const who = document.createElement('div');
    const strongWho = document.createElement('strong');
    strongWho.style.color = '#a7f3d0';
    strongWho.textContent = `${sub.studentFullName || sub.studentUsername} (${sub.studentUsername})`;
    const epSpan = document.createElement('span');
    epSpan.style.color = 'var(--text-muted)';
    epSpan.style.fontSize = '0.85rem';
    epSpan.textContent = ` • ${sub.courseTitle || 'Course'} — Ep. ${sub.episodeNumber}: ${sub.episodeTitle} • Mode: ${sub.watchMode} (+$${Number(sub.potentialRewardAmount || 0).toFixed(2)})`;
    who.appendChild(strongWho);
    who.appendChild(epSpan);

    const statusPill = document.createElement('span');
    statusPill.className = 'tab-count-pill';
    statusPill.textContent = sub.status;
    if (sub.status === 'APPROVED') statusPill.style.color = '#6ee7b7';
    else if (sub.status === 'PENDING') statusPill.style.color = '#fde68a';
    else statusPill.style.color = '#fca5a5';

    topRow.appendChild(who);
    topRow.appendChild(statusPill);
    card.appendChild(topRow);

    const promptBox = document.createElement('div');
    promptBox.style.fontSize = '0.88rem';
    const promptLabel = document.createElement('strong');
    promptLabel.style.color = '#fde68a';
    promptLabel.textContent = 'Open Question: ';
    const promptVal = document.createElement('span');
    promptVal.textContent = sub.promptText || '';
    promptBox.appendChild(promptLabel);
    promptBox.appendChild(promptVal);
    card.appendChild(promptBox);

    if (sub.evidence) {
        const evBox = document.createElement('div');
        evBox.style.fontSize = '0.82rem';
        evBox.style.color = '#c4b5fd';
        evBox.textContent = `Transcript Evidence / Rubric: ${sub.evidence}`;
        card.appendChild(evBox);
    }

    const answerBox = document.createElement('div');
    answerBox.style.background = 'var(--bg-card)';
    answerBox.style.border = '1px solid var(--border)';
    answerBox.style.borderRadius = 'var(--radius-sm)';
    answerBox.style.padding = '0.85rem 1rem';
    answerBox.style.whiteSpace = 'pre-wrap';
    answerBox.style.fontSize = '0.92rem';
    answerBox.style.color = '#f8fafc';
    answerBox.textContent = sub.answerText || '';
    card.appendChild(answerBox);

    const aiSuggestionBox = document.createElement('div');
    aiSuggestionBox.style.display = 'none';
    aiSuggestionBox.style.background = 'rgba(139, 92, 246, 0.12)';
    aiSuggestionBox.style.border = '1px solid rgba(139, 92, 246, 0.4)';
    aiSuggestionBox.style.borderRadius = 'var(--radius-sm)';
    aiSuggestionBox.style.padding = '0.75rem 1rem';
    aiSuggestionBox.style.fontSize = '0.84rem';
    aiSuggestionBox.style.color = '#ede9fe';
    card.appendChild(aiSuggestionBox);

    const feedbackInput = document.createElement('textarea');
    feedbackInput.rows = 2;
    feedbackInput.maxLength = 2000;
    feedbackInput.className = 'text-input';
    feedbackInput.placeholder = 'Optional feedback for student (required/helpful when requesting revision)...';
    feedbackInput.value = sub.adminFeedback || '';
    card.appendChild(feedbackInput);

    const actionsRow = document.createElement('div');
    actionsRow.style.display = 'flex';
    actionsRow.style.gap = '0.65rem';
    actionsRow.style.flexWrap = 'wrap';

    if (sub.status !== 'APPROVED') {
        const approveBtn = document.createElement('button');
        approveBtn.type = 'button';
        approveBtn.className = 'btn-primary';
        approveBtn.style.padding = '0.5rem 1rem';
        approveBtn.textContent = `✓ Approve & Award +$${Number(sub.potentialRewardAmount || 0).toFixed(2)}`;
        approveBtn.addEventListener('click', async () => {
            approveBtn.disabled = true;
            hideFeedback('review-queue-msg');
            try {
                const res = await apiFetch(`/api/admin/open-answers/${sub.id}/approve`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ feedback: feedbackInput.value.trim() || null })
                });
                const data = await res.json().catch(() => ({}));
                if (res.ok) {
                    const bonusMsg = data.bonusAwarded ? ` + $${Number(data.bonusAmount || 0).toFixed(2)} Course Bonus!` : '';
                    showFeedback('review-queue-msg', `✓ Approved written answer! Credited $${Number(data.episodeRewardAmount || 0).toFixed(2)}${bonusMsg}`);
                    await loadAdminDashboard();
                } else {
                    approveBtn.disabled = false;
                    showFeedback('review-queue-msg', data.error || 'Approval failed.', true);
                }
            } catch (err) {
                approveBtn.disabled = false;
                showFeedback('review-queue-msg', 'Error: ' + err.message, true);
            }
        });

        const reviseBtn = document.createElement('button');
        reviseBtn.type = 'button';
        reviseBtn.className = 'btn-danger-outline';
        reviseBtn.style.padding = '0.5rem 0.95rem';
        reviseBtn.textContent = '↩ Request Revision';
        reviseBtn.addEventListener('click', async () => {
            reviseBtn.disabled = true;
            hideFeedback('review-queue-msg');
            try {
                const res = await apiFetch(`/api/admin/open-answers/${sub.id}/request-revision`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ feedback: feedbackInput.value.trim() || null })
                });
                const data = await res.json().catch(() => ({}));
                if (res.ok) {
                    showFeedback('review-queue-msg', '↩ Revision requested; student can rewrite and resubmit.');
                    await loadAdminDashboard();
                } else {
                    reviseBtn.disabled = false;
                    showFeedback('review-queue-msg', data.error || 'Could not request revision.', true);
                }
            } catch (err) {
                reviseBtn.disabled = false;
                showFeedback('review-queue-msg', 'Error: ' + err.message, true);
            }
        });

        actionsRow.appendChild(approveBtn);
        actionsRow.appendChild(reviseBtn);
    }

    const suggestBtn = document.createElement('button');
    suggestBtn.type = 'button';
    suggestBtn.className = 'btn-ghost';
    suggestBtn.textContent = '🤖 Suggest a Grade';
    suggestBtn.addEventListener('click', async () => {
        suggestBtn.disabled = true;
        suggestBtn.textContent = '⏳ Consulting Gemini...';
        try {
            const res = await apiFetch(`/api/admin/open-answers/${sub.id}/suggest-grade`, {
                method: 'POST'
            });
            const data = await res.json().catch(() => ({}));
            aiSuggestionBox.style.display = 'block';
            if (res.ok) {
                aiSuggestionBox.textContent =
                    `AI Suggestion (advisory only): ${data.recommendedAction} [${data.confidence}] — ${data.reasoning}`
                    + (data.suggestedFeedback ? ` | Suggested feedback: "${data.suggestedFeedback}"` : '');
                if (data.suggestedFeedback && !feedbackInput.value.trim()) {
                    feedbackInput.value = data.suggestedFeedback;
                }
            } else {
                aiSuggestionBox.textContent = data.error || 'Could not generate grade suggestion.';
            }
        } catch (err) {
            aiSuggestionBox.style.display = 'block';
            aiSuggestionBox.textContent = 'Connection error: ' + err.message;
        } finally {
            suggestBtn.disabled = false;
            suggestBtn.textContent = '🤖 Suggest a Grade';
        }
    });
    actionsRow.appendChild(suggestBtn);

    if (typeof window.appendReadingListActions === 'function') {
        window.appendReadingListActions(actionsRow, feedbackInput, sub, statusPill);
    }
    card.appendChild(actionsRow);
    return card;
}

// ============================================================================
// EPISODE QUESTION BANK MANAGER (MCQs & OPEN QUESTIONS)
// ============================================================================

async function loadEpisodeQuestionBank() {
    const episodeSelect = document.getElementById('ai-episode-select');
    const summaryEl = document.getElementById('qbank-summary-bar');
    const mcqListEl = document.getElementById('qbank-mcq-list');
    const openListEl = document.getElementById('qbank-open-list');
    if (!episodeSelect || !mcqListEl || !openListEl) return;

    const episodeId = episodeSelect.value;
    if (!episodeId) {
        clearElement(mcqListEl);
        clearElement(openListEl);
        return;
    }

    const statusFilter = (document.getElementById('qbank-filter-status') || {}).value || 'ALL';
    const typeFilter = (document.getElementById('qbank-filter-type') || {}).value || 'ALL';
    const reportedOnly = Boolean((document.getElementById('qbank-filter-reported') || {}).checked);

    try {
        const res = await apiFetch(
            `/api/admin/episodes/${encodeURIComponent(episodeId)}/question-bank`
            + `?status=${encodeURIComponent(statusFilter)}`
            + `&type=${encodeURIComponent(typeFilter)}`
            + `&reportedOnly=${reportedOnly}`
        );
        if (!res.ok) return;
        const bank = await res.json();

        if (summaryEl) {
            const readyText = bank.quizReadyForStudents
                ? '✓ QUIZ READY FOR STUDENTS'
                : '⚠️ NOT READY (Needs ≥10 Approved MCQs + ≥1 Approved Open Question)';
            summaryEl.textContent =
                `${readyText} • MCQs: ${bank.approvedMcqCount} Approved / ${bank.draftMcqCount} Draft / ${bank.retiredMcqCount} Retired `
                + `• Open Prompts: ${bank.approvedOpenCount} Approved / ${bank.draftOpenCount} Draft`;
        }

        renderMcqBankList(bank.questions || []);
        renderOpenQuestionBankList(bank.openQuestions || []);
        syncEpisodeReadinessFromBank(Number(episodeId), bank);
    } catch (err) {
        console.warn('Question bank load failed:', err);
    }
}

function renderMcqBankList(mcqs) {
    const container = document.getElementById('qbank-mcq-list');
    clearElement(container);

    if (!mcqs || mcqs.length === 0) {
        const p = document.createElement('p');
        p.style.color = 'var(--text-muted)';
        p.style.fontSize = '0.85rem';
        p.textContent = 'No multiple-choice questions match the current filter.';
        container.appendChild(p);
        return;
    }

    mcqs.forEach((q) => {
        const card = document.createElement('div');
        card.style.background = 'var(--bg-subtle)';
        card.style.border = '1px solid var(--border)';
        card.style.borderRadius = 'var(--radius-sm)';
        card.style.padding = '0.85rem 1rem';

        const header = document.createElement('div');
        header.style.display = 'flex';
        header.style.justifyContent = 'space-between';
        header.style.alignItems = 'center';
        header.style.flexWrap = 'wrap';
        header.style.gap = '0.5rem';
        header.style.marginBottom = '0.45rem';

        const meta = document.createElement('span');
        meta.style.fontSize = '0.76rem';
        meta.style.fontWeight = '800';
        meta.style.color = q.status === 'APPROVED' ? '#6ee7b7' : (q.status === 'DRAFT' ? '#fde68a' : '#94a3b8');
        meta.textContent = `[${q.status}] • ${q.type}` + (q.reportCount > 0 ? ` • 🚩 ${q.reportCount} report(s)` : '');

        const btnGroup = document.createElement('div');
        btnGroup.style.display = 'flex';
        btnGroup.style.gap = '0.4rem';

        const editBtn = document.createElement('button');
        editBtn.type = 'button';
        editBtn.className = 'btn-ghost';
        editBtn.style.padding = '0.25rem 0.55rem';
        editBtn.style.fontSize = '0.75rem';
        editBtn.textContent = 'Edit';
        editBtn.addEventListener('click', () => populateMcqEditForm(q));
        btnGroup.appendChild(editBtn);

        if (q.status !== 'APPROVED') {
            const appBtn = document.createElement('button');
            appBtn.type = 'button';
            appBtn.className = 'btn-ghost';
            appBtn.style.padding = '0.25rem 0.55rem';
            appBtn.style.fontSize = '0.75rem';
            appBtn.style.color = '#6ee7b7';
            appBtn.textContent = 'Approve';
            appBtn.addEventListener('click', () => handleQuestionBankAction(`/api/admin/questions/${q.id}/approve`));
            btnGroup.appendChild(appBtn);
        }

        if (q.status !== 'RETIRED') {
            const retBtn = document.createElement('button');
            retBtn.type = 'button';
            retBtn.className = 'btn-danger-outline';
            retBtn.style.padding = '0.25rem 0.55rem';
            retBtn.style.fontSize = '0.75rem';
            retBtn.textContent = 'Retire';
            retBtn.addEventListener('click', () => handleQuestionBankAction(`/api/admin/questions/${q.id}/retire`));
            btnGroup.appendChild(retBtn);
        }

        header.appendChild(meta);
        header.appendChild(btnGroup);
        card.appendChild(header);

        const stem = document.createElement('div');
        stem.style.fontWeight = '700';
        stem.style.color = '#f8fafc';
        stem.style.fontSize = '0.9rem';
        stem.textContent = q.questionText;
        card.appendChild(stem);

        if (q.evidence) {
            const ev = document.createElement('div');
            ev.style.fontSize = '0.78rem';
            ev.style.color = '#c4b5fd';
            ev.style.marginTop = '0.25rem';
            ev.textContent = `Evidence: ${q.evidence}`;
            card.appendChild(ev);
        }

        const ul = document.createElement('ul');
        ul.style.marginLeft = '1.2rem';
        ul.style.marginTop = '0.35rem';
        (q.options || []).forEach((opt) => {
            const li = document.createElement('li');
            li.style.fontSize = '0.82rem';
            li.style.color = opt.isCorrect ? '#6ee7b7' : 'var(--text-muted)';
            li.textContent = (opt.isCorrect ? '✓ ' : '• ') + opt.optionText;
            ul.appendChild(li);
        });
        card.appendChild(ul);
        container.appendChild(card);
    });
}

function renderOpenQuestionBankList(opens) {
    const container = document.getElementById('qbank-open-list');
    clearElement(container);

    if (!opens || opens.length === 0) {
        const p = document.createElement('p');
        p.style.color = 'var(--text-muted)';
        p.style.fontSize = '0.85rem';
        p.textContent = 'No open written questions match the current filter.';
        container.appendChild(p);
        return;
    }

    opens.forEach((oq) => {
        const card = document.createElement('div');
        card.style.background = 'var(--bg-subtle)';
        card.style.border = '1px solid var(--border)';
        card.style.borderRadius = 'var(--radius-sm)';
        card.style.padding = '0.85rem 1rem';

        const header = document.createElement('div');
        header.style.display = 'flex';
        header.style.justifyContent = 'space-between';
        header.style.alignItems = 'center';
        header.style.marginBottom = '0.35rem';

        const meta = document.createElement('span');
        meta.style.fontSize = '0.76rem';
        meta.style.fontWeight = '800';
        meta.style.color = oq.status === 'APPROVED' ? '#6ee7b7' : (oq.status === 'DRAFT' ? '#fde68a' : '#94a3b8');
        meta.textContent = `[${oq.status}] Open Question`;

        const btnGroup = document.createElement('div');
        btnGroup.style.display = 'flex';
        btnGroup.style.gap = '0.4rem';

        const editBtn = document.createElement('button');
        editBtn.type = 'button';
        editBtn.className = 'btn-ghost';
        editBtn.style.padding = '0.25rem 0.55rem';
        editBtn.style.fontSize = '0.75rem';
        editBtn.textContent = 'Edit';
        editBtn.addEventListener('click', () => populateOpenQuestionEditForm(oq));
        btnGroup.appendChild(editBtn);

        if (oq.status !== 'APPROVED') {
            const appBtn = document.createElement('button');
            appBtn.type = 'button';
            appBtn.className = 'btn-ghost';
            appBtn.style.padding = '0.25rem 0.55rem';
            appBtn.style.fontSize = '0.75rem';
            appBtn.style.color = '#6ee7b7';
            appBtn.textContent = 'Approve';
            appBtn.addEventListener('click', () => handleQuestionBankAction(`/api/admin/open-questions/${oq.id}/approve`));
            btnGroup.appendChild(appBtn);
        }

        if (oq.status !== 'RETIRED') {
            const retBtn = document.createElement('button');
            retBtn.type = 'button';
            retBtn.className = 'btn-danger-outline';
            retBtn.style.padding = '0.25rem 0.55rem';
            retBtn.style.fontSize = '0.75rem';
            retBtn.textContent = 'Retire';
            retBtn.addEventListener('click', () => handleQuestionBankAction(`/api/admin/open-questions/${oq.id}/retire`));
            btnGroup.appendChild(retBtn);
        }

        header.appendChild(meta);
        header.appendChild(btnGroup);
        card.appendChild(header);

        const promptDiv = document.createElement('div');
        promptDiv.style.fontWeight = '700';
        promptDiv.style.color = '#f8fafc';
        promptDiv.style.fontSize = '0.9rem';
        promptDiv.textContent = oq.promptText;
        card.appendChild(promptDiv);

        if (oq.evidence) {
            const ev = document.createElement('div');
            ev.style.fontSize = '0.78rem';
            ev.style.color = '#c4b5fd';
            ev.style.marginTop = '0.25rem';
            ev.textContent = `Evidence: ${oq.evidence}`;
            card.appendChild(ev);
        }

        container.appendChild(card);
    });
}

async function handleQuestionBankAction(url) {
    hideFeedback('qbank-manager-msg');
    try {
        const res = await apiFetch(url, { method: 'POST' });
        if (res.ok) {
            await loadEpisodeQuestionBank();
        } else {
            const err = await res.json().catch(() => ({}));
            showFeedback('qbank-manager-msg', err.error || 'Action failed.', true);
        }
    } catch (err) {
        showFeedback('qbank-manager-msg', 'Error: ' + err.message, true);
    }
}

async function handleBulkApproveDrafts() {
    hideFeedback('qbank-manager-msg');
    const episodeId = (document.getElementById('ai-episode-select') || {}).value;
    if (!episodeId) return;
    try {
        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/questions/bulk-approve`, {
            method: 'POST'
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('qbank-manager-msg',
                `✓ Approved ${data.approvedMcqs || 0} draft MCQ(s) and ${data.approvedOpenQuestions || 0} draft open question(s).`);
            await loadEpisodeQuestionBank();
        } else {
            showFeedback('qbank-manager-msg', data.error || 'Bulk approve failed.', true);
        }
    } catch (err) {
        showFeedback('qbank-manager-msg', 'Error: ' + err.message, true);
    }
}

function populateMcqEditForm(q) {
    const details = document.getElementById('mcq-editor-details');
    if (details) details.open = true;
    document.getElementById('mcq-edit-id').value = String(q.id);
    document.getElementById('mcq-text').value = q.questionText || '';
    document.getElementById('mcq-type').value = q.type || 'RECALL';
    document.getElementById('mcq-status').value = q.status || 'APPROVED';
    document.getElementById('mcq-evidence').value = q.evidence || '';
    (q.options || []).slice(0, 4).forEach((opt, i) => {
        const input = document.getElementById(`mcq-opt-${i}`);
        if (input) input.value = opt.optionText || '';
        if (opt.isCorrect) {
            const radio = document.querySelector(`input[name="mcq_correct_idx"][value="${i}"]`);
            if (radio) radio.checked = true;
        }
    });
    if (details) details.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function resetMcqForm() {
    document.getElementById('mcq-edit-id').value = '';
    const form = document.getElementById('form-upsert-mcq');
    if (form) form.reset();
}

async function handleSaveMcq(e) {
    e.preventDefault();
    hideFeedback('qbank-manager-msg');
    const episodeId = (document.getElementById('ai-episode-select') || {}).value;
    if (!episodeId) return;

    const editId = document.getElementById('mcq-edit-id').value.trim();
    const correctRadio = document.querySelector('input[name="mcq_correct_idx"]:checked');
    const correctIdx = correctRadio ? parseInt(correctRadio.value, 10) : 0;

    const options = [0, 1, 2, 3].map((i) => ({
        optionText: document.getElementById(`mcq-opt-${i}`).value.trim(),
        isCorrect: i === correctIdx
    }));

    const payload = {
        questionText: document.getElementById('mcq-text').value.trim(),
        type: document.getElementById('mcq-type').value,
        status: document.getElementById('mcq-status').value,
        active: document.getElementById('mcq-status').value !== 'RETIRED',
        evidence: document.getElementById('mcq-evidence').value.trim() || null,
        options
    };

    const url = editId
        ? `/api/admin/questions/${encodeURIComponent(editId)}`
        : `/api/admin/episodes/${encodeURIComponent(episodeId)}/questions`;
    const method = editId ? 'PUT' : 'POST';

    try {
        const res = await apiFetch(url, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            resetMcqForm();
            showFeedback('qbank-manager-msg', editId ? '✓ Updated MCQ (any pending student report cooldowns reset).' : '✓ Added new MCQ.');
            await loadEpisodeQuestionBank();
        } else {
            showFeedback('qbank-manager-msg', data.error || 'Failed to save MCQ.', true);
        }
    } catch (err) {
        showFeedback('qbank-manager-msg', 'Error: ' + err.message, true);
    }
}

function populateOpenQuestionEditForm(oq) {
    const details = document.getElementById('openq-editor-details');
    if (details) details.open = true;
    document.getElementById('openq-edit-id').value = String(oq.id);
    document.getElementById('openq-prompt').value = oq.promptText || '';
    document.getElementById('openq-status').value = oq.status || 'APPROVED';
    document.getElementById('openq-evidence').value = oq.evidence || '';
    if (details) details.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function resetOpenQuestionForm() {
    document.getElementById('openq-edit-id').value = '';
    const form = document.getElementById('form-upsert-openq');
    if (form) form.reset();
}

async function handleSaveOpenQuestion(e) {
    e.preventDefault();
    hideFeedback('qbank-manager-msg');
    const episodeId = (document.getElementById('ai-episode-select') || {}).value;
    if (!episodeId) return;

    const editId = document.getElementById('openq-edit-id').value.trim();
    const payload = {
        promptText: document.getElementById('openq-prompt').value.trim(),
        status: document.getElementById('openq-status').value,
        active: document.getElementById('openq-status').value !== 'RETIRED',
        evidence: document.getElementById('openq-evidence').value.trim() || null
    };

    const url = editId
        ? `/api/admin/open-questions/${encodeURIComponent(editId)}`
        : `/api/admin/episodes/${encodeURIComponent(episodeId)}/open-questions`;
    const method = editId ? 'PUT' : 'POST';

    try {
        const res = await apiFetch(url, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            resetOpenQuestionForm();
            showFeedback('qbank-manager-msg', editId ? '✓ Updated open question.' : '✓ Added open question.');
            await loadEpisodeQuestionBank();
        } else {
            showFeedback('qbank-manager-msg', data.error || 'Failed to save open question.', true);
        }
    } catch (err) {
        showFeedback('qbank-manager-msg', 'Error: ' + err.message, true);
    }
}

async function handleAddEpisodeSubmit(e) {
    e.preventDefault();
    hideFeedback('add-episode-msg');

    const episodeNumber = parseInt(document.getElementById('new-ep-num').value, 10);
    const title = document.getElementById('new-ep-title').value.trim();
    const youtubeVideoId = document.getElementById('new-ep-videoid').value.trim();
    const durationEl = document.getElementById('new-ep-duration');
    const durationSeconds = durationEl ? parseInt(durationEl.value, 10) || 1800 : 1800;
    const btn = document.getElementById('btn-add-episode');

    btn.disabled = true;

    try {
        const res = await apiFetch('/api/admin/episodes', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                playlistId: selectedAdminCourseId || null,
                episodeNumber,
                title,
                youtubeVideoId,
                durationSeconds
            })
        });

        if (res.ok) {
            showFeedback('add-episode-msg', `✓ Added Episode #${episodeNumber}: ${title}`);
            document.getElementById('form-add-episode').reset();
            if (durationEl) durationEl.value = '1800';
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

async function loadStudentEpisodeOverrides(studentId) {
    const tbody = document.getElementById('student-overrides-tbody');
    if (!tbody || !studentId) return;
    clearElement(tbody);

    try {
        const res = await apiFetch(`/api/admin/students/${studentId}/episode-overrides`);
        if (!res.ok) return;
        const rows = await res.json();

        if (!rows || rows.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 5;
            td.style.textAlign = 'center';
            td.style.color = 'var(--text-muted)';
            td.style.padding = '1.25rem';
            td.textContent = 'No episodes available yet.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        rows.forEach((row) => {
            const tr = document.createElement('tr');

            const tdCourse = document.createElement('td');
            tdCourse.style.fontSize = '0.82rem';
            tdCourse.style.color = 'var(--text-muted)';
            tdCourse.textContent = row.courseTitle || `Course #${row.courseId}`;

            const tdEp = document.createElement('td');
            const strongEp = document.createElement('strong');
            strongEp.textContent = `Ep. ${row.episodeNumber}: ${row.episodeTitle}`;
            tdEp.appendChild(strongEp);

            const tdEff = document.createElement('td');
            const effBadge = document.createElement('span');
            effBadge.style.fontSize = '0.75rem';
            effBadge.style.fontWeight = '700';
            effBadge.style.padding = '0.2rem 0.55rem';
            effBadge.style.borderRadius = '999px';
            if (row.effectiveLocked) {
                effBadge.style.background = 'rgba(245, 158, 11, 0.16)';
                effBadge.style.color = '#fcd34d';
                effBadge.textContent = '🔒 Locked';
            } else if (row.passed) {
                effBadge.style.background = 'rgba(16, 185, 129, 0.18)';
                effBadge.style.color = '#6ee7b7';
                effBadge.textContent = '✓ Passed';
            } else {
                effBadge.style.background = 'rgba(59, 130, 246, 0.16)';
                effBadge.style.color = '#93c5fd';
                effBadge.textContent = '🔓 Unlocked';
            }
            tdEff.appendChild(effBadge);

            const tdOv = document.createElement('td');
            const ovCode = document.createElement('code');
            ovCode.textContent = row.overrideState || 'AUTO';
            tdOv.appendChild(ovCode);

            const tdAct = document.createElement('td');
            tdAct.style.display = 'flex';
            tdAct.style.gap = '0.45rem';
            tdAct.style.flexWrap = 'wrap';

            const btnUnlock = document.createElement('button');
            btnUnlock.type = 'button';
            btnUnlock.className = 'btn-ghost';
            btnUnlock.style.padding = '0.28rem 0.6rem';
            btnUnlock.style.fontSize = '0.76rem';
            btnUnlock.textContent = '🔓 Unlock';
            btnUnlock.addEventListener('click', () => setStudentEpisodeOverrideAction(studentId, row.episodeId, 'unlock'));

            const btnLock = document.createElement('button');
            btnLock.type = 'button';
            btnLock.className = 'btn-ghost';
            btnLock.style.padding = '0.28rem 0.6rem';
            btnLock.style.fontSize = '0.76rem';
            btnLock.textContent = '🔒 Lock';
            btnLock.addEventListener('click', () => setStudentEpisodeOverrideAction(studentId, row.episodeId, 'lock'));

            const btnAuto = document.createElement('button');
            btnAuto.type = 'button';
            btnAuto.className = 'btn-ghost';
            btnAuto.style.padding = '0.28rem 0.6rem';
            btnAuto.style.fontSize = '0.76rem';
            btnAuto.textContent = '↺ Auto';
            btnAuto.addEventListener('click', () => setStudentEpisodeOverrideAction(studentId, row.episodeId, 'auto'));

            tdAct.appendChild(btnUnlock);
            tdAct.appendChild(btnLock);
            tdAct.appendChild(btnAuto);

            tr.appendChild(tdCourse);
            tr.appendChild(tdEp);
            tr.appendChild(tdEff);
            tr.appendChild(tdOv);
            tr.appendChild(tdAct);
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.warn('Episode overrides load error:', err);
    }
}

async function setStudentEpisodeOverrideAction(studentId, episodeId, action) {
    hideFeedback('student-overrides-msg');
    try {
        let url;
        let method;
        if (action === 'unlock') {
            url = `/api/admin/students/${studentId}/episodes/${episodeId}/unlock`;
            method = 'POST';
        } else if (action === 'lock') {
            url = `/api/admin/students/${studentId}/episodes/${episodeId}/lock`;
            method = 'POST';
        } else {
            url = `/api/admin/students/${studentId}/episodes/${episodeId}/override`;
            method = 'DELETE';
        }
        const res = await apiFetch(url, { method });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('student-overrides-msg', `✓ Updated episode override to ${data.overrideState || action.toUpperCase()}.`);
            await loadStudentEpisodeOverrides(studentId);
        } else {
            showFeedback('student-overrides-msg', data.error || 'Could not update episode override.', true);
        }
    } catch (err) {
        showFeedback('student-overrides-msg', 'Override error: ' + err.message, true);
    }
}

// ============================================================================
// PHASE 5: YOUTUBE COURSE IMPORT, RE-SYNC & COURSE MANAGEMENT
// ============================================================================

function formatDurationHuman(totalSeconds) {
    const secs = Math.max(0, Math.round(Number(totalSeconds) || 0));
    const hrs = Math.floor(secs / 3600);
    const mins = Math.floor((secs % 3600) / 60);
    const rem = secs % 60;
    if (hrs > 0) {
        return `${hrs}h ${mins}m`;
    }
    if (mins > 0) {
        return rem > 0 ? `${mins}m ${rem}s` : `${mins}m`;
    }
    return `${rem}s`;
}

async function handlePreviewYouTubePlaylist() {
    hideFeedback('yt-import-msg');
    const input = document.getElementById('yt-playlist-input');
    const raw = input ? input.value.trim() : '';
    const previewBox = document.getElementById('yt-preview-box');
    const btn = document.getElementById('btn-yt-preview');

    if (!raw) {
        showFeedback('yt-import-msg', 'Paste a YouTube URL or ID first.', true);
        return;
    }

    btn.disabled = true;
    btn.textContent = '⏳ Fetching from YouTube...';

    try {
        const res = await apiFetch('/api/admin/courses/youtube-preview', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ playlistUrlOrId: raw })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            if (previewBox) previewBox.style.display = 'none';
            showFeedback('yt-import-msg', data.error || 'Could not preview YouTube series.', true);
            return;
        }

        lastYoutubePreview = data;
        document.getElementById('yt-preview-title').textContent = data.title || 'Untitled Series';
        document.getElementById('yt-preview-meta').textContent =
            `ID: ${data.youtubePlaylistId} • ${data.episodeCount} active episode(s) • Total duration: ${formatDurationHuman(data.totalDurationSeconds)}`
            + (data.skippedCount > 0 ? ` • Skipped ${data.skippedCount} private/deleted video(s)` : '');

        const thumbEl = document.getElementById('yt-preview-thumb');
        if (thumbEl) {
            if (data.thumbnailUrl) {
                thumbEl.src = data.thumbnailUrl;
                thumbEl.style.display = 'block';
                thumbEl.onerror = () => {
                    thumbEl.style.display = 'none';
                };
            } else {
                thumbEl.style.display = 'none';
            }
        }

        const warnEl = document.getElementById('yt-preview-warning');
        const saveBtn = document.getElementById('btn-yt-save-draft');
        if (data.alreadyImported) {
            if (warnEl) {
                warnEl.textContent = '⚠️ This YouTube series is already imported. Select it in Course Management below and click Re-sync.';
                warnEl.style.display = 'block';
            }
            if (saveBtn) saveBtn.disabled = true;
        } else {
            if (warnEl) warnEl.style.display = 'none';
            if (saveBtn) saveBtn.disabled = false;
        }

        document.getElementById('yt-draft-title').value = data.title || '';
        document.getElementById('yt-draft-desc').value = data.description || '';
        document.getElementById('yt-draft-cover').value = data.thumbnailUrl || '';
        document.getElementById('yt-draft-rate-ar').value = '15.00';
        document.getElementById('yt-draft-rate-en').value = '10.00';
        document.getElementById('yt-draft-bonus').value = '100.00';

        if (previewBox) previewBox.style.display = 'block';
    } catch (err) {
        showFeedback('yt-import-msg', 'Connection error: ' + err.message, true);
    } finally {
        btn.disabled = false;
        btn.textContent = '🔍 Step 1: Preview Series';
    }
}

async function handleSaveYouTubeDraftCourse(e) {
    e.preventDefault();
    hideFeedback('yt-import-msg');
    const raw = (document.getElementById('yt-playlist-input') || {}).value || (lastYoutubePreview && lastYoutubePreview.youtubePlaylistId) || '';
    if (!raw.trim()) {
        showFeedback('yt-import-msg', 'Preview a YouTube series first.', true);
        return;
    }

    const btn = document.getElementById('btn-yt-save-draft');
    btn.disabled = true;
    btn.textContent = '⏳ Importing Draft Course...';

    const payload = {
        playlistUrlOrId: raw.trim(),
        title: document.getElementById('yt-draft-title').value.trim(),
        description: document.getElementById('yt-draft-desc').value.trim(),
        coverImageUrl: document.getElementById('yt-draft-cover').value.trim(),
        rateArabicSub: parseFloat(document.getElementById('yt-draft-rate-ar').value) || 15.0,
        rateEnglishDub: parseFloat(document.getElementById('yt-draft-rate-en').value) || 10.0,
        completionBonus: parseFloat(document.getElementById('yt-draft-bonus').value) || 100.0
    };

    try {
        const res = await apiFetch('/api/admin/courses/youtube-import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            const previewBox = document.getElementById('yt-preview-box');
            if (previewBox) previewBox.style.display = 'none';
            document.getElementById('yt-playlist-input').value = '';
            lastYoutubePreview = null;
            selectedAdminCourseId = data.id;
            showFeedback('yt-import-msg',
                `✓ Created unpublished draft course "${data.title}" with ${data.activeEpisodeCount} episode(s). Generate/approve quizzes and click Publish when ready!`);
            await loadEpisodes();
            await loadAdminDashboard();
        } else {
            showFeedback('yt-import-msg', data.error || 'Failed to import draft course.', true);
        }
    } catch (err) {
        showFeedback('yt-import-msg', 'Connection error: ' + err.message, true);
    } finally {
        btn.disabled = false;
        btn.textContent = '💾 Step 2: Save as Draft Course (Unpublished)';
    }
}

async function loadAdminCourses(preferredCourseId = null) {
    try {
        const res = await apiFetch('/api/admin/courses');
        if (!res.ok) return;
        adminCourses = await res.json() || [];

        const select = document.getElementById('admin-course-select');
        if (!select) return;
        clearElement(select);

        if (adminCourses.length === 0) {
            selectedAdminCourseId = null;
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = 'No courses yet — import from YouTube above';
            select.appendChild(opt);
            clearElement(document.getElementById('admin-episodes-tbody'));
            return;
        }

        adminCourses.forEach((c) => {
            const opt = document.createElement('option');
            opt.value = String(c.id);
            const stateTag = c.archived ? '[ARCHIVED] ' : (c.published ? '[PUBLISHED] ' : '[DRAFT] ');
            opt.textContent = `${stateTag}${c.title} (${c.activeEpisodeCount} active ep.)`;
            select.appendChild(opt);
        });

        const targetId = preferredCourseId || selectedAdminCourseId || adminCourses[0].id;
        const matched = adminCourses.find((c) => c.id === Number(targetId)) || adminCourses[0];
        selectedAdminCourseId = matched.id;
        select.value = String(matched.id);
        renderSelectedAdminCourse(matched);
    } catch (err) {
        console.warn('Failed to load admin courses:', err);
    }
}

function handleAdminCourseSelectChange() {
    const sel = document.getElementById('admin-course-select');
    if (!sel || !sel.value) return;
    selectedAdminCourseId = Number(sel.value);
    const course = adminCourses.find((c) => c.id === selectedAdminCourseId);
    if (course) {
        hideFeedback('course-mgmt-msg');
        renderSelectedAdminCourse(course);
    }
}

function renderSelectedAdminCourse(course) {
    if (!course) return;
    const badge = document.getElementById('admin-course-status-badge');
    const pubBtn = document.getElementById('btn-course-publish-toggle');
    const archBtn = document.getElementById('btn-course-archive-toggle');
    const resyncBtn = document.getElementById('btn-course-resync');

    if (badge) {
        if (course.archived) {
            badge.textContent = 'ARCHIVED';
            badge.style.color = '#fca5a5';
        } else if (course.published) {
            badge.textContent = 'PUBLISHED';
            badge.style.color = '#6ee7b7';
        } else {
            badge.textContent = 'DRAFT (UNPUBLISHED)';
            badge.style.color = '#fde68a';
        }
    }

    if (pubBtn) {
        pubBtn.textContent = course.published ? 'Unpublish' : '✓ Publish Course';
    }
    if (archBtn) {
        archBtn.textContent = course.archived ? 'Unarchive' : 'Archive';
    }
    if (resyncBtn) {
        resyncBtn.disabled = Boolean(!course.youtubePlaylistId || String(course.youtubePlaylistId).startsWith('manual-'));
    }

    document.getElementById('edit-course-title').value = course.title || '';
    document.getElementById('edit-course-sort').value = String(course.sortOrder ?? 0);
    document.getElementById('edit-course-rate-ar').value = Number(course.rateArabicSub ?? 15).toFixed(2);
    document.getElementById('edit-course-rate-en').value = Number(course.rateEnglishDub ?? 10).toFixed(2);
    document.getElementById('edit-course-bonus').value = Number(course.completionBonus ?? 100).toFixed(2);
    document.getElementById('edit-course-cover').value = course.coverImageUrl || '';
    document.getElementById('edit-course-ytid').value = course.youtubePlaylistId || '';
    document.getElementById('edit-course-desc').value = course.description || '';

    const inlineBox = document.getElementById('edit-episode-inline-box');
    if (inlineBox) inlineBox.style.display = 'none';

    renderAdminCourseEpisodesTable(course);
}

function renderAdminCourseEpisodesTable(course) {
    const tbody = document.getElementById('admin-episodes-tbody');
    if (!tbody) return;
    clearElement(tbody);

    const eps = course.episodes || [];
    if (eps.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 6;
        td.style.textAlign = 'center';
        td.style.color = 'var(--text-muted)';
        td.style.padding = '1.35rem';
        td.textContent = 'No episodes in this course yet.';
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    eps.forEach((ep, idx) => {
        const tr = document.createElement('tr');
        if (!ep.enabled) {
            tr.style.opacity = '0.62';
        }

        // 1. Order + Move Up/Down
        const tdOrder = document.createElement('td');
        const orderWrap = document.createElement('div');
        orderWrap.style.display = 'flex';
        orderWrap.style.alignItems = 'center';
        orderWrap.style.gap = '0.35rem';

        const numStrong = document.createElement('strong');
        numStrong.textContent = `#${ep.episodeNumber}`;
        orderWrap.appendChild(numStrong);

        const upBtn = document.createElement('button');
        upBtn.type = 'button';
        upBtn.className = 'btn-ghost';
        upBtn.style.padding = '0.15rem 0.42rem';
        upBtn.style.fontSize = '0.72rem';
        upBtn.textContent = '↑';
        upBtn.disabled = idx === 0;
        upBtn.title = 'Move episode up';
        upBtn.addEventListener('click', () => handleMoveCourseEpisode(course, idx, -1));

        const downBtn = document.createElement('button');
        downBtn.type = 'button';
        downBtn.className = 'btn-ghost';
        downBtn.style.padding = '0.15rem 0.42rem';
        downBtn.style.fontSize = '0.72rem';
        downBtn.textContent = '↓';
        downBtn.disabled = idx === eps.length - 1;
        downBtn.title = 'Move episode down';
        downBtn.addEventListener('click', () => handleMoveCourseEpisode(course, idx, 1));

        orderWrap.appendChild(upBtn);
        orderWrap.appendChild(downBtn);
        tdOrder.appendChild(orderWrap);

        // 2. Title + YouTube link
        const tdTitle = document.createElement('td');
        const titleDiv = document.createElement('div');
        titleDiv.style.fontWeight = '700';
        titleDiv.textContent = ep.title;
        const ytLink = document.createElement('a');
        ytLink.href = `https://www.youtube.com/watch?v=${encodeURIComponent(ep.youtubeVideoId)}`;
        ytLink.target = '_blank';
        ytLink.rel = 'noopener noreferrer';
        ytLink.style.fontSize = '0.76rem';
        ytLink.style.color = '#93c5fd';
        ytLink.textContent = `${ep.youtubeVideoId} ↗`;
        tdTitle.appendChild(titleDiv);
        tdTitle.appendChild(ytLink);

        // 3. Authoritative Duration
        const tdDur = document.createElement('td');
        tdDur.style.fontSize = '0.82rem';
        tdDur.style.color = 'var(--text-muted)';
        tdDur.textContent = formatDurationHuman(ep.durationSeconds);

        // 4. Quiz Readiness (no bank / drafts only / N approved)
        const tdReady = document.createElement('td');
        const readyPill = document.createElement('span');
        readyPill.style.display = 'inline-block';
        readyPill.style.fontSize = '0.75rem';
        readyPill.style.fontWeight = '700';
        readyPill.style.padding = '0.2rem 0.55rem';
        readyPill.style.borderRadius = '999px';
        readyPill.textContent = ep.quizReadinessLabel || 'No bank';

        if (ep.quizReadinessStatus === 'READY') {
            readyPill.style.background = 'rgba(16, 185, 129, 0.18)';
            readyPill.style.color = '#6ee7b7';
        } else if (ep.quizReadinessStatus === 'DRAFTS_ONLY') {
            readyPill.style.background = 'rgba(245, 158, 11, 0.18)';
            readyPill.style.color = '#fde68a';
        } else if (ep.quizReadinessStatus === 'PARTIAL') {
            readyPill.style.background = 'rgba(59, 130, 246, 0.18)';
            readyPill.style.color = '#93c5fd';
        } else {
            readyPill.style.background = 'rgba(148, 163, 184, 0.15)';
            readyPill.style.color = '#cbd5e1';
        }
        tdReady.appendChild(readyPill);

        // 5. Status (Enabled / Inactive)
        const tdStatus = document.createElement('td');
        const stSpan = document.createElement('span');
        stSpan.style.fontSize = '0.76rem';
        stSpan.style.fontWeight = '700';
        stSpan.style.color = ep.enabled ? '#6ee7b7' : '#fca5a5';
        stSpan.textContent = ep.enabled ? 'Enabled' : 'Inactive';
        tdStatus.appendChild(stSpan);

        // 6. Actions
        const tdActions = document.createElement('td');
        tdActions.style.display = 'flex';
        tdActions.style.gap = '0.4rem';
        tdActions.style.flexWrap = 'wrap';

        const bankBtn = document.createElement('button');
        bankBtn.type = 'button';
        bankBtn.className = 'btn-ghost';
        bankBtn.style.padding = '0.26rem 0.55rem';
        bankBtn.style.fontSize = '0.75rem';
        bankBtn.textContent = '🗂️ Bank';
        bankBtn.addEventListener('click', () => {
            const aiSel = document.getElementById('ai-episode-select');
            if (aiSel) {
                aiSel.value = String(ep.id);
                loadEpisodeQuestionBank();
                aiSel.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        });

        const editEpBtn = document.createElement('button');
        editEpBtn.type = 'button';
        editEpBtn.className = 'btn-ghost';
        editEpBtn.style.padding = '0.26rem 0.55rem';
        editEpBtn.style.fontSize = '0.75rem';
        editEpBtn.textContent = '✏️ Edit';
        editEpBtn.addEventListener('click', () => openInlineEpisodeEdit(ep));

        const toggleEnableBtn = document.createElement('button');
        toggleEnableBtn.type = 'button';
        toggleEnableBtn.className = 'btn-ghost';
        toggleEnableBtn.style.padding = '0.26rem 0.55rem';
        toggleEnableBtn.style.fontSize = '0.75rem';
        toggleEnableBtn.textContent = ep.enabled ? '⏸ Disable' : '▶ Enable';
        toggleEnableBtn.addEventListener('click', () => handleToggleEpisodeEnabled(ep));

        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'btn-danger-outline';
        delBtn.style.padding = '0.26rem 0.55rem';
        delBtn.style.fontSize = '0.75rem';
        delBtn.textContent = 'Archive';
        delBtn.addEventListener('click', () => handleDeleteEpisode(ep));

        tdActions.appendChild(bankBtn);
        tdActions.appendChild(editEpBtn);
        tdActions.appendChild(toggleEnableBtn);
        tdActions.appendChild(delBtn);

        tr.appendChild(tdOrder);
        tr.appendChild(tdTitle);
        tr.appendChild(tdDur);
        tr.appendChild(tdReady);
        tr.appendChild(tdStatus);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);
    });
}

function syncEpisodeReadinessFromBank(episodeId, bank) {
    if (!adminCourses || adminCourses.length === 0 || !bank) return;
    let touchedCourse = null;
    for (const c of adminCourses) {
        const ep = (c.episodes || []).find((item) => item.id === episodeId);
        if (ep) {
            const approvedMcq = Number(bank.approvedMcqCount || 0);
            const draftMcq = Number(bank.draftMcqCount || 0);
            const approvedOpen = Number(bank.approvedOpenCount || 0);
            const draftOpen = Number(bank.draftOpenCount || 0);
            ep.approvedMcqCount = approvedMcq;
            ep.draftMcqCount = draftMcq;
            ep.approvedOpenCount = approvedOpen;
            ep.draftOpenCount = draftOpen;
            ep.quizReady = Boolean(bank.quizReadyForStudents);

            if (approvedMcq === 0 && draftMcq === 0 && approvedOpen === 0 && draftOpen === 0) {
                ep.quizReadinessStatus = 'NO_BANK';
                ep.quizReadinessLabel = 'No bank';
            } else if (approvedMcq === 0 && approvedOpen === 0) {
                ep.quizReadinessStatus = 'DRAFTS_ONLY';
                ep.quizReadinessLabel = `Drafts only (${draftMcq} MCQ, ${draftOpen} open)`;
            } else if (ep.quizReady) {
                ep.quizReadinessStatus = 'READY';
                ep.quizReadinessLabel = `${approvedMcq} approved (${approvedOpen} open) • Ready`;
            } else {
                ep.quizReadinessStatus = 'PARTIAL';
                ep.quizReadinessLabel = `${approvedMcq} approved (${approvedOpen} open) • Needs 10 MCQ + 1 open`;
            }
            touchedCourse = c;
            break;
        }
    }
    if (touchedCourse && touchedCourse.id === selectedAdminCourseId) {
        renderAdminCourseEpisodesTable(touchedCourse);
    }
}

async function handleCreateManualDraftCourse() {
    hideFeedback('course-mgmt-msg');
    const rawTitle = window.prompt('Enter title for the new draft course:');
    if (!rawTitle || !rawTitle.trim()) return;

    try {
        const res = await apiFetch('/api/admin/courses', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                title: rawTitle.trim(),
                rateArabicSub: 15.0,
                rateEnglishDub: 10.0,
                completionBonus: 100.0
            })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            selectedAdminCourseId = data.id;
            showFeedback('course-mgmt-msg', `✓ Created manual draft course "${data.title}".`);
            await loadAdminCourses(data.id);
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Could not create course.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Error: ' + err.message, true);
    }
}

async function handleSaveCourseSettings(e) {
    e.preventDefault();
    hideFeedback('course-mgmt-msg');
    if (!selectedAdminCourseId) return;

    const payload = {
        title: document.getElementById('edit-course-title').value.trim(),
        sortOrder: parseInt(document.getElementById('edit-course-sort').value, 10) || 0,
        rateArabicSub: parseFloat(document.getElementById('edit-course-rate-ar').value) || 0,
        rateEnglishDub: parseFloat(document.getElementById('edit-course-rate-en').value) || 0,
        completionBonus: parseFloat(document.getElementById('edit-course-bonus').value) || 0,
        coverImageUrl: document.getElementById('edit-course-cover').value.trim(),
        youtubePlaylistId: document.getElementById('edit-course-ytid').value.trim(),
        description: document.getElementById('edit-course-desc').value.trim()
    };

    try {
        const res = await apiFetch(`/api/admin/courses/${selectedAdminCourseId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('course-mgmt-msg', `✓ Saved settings for "${data.title}".`);
            await loadEpisodes();
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Failed to save course settings.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Error: ' + err.message, true);
    }
}

async function handleToggleCoursePublish() {
    hideFeedback('course-mgmt-msg');
    const course = adminCourses.find((c) => c.id === selectedAdminCourseId);
    if (!course) return;
    const nextPublished = !course.published;

    try {
        const res = await apiFetch(`/api/admin/courses/${course.id}/publish`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ published: nextPublished })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('course-mgmt-msg',
                nextPublished
                    ? `✓ Published "${data.title}" — now visible in the student catalog.`
                    : `✓ Unpublished "${data.title}" — hidden from students.`);
            await loadAdminCourses(course.id);
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Could not update publish status.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Error: ' + err.message, true);
    }
}

async function handleToggleCourseArchive() {
    hideFeedback('course-mgmt-msg');
    const course = adminCourses.find((c) => c.id === selectedAdminCourseId);
    if (!course) return;
    const nextArchived = !course.archived;

    if (nextArchived && !window.confirm(`Archive course "${course.title}"? It will be unpublished and hidden from students while keeping all student history intact.`)) {
        return;
    }

    try {
        const res = await apiFetch(`/api/admin/courses/${course.id}/archive`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ archived: nextArchived })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('course-mgmt-msg', nextArchived ? `✓ Archived "${data.title}".` : `✓ Unarchived "${data.title}" (as draft).`);
            await loadAdminCourses(course.id);
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Could not update archive status.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Error: ' + err.message, true);
    }
}

async function handleResyncCourse() {
    hideFeedback('course-mgmt-msg');
    const course = adminCourses.find((c) => c.id === selectedAdminCourseId);
    if (!course) return;

    const btn = document.getElementById('btn-course-resync');
    btn.disabled = true;
    btn.textContent = '⏳ Syncing...';

    try {
        const res = await apiFetch(`/api/admin/courses/${course.id}/resync`, { method: 'POST' });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('course-mgmt-msg',
                `✓ Re-sync complete: +${data.addedEpisodes || 0} added, ${data.updatedEpisodes || 0} updated, ${data.deactivatedEpisodes || 0} marked inactive (quizzes & progress preserved).`);
            await loadEpisodes();
            await loadAdminDashboard();
        } else {
            showFeedback('course-mgmt-msg', data.error || 'YouTube re-sync failed.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Re-sync error: ' + err.message, true);
    } finally {
        btn.disabled = false;
        btn.textContent = '🔄 Re-sync YouTube';
    }
}

async function handleMoveCourseEpisode(course, index, delta) {
    hideFeedback('course-mgmt-msg');
    const eps = [...(course.episodes || [])];
    const targetIdx = index + delta;
    if (targetIdx < 0 || targetIdx >= eps.length) return;

    const swapped = eps[index];
    eps[index] = eps[targetIdx];
    eps[targetIdx] = swapped;

    const orderedIds = eps.map((item) => item.id);
    try {
        const res = await apiFetch(`/api/admin/courses/${course.id}/episodes/reorder`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ episodeIds: orderedIds })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('course-mgmt-msg', '✓ Reordered course syllabus.');
            await loadEpisodes();
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Failed to reorder episodes.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Reorder error: ' + err.message, true);
    }
}

function openInlineEpisodeEdit(ep) {
    const box = document.getElementById('edit-episode-inline-box');
    if (!box) return;
    document.getElementById('edit-ep-id').value = String(ep.id);
    document.getElementById('edit-ep-title').value = ep.title || '';
    document.getElementById('edit-ep-duration').value = String(ep.durationSeconds || 1800);
    const heading = document.getElementById('edit-episode-inline-heading');
    if (heading) heading.textContent = `✏️ Edit Episode #${ep.episodeNumber}`;
    box.style.display = 'block';
    box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function handleSaveInlineEpisodeEdit(e) {
    e.preventDefault();
    hideFeedback('course-mgmt-msg');
    const epId = document.getElementById('edit-ep-id').value;
    if (!epId) return;

    const title = document.getElementById('edit-ep-title').value.trim();
    const durationSeconds = parseInt(document.getElementById('edit-ep-duration').value, 10);

    try {
        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(epId)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, durationSeconds })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            const box = document.getElementById('edit-episode-inline-box');
            if (box) box.style.display = 'none';
            showFeedback('course-mgmt-msg', `✓ Updated Episode #${data.episodeNumber}.`);
            await loadEpisodes();
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Failed to update episode.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Error: ' + err.message, true);
    }
}

async function handleToggleEpisodeEnabled(ep) {
    hideFeedback('course-mgmt-msg');
    try {
        const res = await apiFetch(`/api/admin/episodes/${ep.id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: !ep.enabled })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showFeedback('course-mgmt-msg',
                data.enabled
                    ? `✓ Enabled Episode #${data.episodeNumber}.`
                    : `✓ Disabled Episode #${data.episodeNumber} (hidden from student syllabus; progress & quizzes preserved).`);
            await loadEpisodes();
            await loadAdminDashboard();
        } else {
            showFeedback('course-mgmt-msg', data.error || 'Failed to toggle episode status.', true);
        }
    } catch (err) {
        showFeedback('course-mgmt-msg', 'Error: ' + err.message, true);
    }
}

async function loadStudentAdjustments(studentId) {
    const tbody = document.getElementById('admin-adjustments-tbody');
    if (!tbody || !studentId) return;
    clearElement(tbody);
    try {
        const res = await apiFetch(`/api/admin/students/${encodeURIComponent(studentId)}/adjustments`);
        if (!res.ok) return;
        const list = await res.json();
        if (!list || list.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 4;
            td.style.textAlign = 'center';
            td.style.color = 'var(--text-muted)';
            td.style.padding = '1rem';
            td.textContent = 'No balance adjustments recorded for this student.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }
        list.forEach(adj => {
            const tr = document.createElement('tr');
            const tdDate = document.createElement('td');
            tdDate.style.fontSize = '0.82rem';
            tdDate.style.color = 'var(--text-muted)';
            tdDate.textContent = adj.timestamp ? new Date(adj.timestamp).toLocaleString() : '';

            const tdAmt = document.createElement('td');
            const amtNum = Number(adj.amount || 0);
            tdAmt.style.fontWeight = '800';
            tdAmt.style.color = amtNum >= 0 ? '#10b981' : '#ef4444';
            tdAmt.textContent = (amtNum >= 0 ? '+$' : '-$') + Math.abs(amtNum).toFixed(2);

            const tdNote = document.createElement('td');
            tdNote.textContent = adj.note || '';

            const tdAdmin = document.createElement('td');
            tdAdmin.style.fontSize = '0.82rem';
            tdAdmin.style.color = 'var(--text-muted)';
            tdAdmin.textContent = adj.adjustedBy || 'admin';

            tr.appendChild(tdDate);
            tr.appendChild(tdAmt);
            tr.appendChild(tdNote);
            tr.appendChild(tdAdmin);
            tbody.appendChild(tr);
        });
    } catch (e) {
        console.warn('Adjustments load error:', e);
    }
}

async function handleBalanceAdjustmentSubmit(e) {
    e.preventDefault();
    hideFeedback('adj-feedback-msg');
    if (!selectedStudent) {
        showFeedback('adj-feedback-msg', 'Select a student account first.', true);
        return;
    }
    const amtInput = document.getElementById('adj-amount');
    const noteInput = document.getElementById('adj-note');
    const amt = parseFloat(amtInput.value);
    const note = noteInput.value.trim();
    if (isNaN(amt) || amt === 0) {
        showFeedback('adj-feedback-msg', 'Please enter a non-zero adjustment amount (+ or -).', true);
        return;
    }
    if (!note) {
        showFeedback('adj-feedback-msg', 'Please enter a reason or note for this adjustment.', true);
        return;
    }
    try {
        const res = await apiFetch(`/api/admin/students/${encodeURIComponent(selectedStudent.id)}/adjustments`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ amount: amt, note: note })
        });
        if (res.ok) {
            amtInput.value = '';
            noteInput.value = '';
            showFeedback('adj-feedback-msg', `✓ Adjustment of ${amt >= 0 ? '+$' : '-$'}${Math.abs(amt).toFixed(2)} recorded.`);
            await loadAdminDashboard();
        } else {
            const err = await res.json().catch(() => ({}));
            showFeedback('adj-feedback-msg', err.error || 'Failed to post balance adjustment', true);
        }
    } catch (err) {
        showFeedback('adj-feedback-msg', 'Connection error: ' + err.message, true);
    }
}