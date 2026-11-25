// Admin REST API data helpers for student management, payouts, adjustments, unlocks, and password resets.
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
        apiFetch(url, options = {}) {
            return apiFetch(url, options);
        },
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
        fetchStudentOverview(studentId) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/overview`);
        },
        fetchAuditEvents(studentId) {
            const q = studentId ? `?studentId=${encodeURIComponent(studentId)}` : '';
            return jsonRequest(`/api/admin/audit-events${q}`);
        },
        recordPayout(payload) {
            return jsonRequest('/api/admin/payouts', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        },
        voidPayout(payoutId, reason) {
            return jsonRequest(`/api/admin/payouts/${encodeURIComponent(payoutId)}/void`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason })
            });
        },
        resetStudentPassword(studentId, customPassword, requireChangeOnNextLogin) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/password-reset`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    customPassword: customPassword || null,
                    requireChangeOnNextLogin: Boolean(requireChangeOnNextLogin)
                })
            });
        },
        unlockEpisodeVideo(studentId, episodeId) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/episodes/${encodeURIComponent(episodeId)}/unlock-video`, {
                method: 'POST'
            });
        },
        unlockEpisodeQuiz(studentId, episodeId, reason) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/episodes/${encodeURIComponent(episodeId)}/unlock-quiz`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: reason || null })
            });
        },
        lockEpisodeAgain(studentId, episodeId) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/episodes/${encodeURIComponent(episodeId)}/lock-again`, {
                method: 'POST'
            });
        },
        unlockNextEpisode(studentId, courseId) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/episodes/unlock-next`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ courseId: courseId || null })
            });
        },
        unlockAllEpisodes(studentId, courseId) {
            return jsonRequest(`/api/admin/students/${encodeURIComponent(studentId)}/episodes/unlock-all`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ courseId: courseId || null })
            });
        },
        markOpenAnswerRead(submissionId, feedback) {
            return jsonRequest(`/api/admin/open-answers/${encodeURIComponent(submissionId)}/read`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ feedback: feedback || null })
            });
        },
        importEpisodeTextSource(episodeId, text, sourceUrl, confirmSave) {
            return jsonRequest(`/api/admin/episodes/${encodeURIComponent(episodeId)}/text-source`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text, sourceUrl: sourceUrl || null, confirmSave: Boolean(confirmSave) })
            });
        },
        fetchEpisodeTextSourceMeta(episodeId) {
            return jsonRequest(`/api/admin/episodes/${encodeURIComponent(episodeId)}/text-source`);
        },
        triggerJsonDownload
    };
})();
