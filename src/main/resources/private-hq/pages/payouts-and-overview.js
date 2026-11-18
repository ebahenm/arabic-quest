// Handles Overview Prepaid Credit display, before/after payout preview, Advance payment confirmation, void actions, and reflections mark-as-read.
(function () {
    'use strict';

    function parseDollarText(elId) {
        const el = document.getElementById(elId);
        if (!el || !el.textContent) return 0;
        const num = parseFloat(el.textContent.replace(/[^0-9.-]/g, ''));
        return Number.isNaN(num) ? 0 : num;
    }

    function ensurePayoutPreviewBox() {
        let box = document.getElementById('payout-before-after-preview');
        if (box) return box;
        const actionBar = document.querySelector('#tab-panel-overview .admin-action-bar');
        if (!actionBar) return null;

        box = document.createElement('div');
        box.id = 'payout-before-after-preview';
        Object.assign(box.style, {
            width: '100%', marginTop: '0.75rem', padding: '0.85rem 1.1rem',
            background: 'rgba(15, 23, 42, 0.72)', border: '1px solid var(--border)',
            borderRadius: '10px', display: 'flex', flexDirection: 'column', gap: '0.55rem'
        });

        const summaryLine = document.createElement('div');
        summaryLine.id = 'payout-preview-summary-line';
        summaryLine.style.fontSize = '0.84rem';
        summaryLine.style.color = '#e2e8f0';

        const advanceRow = document.createElement('div');
        advanceRow.id = 'payout-advance-confirm-row';
        Object.assign(advanceRow.style, {
            display: 'none', padding: '0.65rem 0.85rem', borderRadius: '8px',
            background: 'rgba(56, 189, 248, 0.12)', border: '1px solid rgba(56, 189, 248, 0.45)',
            alignItems: 'center', gap: '0.65rem', flexWrap: 'wrap'
        });

        const advLabel = document.createElement('label');
        Object.assign(advLabel.style, {
            display: 'flex', alignItems: 'center', gap: '0.45rem',
            fontSize: '0.84rem', fontWeight: '700', color: '#7dd3fc', cursor: 'pointer'
        });
        const advCheck = document.createElement('input');
        advCheck.type = 'checkbox';
        advCheck.id = 'payout-advance-checkbox';
        const advText = document.createElement('span');
        advText.id = 'payout-advance-label-text';
        advText.textContent = 'Advance payment (record excess as Prepaid Credit applied against future earnings)';
        advLabel.appendChild(advCheck);
        advLabel.appendChild(advText);
        advanceRow.appendChild(advLabel);

        box.appendChild(summaryLine);
        box.appendChild(advanceRow);
        actionBar.appendChild(box);

        const amtInput = document.getElementById('payout-amount');
        if (amtInput && !amtInput.dataset.previewHooked) {
            amtInput.dataset.previewHooked = 'true';
            amtInput.addEventListener('input', updatePayoutPreviewBox);
        }
        document.querySelectorAll('.preset-btn').forEach((btn) => {
            btn.addEventListener('click', () => setTimeout(updatePayoutPreviewBox, 10));
        });
        return box;
    }

    function updatePayoutPreviewBox() {
        const box = ensurePayoutPreviewBox();
        if (!box) return;
        const summaryLine = document.getElementById('payout-preview-summary-line');
        const advanceRow = document.getElementById('payout-advance-confirm-row');
        const advCheck = document.getElementById('payout-advance-checkbox');

        if (!selectedStudent) {
            summaryLine.textContent = 'Select a student in the "Acting on" selector above to preview Earned, Paid, and Owed before and after payout.';
            advanceRow.style.display = 'none';
            return;
        }

        const earned = parseDollarText('admin-metric-earned');
        const paidBefore = parseDollarText('admin-metric-paid');
        const owedBefore = typeof lastOwedAmount === 'number' ? lastOwedAmount : (earned - paidBefore);
        const amtInput = document.getElementById('payout-amount');
        const payoutAmt = amtInput && amtInput.value ? Math.max(0, parseFloat(amtInput.value) || 0) : 0;
        const paidAfter = paidBefore + payoutAmt;
        const owedAfter = earned - paidAfter;
        const fmtOwed = (val) => val < -0.004 ? `$${val.toFixed(2)} (Prepaid credit $${Math.abs(val).toFixed(2)}, applied against future earnings)` : `$${val.toFixed(2)}`;
        summaryLine.textContent = `Before Payout → Earned: $${earned.toFixed(2)} | Paid: $${paidBefore.toFixed(2)} | Owed: ${fmtOwed(owedBefore)}   •   After $${payoutAmt.toFixed(2)} Payout → Earned: $${earned.toFixed(2)} | Paid: $${paidAfter.toFixed(2)} | Owed: ${fmtOwed(owedAfter)}`;
        const positiveOwed = Math.max(0, owedBefore);
        if (payoutAmt > positiveOwed + 0.004) {
            advanceRow.style.display = 'flex';
        } else {
            advanceRow.style.display = 'none';
            if (advCheck) advCheck.checked = false;
        }
    }

    window.handleRecordPayout = async function () {
        hideFeedback('payout-feedback-msg');
        if (!selectedStudent) {
            showFeedback('payout-feedback-msg', 'Please select a student in the "Acting on" selector first.', true);
            return;
        }
        const rawAmount = document.getElementById('payout-amount').value;
        const parsedAmount = parseFloat(rawAmount);
        if (!rawAmount || Number.isNaN(parsedAmount) || parsedAmount <= 0) {
            showFeedback('payout-feedback-msg', 'Please enter a positive payout amount greater than $0.00.', true);
            return;
        }

        const owedBefore = typeof lastOwedAmount === 'number' ? lastOwedAmount : 0;
        const positiveOwed = Math.max(0, owedBefore);
        const isAdvance = parsedAmount > positiveOwed + 0.004;
        const advCheck = document.getElementById('payout-advance-checkbox');

        if (isAdvance && (!advCheck || !advCheck.checked)) {
            updatePayoutPreviewBox();
            showFeedback('payout-feedback-msg',
                `This payout ($${parsedAmount.toFixed(2)}) exceeds current balance owed ($${positiveOwed.toFixed(2)}). Check "Advance payment" below to confirm recording the excess as Prepaid credit.`, true);
            return;
        }

        const note = document.getElementById('payout-note').value.trim()
            || (isAdvance ? 'Advance payment / Prepaid credit' : 'Transfer via Turathtona');
        const btn = document.getElementById('btn-record-payout');
        btn.disabled = true;

        try {
            await window.AdminApi.recordPayout({
                studentId: selectedStudent.id, amount: Number(parsedAmount.toFixed(2)),
                note: note, payoutType: isAdvance ? 'ADVANCE' : 'REGULAR', advanceConfirmed: isAdvance
            });
            document.getElementById('payout-note').value = '';
            if (advCheck) advCheck.checked = false;
            showFeedback('payout-feedback-msg', `✓ Recorded $${parsedAmount.toFixed(2)} ${isAdvance ? 'ADVANCE ' : ''}payout to ${selectedStudent.fullName}.`);
            await loadAdminDashboard();
            if (typeof window.refreshStudentOverviewCard === 'function') await window.refreshStudentOverviewCard();
        } catch (e) {
            showFeedback('payout-feedback-msg', e.message, true);
        } finally {
            btn.disabled = false;
        }
    };

    function ensureVoidModal() {
        let modal = document.getElementById('void-payout-modal');
        if (modal) return modal;
        modal = document.createElement('div');
        modal.id = 'void-payout-modal';
        Object.assign(modal.style, {
            position: 'fixed', inset: '0', background: 'rgba(2, 6, 23, 0.8)',
            display: 'none', alignItems: 'center', justifyContent: 'center', zIndex: '9999', padding: '1rem'
        });
        const card = document.createElement('div');
        card.className = 'admin-modal-card';
        Object.assign(card.style, {
            background: '#0f172a', border: '1px solid rgba(245, 158, 11, 0.5)', borderRadius: '14px',
            maxWidth: 'min(440px, calc(100vw - 1.5rem))', maxHeight: 'calc(100dvh - 1.5rem)',
            overflowY: 'auto', width: '100%', padding: '1.25rem', color: '#f8fafc'
        });
        const h3 = document.createElement('h3');
        h3.style.color = '#fcd34d';
        h3.textContent = '↩️ Void Recorded Payout';
        const info = document.createElement('p');
        info.id = 'void-payout-info';
        Object.assign(info.style, { fontSize: '0.86rem', color: '#cbd5e1', margin: '0.5rem 0 0.85rem' });
        const input = document.createElement('input');
        Object.assign(input, { id: 'void-payout-reason', type: 'text', maxLength: 500, className: 'text-input', placeholder: 'Audit reason (Required, e.g. Duplicate entry)' });
        Object.assign(input.style, { width: '100%', marginBottom: '0.8rem' });
        const err = document.createElement('div');
        err.id = 'void-payout-err';
        Object.assign(err.style, { color: '#fca5a5', fontSize: '0.82rem', marginBottom: '0.65rem', display: 'none' });
        const btns = document.createElement('div');
        Object.assign(btns.style, { display: 'flex', justifyContent: 'flex-end', gap: '0.6rem' });
        const cancelBtn = document.createElement('button');
        Object.assign(cancelBtn, { type: 'button', className: 'btn-ghost', textContent: 'Cancel' });
        cancelBtn.addEventListener('click', () => { modal.style.display = 'none'; });
        const confirmBtn = document.createElement('button');
        Object.assign(confirmBtn, { id: 'void-payout-confirm', type: 'button', className: 'btn-danger-outline', textContent: 'Confirm Void' });
        btns.append(cancelBtn, confirmBtn);
        card.append(h3, info, input, err, btns);
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

    window.refreshAdminOverviewExtras = async function (student, owedAmount) {
        const owedEl = document.getElementById('admin-metric-owed');
        const alertEl = document.getElementById('admin-metric-overpaid-alert');
        if (alertEl && owedEl) {
            if (owedAmount < -0.004) {
                owedEl.style.color = '#38bdf8';
                alertEl.style.color = '#6ee7b7';
                alertEl.textContent = `Prepaid credit $${Math.abs(owedAmount).toFixed(2)}, applied against future earnings`;
                alertEl.style.display = 'block';
            } else {
                owedEl.style.color = '';
                alertEl.textContent = '';
                alertEl.style.display = 'none';
            }
        }
        updatePayoutPreviewBox();
        if (typeof window.refreshStudentOverviewCard === 'function') await window.refreshStudentOverviewCard();
    };

    window.appendPayoutActionCell = function (tr, tdAmount, payout) {
        if (payout.payoutType === 'ADVANCE') {
            const advBadge = document.createElement('span');
            Object.assign(advBadge.style, {
                marginLeft: '0.45rem', fontSize: '0.7rem', fontWeight: '800',
                padding: '0.15rem 0.45rem', borderRadius: '999px',
                background: 'rgba(56, 189, 248, 0.2)', color: '#7dd3fc'
            });
            advBadge.textContent = 'ADVANCE';
            tdAmount.appendChild(advBadge);
        }
        const tdAction = document.createElement('td');
        if (payout.voided) {
            tdAmount.style.textDecoration = 'line-through';
            tdAmount.style.color = '#94a3b8';
            const tag = document.createElement('span');
            Object.assign(tag.style, { color: '#fca5a5', fontSize: '0.78rem', fontWeight: '700' });
            tag.textContent = `VOIDED (${payout.voidReason || 'Admin void'})`;
            tdAction.appendChild(tag);
        } else {
            const voidBtn = document.createElement('button');
            Object.assign(voidBtn, { type: 'button', className: 'btn-danger-outline', textContent: 'Void Payout' });
            Object.assign(voidBtn.style, { padding: '0.28rem 0.65rem', fontSize: '0.76rem' });
            voidBtn.addEventListener('click', () => openVoidDialog(payout));
            tdAction.appendChild(voidBtn);
        }
        tr.appendChild(tdAction);
    };

    window.appendReadingListActions = function (actionsRow, feedbackInput, sub, statusPill) {
        statusPill.textContent = sub.readByAdmin ? '✓ READ' : '● UNREAD';
        statusPill.style.color = sub.readByAdmin ? '#6ee7b7' : '#fde68a';
        const readBtn = document.createElement('button');
        Object.assign(readBtn, {
            type: 'button', className: sub.readByAdmin ? 'btn-ghost' : 'btn-primary',
            textContent: sub.readByAdmin ? '💾 Update Note' : '✓ Mark as Read & Save Note'
        });
        readBtn.style.padding = '0.5rem 1rem';
        readBtn.addEventListener('click', async () => {
            readBtn.disabled = true;
            hideFeedback('review-queue-msg');
            try {
                await window.AdminApi.markOpenAnswerRead(sub.id, feedbackInput.value.trim() || null);
                showFeedback('review-queue-msg', sub.readByAdmin ? '✓ Updated feedback note.' : '✓ Marked reflection as read.');
                await loadReviewQueue();
            } catch (err) {
                showFeedback('review-queue-msg', err.message, true);
                readBtn.disabled = false;
            }
        });
        actionsRow.insertBefore(readBtn, actionsRow.firstChild);
    };

    window.updatePayoutPreviewBox = updatePayoutPreviewBox;
    document.addEventListener('DOMContentLoaded', ensurePayoutPreviewBox);
})();
