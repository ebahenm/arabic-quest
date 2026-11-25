# Mobile Watch Tracking Test Checklist

Manual QA checklist for verifying watch-time tracking, resume playback, and player resilience on mobile devices (especially Samsung Internet on Android and iOS Safari).

## Preconditions
- Test with a **regular student account** (`ROLE_STUDENT`, not beta/admin) and an **admin/beta account**.
- Use an episode with duration >= 30 minutes that is not yet passed.

## 1. Normal Playback & Role-Aware Progress Bar
- [ ] **Student view**: Play the episode for 15–30 seconds. Confirm the progress bar fills smoothly and the label shows only short states (`Watching`, `Paused`, `Quiz unlocked`) with **no numeric clock or percentage**.
- [ ] **Admin / Beta view**: Open the same episode on `/beta` or as admin. Confirm numeric clock (`mm:ss / mm:ss (pct%)`) is still displayed.
- [ ] **Seeking**: Drag the scrubber forward by 3+ minutes. Confirm progress bar does **not** jump ahead and seeking earns 0 watch credit.
- [ ] **2x speed**: Switch playback speed to 1.5x or 2x for 20 seconds. Confirm watch progress advances at the real played pace without being dropped as seeking.

## 2. Fullscreen & Orientation Rotation
- [ ] Enter **fullscreen** playback and play for 20+ seconds. Confirm watch progress continues advancing (or if the browser blocks fullscreen player state, a clear inline notice prompts to exit fullscreen).
- [ ] **Rotate** the device between portrait and landscape while playing. Confirm playback does not reset to 0:00 and accumulated watch seconds are preserved.

## 3. Lock Screen & Switching Apps
- [ ] Play for 15 seconds, then **lock the phone screen** for 30 seconds.
  - Confirm pending seconds before locking are flushed (`keepalive` / `sendBeacon`).
  - Confirm locked-screen time is **not** credited when unlocking.
  - Confirm a notice reminds the student to keep the screen unlocked and press Play.
- [ ] Play for 15 seconds, then **switch to another app** for 20 seconds and return.
  - Confirm pre-switch watch seconds are saved.
  - Confirm background time is **not** credited.

## 4. Refresh & Resume Playback
- [ ] Play to ~01:30, then **refresh the page** (or close and reopen the tab).
- [ ] Reopen the same episode. Confirm:
  - Previously credited watch progress is intact.
  - The YouTube player starts near the saved resume position (~01:30), not 0:00.

## 5. Slow / Flaky Network & Retry
- [ ] Throttle network to Slow 3G (or toggle Airplane Mode for 15 seconds while playing, then reconnect).
- [ ] Confirm failed heartbeat seconds are **retained** and retried once the connection returns (up to the 90s per-heartbeat server cap).
- [ ] Block `https://www.youtube.com/iframe_api` in DevTools (or test on a blocked/offline network) and open an episode:
  - Confirm the page does **not** navigate back to the course list.
  - Confirm an inline error box appears with **Retry** and **"Open on YouTube"** buttons.
  - Confirm a sanitized error entry is logged via `POST /api/student/client-errors` (message + browser only, no personal data).

## 6. Samsung Internet (Android) Specific Checks
- [ ] Open the portal in **Samsung Internet** on an Android device.
- [ ] Tap an episode from the syllabus list: confirm it opens the episode view cleanly without bouncing back to the course list.
- [ ] Play for 30+ seconds with screen on, rotate, pause, and refresh: confirm watch time persists and resumes from the last position.
- [ ] Confirm no JS syntax errors in remote DevTools console (`chrome://inspect`).
