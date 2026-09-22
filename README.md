# TB MedTrack

**Your personal medication reminder.** A private, offline-first Android app to remind you about
and record the TB medicines *you* enter, according to schedules *you* set.

> TB MedTrack is a personal medication reminder and tracking tool. It does **not** provide medical
> advice, does not decide which medicines or doses are correct, and does not replace your healthcare
> professional's instructions. Enter and follow your prescribed medication schedule.

---

## What it does

- Home dashboard: greeting, today's date, today's progress, next dose card, quick actions
- Add / edit / pause / resume / delete medicines with multiple reminder times and flexible frequency
- Group multiple medicines that fall at the same time into one dose event ("Mark all as taken")
- Real Android reminder notifications with **Mark as Taken** and **Snooze** actions — work when the
  app is closed, the screen is locked, or the device is idle
- Snooze (5 / 10 / 15 / 30 min), recorded in history
- Monthly calendar with per-day adherence indicators (icons + colour, never colour alone)
- Daily and per-medicine history generated only from recorded events (never fabricated)
- Statistics: adherence %, taken / missed / late, current & best streak, totals, average delay
- Treatment progress ("Day N") and a "My TB Treatment" regimen overview
- Light / Dark / System theme, week starts Monday or Sunday
- JSON backup export & import (replace-on-import with confirmation)
- Works fully offline — no account, no cloud, no server, no analytics. All data stays on the device.
- Reboot / time-change safe: future reminders are re-registered after a reboot, app update, or
  system time / timezone change.

## Architecture

```
UI (Jetpack Compose, Material 3)
   -> ViewModel (StateFlow)
      -> Repository (MedRepository, SettingsRepository, BackupManager)
         -> Room database + DataStore + AlarmManager/Notifications
```

Key packages under `app/src/main/java/com/tbmedtrack/app`:

- `data/db` — Room entities (`Medicine`, `DoseSchedule`, `MedicationLog`), DAOs, `AppDatabase`
- `data/settings` — DataStore-backed `SettingsRepository`
- `data` — `MedRepository` (dose generation, logging, statistics), `BackupManager`
- `reminder` — `AlarmScheduler`, `NotificationHelper`, `AlarmReceiver`, `NotificationActionReceiver`, `BootReceiver`
- `ui` — theme, navigation, and one package per screen
- `util` — `ScheduleUtil` (deterministic, timezone-aware schedule math)

## Requirements

- **Android Studio** (Ladybug 2024.2.1 or newer recommended). Android Studio ships its own JDK (17)
  and Gradle, so you do not need to install these separately.
- Android SDK Platform 35 (installed via Android Studio's SDK Manager)
- A phone running **Android 8.0 (API 26) or newer**. Optimised for Android 12–15+.

> Note on JDK: the Android Gradle Plugin used here (8.5.2) targets JDK 17. If you build from the
> command line, use a JDK 17 (or 21) — set `JAVA_HOME` accordingly. Android Studio's bundled JDK
> already satisfies this.

## Open & build in Android Studio

1. **Open the project**
   - Android Studio -> *File -> Open* -> select this project folder (the one containing
     `settings.gradle.kts`) -> *Open*.
2. **Sync Gradle**
   - Android Studio will prompt "Gradle sync". Click it (or *File -> Sync Project with Gradle Files*).
   - If prompted, let it download the Gradle 8.9 wrapper distribution and any missing SDK packages.
   - If it reports a missing `gradle-wrapper.jar`, run *File -> Sync* or, from a terminal with a
     system Gradle installed, run `gradle wrapper --gradle-version 8.9` once; Android Studio also
     regenerates it automatically on first sync.
3. **Run on a physical phone**
   - Enable *Developer options -> USB debugging* on the phone and connect it by USB.
   - Select the device in the toolbar, then press **Run** (green triangle).
   - Grant the notification permission when asked. On Android 12+, if exact reminders are needed,
     allow "Alarms & reminders" for the app in system settings.

## Build a debug APK

From Android Studio: *Build -> Build Bundle(s) / APK(s) -> Build APK(s)*.

Or from a terminal in the project root:

```bash
# macOS/Linux
./gradlew assembleDebug
# Windows (PowerShell)
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Build a release APK

The release build type is unsigned by default. To produce a signed, installable release APK:

1. Create a keystore (once):
   ```bash
   keytool -genkey -v -keystore tbmedtrack.keystore -alias tbmedtrack \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. In Android Studio: *Build -> Generate Signed Bundle / APK -> APK*, choose the keystore, select
   the **release** variant, and finish.

Or configure signing in `app/build.gradle.kts` and run:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Install the APK on a phone

- **Via Android Studio:** just press Run.
- **Via ADB:**
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Manually:** copy the `.apk` to the phone, open it with a file manager, and allow "install from
  unknown sources" for that app when prompted.

## Notes on reminders & permissions

- **Notifications (Android 13+):** the app requests `POST_NOTIFICATIONS` after onboarding. If you
  deny it, reminders will still be scheduled but no notification will appear until you enable
  notifications for TB MedTrack in system settings.
- **Exact alarms (Android 12+):** the app uses exact alarms for on-time reminders and requests the
  `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` capability. If exact alarms are unavailable, it falls
  back to inexact-but-reliable `setAndAllowWhileIdle` scheduling so reminders still fire.
- **Reboot:** `RECEIVE_BOOT_COMPLETED` lets the app re-register future reminders after a restart.

## Privacy

All medication data is stored locally in a Room database and DataStore on the device. Nothing is
transmitted anywhere by default. Use **Settings -> Export backup** to save a JSON copy yourself, and
**Import backup** to restore it (for example after switching phones).

---

## MDR-TB adherence features

This build is tailored for a personal MDR-TB regimen while keeping every value editable:

- **Seeded regimen (editable):** On first launch it creates Linezolid 600 mg, Moxifloxacin 400 mg,
  Pretomanid 200 mg at 10:00 AM daily; Bedaquiline 100 mg with the rule *every day for the first 14
  days (from 16 Sep 2026), then Monday/Wednesday/Friday*; and Pyridoxine/Vitamin B6 at a configurable
  bedtime. You can edit or delete any of these — the app follows only the schedule you keep.
- **Today's combination:** The home screen shows how many medicines are required today (4 or 3) and
  lists them, computed automatically from the schedule.
- **Escalating critical reminder:** At the dose time you get a reminder; if you have not pressed
  **MEDICINE TAKEN**, it escalates to an hourly full-screen red alarm until you record the dose.
  Dismissing a notification never marks the dose as taken — only the button does.
- **Full-screen alert:** A red alert can appear over the lock screen. The pulse is a slow fade (well
  below seizure-risk flash rates) and can be turned off entirely via **Settings → Reduce motion**.
- **Offline & reboot-safe:** All reminders/alarms are local and are re-registered after reboot, app
  update, and time/timezone changes. A periodic WorkManager job re-arms alarms as a safety net.
- **Devices & monitoring:** Device roles (Primary / Monitor), a device list with revoke, and a
  read-only monitor dashboard are built in. Real multi-device sync and push to a family member's
  phone require a small backend — see `docs/BACKEND.md`.

### Reliability caveat (please read)

For a life-important MDR-TB regimen, do not rely on any phone app as your only safeguard. Android
and, especially, manufacturer battery managers (Xiaomi/MIUI, Samsung, Oppo/ColorOS, Huawei, etc.)
can delay or kill background alarms. The app requests exact-alarm and notification permissions and
offers a battery-optimization shortcut in Settings, and it uses the strongest reliably-available
mechanisms — but no app can guarantee it will override every device's silent/DND/battery policy.
Keep a simple independent backup reminder as well.

### Cloud sync / father's phone

The app is offline-first and needs no account. Multi-device sync and monitor push are optional and
require deploying the backend described in `docs/BACKEND.md` (it holds your Turso token securely —
the token is deliberately never shipped in the APK). Until then, the app is fully functional on a
single device, and the Devices screen explains this.

---

# Multi-device sync, Turso & backend

TB MedTrack is **local-first**: Room is the operational database and the app is fully functional
offline (reminders, escalating alarms, mark/revert, history, calendar, statistics). Cloud sync and
family monitoring are an **optional layer** that activates once you deploy the backend.

## Architecture

```
Android app
   -> Repository (MedRepository)
      -> Room / SQLite            (immediate local operations)
      -> Sync engine (SyncManager + sync_operations queue)
         -> HTTPS -> Backend (holds Turso token) -> Turso
                                   -> FCM push -> Monitor (father's) phone
```

- Every cloud-relevant change writes to Room first, records an **audit** row, and enqueues a
  **sync operation** (`sync_operations`). The sync engine drains the queue when online.
- Operations are **idempotent**: each event carries a stable **UUID** and the backend upserts by
  uuid, so retries/restarts/background syncs never duplicate data.
- Merge rule is **updatedAt-primary with TAKEN as tiebreaker**, so a stale remote `TAKEN` can never
  overwrite a fresh local **revert** (`TAKEN -> REVERTED -> PENDING`), and monitor devices cannot
  recreate an old state.

## Accidental "Medicine Taken" correction

- A short tap records the dose; a brief **Undo** snackbar appears.
- After that, **long-press a completed card** to open a confirmation dialog and revert the dose to
  **NOT RECORDED**. The original event is **never deleted** — the revert is recorded in the audit
  trail and the live status returns to PENDING, re-arming reminders if the window is still open.

## Turso setup

1. Create a database (Turso CLI or dashboard):
   ```bash
   turso db create medicine
   turso db show medicine            # copy the libsql:// URL
   turso db tokens create medicine   # copy the auth token (server-side only!)
   ```
2. Put the URL + token in the **backend** environment (never in the app). If you ever paste a token
   anywhere public, rotate it: `turso db tokens invalidate medicine` then create a new one.

## Database migrations

Versioned SQL lives in `migrations/` (`001_initial_schema.sql` … `005_audit_log.sql`). Apply them in
order against your Turso DB:

```bash
cd backend
npm install
TURSO_DATABASE_URL=... TURSO_AUTH_TOKEN=... npm run migrate
```

## Backend setup (Node/Express + FCM)

```bash
cd backend
cp .env.example .env          # fill in TURSO_* and (optionally) FCM
npm install
npm run migrate               # create tables
npm start                     # serves the sync API
```

Deploy `backend/` to any HTTPS host (Render/Railway/Fly/Cloudflare). Set the same env vars there.
The **FCM server key / service account stays on the backend** — never in the app.

## Environment variables

See `.env.example` (root) and `backend/.env.example`:

| Variable | Where | Purpose |
|---|---|---|
| `TURSO_DATABASE_URL` | backend | Turso libsql URL |
| `TURSO_AUTH_TOKEN` | backend | Turso token (secret, server-only) |
| `SESSION_SIGNING_SECRET` | backend | signs device session tokens |
| `GOOGLE_APPLICATION_CREDENTIALS` | backend | Firebase service account for FCM (optional) |
| `API_BASE_URL` | app pairing | the backend HTTPS URL you enter in the app |

## Activating sync in the app

The app ships with `NoopSyncClient` (local-only). To go live:

1. Deploy the backend and note its HTTPS URL.
2. In the app, store the URL + a device session token via `SecureStore`
   (`EncryptedSharedPreferences`), obtained by pairing.
3. Set `ServiceLocator.syncManager(ctx).client = RemoteSyncClient(SecureStore(ctx))`.
4. (Optional) Add Firebase: drop `google-services.json` into `app/`, add the messaging service,
   and register the FCM token via `POST /v1/devices/fcm-token`.

`RemoteSyncClient` and the outbox/queue are already implemented — no other app changes needed.

## Device authorization

- Primary device: **Settings → Devices → Generate authorization code** (6-digit, 5-min TTL).
- New device: install, enter the code, **Connect** → joins as **MONITOR** and downloads data.
- Revoke anytime from the Devices list; a revoked device loses its token and stops receiving updates.

## Notification & alarm permissions

- Android 13+: the app requests `POST_NOTIFICATIONS`.
- Android 12+: exact alarms need "Alarms & reminders" — Settings has a shortcut. Falls back to
  inexact-but-reliable scheduling if unavailable.
- Battery optimization: Settings links to the exemption screen. Manufacturer battery managers can
  still delay alarms; keep an independent backup reminder for a life-important regimen.

## GitHub development workflow

Secrets are excluded via `.gitignore` (`.env`, `*.keystore`, `*.jks`, `google-services.json`,
`firebase-service-account.json`, `local.properties`). Only `.env.example` files are committed.

```bash
git init
git add .
git commit -m "Initial TB MedTrack application"
git branch -M main
git remote add origin https://github.com/HARISH219/medicineapp.git
# if the remote already exists:  git remote set-url origin https://github.com/HARISH219/medicineapp.git
git push -u origin main
```

Suggested branches: `main` (stable), `develop`, `feature/*`. Example:
`git checkout -b feature/medication-reminders`, then merge into `main` after testing.

> Security: before pushing, confirm no real `TURSO_AUTH_TOKEN`, keystore, or `google-services.json`
> is staged (`git status`). The `.gitignore` already blocks them.
