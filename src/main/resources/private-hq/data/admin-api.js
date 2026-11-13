// Admin REST API data helpers for student management, payouts, adjustments, and reading list.
(function () {
    async function jsonRequest(url, options = {}) {
        const res = await apiFetch(url, options);
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            throw new Error(data.error || data.message || `Request failed (${res.status})`);
        }
        return data;
    }

    function triggerJsonDownload(filename, payload) {
        const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
        const blobUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(blobUrl), 1500);
    }

    window.AdminApi = {
        setStudentEnabled(studentId, enabled) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/enabled`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: Boolean(enabled) })
            });
        },
        exportStudentRecord(studentId) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/export`);
        },
        deleteStudentPermanently(studentId, confirmUsername) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}`, {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ confirmUsername })
            });
        },
        fetchStudentEarnings(username) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(username)}/earnings`);
        },
        voidPayout(payoutId, reason) {
            return jsonRequest(`/api/admin/payouts/${encodeURIComponent(payoutId)}/void`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason })
            });
        },
        markOpenAnswerRead(submissionId, feedback) {
            return jsonRequest(`/api/admin/open-answers/${encodeURIComponent(submissionId)}/read`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ feedback: feedback || null })
            });
        },
        triggerJsonDownload
    };
})();
