// Sticky "Acting on: <student>" context selector, student overview card, recent activity list, and student directory search/filter.
(function () {
    'use strict';

    const STORAGE_KEY = 'turath_active_student_id';
    let directorySearchQuery = '';
    let directoryStatusFilter = 'ALL';

    function getPersistedStudentId() {
        try {
            const raw = sessionStorage.getItem(STORAGE_KEY);
            return raw ? parseInt(raw, 10) : null;
        } catch (_) { return null; }
    }
    function setPersistedStudentId(studentId) {
        try {
            if (studentId) sessionStorage.setItem(STORAGE_KEY, String(studentId));
            else sessionStorage.removeItem(STORAGE_KEY);
        } catch (_) {}
    }

    function ensureStudentsTabStickyBar() {
        let bar = document.getElementById('sticky-acting-student-bar');
        if (bar) return bar;
        const studentsPanel = document.getElementById('tab-panel-students');
        if (!studentsPanel) return null;

        bar = document.createElement('div');
        bar.id = 'sticky-acting-student-bar';
        Object.assign(bar.style, {
            position: 'sticky', top: '0.5rem', zIndex: '40',
            background: 'rgba(15, 23, 42, 0.96)', border: '1px solid rgba(201, 162, 77, 0.55)',
            borderRadius: '12px', padding: '0.85rem 1.2rem', marginBottom: '1rem',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            flexWrap: 'wrap', gap: '0.75rem', boxShadow: '0 8px 24px rgba(0, 0, 0, 0.45)'
        });

        const left = document.createElement('div');
        const label = document.createElement('span');
        Object.assign(label.style, { fontSize: '0.76rem', textTransform: 'uppercase', fontWeight: '800', color: '#94a3b8' });
        label.textContent = 'Student Context • ';
        const activeText = document.createElement('strong');
        activeText.id = 'acting-on-student-label';
        Object.assign(activeText.style, { fontSize: '0.96rem', color: '#fde68a' });
        activeText.textContent = 'Acting on: No student selected';
        left.append(label, activeText);

        const right = document.createElement('div');
        Object.assign(right.style, { display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' });

        const sel = document.createElement('select');
        Object.assign(sel, { id: 'students-tab-student-select', className: 'select-input' });
        sel.style.minWidth = '240px';
        sel.setAttribute('aria-label', 'Acting on student selector');
        sel.addEventListener('change', () => selectActiveStudentById(sel.value ? parseInt(sel.value, 10) : null));

        const resetPwBtn = document.createElement('button');
        Object.assign(resetPwBtn, { type: 'button', id: 'btn-sticky-reset-password', className: 'btn-ghost', textContent: '🔑 Reset Password' });
        resetPwBtn.style.fontSize = '0.8rem';
        resetPwBtn.addEventListener('click', () => {
            if (!selectedStudent) {
                alert('Please select a student in the "Acting on" selector first.');
                return;
            }
            if (typeof window.openStudentPasswordResetModal === 'function') window.openStudentPasswordResetModal(selectedStudent);
        });

        right.append(sel, resetPwBtn);
        bar.append(left, right);
        studentsPanel.insertBefore(bar, studentsPanel.firstChild);

        let card = document.getElementById('student-context-overview-card');
        if (!card) {
            card = document.createElement('div');
            Object.assign(card, { id: 'student-context-overview-card', className: 'admin-card-section' });
            Object.assign(card.style, { marginBottom: '1rem', borderColor: 'rgba(16, 185, 129, 0.45)' });
            bar.insertAdjacentElement('afterend', card);
        }
        ensureDirectorySearchToolbar();
        return bar;
    }

    function ensureDirectorySearchToolbar() {
        if (document.getElementById('student-dir-search-input')) return;
        const tbody = document.getElementById('admin-students-tbody');
        const container = tbody ? tbody.closest('.table-container') : null;
        if (!container) return;

        const bar = document.createElement('div');
        Object.assign(bar.style, {
            padding: '0.75rem 1.5rem', borderBottom: '1px solid var(--border)',
            display: 'flex', gap: '0.65rem', flexWrap: 'wrap', alignItems: 'center'
        });

        const searchInput = document.createElement('input');
        Object.assign(searchInput, { type: 'search', id: 'student-dir-search-input', className: 'text-input', placeholder: '🔍 Search students by name or username...' });
        Object.assign(searchInput.style, { flex: '1', minWidth: '200px' });
        searchInput.addEventListener('input', () => { directorySearchQuery = searchInput.value.trim().toLowerCase(); applyDirectoryFilter(); });

        const filterSelect = document.createElement('select');
        Object.assign(filterSelect, { id: 'student-dir-status-filter', className: 'select-input' });
        filterSelect.style.width = '180px';
        [['ALL', 'All Students'], ['ACTIVE', 'Active Only'], ['DISABLED', 'Disabled Only'], ['MUST_CHANGE_PW', 'Temp Password Pending']].forEach(([val, label]) => {
            const opt = document.createElement('option');
            Object.assign(opt, { value: val, textContent: label });
            filterSelect.appendChild(opt);
        });
        filterSelect.addEventListener('change', () => { directoryStatusFilter = filterSelect.value; applyDirectoryFilter(); });
        bar.append(searchInput, filterSelect);
        const table = container.querySelector('table');
        if (table) container.insertBefore(bar, table);
    }

    function applyDirectoryFilter() {
        const tbody = document.getElementById('admin-students-tbody');
        if (!tbody || typeof allStudents === 'undefined') return;
        Array.from(tbody.querySelectorAll('tr')).forEach((tr, idx) => {
            const s = allStudents[idx];
            if (!s) return;
            const hay = `${s.fullName || ''} ${s.username || ''}`.toLowerCase();
            const matchesText = !directorySearchQuery || hay.includes(directorySearchQuery);
            const matchesStatus = directoryStatusFilter === 'ALL'
                || (directoryStatusFilter === 'ACTIVE' && s.enabled !== false)
                || (directoryStatusFilter === 'DISABLED' && s.enabled === false)
                || (directoryStatusFilter === 'MUST_CHANGE_PW' && Boolean(s.mustChangePassword));
            tr.style.display = (matchesText && matchesStatus) ? '' : 'none';
        });
    }

    function populateSelectorOptions(selectEl, currentId) {
        if (!selectEl) return;
        selectEl.textContent = '';
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = '— Select a student to manage —';
        selectEl.appendChild(placeholder);

        (allStudents || []).forEach((s) => {
            const opt = document.createElement('option');
            Object.assign(opt, { value: String(s.id), textContent: `${s.fullName} (${s.username})` });
            selectEl.appendChild(opt);
        });
        selectEl.value = currentId ? String(currentId) : '';
    }

    async function renderStudentOverviewCard() {
        const card = document.getElementById('student-context-overview-card');
        if (!card) return;
        card.textContent = '';

        if (!selectedStudent) {
            const msg = document.createElement('div');
            Object.assign(msg.style, { color: '#fcd34d', fontWeight: '700', fontSize: '0.9rem' });
            msg.textContent = '⚠️ No student selected. Please choose a student in the "Acting on" selector above to view their overview, unlock episodes, record payouts, or reset their password.';
            card.appendChild(msg);
            return;
        }

        try {
            const ov = await window.AdminApi.fetchStudentOverview(selectedStudent.id);
            const header = document.createElement('div');
            Object.assign(header.style, {
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                flexWrap: 'wrap', gap: '0.5rem', marginBottom: '0.75rem'
            });
            const title = document.createElement('h4');
            Object.assign(title.style, { color: '#6ee7b7', fontSize: '1rem' });
            title.textContent = `📊 Student Overview — ${ov.fullName} (${ov.username})`;
            const lastLogin = document.createElement('span');
            Object.assign(lastLogin.style, { fontSize: '0.8rem', color: 'var(--text-muted)' });
            lastLogin.textContent = 'Last Login: ' + (ov.lastLoginAt ? new Date(ov.lastLoginAt).toLocaleString() : 'Never');
            header.append(title, lastLogin);
            card.appendChild(header);

            const grid = document.createElement('div');
            Object.assign(grid.style, {
                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                gap: '0.65rem', marginBottom: '0.85rem'
            });
            const owedNum = Number(ov.balanceOwed || 0);
            [
                { label: 'Earned', val: '$' + Number(ov.totalEarned || 0).toFixed(2), color: '#6ee7b7' },
                { label: 'Paid', val: '$' + Number(ov.totalPaid || 0).toFixed(2), color: '#e2e8f0' },
                owedNum < 0
                    ? { label: 'Prepaid Credit', val: `Prepaid credit $${Math.abs(owedNum).toFixed(2)}, applied against future earnings`, color: '#38bdf8' }
                    : { label: 'Balance Owed', val: '$' + owedNum.toFixed(2), color: '#fcd34d' },
                { label: 'Episodes Passed', val: `${ov.passedEpisodes || 0} / ${ov.totalEpisodes || 0}`, color: '#c4b5fd' }
            ].forEach((it) => {
                const box = document.createElement('div');
                Object.assign(box.style, { background: 'var(--bg-subtle)', border: '1px solid var(--border)', borderRadius: '8px', padding: '0.6rem 0.8rem' });
                const lbl = document.createElement('div');
                Object.assign(lbl.style, { fontSize: '0.72rem', color: 'var(--text-muted)', textTransform: 'uppercase' });
                lbl.textContent = it.label;
                const val = document.createElement('div');
                Object.assign(val.style, { fontSize: '0.92rem', fontWeight: '800', color: it.color, marginTop: '0.2rem' });
                val.textContent = it.val;
                box.append(lbl, val);
                grid.appendChild(box);
            });
            card.appendChild(grid);

            const actTitle = document.createElement('div');
            Object.assign(actTitle.style, { fontSize: '0.78rem', fontWeight: '800', color: 'var(--text-muted)', marginBottom: '0.35rem' });
            actTitle.textContent = 'Recent Admin Activity for ' + ov.fullName;
            card.appendChild(actTitle);

            const list = document.createElement('div');
            Object.assign(list.style, { display: 'flex', flexDirection: 'column', gap: '0.3rem' });
            const events = Array.isArray(ov.recentActivity) ? ov.recentActivity.slice(0, 6) : [];
            if (events.length === 0) {
                const empty = document.createElement('div');
                Object.assign(empty.style, { fontSize: '0.8rem', color: 'var(--text-muted)' });
                empty.textContent = 'No recent admin actions recorded for this student yet.';
                list.appendChild(empty);
            } else {
                events.forEach((ev) => {
                    const row = document.createElement('div');
                    Object.assign(row.style, { fontSize: '0.8rem', color: '#cbd5e1' });
                    row.textContent = `[${ev.createdAt ? new Date(ev.createdAt).toLocaleString() : ''}] ${ev.actionType}: ${ev.summary} (by ${ev.adminUsername || 'admin'})`;
                    list.appendChild(row);
                });
            }
            card.appendChild(list);
        } catch (err) {
            const errEl = document.createElement('div');
            errEl.style.color = '#fca5a5';
            errEl.textContent = 'Could not load student overview: ' + err.message;
            card.appendChild(errEl);
        }
    }

    function syncAllSelectorsAndLabels() {
        ensureStudentsTabStickyBar();
        const currentId = selectedStudent ? selectedStudent.id : null;
        populateSelectorOptions(document.getElementById('admin-student-select'), currentId);
        populateSelectorOptions(document.getElementById('students-tab-student-select'), currentId);

        const actingLabel = document.getElementById('acting-on-student-label');
        const currentTitle = document.getElementById('current-student-title');
        const editInd = document.getElementById('editing-student-indicator');
        const ovInd = document.getElementById('override-student-indicator');
        const payoutTitle = document.getElementById('payout-title-label');

        if (!selectedStudent) {
            if (actingLabel) actingLabel.textContent = 'Acting on: No student selected — choose a student';
            if (currentTitle) currentTitle.textContent = 'No student selected — please choose a student';
            if (editInd) editInd.textContent = 'No student selected';
            if (ovInd) ovInd.textContent = 'No student selected';
            if (payoutTitle) payoutTitle.textContent = '💸 Issue Payout (Select a student first)';
            const ovTbody = document.getElementById('student-overrides-tbody');
            if (ovTbody) {
                ovTbody.textContent = '';
                const tr = document.createElement('tr');
                const td = document.createElement('td');
                td.colSpan = 6;
                Object.assign(td.style, { textAlign: 'center', padding: '1.2rem', color: '#fcd34d' });
                td.textContent = 'Select a student in the "Acting on" selector above to view and manage episode access overrides.';
                tr.appendChild(td);
                ovTbody.appendChild(tr);
            }
        } else {
            const display = `${selectedStudent.fullName} (${selectedStudent.username})`;
            if (actingLabel) actingLabel.textContent = `Acting on: ${display}`;
            if (currentTitle) currentTitle.textContent = `Acting on: ${display}`;
            if (editInd) editInd.textContent = display;
            if (ovInd) ovInd.textContent = display;
            if (payoutTitle) payoutTitle.textContent = `💸 Issue Payout to ${selectedStudent.fullName}`;
            populateEditStudentForm(selectedStudent);
        }
        renderStudentOverviewCard();
        if (typeof window.updatePayoutPreviewBox === 'function') window.updatePayoutPreviewBox();
    }

    function selectActiveStudentById(studentId) {
        selectedStudent = studentId ? (allStudents || []).find((s) => s.id === studentId) || null : null;
        setPersistedStudentId(selectedStudent ? selectedStudent.id : null);
        syncAllSelectorsAndLabels();
        loadAdminDashboard();
    }

    const origLoadStudents = window.loadStudents;
    if (typeof origLoadStudents === 'function') {
        window.loadStudents = async function (preferStudentId = null) {
            await origLoadStudents(preferStudentId);
            const targetId = preferStudentId || getPersistedStudentId();
            selectedStudent = targetId ? (allStudents || []).find((s) => s.id === targetId) || null : null;
            setPersistedStudentId(selectedStudent ? selectedStudent.id : null);
            syncAllSelectorsAndLabels();
            applyDirectoryFilter();
        };
    }

    window.handleStudentSelectChange = function () {
        const sel = document.getElementById('admin-student-select');
        selectActiveStudentById(sel && sel.value ? parseInt(sel.value, 10) : null);
    };
    window.selectActiveStudentById = selectActiveStudentById;
    window.refreshStudentOverviewCard = renderStudentOverviewCard;
})();
