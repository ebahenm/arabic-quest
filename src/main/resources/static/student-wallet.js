// Student wallet Earned display helper and mustChangePassword redirect enforcement.
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
    }

    window.StudentWallet = {
        checkMustChangePassword: checkMustChangePassword,
        renderEarningsStrip: renderEarningsStrip
    };
})();
