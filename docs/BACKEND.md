# TB MedTrack — Sync Backend & Turso + FCM Integration

The Android app works **fully offline on its own**: reminders, escalating critical alarms,
recording doses, history, calendar, statistics, and reboot recovery all run locally with no
network. Multi-device sync and family monitoring are **optional** and require a small backend
you deploy.

## Why a backend is required (and the token is not in the app)

Turso (libSQL) is reached with a database URL + auth token. That token grants full read/write
to your medication data. **It must never be shipped inside the APK** (not in Kotlin, resources,
`strings.xml`, or `BuildConfig`) because an APK can be trivially decompiled. The requirement is
explicit about this.

So the token lives only on a server you control. The app talks to that server over HTTPS with a
per-device session token; the server talks to Turso. The server is also the only practical way to:

- issue and redeem device authorization codes,
- revoke a device,
- fan out push notifications to a monitor (father's) device via FCM.

```
Primary phone ──HTTPS──▶  Your backend  ──libSQL──▶  Turso
Monitor phone ◀──FCM push──┘  (holds Turso token + FCM server key)
```

## Where the app plugs in

All cloud access goes through one interface:

- `com.tbmedtrack.app.sync.SyncClient` — the contract.
- `NoopSyncClient` — the default; everything stays local, sync calls are no-ops. The app ships
  with this so it compiles and runs with no backend.
- To go live, implement `RemoteSyncClient : SyncClient` that calls your backend, and set it in
  `SyncManager.client` (e.g. in `TbMedApplication.onCreate` once the user has signed in / paired).

The app never needs code changes elsewhere: events are already written to Room first, stamped
with a UUID + device id, queued in an outbox (`syncState = PENDING`), and drained by `SyncManager`.

## Backend HTTP contract (suggested)

All endpoints require `Authorization: Bearer <deviceSessionToken>` except pairing bootstrap.

| Method | Path | Purpose |
|-------|------|---------|
| POST | `/v1/devices/auth-code` | Primary device: create a 6-digit code (TTL 5 min). Returns `{ code, expiresAt }`. |
| POST | `/v1/devices/redeem` | New device: `{ code, deviceName }` → issues a device session token + role `MONITOR`. |
| POST | `/v1/devices/revoke` | Primary device: `{ deviceId }` → invalidates that device's token. |
| GET  | `/v1/events?since=<millis>` | Pull events updated since a watermark. |
| POST | `/v1/events` | Push a batch of events (upsert by `uuid`). Returns accepted uuids. |
| POST | `/v1/devices/fcm-token` | Register this device's FCM token for push. |

### Event JSON (matches `MedicationLog`)

```json
{
  "uuid": "…",
  "deviceId": "…",
  "medicineName": "Linezolid",
  "doseText": "600 mg",
  "scheduledDateTime": 1789200000000,
  "scheduledEpochDay": 20713,
  "actualTakenDateTime": 1789204020000,
  "status": "TAKEN",
  "createdAt": 0, "updatedAt": 0
}
```

### Conflict / merge rule (must match the app)

Events are append-oriented. On merge, a **TAKEN** event is authoritative and must never be
downgraded; otherwise the newer `updatedAt` wins. The app applies the same rule in
`SyncManager.mergeRemote`, so the primary device's explicit "Medicine Taken" is always preserved.

## Turso schema (server-side)

```sql
CREATE TABLE users (
  id TEXT PRIMARY KEY, created_at INTEGER
);
CREATE TABLE devices (
  device_id TEXT PRIMARY KEY, user_id TEXT, name TEXT, role TEXT,
  fcm_token TEXT, revoked INTEGER DEFAULT 0,
  last_active_at INTEGER, created_at INTEGER
);
CREATE TABLE medicines (
  uuid TEXT PRIMARY KEY, user_id TEXT, name TEXT, dose TEXT, unit TEXT,
  schedule_rule TEXT, start_date INTEGER, end_date INTEGER,
  updated_at INTEGER, deleted_at INTEGER
);
CREATE TABLE dose_schedules (
  uuid TEXT PRIMARY KEY, medicine_uuid TEXT, time_minutes INTEGER,
  frequency TEXT, days_of_week TEXT, interval_days INTEGER, updated_at INTEGER
);
CREATE TABLE medication_events (
  uuid TEXT PRIMARY KEY, user_id TEXT, device_id TEXT,
  medicine_name TEXT, dose_text TEXT,
  scheduled_at INTEGER, scheduled_epoch_day INTEGER, taken_at INTEGER,
  status TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER
);
CREATE TABLE auth_codes (
  code TEXT PRIMARY KEY, user_id TEXT, expires_at INTEGER, used INTEGER DEFAULT 0
);
```

## Minimal serverless example (Node, pseudocode)

```js
// Deploy on any HTTPS host (Vercel/Cloudflare Workers/Fly/etc.). Secrets via env vars only.
import { createClient } from "@libsql/client";
const turso = createClient({ url: process.env.TURSO_URL, authToken: process.env.TURSO_TOKEN });

// POST /v1/events  — upsert with TAKEN-authoritative merge
app.post("/v1/events", auth, async (req, res) => {
  for (const e of req.body.events) {
    const cur = await turso.execute({ sql: "SELECT status, updated_at FROM medication_events WHERE uuid=?", args: [e.uuid] });
    const keep = shouldKeepIncoming(cur.rows[0], e); // TAKEN wins, else newer updated_at
    if (keep) await turso.execute({ sql: "INSERT OR REPLACE INTO medication_events (...) VALUES (...)", args: [...] });
    if (e.status !== "TAKEN") await pushToMonitors(e.user_id, "not_recorded", e); // FCM
    else await pushToMonitors(e.user_id, "taken", e);
  }
  res.json({ accepted: req.body.events.map(e => e.uuid) });
});
```

## FCM push to the monitor device

1. Create a Firebase project, add the Android app (`com.tbmedtrack.app`), download
   `google-services.json` into `app/`, and add the Google Services + Firebase Messaging Gradle
   plugins/deps.
2. Add a `FirebaseMessagingService` in the app that shows a monitor notification and refreshes
   `SyncManager.syncNow()` on data messages. Register its token via `POST /v1/devices/fcm-token`.
3. The **FCM server key** stays on your backend, never in the app. The backend sends a data
   message to the monitor's token when an event is not recorded / becomes critical / is taken,
   honoring the primary device's monitor-notification toggles.

## Security checklist

- Turso token and FCM server key are **environment variables on the backend only**.
- All transport over HTTPS. Per-device bearer tokens; revoke invalidates server-side.
- No analytics; medication data is sent only to your backend, only when sync is enabled.

## Going live in the app

1. Implement `RemoteSyncClient(baseUrl, deviceSessionToken) : SyncClient`.
2. On pairing/sign-in, store the session token in Android `EncryptedSharedPreferences`
   (dependency already included: `androidx.security:security-crypto`).
3. Set `ServiceLocator.syncManager(ctx).client = RemoteSyncClient(...)`.
4. Add Firebase (`google-services.json` + plugins) and the messaging service.

Nothing else in the app needs to change — the outbox, UUIDs, roles, and merge rules are already in place.
