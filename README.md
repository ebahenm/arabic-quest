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

2. **Single Episode Reward & Course Completion Bonus**
   - **Episode Reward**: Default **+$15.00** per passed episode (single unified reward per course, editable in Course Settings).
   - **Course Completion Bonus**: Default **+$100.00** awarded once when all active episodes in the course are `PASSED` (editable per course).
   - Videos stream immediately upon selection without mode selection or mode locking.

3. **30-Minute Server-Verified Watch Requirement**
   - Required watch time per episode is `min(app.watch.required-cap-seconds, episode.durationSeconds)` — **30 minutes (`1800s`)** or the full authoritative video length if shorter.
   - Episode durations come exclusively from YouTube Data API v3 imports or admin edits (client-supplied durations and external self-reported watch flags are ignored).
   - Every playback heartbeat is bounded by elapsed server wall-clock time so watch credit cannot accumulate faster than real time.

4. **10-Question Attempt Sessions (`100%` Pass Mark), Cross-Model Pipeline v2, & Auto-Gen Guardrails**
   - When `AUTO_QUIZ_GENERATION_ENABLED=true` (default `true`) and a student starts watching an episode (or crosses ~25% watch progress) whose approved bank has fewer than 10 `APPROVED` MCQs or 1 `APPROVED` open question, the server triggers a non-blocking `@Async` background job (capped at **3 auto-generation attempts per episode per day**). Auto-generated items are auto-approved (`status = APPROVED`, `source = AUTO`) **only** when the transcript quality gate and all pipeline passes succeed; admins can review them via the **Needs Review (`AUTO`)** filter.
   - An episode is `QUIZ_READY` when it has **at least 10 `APPROVED` active multiple-choice questions** and **at least 1 `APPROVED` active open question**. If a student's unseen `APPROVED` MCQ count drops below 10 on attempt draw or failed submission, a deduplicated background top-up is triggered automatically.
   - Opening a ready quiz creates (or reuses on page refresh) a 60-minute `QuizAttemptSession` drawing **10 distinct `APPROVED` active MCQs** (at most **3 questions per 25-minute `chunkIndex`** so questions span the full episode), prioritizing questions unseen by that student and then least-recently seen, with shuffled options.
   - Submissions are graded strictly against the open session's 10 questions (`sessionId` required; expired, reused, or foreign-question submissions are rejected).
   - Passing requires **10/10 (`100%`)**. Answer keys and transcript evidence are never returned by any student endpoint.
   - Failed attempts start a server-enforced retry cooldown (`app.quiz.retry-cooldown-seconds`, default 10 minutes). Students can report a flawed question from the failed-attempt screen (`POST /api/quiz/{episodeId}/report`), which immediately retires the reported question (`RETIRED`, `active = false`), logs the report for admin review, and clears the student's retry cooldown so they can retry immediately with a fresh draw.

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
  - **Anthropic Claude (`claude-sonnet-5-5`)** (`x-api-key` request header only) and **Google Gemini (`gemini-3.8-flash`)** (`x-goog-api-key` request header only; `thinkingLevel` normalized strictly to `low`/`medium`/`high`) for cross-model writing, verification, and blind testing, with optional **OpenAI-compatible fallback** (`FALLBACK_AI_API_KEY`).
- **Container**: Multi-stage `Dockerfile` running as non-root `appuser` on Eclipse Temurin 21 Alpine.
- **DeepSeek Privacy / Data-Transfer Note**: DeepSeek is strictly optional (`DEEPSEEK_API_KEY`). **Only public episode transcript text is ever sent to DeepSeek** for quiz writing/verification/blind-testing. Student PII, accounts, balances, and student written answers are **never** sent to DeepSeek.

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
| `GEMINI_API_KEY` | **Yes** | Empty | Google Gemini API key (`x-goog-api-key` header only) for question bank generation, verification, and blind testing. |
| `GEMINI_MODEL` | **Yes** | `gemini-3.8-flash` | Gemini model name configured on the server (`thinkingLevel` normalized to `low`/`medium`/`high` — never `minimal`). |
| `ANTHROPIC_API_KEY` | Optional | Empty | Anthropic Claude API key (`x-api-key` header only) for cross-model Pass 1 writing / Pass 2 verification. |
| `CLAUDE_MODEL` | Optional | `claude-sonnet-5-5` | Anthropic Claude model name used by `ClaudeClient`. |
| `AUTO_QUIZ_GENERATION_ENABLED` | Optional | `false` (safe default) | Master toggle (`app.quiz.auto-generation.enabled`) for automatic background quiz generation on student watch/retry. Keep `false` in production until cost caps and strict auto-approval are verified. |
| `AUTO_APPROVE_SINGLE_MODEL` | Optional | `false` | When `false` (default), auto-generated questions require different writer and verifier models to auto-approve; single-model items are saved as `DRAFT` under Needs Review. |
| `AI_DAILY_CALL_CAP` | Optional | `150` | Maximum total AI model calls per UTC day across all providers (including retries); refuses new jobs and stops running jobs when reached. |
| `CLAUDE_DAILY_OUTPUT_TOKEN_CAP` | Optional | `150000` | Maximum Claude output tokens per UTC day tracked in `ai_usage_daily`. |
| `DEEPSEEK_API_KEY` | Optional | Empty | Optional DeepSeek API key (`Authorization: Bearer` header only; never logged). When blank, DeepSeek is disabled and nothing changes. |
| `DEEPSEEK_BASE_URL` | Optional | `https://api.deepseek.com` | Base URL for DeepSeek OpenAI-compatible `/v1/chat/completions` endpoint. |
| `DEEPSEEK_MODEL` | Optional | `deepseek-flash` | DeepSeek model name used by `DeepSeekClient`. |
| `DEEPSEEK_DAILY_CALL_CAP` | Optional | `150` | Maximum DeepSeek API calls per UTC day enforced by `AiUsageGuard`. |
| `DEEPSEEK_DAILY_OUTPUT_TOKEN_CAP` | Optional | `250000` | Maximum DeepSeek output tokens per UTC day tracked in `ai_usage_daily`. |
| `FALLBACK_AI_API_KEY` | Optional | Empty | Optional OpenAI-compatible API key used automatically for text-only generation if primary providers fail (`400`/`403`/`429`/`5xx`). |
| `FALLBACK_AI_BASE_URL` | Optional | `https://api.openai.com/v1` | Base URL for the optional OpenAI-compatible fallback provider (`/chat/completions`). |
| `FALLBACK_AI_MODEL` | Optional | `gpt-4.1` | Model identifier sent to the optional OpenAI-compatible fallback provider. |
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
4. Review or customize the course title, description, cover image URL, single **Episode Reward** (default `$15.00`), and completion bonus (default `$100.00`), then click **Import Course as Draft** (`POST /api/admin/courses/youtube-import`).
5. Imported courses start **unpublished (`published = false`)** so students cannot see or access them until question banks are ready.
6. After approving question banks for the episodes, click **Publish** (`POST /api/admin/courses/{id}/publish`) to make the course live in the Student Catalog.
7. At any time later, click **Re-sync YouTube** (`POST /api/admin/courses/{id}/resync`) to safely append newly added playlist videos, update changed titles/durations, and mark removed videos inactive (`enabled = false`) **without deleting existing episodes, quizzes, student progress, or earned completion bonuses**.

### 2. Subtitle Upload (`.srt` / `.vtt`), Quality Gate, & Cross-Model Pipeline v2
1. **Batch Subtitle Upload & Quality Gate**:
   - In **🎬 Courses** (or **🗂️ Question Bank**), upload `.srt` / `.vtt` subtitle files (`POST /api/admin/transcripts/upload` or `/upload-multipart`, max 2 MB/file, strict UTF-8 validation, rolling VTT duplicate cleanup, and `[Music]`/`[Applause]` stripping).
   - Preview matches by YouTube video ID or episode number (`confirmSave=false`), inspect the **Transcript Quality Gate** report (WPM vs duration, repeated lines, `[Music]` ratio, Arabic ratio `>= 50%`, duration coverage `>= 50%`), and click **Confirm & Save Matched Transcripts** (`confirmSave=true`) or **Use Anyway** (`POST /api/admin/episodes/{episodeId}/transcript/use-anyway`).
2. **Async Cross-Model Generation & Blind Test (`GenerationJob`)**:
   - Open **🗂️ Question Bank**, choose the **Primary Writer** (`Claude Writes → Gemini Verifies` or `Gemini Writes → Claude Verifies`), or test both API keys via **🔌 Test AI Keys** (`POST /api/admin/ai/test-connection`).
   - Clicking **⚡ Generate Async Job** (`POST /api/admin/episodes/{episodeId}/generation-jobs`) returns `202 Accepted` immediately (`409 Conflict` if a job is already running for that episode) and runs a 3-pass pipeline across 25-minute (`1500s`) chunks marked with `[mm:ss]` timestamps every ~45 seconds:
     - **Pass 1 (Writer)**: Generates candidate MCQs and open questions with timestamps validated inside the chunk and within 60s of a real `[mm:ss]` marker, plus option-length normalization so the correct answer is never the longest option.
     - **Pass 2 (Cross-Model Verifier)**: The second model verifies every candidate against the chunk transcript.
     - **Pass 3 (Blind Test Without Transcript)**: All MCQs in a chunk are tested in a single request **without the transcript** across up to 3 shuffled runs; any question answered right in >= 2 of 3 runs by general knowledge/eliminable distractors is dropped (if `< 20` MCQs survive across the episode, the least-guessable items are kept and `needsReview = true` is flagged).
   - Use **⚖️ Compare Models (Set A / B)** (`POST /api/admin/episodes/{episodeId}/generation-jobs/compare`) to compare both writer directions side-by-side with model identities hidden until you pick a winner.
3. **Review, Jump-to-Moment, & Backups**:
   - Every question card renders a clickable **Jump to moment (`M:SS`)** link (`https://www.youtube.com/watch?v={videoId}&t={sec}s`) alongside its evidence quote.
   - Filter by status (`ALL`/`DRAFT`/`APPROVED`/`RETIRED`/`NEEDS_REVIEW`), type, or reported count; use **Bulk Approve All Drafts** (`POST /api/admin/episodes/{episodeId}/questions/bulk-approve`) and **Export / Import Question Bank JSON** (`GET/POST /api/admin/question-bank/export|import`).

### 3. Written Reflections Reading List
1. Open the **Written Reflections Reading List** (`GET /api/admin/open-answers?status=UNREAD`). An unread badge displays the count of unread submissions.
2. Read the student's submission alongside the prompt and rubric evidence.
3. Click **Mark as Read** (`POST /api/admin/open-answers/{id}/read`) and optionally enter a feedback note for the student.

### 4. Student Account Management
1. **Enable / Disable Account**: Administrators can toggle account status (`PUT /api/admin/students/{id}/enabled`). Disabling an account immediately revokes all active JWT tokens via `tokenVersion` bump and blocks sign-in.
2. **Export History**: Export complete JSON history of a student (`GET /api/admin/students/{id}/export`), including attempts, claims, bonuses, payouts, and adjustments.
3. **Permanent Deletion**: Permanently delete a student account (`DELETE /api/admin/students/{id}`) inside the confirmation modal only when all guards pass: target is a non-admin account (never the acting admin), `balanceOwed == 0.00`, the student record JSON has been exported, and the exact username is typed for confirmation.
4. **Unlock Quiz Direct Override (Without Faking Watch Time)**: Next to any row in **Admin -> Students -> Per-Student Episode Access Overrides**, click **🔓 Unlock Quiz** (`POST /api/admin/students/{studentId}/episodes/{episodeId}/unlock-quiz` handled by `AdminStudentQuizUnlockController`) and confirm. This records `adminUnlockedAt`, `adminUnlockedBy`, and optional `adminUnlockReason`, unlocks the episode sequentially, and marks `quizUnlocked = true` **without modifying `verifiedWatchSeconds`** (actual watched seconds remain accurate).

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
- **Render Docker Deployment (`Dockerfile`)**: Multi-stage Maven + Eclipse Temurin 21 JRE image (`appuser` UID 10001 with `/bin/bash` shell and container-aware JVM memory flags).
