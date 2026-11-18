// Student wallet strip & prepaid credit display helper, plus mustChangePassword redirect enforcement.
(function () {
    'use strict';

    function checkMustChangePassword(userOrErrorBody) {
        if (userOrErrorBody && userOrErrorBody.mustChangePassword === true) {
            if (!window.location.pathname.startsWith('/change-password')) {
                window.location.replace('/change-password');
            }
            return true;
        }
        return false;
    }

    function renderEarningsStrip(data) {
        if (!data) return;
        const earnedEl = document.getElementById('val-earned');
        if (earnedEl) {
            earnedEl.textContent = '$' + Number(data.totalEarned || 0).toFixed(2);
        }
        const pendingEl = document.getElementById('val-pending-review');
        if (pendingEl) {
            pendingEl.textContent = '$' + Number(data.pendingReviewAmount || 0).toFixed(2);
        }
        const paidEl = document.getElementById('val-paid');
        if (paidEl) {
            paidEl.textContent = '$' + Number(data.totalPaid || 0).toFixed(2);
        }
        const owedEl = document.getElementById('val-owed');
        if (!owedEl) return;

        const owedContainer = owedEl.parentElement;
        const owedVal = Number(data.balanceOwed != null ? data.balanceOwed : 0);

        if (owedVal < 0) {
            const creditAmt = '$' + Math.abs(owedVal).toFixed(2);
            if (owedContainer) {
                owedContainer.textContent = '';
                const strong = document.createElement('strong');
                strong.id = 'val-owed';
                strong.style.color = '#6ee7b7';
                strong.textContent = 'Prepaid credit ' + creditAmt + ', applied against future earnings';
                owedContainer.appendChild(strong);
            } else {
                owedEl.textContent = 'Prepaid credit ' + creditAmt + ', applied against future earnings';
                owedEl.style.color = '#6ee7b7';
            }
        } else {
            if (owedContainer && owedContainer.firstChild === owedEl) {
                owedContainer.textContent = 'Owed: ';
                const strong = document.createElement('strong');
                strong.id = 'val-owed';
                strong.textContent = '$' + owedVal.toFixed(2);
                owedContainer.appendChild(strong);
            } else {
                owedEl.textContent = '$' + owedVal.toFixed(2);
                owedEl.style.color = '';
            }
        }
    }

    window.StudentWallet = {
        checkMustChangePassword: checkMustChangePassword,
        renderEarningsStrip: renderEarningsStrip
    };
})();
