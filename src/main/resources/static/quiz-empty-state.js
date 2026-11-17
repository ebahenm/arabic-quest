// Renders a friendly empty quiz state and jump-to-next-ready-quiz button using textContent only.
(function () {
    'use strict';

    function findNextReadyEpisode(episodes, currentEpisode) {
        if (!Array.isArray(episodes) || episodes.length === 0) return null;
        const currentId = currentEpisode ? currentEpisode.id : null;
        const startIdx = currentId != null ? episodes.findIndex(function (e) { return e.id === currentId; }) : -1;

        for (let pass = 0; pass < 2; pass++) {
            for (let offset = 1; offset <= episodes.length; offset++) {
                const idx = ((startIdx >= 0 ? startIdx : -1) + offset) % episodes.length;
                const cand = episodes[idx];
                if (!cand || cand.id === currentId || cand.locked || !cand.quizReady) continue;
                if (pass === 0 && cand.passed) continue;
                return cand;
            }
        }
        return null;
    }

    function buildEmptyStateCard(currentEpisode, episodes, onSelectEpisode, buttonId) {
        const box = document.createElement('div');
        box.className = 'quiz-empty-state';

        const title = document.createElement('div');
        title.className = 'quiz-empty-title';
        title.textContent = "This episode's quiz isn't ready yet";
        box.appendChild(title);

        const nextReady = findNextReadyEpisode(episodes, currentEpisode);
        const desc = document.createElement('p');
        desc.className = 'quiz-empty-desc';
        desc.textContent = nextReady
            ? 'Your watch progress for this episode is saved. You can jump straight to the next episode that already has a ready quiz.'
            : 'Questions for this episode are still being prepared. Check back shortly.';
        box.appendChild(desc);

        if (nextReady && typeof onSelectEpisode === 'function') {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.id = buttonId || 'btn-jump-next-ready-quiz';
            btn.className = 'btn-primary';
            btn.textContent = 'Jump to Ep. ' + nextReady.episodeNumber + ' Quiz ➔';
            btn.addEventListener('click', function () {
                onSelectEpisode(nextReady);
                window.scrollTo({ top: 0, behavior: 'smooth' });
            });
            box.appendChild(btn);
        }

        return box;
    }

    function renderIntoContainer(container, currentEpisode, episodes, onSelectEpisode) {
        if (!container) return;
        container.textContent = '';
        container.appendChild(buildEmptyStateCard(currentEpisode, episodes, onSelectEpisode, 'btn-jump-next-ready-quiz'));
    }

    function syncNotReadyBanner(currentEpisode, status, episodes, onSelectEpisode) {
        const quizSection = document.getElementById('quiz-section');
        if (!quizSection || !quizSection.parentNode) return;

        let banner = document.getElementById('quiz-not-ready-notice');
        if (!banner) {
            banner = document.createElement('div');
            banner.id = 'quiz-not-ready-notice';
            banner.className = 'quiz-empty-state quiz-not-ready-notice';
            quizSection.parentNode.insertBefore(banner, quizSection);
        }

        const quizBoxVisible = Boolean(currentEpisode && (currentEpisode.passed || (status && status.quizUnlocked)));
        const shouldShowStandalone = Boolean(!quizBoxVisible && currentEpisode && currentEpisode.quizReady === false);

        banner.textContent = '';
        if (!shouldShowStandalone) {
            banner.classList.remove('visible');
            return;
        }
        banner.appendChild(buildEmptyStateCard(currentEpisode, episodes, onSelectEpisode, 'btn-banner-jump-next-ready'));
        banner.classList.add('visible');
    }

    window.QuizEmptyState = {
        findNextReadyEpisode: findNextReadyEpisode,
        renderIntoContainer: renderIntoContainer,
        syncNotReadyBanner: syncNotReadyBanner
    };
})();
