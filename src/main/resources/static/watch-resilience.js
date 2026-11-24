// Samsung/Android episode open guards, YouTube IFrame API load fallbacks, and sanitized client-error reporting.
(function () {
    'use strict';

    var ytLoadTimeout = null;

    function reportClientError(message) {
        try {
            var payload = JSON.stringify({
                message: String(message || 'Unknown client error').slice(0, 300),
                browser: String((navigator && navigator.userAgent) || 'unknown').slice(0, 160)
            });
            var headers = typeof apiHeaders === 'function' ? apiHeaders(true) : { 'Content-Type': 'application/json' };
            fetch('/api/student/client-errors', {
                method: 'POST',
                credentials: 'same-origin',
                keepalive: true,
                headers: headers,
                body: payload
            }).catch(function () {});
        } catch (_) {}
    }

    function showPlayerFallback(ep, message, retryFn) {
        var wrapper = document.getElementById('player-wrapper');
        if (!wrapper) return;
        wrapper.textContent = '';

        var box = document.createElement('div');
        box.setAttribute('role', 'alert');
        box.style.cssText = 'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:0.85rem;padding:1.5rem;min-height:220px;text-align:center;color:#f8fafc;background:#0f172a;border-radius:12px;';

        var title = document.createElement('div');
        title.style.cssText = 'font-weight:700;font-size:1rem;';
        title.textContent = message || 'Video player could not load on this device.';
        box.appendChild(title);

        var actions = document.createElement('div');
        actions.style.cssText = 'display:flex;flex-wrap:wrap;gap:0.65rem;justify-content:center;';

        var retryBtn = document.createElement('button');
        retryBtn.type = 'button';
        retryBtn.className = 'btn-primary';
        retryBtn.textContent = 'Retry';
        retryBtn.addEventListener('click', function () {
            if (typeof retryFn === 'function') retryFn();
        });
        actions.appendChild(retryBtn);

        var vid = ep && ep.youtubeVideoId && typeof extractYouTubeId === 'function'
            ? extractYouTubeId(ep.youtubeVideoId)
            : '';
        if (vid) {
            var ytLink = document.createElement('a');
            ytLink.className = 'btn-secondary';
            ytLink.href = 'https://www.youtube.com/watch?v=' + encodeURIComponent(vid);
            ytLink.target = '_blank';
            ytLink.rel = 'noopener noreferrer';
            ytLink.textContent = 'Open on YouTube';
            actions.appendChild(ytLink);
        }

        box.appendChild(actions);
        wrapper.appendChild(box);
    }

    function clearYtTimeout() {
        if (ytLoadTimeout) {
            clearTimeout(ytLoadTimeout);
            ytLoadTimeout = null;
        }
    }

    function handleYouTubeLoadFailure(reason) {
        clearYtTimeout();
        if (typeof ytApiRequested !== 'undefined') ytApiRequested = false;
        reportClientError(reason);
        var ep = typeof currentEpisode !== 'undefined' ? currentEpisode : null;
        showPlayerFallback(ep, 'The video player could not load (slow connection or blocked script).', function () {
            if (ep && typeof selectEpisode === 'function') selectEpisode(ep);
        });
    }

    function attachYouTubeScriptGuards(scriptTag) {
        clearYtTimeout();
        if (scriptTag) {
            scriptTag.onerror = function () {
                handleYouTubeLoadFailure('YouTube IFrame API script failed to load');
            };
        }
        ytLoadTimeout = setTimeout(function () {
            if (!(window.YT && window.YT.Player) && typeof currentEpisode !== 'undefined' && currentEpisode) {
                handleYouTubeLoadFailure('YouTube IFrame API timed out');
            }
        }, 12000);
    }

    function handlePlayerError(code) {
        reportClientError('YT.Player error code ' + String(code));
        var ep = typeof currentEpisode !== 'undefined' ? currentEpisode : null;
        showPlayerFallback(ep, 'The video player reported an error on this device.', function () {
            if (ep && typeof selectEpisode === 'function') selectEpisode(ep);
        });
    }

    function safeOpenEpisode(ep, openFn) {
        if (window.WatchTracker && typeof window.WatchTracker.resetEpisodeState === 'function') {
            window.WatchTracker.resetEpisodeState();
        }
        try {
            openFn(ep);
        } catch (err) {
            var msg = err && err.message ? err.message : 'Unexpected error opening episode';
            reportClientError('selectEpisode: ' + msg);
            showPlayerFallback(ep, 'Could not open this episode right now. Stay here and try again.', function () {
                safeOpenEpisode(ep, openFn);
            });
        }
    }

    window.WatchResilience = {
        reportClientError: reportClientError,
        showPlayerFallback: showPlayerFallback,
        clearYtTimeout: clearYtTimeout,
        attachYouTubeScriptGuards: attachYouTubeScriptGuards,
        handlePlayerError: handlePlayerError,
        safeOpenEpisode: safeOpenEpisode
    };
})();
