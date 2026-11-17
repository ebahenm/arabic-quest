// Mobile episode list windowing (previous 1 + current + next 6), Show all/Show fewer toggle, and Current episode shortcut.
(function () {
    'use strict';

    const expandedState = { sidebar: false, syllabus: false };
    let lastCourseId = null;

    function isMobileView() {
        return window.matchMedia && window.matchMedia('(max-width: 768px)').matches;
    }

    function resolveCurrentIndex(items, episodes, currentEpisode) {
        if (!Array.isArray(items) || items.length === 0) return 0;
        const activeDomIdx = items.findIndex(function (el) { return el.classList.contains('active'); });
        if (activeDomIdx >= 0) return activeDomIdx;

        if (Array.isArray(episodes) && currentEpisode && currentEpisode.id != null) {
            const epIdx = episodes.findIndex(function (ep) { return ep && ep.id === currentEpisode.id; });
            if (epIdx >= 0 && epIdx < items.length) return epIdx;
        }
        if (Array.isArray(episodes)) {
            const firstTodo = episodes.findIndex(function (ep) { return ep && !ep.locked && !ep.passed; });
            if (firstTodo >= 0 && firstTodo < items.length) return firstTodo;
        }
        return 0;
    }

    function highlightAndScrollTo(targetEl) {
        if (!targetEl) return;
        targetEl.classList.remove('ep-jump-highlight');
        void targetEl.offsetWidth;
        targetEl.classList.add('ep-jump-highlight');
        targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
        setTimeout(function () {
            targetEl.classList.remove('ep-jump-highlight');
        }, 1800);
    }

    function ensureControlsForList(listEl, key, onCurrentClick, onToggleClick) {
        if (!listEl || !listEl.parentNode) return null;

        let toolbar = document.getElementById('mobile-ep-toolbar-' + key);
        let currentBtn = document.getElementById('btn-current-ep-' + key);
        if (!toolbar) {
            toolbar = document.createElement('div');
            toolbar.id = 'mobile-ep-toolbar-' + key;
            toolbar.className = 'mobile-ep-toolbar';

            const countSpan = document.createElement('span');
            countSpan.className = 'mobile-ep-count';
            countSpan.id = 'mobile-ep-count-' + key;

            currentBtn = document.createElement('button');
            currentBtn.type = 'button';
            currentBtn.id = 'btn-current-ep-' + key;
            currentBtn.className = 'mobile-ep-jump-btn';
            currentBtn.textContent = 'Current episode';

            toolbar.appendChild(countSpan);
            toolbar.appendChild(currentBtn);
            listEl.parentNode.insertBefore(toolbar, listEl);
        }
        if (currentBtn) currentBtn.onclick = onCurrentClick;

        let toggleBtn = document.getElementById('btn-toggle-ep-list-' + key);
        if (!toggleBtn) {
            toggleBtn = document.createElement('button');
            toggleBtn.type = 'button';
            toggleBtn.id = 'btn-toggle-ep-list-' + key;
            toggleBtn.className = 'mobile-ep-toggle-btn';
            listEl.parentNode.insertBefore(toggleBtn, listEl.nextSibling);
        }
        toggleBtn.onclick = onToggleClick;

        return {
            toolbar: toolbar,
            countSpan: document.getElementById('mobile-ep-count-' + key),
            currentBtn: document.getElementById('btn-current-ep-' + key),
            toggleBtn: toggleBtn
        };
    }

    function applyWindowToList(listId, itemSelector, key, episodes, currentEpisode, courseId) {
        const listEl = document.getElementById(listId);
        if (!listEl) return;

        if (courseId != null && courseId !== lastCourseId) {
            lastCourseId = courseId;
            expandedState.sidebar = false;
            expandedState.syllabus = false;
        }

        const items = Array.from(listEl.querySelectorAll(itemSelector));
        const total = items.length;
        const currentIdx = resolveCurrentIndex(items, episodes, currentEpisode);

        items.forEach(function (el, idx) {
            el.classList.toggle('is-current-ep', idx === currentIdx);
            el.classList.remove('mobile-ep-hidden');
        });

        const ui = ensureControlsForList(
            listEl,
            key,
            function () {
                const target = items[currentIdx];
                if (!target) return;
                if (target.classList.contains('mobile-ep-hidden')) {
                    expandedState[key] = true;
                    syncView();
                }
                highlightAndScrollTo(target);
            },
            function () {
                expandedState[key] = !expandedState[key];
                syncView();
            }
        );

        function syncView() {
            const mobile = isMobileView();
            if (!mobile || total <= 1) {
                listEl.classList.remove('mobile-list-expanded');
                items.forEach(function (el) { el.classList.remove('mobile-ep-hidden'); });
                if (ui && ui.toolbar) ui.toolbar.style.display = 'none';
                if (ui && ui.toggleBtn) ui.toggleBtn.style.display = 'none';
                return;
            }

            const start = Math.max(0, currentIdx - 1);
            const end = start === 0
                ? Math.min(total - 1, 7)
                : Math.min(total - 1, currentIdx + 6);
            const hasCollapsed = (end - start + 1) < total;
            const expanded = expandedState[key] && hasCollapsed;

            listEl.classList.toggle('mobile-list-expanded', expanded);

            let visibleCount = 0;
            items.forEach(function (el, idx) {
                const hide = !expanded && hasCollapsed && (idx < start || idx > end);
                el.classList.toggle('mobile-ep-hidden', hide);
                if (!hide) visibleCount++;
            });

            if (ui && ui.toolbar) {
                ui.toolbar.style.display = '';
            }
            if (ui && ui.countSpan) {
                ui.countSpan.textContent = expanded
                    ? 'All ' + total + ' episodes'
                    : 'Showing ' + visibleCount + ' of ' + total + ' episodes';
            }
            if (ui && ui.toggleBtn) {
                if (!hasCollapsed) {
                    ui.toggleBtn.style.display = 'none';
                } else {
                    ui.toggleBtn.style.display = '';
                    ui.toggleBtn.textContent = expanded ? 'Show fewer' : 'Show all ' + total + ' episodes';
                }
            }
        }

        listEl.dataset.mobileWindowBound = 'true';
        listEl._syncMobileWindow = syncView;
        syncView();
    }

    function enhanceSidebarList(episodes, currentEpisode, courseId) {
        applyWindowToList('episode-list', '.episode-card', 'sidebar', episodes, currentEpisode, courseId);
    }

    function enhanceSyllabusList(episodes, currentEpisode, courseId) {
        applyWindowToList('syllabus-episode-list', '.syllabus-ep-row', 'syllabus', episodes, currentEpisode, courseId);
    }

    window.addEventListener('resize', function () {
        ['episode-list', 'syllabus-episode-list'].forEach(function (id) {
            const el = document.getElementById(id);
            if (el && typeof el._syncMobileWindow === 'function') {
                el._syncMobileWindow();
            }
        });
    });

    window.MobileEpisodeList = {
        enhanceSidebarList: enhanceSidebarList,
        enhanceSyllabusList: enhanceSyllabusList
    };
})();
