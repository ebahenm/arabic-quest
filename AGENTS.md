# Rules for Turathtona

Keep the app readable for a beginner.

Structure
- Frontend (src/main/resources/static and private-hq): screens in pages/, shared pieces in components/, all loading and saving (API calls) in data/.
- Backend (com.arabicquest): controller/ handles web requests only, service/ holds the logic, repository/ talks to the database, model/ holds entities, config/ holds setup.
- One job per file. Start each file with a one-line comment saying what it does. Use plain, descriptive names.
- New or changed files stay under 300 lines. Put NEW code in NEW files instead of growing big old ones. Do not refactor files you are not otherwise changing.

Working
- Before writing anything new, search the project and reuse what exists.
- Make the smallest change that does the job. Do not rewrite or reorganize unrelated code.
- Never delete or rewrite existing data (students, rewards, payouts, questions) unless the task says so.
- Run the tests after every change. Add tests for anything about money, login or grading.
- Every HTTP method + path is mapped exactly once.

Safety (money and login)
- Rewards, payouts, balances, grading and watch time are decided only on the server. Never trust values sent from the browser.
- Never put secrets, passwords or default credentials in code. Use environment variables.
- Show outside text (titles, answers) with textContent, never innerHTML.
