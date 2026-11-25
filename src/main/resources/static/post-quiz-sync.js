// Immediate post-quiz and post-open-answer UI updates with deduplicated single background refresh.
(function () {
    'use strict';

    var episodesInFlightPromise = null;
    var bgSyncScheduled = false;

    function parseDollarText(el) {
        if (!el || !el.textContent) return null;
        var m = String(el.textContent).match(/-?\d+(?:\.\d+)?/);
        return m ? Number(m[0]) : null;
    }

    function bumpWalletDisplayImmediate(rewardDelta) {
        var delta = Number(rewardDelta || 0);
        if (!(delta > 0)) return;
        var earnedEl = document.getElementById('val-earned');
        var earnedVal = parseDollarText(earnedEl);
        if (earnedEl && earnedVal !== null) {
            earnedEl.textContent = '$' + (earnedVal + delta).toFixed(2);
        }
    }

    function unlockNextEpisodeInMemory(passedEpisodeId) {
        if (typeof episodes === 'undefined' || !Array.isArray(episodes)) return;
        var idx = episodes.findIndex(function (e) { return e.id === passedEpisodeId; });
        if (idx >= 0 && idx + 1 < episodes.length) {
            var nextEp = episodes[idx + 1];
            if (nextEp && nextEp.locked && nextEp.unlockAfterEpisodeNumber) {
                nextEp.locked = false;
                nextEp.unlockAfterEpisodeNumber = null;
                if (nextEp.state === 'LOCKED') {
                    nextEp.state = 'AVAILABLE';
                    nextEp.stateLabel = 'Available';
                }
            }
        }
    }

    function applyEpisodePassInMemory(epId) {
        if (typeof episodes !== 'undefined' && Array.isArray(episodes)) {
            var match = episodes.find(function (e) { return e.id === epId; });
            if (match) {
                match.passed = true;
                match.locked = false;
                match.state = 'PASSED';
                match.stateLabel = 'Passed';
            }
        }
        if (typeof currentEpisode !== 'undefined' && currentEpisode && currentEpisode.id === epId) {
            currentEpisode.passed = true;
            currentEpisode.locked = false;
            currentEpisode.state = 'PASSED';
            currentEpisode.stateLabel = 'Passed';
        }
        unlockNextEpisodeInMemory(epId);
    }

    function scheduleBackgroundSync(episodeId, refreshQuiz) {
        if (bgSyncScheduled) return;
        bgSyncScheduled = true;
        setTimeout(function () {
            bgSyncScheduled = false;
            var tasks = [];
            if (typeof fetchEarnings === 'function') tasks.push(fetchEarnings());
            if (typeof fetchEpisodes === 'function') tasks.push(fetchEpisodes());
            if (refreshQuiz && episodeId && typeof fetchQuiz === 'function') tasks.push(fetchQuiz(episodeId));
            Promise.all(tasks).catch(function () {});
        }, 0);
    }

    function applyQuizSubmitImmediate(result) {
        if (!result || typeof currentEpisode === 'undefined' || !currentEpisode) return;
        var epId = currentEpisode.id;
        if (result.passed) {
            var reward = Number(result.rewardAmount || 0);
            if (reward > 0 || !result.openQuestion) {
                applyEpisodePassInMemory(epId);
                bumpWalletDisplayImmediate(reward);
            }
            if (typeof renderEpisodeHeader === 'function') renderEpisodeHeader(currentEpisode);
            if (typeof renderLiveWatchBar === 'function') renderLiveWatchBar(currentEpisode.watchStatus, currentEpisode.passed);
            if (typeof renderEpisodeList === 'function') renderEpisodeList();
        }
        scheduleBackgroundSync(epId, true);
    }

    function applyOpenAnswerImmediate(data) {
        if (typeof currentEpisode === 'undefined' || !currentEpisode) return;
        var epId = currentEpisode.id;
        var status = data && data.status ? String(data.status).toUpperCase() : '';
        if (status === 'APPROVED') {
            applyEpisodePassInMemory(epId);
        } else if (status === 'PENDING' || !currentEpisode.passed) {
            currentEpisode.state = 'PENDING_REVIEW';
            currentEpisode.stateLabel = 'Pending Review';
            if (typeof episodes !== 'undefined' && Array.isArray(episodes)) {
                var match = episodes.find(function (e) { return e.id === epId; });
                if (match) {
                    match.state = 'PENDING_REVIEW';
                    match.stateLabel = 'Pending Review';
                }
            }
        }
        if (typeof renderEpisodeHeader === 'function') renderEpisodeHeader(currentEpisode);
        if (typeof renderEpisodeList === 'function') renderEpisodeList();
        scheduleBackgroundSync(epId, false);
    }

    function dedupFetchEpisodes(fetchImpl) {
        if (episodesInFlightPromise) {
            return episodesInFlightPromise;
        }
        episodesInFlightPromise = Promise.resolve()
            .then(fetchImpl)
            .finally(function () {
                episodesInFlightPromise = null;
            });
        return episodesInFlightPromise;
    }

    window.PostQuizSync = {
        applyQuizSubmitImmediate: applyQuizSubmitImmediate,
        applyOpenAnswerImmediate: applyOpenAnswerImmediate,
        dedupFetchEpisodes: dedupFetchEpisodes
    };
})();
