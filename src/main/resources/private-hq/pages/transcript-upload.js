// Provides single/bulk .srt and .vtt transcript upload preview, confirmation, and Quality Gate UI in Question Bank.
(function () {
    const MAX_FILE_BYTES = 2 * 1024 * 1024;
    const MAX_FILES = 100;
    let pendingUploadFiles = [];

    function mountTranscriptUploadPanel() {
        const qbankPanel = document.getElementById('tab-panel-question-bank');
        if (!qbankPanel || document.getElementById('transcript-upload-card')) {
            return;
        }

        const card = document.createElement('div');
        card.id = 'transcript-upload-card';
        card.className = 'admin-card-section';
        card.style.borderColor = 'rgba(56, 189, 248, 0.45)';
        card.style.marginBottom = '1.25rem';

        const headerBar = document.createElement('div');
        headerBar.className = 'card-header-bar';
        const headerWrap = document.createElement('div');
        const h3 = document.createElement('h3');
        h3.style.color = '#7dd3fc';
        h3.textContent = '📄 Upload Episode Transcripts (.srt / .vtt) & Quality Gate';
        const desc = document.createElement('p');
        desc.style.fontSize = '0.85rem';
        desc.style.color = 'var(--text-muted)';
        desc.style.marginTop = '0.2rem';
        desc.textContent = 'Upload single or bulk .srt / .vtt files (max 2 MB/file, max 100 files, UTF-8). Matched by episode number or YouTube ID and checked by the Quality Gate before saving.';
        headerWrap.appendChild(h3);
        headerWrap.appendChild(desc);
        headerBar.appendChild(headerWrap);
        card.appendChild(headerBar);

        const controlsRow = document.createElement('div');
        controlsRow.style.display = 'flex';
        controlsRow.style.gap = '0.85rem';
        controlsRow.style.alignItems = 'center';
        controlsRow.style.flexWrap = 'wrap';
        controlsRow.style.marginBottom = '0.85rem';

        const fileInput = document.createElement('input');
        fileInput.type = 'file';
        fileInput.id = 'input-transcript-files';
        fileInput.multiple = true;
        fileInput.accept = '.srt,.vtt';
        fileInput.className = 'text-input';
        fileInput.style.maxWidth = '340px';
        fileInput.style.padding = '0.45rem';

        const previewBtn = document.createElement('button');
        previewBtn.type = 'button';
        previewBtn.id = 'btn-preview-transcripts';
        previewBtn.className = 'btn-ghost';
        previewBtn.textContent = '🔍 Preview & Match Files';
        previewBtn.addEventListener('click', () => handleTranscriptUpload(false));

        const confirmBtn = document.createElement('button');
        confirmBtn.type = 'button';
        confirmBtn.id = 'btn-confirm-transcripts';
        confirmBtn.className = 'btn-primary';
        confirmBtn.style.display = 'none';
        confirmBtn.textContent = '✓ Confirm & Save Matched Transcripts';
        confirmBtn.addEventListener('click', () => handleTranscriptUpload(true));

        controlsRow.appendChild(fileInput);
        controlsRow.appendChild(previewBtn);
        controlsRow.appendChild(confirmBtn);
        card.appendChild(controlsRow);

        const msgBox = document.createElement('div');
        msgBox.id = 'transcript-upload-msg';
        msgBox.className = 'feedback-msg';
        card.appendChild(msgBox);

        const tableWrap = document.createElement('div');
        tableWrap.id = 'transcript-preview-table-wrap';
        tableWrap.className = 'table-container';
        tableWrap.style.display = 'none';
        tableWrap.style.marginTop = '0.85rem';

        const table = document.createElement('table');
        table.className = 'data-table';
        const thead = document.createElement('thead');
        const trHead = document.createElement('tr');
        ['File', 'Matched Episode', 'Word Count', 'Status'].forEach(label => {
            const th = document.createElement('th');
            th.textContent = label;
            trHead.appendChild(th);
        });
        thead.appendChild(trHead);
        table.appendChild(thead);

        const tbody = document.createElement('tbody');
        tbody.id = 'transcript-preview-tbody';
        table.appendChild(tbody);
        tableWrap.appendChild(table);
        card.appendChild(tableWrap);

        const epStatusBox = document.createElement('div');
        epStatusBox.id = 'episode-transcript-gate-box';
        epStatusBox.style.marginTop = '1rem';
        epStatusBox.style.padding = '0.85rem 1rem';
        epStatusBox.style.background = 'var(--bg-subtle)';
        epStatusBox.style.borderRadius = 'var(--radius-md)';
        epStatusBox.style.border = '1px solid var(--border)';
        epStatusBox.style.display = 'none';
        card.appendChild(epStatusBox);

        const cards = qbankPanel.querySelectorAll('.admin-card-section');
        if (cards.length >= 2) {
            qbankPanel.insertBefore(card, cards[1]);
        } else {
            qbankPanel.appendChild(card);
        }

        const epSelect = document.getElementById('ai-episode-select');
        if (epSelect) {
            epSelect.addEventListener('change', () => refreshSelectedEpisodeTranscriptStatus());
        }
    }

    function setFeedback(text, isError) {
        const msgBox = document.getElementById('transcript-upload-msg');
        if (!msgBox) return;
        msgBox.className = isError ? 'feedback-msg err' : 'feedback-msg ok';
        msgBox.style.display = 'block';
        msgBox.textContent = text;
    }

    async function readFilesAsPayload(fileList) {
        if (!fileList || fileList.length === 0) {
            throw new Error('Select at least one .srt or .vtt file first.');
        }
        if (fileList.length > MAX_FILES) {
            throw new Error(`Maximum ${MAX_FILES} files allowed per bulk upload.`);
        }
        const items = [];
        for (let i = 0; i < fileList.length; i++) {
            const f = fileList[i];
            if (f.size > MAX_FILE_BYTES) {
                throw new Error(`File "${f.name}" exceeds the 2 MB limit.`);
            }
            const buf = await f.arrayBuffer();
            const decoder = new TextDecoder('utf-8', { fatal: true });
            let content;
            try {
                content = decoder.decode(buf);
            } catch (e) {
                throw new Error(`File "${f.name}" is not valid UTF-8 text.`);
            }
            items.push({ fileName: f.name, content: content });
        }
        return items;
    }

    async function handleTranscriptUpload(confirmSave) {
        const fileInput = document.getElementById('input-transcript-files');
        const confirmBtn = document.getElementById('btn-confirm-transcripts');
        const epSelect = document.getElementById('ai-episode-select');
        const courseSelect = document.getElementById('admin-course-select');

        try {
            if (!confirmSave || pendingUploadFiles.length === 0) {
                pendingUploadFiles = await readFilesAsPayload(fileInput ? fileInput.files : null);
            }
            const playlistId = courseSelect && courseSelect.value ? Number(courseSelect.value) : null;
            const episodeId = epSelect && epSelect.value ? Number(epSelect.value) : null;

            const res = await apiFetch('/api/admin/transcripts/upload', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    playlistId: playlistId,
                    episodeId: episodeId,
                    confirmSave: Boolean(confirmSave),
                    files: pendingUploadFiles
                })
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                setFeedback(data.error || `Upload failed (HTTP ${res.status}).`, true);
                return;
            }
            renderPreviewTable(data.files || []);
            const matchedCount = (data.files || []).filter(r => r.matchStatus === 'MATCHED').length;
            if (confirmSave) {
                setFeedback(`Saved ${data.savedCount ?? 0} transcript(s). Rejected: ${data.rejectedCount ?? 0}.`, false);
                if (confirmBtn) confirmBtn.style.display = 'none';
                pendingUploadFiles = [];
                await refreshSelectedEpisodeTranscriptStatus();
            } else {
                setFeedback(`Preview ready: ${matchedCount} matched, ${data.rejectedCount ?? 0} rejected/ambiguous. Confirm below to save.`, matchedCount === 0);
                if (confirmBtn) {
                    confirmBtn.style.display = matchedCount > 0 ? 'inline-flex' : 'none';
                }
            }
        } catch (err) {
            setFeedback(err.message || 'Failed to read or upload transcript files.', true);
        }
    }

    function renderPreviewTable(rows) {
        const wrap = document.getElementById('transcript-preview-table-wrap');
        const tbody = document.getElementById('transcript-preview-tbody');
        if (!wrap || !tbody) return;
        clearElement(tbody);
        wrap.style.display = rows.length > 0 ? 'block' : 'none';

        rows.forEach(r => {
            const tr = document.createElement('tr');
            const tdFile = document.createElement('td');
            tdFile.textContent = r.fileName || '';

            const tdEp = document.createElement('td');
            tdEp.textContent = r.episodeTitle
                ? `Ep. ${r.episodeNumber ?? ''}: ${r.episodeTitle}`
                : (r.matchStatus || 'UNMATCHED');

            const tdWords = document.createElement('td');
            tdWords.textContent = String(r.wordCount ?? 0);

            const tdStatus = document.createElement('td');
            const reasons = Array.isArray(r.qualityReasons) && r.qualityReasons.length > 0
                ? ' — ' + r.qualityReasons.join('; ')
                : '';
            const savedTag = r.saved ? ' (SAVED)' : '';
            tdStatus.textContent = `${r.matchStatus || ''} / ${r.qualityStatus || ''}${savedTag}${reasons}`;
            tdStatus.style.color = r.qualityStatus === 'PASS' ? '#6ee7b7' : '#fca5a5';

            tr.appendChild(tdFile);
            tr.appendChild(tdEp);
            tr.appendChild(tdWords);
            tr.appendChild(tdStatus);
            tbody.appendChild(tr);
        });
    }

    async function refreshSelectedEpisodeTranscriptStatus() {
        const epSelect = document.getElementById('ai-episode-select');
        const box = document.getElementById('episode-transcript-gate-box');
        if (!epSelect || !epSelect.value || !box) return;
        try {
            const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(epSelect.value)}/transcript`);
            if (!res.ok) return;
            const data = await res.json();
            clearElement(box);
            if (!data.hasTranscript) {
                box.style.display = 'none';
                return;
            }
            box.style.display = 'block';
            const title = document.createElement('div');
            title.style.fontWeight = '800';
            title.style.marginBottom = '0.35rem';
            const passed = data.qualityStatus === 'PASS';
            title.style.color = (passed || data.useAnyway) ? '#6ee7b7' : '#fca5a5';
            title.textContent = `Stored Transcript Quality Gate: ${data.qualityStatus}` +
                (data.useAnyway ? ' (Override: Use anyway enabled)' : '') +
                ` • ${data.wordCount ?? 0} words (${data.source || 'UPLOAD'})`;
            box.appendChild(title);

            if (!passed && !data.useAnyway) {
                const actions = document.createElement('div');
                actions.style.display = 'flex';
                actions.style.gap = '0.6rem';
                actions.style.marginTop = '0.5rem';

                const useBtn = document.createElement('button');
                useBtn.type = 'button';
                useBtn.className = 'btn-secondary';
                useBtn.textContent = 'Use anyway';
                useBtn.addEventListener('click', async () => {
                    await apiFetch(`/api/admin/episodes/${encodeURIComponent(epSelect.value)}/transcript/use-anyway`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ useAnyway: true })
                    });
                    await refreshSelectedEpisodeTranscriptStatus();
                });

                const videoBtn = document.createElement('button');
                videoBtn.type = 'button';
                videoBtn.className = 'btn-ghost';
                videoBtn.textContent = 'Generate from video with Gemini';
                videoBtn.addEventListener('click', () => {
                    if (typeof window.handleGenerateAiVideoQuiz === 'function') {
                        window.handleGenerateAiVideoQuiz();
                    }
                });

                actions.appendChild(useBtn);
                actions.appendChild(videoBtn);
                box.appendChild(actions);
            }
        } catch (err) {
            box.style.display = 'none';
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        mountTranscriptUploadPanel();
        setTimeout(refreshSelectedEpisodeTranscriptStatus, 600);
    });

    window.refreshSelectedEpisodeTranscriptStatus = refreshSelectedEpisodeTranscriptStatus;
})();
