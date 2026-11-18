// One-time student temporary password reset modal with Copy, Show/Hide toggle, and password input toggles.
(function () {
    'use strict';

    function attachPasswordShowHideToggles() {
        ['new-student-password', 'reset-student-newpw', 'admin-current-pw', 'admin-new-pw', 'admin-confirm-pw'].forEach((inputId) => {
            const input = document.getElementById(inputId);
            if (!input || input.dataset.toggleAttached === 'true') return;
            input.dataset.toggleAttached = 'true';

            const wrapper = document.createElement('div');
            Object.assign(wrapper.style, { display: 'flex', gap: '0.4rem', alignItems: 'center' });
            input.parentNode.insertBefore(wrapper, input);
            input.style.flex = '1';
            wrapper.appendChild(input);

            const toggleBtn = document.createElement('button');
            Object.assign(toggleBtn, { type: 'button', className: 'btn-ghost', textContent: 'Show' });
            Object.assign(toggleBtn.style, { padding: '0.5rem 0.65rem', fontSize: '0.76rem' });
            toggleBtn.addEventListener('click', () => {
                const isPw = input.type === 'password';
                input.type = isPw ? 'text' : 'password';
                toggleBtn.textContent = isPw ? 'Hide' : 'Show';
            });
            wrapper.appendChild(toggleBtn);
        });
    }

    function ensurePasswordResetModal() {
        let modal = document.getElementById('student-pw-reset-modal');
        if (modal) return modal;

        modal = document.createElement('div');
        modal.id = 'student-pw-reset-modal';
        Object.assign(modal.style, {
            position: 'fixed', inset: '0', background: 'rgba(2, 6, 23, 0.84)',
            display: 'none', alignItems: 'center', justifyContent: 'center', zIndex: '10000', padding: '1rem'
        });

        const card = document.createElement('div');
        card.className = 'admin-modal-card';
        Object.assign(card.style, {
            background: '#0f172a', border: '1px solid rgba(201, 162, 77, 0.6)', borderRadius: '14px',
            maxWidth: 'min(480px, calc(100vw - 1.5rem))', maxHeight: 'calc(100dvh - 1.5rem)',
            overflowY: 'auto', width: '100%', padding: '1.35rem', color: '#f8fafc'
        });

        const h3 = document.createElement('h3');
        h3.id = 'pw-reset-modal-title';
        Object.assign(h3.style, { color: '#fde68a', marginBottom: '0.4rem' });
        h3.textContent = '🔑 Reset Student Password';

        const sub = document.createElement('p');
        sub.id = 'pw-reset-modal-sub';
        Object.assign(sub.style, { fontSize: '0.84rem', color: '#cbd5e1', marginBottom: '1rem' });

        const formWrap = document.createElement('div');
        formWrap.id = 'pw-reset-form-wrap';

        const customLbl = document.createElement('label');
        Object.assign(customLbl.style, { display: 'block', fontSize: '0.75rem', fontWeight: '700', color: '#94a3b8', marginBottom: '0.3rem' });
        customLbl.textContent = 'Custom Temporary Password (Optional — leave blank to auto-generate 18-char password)';

        const customRow = document.createElement('div');
        Object.assign(customRow.style, { display: 'flex', gap: '0.4rem', marginBottom: '0.85rem' });

        const customInput = document.createElement('input');
        Object.assign(customInput, {
            type: 'password', id: 'pw-reset-custom-input', className: 'text-input',
            placeholder: 'Leave blank to generate strong 18-char password'
        });
        customInput.style.flex = '1';

        const customToggle = document.createElement('button');
        Object.assign(customToggle, { type: 'button', className: 'btn-ghost', textContent: 'Show' });
        Object.assign(customToggle.style, { padding: '0.45rem 0.7rem', fontSize: '0.78rem' });
        customToggle.addEventListener('click', () => {
            const isPw = customInput.type === 'password';
            customInput.type = isPw ? 'text' : 'password';
            customToggle.textContent = isPw ? 'Hide' : 'Show';
        });
        customRow.append(customInput, customToggle);

        const reqLabel = document.createElement('label');
        Object.assign(reqLabel.style, {
            display: 'flex', alignItems: 'center', gap: '0.5rem',
            fontSize: '0.84rem', color: '#e2e8f0', marginBottom: '0.9rem', cursor: 'pointer'
        });
        const reqCheck = document.createElement('input');
        Object.assign(reqCheck, { type: 'checkbox', id: 'pw-reset-require-change', checked: true });
        const reqSpan = document.createElement('span');
        reqSpan.textContent = 'Require student to change password on next login (enforced server-side)';
        reqLabel.append(reqCheck, reqSpan);
        formWrap.append(customLbl, customRow, reqLabel);

        const onceBox = document.createElement('div');
        onceBox.id = 'pw-reset-once-box';
        Object.assign(onceBox.style, {
            display: 'none', background: 'rgba(16, 185, 129, 0.12)',
            border: '1px solid rgba(16, 185, 129, 0.45)', borderRadius: '10px', padding: '0.9rem', marginBottom: '1rem'
        });

        const onceTitle = document.createElement('div');
        Object.assign(onceTitle.style, { fontSize: '0.8rem', fontWeight: '800', color: '#6ee7b7', marginBottom: '0.4rem' });
        onceTitle.textContent = '✓ Temporary Password Created (Shown Once Only — Copy Now)';

        const onceRow = document.createElement('div');
        Object.assign(onceRow.style, { display: 'flex', gap: '0.45rem', alignItems: 'center' });

        const onceInput = document.createElement('input');
        Object.assign(onceInput, { type: 'password', id: 'pw-reset-once-value', readOnly: true, className: 'text-input' });
        Object.assign(onceInput.style, { flex: '1', fontFamily: 'monospace', fontWeight: '800' });

        const onceShowBtn = document.createElement('button');
        Object.assign(onceShowBtn, { type: 'button', id: 'pw-reset-once-toggle', className: 'btn-ghost', textContent: 'Show' });
        Object.assign(onceShowBtn.style, { padding: '0.45rem 0.7rem', fontSize: '0.78rem' });
        onceShowBtn.addEventListener('click', () => {
            const isPw = onceInput.type === 'password';
            onceInput.type = isPw ? 'text' : 'password';
            onceShowBtn.textContent = isPw ? 'Hide' : 'Show';
        });

        const onceCopyBtn = document.createElement('button');
        Object.assign(onceCopyBtn, { type: 'button', className: 'btn-primary', textContent: '📋 Copy' });
        Object.assign(onceCopyBtn.style, { padding: '0.45rem 0.8rem', fontSize: '0.78rem' });
        onceCopyBtn.addEventListener('click', async () => {
            try {
                await navigator.clipboard.writeText(onceInput.value);
                onceCopyBtn.textContent = '✓ Copied!';
                setTimeout(() => { onceCopyBtn.textContent = '📋 Copy'; }, 1800);
            } catch (_) {
                onceInput.type = 'text';
                onceInput.select();
            }
        });

        onceRow.append(onceInput, onceShowBtn, onceCopyBtn);
        onceBox.append(onceTitle, onceRow);

        const errBox = document.createElement('div');
        errBox.id = 'pw-reset-err-box';
        Object.assign(errBox.style, { display: 'none', color: '#fca5a5', fontSize: '0.82rem', marginBottom: '0.75rem' });

        const actions = document.createElement('div');
        Object.assign(actions.style, { display: 'flex', justifyContent: 'flex-end', gap: '0.6rem' });

        const closeBtn = document.createElement('button');
        Object.assign(closeBtn, { type: 'button', className: 'btn-ghost', textContent: 'Close' });
        closeBtn.addEventListener('click', () => {
            onceInput.value = '';
            modal.style.display = 'none';
        });

        const submitBtn = document.createElement('button');
        Object.assign(submitBtn, { type: 'button', id: 'pw-reset-submit-btn', className: 'btn-primary', textContent: '🔑 Reset Password Now' });

        actions.append(closeBtn, submitBtn);
        card.append(h3, sub, formWrap, onceBox, errBox, actions);
        modal.appendChild(card);
        document.body.appendChild(modal);
        return modal;
    }

    function openStudentPasswordResetModal(student) {
        if (!student) return;
        const modal = ensurePasswordResetModal();
        document.getElementById('pw-reset-modal-title').textContent = `🔑 Reset Password — ${student.fullName}`;
        document.getElementById('pw-reset-modal-sub').textContent =
            `Resetting password for "${student.username}". Existing passwords are never stored in plaintext. Active sessions for this student will end immediately.`;

        const customInput = document.getElementById('pw-reset-custom-input');
        customInput.value = '';
        customInput.type = 'password';
        document.getElementById('pw-reset-require-change').checked = true;
        document.getElementById('pw-reset-form-wrap').style.display = 'block';
        const onceBox = document.getElementById('pw-reset-once-box');
        onceBox.style.display = 'none';
        const onceInput = document.getElementById('pw-reset-once-value');
        onceInput.value = '';
        onceInput.type = 'password';
        document.getElementById('pw-reset-once-toggle').textContent = 'Show';
        const errBox = document.getElementById('pw-reset-err-box');
        errBox.style.display = 'none';

        const submitBtn = document.getElementById('pw-reset-submit-btn');
        submitBtn.style.display = 'inline-flex';
        submitBtn.disabled = false;
        submitBtn.onclick = async () => {
            errBox.style.display = 'none';
            const customPw = customInput.value.trim();
            if (customPw && customPw.length < 8) {
                errBox.textContent = 'Custom password must be at least 8 characters.';
                errBox.style.display = 'block';
                return;
            }
            submitBtn.disabled = true;
            try {
                const requireChange = document.getElementById('pw-reset-require-change').checked;
                const res = await window.AdminApi.resetStudentPassword(student.id, customPw || null, requireChange);
                onceInput.value = res.temporaryPassword || '';
                onceBox.style.display = 'block';
                document.getElementById('pw-reset-form-wrap').style.display = 'none';
                submitBtn.style.display = 'none';
                await loadStudents(student.id);
                if (typeof window.refreshStudentOverviewCard === 'function') await window.refreshStudentOverviewCard();
            } catch (err) {
                errBox.textContent = err.message;
                errBox.style.display = 'block';
                submitBtn.disabled = false;
            }
        };
        modal.style.display = 'flex';
    }

    const prevEnhance = window.enhanceStudentRow;
    window.enhanceStudentRow = function (tdActions, tdAccess, student) {
        if (student.mustChangePassword) {
            const pwPill = document.createElement('span');
            Object.assign(pwPill.style, {
                marginLeft: '0.4rem', fontSize: '0.72rem', fontWeight: '800',
                padding: '0.18rem 0.5rem', borderRadius: '999px',
                background: 'rgba(245, 158, 11, 0.22)', color: '#fcd34d'
            });
            pwPill.textContent = '🔑 Must Change PW';
            tdAccess.appendChild(pwPill);
        }
        const resetBtn = document.createElement('button');
        Object.assign(resetBtn, { type: 'button', className: 'btn-ghost', textContent: '🔑 Reset PW' });
        Object.assign(resetBtn.style, { padding: '0.32rem 0.65rem', fontSize: '0.78rem' });
        resetBtn.addEventListener('click', () => {
            if (typeof window.selectActiveStudentById === 'function') window.selectActiveStudentById(student.id);
            openStudentPasswordResetModal(student);
        });
        tdActions.appendChild(resetBtn);
        if (typeof prevEnhance === 'function') prevEnhance(tdActions, tdAccess, student);
    };

    document.addEventListener('DOMContentLoaded', attachPasswordShowHideToggles);
    attachPasswordShowHideToggles();
    window.openStudentPasswordResetModal = openStudentPasswordResetModal;
})();
