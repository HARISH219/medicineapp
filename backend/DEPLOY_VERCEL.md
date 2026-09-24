# Deploy the TB MedTrack backend on Vercel (free Hobby plan)

Vercel runs the backend as a **serverless function** (`api/index.js`), which imports the same
Express app used for local/Railway. It's free for personal use and fine for this app: reminders
run on your phone; the backend is only for syncing to your father's phone.

> First: **rotate your Turso token** if you ever pasted it anywhere. Use the NEW token below.
> Never put the token in the app or commit it to Git.

---

## Step 0 — Create the tables (one time, if not done already)
From the project root:
```powershell
cd backend
npm install
$env:TURSO_DATABASE_URL="libsql://medicine-harishbag211.aws-ap-south-1.turso.io"
$env:TURSO_AUTH_TOKEN="your-new-token"
node migrate.js
```
Expect: "Migrations complete." (Already done if you ran it earlier.)

## Step 1 — Import the repo into Vercel
1. Go to https://vercel.com and sign in with GitHub.
2. **Add New… → Project** → import `HARISH219/medicineapp`.
3. **Root Directory**: click *Edit* and set it to **`backend`**.
   (This makes Vercel use `backend/vercel.json`, `backend/api/index.js`, and `backend/package.json`.)
4. Framework preset: **Other** (no framework). Leave build settings default.

## Step 2 — Add Environment Variables
In the import screen (or later under **Project → Settings → Environment Variables**), add:

| Name | Value |
|---|---|
| `TURSO_DATABASE_URL` | your libsql:// URL |
| `TURSO_AUTH_TOKEN` | your NEW Turso token |
| `SESSION_SIGNING_SECRET` | a long random string you invent |
| `STATS_KEY` | a secret key to open the stats dashboard at `/stats?key=…` (leave unset to keep the dashboard disabled) |

Apply them to the **Production** environment (and Preview if you like).

The site: visiting the base URL shows a small HTML landing page; the medication stats
dashboard is at `/stats` and requires `STATS_KEY` (data is never exposed without it).
Leave `GOOGLE_APPLICATION_CREDENTIALS` unset for now (that's only for FCM push).

## Step 3 — Deploy
Click **Deploy**. When it finishes, Vercel gives you a URL like:
```
https://medicineapp-xxxx.vercel.app
```

## Step 4 — Test it
Open in a browser:
```
https://medicineapp-xxxx.vercel.app/health
```
You should see `{"ok":true}`. If so, the backend is live.

Save that base URL — you'll enter it in the app to turn on sync.

---

## Notes
- **Cold starts:** the function sleeps when idle and wakes on the next request (~1–2s). Harmless
  here — your phone calls it on demand, and your own reminders don't depend on it.
- **FCM push (optional, later):** to push to the father's phone in real time, add a Firebase
  service account and set `GOOGLE_APPLICATION_CREDENTIALS`. On Vercel you'd instead paste the
  service-account JSON contents into an env var and adjust `app.js` to read it — tell me when you
  want push and I'll wire that variant.
- **Security:** the Turso token lives ONLY in Vercel's env vars. Never in the app, never in Git.
  `.env`, `CREDENTIALS.txt`, keystores, and `google-services.json` are git-ignored.
