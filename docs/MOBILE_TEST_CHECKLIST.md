# Turathtona (تراثُنا) — Mobile Responsiveness & Watch-Tracking Test Checklist

This checklist covers phone viewport testing (`360px`, `375px`, `390px`, `430px` portrait and landscape) and mobile episode watch-tracking edge cases.

---

## 1. Viewport & Overflow Matrix (`360px` – `430px` Portrait & Landscape)

For every page below, verify at `360×800`, `375×812`, `390×844`, `430×932`, and landscape (`800×360` / `844×390`):
- `document.documentElement.scrollWidth <= window.innerWidth` (zero horizontal overflow).
- All `<input>`, `<textarea>`, and `<select>` elements compute to `font-size: 16px` (preventing iOS Safari auto-zoom).
- All interactive buttons, tabs, episode rows, and quiz options have a tap height of `>= 44px`.
- Arabic RTL titles mixed with English (`dir="auto"`) wrap cleanly without clipping (`unicode-bidi: plaintext; overflow-wrap: anywhere`).

### Student Pages
- [ ] **Logged-Out Landing (`/`)**: Uses `100dvh` + `env(safe-area-inset-bottom)`; emblem, Arabic wordmark (`تراثُنا`), tagline, and Sign In button stack cleanly in both portrait and landscape.
- [ ] **Student Sign-In (`/login`)**: Auth card fits `360px` with `16px` inputs, `>= 44px` submit button, and responsive Google Sign-In button width.
- [ ] **Course Catalog (`/student`)**: `.course-catalog-grid` collapses to a single full-width column on phones (`minmax(min(100%, 310px), 1fr)`), overriding the base `330px` minimum column width in `style.css`.
- [ ] **Course Syllabus**: `.syllabus-hero-card` stacks into 1 column (overriding the base `280px 1fr` grid in `style.css`); episode rows wrap reward chips, status badges, and `44×44px` favourite heart buttons without clipping.
- [ ] **Episode Player Page**:
  - Header (`#app-header`) and 3-column earnings strip (`Earned / Paid / Owed`) stack cleanly without overlapping content.
  - 16:9 YouTube player (`.player-wrapper`) scales to `100%` container width.
  - MCQ Quiz options (`.option-pill`) span full width (`>= 48px` height), display `"Answered: X / 10"` (`#quiz-answered-badge`), and keep the sticky bottom Submit bar (`.quiz-submit-sticky-bar`) above the safe area (switching to static positioning when the soft keyboard opens).
  - Written reflection textarea (`#open-answer-textarea`) uses `16px` font and displays live word and character counts (`#open-answer-char-count`).

### Admin Pages
- [ ] **Admin Sign-In (`/<ADMIN_SECRET_PATH>-login`)**: Fits `360px–430px` with `16px` inputs and zero horizontal overflow.
- [ ] **Admin Command Center (`/<ADMIN_SECRET_PATH>`)**:
  - Header is non-overlapping (`position: relative`) on mobile with a 2-column action button grid.
  - 7-tab navigation bar (`.admin-tabs-nav`) scrolls horizontally with touch-friendly pills (`min-height: 44px`).
  - All `.form-row` grids (including `grid-column: span 2` fields) collapse to a single column (`grid-column: 1 / -1`).
  - Data tables sit inside `.table-wrap` (`overflow-x: auto`) and render as stacked cards with `data-label` headers under `768px`.
  - Modals (Delete Student, Void Payout / Balance Adjustments, Unlock Quiz) fit within `calc(100vw - 1.5rem)` × `calc(100dvh - 1.5rem)` and scroll internally.

---

## 2. Mobile Watch-Tracking & Player Edge Cases (`mobile-watch.js` + `app.js`)

### A. Fullscreen Playback
- [ ] Start episode playback inline and verify `#watch-status-label` advances (`▶️ Watching Live: MM:SS / 30:00`).
- [ ] Enter fullscreen (`fullscreenchange` / `webkitfullscreenchange`).
- [ ] Confirm the 1-second local playback monitor and 5-second server heartbeat continue running while fullscreen is active.
- [ ] If the mobile browser enters native fullscreen where `ytPlayer.getPlayerState()` or `ytPlayer.getCurrentTime()` cannot be read or stalls for 3+ checks, verify `#mobile-watch-notice` becomes visible with a clear warning instructing the student to exit fullscreen and play inline rather than silently losing watch time.

### B. Rotate Device (Portrait ↔ Landscape)
- [ ] While an episode is playing, rotate the phone from portrait (`390×844`) to landscape (`844×390`) and back.
- [ ] Verify the YouTube player is **not** destroyed or reloaded on `orientationchange`/`resize`, `lastPlayerTime` stays synchronized, and zero horizontal overflow occurs in either orientation.

### C. Lock Screen
- [ ] While an episode is playing, lock the phone screen (`document.hidden === true` / `visibilitychange`).
- [ ] Confirm pending watched seconds are flushed immediately, the player is paused (`pausePlayerSafely()`), and **0 seconds** are credited while the screen is locked.
- [ ] Unlock the phone screen and verify `#mobile-watch-notice` displays:
  `"📱 Playback was paused when this tab was hidden or your screen locked. Keep this tab open and press Play to continue earning watch time."`

### D. Switch Apps / Background Tab
- [ ] While an episode is playing, switch to another app or browser tab for 15+ seconds, then return.
- [ ] Verify `serverVerifiedSeconds + pendingWatchedDeltaSeconds` did not advance during the background interval and `#mobile-watch-notice` reminds the student to keep the tab open.

### E. Slow Network / Intermittent Cellular Connection
- [ ] Simulate Slow 3G / high latency or brief offline state in DevTools while watching.
- [ ] Confirm that if `/api/student/episodes/{id}/heartbeat` fails or times out, `flushWatchHeartbeat` restores `deltaToSend` back into `pendingWatchedDeltaSeconds` so no earned watch time is lost, and the next successful heartbeat commits the accumulated delta.
