// Handles video/transcript generation, async job polling, Compare Models (Set A/B), Test Connection, and Jump-to-moment links.
(function () {
    function formatSecondsToMmSs(rawSeconds) {
        const total = Math.max(0, Math.floor(Number(rawSeconds) || 0));
        const hours = Math.floor(total / 3600);
        const mins = Math.floor((total % 3600) / 60);
        const secs = total % 60;
        const mm = hours > 0 ? String(mins).padStart(2, '0') : String(mins);
        const ss = String(secs).padStart(2, '0');
        return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`;
    }

    function renderAiErrorWithRetry(resultTitleEl, resultContentEl, message, retryFn) {
        resultTitleEl.textContent = 'Generation Failed';
        resultTitleEl.style.color = '#fca5a5';
        clearElement(resultContentEl);

        const p = document.createElement('p');
        p.style.marginBottom = '0.65rem';
        p.style.color = '#fecaca';
        p.textContent = message;
        resultContentEl.appendChild(p);

        const retryBtn = document.createElement('button');
        retryBtn.type = 'button';
        retryBtn.id = 'btn-retry-ai-gen';
        retryBtn.className = 'btn-secondary';
        retryBtn.style.fontSize = '0.82rem';
        retryBtn.style.padding = '0.45rem 0.9rem';
        retryBtn.textContent = 'Retry Generation';
        retryBtn.addEventListener('click', () => (retryFn ? retryFn() : handleGenerateAiVideoQuiz()));
        resultContentEl.appendChild(retryBtn);
    }

    function renderAiSuccessSummary(resultTitleEl, resultContentEl, data, usedTranscript) {
        resultTitleEl.textContent = 'Draft Bank Generated (Saved as DRAFT)';
        resultTitleEl.style.color = '#6ee7b7';
        clearElement(resultContentEl);

        const summary = document.createElement('p');
        summary.style.marginBottom = '0.45rem';
        summary.textContent =
            `Saved ${data.savedDraftMcqs ?? data.savedCount ?? 0} verified draft MCQ(s) and ` +
            `${data.savedDraftOpenQuestions ?? 0} draft open question(s).`;
        resultContentEl.appendChild(summary);

        const note = document.createElement('p');
        note.style.fontSize = '0.82rem';
        note.style.color = 'var(--text-muted)';
        note.textContent = usedTranscript
            ? 'Generated using transcript + cross-model verification. Review DRAFT items below.'
            : 'Generated directly from the YouTube video. Use "Jump to moment" on DRAFT cards below to verify timestamps.';
        resultContentEl.appendChild(note);
    }

    async function handleGenerateAiVideoQuiz() {
        const episodeSelect = document.getElementById('ai-episode-select');
        const transcriptEl = document.getElementById('ai-transcript-input');
        const resultBox = document.getElementById('ai-result-box');
        const resultTitle = document.getElementById('ai-result-title');
        const resultContent = document.getElementById('ai-result-content');
        const btn = document.getElementById('btn-run-ai');

        const episodeId = episodeSelect ? episodeSelect.value : '';
        if (!episodeId || !resultBox || !resultTitle || !resultContent || !btn) return;

        const transcript = transcriptEl ? transcriptEl.value.trim() : '';
        if (transcript.length > 65000) {
            resultBox.style.display = 'block';
            renderAiErrorWithRetry(resultTitle, resultContent, 'Optional transcript exceeds 65,000 characters.');
            return;
        }

        const payload = transcript.length > 0 ? { transcript: transcript } : {};
        btn.disabled = true;
        btn.textContent = 'Analyzing YouTube video...';
        resultBox.style.display = 'block';
        resultTitle.textContent = 'Generating draft questions from YouTube video...';
        resultTitle.style.color = '#c4b5fd';
        clearElement(resultContent);

        try {
            const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/generate-bank`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok) {
                renderAiSuccessSummary(resultTitle, resultContent, data, transcript.length > 0);
                if (typeof loadEpisodeQuestionBank === 'function') await loadEpisodeQuestionBank();
                if (typeof loadEpisodes === 'function') await loadEpisodes();
            } else {
                const errMsg = `[HTTP ${res.status}] ` + (data.error || `Generation failed (HTTP ${res.status}).`);
                renderAiErrorWithRetry(resultTitle, resultContent, errMsg);
            }
        } catch (err) {
            renderAiErrorWithRetry(resultTitle, resultContent, 'Network error: ' + err.message);
        } finally {
            btn.disabled = false;
            btn.textContent = 'Generate from the YouTube video';
        }
    }

    function mountPipelineV2Controls() {
        const runBtn = document.getElementById('btn-run-ai');
        if (!runBtn || document.getElementById('pipeline-v2-toolbar')) return;

        const toolbar = document.createElement('div');
        toolbar.id = 'pipeline-v2-toolbar';
        toolbar.style.display = 'flex';
        toolbar.style.flexWrap = 'wrap';
        toolbar.style.gap = '0.65rem';
        toolbar.style.alignItems = 'center';
        toolbar.style.marginTop = '0.85rem';

        const modelSelect = document.createElement('select');
        modelSelect.id = 'ai-writer-model-select';
        modelSelect.className = 'select-input';
        modelSelect.style.width = '220px';
        [['gemini-3.8-flash', 'Writer: Gemini (gemini-3.8-flash)'], ['claude-sonnet-5-5', 'Writer: Claude (claude-sonnet-5-5)']].forEach(([val, label]) => {
            const opt = document.createElement('option');
            opt.value = val;
            opt.textContent = label;
            modelSelect.appendChild(opt);
        });

        const jobBtn = document.createElement('button');
        jobBtn.type = 'button';
        jobBtn.id = 'btn-start-gen-job';
        jobBtn.className = 'btn-primary';
        jobBtn.textContent = '⚙️ Run Async 3-Pass Job';
        jobBtn.addEventListener('click', startAsyncGenerationJob);

        const compareBtn = document.createElement('button');
        compareBtn.type = 'button';
        compareBtn.id = 'btn-compare-models';
        compareBtn.className = 'btn-ghost';
        compareBtn.textContent = '⚖️ Compare Models (Set A vs Set B)';
        compareBtn.addEventListener('click', runCompareModels);

        const testConnBtn = document.createElement('button');
        testConnBtn.type = 'button';
        testConnBtn.id = 'btn-test-ai-connection';
        testConnBtn.className = 'btn-ghost';
        testConnBtn.textContent = '🔌 Test connection';
        testConnBtn.addEventListener('click', runTestAiConnection);

        toolbar.appendChild(modelSelect);
        toolbar.appendChild(jobBtn);
        toolbar.appendChild(compareBtn);
        toolbar.appendChild(testConnBtn);
        runBtn.parentElement.appendChild(toolbar);

        const compareBox = document.createElement('div');
        compareBox.id = 'compare-models-box';
        compareBox.style.display = 'none';
        compareBox.style.marginTop = '1rem';
        runBtn.parentElement.parentElement.appendChild(compareBox);
    }

    async function startAsyncGenerationJob() {
        const epSelect = document.getElementById('ai-episode-select');
        const modelSelect = document.getElementById('ai-writer-model-select');
        const transcriptEl = document.getElementById('ai-transcript-input');
        const resultBox = document.getElementById('ai-result-box');
        const resultTitle = document.getElementById('ai-result-title');
        const resultContent = document.getElementById('ai-result-content');
        if (!epSelect || !epSelect.value || !resultBox) return;

        resultBox.style.display = 'block';
        resultTitle.textContent = 'Queueing Async Generation Job...';
        resultTitle.style.color = '#c4b5fd';
        clearElement(resultContent);

        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(epSelect.value)}/generation-jobs`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                writerModel: modelSelect ? modelSelect.value : 'gemini-3.8-flash',
                transcript: transcriptEl && transcriptEl.value.trim() ? transcriptEl.value.trim() : null
            })
        });
        const data = await res.json().catch(() => ({}));
        if (res.status === 202 && data.jobId) {
            pollJobStatus(epSelect.value, data.jobId);
        } else {
            renderAiErrorWithRetry(resultTitle, resultContent, data.error || `Failed to start job (HTTP ${res.status})`, startAsyncGenerationJob);
        }
    }

    async function pollJobStatus(episodeId, jobId) {
        const resultTitle = document.getElementById('ai-result-title');
        const resultContent = document.getElementById('ai-result-content');
        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/generation-jobs/${encodeURIComponent(jobId)}`);
        const job = await res.json().catch(() => ({}));
        if (!res.ok) return;

        resultTitle.textContent = `Job #${job.jobId}: ${job.status} (${job.stage || ''})`;
        clearElement(resultContent);
        const p = document.createElement('p');
        p.textContent = `Progress: ${job.progress ?? 0}% • Writer: ${job.writerModel || ''} • Saved MCQs: ${job.savedCount ?? 0}`;
        resultContent.appendChild(p);

        if (job.status === 'QUEUED' || job.status === 'RUNNING') {
            setTimeout(() => pollJobStatus(episodeId, jobId), 1200);
        } else if (job.status === 'DONE') {
            resultTitle.style.color = '#6ee7b7';
            if (typeof loadEpisodeQuestionBank === 'function') await loadEpisodeQuestionBank();
        } else if (job.status === 'FAILED') {
            renderAiErrorWithRetry(resultTitle, resultContent, job.sanitizedError || 'Job failed.', async () => {
                await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/generation-jobs/${encodeURIComponent(jobId)}/retry`, { method: 'POST' });
                if (typeof loadEpisodeQuestionBank === 'function') await loadEpisodeQuestionBank();
            });
        }
    }

    async function runTestAiConnection() {
        const resultBox = document.getElementById('ai-result-box');
        const resultTitle = document.getElementById('ai-result-title');
        const resultContent = document.getElementById('ai-result-content');
        if (!resultBox || !resultTitle || !resultContent) return;
        resultBox.style.display = 'block';
        resultTitle.textContent = 'Testing AI Provider Connections...';
        clearElement(resultContent);

        const res = await apiFetch('/api/admin/ai/test-connection', { method: 'POST' });
        const data = await res.json().catch(() => ({}));
        resultTitle.textContent = data.ok ? 'AI Connection Test Result' : 'AI Connection Warning';
        resultTitle.style.color = data.ok ? '#6ee7b7' : '#fca5a5';
        ['gemini', 'claude'].forEach(k => {
            if (data[k]) {
                const line = document.createElement('p');
                line.textContent = `${k.toUpperCase()}: ${data[k].ok ? 'OK' : 'FAILED'} — ${data[k].message || ''}`;
                resultContent.appendChild(line);
            }
        });
    }

    async function runCompareModels() {
        const epSelect = document.getElementById('ai-episode-select');
        const box = document.getElementById('compare-models-box');
        if (!epSelect || !epSelect.value || !box) return;
        if (!window.confirm('Cost warning: Compare Models runs both writer pipelines (Set A and Set B) and doubles token usage. Continue?')) return;

        box.style.display = 'block';
        clearElement(box);
        const loading = document.createElement('p');
        loading.textContent = 'Generating blind Set A and Set B (model names hidden until you choose)...';
        box.appendChild(loading);

        const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(epSelect.value)}/compare-models`, { method: 'POST' });
        const data = await res.json().catch(() => ({}));
        clearElement(box);
        if (!res.ok) {
            const err = document.createElement('p');
            err.style.color = '#fca5a5';
            err.textContent = data.error || 'Compare Models failed.';
            box.appendChild(err);
            return;
        }

        const grid = document.createElement('div');
        grid.style.display = 'grid';
        grid.style.gridTemplateColumns = '1fr 1fr';
        grid.style.gap = '1rem';

        [['SET_A', 'Set A (Model Hidden)', data.setA || []], ['SET_B', 'Set B (Model Hidden)', data.setB || []]].forEach(([code, title, list]) => {
            const col = document.createElement('div');
            col.style.background = 'var(--bg-subtle)';
            col.style.padding = '0.9rem';
            col.style.borderRadius = 'var(--radius-md)';
            const h4 = document.createElement('h4');
            h4.textContent = `${title} (${list.length} MCQs)`;
            col.appendChild(h4);
            list.slice(0, 5).forEach(q => {
                const p = document.createElement('p');
                p.style.fontSize = '0.82rem';
                p.style.marginTop = '0.35rem';
                p.textContent = '• ' + q.questionText;
                col.appendChild(p);
            });
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn-primary';
            btn.style.marginTop = '0.65rem';
            btn.textContent = `Approve ${code === 'SET_A' ? 'Set A' : 'Set B'} & Retire Other`;
            btn.addEventListener('click', async () => {
                const chooseRes = await apiFetch(`/api/admin/episodes/${encodeURIComponent(epSelect.value)}/compare-models/choose`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ chosenSet: code })
                });
                const chosenData = await chooseRes.json().catch(() => ({}));
                clearElement(box);
                const msg = document.createElement('p');
                msg.style.color = '#6ee7b7';
                const revealed = chosenData.revealedModels || {};
                msg.textContent = `Approved ${chosenData.chosenSet} (${chosenData.approvedCount ?? 0} questions) and retired ${chosenData.retiredCount ?? 0}. Revealed models — Set A: ${revealed.SET_A || ''}, Set B: ${revealed.SET_B || ''}.`;
                box.appendChild(msg);
                if (typeof loadEpisodeQuestionBank === 'function') await loadEpisodeQuestionBank();
            });
            col.appendChild(btn);
            grid.appendChild(col);
        });
        box.appendChild(grid);
    }

    function attachJumpLinks(containerId, items, youtubeVideoId) {
        const container = document.getElementById(containerId);
        if (!container || !Array.isArray(items) || items.length === 0) return;
        const cards = Array.from(container.children);
        if (cards.length !== items.length) return;
        cards.forEach((card, idx) => {
            const item = items[idx];
            if (!item || card.querySelector('.aq-jump-moment-row')) return;
            const ts = item.evidenceTimestamp;
            if (ts === null || ts === undefined || Number.isNaN(Number(ts)) || Number(ts) < 0) return;
            const seconds = Math.floor(Number(ts));
            const row = document.createElement('div');
            row.className = 'aq-jump-moment-row';
            row.style.marginTop = '0.45rem';
            row.style.fontSize = '0.78rem';
            if (youtubeVideoId) {
                const link = document.createElement('a');
                link.href = `https://www.youtube.com/watch?v=${encodeURIComponent(youtubeVideoId)}&t=${seconds}s`;
                link.target = '_blank';
                link.rel = 'noopener noreferrer';
                link.className = 'aq-jump-moment-link';
                link.style.color = '#38bdf8';
                link.style.fontWeight = '700';
                link.style.textDecoration = 'underline';
                link.textContent = `Jump to moment (${formatSecondsToMmSs(seconds)})`;
                row.appendChild(link);
            }
            const btnGroup = card.lastElementChild;
            if (btnGroup && btnGroup.querySelector('button')) card.insertBefore(row, btnGroup);
            else card.appendChild(row);
        });
    }

    function enhanceQuestionBankCards(bank) {
        if (!bank) return;
        attachJumpLinks('qbank-mcq-list', bank.questions || [], bank.youtubeVideoId || '');
        attachJumpLinks('qbank-open-list', bank.openQuestions || [], bank.youtubeVideoId || '');
    }

    document.addEventListener('DOMContentLoaded', () => mountPipelineV2Controls());
    window.handleGenerateAiVideoQuiz = handleGenerateAiVideoQuiz;
    window.enhanceQuestionBankCards = enhanceQuestionBankCards;
})();
