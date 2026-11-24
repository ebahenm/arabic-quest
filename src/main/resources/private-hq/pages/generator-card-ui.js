// Simplifies the Question Bank "Generate Episode Quiz" card: status line, wide writer select, green primary CTA, and collapsed Advanced tools.
(function () {
    'use strict';

    var STORAGE_KEY = 'aq_admin_ai_provider_status_v1';
    var WRITER_PROVIDERS = [
        { value: 'claude-sonnet-5-5', baseLabel: 'Claude Sonnet 5.5 (Primary)', providerKey: 'claude' },
        { value: 'gemini-3.8-flash', baseLabel: 'Gemini 3.8 Flash (Fallback)', providerKey: 'gemini' }
        // Future extra writer options (e.g. DeepSeek) can be appended via GeneratorCardUi.registerWriterOption(...)
    ];

    function injectStyles() {
        if (document.getElementById('generator-card-styles')) return;
        var style = document.createElement('style');
        style.id = 'generator-card-styles';
        style.textContent =
            '#generator-card-section .gen-episode-wrap{width:100%;margin-bottom:.75rem}' +
            '#generator-card-section #ai-episode-search,#generator-card-section #ai-episode-select{width:100%;max-width:100%;box-sizing:border-box}' +
            '#gen-episode-status-line{display:flex;flex-wrap:wrap;gap:.5rem 1.25rem;align-items:center;padding:.55rem .85rem;margin:.5rem 0 .9rem;border-radius:8px;background:var(--bg-subtle);border:1px solid var(--border);font-size:.84rem;color:var(--text-muted)}' +
            '#gen-episode-status-line .gen-status-ok{color:#6ee7b7;font-weight:700}' +
            '#gen-episode-status-line .gen-status-warn{color:#fcd34d;font-weight:700}' +
            '#gen-primary-row{display:flex;flex-wrap:wrap;gap:.85rem;align-items:flex-end;margin-bottom:.75rem}' +
            '#gen-writer-group{flex:1 1 320px;min-width:260px;max-width:480px}' +
            '#gen-writer-group label{display:block;font-size:.8rem;font-weight:700;color:var(--text-muted);margin-bottom:.3rem}' +
            '#generator-card-section #ai-writer-model-select{width:100%;max-width:100%;min-width:260px;box-sizing:border-box}' +
            '#generator-card-section #btn-start-async-job{background:linear-gradient(135deg,#10b981,#059669);color:#fff;border:none;padding:.72rem 1.45rem;font-size:.92rem;font-weight:800;border-radius:8px;cursor:pointer;white-space:nowrap}' +
            '#generator-card-section #btn-start-async-job:hover:not(:disabled){filter:brightness(1.08)}' +
            '#generator-card-section #btn-start-async-job:disabled{opacity:.6;cursor:not-allowed}' +
            '#gen-usage-line{font-size:.8rem;color:var(--text-muted);margin-bottom:.75rem}' +
            '#gen-advanced-panel{margin-top:1rem;border:1px solid var(--border);border-radius:10px;background:var(--bg-subtle);padding:.75rem 1rem}' +
            '#gen-advanced-panel>summary{cursor:pointer;font-weight:700;font-size:.88rem;color:var(--text-muted);user-select:none}' +
            '#gen-advanced-body{display:flex;flex-direction:column;gap:1rem;margin-top:.9rem;padding-top:.85rem;border-top:1px solid var(--border)}' +
            '.gen-adv-row{display:flex;flex-wrap:wrap;gap:.65rem;align-items:center}' +
            '.gen-adv-note{font-size:.8rem;color:var(--text-muted)}' +
            '#gen-ai-test-results{display:none;padding:.65rem .85rem;border-radius:8px;border:1px solid var(--border);background:rgba(15,23,42,.55);font-size:.83rem;line-height:1.5}' +
            '@media(max-width:900px){#gen-primary-row{flex-direction:column;align-items:stretch}#gen-writer-group{max-width:100%}#generator-card-section #btn-start-async-job{width:100%}}';
        document.head.appendChild(style);
    }

    function shortProviderReason(statusObj) {
        if (!statusObj) return '';
        if (statusObj.configured === false) return 'no API key';
        if (statusObj.ok === false) return 'connection failed';
        return '';
    }

    function applyProviderStatuses(payload) {
        var selectEl = document.getElementById('ai-writer-model-select');
        if (!selectEl) return;
        var prevVal = selectEl.value;
        while (selectEl.firstChild) selectEl.removeChild(selectEl.firstChild);

        var firstWorkingValue = null;
        WRITER_PROVIDERS.forEach(function (item) {
            var st = payload && item.providerKey ? payload[item.providerKey] : null;
            var reason = shortProviderReason(st);
            var opt = document.createElement('option');
            opt.value = item.value;
            opt.textContent = reason ? (item.baseLabel + ' — ' + reason) : item.baseLabel;
            if (reason) opt.disabled = true;
            else if (!firstWorkingValue) firstWorkingValue = item.value;
            selectEl.appendChild(opt);
        });

        var prevStillEnabled = Array.from(selectEl.options).some(function (o) { return o.value === prevVal && !o.disabled; });
        if (prevStillEnabled) selectEl.value = prevVal;
        else if (firstWorkingValue) selectEl.value = firstWorkingValue;
    }

    function renderConnectionResults(data, isErrorText) {
        var box = document.getElementById('gen-ai-test-results');
        if (!box) return;
        while (box.firstChild) box.removeChild(box.firstChild);
        box.style.display = 'block';
        if (isErrorText) {
            var err = document.createElement('div');
            err.style.color = '#fca5a5';
            err.textContent = isErrorText;
            box.appendChild(err);
            return;
        }
        ['claude', 'gemini'].forEach(function (key) {
            var st = data && data[key];
            if (!st) return;
            var line = document.createElement('div');
            line.style.color = st.ok ? '#6ee7b7' : '#fca5a5';
            var label = key === 'claude' ? 'Claude' : 'Gemini';
            var statusTag = st.ok ? 'OK' : (st.configured === false ? 'No API key' : 'Failed');
            line.textContent = label + ' (' + (st.model || 'default') + '): ' + statusTag + (st.message ? ' — ' + st.message : '');
            box.appendChild(line);
        });
    }

    async function handleTestAiConnections() {
        var btn = document.getElementById('btn-test-ai-connections');
        if (btn) { btn.disabled = true; btn.textContent = 'Testing AI connections...'; }
        try {
            var res = await window.AdminApi.apiFetch('/api/admin/ai/test-connection', { method: 'POST' });
            var data = await res.json().catch(function () { return {}; });
            if (!res.ok) {
                renderConnectionResults(null, data.error || data.message || ('Connection test failed (HTTP ' + res.status + ')'));
                return;
            }
            try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data)); } catch (_) {}
            renderConnectionResults(data, null);
            applyProviderStatuses(data);
        } catch (_) {
            renderConnectionResults(null, 'Network error while testing AI connections.');
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = 'Test AI Connections'; }
        }
    }

    async function refreshEpisodeStatusLine() {
        var selectEl = document.getElementById('ai-episode-select');
        var statusLine = document.getElementById('gen-episode-status-line');
        if (!selectEl || !statusLine || !window.AdminApi) return;

        var episodeId = selectEl.value;
        while (statusLine.firstChild) statusLine.removeChild(statusLine.firstChild);
        if (!episodeId) {
            statusLine.textContent = 'Select an episode to view transcript and question bank status.';
            return;
        }

        var trSpan = document.createElement('span');
        var bankSpan = document.createElement('span');
        trSpan.textContent = 'Transcript: checking...';
        bankSpan.textContent = 'Bank: checking...';
        statusLine.appendChild(trSpan);
        statusLine.appendChild(bankSpan);

        try {
            var results = await Promise.all([
                window.AdminApi.apiFetch('/api/admin/episodes/' + encodeURIComponent(episodeId) + '/transcript'),
                window.AdminApi.apiFetch('/api/admin/episodes/' + encodeURIComponent(episodeId) + '/question-bank?status=ALL&type=ALL&reportedOnly=false')
            ]);
            if (results[0] && results[0].ok) {
                var tr = await results[0].json();
                if (tr.hasTranscript) {
                    trSpan.className = 'gen-status-ok';
                    trSpan.textContent = 'Transcript: saved (' + Number(tr.wordCount || 0).toLocaleString() + ' words)';
                } else {
                    trSpan.className = 'gen-status-warn';
                    trSpan.textContent = 'No transcript yet: upload one in Transcripts';
                }
            }
            if (results[1] && results[1].ok) {
                var bank = await results[1].json();
                var approved = Number(bank.approvedMcqCount || 0) + Number(bank.approvedOpenCount || 0);
                var draft = Number(bank.draftMcqCount || 0) + Number(bank.draftOpenCount || 0);
                bankSpan.textContent = 'Current bank: ' + approved + ' approved, ' + draft + ' draft';
            }
        } catch (_) {
            trSpan.textContent = 'Transcript: status unavailable';
        }
    }

    function setUsage(used, limit) {
        var el = document.getElementById('gen-usage-line');
        if (el) el.textContent = 'Today: ' + String(used) + ' of ' + String(limit) + ' calls';
    }

    function registerWriterOption(opt) {
        if (!opt || !opt.value || !opt.baseLabel) return;
        if (WRITER_PROVIDERS.some(function (p) { return p.value === opt.value; })) return;
        WRITER_PROVIDERS.push(opt);
        var cached = null;
        try { cached = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null'); } catch (_) {}
        applyProviderStatuses(cached);
    }

    function enhanceGeneratorCard() {
        var card = document.getElementById('generator-card-section');
        var epSelect = document.getElementById('ai-episode-select');
        var writerSelect = document.getElementById('ai-writer-model-select');
        var asyncBtn = document.getElementById('btn-start-async-job');
        if (!card || !epSelect || !writerSelect || !asyncBtn || card.dataset.genEnhanced === 'true') return;
        card.dataset.genEnhanced = 'true';
        injectStyles();

        var epWrap = epSelect.parentNode;
        if (epWrap) epWrap.classList.add('gen-episode-wrap');

        var statusLine = document.createElement('div');
        statusLine.id = 'gen-episode-status-line';
        statusLine.setAttribute('role', 'status');
        epWrap.insertAdjacentElement('afterend', statusLine);

        var primaryRow = document.createElement('div');
        primaryRow.id = 'gen-primary-row';
        var writerGroup = document.createElement('div');
        writerGroup.id = 'gen-writer-group';
        writerGroup.setAttribute('data-extra-writers-slot', 'true');
        var writerLabel = document.createElement('label');
        writerLabel.setAttribute('for', 'ai-writer-model-select');
        writerLabel.textContent = 'Writer';
        writerGroup.appendChild(writerLabel);
        writerSelect.style.maxWidth = '100%';
        writerGroup.appendChild(writerSelect);

        asyncBtn.textContent = 'Generate quiz';
        primaryRow.appendChild(writerGroup);
        primaryRow.appendChild(asyncBtn);
        statusLine.insertAdjacentElement('afterend', primaryRow);

        var usageLine = document.createElement('div');
        usageLine.id = 'gen-usage-line';
        usageLine.setAttribute('data-usage-slot', 'true');
        usageLine.textContent = 'Today: — of — calls';
        primaryRow.insertAdjacentElement('afterend', usageLine);

        var details = document.createElement('details');
        details.id = 'gen-advanced-panel';
        var summary = document.createElement('summary');
        summary.textContent = 'Advanced';
        details.appendChild(summary);
        var advBody = document.createElement('div');
        advBody.id = 'gen-advanced-body';

        var compareBtn = document.getElementById('btn-compare-models');
        var compareBox = document.getElementById('qb-compare-models-box');
        if (compareBtn) {
            var compareRow = document.createElement('div');
            compareRow.className = 'gen-adv-row';
            compareRow.appendChild(compareBtn);
            var costNote = document.createElement('span');
            costNote.className = 'gen-adv-note';
            costNote.textContent = 'Cost warning: runs both Gemini and Claude (2x API cost).';
            compareRow.appendChild(costNote);
            advBody.appendChild(compareRow);
        }
        if (compareBox) advBody.appendChild(compareBox);

        var testRow = document.createElement('div');
        testRow.className = 'gen-adv-row';
        var testBtn = document.createElement('button');
        testBtn.type = 'button';
        testBtn.id = 'btn-test-ai-connections';
        testBtn.className = 'btn-secondary btn-sm';
        testBtn.textContent = 'Test AI Connections';
        testBtn.addEventListener('click', handleTestAiConnections);
        testRow.appendChild(testBtn);
        advBody.appendChild(testRow);

        var testResults = document.createElement('div');
        testResults.id = 'gen-ai-test-results';
        advBody.appendChild(testResults);

        var transcriptArea = document.getElementById('ai-transcript-input');
        var transcriptWrap = transcriptArea ? transcriptArea.parentNode : null;
        if (transcriptWrap) {
            var lbl = transcriptWrap.querySelector('label[for="ai-transcript-input"]');
            if (lbl) lbl.textContent = 'Paste a custom transcript manually';
            advBody.appendChild(transcriptWrap);
        }

        var legacyRunBtn = document.getElementById('btn-run-ai');
        if (legacyRunBtn) {
            var legacyRow = document.createElement('div');
            legacyRow.className = 'gen-adv-row';
            legacyRunBtn.textContent = 'Generate from the YouTube video (no transcript)';
            legacyRow.appendChild(legacyRunBtn);
            var fallbackNote = document.createElement('span');
            fallbackNote.className = 'gen-adv-note';
            fallbackNote.textContent = 'Fallback when an episode has no saved transcript.';
            legacyRow.appendChild(fallbackNote);
            advBody.appendChild(legacyRow);
        }

        details.appendChild(advBody);
        var legacyToolbar = document.getElementById('pipeline-v2-toolbar');
        if (legacyToolbar) legacyToolbar.style.display = 'none';

        var resultBox = document.getElementById('ai-result-box');
        if (resultBox) resultBox.insertAdjacentElement('afterend', details);
        else card.appendChild(details);

        var cached = null;
        try { cached = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null'); } catch (_) {}
        applyProviderStatuses(cached);

        epSelect.addEventListener('change', refreshEpisodeStatusLine);
        var obs = new MutationObserver(function () { if (epSelect.value) refreshEpisodeStatusLine(); });
        obs.observe(epSelect, { childList: true });
        if (epSelect.value) refreshEpisodeStatusLine();
    }

    window.GeneratorCardUi = {
        refreshEpisodeStatusLine: refreshEpisodeStatusLine,
        applyProviderStatuses: applyProviderStatuses,
        registerWriterOption: registerWriterOption,
        setUsage: setUsage
    };

    document.addEventListener('DOMContentLoaded', function () { setTimeout(enhanceGeneratorCard, 0); });
})();
