// Stamps data-label attributes on admin tables for stacked mobile cards and provides a scrollable Unlock Quiz modal.
(function () {
    'use strict';

    function stampTableDataLabels(table) {
        if (!table) return;
        const headers = Array.from(table.querySelectorAll('thead th')).map(function (th) {
            return (th.textContent || '').trim();
        });
        if (headers.length === 0) return;
        const rows = table.querySelectorAll('tbody tr');
        rows.forEach(function (tr) {
            const cells = tr.querySelectorAll('td');
            cells.forEach(function (td, idx) {
                if (td.hasAttribute('colspan')) return;
                if (headers[idx] && td.getAttribute('data-label') !== headers[idx]) {
                    td.setAttribute('data-label', headers[idx]);
                }
            });
        });
    }

    function observeAllAdminTables() {
        const containers = document.querySelectorAll('.table-container');
        containers.forEach(function (c) {
            c.classList.add('table-wrap');
        });
        const tables = document.querySelectorAll('table.data-table');
        tables.forEach(function (table) {
            stampTableDataLabels(table);
            const tbody = table.querySelector('tbody');
            if (!tbody || tbody.dataset.mobileObserved === 'true') return;
            tbody.dataset.mobileObserved = 'true';
            const obs = new MutationObserver(function () {
                stampTableDataLabels(table);
            });
            obs.observe(tbody, { childList: true, subtree: true });
        });
    }

    function ensureUnlockQuizModal() {
        let overlay = document.getElementById('admin-unlock-quiz-modal');
        if (overlay) return overlay;
        overlay = document.createElement('div');
        overlay.id = 'admin-unlock-quiz-modal';
        overlay.className = 'admin-modal-overlay';

        const card = document.createElement('div');
        card.className = 'admin-modal-card';
        card.style.border = '1px solid rgba(59, 130, 246, 0.5)';

        const title = document.createElement('h3');
        title.style.color = '#93c5fd';
        title.style.marginBottom = '0.4rem';
        title.style.fontSize = '1.08rem';
        title.textContent = '🔓 Unlock Student Quiz Directly';

        const desc = document.createElement('p');
        desc.id = 'unlock-quiz-modal-desc';
        desc.style.color = '#cbd5e1';
        desc.style.fontSize = '0.86rem';
        desc.style.marginBottom = '0.85rem';
        desc.textContent = 'Unlock this episode quiz for the selected student without altering verified watch seconds.';

        const reasonLabel = document.createElement('label');
        reasonLabel.setAttribute('for', 'unlock-quiz-modal-reason');
        reasonLabel.style.display = 'block';
        reasonLabel.style.fontSize = '0.78rem';
        reasonLabel.style.fontWeight = '700';
        reasonLabel.style.color = '#cbd5e1';
        reasonLabel.style.marginBottom = '0.35rem';
        reasonLabel.textContent = 'Optional Audit Reason:';

        const reasonInput = document.createElement('input');
        reasonInput.type = 'text';
        reasonInput.id = 'unlock-quiz-modal-reason';
        reasonInput.className = 'text-input';
        reasonInput.maxLength = 300;
        reasonInput.placeholder = 'e.g. Watched together with family on TV';
        reasonInput.style.marginBottom = '0.9rem';

        const btnRow = document.createElement('div');
        btnRow.style.display = 'flex';
        btnRow.style.justifyContent = 'flex-end';
        btnRow.style.gap = '0.6rem';
        btnRow.style.flexWrap = 'wrap';

        const cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'btn-ghost';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', function () {
            overlay.style.display = 'none';
        });

        const confirmBtn = document.createElement('button');
        confirmBtn.type = 'button';
        confirmBtn.id = 'unlock-quiz-modal-confirm';
        confirmBtn.className = 'btn-primary';
        confirmBtn.textContent = '🔓 Confirm Unlock Quiz';

        btnRow.appendChild(cancelBtn);
        btnRow.appendChild(confirmBtn);
        card.appendChild(title);
        card.appendChild(desc);
        card.appendChild(reasonLabel);
        card.appendChild(reasonInput);
        card.appendChild(btnRow);
        overlay.appendChild(card);
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) overlay.style.display = 'none';
        });
        document.body.appendChild(overlay);
        return overlay;
    }

    document.addEventListener('DOMContentLoaded', function () {
        observeAllAdminTables();
        ensureUnlockQuizModal();
        setTimeout(observeAllAdminTables, 800);
    });

    window.AdminMobileCards = {
        observeAllAdminTables: observeAllAdminTables,
        ensureUnlockQuizModal: ensureUnlockQuizModal
    };
})();
