// Handles Overview Overpaid alerts, payout void actions, and Written Reflections mark-as-read actions.
(function () {
    function ensurePlatformOverpaidBanner() {
        let banner = document.getElementById('overview-platform-overpaid-banner');
        if (banner) return banner;
        const overviewPanel = document.getElementById('tab-panel-overview');
        if (!overviewPanel) return null;
        banner = document.createElement('div');
        banner.id = 'overview-platform-overpaid-banner';
        banner.style.display = 'none';
        banner.style.background = 'rgba(239, 68, 68, 0.14)';
        banner.style.border = '1px solid rgba(239, 68, 68, 0.5)';
        banner.style.color = '#fecaca';
        banner.style.borderRadius = '12px';
        banner.style.padding = '0.9rem 1.2rem';
        banner.style.marginBottom = '1rem';
        banner.style.fontWeight = '700';
        banner.style.fontSize = '0.88rem';
        overviewPanel.insertBefore(banner, overviewPanel.firstChild);
        return banner;
    }

    function ensureVoidModal() {
        let modal = document.getElementById('void-payout-modal');
        if (modal) return modal;
        modal = document.createElement('div');
        modal.id = 'void-payout-modal';
        Object.assign(modal.style, {
            position: 'fixed', inset: '0', background: 'rgba(2, 6, 23, 0.8)',
            display: 'none', alignItems: 'center', justifyContent: 'center',
            zIndex: '9999', padding: '1rem'
        });
        const card = document.createElement('div');
        Object.assign(card.style, {
            background: '#0f172a', border: '1px solid rgba(245, 158, 11, 0.5)',
            borderRadius: '14px', maxWidth: '440px', width: '100%', padding: '1.35rem', color: '#f8fafc'
        });
        const h3 = document.createElement('h3');
        h3.style.color = '#fcd34d';
        h3.textContent = '↩️ Void Recorded Payout';
        const info = document.createElement('p');
        info.id = 'void-payout-info';
        info.style.fontSize = '0.86rem';
        info.style.color = '#cbd5e1';
        info.style.margin = '0.5rem 0 0.85rem';
        const input = document.createElement('input');
        input.id = 'void-payout-reason';
        input.type = 'text';
        input.maxLength = 500;
        input.className = 'text-input';
        input.placeholder = 'Audit reason (Required, e.g. Duplicate entry)';
        input.style.width = '100%';
        input.style.marginBottom = '0.8rem';
        const err = document.createElement('div');
        err.id = 'void-payout-err';
        err.style.color = '#fca5a5';
        err.style.fontSize = '0.82rem';
        err.style.marginBottom = '0.65rem';
        err.style.display = 'none';
        const btns = document.createElement('div');
        btns.style.display = 'flex';
        btns.style.justifyContent = 'flex-end';
        btns.style.gap = '0.6rem';
        const cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'btn-ghost';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', () => { modal.style.display = 'none'; });
        const confirmBtn = document.createElement('button');
        confirmBtn.id = 'void-payout-confirm';
        confirmBtn.type = 'button';
        confirmBtn.className = 'btn-danger-outline';
        confirmBtn.textContent = 'Confirm Void';
        btns.appendChild(cancelBtn);
        btns.appendChild(confirmBtn);
        card.appendChild(h3);
        card.appendChild(info);
        card.appendChild(input);
        card.appendChild(err);
        card.appendChild(btns);
        modal.appendChild(card);
        document.body.appendChild(modal);
        return modal;
    }

    function openVoidDialog(payout) {
        const modal = ensureVoidModal();
        const info = document.getElementById('void-payout-info');
        const input = document.getElementById('void-payout-reason');
        const err = document.getElementById('void-payout-err');
        const confirmBtn = document.getElementById('void-payout-confirm');
        info.textContent = `Void $${Number(payout.amount || 0).toFixed(2)} payout for ${payout.studentFullName || payout.studentUsername}? The row stays in the ledger for auditability.`;
        input.value = '';
        err.style.display = 'none';
        confirmBtn.disabled = false;
        confirmBtn.onclick = async () => {
            const reason = input.value.trim();
            if (!reason) {
                err.textContent = 'A void reason is required.';
                err.style.display = 'block';
                return;
            }
            confirmBtn.disabled = true;
            try {
                await window.AdminApi.voidPayout(payout.id, reason);
                modal.style.display = 'none';
                showFeedback('payout-feedback-msg', `✓ Payout #${payout.id} voided (${reason}).`);
                await loadAdminDashboard();
            } catch (e) {
                err.textContent = e.message;
                err.style.display = 'block';
                confirmBtn.disabled = false;
            }
        };
        modal.style.display = 'flex';
        input.focus();
    }

    window.refreshAdminOverviewExtras = async function (selectedStudent, owedAmount) {
        const owedEl = document.getElementById('admin-metric-owed');
        const alertEl = document.getElementById('admin-metric-overpaid-alert');
        if (alertEl && owedEl) {
            if (owedAmount < -0.004) {
                owedEl.style.color = '#ef4444';
                alertEl.textContent = `⚠️ Overpaid by $${Math.abs(owedAmount).toFixed(2)}`;
                alertEl.style.display = 'block';
            } else {
                owedEl.style.color = '';
                alertEl.textContent = '';
                alertEl.style.display = 'none';
            }
        }

        const banner = ensurePlatformOverpaidBanner();
        if (!banner || typeof allStudents === 'undefined' || !Array.isArray(allStudents)) return;
        try {
            const results = await Promise.all(
                allStudents.map((s) => window.AdminApi.fetchStudentEarnings(s.username)
                    .then((e) => ({ student: s, owed: Number(e.balanceOwed || 0) }))
                    .catch(() => null))
            );
            const overpaid = results.filter((r) => r && r.owed < -0.004);
            if (overpaid.length > 0) {
                const summary = overpaid
                    .map((r) => `${r.student.fullName || r.student.username} (-$${Math.abs(r.owed).toFixed(2)})`)
                    .join(', ');
                banner.textContent = `⚠️ Overpaid Account Alert: Negative balance detected for ${summary}. Void a mistaken payout or post a balance adjustment.`;
                banner.style.display = 'block';
            } else {
                banner.style.display = 'none';
            }
        } catch (_) {}
    };

    window.appendPayoutActionCell = function (tr, tdAmount, payout) {
        const tdAction = document.createElement('td');
        if (payout.voided) {
            tdAmount.style.textDecoration = 'line-through';
            tdAmount.style.color = '#94a3b8';
            const tag = document.createElement('span');
            tag.style.color = '#fca5a5';
            tag.style.fontSize = '0.78rem';
            tag.style.fontWeight = '700';
            tag.textContent = `VOIDED (${payout.voidReason || 'Admin void'})`;
            tdAction.appendChild(tag);
        } else {
            const voidBtn = document.createElement('button');
            voidBtn.type = 'button';
            voidBtn.className = 'btn-danger-outline';
            voidBtn.style.padding = '0.28rem 0.65rem';
            voidBtn.style.fontSize = '0.76rem';
            voidBtn.textContent = 'Void Payout';
            voidBtn.addEventListener('click', () => openVoidDialog(payout));
            tdAction.appendChild(voidBtn);
        }
        tr.appendChild(tdAction);
    };

    window.appendReadingListActions = function (actionsRow, feedbackInput, sub, statusPill) {
        statusPill.textContent = sub.readByAdmin ? '✓ READ' : '● UNREAD';
        statusPill.style.color = sub.readByAdmin ? '#6ee7b7' : '#fde68a';

        const readBtn = document.createElement('button');
        readBtn.type = 'button';
        readBtn.className = sub.readByAdmin ? 'btn-ghost' : 'btn-primary';
        readBtn.style.padding = '0.5rem 1rem';
        readBtn.textContent = sub.readByAdmin ? '💾 Update Note' : '✓ Mark as Read & Save Note';
        readBtn.addEventListener('click', async () => {
            readBtn.disabled = true;
            hideFeedback('review-queue-msg');
            try {
                await window.AdminApi.markOpenAnswerRead(sub.id, feedbackInput.value.trim() || null);
                showFeedback('review-queue-msg', sub.readByAdmin
                    ? '✓ Updated feedback note.'
                    : '✓ Marked reflection as read.');
                await loadReviewQueue();
            } catch (err) {
                showFeedback('review-queue-msg', err.message, true);
                readBtn.disabled = false;
            }
        });
        actionsRow.insertBefore(readBtn, actionsRow.firstChild);
    };
})();
