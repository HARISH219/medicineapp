# Deploy the MedTrack backend on Railway

This is the small server that holds your Turso token and lets your devices sync. Follow the
steps in order. It takes ~15 minutes and runs on Railway's free/hobby tier.

> Before anything: **rotate your Turso token** if you ever pasted it anywhere.
> ```
> turso db tokens invalidate medicine
> turso db tokens create medicine
> ```
> Use the NEW token below. Never put it in the Android app or commit it to Git.

---

## Step 0 — Fill in your credentials
1. In the project root, copy `CREDENTIALS.template.txt` to `CREDENTIALS.txt`.
2. Fill in `TURSO_DATABASE_URL`, `TURSO_AUTH_TOKEN`, and `SESSION_SIGNING_SECRET`.
   (`CREDENTIALS.txt` is git-ignored — it won't be committed.)

## Step 1 — Create the Turso tables (one time)
On your computer, in the project root:
```bash
cd backend
npm install
# paste your values inline (PowerShell shown; for bash use VAR=... prefixes):
$env:TURSO_DATABASE_URL="libsql://medicine-harishbag211.aws-ap-south-1.turso.io"
$env:TURSO_AUTH_TOKEN="eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTAxMDkwNTAsImlkIjoiMDFhMGNhM2YtNjYwMS03Yzk2LTk5ZGQtYzYxYzU1NDhlZGI0Iiwia2lkIjoiNGd5RVFoS3pQQmhTaG5EYUo0TU43QlRyalM0Z3FWNk16RHJ3QS1PS3llbyIsInJpZCI6IjczNmUxNTIwLWUzOGMtNDM2Ni1iYzY5LTAxOWYxMGMxY2U4YSJ9.U09s8NmHr8Yece-rDwGRmK8mWFEh_htuNvaVjPS8-2qzHZoRuTXJqUdj_gLVBTwLv_fmiA-elG_mS5JM7JaLAA"
npm run migrate
```
You should see "Migrations complete."

## Step 2 — Create the Railway project
1. Go to https://railway.app and sign in with GitHub.
2. **New Project → Deploy from GitHub repo** → pick `HARISH219/medicineapp`.
   - If Railway asks for a root directory / service, set the **Root Directory** to `backend`.
   - (Nixpacks auto-detects Node and runs `npm install` then `npm start` via `railway.json`.)

## Step 3 — Add the environment variables in Railway
In your Railway service → **Variables** tab, add these (from your `CREDENTIALS.txt`):

| Variable | Value |
|---|---|
| `TURSO_DATABASE_URL` | your libsql:// URL |
| `TURSO_AUTH_TOKEN` | your NEW Turso token |
| `SESSION_SIGNING_SECRET` | your long random string |

Do **not** set `PORT` — Railway provides it automatically.
(Leave `GOOGLE_APPLICATION_CREDENTIALS` unset for now; that's only for FCM push.)

Click **Deploy** (or it redeploys automatically after saving variables).

## Step 4 — Get your public URL
1. Railway → your service → **Settings → Networking → Generate Domain**.
2. Copy the URL, e.g. `https://tbmedtrack-backend.up.railway.app`.
3. Test it in a browser: visiting `.../health` should show `{"ok":true}`.
4. Put that URL into `CREDENTIALS.txt` under `API_BASE_URL`.

## Step 5 — Turn on sync in the app (later)
Once the backend is live, tell me and I'll flip the app from the local-only client to the real
`RemoteSyncClient` and add the "backend URL + pairing" screen so you can enter the URL and pair
your father's phone. (This is a small, well-defined change — everything else is already in place.)

---

## Security reminders
- The Turso token lives ONLY in Railway's Variables. Never in the app, never in Git.
- `CREDENTIALS.txt`, `backend/.env`, keystores, and `google-services.json` are all git-ignored.
- If a token leaks, rotate it in Turso and update the Railway variable.
