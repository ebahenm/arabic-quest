# Turathtona (تراثُنا) — Arabic History Learning & Incentive Platform

> *"Know your history. Stand tall."*  
> **Live Domain**: https://turathtona.site

---

## Overview

**Turathtona (`تراثُنا`)** is a private family learning and incentive platform built on **Java 21** and **Spring Boot 3.5.16**. Students study multi-course Arabic history playlists, verify real video watch time on the server, pass **10-question comprehension quizzes (`100%`)**, submit a written reflection, and earn verified cash rewards tracked in an idempotent ledger.

---

## Learning, Assessment, & Reward Rules

1. **Multi-Course Catalog & Sequential Locking**
   - Students browse published courses in the **Course Catalog**, view per-course syllabi and progress (`LOCKED`, `AVAILABLE`, `WATCHING`, `QUIZ_READY`, `QUIZ_NOT_READY`, `PASSED`), and mark favourite episodes (`♡ / ♥`).
   - Within each course, Episode `N` unlocks sequentially after Episode `N - 1` is `PASSED` (independent across separate courses). Administrators can also apply per-student episode unlock/lock overrides.

2. **Language Modes & Configurable Per-Course Rewards**
   - **Original Arabic Audio + English Subtitles (`ARABIC_SUB`)**: Default **+$15.00** per passed episode (editable per course).
   - **English Dubbed Audio (`ENGLISH_DUB`)**: Default **+$10.00** per passed episode (editable per course).
   - **Course Completion Bonus**: Default **+$100.00** awarded once when all active episodes in the course are `PASSED` (editable per course).
   - Choosing a mode locks that episode's reward tier once playback starts. Before passing, a student may explicitly reset the episode watch timer to `0` to switch modes.

3. **30-Minute Server-Verified Watch Requirement**
   - Required watch time per episode is `min(app.watch.required-cap-seconds, episode.durationSeconds)` — **30 minutes (`1800s`)** or the full authoritative video length if shorter.
   - Episode durations come exclusively from YouTube Data API v3 imports or admin edits (client-supplied durations and external self-reported watch flags are ignored).
   - Every playback heartbeat is bounded by elapsed server wall-clock time so watch credit cannot accumulate faster than real time.

4. **10-Question Attempt Sessions (`100%` Pass Mark)**
   - Student requests never invoke AI generation. An episode is `QUIZ_READY` only when it has **at least 10 `APPROVED` active multiple-choice questions** and **at least 1 `APPROVED` active open question**.
   - Opening a ready quiz creates (or reuses on page refresh) a 60-minute `QuizAttemptSession` drawing **10 distinct `APPROVED` active MCQs**, prioritizing questions unseen by that student and then least-recently seen, with shuffled options.
   - Submissions are graded strictly against the open session's 10 questions (`sessionId` required; expired, reused, or foreign-question submissions are rejected).
   - Passing requires **10/10 (`100%`)**. Answer keys and transcript evidence are never returned by any student endpoint.
   - Failed attempts start a server-enforced retry cooldown (`app.quiz.retry-cooldown-seconds`, default 10 minutes). Students can report a flawed question from the failed-attempt screen; if an admin edits or retires that question, the reporting student's cooldown is cleared immediately.

5. **Pass Rule: Immediate Reward & Reading List**
   - Scoring `10/10` on the MCQ session unlocks **1 fixed `APPROVED` open question** assigned to that student and episode.
   - Submitting a valid written answer (at least **40 words in English**, maximum 2,000 characters; verified against spam, repeated words, and prompt copying) **immediately passes the episode and awards the reward** (`EpisodeRewardClaim`).
   - If this is the final active episode in the course, the course completion bonus (`PlaylistBonusClaim`) is also awarded automatically and idempotently.
   - Submissions are automatically placed in the administrator's **Written Reflections Reading List** with an unread badge. Administrators can inspect answers, mark them as read, and leave optional feedback notes for the student.

6. **Money Safety, Append-Only Payouts, & Balance Adjustments**
   - **Balance Calculation**: `totalEarned = episodeRewards + bonusRewards + balanceAdjustments`. `balanceOwed = totalEarned - totalPaid`.
   - **Overpaid UI**: If `balanceOwed < 0`, both student and admin interfaces clearly display `Overpaid by $X.XX` with prominent red styling.
   - **Voiding Payouts**: Payout records are append-only. Administrators can void mistakenly recorded payouts with an audit reason; voided payouts remain in the ledger for auditing but are immediately excluded from `totalPaid`.
   - **Balance Adjustments**: Administrators can post positive (+) or negative (-) adjustments with a mandatory reason to credit or debit student balances.
   - **Brother Reconciliation**: An idempotent startup task reconciles historical payouts with a +$30.00 adjustment for the brother account.

---

## Tech Stack & Security Architecture

- **Backend**: Java 21 LTS, Spring Boot `3.5.16`, Spring Security (stateless HS256 `HttpOnly; SameSite=Strict` JWT cookies + `tokenVersion` revocation), Spring Data JPA, Bean Validation.
- **Database**:
  - `dev`: In-memory H2 (`ddl-auto: update`).
  - `prod`: Neon Serverless PostgreSQL (`sslmode=require`, `ddl-auto: update` with safe non-destructive column defaults and startup backfill).
- **Frontend**: Vanilla HTML5, CSS3, and JavaScript (`src/main/resources/static` and `src/main/resources/private-hq`), self-hosted SIL OFL Arabic `.woff2` fonts (`Amiri`, `Cairo`), strict `textContent` / DOM rendering (`innerHTML` is never used).
- **External APIs**:
  - **YouTube Data API v3** (`X-Goog-Api-Key` request header only).
  - **Google Gemini (`gemini-2.5-flash`)** (`x-goog-api-key` request header only; structured JSON output).
- **Container**: Multi-stage `Dockerfile` running as non-root `appuser` on Eclipse Temurin 21 Alpine.

---

## Environment Variables & Configuration Properties

In `prod` (`SPRING_PROFILES_ACTIVE=prod`), `ProdStartupValidator` fails fast on startup with a clear error message if required variables are missing, too short, or left at development defaults.

| Variable / Property | Required in `prod` | Default (`dev`) | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | `dev` | Set to `prod` in production (`Dockerfile` sets `prod`). |
| `DATABASE_URL` | **Yes** | H2 in-memory | PostgreSQL URI (`postgresql://...` or `jdbc:postgresql://...`); logged only as `host/database` with credentials redacted. |
| `JWT_SECRET` | **Yes** | Dev-only key | Random HS256 signing secret (**>= 32 characters**; must differ from dev default). |
| `ADMIN_USERNAME` | **Yes** | `admin` | Administrator username synced on startup. |
| `ADMIN_PASSWORD` | **Yes** | Random logged | Administrator password (**>= 12 characters** in `prod`; BCrypt-hashed on startup). |
| `ADMIN_SECRET_PATH` | **Yes** | Dev-only slug | Private URL slug for the Admin Command Center (must not be blank or `turath-hq` in `prod`). |
| `GEMINI_API_KEY` | **Yes** | Empty | Google Gemini API key (`gemini-2.5-flash`) for video-grounded question bank generation. |
| `GEMINI_MODEL` | **Yes** | `gemini-3.5-flash` (override via env var, e.g. `gemini-2.5-flash`) | Gemini model name configured on the server (never hardcoded as the only option). |
| `YOUTUBE_API_KEY` | **Yes** | Empty | YouTube Data API v3 key for course playlist preview and import. |
| `GOOGLE_CLIENT_ID` | Optional | Empty | Google OAuth 2.0 Web Client ID (`...apps.googleusercontent.com`) for Sign in with Google on pre-created student accounts. |
| `ALLOWED_ORIGINS` | Optional | `https://turathtona.site,https://www.turathtona.site` | Comma-separated allowed CORS origins. |
| `WATCH_REQUIRED_CAP_SECONDS` (`app.watch.required-cap-seconds`) | Optional | `1800` | Maximum watch time required per episode in seconds (`min(cap, durationSeconds)`). |
| `QUIZ_RETRY_COOLDOWN_SECONDS` (`app.quiz.retry-cooldown-seconds`) | Optional | `600` | Cooldown in seconds after a failed MCQ attempt before a new session can be started. |
| `app.brand.name` | Optional | `Turathtona` | Centralized Latin brand name constant exposed via `/api/public/config`. |

---

## Admin Workflows

### 1. Adding, Publishing, & Re-Syncing a Course from YouTube
1. Sign in to your private Admin Command Center (`/<ADMIN_SECRET_PATH>-login`).
2. Open the **Courses & YouTube Import** section and paste a YouTube Playlist URL or Playlist ID (`PL...`).
3. Click **Preview YouTube Playlist** (`POST /api/admin/courses/youtube-preview`). The server fetches playlist metadata, paginates all items (`50` per page), skips private/deleted videos, and parses authoritative ISO-8601 video durations via YouTube Data API v3.
4. Review or customize the course title, description, cover image URL, `ARABIC_SUB` reward rate (default `$15.00`), `ENGLISH_DUB` reward rate (default `$10.00`), and completion bonus (default `$100.00`), then click **Import Course as Draft** (`POST /api/admin/courses/youtube-import`).
5. Imported courses start **unpublished (`published = false`)** so students cannot see or access them until question banks are ready.
6. After approving question banks for the episodes, click **Publish** (`POST /api/admin/courses/{id}/publish`) to make the course live in the Student Catalog.
7. At any time later, click **Re-sync YouTube** (`POST /api/admin/courses/{id}/resync`) to safely append newly added playlist videos, update changed titles/durations, and mark removed videos inactive (`enabled = false`) **without deleting existing episodes, quizzes, student progress, or earned completion bonuses**.

### 2. Video-Grounded Question Bank Generation & Jump-to-Moment
1. In the Admin Command Center, select an episode in the **Question Bank Manager**.
2. Pasting a transcript is **optional**. Click **Generate from the YouTube video** (`POST /api/admin/episodes/{episodeId}/generate-bank`):
   - Gemini analyzes the video directly using `file_data` with the YouTube URL and `MEDIA_RESOLUTION_LOW` (episodes longer than 35 minutes are split automatically into ~25-30 minute segments).
   - Every generated question requires a valid `evidenceTimestamp` (seconds from video start) and an `evidence` quote/note.
   - **Pass 1** produces candidate MCQs (~10+ per segment, up to ~30 total) and open explanation questions (~1+ per segment, up to ~3 total).
   - **Pass 2** strictly verifies candidates against the video segment, enforces a max of 2 vocabulary questions, and removes same-concept / repeated-term duplicates.
   - Surviving items are saved strictly as **`DRAFT`**; any existing **`APPROVED`** questions remain untouched.
   - Transient failures (429/503/timeouts) are retried automatically on the backend; any exhausted failure rolls back cleanly ("Nothing was saved") and renders a **Retry Generation** button in the Admin UI.
3. Every draft and approved question card renders a clickable **Jump to moment (`M:SS`)** link (`https://www.youtube.com/watch?v={videoId}&t={sec}s`) alongside its evidence note for fast timestamp verification.
4. Filter questions by status (`ALL`/`DRAFT`/`APPROVED`/`RETIRED`), type (`RECALL`/`REASONING`/`VOCABULARY`/`LISTENING`), or reported count; use **Bulk Approve All Drafts** (`POST /api/admin/episodes/{episodeId}/questions/bulk-approve`) once verified.
5. Each episode requires **>= 10 `APPROVED` active MCQs** and **>= 1 `APPROVED` active open question** to appear as **Quiz Ready** to students.
6. Use **Export Question Bank JSON** (`GET /api/admin/question-bank/export`) and **Import Question Bank JSON** (`POST /api/admin/question-bank/import`) for off-repo curriculum backups.

### 3. Written Reflections Reading List
1. Open the **Written Reflections Reading List** (`GET /api/admin/open-answers?status=UNREAD`). An unread badge displays the count of unread submissions.
2. Read the student's submission alongside the prompt and rubric evidence.
3. Click **Mark as Read** (`POST /api/admin/open-answers/{id}/read`) and optionally enter a feedback note for the student.

### 4. Student Account Management
1. **Enable / Disable Account**: Administrators can toggle account status (`PUT /api/admin/students/{id}/enabled`). Disabling an account immediately revokes all active JWT tokens via `tokenVersion` bump and blocks sign-in.
2. **Export History**: Export complete JSON history of a student (`GET /api/admin/students/{id}/export`), including attempts, claims, bonuses, payouts, and adjustments.
3. **Permanent Deletion**: Permanently delete a student account (`DELETE /api/admin/students/{id}`) inside the confirmation modal only when all guards pass: target is a non-admin account (never the acting admin), `balanceOwed == 0.00` (check `earnings.balanceOwed` ? if positive or overpaid negative, issue a payout or adjustment first), the student record JSON has been exported (`GET /api/admin/students/{id}/export`), and the exact username is typed for confirmation. Runs atomically in one transaction across all child tables and writes a clean `AUDIT [USER_DELETED]` entry.

### 5. Payouts Ledger & Balance Adjustments
1. **Issue Payout**: Record payouts (`POST /api/admin/payouts`) to deduct from balance owed.
2. **Void Payout**: Click **Void** on any unvoided payout (`POST /api/admin/payouts/{id}/void`) with a required audit reason. The payout is excluded from totals while remaining in the ledger for auditing.
3. **Post Balance Adjustment**: Credit (+) or debit (-) a student's balance (`POST /api/admin/students/{id}/adjustments`) with a mandatory explanation.

---

## Local Development & Testing

```bash
# Run unit & integration test suite (H2 in-memory, mocked external APIs)
mvn test

# Run locally on http://localhost:8080 (dev profile)
mvn spring-boot:run
```

- **Public Landing Page**: `http://localhost:8080/`
- **Student Sign-In**: `http://localhost:8080/login`

---

## Project Rules, CI & Render Deployment
- **Engineering Rules (`RULES.md` & `.agents/rules/turathtona.md` / `AGENTS.md`)**: Strict separation between frontend `pages/`, `components/`, and `data/` modules; backend `controller/`, `service/`, `repository/`, `model/`, and `config/`; files kept focused and under 300 lines; no `innerHTML`; server-side authority for rewards/balances/grading/watch time; unique `(HTTP method, path)` mappings.
- **CI (`.github/workflows/ci.yml`)**: Runs `mvn clean test` on Java 21 Temurin for every push and pull request (`ContextAndRouteMappingTest` verifies Spring startup and unique endpoint mappings alongside all phase integration tests).
- **Render Docker Deployment (`Dockerfile`)**: Multi-stage Maven + Eclipse Temurin 21 JRE image (`appuser` UID 10001 with `/bin/bash` shell and container-aware JVM memory flags). Recommend configuring Render Auto-Deploy **"After CI Checks Pass"** and setting all 8 required environment variables above before manual hard refresh (`Ctrl+Shift+R`).
