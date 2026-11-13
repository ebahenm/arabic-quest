// Handles optional-transcript video generation, Retry on errors, and Jump-to-moment links in the Question Bank.
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

    function renderAiErrorWithRetry(resultTitleEl, resultContentEl, message) {
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
        retryBtn.addEventListener('click', () => handleGenerateAiVideoQuiz());
        resultContentEl.appendChild(retryBtn);
    }

    function renderAiSuccessSummary(resultTitleEl, resultContentEl, data, usedTranscript) {
        resultTitleEl.textContent = 'Draft Bank Generated (Saved as DRAFT)';
        resultTitleEl.style.color = '#6ee7b7';
        clearElement(resultContentEl);

        const summary = document.createElement('p');
        summary.style.marginBottom = '0.45rem';
        summary.textContent =
            `Saved ${data.savedDraftMcqs ?? 0} verified draft MCQ(s) and ` +
            `${data.savedDraftOpenQuestions ?? 0} draft open question(s) ` +
            `(Pass 1 candidates: ${data.pass1McqCandidates ?? 0} MCQ, ` +
            `${data.pass1OpenCandidates ?? 0} open; filtered/deduped: ` +
            `${data.droppedInVerificationOrDedup ?? 0}).`;
        resultContentEl.appendChild(summary);

        const note = document.createElement('p');
        note.style.fontSize = '0.82rem';
        note.style.color = 'var(--text-muted)';
        note.textContent = usedTranscript
            ? 'Generated using video analysis + optional transcript. Review DRAFT items below (existing Approved questions are untouched).'
            : 'Generated directly from the YouTube video. Use "Jump to moment" on DRAFT cards below to verify timestamps before approving.';
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
        if (!episodeId || !resultBox || !resultTitle || !resultContent || !btn) {
            return;
        }

        const transcript = transcriptEl ? transcriptEl.value.trim() : '';
        if (transcript.length > 65000) {
            resultBox.style.display = 'block';
            renderAiErrorWithRetry(
                resultTitle,
                resultContent,
                'Optional transcript exceeds 65,000 characters. Clear it to generate purely from the YouTube video or shorten it.'
            );
            return;
        }

        const payload = transcript.length > 0 ? { transcript: transcript } : {};

        btn.disabled = true;
        btn.textContent = 'Analyzing YouTube video...';
        resultBox.style.display = 'block';
        resultTitle.textContent = 'Generating draft questions from YouTube video...';
        resultTitle.style.color = '#c4b5fd';
        clearElement(resultContent);

        const waitMsg = document.createElement('p');
        waitMsg.textContent = 'Running Pass 1 video analysis and Pass 2 verification (long videos are automatically split into segments). Please wait...';
        resultContent.appendChild(waitMsg);

        try {
            const res = await apiFetch(`/api/admin/episodes/${encodeURIComponent(episodeId)}/generate-bank`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok) {
                renderAiSuccessSummary(resultTitle, resultContent, data, transcript.length > 0);
                if (typeof loadEpisodeQuestionBank === 'function') {
                    await loadEpisodeQuestionBank();
                }
                if (typeof loadEpisodes === 'function') {
                    await loadEpisodes();
                }
            } else {
                const statusPrefix = `[HTTP ${res.status}] `;
                let errMsg = statusPrefix + (data.error || `Generation failed (HTTP ${res.status}).`);
                if (res.status === 429) {
                    const retryHint = data.retryAfterSeconds ? ` Wait ~${data.retryAfterSeconds}s before retrying.` : ' Please wait briefly and retry.';
                    errMsg = statusPrefix + (data.error || 'Gemini API rate limit reached.') + retryHint;
                } else if (res.status === 400 || res.status === 403 || res.status === 404) {
                    errMsg += ' Verify the YouTube video is public/unlisted and accessible, or paste an optional transcript above.';
                } else if (res.status >= 500) {
                    errMsg += ' Temporary server/Gemini error — please retry shortly or paste an optional transcript.';
                }
                renderAiErrorWithRetry(resultTitle, resultContent, errMsg);
            }
        } catch (err) {
            renderAiErrorWithRetry(
                resultTitle,
                resultContent,
                'Network or timeout error while contacting the server: ' + err.message
            );
        } finally {
            btn.disabled = false;
            btn.textContent = 'Generate from the YouTube video';
        }
    }

    function attachJumpLinks(containerId, items, youtubeVideoId) {
        const container = document.getElementById(containerId);
        if (!container || !Array.isArray(items) || items.length === 0) {
            return;
        }
        const cards = Array.from(container.children);
        if (cards.length !== items.length) {
            return;
        }
        cards.forEach((card, idx) => {
            const item = items[idx];
            if (!item || card.querySelector('.aq-jump-moment-row')) {
                return;
            }
            const ts = item.evidenceTimestamp;
            if (ts === null || ts === undefined || Number.isNaN(Number(ts)) || Number(ts) < 0) {
                return;
            }
            const seconds = Math.floor(Number(ts));
            const row = document.createElement('div');
            row.className = 'aq-jump-moment-row';
            row.style.display = 'flex';
            row.style.flexWrap = 'wrap';
            row.style.alignItems = 'center';
            row.style.gap = '0.6rem';
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
            } else {
                const span = document.createElement('span');
                span.style.color = '#94a3b8';
                span.textContent = `Timestamp: ${formatSecondsToMmSs(seconds)}`;
                row.appendChild(span);
            }

            const btnGroup = card.lastElementChild;
            if (btnGroup && btnGroup.querySelector('button')) {
                card.insertBefore(row, btnGroup);
            } else {
                card.appendChild(row);
            }
        });
    }

    function enhanceQuestionBankCards(bank) {
        if (!bank) {
            return;
        }
        attachJumpLinks('qbank-mcq-list', bank.questions || [], bank.youtubeVideoId || '');
        attachJumpLinks('qbank-open-list', bank.openQuestions || [], bank.youtubeVideoId || '');
    }

    window.handleGenerateAiVideoQuiz = handleGenerateAiVideoQuiz;
    window.enhanceQuestionBankCards = enhanceQuestionBankCards;
})();
