// Independent status pills (Transcript, Bank, Today's Usage) for the Generate Episode Quiz card using Promise.allSettled.
(function () {
    'use strict';

    function doAdminFetch(url, opts) {
        if (window.AdminApi && typeof window.AdminApi.apiFetch === 'function') return window.AdminApi.apiFetch(url, opts);
        if (typeof apiFetch === 'function') return apiFetch(url, opts);
        return Promise.reject(new Error('Admin apiFetch unavailable'));
    }

    async function refreshEpisodeStatusLine() {
        var selectEl = document.getElementById('ai-episode-select');
        var trPill = document.getElementById('gen-pill-transcript');
        var bankPill = document.getElementById('gen-pill-bank');
        if (!selectEl || !trPill || !bankPill) return;

        var episodeId = selectEl.value;
        trPill.className = 'gen-status-pill';
        bankPill.className = 'gen-status-pill';
        if (!episodeId) {
            trPill.textContent = 'Transcript: select an episode';
            bankPill.textContent = 'Bank: select an episode';
            return;
        }

        trPill.textContent = 'Transcript: checking...';
        bankPill.textContent = 'Bank: checking...';

        var settled = await Promise.allSettled([
            doAdminFetch('/api/admin/episodes/' + encodeURIComponent(episodeId) + '/transcript'),
            doAdminFetch('/api/admin/episodes/' + encodeURIComponent(episodeId) + '/question-bank?status=ALL&type=ALL&reportedOnly=false')
        ]);

        // 1. Transcript pill (independent)
        if (settled[0].status === 'fulfilled') {
            var trRes = settled[0].value;
            if (trRes && trRes.ok) {
                try {
                    var tr = await trRes.json();
                    if (tr.hasTranscript) {
                        trPill.className = 'gen-status-pill gen-status-ok';
                        trPill.textContent = 'Transcript: saved (' + Number(tr.wordCount || 0).toLocaleString() + ' words)';
                    } else {
                        trPill.className = 'gen-status-pill gen-status-warn';
                        trPill.textContent = 'No transcript yet: upload one in Transcripts';
                    }
                } catch (parseErr) {
                    console.error('Transcript status JSON parse error:', parseErr);
                    trPill.className = 'gen-status-pill gen-status-err';
                    trPill.textContent = 'Transcript: error (invalid JSON)';
                }
            } else {
                var trCode = trRes ? trRes.status : 'unknown';
                console.error('Transcript status HTTP error for episode ' + episodeId + ': HTTP ' + trCode);
                trPill.className = 'gen-status-pill gen-status-err';
                trPill.textContent = 'Transcript: error (HTTP ' + trCode + ')';
            }
        } else {
            console.error('Transcript status request failed:', settled[0].reason);
            trPill.className = 'gen-status-pill gen-status-err';
            trPill.textContent = 'Transcript: error (network)';
        }

        // 2. Bank pill (independent)
        if (settled[1].status === 'fulfilled') {
            var bankRes = settled[1].value;
            if (bankRes && bankRes.ok) {
                try {
                    var bank = await bankRes.json();
                    var approved = Number(bank.approvedMcqCount || 0) + Number(bank.approvedOpenCount || 0);
                    var draft = Number(bank.draftMcqCount || 0) + Number(bank.draftOpenCount || 0);
                    bankPill.textContent = 'Bank: ' + approved + ' approved • ' + draft + ' draft';
                } catch (parseErr) {
                    console.error('Question bank status JSON parse error:', parseErr);
                    bankPill.className = 'gen-status-pill gen-status-err';
                    bankPill.textContent = 'Bank: error (invalid JSON)';
                }
            } else {
                var bkCode = bankRes ? bankRes.status : 'unknown';
                console.error('Question bank status HTTP error for episode ' + episodeId + ': HTTP ' + bkCode);
                bankPill.className = 'gen-status-pill gen-status-err';
                bankPill.textContent = 'Bank: error (HTTP ' + bkCode + ')';
            }
        } else {
            console.error('Question bank status request failed:', settled[1].reason);
            bankPill.className = 'gen-status-pill gen-status-err';
            bankPill.textContent = 'Bank: error (network)';
        }
    }

    function setUsage(used, limit) {
        var el = document.getElementById('gen-usage-line');
        if (!el) return;
        el.className = 'gen-status-pill';
        el.textContent = 'Today: ' + String(used) + ' of ' + String(limit) + ' calls';
    }

    function setUsageUnavailable() {
        var el = document.getElementById('gen-usage-line');
        if (!el) return;
        el.className = 'gen-status-pill gen-status-warn';
        el.textContent = 'Today: unavailable';
    }

    window.GeneratorStatusPills = {
        doAdminFetch: doAdminFetch,
        refreshEpisodeStatusLine: refreshEpisodeStatusLine,
        setUsage: setUsage,
        setUsageUnavailable: setUsageUnavailable
    };
})();
