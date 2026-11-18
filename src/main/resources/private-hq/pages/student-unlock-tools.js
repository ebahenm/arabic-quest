// Per-student episode access override tools: Unlock video access, Unlock quiz, Lock again, Unlock next episode, and Unlock all episodes.
(function () {
    'use strict';

    function ensureBulkUnlockToolbar() {
        if (document.getElementById('btn-unlock-next-episode')) return;
        const refreshBtn = document.getElementById('btn-refresh-student-overrides');
        if (!refreshBtn || !refreshBtn.parentElement) return;

        const bar = document.createElement('div');
        bar.style.display = 'flex';
        bar.style.gap = '0.5rem';
        bar.style.flexWrap = 'wrap';
        bar.style.alignItems = 'center';

        const btnNext = document.createElement('button');
        btnNext.type = 'button';
        btnNext.id = 'btn-unlock-next-episode';
        btnNext.className = 'btn-primary';
        btnNext.style.padding = '0.45rem 0.85rem';
        btnNext.style.fontSize = '0.8rem';
        btnNext.textContent = '🔓 Unlock Next Episode';
        btnNext.addEventListener('click', handleUnlockNextEpisode);

        const btnAll = document.createElement('button');
        btnAll.type = 'button';
        btnAll.id = 'btn-unlock-all-episodes';
        btnAll.className = 'btn-ghost';
        btnAll.style.padding = '0.45rem 0.85rem';
        btnAll.style.fontSize = '0.8rem';
        btnAll.textContent = '🔓 Unlock All Episodes for This Student';
        btnAll.addEventListener('click', handleUnlockAllEpisodes);

        refreshBtn.parentElement.insertBefore(bar, refreshBtn);
        bar.appendChild(btnNext);
        bar.appendChild(btnAll);
        bar.appendChild(refreshBtn);
    }

    function requireSelectedStudentOrAlert() {
        if (!selectedStudent || !selectedStudent.id) {
            showFeedback('student-overrides-msg', 'Please select a student in the "Acting on" selector above first.', true);
            return null;
        }
        return selectedStudent;
    }

    async function handleUnlockNextEpisode() {
        hideFeedback('student-overrides-msg');
        const student = requireSelectedStudentOrAlert();
        if (!student) return;
        try {
            const res = await window.AdminApi.unlockNextEpisode(student.id, null);
            showFeedback('student-overrides-msg', `✓ ${res.message || 'Unlocked next episode.'}`);
            await loadStudentEpisodeOverrides(student.id);
            if (typeof window.refreshStudentOverviewCard === 'function') {
                await window.refreshStudentOverviewCard();
            }
        } catch (err) {
            showFeedback('student-overrides-msg', err.message, true);
        }
    }

    async function handleUnlockAllEpisodes() {
        hideFeedback('student-overrides-msg');
        const student = requireSelectedStudentOrAlert();
        if (!student) return;
        const confirmed = window.confirm(
            `Confirm unlocking ALL episodes for ${student.fullName} (${student.username})?\n\nThis creates video access overrides only and never awards rewards.`
        );
        if (!confirmed) return;
        try {
            const res = await window.AdminApi.unlockAllEpisodes(student.id, null);
            showFeedback('student-overrides-msg', `✓ ${res.message || 'Unlocked all episodes.'}`);
            await loadStudentEpisodeOverrides(student.id);
            if (typeof window.refreshStudentOverviewCard === 'function') {
                await window.refreshStudentOverviewCard();
            }
        } catch (err) {
            showFeedback('student-overrides-msg', err.message, true);
        }
    }

    window.loadStudentEpisodeOverrides = async function (studentId) {
        ensureBulkUnlockToolbar();
        const tbody = document.getElementById('student-overrides-tbody');
        if (!tbody) return;
        tbody.textContent = '';

        const targetId = studentId || (selectedStudent ? selectedStudent.id : null);
        if (!targetId) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 6;
            td.style.textAlign = 'center';
            td.style.padding = '1.25rem';
            td.style.color = '#fcd34d';
            td.textContent = 'Select a student in the "Acting on" selector above to manage their episode access overrides.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        try {
            const res = await apiFetch(`/api/admin/students/${encodeURIComponent(targetId)}/episode-overrides`);
            if (!res.ok) return;
            const rows = await res.json();
            if (!rows || rows.length === 0) {
                const tr = document.createElement('tr');
                const td = document.createElement('td');
                td.colSpan = 6;
                td.style.textAlign = 'center';
                td.style.padding = '1rem';
                td.style.color = 'var(--text-muted)';
                td.textContent = 'No episodes available yet.';
                tr.appendChild(td);
                tbody.appendChild(tr);
                return;
            }

            rows.forEach((row) => {
                const tr = document.createElement('tr');

                const tdCourse = document.createElement('td');
                tdCourse.style.fontSize = '0.82rem';
                tdCourse.style.color = 'var(--text-muted)';
                tdCourse.textContent = row.courseTitle || `Course #${row.courseId}`;

                const tdEp = document.createElement('td');
                const strongEp = document.createElement('strong');
                strongEp.textContent = `Ep. ${row.episodeNumber}: ${row.episodeTitle}`;
                tdEp.appendChild(strongEp);

                const tdEff = document.createElement('td');
                const effBadge = document.createElement('span');
                Object.assign(effBadge.style, {
                    fontSize: '0.75rem', fontWeight: '700', padding: '0.2rem 0.55rem', borderRadius: '999px'
                });
                if (row.effectiveLocked) {
                    effBadge.style.background = 'rgba(245, 158, 11, 0.16)';
                    effBadge.style.color = '#fcd34d';
                    effBadge.textContent = '🔒 Locked';
                } else if (row.passed) {
                    effBadge.style.background = 'rgba(16, 185, 129, 0.18)';
                    effBadge.style.color = '#6ee7b7';
                    effBadge.textContent = '✓ Passed';
                } else {
                    effBadge.style.background = 'rgba(59, 130, 246, 0.16)';
                    effBadge.style.color = '#93c5fd';
                    effBadge.textContent = '🔓 Video Unlocked';
                }
                tdEff.appendChild(effBadge);

                const tdWatch = document.createElement('td');
                const watchBadge = document.createElement('span');
                Object.assign(watchBadge.style, {
                    fontSize: '0.74rem', fontWeight: '700', padding: '0.2rem 0.55rem', borderRadius: '999px'
                });
                const verifiedSecs = Number(row.verifiedWatchSeconds || 0);
                const requiredSecs = Number(row.requiredWatchSeconds || 0);
                const fmtClock = (secs) => `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`;
                if (row.passed) {
                    watchBadge.style.background = 'rgba(16, 185, 129, 0.18)';
                    watchBadge.style.color = '#6ee7b7';
                    watchBadge.textContent = '✓ Quiz Passed';
                } else if (row.quizUnlocked) {
                    watchBadge.style.background = 'rgba(16, 185, 129, 0.16)';
                    watchBadge.style.color = '#6ee7b7';
                    watchBadge.textContent = `✓ Quiz Unlocked (${fmtClock(verifiedSecs)} / ${fmtClock(requiredSecs)})`;
                } else {
                    watchBadge.style.background = 'rgba(148, 163, 184, 0.14)';
                    watchBadge.style.color = 'var(--text-muted)';
                    watchBadge.textContent = `⏳ ${fmtClock(verifiedSecs)} / ${fmtClock(requiredSecs)}`;
                }
                tdWatch.appendChild(watchBadge);

                const tdOv = document.createElement('td');
                const ovCode = document.createElement('code');
                ovCode.textContent = row.overrideState || 'AUTO';
                tdOv.appendChild(ovCode);

                const tdAct = document.createElement('td');
                tdAct.style.display = 'flex';
                tdAct.style.gap = '0.45rem';
                tdAct.style.flexWrap = 'wrap';

                const btnUnlockVideo = document.createElement('button');
                btnUnlockVideo.type = 'button';
                btnUnlockVideo.className = 'btn-ghost';
                btnUnlockVideo.style.padding = '0.28rem 0.6rem';
                btnUnlockVideo.style.fontSize = '0.76rem';
                btnUnlockVideo.textContent = '🔓 Unlock video access';
                btnUnlockVideo.addEventListener('click', () => runRowToolAction(targetId, row.episodeId, 'unlock-video'));

                const btnUnlockQuiz = document.createElement('button');
                btnUnlockQuiz.type = 'button';
                btnUnlockQuiz.className = 'btn-ghost';
                btnUnlockQuiz.style.padding = '0.28rem 0.6rem';
                btnUnlockQuiz.style.fontSize = '0.76rem';
                btnUnlockQuiz.textContent = row.quizUnlocked ? '✓ Quiz unlocked' : '🔓 Unlock quiz';
                btnUnlockQuiz.disabled = Boolean(row.passed || row.quizUnlocked);
                if (btnUnlockQuiz.disabled) btnUnlockQuiz.style.opacity = '0.6';
                btnUnlockQuiz.addEventListener('click', () => runRowToolAction(targetId, row.episodeId, 'unlock-quiz'));

                const btnLockAgain = document.createElement('button');
                btnLockAgain.type = 'button';
                btnLockAgain.className = 'btn-ghost';
                btnLockAgain.style.padding = '0.28rem 0.6rem';
                btnLockAgain.style.fontSize = '0.76rem';
                btnLockAgain.textContent = '🔒 Lock again';
                btnLockAgain.addEventListener('click', () => runRowToolAction(targetId, row.episodeId, 'lock-again'));

                tdAct.appendChild(btnUnlockVideo);
                tdAct.appendChild(btnUnlockQuiz);
                tdAct.appendChild(btnLockAgain);

                tr.appendChild(tdCourse);
                tr.appendChild(tdEp);
                tr.appendChild(tdEff);
                tr.appendChild(tdWatch);
                tr.appendChild(tdOv);
                tr.appendChild(tdAct);
                tbody.appendChild(tr);
            });
        } catch (err) {
            console.warn('Episode overrides load error:', err);
        }
    };

    async function runRowToolAction(studentId, episodeId, action) {
        hideFeedback('student-overrides-msg');
        try {
            let res;
            if (action === 'unlock-video') {
                res = await window.AdminApi.unlockEpisodeVideo(studentId, episodeId);
            } else if (action === 'unlock-quiz') {
                res = await window.AdminApi.unlockEpisodeQuiz(studentId, episodeId, 'Admin quiz unlock');
            } else {
                res = await window.AdminApi.lockEpisodeAgain(studentId, episodeId);
            }
            showFeedback('student-overrides-msg', `✓ ${res.message || 'Updated episode access.'}`);
            await window.loadStudentEpisodeOverrides(studentId);
            if (typeof window.refreshStudentOverviewCard === 'function') {
                await window.refreshStudentOverviewCard();
            }
        } catch (err) {
            showFeedback('student-overrides-msg', err.message, true);
        }
    }

    document.addEventListener('DOMContentLoaded', ensureBulkUnlockToolbar);
    ensureBulkUnlockToolbar();
})();
