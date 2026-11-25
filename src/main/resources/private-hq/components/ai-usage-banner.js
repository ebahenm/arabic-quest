// Displays today's AI daily call/token usage and caps in Question Bank, plus verifier model and single-model badges.
(function () {
    'use strict';

    function mountAiUsageBanner() {
        const qbankPanel = document.getElementById('tab-panel-question-bank');
        if (!qbankPanel || document.getElementById('ai-daily-usage-banner')) return;

        const box = document.createElement('div');
        box.id = 'ai-daily-usage-banner';
        box.className = 'admin-card-section';
        Object.assign(box.style, {
            borderColor: 'rgba(16, 185, 129, 0.45)',
            marginBottom: '1.1rem',
            padding: '0.9rem 1.2rem'
        });

        const top = document.createElement('div');
        Object.assign(top.style, {
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            flexWrap: 'wrap', gap: '0.6rem'
        });

        const title = document.createElement('strong');
        title.id = 'ai-usage-banner-title';
        title.style.fontSize = '0.92rem';
        title.style.color = '#6ee7b7';
        title.textContent = '🛡️ Daily AI Cost Guard: Loading usage...';

        const refreshBtn = document.createElement('button');
        Object.assign(refreshBtn, { type: 'button', className: 'btn-ghost', textContent: '↻ Refresh Usage' });
        Object.assign(refreshBtn.style, { padding: '0.3rem 0.7rem', fontSize: '0.78rem' });
        refreshBtn.addEventListener('click', refreshAiUsageBanner);

        top.append(title, refreshBtn);

        const detail = document.createElement('div');
        detail.id = 'ai-usage-banner-detail';
        Object.assign(detail.style, { fontSize: '0.82rem', color: 'var(--text-muted)', marginTop: '0.35rem' });
        detail.textContent = '';

        box.append(top, detail);
        qbankPanel.insertBefore(box, qbankPanel.firstChild);
    }

    async function refreshAiUsageBanner() {
        mountAiUsageBanner();
        const box = document.getElementById('ai-daily-usage-banner');
        const title = document.getElementById('ai-usage-banner-title');
        const detail = document.getElementById('ai-usage-banner-detail');
        if (!box || !title || !detail || typeof apiFetch !== 'function') return;

        try {
            const res = await apiFetch('/api/admin/ai/usage');
            if (!res.ok) {
                console.error('Failed to load AI usage snapshot: HTTP ' + res.status);
                if (window.GeneratorCardUi && typeof window.GeneratorCardUi.setUsageUnavailable === 'function') {
                    window.GeneratorCardUi.setUsageUnavailable();
                }
                return;
            }
            const d = await res.json();
            if (window.GeneratorCardUi && typeof window.GeneratorCardUi.setUsage === 'function') {
                window.GeneratorCardUi.setUsage(d.totalCalls, d.dailyCallCap);
            }
            const capReached = Boolean(d.capReached);
            box.style.borderColor = capReached ? 'rgba(239, 68, 68, 0.65)' : 'rgba(16, 185, 129, 0.45)';
            title.style.color = capReached ? '#fca5a5' : '#6ee7b7';
            title.textContent = capReached
                ? `⛔ Daily AI Cost Cap Reached (${d.date}) — ${d.totalCalls} / ${d.dailyCallCap} calls`
                : `🛡️ Daily AI Usage (${d.date}): ${d.totalCalls} / ${d.dailyCallCap} calls`;

            const parts = [];
            const providers = d.providers || {};
            Object.keys(providers).forEach((name) => {
                const p = providers[name];
                const capStr = p.outputTokenCap ? ` / ${Number(p.outputTokenCap).toLocaleString()} out cap` : '';
                parts.push(`${name}: ${p.calls} calls (${Number(p.outputTokens || 0).toLocaleString()} out tokens${capStr})`);
            });
            detail.textContent = parts.join('   •   ');
        } catch (err) {
            console.error('Error fetching /api/admin/ai/usage:', err);
            title.textContent = '🛡️ Daily AI Cost Guard: Unable to load usage.';
            if (window.GeneratorCardUi && typeof window.GeneratorCardUi.setUsageUnavailable === 'function') {
                window.GeneratorCardUi.setUsageUnavailable();
            }
        }
    }

    function createVerifierMetaSpan(verifierModel, singleModel) {
        const wrap = document.createElement('span');
        Object.assign(wrap.style, { display: 'inline-flex', gap: '0.35rem', alignItems: 'center', marginLeft: '0.45rem' });
        if (verifierModel) {
            const vBadge = document.createElement('span');
            Object.assign(vBadge.style, {
                fontSize: '0.72rem', fontWeight: '700', padding: '0.12rem 0.45rem',
                borderRadius: '999px', background: 'rgba(59, 130, 246, 0.16)', color: '#93c5fd'
            });
            vBadge.textContent = 'Verifier: ' + verifierModel;
            wrap.appendChild(vBadge);
        }
        if (singleModel) {
            const sBadge = document.createElement('span');
            Object.assign(sBadge.style, {
                fontSize: '0.72rem', fontWeight: '800', padding: '0.12rem 0.45rem',
                borderRadius: '999px', background: 'rgba(245, 158, 11, 0.22)', color: '#fcd34d'
            });
            sBadge.textContent = 'Single-model';
            wrap.appendChild(sBadge);
        }
        return wrap;
    }

    document.addEventListener('DOMContentLoaded', () => {
        setTimeout(() => {
            mountAiUsageBanner();
            refreshAiUsageBanner();
        }, 300);
    });

    window.refreshAiUsageBanner = refreshAiUsageBanner;
    window.createVerifierMetaSpan = createVerifierMetaSpan;
})();
