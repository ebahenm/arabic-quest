# ⚔️ Turathtona (تراثُنا) — Arabic Learning & Incentive Platform

> **Live Domain**: [turathtona.site](https://turathtona.site)  
> **Course Series**: [100 سؤال في التاريخ (One Hundred Questions in History)](https://youtube.com/playlist?list=PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB) by *Tareekhuna (محمد إلهامي)*.

---

## 🌟 Overview

**Turathtona** is an educational, gamified platform designed to motivate learners to master Arabic listening comprehension through immersion in authentic historical narratives. 

### 💰 Incentive & Reward System
* **Arabic Audio with English Subtitles**: **+\$15.00** per passed episode (Score $\ge 80\%$).
* **English Dub**: **+\$10.00** per passed episode (Score $\ge 80\%$).
* **Course Completion Bonus**: **+\$100.00** automatically awarded upon passing all episodes in the series.
* **Anti-Cheat Grading**: Grading is performed strictly on the server. The client never receives correct answer keys in advance. Rewards can only be claimed once per episode (retries are allowed, but duplicate payouts are blocked).

---

## 🏛️ Portal Separation

The application features two separate web portals with distinct authentication mechanisms:

1. **Student Portal** (`/login.html` ➔ `/index.html`)
   * Full immersion interface with embedded YouTube player.
   * Direct **"Watch on YouTube"** button allowing native browser extensions (like *YouTube Comment Translator*, *Dual Subtitles*, *Language Reactor*).
   * **AI Vocabulary & Translation Guide**: Key Arabic terms, transliterations, and meanings right on screen.
   * **AI-Generated Comprehension Quizzes**: Dynamically tests vocabulary and listening comprehension.
   * Celebration confetti on passing with immediate live earnings update.
   * **Sign-in Methods**:
     * Username & Password (seeded account: `brother` / `learn123`)
     * **Create Account** tab for self-registration.
     * **Sign in with Google** button.

2. **Secret Admin Command Center** (`/turath-hq-login` ➔ `/turath-hq`)
   * Hidden, unguessable private route (`/turath-hq`) exclusively for the administrator.
   * Protected with `ROLE_ADMIN` (seeded account: `admin` / `admin123`).
   * **Admin-Exclusive Student Creator**: Only the admin can register and issue logins for students (public self-registration disabled).
   * **Multi-Student Switcher**: View stats and balance owed for any student.
   * **One-Click Payout Actions**: Fast-pay buttons (`+$15`, `+$30`, `+$50`, `Full Balance`) to log cash/digital transfers.
   * **AI Quiz Generator Tool**: Select any episode and generate 3 multiple-choice questions with one click via Gemini 1.5.
   * **Episode Creator**: Add new episode numbers, titles, and YouTube video IDs.
   * **Attempts Audit Log**: Real-time table tracking all student attempts, scores, and timestamps.

---

## 🛠️ Tech Stack & Architecture

* **Backend**: Java 21 LTS, Spring Boot 3.3.4, Spring Security, Spring Data JPA.
* **Database**:
  * Local Dev: In-memory H2 database (zero configuration).
  * Production: Neon Serverless PostgreSQL with SSL.
* **Frontend**: HTML5, CSS3 (Glassmorphic dark theme), Vanilla JavaScript, YouTube IFrame API, Canvas Confetti.
* **AI Integration**: Google Gemini 1.5 Flash API for automatic quiz generation and vocabulary parsing (with intelligent semantic fallback).
* **Containerization**: Multi-stage `Dockerfile` running on Eclipse Temurin Alpine JRE.

---

## 🚀 Running Locally

1. Open this repository folder in **VS Code**.
2. Make sure the **Extension Pack for Java** is installed.
3. Open `src/main/java/com/arabicquest/ArabicQuestApplication.java`.
4. Click **Run** (or press `F5`).
5. Open your browser:
   * **Student Portal**: [http://localhost:8080/login](http://localhost:8080/login)
   * **Secret Admin Portal**: [http://localhost:8080/turath-hq-login](http://localhost:8080/turath-hq-login)

---

## 🌐 Deploying to Render with `turathtona.site`

### 1. Render Web Service Setup
* **Repository**: Connect your GitHub repository.
* **Runtime**: Docker (auto-detected from `Dockerfile`).
* **Instance Type**: Free.
* **Environment Variables**:
  * `DATABASE_URL`: `postgresql://neondb_owner:password@ep-...neon.tech/neondb?sslmode=require`
  * `GEMINI_API_KEY`: *(Optional)* Your free Google Gemini API key for live AI quiz generation.

### 2. Custom Domain (Namecheap DNS)
Under **Render Settings > Custom Domains**, add:
* `turathtona.site`
* `www.turathtona.site`

In **Namecheap > Advanced DNS**, add two Host Records:
1. `CNAME Record` | Host: `www` | Value: `your-app.onrender.com.` | TTL: Automatic
2. `ALIAS Record` (or `A Record`) | Host: `@` | Value: `216.24.57.1` (or Render apex address) | TTL: Automatic

---

## 🔐 Default Pre-Seeded Accounts

| Role | Username | Password | Access Portal |
|---|---|---|---|
| **Admin** | `admin` | `admin123` | `/turath-hq-login` |
| **Student** | `brother` | `learn123` | `/login` |
