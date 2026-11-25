// Question Bank Pipeline v2 controls: Async Generation Job status/cancel/retry, Compare Models, and Needs Review banner.
(function () {
    'use strict';

    let activePollTimer = null;

    function injectPipelineV2Controls() {
        const aiBtn = document.getElementById('btn-run-ai');
        if (!aiBtn || document.getElementById('pipeline-v2-toolbar')) return;

        const bar = document.createElement('div');
        bar.id = 'pipeline-v2-toolbar';
        bar.style.display = 'flex';
        bar.style.flexWrap = 'wrap';
        bar.style.gap = '0.6rem';
        bar.style.alignItems = 'center';
        bar.style.marginTop = '0.75rem';

        const modelSelect = document.createElement('select');
        modelSelect.id = 'ai-writer-model-select';
        modelSelect.className = 'select-input';
        modelSelect.style.maxWidth = '240px';
        [
            { value: 'claude-sonnet-5-5', label: 'Writer: Claude Sonnet 5.5 (Primary)' },
            { value: 'gemini-3.8-flash', label: 'Writer: Gemini 3.8 Flash (Fallback)' }
        ].forEach(opt => {
            const o = document.createElement('option');
            o.value = opt.value;
            o.textContent = opt.label;
            modelSelect.appendChild(o);
        });

        const asyncJobBtn = document.createElement('button');
        asyncJobBtn.type = 'button';
        asyncJobBtn.id = 'btn-start-async-job';
        asyncJobBtn.className = 'btn-primary btn-sm';
        asyncJobBtn.textContent = '🚀 Run Async 3-Pass Job';
        asyncJobBtn.addEventListener('click', startAsyncGenerationJob);

        const compareBtn = document.createElement('button');
        compareBtn.type = 'button';
        compareBtn.id = 'btn-compare-models';
        compareBtn.className = 'btn-secondary btn-sm';
        compareBtn.textContent = '⚖️ Compare Models (2x Cost)';
        compareBtn.addEventListener('click', handleCompareModels);

        bar.appendChild(modelSelect);
        bar.appendChild(asyncJobBtn);
        bar.appendChild(compareBtn);
        aiBtn.parentNode.appendChild(bar);

        const reviewBanner = document.createElement('div');
        reviewBanner.id = 'qb-needs-review-banner';
        reviewBanner.className = 'feedback-msg error';
        reviewBanner.style.display = 'none';
        reviewBanner.style.marginTop = '0.75rem';
        aiBtn.parentNode.appendChild(reviewBanner);

        const compareBox = document.createElement('div');
        compareBox.id = 'qb-compare-models-box';
        compareBox.style.display = 'none';
        compareBox.style.marginTop = '0.85rem';
        compareBox.style.padding = '0.9rem';
        compareBox.style.borderRadius = '10px';
        compareBox.style.border = '1px solid rgba(139, 92, 246, 0.35)';
        compareBox.style.background = 'rgba(15, 23, 42, 0.65)';
        aiBtn.parentNode.appendChild(compareBox);
    }

    function getSelectedEpisodeId() {
        const sel = document.getElementById('ai-episode-select');
        return sel && sel.value ? sel.value : null;
    }

    async function startAsyncGenerationJob() {
        const episodeId = getSelectedEpisodeId();
        if (!episodeId || !window.AdminApi) return;

        const modelSelect = document.getElementById('ai-writer-model-select');
        const transcriptInput = document.getElementById('ai-transcript-input');
        const writerModel = modelSelect ? modelSelect.value : 'claude-sonnet-5-5';
        const transcript = transcriptInput && transcriptInput.value.trim() ? transcriptInput.value.trim() : null;

        const btn = document.getElementById('btn-start-async-job');
        if (btn) btn.disabled = true;

        try {
            const res = await window.AdminApi.apiFetch('/api/admin/episodes/' + episodeId + '/generation-jobs', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ writerModel: writerModel, transcript: transcript })
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                if (res.status === 409 && data.jobId) {
                    showJobStatusBox({ jobId: data.jobId, status: 'RUNNING', stage: 'ALREADY_RUNNING', progress: 0, savedCount: 0, sanitizedError: 'A generation job is already running for this episode. Showing live progress below.' }, episodeId);
                    pollGenerationJob(episodeId, data.jobId);
                    return;
                }
                showJobStatusBox({
                    jobId: data.jobId || '—',
                    status: 'FAILED',
                    stage: 'CONFLICT',
                    progress: 0,
                    savedCount: 0,
                    sanitizedError: data.error || ('Failed to start job (HTTP ' + res.status + ')')
                }, episodeId);
                return;
            }
            showJobStatusBox(data, episodeId);
            pollGenerationJob(episodeId, data.jobId);
        } catch (err) {
            showJobStatusBox({
                jobId: '—', status: 'FAILED', stage: 'NETWORK_ERROR', progress: 0, savedCount: 0,
                sanitizedError: 'Network error starting generation job.'
            }, episodeId);
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    function pollGenerationJob(episodeId, jobId) {
        if (activePollTimer) clearInterval(activePollTimer);
        const fetchOnce = async () => {
            try {
                const res = await window.AdminApi.apiFetch('/api/admin/episodes/' + episodeId + '/generation-jobs/' + jobId);
                if (!res.ok) return;
                const job = await res.json();
                showJobStatusBox(job, episodeId);
                if (job.status === 'DONE' || job.status === 'FAILED') {
                    clearInterval(activePollTimer);
                    activePollTimer = null;
                    if (typeof window.loadEpisodeQuestionBank === 'function') window.loadEpisodeQuestionBank(episodeId);
                    if (window.GeneratorCardUi && typeof window.GeneratorCardUi.refreshEpisodeStatusLine === 'function') window.GeneratorCardUi.refreshEpisodeStatusLine();
                }
            } catch (e) {
                clearInterval(activePollTimer);
                activePollTimer = null;
            }
        };
        fetchOnce();
        activePollTimer = setInterval(fetchOnce, 2000);
    }

    async function cancelActiveJob(episodeId, jobId) {
        if (!window.AdminApi || !jobId) return;
        if (activePollTimer) {
            clearInterval(activePollTimer);
            activePollTimer = null;
        }
        try {
            const res = await window.AdminApi.apiFetch('/api/admin/generation-jobs/' + jobId + '/cancel', { method: 'POST' });
            const data = await res.json().catch(() => ({}));
            if (res.ok) {
                showJobStatusBox(data, episodeId);
            }
        } catch (e) {}
    }

    async function retryFailedJob(episodeId, jobId) {
        if (!window.AdminApi) return;
        try {
            const res = await window.AdminApi.apiFetch('/api/admin/episodes/' + episodeId + '/generation-jobs/' + jobId + '/retry', {
                method: 'POST'
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok && data.jobId) {
                showJobStatusBox(data, episodeId);
                pollGenerationJob(episodeId, data.jobId);
            } else if (res.status === 409 && data.jobId) {
                pollGenerationJob(episodeId, data.jobId);
            }
        } catch (e) {}
    }

    function showJobStatusBox(job, episodeId) {
        if (window.GenerationJobUi && typeof window.GenerationJobUi.renderJobStatusCard === 'function') {
            window.GenerationJobUi.renderJobStatusCard(job, {
                onCancel: function (jid) { cancelActiveJob(episodeId, jid); },
                onRetry: function (jid) { retryFailedJob(episodeId, jid); }
            });
        }
    }

    async function handleCompareModels() {
        const episodeId = getSelectedEpisodeId();
        if (!episodeId || !window.AdminApi) return;
        if (!window.confirm('Compare Models runs both Gemini and Claude pipelines (Set A & Set B) and doubles token usage. Continue?')) {
            return;
        }
        const compareBox = document.getElementById('qb-compare-models-box');
        if (!compareBox) return;
        compareBox.style.display = 'block';
        while (compareBox.firstChild) compareBox.removeChild(compareBox.firstChild);
        const loading = document.createElement('p');
        loading.textContent = 'Running Set A & Set B pipelines (blind model comparison)...';
        compareBox.appendChild(loading);

        try {
            const res = await window.AdminApi.apiFetch('/api/admin/episodes/' + episodeId + '/compare-models', { method: 'POST' });
            const data = await res.json().catch(() => ({}));
            while (compareBox.firstChild) compareBox.removeChild(compareBox.firstChild);
            if (!res.ok) {
                const err = document.createElement('p');
                err.style.color = '#fca5a5';
                err.textContent = data.error || ('Compare Models failed (HTTP ' + res.status + ')');
                compareBox.appendChild(err);
                return;
            }
            renderCompareModelsResult(episodeId, data, compareBox);
        } catch (e) {
            while (compareBox.firstChild) compareBox.removeChild(compareBox.firstChild);
            const err = document.createElement('p');
            err.textContent = 'Network error running Compare Models.';
            compareBox.appendChild(err);
        }
    }

    function renderCompareModelsResult(episodeId, data, container) {
        const header = document.createElement('h4');
        header.style.marginBottom = '0.5rem';
        header.textContent = 'Blind Comparison Ready — Choose Winning Set (Models Revealed After Choice)';
        container.appendChild(header);

        const summary = document.createElement('p');
        summary.style.fontSize = '0.85rem';
        summary.style.marginBottom = '0.65rem';
        const hasSetC = Array.isArray(data.setC);
        summary.textContent = 'Set A: ' + (data.setA ? data.setA.length : 0) + ' draft MCQs • Set B: ' + (data.setB ? data.setB.length : 0) + ' draft MCQs'
            + (hasSetC ? (' • Set C: ' + data.setC.length + ' draft MCQs') : '');
        container.appendChild(summary);

        const btnRow = document.createElement('div');
        btnRow.style.display = 'flex';
        btnRow.style.gap = '0.6rem';
        (hasSetC ? ['SET_A', 'SET_B', 'SET_C'] : ['SET_A', 'SET_B']).forEach(setKey => {
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'btn-primary btn-sm';
            b.textContent = 'Approve ' + setKey.replace('_', ' ') + ' & Retire Other';
            b.addEventListener('click', () => chooseComparedSet(episodeId, setKey, container));
            btnRow.appendChild(b);
        });
        container.appendChild(btnRow);
    }

    async function chooseComparedSet(episodeId, chosenSet, container) {
        const res = await window.AdminApi.apiFetch('/api/admin/episodes/' + episodeId + '/compare-models/choose', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ chosenSet: chosenSet })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) return;
        while (container.firstChild) container.removeChild(container.firstChild);
        const done = document.createElement('p');
        done.style.color = '#6ee7b7';
        const rev = data.revealedModels || {};
        done.textContent = 'Chosen ' + data.chosenSet + ' (' + data.approvedCount + ' approved, ' + data.retiredCount +
            ' retired). Revealed: Set A = ' + (rev.SET_A || '?') + ', Set B = ' + (rev.SET_B || '?') + (rev.SET_C ? (', Set C = ' + rev.SET_C) : '');
        container.appendChild(done);
        if (typeof window.loadEpisodeQuestionBank === 'function') {
            window.loadEpisodeQuestionBank(episodeId);
        }
    }

    function formatTimestampMmSs(totalSeconds) {
        if (totalSeconds === null || totalSeconds === undefined || isNaN(totalSeconds) || totalSeconds < 0) return null;
        const sec = Math.floor(Number(totalSeconds));
        return String(Math.floor(sec / 60)).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0');
    }

    function buildJumpToMomentLink(youtubeVideoId, evidenceTimestamp) {
        if (!youtubeVideoId || evidenceTimestamp === null || evidenceTimestamp === undefined || evidenceTimestamp < 0) return null;
        const sec = Math.floor(Number(evidenceTimestamp));
        const link = document.createElement('a');
        link.href = 'https://www.youtube.com/watch?v=' + encodeURIComponent(youtubeVideoId) + '&t=' + sec + 's';
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = '⏱ ' + formatTimestampMmSs(sec) + ' (Jump to moment)';
        return link;
    }

    window.QuestionBankManager = {
        formatTimestampMmSs: formatTimestampMmSs,
        buildJumpToMomentLink: buildJumpToMomentLink
    };

    document.addEventListener('DOMContentLoaded', function () {
        injectPipelineV2Controls();
        if (window.GenerationJobUi && typeof window.GenerationJobUi.initEpisodeSearchFilter === 'function') {
            window.GenerationJobUi.initEpisodeSearchFilter(function (newEpisodeId) {
                if (typeof window.loadEpisodeQuestionBank === 'function') {
                    window.loadEpisodeQuestionBank(newEpisodeId);
                }
            });
        }
    });
})();
