# ⚔️ Turathtona (تراثُنا) — Arabic Learning & Incentive Platform

> **Live Domain**: https://turathtona.site  
> **Course Series**: [100 سؤال في التاريخ (One Hundred Questions in History)](https://youtube.com/playlist?list=PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB) by *Tareekhuna (محمد إلهامي)*.

---

## 🌟 Overview

**Turathtona** is an educational, incentive-driven platform designed to motivate learners to master Arabic listening comprehension through authentic historical narratives.

---

## 🏛️ Portal Architecture

1. **Student Portal** (`/login` ➔ `/`)
   * Responsive desktop & mobile interface with embedded YouTube player and **"Open on YouTube"** extension support (*Language Reactor*, *Dual Subtitles*, *YouTube Comment Translator*).
   * Episode-specific **Arabic Vocabulary & Translation Guide** (Arabic script, transliteration, English meaning).
   * Randomized comprehension quizzes graded strictly on the server (answer keys are never sent to the client).
   * Supports assigned username/password login and optional **Sign in with Google** (restricted exclusively to pre-created student accounts; public self-registration is disabled).

2. **Private Admin Command Center** (configured via `ADMIN_SECRET_PATH`)
   * Served exclusively at your private `ADMIN_SECRET_PATH` route (direct static access to admin files is disabled).
   * Protected by `ROLE_ADMIN`, `HttpOnly; SameSite=Strict` signed JWT cookies, CSRF custom-header enforcement, and brute-force login rate limiting.
   * Create student accounts, reset student passwords, rotate the master admin password, record payouts, audit quiz attempts, manage course episodes, and generate episode quizzes & vocabulary via **Google Gemini 2.5 Flash**.

---

## 🛠️ Tech Stack

* **Backend**: Java 21 LTS, Spring Boot 3.3.4, Spring Security (stateless HS256 JWT cookies), Spring Data JPA, Bean Validation.
* **Database**:
  * `dev`: In-memory H2 database.
  * `prod`: Neon Serverless PostgreSQL (`sslmode=require`).
* **Frontend**: Responsive HTML5, CSS3, XSS-safe Vanilla JavaScript (`textContent` / DOM APIs), YouTube IFrame Player API.
* **AI Integration**: Google Gemini (`gemini-2.5-flash`) with structured JSON schema output (`x-goog-api-key` header).
* **Deployment**: Multi-stage `Dockerfile` on Eclipse Temurin 21 Alpine.

---

## ⚙️ Environment Variables

| Variable | Required in Prod | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Yes | Set to `prod` on Render (`Dockerfile` sets this automatically). |
| `DATABASE_URL` | Yes | PostgreSQL connection URI (e.g. `postgresql://user:pass@ep-...neon.tech/neondb?sslmode=require`). |
| `JWT_SECRET` | Recommended | Strong random secret (32+ bytes) used to sign `HttpOnly` session JWTs across restarts. |
| `ADMIN_USERNAME` | Recommended | Master administrator username synced on startup (defaults to `admin`). |
| `ADMIN_PASSWORD` | Recommended | Master administrator password synced on startup. If omitted on an empty DB, a random one-time password is generated in startup logs. |
| `ADMIN_SECRET_PATH` | Recommended | Private URL slug for the Admin Command Center. |
| `GOOGLE_CLIENT_ID` | Optional | Google OAuth 2.0 Web Client ID (`...apps.googleusercontent.com`) to enable Sign in with Google for pre-registered student emails. |
| `GEMINI_API_KEY` | Optional | Google Gemini API key for live AI quiz and vocabulary generation (`gemini-2.5-flash`). |
| `ALLOWED_ORIGINS` | Optional | Comma-separated CORS origins (defaults to `https://turathtona.site,https://www.turathtona.site`). |

---

## 🚀 Running Locally

```bash
mvn spring-boot:run
```

* **Student Portal**: http://localhost:8080/login
* If `ADMIN_PASSWORD` is not set in your local environment and the database is empty, check the console startup log for the generated bootstrap admin password.
