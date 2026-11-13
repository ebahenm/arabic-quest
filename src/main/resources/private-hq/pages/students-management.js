// Extends the Admin Students tab with Disable/Enable, JSON Record Export, and Safe Permanent Delete actions.
(function () {
    function ensureStudentsFeedbackBanner() {
        let banner = document.getElementById('students-directory-msg');
        if (banner) return banner;
        const tbody = document.getElementById('admin-students-tbody');
        const tableContainer = tbody ? tbody.closest('.table-container') : null;
        if (!tableContainer) return null;
        banner = document.createElement('div');
        banner.id = 'students-directory-msg';
        banner.className = 'feedback-msg';
        banner.style.margin = '0.75rem 1.5rem 0';
        tableContainer.insertBefore(banner, tableContainer.querySelector('table'));
        return banner;
    }

    function showStudentBanner(message, isError = false) {
        const el = ensureStudentsFeedbackBanner();
        if (!el) return;
        el.textContent = message;
        el.className = 'feedback-msg ' + (isError ? 'err' : 'ok');
    }

    window.enhanceStudentRow = function (tdActions, tdAccess, student) {
        tdActions.style.flexWrap = 'wrap';

        if (student.enabled === false) {
            const disabledPill = document.createElement('span');
            disabledPill.style.marginLeft = '0.4rem';
            disabledPill.style.fontSize = '0.74rem';
            disabledPill.style.fontWeight = '800';
            disabledPill.style.padding = '0.18rem 0.5rem';
            disabledPill.style.borderRadius = '999px';
            disabledPill.style.background = 'rgba(239, 68, 68, 0.22)';
            disabledPill.style.color = '#fca5a5';
            disabledPill.textContent = '⛔ Disabled';
            tdAccess.appendChild(disabledPill);
        }

        const isSelfOrAdmin = student.role === 'ROLE_ADMIN'
            || (typeof currentAdmin !== 'undefined' && currentAdmin && student.id === currentAdmin.id);
        if (isSelfOrAdmin) return;

        const isEnabled = student.enabled !== false;
        const toggleBtn = document.createElement('button');
        toggleBtn.type = 'button';
        toggleBtn.className = isEnabled ? 'btn-danger-outline' : 'btn-primary';
        toggleBtn.style.padding = '0.32rem 0.65rem';
        toggleBtn.style.fontSize = '0.78rem';
        toggleBtn.textContent = isEnabled ? '⛔ Disable' : '✓ Enable';
        toggleBtn.addEventListener('click', async () => {
            const nextState = !isEnabled;
            const actionLabel = nextState ? 'enable' : 'disable and log out';
            if (!confirm(`Confirm ${actionLabel} "${student.username}"? All learning & payout history stays preserved.`)) {
                return;
            }
            toggleBtn.disabled = true;
            try {
                await window.AdminApi.setStudentEnabled(student.id, nextState);
                showStudentBanner(nextState
                    ? `✓ Enabled "${student.username}". They may sign in again.`
                    : `✓ Disabled "${student.username}" and revoked active sessions.`);
                await loadStudents(student.id);
                await loadAdminDashboard();
            } catch (err) {
                showStudentBanner(err.message, true);
                toggleBtn.disabled = false;
            }
        });

        const exportBtn = document.createElement('button');
        exportBtn.type = 'button';
        exportBtn.className = 'btn-ghost';
        exportBtn.style.padding = '0.32rem 0.65rem';
        exportBtn.style.fontSize = '0.78rem';
        exportBtn.textContent = '⬇️ Export JSON';
        exportBtn.addEventListener('click', async () => {
            exportBtn.disabled = true;
            try {
                const record = await window.AdminApi.exportStudentRecord(student.id);
                window.AdminApi.triggerJsonDownload(`student-${student.username}-record.json`, record);
                showStudentBanner(`✓ Exported full JSON history for "${student.username}".`);
            } catch (err) {
                showStudentBanner(err.message, true);
            } finally {
                exportBtn.disabled = false;
            }
        });

        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'btn-danger-outline';
        deleteBtn.style.padding = '0.32rem 0.65rem';
        deleteBtn.style.fontSize = '0.78rem';
        deleteBtn.textContent = '🗑️ Delete';
        deleteBtn.addEventListener('click', () => {
            window.openDeleteStudentModal(student, async (res) => {
                showStudentBanner(`✓ ${res.message || 'Student permanently deleted.'}`);
                await loadStudents();
                await loadAdminDashboard();
            });
        });

        tdActions.appendChild(toggleBtn);
        tdActions.appendChild(exportBtn);
        tdActions.appendChild(deleteBtn);
    };
})();
