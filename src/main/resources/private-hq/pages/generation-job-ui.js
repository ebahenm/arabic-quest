// Episode search filter and live generation job status card (progress bar, elapsed timer, stage detail, Cancel/Reset & Retry).
(function () {
    'use strict';

    let elapsedTimerId = null;
    let activeJobStartMs = null;
    let cachedEpisodeOptions = [];

    function initEpisodeSearchFilter(onEpisodeChanged) {
        const searchInput = document.getElementById('ai-episode-search');
        const selectEl = document.getElementById('ai-episode-select');
        if (!searchInput || !selectEl || searchInput.dataset.bound === 'true') return;
        searchInput.dataset.bound = 'true';

        function syncCacheFromSelect() {
            const opts = Array.from(selectEl.options);
            if (opts.length > 0 && cachedEpisodeOptions.length === 0) {
                cachedEpisodeOptions = opts.map(function (o) {
                    return { value: o.value, text: o.textContent || '' };
                });
            }
        }

        const observer = new MutationObserver(function () {
            const opts = Array.from(selectEl.options);
            if (opts.length > cachedEpisodeOptions.length && !searchInput.value.trim()) {
                cachedEpisodeOptions = opts.map(function (o) {
                    return { value: o.value, text: o.textContent || '' };
                });
            }
        });
        observer.observe(selectEl, { childList: true });

        searchInput.addEventListener('input', function () {
            syncCacheFromSelect();
            if (cachedEpisodeOptions.length === 0) return;
            const query = (searchInput.value || '').trim().toLowerCase();
            const previousVal = selectEl.value;
            while (selectEl.firstChild) selectEl.removeChild(selectEl.firstChild);

            const filtered = cachedEpisodeOptions.filter(function (item) {
                return !query || item.text.toLowerCase().indexOf(query) !== -1 || String(item.value) === query;
            });

            const listToRender = filtered.length > 0 ? filtered : cachedEpisodeOptions;
            listToRender.forEach(function (item) {
                const opt = document.createElement('option');
                opt.value = item.value;
                opt.textContent = item.text;
                selectEl.appendChild(opt);
            });

            const stillHasPrev = listToRender.some(function (item) { return String(item.value) === String(previousVal); });
            selectEl.value = stillHasPrev ? previousVal : (listToRender[0] ? listToRender[0].value : '');
            if (selectEl.value && String(selectEl.value) !== String(previousVal) && typeof onEpisodeChanged === 'function') {
                onEpisodeChanged(selectEl.value);
            }
        });
    }

    function stopElapsedTimer() {
        if (elapsedTimerId) {
            clearInterval(elapsedTimerId);
            elapsedTimerId = null;
        }
    }

    function formatStageLabel(rawStage) {
        const s = rawStage || 'QUEUED';
        return s.replace('PASS_1_WRITER', 'Pass 1 Writer')
                .replace('PASS_2_VERIFIER', 'Pass 2 Verifier')
                .replace('PASS_3_BLIND_TEST', 'Pass 3 Blind Test')
                .replace('VERIFIED', 'Verified');
    }

    function computeElapsedSeconds(job) {
        if (job && job.startedAt) {
            const parsed = Date.parse(job.startedAt);
            if (!isNaN(parsed)) {
                return Math.max(0, Math.floor((Date.now() - parsed) / 1000));
            }
        }
        if (!activeJobStartMs) activeJobStartMs = Date.now();
        return Math.max(0, Math.floor((Date.now() - activeJobStartMs) / 1000));
    }

    function renderJobStatusCard(job, handlers) {
        const resBox = document.getElementById('ai-result-box');
        const resTitle = document.getElementById('ai-result-title');
        const resContent = document.getElementById('ai-result-content');
        if (!resBox || !resContent) return;
        resBox.classList.add('visible');
        while (resContent.firstChild) resContent.removeChild(resContent.firstChild);

        const isRunning = job.status === 'QUEUED' || job.status === 'RUNNING';
        const pct = Math.max(0, Math.min(100, Number(job.progress || 0)));
        if (isRunning && !activeJobStartMs) activeJobStartMs = Date.now();
        if (!isRunning) {
            stopElapsedTimer();
            activeJobStartMs = null;
        }

        if (resTitle) {
            resTitle.textContent = job.status === 'DONE'
                ? '✅ Async 3-Pass Generation Complete!'
                : (job.status === 'FAILED' ? '❌ Generation Job Stopped / Failed' : '⏳ Running 3-Pass Pipeline...');
        }

        const summaryLine = document.createElement('div');
        summaryLine.style.fontWeight = '700';
        summaryLine.textContent = 'Job #' + job.jobId + ' • ' + job.status + ' (' + (job.stage || '') + ') • Progress: ' + pct + '% • Saved MCQs: ' + (job.savedCount || 0);
        resContent.appendChild(summaryLine);

        const barTrack = document.createElement('div');
        barTrack.style.cssText = 'width:100%;height:10px;background:rgba(148,163,184,0.2);border-radius:999px;overflow:hidden;margin:0.55rem 0;';
        const barFill = document.createElement('div');
        barFill.id = 'ai-job-progress-bar';
        const barColor = job.status === 'FAILED' ? '#ef4444' : (job.status === 'DONE' ? '#10b981' : '#8b5cf6');
        barFill.style.cssText = 'height:100%;transition:width 0.35s ease;background:' + barColor + ';width:' + pct + '%;';
        barTrack.appendChild(barFill);
        resContent.appendChild(barTrack);

        const detailEl = document.createElement('div');
        detailEl.id = 'ai-job-stage-detail';
        detailEl.style.cssText = 'font-size:0.82rem;color:#cbd5e1;margin-bottom:0.45rem;';
        const updateDetailText = function () {
            const elapsedSec = computeElapsedSeconds(job);
            detailEl.textContent = formatStageLabel(job.stage) + ' • ' + elapsedSec + 's elapsed';
        };
        updateDetailText();
        resContent.appendChild(detailEl);

        if (isRunning) {
            stopElapsedTimer();
            elapsedTimerId = setInterval(updateDetailText, 1000);
        }

        if (job.sanitizedError) {
            const errDiv = document.createElement('div');
            errDiv.style.cssText = 'color:#fca5a5;margin-top:0.35rem;font-size:0.84rem;';
            errDiv.textContent = 'Reason: ' + job.sanitizedError;
            resContent.appendChild(errDiv);
        }

        const btnRow = document.createElement('div');
        btnRow.style.cssText = 'display:flex;gap:0.5rem;margin-top:0.55rem;flex-wrap:wrap;';

        if (isRunning && job.jobId && handlers && typeof handlers.onCancel === 'function') {
            const cancelBtn = document.createElement('button');
            cancelBtn.type = 'button';
            cancelBtn.id = 'btn-cancel-gen-job';
            cancelBtn.className = 'btn-secondary btn-sm';
            cancelBtn.style.cssText = 'border-color:rgba(239,68,68,0.5);color:#fca5a5;';
            cancelBtn.textContent = '⏹ Cancel / Reset Job #' + job.jobId;
            cancelBtn.addEventListener('click', function () {
                cancelBtn.disabled = true;
                cancelBtn.textContent = 'Cancelling...';
                handlers.onCancel(job.jobId);
            });
            btnRow.appendChild(cancelBtn);
        }

        if (job.status === 'FAILED' && job.jobId && handlers && typeof handlers.onRetry === 'function') {
            const retryBtn = document.createElement('button');
            retryBtn.type = 'button';
            retryBtn.id = 'btn-retry-gen-job';
            retryBtn.className = 'btn-secondary btn-sm';
            retryBtn.textContent = '🔄 Retry Job';
            retryBtn.addEventListener('click', function () {
                retryBtn.disabled = true;
                handlers.onRetry(job.jobId);
            });
            btnRow.appendChild(retryBtn);
        }

        if (btnRow.childNodes.length > 0) {
            resContent.appendChild(btnRow);
        }
    }

    window.GenerationJobUi = {
        initEpisodeSearchFilter: initEpisodeSearchFilter,
        renderJobStatusCard: renderJobStatusCard,
        stopElapsedTimer: stopElapsedTimer
    };
})();
