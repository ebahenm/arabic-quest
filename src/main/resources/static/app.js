// Turathtona (تراثُنا) Student & Beta Sandbox Application Logic — Hardened & Anti-Cheat Verified

// Purge any legacy localStorage entries from older versions
['aq_auth_token', 'aq_role', 'aq_username', 'aq_fullname'].forEach((k) => {
    try { localStorage.removeItem(k); } catch (_) {}
});

let selectedWatchMode = 'ARABIC_SUB';
const DEFAULT_REQUIRED_WATCH_SECONDS = 1800; // 30 minutes cap
const isBetaRoute = window.location.pathname === '/beta' || window.location.pathname.startsWith('/beta/');

let currentUser = null;
let hasBetaAccess = false;
let betaUnlockAllEpisodes = false;

let coursesCatalog = [];
let currentCourse = null;
let currentView = 'CATALOG'; // 'CATALOG' | 'SYLLABUS' | 'EPISODE'
let syllabusFavoritesOnly = false;
let currentEpisode = null;
let episodes = [];
let currentQuiz = null;
const failedQuestionsByEpisodeId = {};

// YouTube IFrame API & Live Playback State
let ytPlayer = null;
let ytReady = false;
let ytApiRequested = false;
let pendingPlayerBuild = null;
let currentLoadedVideoId = null;

let serverVerifiedSeconds = 0;
let requiredWatchSeconds = 1800;
let pendingWatchedDeltaSeconds = 0;
let lastPlayerTime = 0;
let isPlayerCurrentlyPlaying = false;
let heartbeatInFlight = false;

let playbackTickTimer = null;
let cooldownInterval = null;
let cooldownRemainingSecs = 0;
let lockToastTimer = null;

function apiHeaders(includeJson = false) {
    const h = { 'X-Requested-With': 'XMLHttpRequest' };
    if (includeJson) {
        h['Content-Type'] = 'application/json';
    }
    return h;
}

function betaQuerySuffix(prefix = '?') {
    if (isBetaRoute && hasBetaAccess && betaUnlockAllEpisodes) {
        return `${prefix}betaUnlock=true`;
    }
    return '';
}

document.addEventListener('DOMContentLoaded', () => {
    wireGracefulAssetFallbacks();
    const openTextarea = document.getElementById('open-answer-textarea');
    if (openTextarea) {
        openTextarea.addEventListener('input', updateOpenAnswerCharCount);
    }
    startLocalPlaybackMonitor();
    initApp();
});

function wireGracefulAssetFallbacks() {
    const landingHeroImg = document.getElementById('landing-hero-img');
    if (landingHeroImg) {
        landingHeroImg.addEventListener('error', () => {
            landingHeroImg.style.display = 'none';
        });
    }
    const landingEmblemImg = document.getElementById('landing-emblem-img');
    const landingEmblemFallback = document.getElementById('landing-emblem-fallback');
    if (landingEmblemImg) {
        landingEmblemImg.addEventListener('error', () => {
            landingEmblemImg.style.display = 'none';
            if (landingEmblemFallback) landingEmblemFallback.style.display = 'inline';
        });
    }
    const headerEmblemImg = document.getElementById('header-emblem-img');
    if (headerEmblemImg) {
        headerEmblemImg.addEventListener('error', () => {
            headerEmblemImg.style.display = 'none';
            const parent = headerEmblemImg.parentElement;
            if (parent) parent.textContent = 'ت';
        });
    }
}

async function applyPublicBrandConfig() {
    try {
        const cfgRes = await fetch('/api/public/config', { credentials: 'same-origin' });
        if (!cfgRes.ok) return;
        const cfg = await cfgRes.json();
        if (cfg.brandNameLatin) {
            const elLanding = document.getElementById('landing-brand-latin');
            const elHeader = document.getElementById('header-brand-latin');
            if (elLanding) elLanding.textContent = cfg.brandNameLatin;
            if (elHeader) elHeader.textContent = cfg.brandNameLatin;
        }
        if (cfg.tagline) {
            const elTagLanding = document.getElementById('landing-tagline');
            const elTagHeader = document.getElementById('header-brand-tagline');
            if (elTagLanding) elTagLanding.textContent = cfg.tagline;
            if (elTagHeader) elTagHeader.textContent = cfg.tagline;
        }
    } catch (_) {}
}

async function initApp() {
    const pathname = window.location.pathname || '/';
    const isRootLandingRoute = pathname === '/' || pathname === '/index.html';
    applyPublicBrandConfig();

    try {
        const meRes = await fetch('/api/auth/me', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (!meRes.ok) {
            if (isRootLandingRoute) {
                setActivePortalView('LANDING');
                return;
            }
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
            return;
        }
        currentUser = await meRes.json();
        hasBetaAccess = Boolean(currentUser.betaTester || currentUser.role === 'ROLE_ADMIN');

        if (isBetaRoute && !hasBetaAccess) {
            window.location.replace('/');
            return;
        }

        const headerEl = document.getElementById('app-header');
        if (headerEl) headerEl.style.display = 'flex';
        const landingEl = document.getElementById('landing-view');
        if (landingEl) landingEl.style.display = 'none';

        const displayEl = document.getElementById('student-name-display');
        if (displayEl) {
            const prefix = isBetaRoute ? '🧪 ' : '🎓 ';
            displayEl.textContent = prefix + (currentUser.fullName || currentUser.username);
        }

        if (isBetaRoute && hasBetaAccess) {
            document.title = '🧪 Beta Sandbox • Turathtona';
            const betaBar = document.getElementById('beta-sandbox-bar');
            if (betaBar) betaBar.style.display = 'flex';
        } else if (!isBetaRoute && hasBetaAccess) {
            const shortcut = document.getElementById('btn-enter-beta-shortcut');
            if (shortcut) shortcut.style.display = 'inline-flex';
        }

        await Promise.all([fetchEarnings(), fetchCourseCatalog()]);
        showCourseCatalogView(false);
    } catch (e) {
        if (isRootLandingRoute) {
            setActivePortalView('LANDING');
            return;
        }
        window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
    }
}

function pausePlayerSafely() {
    if (ytReady && ytPlayer && typeof ytPlayer.pauseVideo === 'function') {
        try { ytPlayer.pauseVideo(); } catch (_) {}
    }
    isPlayerCurrentlyPlaying = false;
}

function setActivePortalView(viewName) {
    currentView = viewName;
    const landingEl = document.getElementById('landing-view');
    const headerEl = document.getElementById('app-header');
    const catEl = document.getElementById('catalog-view');
    const sylEl = document.getElementById('course-syllabus-view');
    const epEl = document.getElementById('episode-player-view');
    if (landingEl) landingEl.style.display = (viewName === 'LANDING') ? '' : 'none';
    if (headerEl && viewName === 'LANDING') headerEl.style.display = 'none';
    if (catEl) catEl.style.display = (viewName === 'CATALOG') ? 'block' : 'none';
    if (sylEl) sylEl.style.display = (viewName === 'SYLLABUS') ? 'block' : 'none';
    if (epEl) epEl.style.display = (viewName === 'EPISODE') ? 'grid' : 'none';
}

async function showCourseCatalogView(reload = true) {
    pausePlayerSafely();
    setActivePortalView('CATALOG');
    if (reload) {
        await Promise.all([fetchEarnings(), fetchCourseCatalog()]);
    }
}

async function showCourseSyllabusFromEpisode() {
    pausePlayerSafely();
    if (currentCourse) {
        await openCourseSyllabus(currentCourse.id);
    } else {
        await showCourseCatalogView(true);
    }
}

function populateCoverBox(containerEl, coverImageUrl, titleText) {
    if (!containerEl) return;
    containerEl.textContent = '';

    const fallback = document.createElement('div');
    fallback.className = 'course-cover-fallback';
    const arSpan = document.createElement('span');
    arSpan.lang = 'ar';
    arSpan.dir = 'rtl';
    arSpan.textContent = 'تراثُنا';
    const subSpan = document.createElement('span');
    subSpan.style.fontSize = '0.82rem';
    subSpan.style.color = '#f5ecd6';
    subSpan.style.fontWeight = '600';
    subSpan.textContent = titleText || 'Turathtona Course';
    fallback.appendChild(arSpan);
    fallback.appendChild(subSpan);

    if (coverImageUrl && String(coverImageUrl).trim()) {
        fallback.style.display = 'none';
        const img = document.createElement('img');
        img.src = String(coverImageUrl).trim();
        img.alt = (titleText || 'Course') + ' cover image';
        img.loading = 'lazy';
        img.addEventListener('error', () => {
            img.style.display = 'none';
            fallback.style.display = 'flex';
        });
        containerEl.appendChild(img);
    }
    containerEl.appendChild(fallback);
}

async function fetchCourseCatalog() {
    try {
        const res = await fetch('/api/student/courses', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            coursesCatalog = await res.json();
            renderCourseCatalog();
        } else if (res.status === 401) {
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
        }
    } catch (e) {
        console.warn('Course catalog fetch failed:', e);
    }
}

function renderCourseCatalog() {
    const grid = document.getElementById('course-catalog-grid');
    if (!grid) return;
    grid.textContent = '';

    if (!coursesCatalog || coursesCatalog.length === 0) {
        const emptyCard = document.createElement('div');
        emptyCard.style.background = 'var(--bg-card)';
        emptyCard.style.border = '1px solid var(--border)';
        emptyCard.style.borderRadius = 'var(--radius-lg)';
        emptyCard.style.padding = '2.5rem';
        emptyCard.style.gridColumn = '1 / -1';
        emptyCard.style.textAlign = 'center';

        const h3 = document.createElement('h3');
        h3.textContent = 'No published courses yet';
        h3.style.marginBottom = '0.4rem';

        const p = document.createElement('p');
        p.style.color = 'var(--text-muted)';
        p.style.fontSize = '0.9rem';
        p.textContent = 'New courses will appear here as soon as the administrator publishes them.';

        emptyCard.appendChild(h3);
        emptyCard.appendChild(p);
        grid.appendChild(emptyCard);
        return;
    }

    coursesCatalog.forEach((course) => {
        const card = document.createElement('article');
        card.className = 'course-catalog-card';

        const coverWrap = document.createElement('div');
        coverWrap.className = 'course-cover-wrap';
        populateCoverBox(coverWrap, course.coverImageUrl, course.title);
        card.appendChild(coverWrap);

        const body = document.createElement('div');
        body.className = 'course-card-body';

        const titleEl = document.createElement('h3');
        titleEl.className = 'course-card-title';
        titleEl.textContent = course.title;
        body.appendChild(titleEl);

        if (course.description) {
            const descEl = document.createElement('p');
            descEl.className = 'course-card-desc';
            descEl.textContent = course.description;
            body.appendChild(descEl);
        }

        const metaRow = document.createElement('div');
        metaRow.className = 'course-card-meta-row';
        const epCountSpan = document.createElement('span');
        epCountSpan.textContent = `${course.totalEpisodes} Episode${course.totalEpisodes === 1 ? '' : 's'}`;
        const ratesSpan = document.createElement('span');
        ratesSpan.textContent = `🎧 $${Number(course.rateArabicSub).toFixed(2)} • 🔊 $${Number(course.rateEnglishDub).toFixed(2)}`;
        metaRow.appendChild(epCountSpan);
        metaRow.appendChild(ratesSpan);
        body.appendChild(metaRow);

        const progWrap = document.createElement('div');
        const progHeader = document.createElement('div');
        progHeader.className = 'progress-header';
        const progLeft = document.createElement('span');
        progLeft.textContent = `${course.passedEpisodes} / ${course.totalEpisodes} passed`;
        const progRight = document.createElement('strong');
        progRight.textContent = `${course.progressPercentage}%`;
        progHeader.appendChild(progLeft);
        progHeader.appendChild(progRight);

        const progBg = document.createElement('div');
        progBg.className = 'progress-bar-bg';
        const progFill = document.createElement('div');
        progFill.className = 'progress-bar-fill';
        progFill.style.width = `${Math.min(100, Math.max(0, course.progressPercentage || 0))}%`;
        progBg.appendChild(progFill);

        progWrap.appendChild(progHeader);
        progWrap.appendChild(progBg);
        body.appendChild(progWrap);

        const earnedBox = document.createElement('div');
        earnedBox.className = 'course-earned-box';
        const earnedLeft = document.createElement('span');
        earnedLeft.textContent = `Earned: $${Number(course.earnedAmount || 0).toFixed(2)}`;
        earnedLeft.style.fontWeight = '700';
        earnedLeft.style.color = '#6ee7b7';

        const possibleRight = document.createElement('span');
        possibleRight.textContent = `of $${Number(course.possibleAmount || 0).toFixed(2)} possible`;
        possibleRight.style.color = '#fde68a';
        possibleRight.style.fontWeight = '700';

        earnedBox.appendChild(earnedLeft);
        earnedBox.appendChild(possibleRight);
        body.appendChild(earnedBox);

        const openBtn = document.createElement('button');
        openBtn.type = 'button';
        openBtn.className = 'btn-primary';
        openBtn.style.marginTop = 'auto';
        openBtn.style.justifyContent = 'center';
        openBtn.textContent = 'Open Course Syllabus ➔';
        openBtn.addEventListener('click', () => openCourseSyllabus(course.id));
        body.appendChild(openBtn);

        card.appendChild(body);
        grid.appendChild(card);
    });
}

async function openCourseSyllabus(courseId) {
    try {
        const res = await fetch(`/api/student/courses/${courseId}` + betaQuerySuffix('?'), {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            alert(err.error || 'Unable to load course.');
            return;
        }
        const detail = await res.json();
        const switchingCourse = !currentCourse || currentCourse.id !== detail.course.id;
        currentCourse = detail.course;
        episodes = detail.episodes || [];
        if (switchingCourse) {
            syllabusFavoritesOnly = false;
        }
        syncSyllabusFavFilterButton();
        applyCourseSidebarSummary(currentCourse);
        renderCourseSyllabus();
        renderEpisodeList();
        setActivePortalView('SYLLABUS');
        window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (e) {
        console.warn('Course detail load error:', e);
    }
}

function applyCourseSidebarSummary(course) {
    if (!course) return;
    const sbTitle = document.getElementById('sidebar-course-title');
    if (sbTitle) sbTitle.textContent = course.title;

    const crumb = document.getElementById('episode-course-breadcrumb');
    if (crumb) crumb.textContent = course.title;

    const countEl = document.getElementById('badge-course-count');
    if (countEl) countEl.textContent = `${course.passedEpisodes}/${course.totalEpisodes}`;

    const pctEl = document.getElementById('progress-percent-label');
    if (pctEl) pctEl.textContent = `${course.progressPercentage}%`;

    const fillEl = document.getElementById('progress-bar-fill');
    if (fillEl) fillEl.style.width = `${Math.min(100, Math.max(0, course.progressPercentage || 0))}%`;

    const bonusNote = document.getElementById('sidebar-bonus-note');
    if (bonusNote) {
        const bonusVal = Number(course.completionBonus != null ? course.completionBonus : 100).toFixed(2);
        bonusNote.textContent = course.bonusEarned
            ? `🏆 $${bonusVal} Course Completion Bonus Earned!`
            : `🏆 Pass every episode in this course to earn the $${bonusVal} Completion Bonus!`;
    }
}

function renderCourseSyllabus() {
    if (!currentCourse) return;

    populateCoverBox(
        document.getElementById('syllabus-cover-box'),
        currentCourse.coverImageUrl,
        currentCourse.title
    );

    const titleEl = document.getElementById('syllabus-course-title');
    if (titleEl) titleEl.textContent = currentCourse.title;

    const descEl = document.getElementById('syllabus-course-desc');
    if (descEl) {
        descEl.textContent = currentCourse.description || 'Watch each episode in sequence, pass the 10-question quiz (100%), and submit your written reflection.';
    }

    const bonusBadge = document.getElementById('syllabus-bonus-badge');
    if (bonusBadge) {
        const bonusVal = Number(currentCourse.completionBonus != null ? currentCourse.completionBonus : 100).toFixed(2);
        bonusBadge.textContent = currentCourse.bonusEarned
            ? `✓ $${bonusVal} Course Bonus Earned`
            : `🏆 +$${bonusVal} Completion Bonus`;
    }

    const progText = document.getElementById('syllabus-progress-text');
    if (progText) progText.textContent = `${currentCourse.passedEpisodes} / ${currentCourse.totalEpisodes} episodes passed`;

    const progPct = document.getElementById('syllabus-progress-pct');
    if (progPct) progPct.textContent = `${currentCourse.progressPercentage}%`;

    const progFill = document.getElementById('syllabus-progress-fill');
    if (progFill) progFill.style.width = `${Math.min(100, Math.max(0, currentCourse.progressPercentage || 0))}%`;

    const earnedEl = document.getElementById('syllabus-earned-val');
    if (earnedEl) earnedEl.textContent = `$${Number(currentCourse.earnedAmount || 0).toFixed(2)}`;

    const possEl = document.getElementById('syllabus-possible-val');
    if (possEl) possEl.textContent = `$${Number(currentCourse.possibleAmount || 0).toFixed(2)} possible`;

    const ratesSummary = document.getElementById('syllabus-rates-summary');
    if (ratesSummary) {
        ratesSummary.textContent = `🎧 Arabic + Subs: +$${Number(currentCourse.rateArabicSub).toFixed(2)} • 🔊 English Dub: +$${Number(currentCourse.rateEnglishDub).toFixed(2)}`;
    }

    syncSyllabusFavFilterButton();

    const favCount = episodes.filter((e) => Boolean(e.favorited)).length;
    const epCountLbl = document.getElementById('syllabus-episode-count-label');
    if (epCountLbl) {
        epCountLbl.textContent = syllabusFavoritesOnly
            ? `${favCount} favourite${favCount === 1 ? '' : 's'} of ${episodes.length} episode${episodes.length === 1 ? '' : 's'}`
            : `${episodes.length} episode${episodes.length === 1 ? '' : 's'} in course`;
    }

    const listEl = document.getElementById('syllabus-episode-list');
    if (!listEl) return;
    listEl.textContent = '';

    const visibleEpisodes = syllabusFavoritesOnly
        ? episodes.filter((e) => Boolean(e.favorited))
        : episodes;

    if (visibleEpisodes.length === 0) {
        const emptyP = document.createElement('p');
        emptyP.style.color = 'var(--text-muted)';
        emptyP.style.padding = '1.25rem';
        emptyP.textContent = syllabusFavoritesOnly
            ? 'No favourited episodes in this course yet. Tap ♡ on any episode to save it here.'
            : 'No active episodes in this course yet.';
        listEl.appendChild(emptyP);
        return;
    }

    visibleEpisodes.forEach((ep) => {
        const row = document.createElement('div');
        row.className = 'syllabus-ep-row' + (ep.locked ? ' locked' : '');
        row.addEventListener('click', () => {
            if (ep.locked) {
                showSyllabusLockedToast(ep);
                return;
            }
            openEpisodeFromSyllabus(ep);
        });

        const leftWrap = document.createElement('div');
        leftWrap.style.display = 'flex';
        leftWrap.style.alignItems = 'center';
        leftWrap.style.gap = '0.9rem';
        leftWrap.style.flex = '1';
        leftWrap.style.minWidth = '220px';

        const pill = document.createElement('div');
        pill.className = 'ep-num-pill';
        pill.textContent = ep.locked ? '🔒' : String(ep.episodeNumber);
        leftWrap.appendChild(pill);

        const textWrap = document.createElement('div');
        const t = document.createElement('div');
        t.style.fontWeight = '700';
        t.style.fontSize = '0.96rem';
        t.textContent = ep.title;

        const sub = document.createElement('div');
        sub.style.fontSize = '0.78rem';
        sub.style.color = 'var(--text-muted)';
        sub.style.marginTop = '0.18rem';
        const mins = Math.max(1, Math.round((ep.durationSeconds || 1200) / 60));
        sub.textContent = ep.locked && ep.unlockAfterEpisodeNumber
            ? `~${mins} min • Pass Episode ${ep.unlockAfterEpisodeNumber} first`
            : `~${mins} min`;

        textWrap.appendChild(t);
        textWrap.appendChild(sub);
        leftWrap.appendChild(textWrap);

        const rightWrap = document.createElement('div');
        rightWrap.className = 'syllabus-ep-rates';

        const chipAr = document.createElement('span');
        chipAr.className = 'rate-chip';
        chipAr.textContent = `🎧 Arabic + Subs: +$${Number(ep.rateArabicSub != null ? ep.rateArabicSub : 15).toFixed(2)}`;

        const chipEn = document.createElement('span');
        chipEn.className = 'rate-chip';
        chipEn.textContent = `🔊 English Dub: +$${Number(ep.rateEnglishDub != null ? ep.rateEnglishDub : 10).toFixed(2)}`;

        const stateBadge = document.createElement('span');
        const stateCode = ep.state || (ep.locked ? 'LOCKED' : (ep.passed ? 'PASSED' : 'AVAILABLE'));
        applyEpisodeStateBadgeStyle(stateBadge, stateCode);
        stateBadge.textContent = ep.stateLabel || stateCode;

        rightWrap.appendChild(chipAr);
        rightWrap.appendChild(chipEn);
        rightWrap.appendChild(stateBadge);
        rightWrap.appendChild(createFavoriteHeartButton(ep));

        row.appendChild(leftWrap);
        row.appendChild(rightWrap);
        listEl.appendChild(row);
    });
}

function toggleSyllabusFavoritesFilter() {
    syllabusFavoritesOnly = !syllabusFavoritesOnly;
    syncSyllabusFavFilterButton();
    renderCourseSyllabus();
}

function syncSyllabusFavFilterButton() {
    const btn = document.getElementById('btn-syllabus-fav-filter');
    if (!btn) return;
    const favCount = episodes.filter((e) => Boolean(e.favorited)).length;
    btn.classList.toggle('active', syllabusFavoritesOnly);
    btn.setAttribute('aria-pressed', syllabusFavoritesOnly ? 'true' : 'false');
    btn.textContent = syllabusFavoritesOnly
        ? `♥ Favourites (${favCount})`
        : (favCount > 0 ? `♡ Favourites (${favCount})` : '♡ Favourites');
}

function createFavoriteHeartButton(ep) {
    const btn = document.createElement('button');
    btn.type = 'button';
    const isFav = Boolean(ep.favorited);
    btn.className = 'fav-heart-btn' + (isFav ? ' active' : '');
    btn.textContent = isFav ? '♥' : '♡';
    btn.title = isFav ? 'Remove from favourites' : 'Add to favourites';
    btn.setAttribute('aria-label', isFav ? 'Remove episode from favourites' : 'Add episode to favourites');
    btn.setAttribute('aria-pressed', isFav ? 'true' : 'false');
    btn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        toggleEpisodeFavorite(ep);
    });
    return btn;
}

async function toggleEpisodeFavorite(ep) {
    if (!ep || !ep.id) return;
    const nextFav = !ep.favorited;
    try {
        const res = await fetch(
            `/api/student/episodes/${ep.id}/favorite` + (nextFav ? betaQuerySuffix('?') : ''),
            {
                method: nextFav ? 'POST' : 'DELETE',
                credentials: 'same-origin',
                headers: apiHeaders()
            }
        );
        if (!res.ok) return;
        const data = await res.json();
        const confirmedFav = Boolean(data.favorited);
        episodes.forEach((item) => {
            if (item.id === ep.id) item.favorited = confirmedFav;
        });
        if (currentEpisode && currentEpisode.id === ep.id) {
            currentEpisode.favorited = confirmedFav;
        }
        renderCourseSyllabus();
        renderEpisodeList();
    } catch (e) {
        console.warn('Favourite toggle error:', e);
    }
}

function showSyllabusLockedToast(ep) {
    const toast = document.getElementById('syllabus-lock-toast');
    if (!toast) return;
    toast.textContent = ep.unlockAfterEpisodeNumber
        ? `🔒 Episode ${ep.episodeNumber} is locked! Complete Episode ${ep.unlockAfterEpisodeNumber} in this course first.`
        : `🔒 Episode ${ep.episodeNumber} is currently locked by the administrator.`;
    toast.style.display = 'block';
    if (lockToastTimer) clearTimeout(lockToastTimer);
    lockToastTimer = setTimeout(() => {
        toast.style.display = 'none';
    }, 6000);
}

function openEpisodeFromSyllabus(ep) {
    setActivePortalView('EPISODE');
    selectEpisode(ep);
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function handleStudentLogout() {
    try {
        await fetch('/api/auth/logout', {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
    } catch (_) {}
    window.location.href = '/login';
}

async function fetchEarnings() {
    try {
        const res = await fetch('/api/student/earnings', {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const data = await res.json();
            document.getElementById('val-earned').textContent = '$' + Number(data.totalEarned).toFixed(2);
            const pendingEl = document.getElementById('val-pending-review');
            if (pendingEl) {
                pendingEl.textContent = '$' + Number(data.pendingReviewAmount || 0).toFixed(2);
            }
            document.getElementById('val-paid').textContent = '$' + Number(data.totalPaid).toFixed(2);
            const owedEl = document.getElementById('val-owed');
            const owedVal = Number(data.balanceOwed != null ? data.balanceOwed : 0);
            if (owedVal < 0) {
                owedEl.textContent = 'Overpaid by $' + Math.abs(owedVal).toFixed(2);
                owedEl.style.color = '#ef4444';
            } else {
                owedEl.textContent = '$' + owedVal.toFixed(2);
                owedEl.style.color = '';
            }

            if (!currentCourse) {
                const total = data.totalEpisodesCount > 0 ? data.totalEpisodesCount : 1;
                const percent = Math.min(100, Math.round((data.passedEpisodesCount / total) * 100));
                document.getElementById('badge-course-count').textContent = `${data.passedEpisodesCount}/${data.totalEpisodesCount}`;
                document.getElementById('progress-percent-label').textContent = `${percent}%`;
                document.getElementById('progress-bar-fill').style.width = `${percent}%`;
            }
        } else if (res.status === 401) {
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
        }
    } catch (e) {
        console.warn('Earnings fetch failed:', e);
    }
}

async function fetchEpisodes() {
    try {
        if (currentCourse) {
            const res = await fetch(`/api/student/courses/${currentCourse.id}` + betaQuerySuffix('?'), {
                credentials: 'same-origin',
                headers: apiHeaders()
            });
            if (res.ok) {
                const detail = await res.json();
                currentCourse = detail.course;
                episodes = detail.episodes || [];
                applyCourseSidebarSummary(currentCourse);
                renderCourseSyllabus();
                if (currentEpisode) {
                    const refreshed = episodes.find((e) => e.id === currentEpisode.id);
                    if (refreshed && !refreshed.locked) {
                        currentEpisode = refreshed;
                        applyWatchStatus(refreshed.watchStatus, refreshed.passed);
                    } else if (refreshed && refreshed.locked) {
                        const fallbackUnlocked = episodes.find((e) => !e.locked) || episodes[0];
                        if (fallbackUnlocked && currentView === 'EPISODE') selectEpisode(fallbackUnlocked);
                    }
                }
                renderEpisodeList();
                return;
            } else if (res.status === 401) {
                window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
                return;
            }
        }

        const res = await fetch('/api/student/episodes' + betaQuerySuffix('?'), {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            episodes = await res.json();
            renderEpisodeList();
        } else if (res.status === 401) {
            window.location.href = isBetaRoute ? '/login?next=/beta' : '/login';
        }
    } catch (e) {
        console.warn('Episodes fetch failed:', e);
    }
}

function showLockedEpisodeToast(ep) {
    const toast = document.getElementById('episode-lock-toast');
    if (!toast) return;
    if (ep.unlockAfterEpisodeNumber) {
        toast.textContent = `🔒 Episode ${ep.episodeNumber} is locked! Pass Episode ${ep.unlockAfterEpisodeNumber} in this course first to unlock the next video in order.`;
    } else {
        toast.textContent = `🔒 Episode ${ep.episodeNumber} is currently locked by the administrator.`;
    }
    toast.style.display = 'block';
    toast.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    if (lockToastTimer) clearTimeout(lockToastTimer);
    lockToastTimer = setTimeout(() => {
        toast.style.display = 'none';
    }, 6500);
}

function applyEpisodeStateBadgeStyle(badge, state) {
    badge.className = 'ep-badge-passed';
    badge.style.background = '';
    badge.style.color = '';
    badge.style.borderColor = '';

    if (state === 'PASSED') {
        badge.className = 'ep-badge-passed';
    } else if (state === 'LOCKED') {
        badge.className = 'ep-badge-locked';
    } else if (state === 'PENDING_REVIEW') {
        badge.style.background = 'rgba(245, 158, 11, 0.18)';
        badge.style.color = '#fde68a';
        badge.style.borderColor = 'rgba(245, 158, 11, 0.45)';
    } else if (state === 'QUIZ_READY') {
        badge.style.background = 'rgba(59, 130, 246, 0.18)';
        badge.style.color = '#93c5fd';
        badge.style.borderColor = 'rgba(59, 130, 246, 0.45)';
    } else if (state === 'QUIZ_NOT_READY') {
        badge.style.background = 'rgba(148, 163, 184, 0.16)';
        badge.style.color = '#cbd5e1';
        badge.style.borderColor = 'rgba(148, 163, 184, 0.35)';
    } else if (state === 'WATCHING') {
        badge.style.background = 'rgba(20, 184, 166, 0.18)';
        badge.style.color = '#5eead4';
        badge.style.borderColor = 'rgba(20, 184, 166, 0.45)';
    } else {
        badge.style.background = 'rgba(16, 185, 129, 0.12)';
        badge.style.color = '#a7f3d0';
        badge.style.borderColor = 'rgba(16, 185, 129, 0.3)';
    }
}

function renderEpisodeList() {
    const container = document.getElementById('episode-list');
    container.textContent = '';

    episodes.forEach((ep) => {
        const isCurrent = Boolean(currentEpisode && currentEpisode.id === ep.id);
        const card = document.createElement('div');
        card.className = 'episode-card'
            + (isCurrent ? ' active' : '')
            + (ep.locked ? ' locked' : '');

        card.onclick = () => {
            if (ep.locked) {
                showLockedEpisodeToast(ep);
                return;
            }
            selectEpisode(ep);
        };

        const pill = document.createElement('div');
        pill.className = 'ep-num-pill';
        pill.textContent = ep.locked ? '🔒' : String(ep.episodeNumber);
        card.appendChild(pill);

        const info = document.createElement('div');
        info.className = 'ep-info';

        const title = document.createElement('div');
        title.className = 'ep-title';
        title.textContent = ep.title;
        info.appendChild(title);

        const tag = document.createElement('div');
        tag.className = 'ep-tag';
        const rateAr = Number(ep.rateArabicSub != null ? ep.rateArabicSub : 15).toFixed(2);
        const rateEn = Number(ep.rateEnglishDub != null ? ep.rateEnglishDub : 10).toFixed(2);
        if (ep.locked) {
            tag.textContent = ep.unlockAfterEpisodeNumber
                ? `Pass Episode ${ep.unlockAfterEpisodeNumber} first`
                : 'Locked by administrator';
        } else {
            const mins = Math.max(1, Math.round((ep.durationSeconds || 1200) / 60));
            tag.textContent = `~${mins} min • Arabic $${rateAr} / Dub $${rateEn}`;
        }
        info.appendChild(tag);

        card.appendChild(info);

        const badge = document.createElement('div');
        const stateCode = ep.state || (ep.locked ? 'LOCKED' : (ep.passed ? 'PASSED' : 'AVAILABLE'));
        const stateLabel = ep.stateLabel || stateCode;
        applyEpisodeStateBadgeStyle(badge, stateCode);
        badge.textContent = stateLabel;
        card.appendChild(badge);
        card.appendChild(createFavoriteHeartButton(ep));

        container.appendChild(card);
    });
}

function renderModePicker(ep, statusOverride = null) {
    if (!ep) return;
    const status = statusOverride || ep.watchStatus || {};
    const modeLocked = Boolean(status.modeLocked);
    if (modeLocked && status.lockedWatchMode) {
        selectedWatchMode = status.lockedWatchMode;
    }

    const rateAr = Number(ep.rateArabicSub != null ? ep.rateArabicSub : 15).toFixed(2);
    const rateEn = Number(ep.rateEnglishDub != null ? ep.rateEnglishDub : 10).toFixed(2);

    const arRateLabel = document.getElementById('label-rate-arabic');
    if (arRateLabel) arRateLabel.textContent = `+$${rateAr} Reward`;
    const enRateLabel = document.getElementById('label-rate-english');
    if (enRateLabel) enRateLabel.textContent = `+$${rateEn} Reward`;

    const btnAr = document.getElementById('btn-mode-arabic');
    const btnEn = document.getElementById('btn-mode-english');
    if (btnAr) {
        btnAr.classList.toggle('active', selectedWatchMode === 'ARABIC_SUB');
        btnAr.disabled = modeLocked && selectedWatchMode !== 'ARABIC_SUB';
        btnAr.style.opacity = (modeLocked && selectedWatchMode !== 'ARABIC_SUB') ? '0.45' : '1';
    }
    if (btnEn) {
        btnEn.classList.toggle('active', selectedWatchMode === 'ENGLISH_DUB');
        btnEn.disabled = modeLocked && selectedWatchMode !== 'ENGLISH_DUB';
        btnEn.style.opacity = (modeLocked && selectedWatchMode !== 'ENGLISH_DUB') ? '0.45' : '1';
    }

    const banner = document.getElementById('mode-instructions-banner');
    if (banner) {
        banner.classList.toggle('english-mode', selectedWatchMode === 'ENGLISH_DUB');
    }

    const lockInd = document.getElementById('mode-lock-indicator');
    if (lockInd) {
        lockInd.style.display = modeLocked ? 'inline-flex' : 'none';
    }

    const resetBtn = document.getElementById('btn-reset-watch-mode');
    if (resetBtn) {
        const canReset = modeLocked && !ep.passed && ep.state !== 'PENDING_REVIEW';
        resetBtn.style.display = canReset ? 'inline-flex' : 'none';
    }

    const curStateBadge = document.getElementById('current-episode-state-badge');
    if (curStateBadge) {
        const stateCode = ep.state || (ep.passed ? 'PASSED' : 'AVAILABLE');
        applyEpisodeStateBadgeStyle(curStateBadge, stateCode);
        curStateBadge.textContent = ep.stateLabel || stateCode;
        curStateBadge.style.display = 'inline-flex';
    }
}

function handleSelectWatchMode(mode) {
    if (!currentEpisode) return;
    const status = currentEpisode.watchStatus || {};
    if (status.modeLocked) {
        return;
    }
    selectedWatchMode = (mode === 'ENGLISH_DUB') ? 'ENGLISH_DUB' : 'ARABIC_SUB';
    renderModePicker(currentEpisode, status);
    applyCaptionsPreference();
}

async function handleResetWatchSessionAndSwitchMode() {
    if (!currentEpisode) return;
    const nextMode = selectedWatchMode === 'ARABIC_SUB' ? 'ENGLISH_DUB' : 'ARABIC_SUB';
    if (!confirm(`Reset your current watch timer to 00:00 on this episode so you can switch to ${nextMode === 'ARABIC_SUB' ? 'Original Arabic + English Subs' : 'English Dub'}?`)) {
        return;
    }
    try {
        if (ytReady && ytPlayer && typeof ytPlayer.pauseVideo === 'function') {
            try { ytPlayer.pauseVideo(); } catch (_) {}
        }
        const res = await fetch(`/api/student/episodes/${currentEpisode.id}/reset-session?watchMode=${encodeURIComponent(nextMode)}`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            pendingWatchedDeltaSeconds = 0;
            selectedWatchMode = data.lockedWatchMode || nextMode;
            currentEpisode.watchStatus = data;
            applyWatchStatus(data, currentEpisode.passed);
            await fetchEpisodes();
        } else {
            alert(data.error || 'Could not reset watch mode.');
        }
    } catch (err) {
        alert('Error resetting watch session: ' + err.message);
    }
}

function selectEpisode(ep) {
    if (ep.locked) {
        showLockedEpisodeToast(ep);
        return;
    }

    const toast = document.getElementById('episode-lock-toast');
    if (toast) toast.style.display = 'none';

    pendingWatchedDeltaSeconds = 0;
    isPlayerCurrentlyPlaying = false;
    lastPlayerTime = 0;

    currentEpisode = ep;
    document.getElementById('current-episode-title').textContent = ep.title;

    if (ep.watchStatus && ep.watchStatus.lockedWatchMode) {
        selectedWatchMode = ep.watchStatus.lockedWatchMode;
    } else {
        selectedWatchMode = 'ARABIC_SUB';
    }

    if (ep.watchStatus) {
        applyWatchStatus(ep.watchStatus, ep.passed);
    } else {
        serverVerifiedSeconds = 0;
        requiredWatchSeconds = Math.min(DEFAULT_REQUIRED_WATCH_SECONDS, Math.max(60, ep.durationSeconds || DEFAULT_REQUIRED_WATCH_SECONDS));
        document.getElementById('quiz-section').style.display = 'none';
        renderModePicker(ep, null);
    }
    const openSec = document.getElementById('open-question-section');
    if (openSec) openSec.style.display = 'none';

    renderEpisodeList();
    updateExternalYoutubeLink();

    const cleanVid = extractYouTubeId(ep.youtubeVideoId);
    mountYouTubePlayer(cleanVid, 0, false);

    fetchVocabGuide(ep.id);
    fetchQuiz(ep.id);
}

function updateExternalYoutubeLink() {
    if (!currentEpisode) return;
    const cleanVid = extractYouTubeId(currentEpisode.youtubeVideoId);
    const ytExternalLink = document.getElementById('btn-yt-external-link');
    if (!ytExternalLink) return;

    ytExternalLink.href = `https://www.youtube.com/watch?v=${encodeURIComponent(cleanVid)}`;
}

function formatClock(totalSeconds) {
    const s = Math.max(0, Math.floor(totalSeconds || 0));
    const mins = Math.floor(s / 60);
    const secs = s % 60;
    return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

function applyWatchStatus(status, episodePassed) {
    if (!status) return;

    serverVerifiedSeconds = status.verifiedWatchSeconds || 0;
    requiredWatchSeconds = status.requiredWatchSeconds || DEFAULT_REQUIRED_WATCH_SECONDS;
    if (status.modeLocked && status.lockedWatchMode) {
        selectedWatchMode = status.lockedWatchMode;
    }

    renderModePicker(currentEpisode, status);
    updateExternalYoutubeLink();
    renderLiveWatchBar(status, episodePassed);

    const quizSection = document.getElementById('quiz-section');
    if (episodePassed || status.quizUnlocked) {
        quizSection.style.display = 'block';
    } else {
        quizSection.style.display = 'none';
    }

    startCooldownTicker(status.retryCooldownRemainingSeconds || 0);
}

function renderLiveWatchBar(statusOverride = null, episodePassedOverride = null) {
    if (!currentEpisode) return;
    const status = statusOverride || currentEpisode.watchStatus || {};
    const episodePassed = episodePassedOverride !== null ? episodePassedOverride : Boolean(currentEpisode.passed);

    const required = Math.max(1, requiredWatchSeconds || status.requiredWatchSeconds || DEFAULT_REQUIRED_WATCH_SECONDS);
    const liveWatched = Math.min(required, serverVerifiedSeconds + pendingWatchedDeltaSeconds);
    const pct = episodePassed ? 100 : Math.min(100, Math.round((liveWatched / required) * 100));

    const statusLabel = document.getElementById('watch-status-label');
    const fill = document.getElementById('watch-progress-fill');

    if (fill) fill.style.width = pct + '%';
    if (!statusLabel) return;

    if (episodePassed) {
        statusLabel.textContent = '✓ Episode Passed — Comprehension Quiz & Written Reflection Approved';
    } else if (status.quizUnlocked || liveWatched >= required) {
        statusLabel.textContent = `✓ Watch Target Reached (${formatClock(liveWatched)} / ${formatClock(required)}) — 10-Question Quiz Unlocked Below!`;
    } else {
        const statePrefix = isPlayerCurrentlyPlaying ? '▶️ Watching Live:' : '⏸️ Watch Progress (Paused):';
        statusLabel.textContent = `${statePrefix} ${formatClock(liveWatched)} / ${formatClock(required)} (${pct}%)`;
    }
}

// ============================================================================
// YOUTUBE IFRAME API & REAL PLAYBACK WATCH TRACKER
// ============================================================================

function extractYouTubeId(raw) {
    if (!raw) return 'EXrpLeyeSeU';
    const s = String(raw).trim();
    if (s.includes('youtu.be/')) return s.split('youtu.be/')[1].split(/[?&/#]/)[0];
    if (s.includes('v=')) return s.split('v=')[1].split(/[?&#]/)[0];
    if (s.includes('/embed/')) return s.split('/embed/')[1].split(/[?&/#]/)[0];
    if (s.includes('/shorts/')) return s.split('/shorts/')[1].split(/[?&/#]/)[0];
    return s;
}

function getSafeCurrentTime() {
    try {
        if (ytReady && ytPlayer && typeof ytPlayer.getCurrentTime === 'function') {
            return Math.max(0, Number(ytPlayer.getCurrentTime() || 0));
        }
    } catch (_) {}
    return 0;
}

function getSafeDurationSeconds() {
    try {
        if (ytReady && ytPlayer && typeof ytPlayer.getDuration === 'function') {
            const d = Math.round(Number(ytPlayer.getDuration() || 0));
            return d >= 60 ? d : null;
        }
    } catch (_) {}
    return null;
}

function mountYouTubePlayer(videoId, startSeconds = 0, autoplay = false) {
    const cleanId = extractYouTubeId(videoId);
    pendingPlayerBuild = { videoId: cleanId, startSeconds, autoplay };

    if (!(window.YT && window.YT.Player)) {
        ensureYouTubeIframeScriptLoaded();
        return;
    }

    buildPlayerFromPending();
}

function ensureYouTubeIframeScriptLoaded() {
    window.onYouTubeIframeAPIReady = () => {
        buildPlayerFromPending();
    };
    if (window.YT && window.YT.Player) {
        buildPlayerFromPending();
        return;
    }
    if (!ytApiRequested) {
        ytApiRequested = true;
        const tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        tag.async = true;
        document.head.appendChild(tag);
    }
}

function buildPlayerFromPending() {
    if (!pendingPlayerBuild || !(window.YT && window.YT.Player)) return;
    const { videoId, startSeconds, autoplay } = pendingPlayerBuild;
    pendingPlayerBuild = null;

    const wrapper = document.getElementById('player-wrapper');
    if (!wrapper) return;

    if (ytPlayer && typeof ytPlayer.destroy === 'function') {
        try { ytPlayer.destroy(); } catch (_) {}
    }
    ytPlayer = null;
    ytReady = false;

    wrapper.textContent = '';
    const mountDiv = document.createElement('div');
    mountDiv.id = 'yt-player-mount';
    wrapper.appendChild(mountDiv);

    currentLoadedVideoId = videoId;

    const playerVars = {
        enablejsapi: 1,
        playsinline: 1,
        rel: 0,
        origin: window.location.origin
    };
    if (startSeconds > 0) {
        playerVars.start = Math.floor(startSeconds);
    }
    if (autoplay) {
        playerVars.autoplay = 1;
    }

    try {
        ytPlayer = new YT.Player('yt-player-mount', {
            width: '100%',
            height: '100%',
            videoId: videoId,
            playerVars: playerVars,
            events: {
                onReady: onPlayerReady,
                onStateChange: onPlayerStateChange
            }
        });
    } catch (err) {
        console.warn('YT.Player creation error:', err);
    }
}

function onPlayerReady() {
    ytReady = true;
    lastPlayerTime = getSafeCurrentTime();
    applyCaptionsPreference();
}

function applyCaptionsPreference() {
    if (!ytReady || !ytPlayer) return;
    try {
        if (typeof ytPlayer.loadModule === 'function') {
            ytPlayer.loadModule('captions');
        }
        if (typeof ytPlayer.setOption === 'function') {
            ytPlayer.setOption('captions', 'track', { languageCode: 'en' });
        }
    } catch (_) {}
}

function onPlayerStateChange(event) {
    if (!window.YT || !window.YT.PlayerState) return;

    if (event.data === YT.PlayerState.PLAYING) {
        isPlayerCurrentlyPlaying = true;
        lastPlayerTime = getSafeCurrentTime();
        applyCaptionsPreference();
        renderLiveWatchBar();

        // Initialize server heartbeat timestamp immediately when playback starts
        flushWatchHeartbeat({
            playing: true,
            videoEnded: false,
            external: false,
            videoDurationSeconds: getSafeDurationSeconds()
        });
    } else if (event.data === YT.PlayerState.PAUSED) {
        isPlayerCurrentlyPlaying = false;
        renderLiveWatchBar();
        if (pendingWatchedDeltaSeconds > 0) {
            flushWatchHeartbeat({
                playing: false,
                videoEnded: false,
                external: false,
                videoDurationSeconds: getSafeDurationSeconds()
            });
        }
    } else if (event.data === YT.PlayerState.ENDED) {
        isPlayerCurrentlyPlaying = false;
        renderLiveWatchBar();
        flushWatchHeartbeat({
            playing: false,
            videoEnded: true,
            external: false,
            videoDurationSeconds: getSafeDurationSeconds()
        });
    }
}

function startLocalPlaybackMonitor() {
    if (playbackTickTimer) clearInterval(playbackTickTimer);

    playbackTickTimer = setInterval(() => {
        if (!currentEpisode || !ytReady || !ytPlayer || typeof ytPlayer.getPlayerState !== 'function') {
            return;
        }
        if (!window.YT || !window.YT.PlayerState) return;

        let state;
        let curTime;
        try {
            state = ytPlayer.getPlayerState();
            curTime = Number(ytPlayer.getCurrentTime() || 0);
        } catch (_) {
            return;
        }

        if (state === YT.PlayerState.PLAYING) {
            isPlayerCurrentlyPlaying = true;
            const delta = curTime - lastPlayerTime;
            lastPlayerTime = curTime;

            // Only credit real sequential video playback (0.15s..2.75s per 1s tick).
            // Dragging/scrubbing the seekbar forward produces delta > 2.75s and is ignored!
            if (delta > 0.15 && delta <= 2.75) {
                pendingWatchedDeltaSeconds += 1;
                renderLiveWatchBar();

                const targetRemaining = Math.max(0, requiredWatchSeconds - serverVerifiedSeconds);
                if (!heartbeatInFlight && (pendingWatchedDeltaSeconds >= 5 || (targetRemaining > 0 && pendingWatchedDeltaSeconds >= targetRemaining))) {
                    flushWatchHeartbeat({
                        playing: true,
                        videoEnded: false,
                        external: false,
                        videoDurationSeconds: getSafeDurationSeconds()
                    });
                }
            }
        } else {
            if (isPlayerCurrentlyPlaying) {
                isPlayerCurrentlyPlaying = false;
                renderLiveWatchBar();
            }
            lastPlayerTime = curTime;
        }
    }, 1000);
}

async function flushWatchHeartbeat(opts = {}) {
    if (!currentEpisode) return;

    const deltaToSend = pendingWatchedDeltaSeconds;
    pendingWatchedDeltaSeconds = Math.max(0, pendingWatchedDeltaSeconds - deltaToSend);

    heartbeatInFlight = true;
    try {
        const bodyPayload = {
            watchMode: selectedWatchMode,
            external: false,
            videoEnded: Boolean(opts.videoEnded),
            watchedDeltaSeconds: deltaToSend,
            currentPositionSeconds: Math.floor(getSafeCurrentTime()),
            playing: Boolean(opts.playing)
        };

        const res = await fetch(`/api/student/episodes/${currentEpisode.id}/heartbeat` + betaQuerySuffix('?'), {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify(bodyPayload)
        });

        if (res.ok) {
            const wasUnlocked = Boolean(currentEpisode.watchStatus && currentEpisode.watchStatus.quizUnlocked);
            const wasModeLocked = Boolean(currentEpisode.watchStatus && currentEpisode.watchStatus.modeLocked);
            const status = await res.json();
            currentEpisode.watchStatus = status;
            applyWatchStatus(status, currentEpisode.passed);

            if ((!wasModeLocked && status.modeLocked) || (!wasUnlocked && status.quizUnlocked)) {
                fetchEpisodes();
            }
            if (!wasUnlocked && status.quizUnlocked && !currentEpisode.passed) {
                if (typeof confetti === 'function') {
                    confetti({ particleCount: 55, spread: 60, origin: { y: 0.7 } });
                }
            }
        } else if (deltaToSend > 0) {
            pendingWatchedDeltaSeconds += deltaToSend;
        }
    } catch (e) {
        if (deltaToSend > 0) {
            pendingWatchedDeltaSeconds += deltaToSend;
        }
        console.warn('Heartbeat sync warning:', e);
    } finally {
        heartbeatInFlight = false;
    }
}

// ============================================================================
// BETA SANDBOX CONTROLS (/beta — Strictly Gated to Admin & Beta Testers)
// ============================================================================

function setBetaStatusMessage(msg) {
    const el = document.getElementById('beta-status-msg');
    if (el) el.textContent = msg;
}

async function betaInstantUnlockQuiz() {
    if (!currentEpisode || !hasBetaAccess) return;
    try {
        const res = await fetch(`/api/beta/episodes/${currentEpisode.id}/unlock-watch?watchMode=${encodeURIComponent(selectedWatchMode)}`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const status = await res.json();
            pendingWatchedDeltaSeconds = 0;
            currentEpisode.watchStatus = status;
            applyWatchStatus(status, currentEpisode.passed);
            await fetchEpisodes();
            setBetaStatusMessage(`✓ Watch target unlocked instantly (${selectedWatchMode}) for Ep. ${currentEpisode.episodeNumber}.`);
            document.getElementById('quiz-section').scrollIntoView({ behavior: 'smooth', block: 'start' });
        } else {
            const err = await res.json().catch(() => ({}));
            alert(err.error || 'Beta unlock failed');
        }
    } catch (e) {
        alert('Error: ' + e.message);
    }
}

async function betaToggleUnlockAllEpisodes() {
    if (!hasBetaAccess) return;
    betaUnlockAllEpisodes = !betaUnlockAllEpisodes;
    const btn = document.getElementById('btn-beta-unlock-all');
    if (btn) {
        btn.textContent = betaUnlockAllEpisodes ? '🔓 Unlock All Episodes: ON' : '🔓 Unlock All Episodes: OFF';
        btn.classList.toggle('active-toggle', betaUnlockAllEpisodes);
    }
    setBetaStatusMessage(betaUnlockAllEpisodes
        ? '🔓 All 15 episodes unlocked for free navigation in Beta Sandbox.'
        : '🔒 Strict sequential progression active (Episode N requires Episode N-1 passed).');
    await fetchEpisodes();
}

async function betaClearCooldown() {
    if (!currentEpisode || !hasBetaAccess) return;
    try {
        const res = await fetch(`/api/beta/episodes/${currentEpisode.id}/clear-cooldown`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            startCooldownTicker(0);
            setBetaStatusMessage(`✓ Retry cooldown cleared for Episode ${currentEpisode.episodeNumber}.`);
            await fetchEpisodes();
        }
    } catch (e) {
        console.warn('Clear cooldown failed:', e);
    }
}

async function betaResetMyProgress() {
    if (!hasBetaAccess) return;
    if (!confirm('Reset ALL of your own Beta/Test watch timers, passed episodes, and test balance back to $0.00? (Other student accounts are untouched.)')) {
        return;
    }
    try {
        const res = await fetch('/api/beta/reset-progress', {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            pendingWatchedDeltaSeconds = 0;
            serverVerifiedSeconds = 0;
            currentEpisode = null;
            await Promise.all([fetchEarnings(), fetchEpisodes()]);
            setBetaStatusMessage('✓ Your beta test progress & $0.00 balance have been reset!');
        } else {
            const err = await res.json().catch(() => ({}));
            alert(err.error || 'Reset failed');
        }
    } catch (e) {
        alert('Reset failed: ' + e.message);
    }
}

// ============================================================================
// COOLDOWN, VOCABULARY, & QUIZ SUBMISSION
// ============================================================================

function startCooldownTicker(seconds) {
    if (cooldownInterval) {
        clearInterval(cooldownInterval);
        cooldownInterval = null;
    }
    cooldownRemainingSecs = Math.max(0, Math.floor(seconds || 0));
    renderCooldownBanner();

    if (cooldownRemainingSecs > 0) {
        cooldownInterval = setInterval(() => {
            cooldownRemainingSecs = Math.max(0, cooldownRemainingSecs - 1);
            renderCooldownBanner();
            if (cooldownRemainingSecs <= 0) {
                clearInterval(cooldownInterval);
                cooldownInterval = null;
                if (currentEpisode) fetchQuiz(currentEpisode.id);
            }
        }, 1000);
    }
}

function renderCooldownBanner() {
    const banner = document.getElementById('cooldown-banner');
    const submitBtn = document.getElementById('btn-submit-quiz');
    if (!banner || !submitBtn) return;

    if (cooldownRemainingSecs > 0) {
        banner.style.display = 'block';
        banner.textContent = `⏳ Review Cooldown Active: Please review the lesson! You can retry the quiz in ${formatClock(cooldownRemainingSecs)}.`;
        submitBtn.disabled = true;
        submitBtn.style.opacity = '0.5';
    } else {
        banner.style.display = 'none';
        submitBtn.disabled = false;
        submitBtn.style.opacity = '1';
    }
}

async function fetchVocabGuide(episodeId) {
    const drawer = document.getElementById('vocab-drawer-box');
    const container = document.getElementById('vocab-grid');
    if (!container) return;
    container.textContent = '';

    try {
        const res = await fetch(`/api/student/episodes/${episodeId}/vocab-guide`, {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            const words = await res.json();
            if (!words || words.length === 0) {
                if (drawer) drawer.style.display = 'none';
                return;
            }
            if (drawer) drawer.style.display = 'block';
            words.forEach((w) => {
                const card = document.createElement('div');
                card.className = 'vocab-card';

                const ar = document.createElement('div');
                ar.className = 'vocab-arabic';
                ar.textContent = w.arabic || '';

                const tr = document.createElement('div');
                tr.className = 'vocab-translit';
                tr.textContent = w.translit || '';

                const en = document.createElement('div');
                en.className = 'vocab-english';
                en.textContent = w.english || '';

                const note = document.createElement('div');
                note.className = 'vocab-note';
                note.textContent = w.note || '';

                card.appendChild(ar);
                card.appendChild(tr);
                card.appendChild(en);
                card.appendChild(note);
                container.appendChild(card);
            });
        } else if (drawer) {
            drawer.style.display = 'none';
        }
    } catch (e) {
        if (drawer) drawer.style.display = 'none';
        console.warn('Vocab fetch failed:', e);
    }
}

async function fetchQuiz(episodeId) {
    try {
        const res = await fetch(`/api/student/quizzes/${episodeId}` + betaQuerySuffix('?'), {
            credentials: 'same-origin',
            headers: apiHeaders()
        });
        if (res.ok) {
            currentQuiz = await res.json();
            renderQuizQuestions();
            renderFailedReportPanel(episodeId);
            renderOpenQuestionSection(currentQuiz.openQuestion || null);
        } else {
            currentQuiz = null;
            renderOpenQuestionSection(null);
        }
    } catch (e) {
        console.warn('Quiz fetch failed:', e);
    }
}

function updateQuizAnsweredCounter() {
    const answeredBadge = document.getElementById('quiz-answered-badge');
    if (!answeredBadge || !currentQuiz || !currentQuiz.questions) return;
    const total = currentQuiz.questions.length;
    let answered = 0;
    currentQuiz.questions.forEach((q) => {
        if (document.querySelector(`input[name="question_${q.id}"]:checked`)) {
            answered++;
        }
    });
    answeredBadge.textContent = `Answered: ${answered} / ${total}`;
}

function renderFailedReportPanel(episodeId) {
    const panel = document.getElementById('failed-report-panel');
    const listEl = document.getElementById('failed-report-list');
    if (!panel || !listEl) return;
    listEl.textContent = '';

    const failedList = failedQuestionsByEpisodeId[episodeId];
    if (!failedList || failedList.length === 0) {
        panel.style.display = 'none';
        return;
    }

    panel.style.display = 'block';
    failedList.forEach((q, idx) => {
        const row = document.createElement('div');
        row.style.display = 'flex';
        row.style.justifyContent = 'space-between';
        row.style.alignItems = 'center';
        row.style.gap = '0.75rem';
        row.style.flexWrap = 'wrap';
        row.style.padding = '0.55rem 0.75rem';
        row.style.background = 'var(--bg-subtle)';
        row.style.border = '1px solid var(--border)';
        row.style.borderRadius = 'var(--radius-sm)';

        const qText = document.createElement('span');
        qText.style.fontSize = '0.84rem';
        qText.style.color = '#e2e8f0';
        qText.style.flex = '1';
        qText.textContent = `Q${idx + 1}: ${q.questionText}`;

        const actionsWrap = document.createElement('div');
        actionsWrap.style.display = 'flex';
        actionsWrap.style.alignItems = 'center';
        actionsWrap.style.gap = '0.45rem';

        const reasonInput = document.createElement('input');
        reasonInput.type = 'text';
        reasonInput.maxLength = 500;
        reasonInput.placeholder = 'Optional reason...';
        reasonInput.style.padding = '0.3rem 0.55rem';
        reasonInput.style.fontSize = '0.78rem';
        reasonInput.style.borderRadius = '6px';
        reasonInput.style.border = '1px solid var(--border)';
        reasonInput.style.background = 'var(--bg-card)';
        reasonInput.style.color = '#f8fafc';

        const reportBtn = document.createElement('button');
        reportBtn.type = 'button';
        reportBtn.className = 'btn-reset-mode';
        reportBtn.textContent = '🚩 Report this question';
        reportBtn.addEventListener('click', async () => {
            reportBtn.disabled = true;
            try {
                const res = await fetch(`/api/student/questions/${q.id}/report`, {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: apiHeaders(true),
                    body: JSON.stringify({ reason: reasonInput.value.trim() || null })
                });
                const data = await res.json().catch(() => ({}));
                if (res.ok) {
                    reportBtn.textContent = '✓ Reported';
                    reasonInput.disabled = true;
                } else {
                    reportBtn.disabled = false;
                    alert(data.error || 'Could not report question.');
                }
            } catch (err) {
                reportBtn.disabled = false;
                alert('Error reporting question: ' + err.message);
            }
        });

        actionsWrap.appendChild(reasonInput);
        actionsWrap.appendChild(reportBtn);
        row.appendChild(qText);
        row.appendChild(actionsWrap);
        listEl.appendChild(row);
    });
}

function renderQuizQuestions() {
    const container = document.getElementById('questions-container');
    const submitBtn = document.getElementById('btn-submit-quiz');
    container.textContent = '';

    if (!currentQuiz || currentQuiz.ready === false || !currentQuiz.questions || currentQuiz.questions.length === 0) {
        const p = document.createElement('p');
        p.style.color = 'var(--text-muted)';
        p.style.padding = '1rem';
        p.textContent = (currentQuiz && currentQuiz.message) ? currentQuiz.message : 'Quiz not ready yet';
        container.appendChild(p);
        if (submitBtn) {
            submitBtn.style.display = 'none';
        }
        return;
    }

    if (submitBtn) {
        submitBtn.style.display = 'inline-flex';
    }

    const totalQ = currentQuiz.questions.length;
    const passPct = Math.round(currentQuiz.passingScorePercentage || 100);
    const requiredCorrect = Math.ceil((passPct / 100) * totalQ);

    const quizHeading = document.getElementById('quiz-heading-title');
    if (quizHeading) {
        quizHeading.textContent = `Comprehension Quiz (${totalQ} Questions)`;
    }
    const passBadge = document.getElementById('quiz-pass-badge');
    if (passBadge) {
        passBadge.textContent = `Pass Mark: ${passPct}% (${requiredCorrect}/${totalQ})`;
    }

    currentQuiz.questions.forEach((q, idx) => {
        const block = document.createElement('div');
        block.className = 'question-block';

        const heading = document.createElement('div');
        heading.className = 'question-heading';
        heading.textContent = `Question ${idx + 1} (${idx + 1}/${totalQ}): ${q.questionText}`;
        block.appendChild(heading);

        const grid = document.createElement('div');
        grid.className = 'options-grid';

        q.options.forEach((opt) => {
            const label = document.createElement('label');
            label.className = 'option-pill';

            const radio = document.createElement('input');
            radio.type = 'radio';
            radio.name = `question_${q.id}`;
            radio.value = String(opt.id);
            radio.required = true;
            radio.addEventListener('change', updateQuizAnsweredCounter);

            const span = document.createElement('span');
            span.textContent = opt.optionText;

            label.appendChild(radio);
            label.appendChild(span);
            grid.appendChild(label);
        });

        block.appendChild(grid);
        container.appendChild(block);
    });

    updateQuizAnsweredCounter();
}

function updateOpenAnswerCharCount() {
    const textarea = document.getElementById('open-answer-textarea');
    const counter = document.getElementById('open-answer-char-count');
    if (!textarea || !counter) return;
    const len = textarea.value.trim().length;
    counter.textContent = `${len} / 2,000 characters (minimum 40)`;
    counter.style.color = (len >= 40 && len <= 2000) ? '#6ee7b7' : 'var(--text-muted)';
}

function renderOpenQuestionSection(openQuestion) {
    const section = document.getElementById('open-question-section');
    if (!section) return;

    if (!openQuestion) {
        section.style.display = 'none';
        return;
    }

    section.style.display = 'block';

    const promptEl = document.getElementById('open-q-prompt-text');
    if (promptEl) promptEl.textContent = openQuestion.promptText || '';

    const statusBadge = document.getElementById('open-q-status-badge');
    const feedbackBox = document.getElementById('open-q-feedback-box');
    const feedbackText = document.getElementById('open-q-feedback-text');
    const textarea = document.getElementById('open-answer-textarea');
    const submitBtn = document.getElementById('btn-submit-open-answer');
    const msgEl = document.getElementById('open-answer-msg');
    if (msgEl) {
        msgEl.style.display = 'none';
        msgEl.textContent = '';
    }

    const st = openQuestion.submissionStatus || 'AWAITING_SUBMISSION';
    const rewardStr = openQuestion.potentialRewardAmount != null
        ? `+$${Number(openQuestion.potentialRewardAmount).toFixed(2)}`
        : '+$15.00';

    if (statusBadge) {
        if (st === 'APPROVED') {
            statusBadge.textContent = `✓ Approved (${rewardStr} Earned)`;
        } else if (st === 'PENDING') {
            statusBadge.textContent = `⏳ Pending Admin Review (${rewardStr})`;
        } else if (st === 'REVISION_REQUESTED') {
            statusBadge.textContent = `↩ Revision Requested (${rewardStr})`;
        } else {
            statusBadge.textContent = `✍️ Awaiting Written Answer (${rewardStr})`;
        }
    }

    if (feedbackBox && feedbackText) {
        if (openQuestion.adminFeedback) {
            feedbackBox.style.display = 'block';
            feedbackText.textContent = openQuestion.adminFeedback;
        } else {
            feedbackBox.style.display = 'none';
            feedbackText.textContent = '';
        }
    }

    if (textarea) {
        if (openQuestion.submittedAnswerText && (!textarea.value || st === 'APPROVED' || st === 'PENDING')) {
            textarea.value = openQuestion.submittedAnswerText;
        }
        textarea.disabled = (st === 'APPROVED');
        updateOpenAnswerCharCount();
    }

    if (submitBtn) {
        submitBtn.style.display = (st === 'APPROVED') ? 'none' : 'inline-flex';
        const labelSpan = submitBtn.querySelector('span');
        if (labelSpan) {
            if (st === 'REVISION_REQUESTED') {
                labelSpan.textContent = 'Resubmit Revised Answer';
            } else if (st === 'PENDING') {
                labelSpan.textContent = 'Update Submitted Answer';
            } else {
                labelSpan.textContent = 'Submit Written Answer for Approval';
            }
        }
    }
}

async function handleOpenAnswerSubmit(event) {
    event.preventDefault();
    if (!currentEpisode) return;

    const textarea = document.getElementById('open-answer-textarea');
    const submitBtn = document.getElementById('btn-submit-open-answer');
    const msgEl = document.getElementById('open-answer-msg');
    if (!textarea || !submitBtn) return;

    const answerText = textarea.value.trim();
    if (answerText.length < 40 || answerText.length > 2000) {
        if (msgEl) {
            msgEl.style.display = 'block';
            msgEl.style.color = '#fca5a5';
            msgEl.textContent = 'Please write between 40 and 2,000 characters in English.';
        }
        return;
    }

    submitBtn.disabled = true;
    try {
        const res = await fetch(`/api/student/episodes/${currentEpisode.id}/open-answer`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify({ answerText })
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            renderOpenQuestionSection(data);
            if (msgEl) {
                msgEl.style.display = 'block';
                msgEl.style.color = '#6ee7b7';
                msgEl.textContent = '✓ Written answer submitted! Your reward now appears under Pending Review until approved.';
            }
            await Promise.all([fetchEarnings(), fetchEpisodes()]);
        } else if (msgEl) {
            msgEl.style.display = 'block';
            msgEl.style.color = '#fca5a5';
            msgEl.textContent = data.error || 'Could not submit written answer.';
        }
    } catch (err) {
        if (msgEl) {
            msgEl.style.display = 'block';
            msgEl.style.color = '#fca5a5';
            msgEl.textContent = 'Connection error: ' + err.message;
        }
    } finally {
        submitBtn.disabled = false;
    }
}

async function handleQuizSubmit(event) {
    event.preventDefault();
    if (!currentQuiz || !currentEpisode || !currentQuiz.sessionId) return;

    const submitBtn = document.getElementById('btn-submit-quiz');
    submitBtn.disabled = true;

    const attemptedQuestionsSnapshot = (currentQuiz.questions || []).map((q) => ({
        id: q.id,
        questionText: q.questionText
    }));

    const answers = {};
    currentQuiz.questions.forEach((q) => {
        const checked = document.querySelector(`input[name="question_${q.id}"]:checked`);
        if (checked) {
            answers[q.id] = parseInt(checked.value, 10);
        }
    });

    const payload = {
        sessionId: currentQuiz.sessionId,
        episodeId: currentEpisode.id,
        watchMode: selectedWatchMode,
        answers: answers
    };

    try {
        const res = await fetch('/api/student/quizzes/submit' + betaQuerySuffix('?'), {
            method: 'POST',
            credentials: 'same-origin',
            headers: apiHeaders(true),
            body: JSON.stringify(payload)
        });

        if (res.ok) {
            const result = await res.json();
            if (result.passed) {
                delete failedQuestionsByEpisodeId[currentEpisode.id];
            } else {
                failedQuestionsByEpisodeId[currentEpisode.id] = attemptedQuestionsSnapshot;
            }
            await Promise.all([fetchEarnings(), fetchEpisodes(), fetchQuiz(currentEpisode.id)]);
            if (result.openQuestion) {
                renderOpenQuestionSection(result.openQuestion);
            }
            showResultModal(result);
        } else {
            const err = await res.json().catch(() => ({}));
            if (res.status === 429 && err.retryAfterSeconds) {
                startCooldownTicker(err.retryAfterSeconds);
            }
            if (res.status === 400 && currentEpisode) {
                await fetchQuiz(currentEpisode.id);
            }
            alert(err.error || 'Unable to submit quiz.');
        }
    } catch (e) {
        alert('Submission error: ' + e.message);
    } finally {
        if (cooldownRemainingSecs <= 0) {
            submitBtn.disabled = false;
        }
    }
}

function showResultModal(result) {
    const modal = document.getElementById('result-modal');
    const dialog = document.getElementById('modal-dialog');
    const title = document.getElementById('modal-title');
    const score = document.getElementById('modal-score');
    const message = document.getElementById('modal-message');
    const nextBtn = document.getElementById('btn-modal-next-ep');

    if (result.passed) {
        dialog.className = 'modal-dialog';
        title.textContent = '🎉 Congratulations!';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;

        const nextEp = findNextSequentialEpisode();
        if (nextBtn) {
            nextBtn.style.display = nextEp ? 'inline-flex' : 'none';
        }

        if (typeof confetti === 'function') {
            confetti({ particleCount: 120, spread: 80, origin: { y: 0.6 } });
        }
    } else {
        dialog.className = 'modal-dialog failed';
        title.textContent = '❌ Review & Retry Later';
        score.textContent = result.scorePercentage.toFixed(0) + '%';
        message.textContent = result.message;
        if (nextBtn) nextBtn.style.display = 'none';
    }

    modal.style.display = 'flex';
}

function findNextSequentialEpisode() {
    if (!currentEpisode || !episodes.length) return null;
    const idx = episodes.findIndex((e) => e.id === currentEpisode.id);
    if (idx >= 0 && idx + 1 < episodes.length && !episodes[idx + 1].locked) {
        return episodes[idx + 1];
    }
    return null;
}

function advanceToNextUnlockedEpisode() {
    closeModal();
    const nextEp = findNextSequentialEpisode();
    if (nextEp) {
        selectEpisode(nextEp);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }
}

function closeModal() {
    document.getElementById('result-modal').style.display = 'none';
}
