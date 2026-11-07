# Turathtona (تراثُنا) — Arabic History Learning & Incentive Platform

> *"Know your history. Stand tall."*  
> **Live Domain**: https://turathtona.site

---

## Overview

**Turathtona (`تراثُنا`)** is a private family learning and incentive platform built on **Java 21** and **Spring Boot 3.5.16**. Students study multi-course Arabic history playlists, verify real video watch time on the server, pass **10-question comprehension quizzes (`100%`)**, write an open reflection reviewed by the administrator, and earn verified cash rewards tracked in an idempotent ledger.

---

## Learning, Assessment, & Reward Rules

1. **Multi-Course Catalog & Sequential Locking**
   - Students browse published courses in the **Course Catalog**, view per-course syllabi and progress (`LOCKED`, `AVAILABLE`, `WATCHING`, `QUIZ_READY`, `QUIZ_NOT_READY`, `PENDING_REVIEW`, `PASSED`), and mark favourite episodes (`♡ / ♥`).
   - Within each course, Episode `N` unlocks sequentially after Episode `N - 1` is `PASSED` (independent across separate courses). Administrators can also apply per-student episode unlock/lock overrides.

2. **Language Modes & Configurable Per-Course Rewards**
   - **Original Arabic Audio + English Subtitles (`ARABIC_SUB`)**: Default **+\$15.00** per approved episode (editable per course).
   - **English Dubbed Audio (`ENGLISH_DUB`)**: Default **+\$10.00** per approved episode (editable per course).
   - **Course Completion Bonus**: Default **+\$100.00** awarded once when all active episodes in the course are `PASSED` (editable per course).
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

5. **Written Reflection & Admin Approval Gate**
   - Scoring `10/10` on the MCQ session unlocks **1 fixed `APPROVED` open question** assigned to that student and episode (`40–2,000` characters in English).
   - Submitting the written answer places the episode in **`PENDING_REVIEW`** (tracked under **Pending Review**, separate from **Earned**).
   - **No money is earned until the administrator approves the written answer.** Approving creates the unique `EpisodeRewardClaim` (idempotent) and automatically awards the `PlaylistBonusClaim` once every active episode in the course is `PASSED`.
   - If the administrator requests a revision (`REVISION_REQUESTED`) with feedback, the student revises and resubmits the same prompt without retaking the MCQ quiz.

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
| `GEMINI_API_KEY` | Recommended | Empty | Google Gemini API key (`gemini-2.5-flash`) for admin 2-pass question bank generation and written-answer grade suggestions. |
| `YOUTUBE_API_KEY` | Recommended | Empty | YouTube Data API v3 key for admin playlist preview, course import, and re-sync. |
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
4. Review or customize the course title, description, cover image URL, `ARABIC_SUB` reward rate (default `\$15.00`), `ENGLISH_DUB` reward rate (default `\$10.00`), and completion bonus (default `\$100.00`), then click **Import Course as Draft** (`POST /api/admin/courses/youtube-import`).
5. Imported courses start **unpublished (`published = false`)** so students cannot see or access them until question banks are ready.
6. After approving question banks for the episodes, click **Publish** (`POST /api/admin/courses/{id}/publish`) to make the course live in the Student Catalog.
7. At any time later, click **Re-sync YouTube** (`POST /api/admin/courses/{id}/resync`) to safely append newly added playlist videos, update changed titles/durations, and mark removed videos inactive (`enabled = false`) **without deleting existing episodes, quizzes, student progress, or earned completion bonuses**.

### 2. Generating, Reviewing, & Backing Up a Question Bank
1. In the Admin Command Center, select an episode in the **Question Bank Manager**.
2. Paste the episode's Arabic or English transcript (`>= 80` characters) into the **Transcript** box and click **Generate Draft Bank (2-Pass Gemini)** (`POST /api/admin/episodes/{episodeId}/generate-bank`, rate-limited per episode):
   - **Pass 1** generates ~30 multiple-choice questions across `RECALL`, `REASONING`, `VOCABULARY`, and `LISTENING` (each with 4 options, 1 correct answer, and a verbatim transcript `evidence` quote) plus 3 open reflection prompts.
   - **Pass 2** verifies every generated item strictly against the transcript, rejecting unsupported, ambiguous, duplicate, or multi-answer items.
   - Surviving items are saved strictly as **`DRAFT`** — AI output is never auto-approved.
3. Filter the episode's bank by status (`DRAFT`, `APPROVED`, `RETIRED`), question type, or **Reported Only**. Inspect the transcript `evidence` quote, edit wording/options if needed, and click **Approve** individually or **Bulk Approve Drafts** once verified.
4. Ensure each episode has **>= 10 `APPROVED` active MCQs** and **>= 1 `APPROVED` active open question** (indicated by the green readiness badge in the Course Manager).
5. Use **Export Question Bank JSON** (`GET /api/admin/question-bank/export`) and **Import Question Bank JSON** (`POST /api/admin/question-bank/import`) to keep private off-repo backups of your curated curriculum (`*.private.json` is git-ignored).

### 3. Reviewing Student Written Reflections
1. Open the **Written Answer Review Queue** (`GET /api/admin/open-answers?status=PENDING`).
2. Read the student's reflection alongside the assigned open prompt and admin-only rubric/evidence. Optionally click **AI Suggest Grade** (`POST /api/admin/open-answers/{id}/suggest-grade`), which treats the student's text strictly as untrusted quoted data.
3. Click **Approve & Credit Reward** to finalize the episode (`PASSED`) and credit the locked-mode reward (plus the course completion bonus if it is the final active episode in the course), or click **Request Revision** with feedback so the student can improve and resubmit their answer.

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
