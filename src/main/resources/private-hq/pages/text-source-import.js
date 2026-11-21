// Admin Question Bank panel for pasting or uploading (.txt/.md, max 1 MB) structured written text sources with preview and confirmation.
(function () {
    'use strict';

    const MAX_BYTES = 1024 * 1024;
    let previewReadyForEpisodeId = null;

    function mountTextSourcePanel() {
        const qbankPanel = document.getElementById('tab-panel-question-bank');
        if (!qbankPanel || document.getElementById('text-source-import-card')) return;

        const card = document.createElement('div');
        card.id = 'text-source-import-card';
        card.className = 'admin-card-section';
        card.style.borderColor = 'rgba(201, 162, 77, 0.55)';
        card.style.marginBottom = '1.25rem';

        const headerBar = document.createElement('div');
        headerBar.className = 'card-header-bar';
        const headerWrap = document.createElement('div');
        const h3 = document.createElement('h3');
        h3.style.color = '#fde68a';
        h3.textContent = '📝 Written Text Source Import (.txt / .md or Paste)';
        const desc = document.createElement('p');
        Object.assign(desc.style, { fontSize: '0.84rem', color: 'var(--text-muted)', marginTop: '0.2rem' });
        desc.textContent = 'Paste or upload a structured written article/summary (max 1 MB, UTF-8) for the selected episode. Supports multiple sections with headings, start times (mm:ss, h:mm:ss, or YouTube t=), short/detailed answers, key takeaways (أبرز الخلاصات), and references (المراجع المذكورة). Strictly admin-only.';
        headerWrap.append(h3, desc);
        headerBar.appendChild(headerWrap);
        card.appendChild(headerBar);

        const targetBanner = document.createElement('div');
        targetBanner.id = 'text-source-target-label';
        Object.assign(targetBanner.style, { fontSize: '0.82rem', fontWeight: '700', color: '#93c5fd', marginBottom: '0.7rem' });
        targetBanner.textContent = 'Target Episode: Select an episode in the Question Bank selector above.';
        card.appendChild(targetBanner);

        const topRow = document.createElement('div');
        Object.assign(topRow.style, { display: 'flex', gap: '0.75rem', flexWrap: 'wrap', alignItems: 'center', marginBottom: '0.75rem' });

        const fileInput = document.createElement('input');
        Object.assign(fileInput, { type: 'file', id: 'input-text-source-file', accept: '.txt,.md', className: 'text-input' });
        Object.assign(fileInput.style, { maxWidth: '300px', padding: '0.45rem' });
        fileInput.addEventListener('change', handleFileLoadIntoTextarea);

        const urlInput = document.createElement('input');
        Object.assign(urlInput, {
            type: 'url', id: 'input-text-source-url', className: 'text-input', maxLength: 1000,
            placeholder: 'Optional admin-only Source URL note (never shown to students)'
        });
        Object.assign(urlInput.style, { flex: '1', minWidth: '220px' });

        topRow.append(fileInput, urlInput);
        card.appendChild(topRow);

        const textarea = document.createElement('textarea');
        Object.assign(textarea, {
            id: 'input-text-source-body', className: 'text-input', rows: 7, dir: 'auto',
            placeholder: 'Paste structured written sections here (heading, start time mm:ss / h:mm:ss / YouTube t=, short answer, detailed answer, أبرز الخلاصات, المراجع المذكورة)...'
        });
        Object.assign(textarea.style, { width: '100%', resize: 'vertical', marginBottom: '0.75rem', fontFamily: 'inherit' });
        textarea.addEventListener('input', () => {
            previewReadyForEpisodeId = null;
            const confirmBtn = document.getElementById('btn-confirm-text-source');
            if (confirmBtn) confirmBtn.style.display = 'none';
        });
        card.appendChild(textarea);

        const btnRow = document.createElement('div');
        Object.assign(btnRow.style, { display: 'flex', gap: '0.7rem', flexWrap: 'wrap', alignItems: 'center', marginBottom: '0.75rem' });

        const previewBtn = document.createElement('button');
        Object.assign(previewBtn, { type: 'button', id: 'btn-preview-text-source', className: 'btn-ghost', textContent: '🔍 Preview Sections' });
        previewBtn.addEventListener('click', () => submitTextSource(false));

        const confirmBtn = document.createElement('button');
        Object.assign(confirmBtn, { type: 'button', id: 'btn-confirm-text-source', className: 'btn-primary', textContent: '✓ Confirm & Save Written Text Source' });
        confirmBtn.style.display = 'none';
        confirmBtn.addEventListener('click', () => submitTextSource(true));

        btnRow.append(previewBtn, confirmBtn);
        card.appendChild(btnRow);

        const msgBox = document.createElement('div');
        msgBox.id = 'text-source-import-msg';
        msgBox.className = 'feedback-msg';
        card.appendChild(msgBox);

        const tableWrap = document.createElement('div');
        tableWrap.id = 'text-source-preview-wrap';
        tableWrap.className = 'table-container';
        Object.assign(tableWrap.style, { display: 'none', marginTop: '0.8rem' });

        const table = document.createElement('table');
        table.className = 'data-table';
        const thead = document.createElement('thead');
        const trHead = document.createElement('tr');
        ['#', 'Section Title', 'Start Time', 'Word Count', 'Structured Blocks'].forEach((label) => {
            const th = document.createElement('th');
            th.textContent = label;
            trHead.appendChild(th);
        });
        thead.appendChild(trHead);
        table.appendChild(thead);

        const tbody = document.createElement('tbody');
        tbody.id = 'text-source-preview-tbody';
        table.appendChild(tbody);
        tableWrap.appendChild(table);
        card.appendChild(tableWrap);

        const existingStatus = document.createElement('div');
        existingStatus.id = 'text-source-stored-status';
        Object.assign(existingStatus.style, {
            display: 'none', marginTop: '0.85rem', padding: '0.75rem 1rem',
            background: 'var(--bg-subtle)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
            fontSize: '0.82rem', color: '#cbd5e1'
        });
        card.appendChild(existingStatus);

        const transcriptCard = document.getElementById('transcript-upload-card');
        if (transcriptCard && transcriptCard.parentNode === qbankPanel) {
            transcriptCard.insertAdjacentElement('afterend', card);
        } else {
            const cards = qbankPanel.querySelectorAll('.admin-card-section');
            if (cards.length >= 2) qbankPanel.insertBefore(card, cards[1]);
            else qbankPanel.appendChild(card);
        }

        const epSelect = document.getElementById('ai-episode-select');
        if (epSelect) {
            epSelect.addEventListener('change', refreshTextSourceForSelectedEpisode);
        }
        refreshTextSourceForSelectedEpisode();
    }

    function setMsg(text, isError) {
        const box = document.getElementById('text-source-import-msg');
        if (!box) return;
        box.className = isError ? 'feedback-msg err' : 'feedback-msg ok';
        box.style.display = 'block';
        box.textContent = text;
    }

    async function handleFileLoadIntoTextarea(e) {
        const file = e.target && e.target.files ? e.target.files[0] : null;
        if (!file) return;
        const lower = (file.name || '').toLowerCase();
        if (!lower.endsWith('.txt') && !lower.endsWith('.md')) {
            setMsg('Only .txt and .md files are supported for written text sources.', true);
            return;
        }
        if (file.size > MAX_BYTES) {
            setMsg(`File "${file.name}" exceeds the 1 MB maximum limit.`, true);
            return;
        }
        try {
            const buf = await file.arrayBuffer();
            const text = new TextDecoder('utf-8', { fatal: true }).decode(buf);
            const textarea = document.getElementById('input-text-source-body');
            if (textarea) textarea.value = text;
            previewReadyForEpisodeId = null;
            setMsg(`Loaded "${file.name}" (${file.size.toLocaleString()} bytes). Click "Preview Sections" to inspect before saving.`, false);
        } catch (_) {
            setMsg(`File "${file.name}" is not valid UTF-8 text.`, true);
        }
    }

    async function submitTextSource(confirmSave) {
        const epSelect = document.getElementById('ai-episode-select');
        const episodeId = epSelect && epSelect.value ? Number(epSelect.value) : null;
        if (!episodeId) {
            setMsg('Select an episode in the Question Bank episode dropdown first.', true);
            return;
        }
        const textarea = document.getElementById('input-text-source-body');
        const urlInput = document.getElementById('input-text-source-url');
        const text = textarea ? textarea.value.trim() : '';
        const sourceUrl = urlInput ? urlInput.value.trim() : '';

        if (!text) {
            setMsg('Paste text or upload a .txt / .md file first.', true);
            return;
        }
        if (new Blob([text]).size > MAX_BYTES) {
            setMsg('Written text source exceeds the 1 MB limit.', true);
            return;
        }
        if (confirmSave && previewReadyForEpisodeId !== episodeId) {
            setMsg('Please click "Preview Sections" first before confirming.', true);
            return;
        }

        const confirmBtn = document.getElementById('btn-confirm-text-source');
        try {
            const data = await window.AdminApi.importEpisodeTextSource(episodeId, text, sourceUrl, confirmSave);
            renderSectionPreviewTable(data.sections || []);
            const reasons = Array.isArray(data.qualityReasons) && data.qualityReasons.length > 0
                ? ' — ' + data.qualityReasons.join('; ')
                : '';
            if (confirmSave) {
                previewReadyForEpisodeId = null;
                if (confirmBtn) confirmBtn.style.display = 'none';
                setMsg(`✓ Saved ${data.sectionCount || 0} section(s) (${data.wordCount || 0} words) as TEXT_SOURCE • Quality Gate: ${data.qualityStatus}${reasons}`, data.qualityStatus !== 'PASS');
                if (typeof window.refreshSelectedEpisodeTranscriptStatus === 'function') {
                    await window.refreshSelectedEpisodeTranscriptStatus();
                }
                await refreshTextSourceForSelectedEpisode();
            } else {
                previewReadyForEpisodeId = episodeId;
                if (confirmBtn) confirmBtn.style.display = (data.sectionCount > 0) ? 'inline-flex' : 'none';
                setMsg(`Preview ready: ${data.sectionCount || 0} section(s), ${data.wordCount || 0} words • Quality Gate: ${data.qualityStatus}${reasons}. Click "Confirm & Save" to store.`, data.qualityStatus !== 'PASS');
            }
        } catch (err) {
            setMsg(err.message || 'Failed to process written text source.', true);
        }
    }

    function renderSectionPreviewTable(sections) {
        const wrap = document.getElementById('text-source-preview-wrap');
        const tbody = document.getElementById('text-source-preview-tbody');
        if (!wrap || !tbody) return;
        tbody.textContent = '';
        wrap.style.display = sections.length > 0 ? 'block' : 'none';

        sections.forEach((sec, idx) => {
            const tr = document.createElement('tr');
            const tdIdx = document.createElement('td');
            tdIdx.textContent = String((sec.index != null ? sec.index : idx) + 1);
            const tdTitle = document.createElement('td');
            tdTitle.dir = 'auto';
            tdTitle.textContent = sec.heading || `Section ${idx + 1}`;
            const tdStart = document.createElement('td');
            tdStart.textContent = sec.startTimeFormatted || '00:00';
            const tdWords = document.createElement('td');
            tdWords.textContent = String(sec.wordCount ?? 0);
            const tdBlocks = document.createElement('td');
            const tags = [];
            if (sec.hasTakeaways) tags.push('Key Takeaways');
            if (sec.hasReferences) tags.push('References');
            tdBlocks.textContent = tags.length > 0 ? tags.join(' • ') : 'Answer';
            tr.append(tdIdx, tdTitle, tdStart, tdWords, tdBlocks);
            tbody.appendChild(tr);
        });
    }

    async function refreshTextSourceForSelectedEpisode() {
        const epSelect = document.getElementById('ai-episode-select');
        const targetBanner = document.getElementById('text-source-target-label');
        const statusBox = document.getElementById('text-source-stored-status');
        if (!epSelect || !epSelect.value) {
            if (targetBanner) targetBanner.textContent = 'Target Episode: Select an episode in the Question Bank selector above.';
            if (statusBox) statusBox.style.display = 'none';
            return;
        }
        const selectedOpt = epSelect.options[epSelect.selectedIndex];
        if (targetBanner && selectedOpt) {
            targetBanner.textContent = `Target Episode: ${selectedOpt.textContent}`;
        }
        try {
            const meta = await window.AdminApi.fetchEpisodeTextSourceMeta(Number(epSelect.value));
            if (!statusBox) return;
            if (!meta || !meta.hasTextSource) {
                statusBox.style.display = 'none';
                return;
            }
            statusBox.style.display = 'block';
            const urlPart = meta.sourceUrl ? ` • Source URL note: ${meta.sourceUrl}` : '';
            statusBox.textContent = `Stored Written Text Source: ${meta.sectionCount || 0} section(s), ${meta.wordCount || 0} words • Quality Gate: ${meta.qualityStatus}${urlPart}`;
        } catch (_) {
            if (statusBox) statusBox.style.display = 'none';
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        setTimeout(mountTextSourcePanel, 120);
        setTimeout(refreshTextSourceForSelectedEpisode, 700);
    });
    window.refreshTextSourceForSelectedEpisode = refreshTextSourceForSelectedEpisode;
})();
