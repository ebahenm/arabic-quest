// Confirmation modal for permanent student deletion enforcing export, zero balance, and username confirmation.
(function () {
    let overlay = null;
    let activeStudent = null;
    let onDeletedCallback = null;

    function el(tag, styleObj, text) {
        const node = document.createElement(tag);
        if (styleObj) Object.assign(node.style, styleObj);
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function ensureModal() {
        if (overlay) return overlay;
        overlay = el('div', {
            position: 'fixed', inset: '0', background: 'rgba(2, 6, 23, 0.82)',
            display: 'none', alignItems: 'center', justifyContent: 'center',
            zIndex: '9999', padding: '1rem'
        });
        const card = el('div', {
            background: '#0f172a', border: '1px solid rgba(239, 68, 68, 0.5)',
            borderRadius: '14px', maxWidth: '500px', width: '100%', padding: '1.5rem',
            boxShadow: '0 24px 48px rgba(0,0,0,0.6)', color: '#f8fafc'
        });

        const title = el('h3', { color: '#fca5a5', marginBottom: '0.4rem', fontSize: '1.1rem' }, '🗑️ Permanently Delete Student');
        const subtitle = el('p', { color: '#cbd5e1', fontSize: '0.88rem', marginBottom: '1rem' });
        subtitle.id = 'del-modal-subtitle';

        const totalsBox = el('div', {
            display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '0.6rem',
            background: '#1e293b', padding: '0.85rem', borderRadius: '10px', marginBottom: '1rem'
        });
        totalsBox.id = 'del-modal-totals';

        const exportBtn = el('button', {
            width: '100%', marginBottom: '0.9rem', padding: '0.6rem 1rem'
        }, '⬇️ Download Student Record (JSON)');
        exportBtn.type = 'button';
        exportBtn.className = 'btn-ghost';
        exportBtn.id = 'del-modal-export-btn';

        const guardMsg = el('div', {
            fontSize: '0.84rem', borderRadius: '8px', padding: '0.7rem 0.9rem',
            marginBottom: '0.9rem', display: 'none'
        });
        guardMsg.id = 'del-modal-guard';

        const confirmWrap = el('div', { marginBottom: '1rem' });
        confirmWrap.id = 'del-modal-confirm-wrap';
        const confirmLabel = el('label', {
            display: 'block', fontSize: '0.8rem', fontWeight: '700',
            color: '#cbd5e1', marginBottom: '0.35rem'
        });
        confirmLabel.id = 'del-modal-confirm-label';
        const confirmInput = el('input', { width: '100%' });
        confirmInput.type = 'text';
        confirmInput.className = 'text-input';
        confirmInput.id = 'del-modal-username-input';
        confirmInput.autocomplete = 'off';
        confirmWrap.appendChild(confirmLabel);
        confirmWrap.appendChild(confirmInput);

        const errMsg = el('div', { color: '#fca5a5', fontSize: '0.84rem', marginBottom: '0.75rem', display: 'none' });
        errMsg.id = 'del-modal-error';

        const actions = el('div', { display: 'flex', justifyContent: 'flex-end', gap: '0.65rem' });
        const cancelBtn = el('button', { padding: '0.55rem 1rem' }, 'Cancel');
        cancelBtn.type = 'button';
        cancelBtn.className = 'btn-ghost';
        cancelBtn.addEventListener('click', close);

        const deleteBtn = el('button', {
            padding: '0.55rem 1.1rem', background: '#dc2626', color: '#fff',
            border: 'none', borderRadius: '8px', fontWeight: '700', cursor: 'pointer'
        }, 'Delete Permanently');
        deleteBtn.type = 'button';
        deleteBtn.id = 'del-modal-confirm-btn';

        confirmInput.addEventListener('input', () => {
            if (!activeStudent) return;
            deleteBtn.disabled = confirmInput.value.trim().toLowerCase() !== activeStudent.username.toLowerCase();
        });

        exportBtn.addEventListener('click', async () => {
            if (!activeStudent) return;
            exportBtn.disabled = true;
            try {
                const record = await window.AdminApi.exportStudentRecord(activeStudent.id);
                window.AdminApi.triggerJsonDownload(`student-${activeStudent.username}-record.json`, record);
            } catch (err) {
                showError(err.message);
            } finally {
                exportBtn.disabled = false;
            }
        });

        deleteBtn.addEventListener('click', async () => {
            if (!activeStudent) return;
            deleteBtn.disabled = true;
            errMsg.style.display = 'none';
            try {
                const res = await window.AdminApi.deleteStudentPermanently(activeStudent.id, confirmInput.value.trim());
                close();
                if (typeof onDeletedCallback === 'function') {
                    await onDeletedCallback(res);
                }
            } catch (err) {
                showError(err.message);
                deleteBtn.disabled = false;
            }
        });

        actions.appendChild(cancelBtn);
        actions.appendChild(deleteBtn);
        card.appendChild(title);
        card.appendChild(subtitle);
        card.appendChild(totalsBox);
        card.appendChild(exportBtn);
        card.appendChild(guardMsg);
        card.appendChild(confirmWrap);
        card.appendChild(errMsg);
        card.appendChild(actions);
        overlay.appendChild(card);
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) close();
        });
        document.body.appendChild(overlay);
        return overlay;
    }

    function showError(msg) {
        const err = document.getElementById('del-modal-error');
        if (!err) return;
        err.textContent = msg;
        err.style.display = 'block';
    }

    function renderMetricTile(label, value, color) {
        const tile = el('div', { textAlign: 'center' });
        tile.appendChild(el('div', { fontSize: '0.72rem', color: '#94a3b8', textTransform: 'uppercase' }, label));
        tile.appendChild(el('div', { fontSize: '1.05rem', fontWeight: '800', color, marginTop: '0.15rem' }, value));
        return tile;
    }

    function close() {
        if (overlay) overlay.style.display = 'none';
        activeStudent = null;
    }

    window.openDeleteStudentModal = async function (student, onDeleted) {
        ensureModal();
        activeStudent = student;
        onDeletedCallback = onDeleted;

        const subtitle = document.getElementById('del-modal-subtitle');
        const totalsBox = document.getElementById('del-modal-totals');
        const guardMsg = document.getElementById('del-modal-guard');
        const confirmWrap = document.getElementById('del-modal-confirm-wrap');
        const confirmLabel = document.getElementById('del-modal-confirm-label');
        const confirmInput = document.getElementById('del-modal-username-input');
        const deleteBtn = document.getElementById('del-modal-confirm-btn');
        const errMsg = document.getElementById('del-modal-error');

        subtitle.textContent = `Account: ${student.fullName} (${student.username}). Download their record first; this erases all student history permanently.`;
        errMsg.style.display = 'none';
        confirmInput.value = '';
        deleteBtn.disabled = true;
        guardMsg.style.display = 'none';
        confirmWrap.style.display = 'none';
        while (totalsBox.firstChild) totalsBox.removeChild(totalsBox.firstChild);
        totalsBox.appendChild(el('div', { gridColumn: '1 / -1', textAlign: 'center', color: '#94a3b8' }, 'Loading balances...'));
        overlay.style.display = 'flex';

        const isSelfOrAdmin = student.role === 'ROLE_ADMIN'
            || (typeof currentAdmin !== 'undefined' && currentAdmin && student.id === currentAdmin.id);
        if (isSelfOrAdmin) {
            guardMsg.style.display = 'block';
            guardMsg.style.background = 'rgba(239, 68, 68, 0.16)';
            guardMsg.style.color = '#fca5a5';
            guardMsg.textContent = 'Admin accounts and your currently signed-in account cannot be deleted.';
            return;
        }

        try {
            const earnings = await window.AdminApi.fetchStudentEarnings(student.username);
            const earned = Number(earnings.totalEarned || 0);
            const paid = Number(earnings.totalPaid || 0);
            const owed = Number(earnings.balanceOwed || 0);
            while (totalsBox.firstChild) totalsBox.removeChild(totalsBox.firstChild);
            totalsBox.appendChild(renderMetricTile('Earned', '$' + earned.toFixed(2), '#6ee7b7'));
            totalsBox.appendChild(renderMetricTile('Paid', '$' + paid.toFixed(2), '#f8fafc'));
            totalsBox.appendChild(renderMetricTile('Owed', (owed < 0 ? '-$' : '$') + Math.abs(owed).toFixed(2), owed === 0 ? '#6ee7b7' : '#ef4444'));

            if (Math.abs(owed) > 0.004) {
                guardMsg.style.display = 'block';
                guardMsg.style.background = 'rgba(239, 68, 68, 0.16)';
                guardMsg.style.color = '#fca5a5';
                guardMsg.textContent = `Deletion blocked while Balance Owed is ${(owed < 0 ? '-$' : '$') + Math.abs(owed).toFixed(2)}. Record a payout or balance adjustment until Owed is $0.00.`;
                return;
            }

            confirmLabel.textContent = `Type the exact username "${student.username}" to confirm permanent deletion:`;
            confirmInput.placeholder = student.username;
            confirmWrap.style.display = 'block';
        } catch (err) {
            showError(err.message);
        }
    };
})();
