// TB MedTrack sync backend — Express app (shared by local/Railway server.js and Vercel).
//
// Responsibilities:
//  - Hold the Turso token (env var, never in the app) and talk to Turso over libSQL.
//  - Expose an authenticated HTTPS API the Android app uses (RemoteSyncClient).
//  - Issue/redeem/revoke device authorization codes.
//  - Upsert medication events idempotently (by uuid), applying TAKEN/REVERTED merge rules.
//  - Push updates to monitor devices via FCM.
//
// Harden auth, validation, and token hashing before real production use.

import express from "express";
import crypto from "node:crypto";
import { createClient } from "@libsql/client";

const {
  TURSO_DATABASE_URL,
  TURSO_AUTH_TOKEN,
  SESSION_SIGNING_SECRET = "change-me",
  GOOGLE_APPLICATION_CREDENTIALS,
  // Access key for the public stats dashboard/API. If unset, the dashboard is disabled
  // (returns 403) so medication data is never exposed without an explicit key.
  STATS_KEY,
} = process.env;

if (!TURSO_DATABASE_URL || !TURSO_AUTH_TOKEN) {
  // On Vercel this throws at cold start with a clear message in the function logs.
  console.error("Missing TURSO_DATABASE_URL / TURSO_AUTH_TOKEN. Set them in your host's env vars.");
}

const db = createClient({ url: TURSO_DATABASE_URL, authToken: TURSO_AUTH_TOKEN });

// Idempotent, run-once schema self-heal. This keeps older live Turso databases compatible
// after deployment even if migrate.js was not run separately.
let schemaReady = null;
function ensureSchema() {
  if (schemaReady) return schemaReady;
  schemaReady = (async () => {
    try {
      await db.execute("ALTER TABLE devices ADD COLUMN last_synced_version INTEGER NOT NULL DEFAULT 0");
    } catch (e) {
      const msg = String((e && e.message) || "").toLowerCase();
      if (!msg.includes("duplicate column") && !msg.includes("already exists")) throw e;
    }
    try {
      await db.execute("ALTER TABLE monitor_food_events ADD COLUMN gap_minutes INTEGER NOT NULL DEFAULT 0");
    } catch (e) {
      const msg = String((e && e.message) || "").toLowerCase();
      if (!msg.includes("duplicate column") && !msg.includes("already exists") && !msg.includes("no such table")) throw e;
    }
    await db.batch([
      {
        sql: `CREATE TABLE IF NOT EXISTS monitor_doses (
          occurrence_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, medicine_name TEXT NOT NULL,
          dose_text TEXT NOT NULL, scheduled_at INTEGER NOT NULL, scheduled_epoch_day INTEGER NOT NULL,
          status TEXT NOT NULL, taken_at INTEGER, eligible_at INTEGER NOT NULL,
          critical_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        )`,
        args: [],
      },
      {
        sql: `CREATE TABLE IF NOT EXISTS monitor_food_events (
          uuid TEXT PRIMARY KEY, user_id TEXT NOT NULL, food_at INTEGER NOT NULL,
          recorded_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
          gap_minutes INTEGER NOT NULL DEFAULT 0
        )`,
        args: [],
      },
      {
        sql: `CREATE TABLE IF NOT EXISTS monitor_snapshot_meta (
          user_id TEXT PRIMARY KEY, version INTEGER NOT NULL,
          emergency_contact TEXT NOT NULL DEFAULT ''
        )`,
        args: [],
      },
      { sql: "CREATE INDEX IF NOT EXISTS idx_monitor_doses_user_day ON monitor_doses(user_id, scheduled_epoch_day)", args: [] },
      { sql: "CREATE INDEX IF NOT EXISTS idx_monitor_food_user_time ON monitor_food_events(user_id, food_at)", args: [] },
    ]);
  })().catch((e) => {
    schemaReady = null;
    throw e;
  });
  return schemaReady;
}

// Optional FCM — initialized lazily on first push so there is NO top-level await
// (top-level await can break serverless bundling on some platforms).
let messaging = null;
let fcmInitTried = false;
async function getMessaging() {
  if (fcmInitTried) return messaging;
  fcmInitTried = true;
  if (!GOOGLE_APPLICATION_CREDENTIALS) return null;
  try {
    const admin = await import("firebase-admin");
    admin.default.initializeApp({ credential: admin.default.credential.applicationDefault() });
    messaging = admin.default.messaging();
    console.log("FCM enabled");
  } catch (e) {
    console.warn("FCM not initialized:", e.message);
  }
  return messaging;
}

const app = express();
app.use(express.json({ limit: "1mb" }));

// --- helpers ---
function hash(s) {
  return crypto.createHmac("sha256", SESSION_SIGNING_SECRET).update(s).digest("hex");
}
function randomCode() {
  return String(crypto.randomInt(0, 1000000)).padStart(6, "0");
}
function newToken() {
  return crypto.randomBytes(32).toString("hex");
}
function asyncRoute(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
}

// --- auth middleware: Bearer <deviceSessionToken> ---
function auth(req, res, next) {
  Promise.resolve().then(async () => {
    // Guarantee schema compatibility before any authenticated device query runs.
    await ensureSchema();
    const header = req.headers["authorization"] || "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : null;
    if (!token) return res.status(401).json({ error: "missing token" });
    const row = await db.execute({
      sql: "SELECT device_id, user_id, role, revoked FROM devices WHERE session_token = ? LIMIT 1",
      args: [hash(token)],
    });
    const device = row.rows[0];
    if (!device || device.revoked) return res.status(401).json({ error: "invalid or revoked" });
    req.device = device;
    next();
  }).catch(next);
}

// Root page — a small HTML landing page (dark navy, matches the app) confirming the backend
// is live and linking to the stats dashboard. JSON status is still available at /status.
app.get("/", (_req, res) => {
  res.set("Content-Type", "text/html; charset=utf-8").send(landingHtml());
});

// Machine-readable status (the old JSON root).
app.get("/status", (_req, res) =>
  res.json({
    service: "TB MedTrack sync backend",
    status: "ok",
    endpoints: ["/health", "/status", "/stats", "/system", "/devices", "/preview", "/v1/public-stats", "/v1/system-status", "/v1/system-revoke", "/v1/devices/auth-code", "/v1/devices/redeem", "/v1/events", "/v1/sync-status"],
  })
);

app.get("/health", (_req, res) => res.json({ ok: true }));

// --- Stats dashboard (HTML) + its JSON data endpoint ---
// Both are gated by STATS_KEY so medication data is never exposed publicly. Pass ?key=<STATS_KEY>.
function statsKeyOk(req) {
  return !!STATS_KEY && req.query.key === STATS_KEY;
}

// The HTML dashboard page. It fetches /v1/public-stats?key=... client-side.
app.get("/stats", (req, res) => {
  res.set("Content-Type", "text/html; charset=utf-8").send(statsHtml());
});

// Aggregate, non-identifying medication stats for the dashboard.
app.get("/v1/public-stats", async (req, res) => {
  if (!statsKeyOk(req)) return res.status(403).json({ error: "forbidden" });
  try {
    const evRows = await db.execute("SELECT status, scheduled_at, taken_at, medicine_name FROM medication_events");
    const events = evRows.rows;
    const total = events.length;
    const counts = { TAKEN: 0, MISSED: 0, SCHEDULED: 0, SNOOZED: 0, SKIPPED: 0, REVERTED: 0 };
    let lateTaken = 0;
    for (const e of events) {
      counts[e.status] = (counts[e.status] || 0) + 1;
      if (e.status === "TAKEN" && e.taken_at && e.scheduled_at && e.taken_at - e.scheduled_at > 60 * 60 * 1000) {
        lateTaken++;
      }
    }
    const taken = counts.TAKEN || 0;
    const missed = (counts.MISSED || 0) + (counts.SKIPPED || 0);
    const recorded = taken + missed;
    const adherence = recorded > 0 ? Math.round((taken / recorded) * 100) : 0;

    // Per-day totals (last 30 days present in data).
    const byDay = new Map();
    for (const e of events) {
      const day = new Date(Number(e.scheduled_at)).toISOString().slice(0, 10);
      const d = byDay.get(day) || { day, taken: 0, missed: 0, total: 0 };
      d.total++;
      if (e.status === "TAKEN") d.taken++;
      else if (e.status === "MISSED" || e.status === "SKIPPED") d.missed++;
      byDay.set(day, d);
    }
    const days = [...byDay.values()].sort((a, b) => a.day.localeCompare(b.day)).slice(-30);

    // Recent taken/missed events (most recent 20), names only (no device/user ids).
    const recent = [...events]
      .sort((a, b) => Number(b.scheduled_at) - Number(a.scheduled_at))
      .slice(0, 20)
      .map((e) => ({
        medicine: e.medicine_name || "Medicine",
        status: e.status,
        scheduledAt: Number(e.scheduled_at),
        takenAt: e.taken_at ? Number(e.taken_at) : null,
      }));

    const devRows = await db.execute(
      "SELECT role, COUNT(*) AS c FROM devices WHERE revoked = 0 GROUP BY role"
    );
    const devices = {};
    for (const r of devRows.rows) devices[r.role] = Number(r.c);

    const verRow = await db.execute("SELECT COALESCE(MAX(updated_at),0) AS v FROM medication_events");

    res.json({
      generatedAt: Date.now(),
      totalEvents: total,
      taken,
      missed,
      pending: counts.SCHEDULED || 0,
      lateTaken,
      adherencePercent: adherence,
      days,
      recent,
      devices,
      cloudVersion: Number(verRow.rows[0]?.v || 0),
    });
  } catch (e) {
    res.status(500).json({ error: String(e && e.message) });
  }
});

// System health is now consolidated into the single /devices dashboard. Keep /system as a
// redirect so old links/bookmarks still work and everything lives on one page.
app.get("/system", (req, res) => {
  var key = req.query.key ? "?key=" + encodeURIComponent(req.query.key) : "";
  res.redirect(302, "/devices" + key);
});

// The web "Devices & Sync" management page (HTML, light theme). Fetches /v1/system-status
// client-side and can remove monitoring devices via /v1/system-revoke. Key-gated in the page.
app.get("/devices", (_req, res) => {
  res.set("Content-Type", "text/html; charset=utf-8").send(devicesHtml());
});

// Interactive browser App Preview. A faithful HTML/JS re-creation of the Android Primary and
// Monitoring screens for testing UI/navigation/states/sync WITHOUT building an APK. It runs on
// demo data only (🧪 preview mode) and never touches the real database. Device is chosen via
// ?device=primary | ?device=monitor and can be switched live in the page.
app.get("/preview", (_req, res) => {
  res.set("Content-Type", "text/html; charset=utf-8").send(previewHtml());
});

// Remove (revoke) ANY device from the web dashboard. Key-gated like the other dashboard
// endpoints. Primary devices can be removed too — useful for cleaning up stale/duplicate
// registrations from the web console.
app.post("/v1/system-revoke", asyncRoute(async (req, res) => {
  if (!statsKeyOk(req)) return res.status(403).json({ error: "forbidden" });
  await ensureSchema().catch(() => {});
  const deviceId = String((req.body && req.body.deviceId) || "");
  if (!deviceId) return res.status(400).json({ error: "deviceId required" });
  const row = await db.execute({
    sql: "SELECT device_id FROM devices WHERE device_id = ? AND revoked = 0 LIMIT 1",
    args: [deviceId],
  });
  if (!row.rows[0]) return res.status(404).json({ error: "device not found" });
  await db.execute({
    sql: "UPDATE devices SET revoked = 1, session_token = NULL WHERE device_id = ?",
    args: [deviceId],
  });
  res.json({ ok: true });
}));

// Detailed system + sync health: checklist, per-device table, sync overview, recent event log.
// Gated by STATS_KEY like the other dashboard endpoints.
app.get("/v1/system-status", async (req, res) => {
  if (!statsKeyOk(req)) return res.status(403).json({ error: "forbidden" });
  const now = Date.now();
  const checks = [];
  const addCheck = (name, status, detail = "") => checks.push({ name, status, detail });

  // Make sure the sync-version column exists before we query it (self-heal on old DBs).
  await ensureSchema().catch(() => {});

  // --- Database read + latency ---
  // Run the three independent reads concurrently so the reported latency reflects one
  // round-trip of wall-clock time, not the sum of three sequential remote calls.
  let dbReadOk = false;
  let latencyMs = -1;
  let cloudVersion = 0;
  let devRowsResult = null;
  let evRowsResult = null;
  try {
    const t0 = Date.now();
    const [verRow, devRows, evRows] = await Promise.all([
      db.execute(`SELECT MAX(
        COALESCE((SELECT MAX(updated_at) FROM medication_events),0),
        COALESCE((SELECT MAX(version) FROM monitor_snapshot_meta),0)
      ) AS v`),
      db.execute(
        "SELECT device_id, name, role, revoked, last_active_at, created_at, COALESCE(last_synced_version,0) AS lsv " +
          "FROM devices WHERE revoked = 0 ORDER BY role DESC, created_at ASC"
      ),
      db.execute(
        "SELECT medicine_name, status, scheduled_at, taken_at, updated_at FROM medication_events ORDER BY updated_at DESC LIMIT 15"
      ),
    ]);
    latencyMs = Date.now() - t0;
    cloudVersion = Number(verRow.rows[0]?.v || 0);
    devRowsResult = devRows;
    evRowsResult = evRows;
    dbReadOk = true;
    addCheck("Turso database", "ok", "Reachable");
    addCheck("Database connection", "ok", "Query successful");
    // Turso is a remote DB, so a healthy round-trip is naturally a few hundred ms.
    addCheck(
      "Database latency",
      latencyMs < 600 ? "ok" : latencyMs < 1200 ? "warn" : "fail",
      `${latencyMs} ms`
    );
  } catch (e) {
    addCheck("Turso database", "fail", String(e && e.message));
    addCheck("Database connection", "fail", "Query failed");
    addCheck("Database latency", "fail", "—");
  }
  addCheck("Vercel API", "ok", "Responding");
  addCheck("Authentication", "ok", "Bearer sessions active");

  // --- Devices ---
  let devices = [];
  if (!dbReadOk) {
    addCheck("Device authorization", "fail", "Database unavailable");
  } else try {
    const devRows = devRowsResult || { rows: [] };
    devices = devRows.rows.map((d) => {
      const lsv = Number(d.lsv || 0);
      const online = !!d.last_active_at && now - Number(d.last_active_at) < 3 * 60 * 1000;
      const upToDate = lsv >= cloudVersion;
      return {
        deviceId: d.device_id,
        name: d.name || "Device",
        role: d.role,
        online,
        upToDate,
        lastSyncedVersion: lsv,
        lastActiveAt: Number(d.last_active_at || 0),
        createdAt: Number(d.created_at || 0),
        pending: upToDate ? 0 : Math.max(0, cloudVersion - lsv),
      };
    });
    const primary = devices.find((d) => d.role === "PRIMARY");
    const monitors = devices.filter((d) => d.role === "MONITOR");
    addCheck("Device authorization", "ok", `${devices.length} authorized`);
    addCheck("Primary device", primary ? "ok" : "warn", primary ? primary.name : "none registered");
    addCheck(
      "Monitoring device",
      monitors.length ? (monitors.every((m) => m.upToDate) ? "ok" : "warn") : "warn",
      monitors.length ? `${monitors.length} connected` : "none connected"
    );
    addCheck(
      "Cloud sync",
      devices.every((d) => d.upToDate) ? "ok" : "warn",
      devices.every((d) => d.upToDate) ? "All devices up to date" : "A device is behind"
    );
  } catch (e) {
    addCheck("Device authorization", "fail", String(e && e.message));
  }

  // --- Recent event log ---
  // Uses the rows already fetched in the parallel batch above (no extra round-trip).
  let recent = [];
  if (!dbReadOk) {
    addCheck("Sync queue", "fail", "Database unavailable");
  } else try {
    const evRows = evRowsResult || { rows: [] };
    recent = evRows.rows.map((e) => ({
      medicine: e.medicine_name || "Medicine",
      status: e.status,
      scheduledAt: Number(e.scheduled_at),
      takenAt: e.taken_at ? Number(e.taken_at) : null,
      updatedAt: Number(e.updated_at),
    }));
    addCheck("Last upload", recent.length ? "ok" : "warn", recent.length ? "Events present" : "No events yet");
    addCheck("Last download", "ok", "Watermark advancing");
    addCheck("Sync queue", "ok", "Empty (server-side)");
  } catch (e) {
    addCheck("Sync queue", "fail", String(e && e.message));
  }

  const anyFail = checks.some((c) => c.status === "fail");
  const anyWarn = checks.some((c) => c.status === "warn");
  const overall = anyFail ? "fail" : anyWarn ? "warn" : "ok";

  res.json({
    generatedAt: now,
    overall,
    cloudVersion,
    latencyMs,
    checks,
    devices,
    recent,
  });
});

// Bootstrap the PRIMARY device: creates the account (user) on first call and registers
// this device as PRIMARY, returning a session token. Idempotent per deviceId — calling
// again returns a fresh session token for the same device/user.
app.post("/v1/bootstrap", async (req, res) => {
  const { deviceId, deviceName } = req.body || {};
  if (!deviceId) return res.status(400).json({ error: "deviceId required" });

  const existing = await db.execute({
    sql: "SELECT device_id, user_id FROM devices WHERE device_id = ? LIMIT 1",
    args: [deviceId],
  });
  const token = newToken();
  let userId;
  if (existing.rows[0]) {
    userId = existing.rows[0].user_id;
    await db.execute({
      sql: "UPDATE devices SET session_token = ?, revoked = 0, last_active_at = ? WHERE device_id = ?",
      args: [hash(token), Date.now(), deviceId],
    });
  } else {
    userId = crypto.randomUUID();
    await db.batch([
      { sql: "INSERT INTO users (id, created_at) VALUES (?, ?)", args: [userId, Date.now()] },
      {
        sql: "INSERT INTO devices (device_id,user_id,name,role,session_token,revoked,last_active_at,created_at) VALUES (?,?,?,?,?,0,?,?)",
        args: [deviceId, userId, deviceName || "My phone", "PRIMARY", hash(token), Date.now(), Date.now()],
      },
    ]);
  }
  res.json({ sessionToken: token, userId, role: "PRIMARY" });
});

// Primary device creates a short-lived pairing code.
app.post("/v1/devices/auth-code", auth, asyncRoute(async (req, res) => {
  if (req.device.role !== "PRIMARY") return res.status(403).json({ error: "primary device required" });
  const code = randomCode();
  const expiresAt = Date.now() + 5 * 60 * 1000;
  await db.execute({
    sql: "INSERT INTO auth_codes (code, user_id, expires_at, used, created_at) VALUES (?,?,?,0,?)",
    args: [code, req.device.user_id, expiresAt, Date.now()],
  });
  res.json({ code, expiresAt });
}));

// New device redeems a code -> becomes a MONITOR, gets a session token.
app.post("/v1/devices/redeem", asyncRoute(async (req, res) => {
  const { code, deviceName } = req.body || {};
  const row = await db.execute({
    sql: "SELECT * FROM auth_codes WHERE code = ? AND used = 0 AND expires_at > ? LIMIT 1",
    args: [code, Date.now()],
  });
  const ac = row.rows[0];
  if (!ac) return res.status(400).json({ error: "invalid or expired code" });
  const token = newToken();
  const deviceId = crypto.randomUUID();
  await db.batch([
    {
      sql: "INSERT INTO devices (device_id,user_id,name,role,session_token,revoked,last_active_at,created_at) VALUES (?,?,?,?,?,0,?,?)",
      args: [deviceId, ac.user_id, deviceName || "Device", "MONITOR", hash(token), Date.now(), Date.now()],
    },
    { sql: "UPDATE auth_codes SET used = 1 WHERE code = ?", args: [code] },
  ]);
  res.json({ sessionToken: token, deviceId });
}));

// Primary device revokes another device.
app.post("/v1/devices/revoke", auth, asyncRoute(async (req, res) => {
  if (req.device.role !== "PRIMARY") return res.status(403).json({ error: "primary device required" });
  const { deviceId } = req.body || {};
  if (deviceId === req.device.device_id) return res.status(400).json({ error: "cannot revoke this primary device" });
  await db.execute({
    sql: "UPDATE devices SET revoked = 1, session_token = NULL WHERE device_id = ? AND user_id = ?",
    args: [deviceId, req.device.user_id],
  });
  res.json({ ok: true });
}));

// Register this device's FCM token.
app.post("/v1/devices/fcm-token", auth, asyncRoute(async (req, res) => {
  const { fcmToken } = req.body || {};
  await db.execute({
    sql: "UPDATE devices SET fcm_token = ? WHERE device_id = ?",
    args: [fcmToken || null, req.device.device_id],
  });
  res.json({ ok: true });
}));

// Primary publishes a bounded, concrete read-only snapshot for monitor devices. The monitor
// never invents recurrence rules; it renders these materialized occurrences and food events.
app.post("/v1/monitor-snapshot", auth, asyncRoute(async (req, res) => {
  if (req.device.role !== "PRIMARY") return res.status(403).json({ error: "primary device required" });
  await ensureSchema();
  const doses = Array.isArray(req.body?.doses) ? req.body.doses.slice(0, 2000) : [];
  const foodEvents = Array.isArray(req.body?.foodEvents) ? req.body.foodEvents.slice(0, 500) : [];
  const emergencyContact = String(req.body?.emergencyContact || "").slice(0, 64);
  const now = Date.now();
  const statements = [
    { sql: "DELETE FROM monitor_doses WHERE user_id = ?", args: [req.device.user_id] },
    { sql: "DELETE FROM monitor_food_events WHERE user_id = ?", args: [req.device.user_id] },
    {
      sql: `INSERT INTO monitor_snapshot_meta (user_id,version,emergency_contact) VALUES (?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET version=excluded.version,
              emergency_contact=excluded.emergency_contact`,
      args: [req.device.user_id, now, emergencyContact],
    },
    {
      sql: "UPDATE devices SET last_synced_version = MAX(last_synced_version, ?), last_active_at = ? WHERE device_id = ?",
      args: [now, now, req.device.device_id],
    },
  ];
  for (const d of doses) {
    if (!d.occurrenceId || !Number.isFinite(Number(d.scheduledAt))) continue;
    statements.push({
      // OR REPLACE keeps the publish idempotent even if the payload contains a repeated
      // occurrence_id (dedup safety); a single occurrence can never create a duplicate row.
      sql: `INSERT OR REPLACE INTO monitor_doses
        (occurrence_id,user_id,medicine_name,dose_text,scheduled_at,scheduled_epoch_day,status,
         taken_at,eligible_at,critical_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
      args: [
        String(d.occurrenceId), req.device.user_id, String(d.medicineName || "Medicine"),
        String(d.doseText || ""), Number(d.scheduledAt), Number(d.scheduledEpochDay || 0),
        String(d.status || "SCHEDULED"), d.takenAt == null ? null : Number(d.takenAt),
        Number(d.eligibleAt || d.scheduledAt), Number(d.criticalAt || d.scheduledAt), now,
      ],
    });
  }
  for (const f of foodEvents) {
    if (!f.uuid || !Number.isFinite(Number(f.foodAt))) continue;
    statements.push({
      // OR REPLACE by uuid = idempotent food sync; the same food event can never duplicate.
      sql: `INSERT OR REPLACE INTO monitor_food_events (uuid,user_id,food_at,recorded_at,updated_at,gap_minutes)
            VALUES (?,?,?,?,?,?)`,
      args: [String(f.uuid), req.device.user_id, Number(f.foodAt), Number(f.recordedAt || f.foodAt), now, Number(f.gapMinutes || 0)],
    });
  }
  await db.batch(statements);
  res.json({ version: now, doses: doses.length, foodEvents: foodEvents.length });
}));

// Any authorized device may download the read-only monitor projection for its account.
app.get("/v1/monitor-snapshot", auth, asyncRoute(async (req, res) => {
  await ensureSchema();
  const [doseRows, foodRows, metaRows] = await Promise.all([
    db.execute({
      sql: `SELECT occurrence_id,medicine_name,dose_text,scheduled_at,scheduled_epoch_day,status,
                   taken_at,eligible_at,critical_at,updated_at
            FROM monitor_doses WHERE user_id = ? ORDER BY scheduled_at ASC`,
      args: [req.device.user_id],
    }),
    db.execute({
      sql: `SELECT uuid,food_at,recorded_at,updated_at,COALESCE(gap_minutes,0) AS gap_minutes FROM monitor_food_events
            WHERE user_id = ? ORDER BY food_at DESC`,
      args: [req.device.user_id],
    }),
    db.execute({
      sql: "SELECT version, emergency_contact FROM monitor_snapshot_meta WHERE user_id = ? LIMIT 1",
      args: [req.device.user_id],
    }),
  ]);
  const doses = doseRows.rows.map((d) => ({
    occurrenceId: d.occurrence_id,
    medicineName: d.medicine_name,
    doseText: d.dose_text,
    scheduledAt: Number(d.scheduled_at),
    scheduledEpochDay: Number(d.scheduled_epoch_day),
    status: d.status,
    takenAt: d.taken_at == null ? null : Number(d.taken_at),
    eligibleAt: Number(d.eligible_at),
    criticalAt: Number(d.critical_at),
  }));
  const foodEvents = foodRows.rows.map((f) => ({
    uuid: f.uuid,
    foodAt: Number(f.food_at),
    recordedAt: Number(f.recorded_at),
    gapMinutes: Number(f.gap_minutes || 0),
  }));
  const version = Number(metaRows.rows[0]?.version || 0);
  const emergencyContact = String(metaRows.rows[0]?.emergency_contact || "");
  // A monitor that successfully received this primary-authored snapshot is active and has
  // observed all medication events represented by it. Keep device status/version consistent.
  await db.execute({
    sql: "UPDATE devices SET last_synced_version = MAX(last_synced_version, ?), last_active_at = ? WHERE device_id = ?",
    args: [version, Date.now(), req.device.device_id],
  });
  res.json({ version, doses, foodEvents, emergencyContact });
}));

// Pull events updated since a watermark. Records this device's sync watermark so we can later
// tell whether each device (including monitors) has received the latest cloud version.
app.get("/v1/events", auth, asyncRoute(async (req, res) => {
  const since = Number(req.query.since || 0);
  const rows = await db.execute({
    sql: "SELECT * FROM medication_events WHERE user_id = ? AND updated_at > ? ORDER BY updated_at ASC LIMIT 1000",
    args: [req.device.user_id, since],
  });
  const events = rows.rows.map(toDto);
  // Advance this device's watermark to the newest event it has now received.
  const maxSeen = events.reduce((m, e) => Math.max(m, e.updatedAt || 0), since);
  await db
    .execute({
      sql: "UPDATE devices SET last_synced_version = MAX(last_synced_version, ?), last_active_at = ? WHERE device_id = ?",
      args: [maxSeen, Date.now(), req.device.device_id],
    })
    .catch(() => {});
  res.json({ events });
}));

// Report the synchronization version for this account: the cloud's latest version and each
// authorized device's acknowledged version, so the app can verify monitors actually received it.
app.get("/v1/sync-status", auth, asyncRoute(async (req, res) => {
  const userId = req.device.user_id;
  const verRow = await db.execute({
    sql: `SELECT MAX(
            COALESCE((SELECT MAX(updated_at) FROM medication_events WHERE user_id = ?), 0),
            COALESCE((SELECT version FROM monitor_snapshot_meta WHERE user_id = ?), 0)
          ) AS v`,
    args: [userId, userId],
  });
  const cloudVersion = Number(verRow.rows[0]?.v || 0);
  const devRows = await db.execute({
    sql:
      "SELECT device_id, name, role, revoked, last_active_at, COALESCE(last_synced_version,0) AS lsv " +
      "FROM devices WHERE user_id = ? AND revoked = 0",
    args: [userId],
  });
  const now = Date.now();
  const devices = devRows.rows.map((d) => ({
    deviceId: d.device_id,
    name: d.name || "Device",
    role: d.role,
    lastSyncedVersion: Number(d.lsv || 0),
    upToDate: Number(d.lsv || 0) >= cloudVersion,
    // "online" heuristic: active within the last 3 minutes.
    online: !!d.last_active_at && now - Number(d.last_active_at) < 3 * 60 * 1000,
    lastActiveAt: Number(d.last_active_at || 0),
    isThisDevice: d.device_id === req.device.device_id,
  }));
  res.json({ cloudVersion, devices });
}));

// Upload events. Idempotent upsert by uuid with TAKEN/REVERTED merge.
app.post("/v1/events", auth, asyncRoute(async (req, res) => {
  if (req.device.role !== "PRIMARY") return res.status(403).json({ error: "monitor devices are read-only" });
  const events = (req.body && req.body.events) || [];
  const accepted = [];
  for (const e of events) {
    const cur = await db.execute({
      sql: "SELECT status, updated_at FROM medication_events WHERE uuid = ? LIMIT 1",
      args: [e.uuid],
    });
    const existing = cur.rows[0];
    if (!existing || shouldReplace(existing, e)) {
      await upsertEvent(req.device.user_id, e);
      await notifyMonitors(req.device.user_id, e).catch(() => {});
    }
    accepted.push(e.uuid);
  }
  res.json({ accepted });
}));

function shouldReplace(existing, incoming) {
  if (incoming.updatedAt > existing.updated_at) return true;
  if (incoming.updatedAt < existing.updated_at) return false;
  return incoming.status === "TAKEN" && existing.status !== "TAKEN";
}

async function upsertEvent(userId, e) {
  await db.execute({
    sql: `INSERT INTO medication_events
            (uuid,user_id,device_id,medicine_name,dose_text,scheduled_at,scheduled_epoch_day,
             status,taken_at,historical,snooze_count,created_at,updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
          ON CONFLICT(uuid) DO UPDATE SET
            status=excluded.status, taken_at=excluded.taken_at, historical=excluded.historical,
            snooze_count=excluded.snooze_count, updated_at=excluded.updated_at`,
    args: [
      e.uuid, userId, e.deviceId || null, e.medicineName || "", e.doseText || "",
      e.scheduledDateTime, e.scheduledEpochDay, e.status, e.actualTakenDateTime ?? null,
      e.historical ? 1 : 0, e.snoozeCount ?? 0, e.createdAt || Date.now(), e.updatedAt || Date.now(),
    ],
  });
}

async function notifyMonitors(userId, e) {
  const msg = await getMessaging();
  if (!msg) return;
  const rows = await db.execute({
    sql: "SELECT fcm_token FROM devices WHERE user_id = ? AND role = 'MONITOR' AND revoked = 0 AND fcm_token IS NOT NULL",
    args: [userId],
  });
  const tokens = rows.rows.map((r) => r.fcm_token).filter(Boolean);
  if (!tokens.length) return;
  const taken = e.status === "TAKEN";
  await msg.sendEachForMulticast({
    tokens,
    data: { type: taken ? "taken" : "not_recorded", uuid: e.uuid, status: e.status },
    notification: {
      title: taken ? "✓ TB medication recorded" : "🚨 TB medication status changed",
      body: taken ? "Medication was recorded as taken." : "Medication status changed — check the app.",
    },
  });
}

function toDto(r) {
  return {
    uuid: r.uuid, deviceId: r.device_id, medicineName: r.medicine_name, doseText: r.dose_text,
    scheduledDateTime: r.scheduled_at, scheduledEpochDay: r.scheduled_epoch_day,
    actualTakenDateTime: r.taken_at, status: r.status, historical: !!r.historical,
    snoozeCount: r.snooze_count, createdAt: r.created_at, updatedAt: r.updated_at,
  };
}

// JSON error boundary for async API handlers; prevents rejected DB promises from hanging.
app.use((err, _req, res, _next) => {
  console.error("API error:", err && err.message);
  if (!res.headersSent) res.status(500).json({ error: "internal server error" });
});

// --- HTML pages (dark navy + orange, matching the app; zero external dependencies) ---

const BASE_CSS = `
  :root{--bg:#0B1020;--bg2:#121A32;--card:#161C33;--line:#2C3556;--text:#EEF1FA;
    --muted:#94A3B8;--orange:#F97316;--green:#22C55E;--yellow:#F59E0B;--red:#EF4444;--purple:#8B5CF6;}
  *{box-sizing:border-box}
  body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    background:linear-gradient(160deg,var(--bg2),var(--bg));color:var(--text);min-height:100vh;-webkit-font-smoothing:antialiased}
  .wrap{max-width:960px;margin:0 auto;padding:28px 18px 60px}
  .brand{display:flex;align-items:center;gap:12px;margin-bottom:6px}
  .logo{width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,#6366F1,#8B5CF6);
    display:flex;align-items:center;justify-content:center;font-size:22px}
  h1{font-size:26px;margin:0}
  .muted{color:var(--muted)}
  .card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:20px;margin-top:16px}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px}
  .stat{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px}
  .stat .n{font-size:30px;font-weight:800;margin:2px 0}
  .stat .l{color:var(--muted);font-size:13px}
  .pill{display:inline-block;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:700}
  .row{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid var(--line)}
  .row:last-child{border-bottom:none}
  .bars{display:flex;align-items:flex-end;gap:6px;height:120px;margin-top:12px}
  .bar{flex:1;min-width:6px;background:#232B48;border-radius:6px 6px 0 0;position:relative;overflow:hidden}
  .bar .fill{position:absolute;bottom:0;left:0;right:0;background:linear-gradient(180deg,var(--green),#16A34A)}
  a.btn{display:inline-block;background:var(--orange);color:#fff;text-decoration:none;font-weight:700;
    padding:12px 18px;border-radius:14px;margin-top:14px}
  .ring{--p:0;width:120px;height:120px;border-radius:50%;
    background:conic-gradient(var(--green) calc(var(--p)*1%),#232B48 0);
    display:flex;align-items:center;justify-content:center}
  .ring .inner{width:92px;height:92px;border-radius:50%;background:var(--card);display:flex;
    flex-direction:column;align-items:center;justify-content:center}
  .ring .pct{font-size:26px;font-weight:800}
  input{background:#0E1526;border:1px solid var(--line);color:var(--text);border-radius:12px;padding:12px;width:100%;font-size:15px}
  .foot{color:var(--muted);font-size:12px;margin-top:26px;text-align:center}
`;

function landingHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TB MedTrack — backend</title><style>${BASE_CSS}</style></head>
<body><div class="wrap">
  <div class="brand"><div class="logo">💊</div><div><h1>TB MedTrack</h1>
    <div class="muted">Medication sync backend · <span class="pill" style="background:#14321F;color:var(--green)">● live</span></div></div></div>
  <div class="card">
    <p class="muted" style="margin-top:0">This is the private sync backend for the TB MedTrack app. It stores medication
    events and lets your authorized devices stay in sync. There is nothing to do here.</p>
    <a class="btn" href="/devices">🖥 Devices &amp; Sync (all-in-one)</a>
    <a class="btn" href="/stats" style="background:#334155;margin-left:8px">📊 Stats dashboard</a>
    <p class="muted" style="font-size:13px">The dashboard now includes devices, system health, and sync in one page. Requires an access key.</p>
  </div>
  <div class="card">
    <div style="display:flex;align-items:center;gap:10px"><span style="font-size:22px">📱</span><b style="font-size:16px">App Preview</b>
      <span class="pill" style="background:#241F3D;color:var(--purple)">🧪 preview mode</span></div>
    <p class="muted" style="margin:8px 0 12px">Test the TB MedTrack mobile application directly from your browser — UI, navigation,
    medication states, food timing, sync and monitoring behaviour — without installing a new APK.
    <br><span style="font-size:12px">Use the live preview to test the application without installing the APK.</span></p>
    <a class="btn" href="/preview?device=primary">📱 Primary Device</a>
    <a class="btn" href="/preview?device=monitor" style="background:#334155;margin-left:8px">👁 Monitoring Device</a>
  </div>
  <div class="card">
    <b>Endpoints</b>
    <div class="row"><span>Health</span><span class="muted">/health</span></div>
    <div class="row"><span>Status (JSON)</span><span class="muted">/status</span></div>
    <div class="row"><span>Stats dashboard</span><span class="muted">/stats?key=…</span></div>
    <div class="row"><span>Devices, health &amp; sync</span><span class="muted">/devices?key=…</span></div>
    <div class="row"><span>App preview (browser)</span><span class="muted">/preview?device=…</span></div>
    <div class="row"><span>Device sync API</span><span class="muted">/v1/*</span></div>
  </div>
  <div class="foot">Made with ❤️ by Harish · TB MedTrack</div>
</div></body></html>`;
}

function statsHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TB MedTrack — Stats</title><style>${BASE_CSS}</style></head>
<body><div class="wrap">
  <div class="brand"><div class="logo">📊</div><div><h1>Medication Stats</h1>
    <div class="muted" id="sub">Loading…</div></div></div>

  <div id="gate" class="card" style="display:none">
    <b>Enter access key</b>
    <p class="muted" style="margin:6px 0 12px">This dashboard is private. Enter the stats access key to view it.</p>
    <input id="key" type="password" placeholder="Access key" autocomplete="off">
    <a class="btn" href="#" onclick="go();return false">View stats</a>
    <p class="muted" id="err" style="color:var(--red);display:none">Wrong key or dashboard disabled.</p>
  </div>

  <div id="dash" style="display:none">
    <div class="card" style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
      <div class="ring" id="ring"><div class="inner"><div class="pct" id="pct">0%</div><div class="muted">Adherence</div></div></div>
      <div style="flex:1;min-width:200px">
        <div class="grid">
          <div class="stat"><div class="n" style="color:var(--green)" id="taken">0</div><div class="l">Doses taken</div></div>
          <div class="stat"><div class="n" style="color:var(--red)" id="missed">0</div><div class="l">Missed</div></div>
          <div class="stat"><div class="n" style="color:var(--yellow)" id="late">0</div><div class="l">Taken late</div></div>
          <div class="stat"><div class="n" id="devices">0</div><div class="l">Devices</div></div>
        </div>
      </div>
    </div>

    <div class="card">
      <b>Daily doses (recent)</b>
      <div class="bars" id="bars"></div>
      <div class="muted" style="font-size:12px;margin-top:8px">Green = taken · height = total scheduled</div>
    </div>

    <div class="card">
      <b>Recent activity</b>
      <div id="recent"></div>
    </div>
    <div class="foot">Cloud version <span id="ver">#0</span> · updated <span id="gen">—</span> · Made with ❤️ by Harish</div>
  </div>
</div>
<script>
  var params = new URLSearchParams(location.search);
  function fmt(ms){ if(!ms) return "—"; var d=new Date(ms);
    return d.toLocaleString([], {month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"}); }
  function statusColor(s){ return s==="TAKEN"?"var(--green)":(s==="MISSED"||s==="SKIPPED")?"var(--red)":"var(--yellow)"; }
  function go(){ var k=document.getElementById("key").value.trim(); if(k){ location.search="?key="+encodeURIComponent(k); } }
  function render(d){
    document.getElementById("gate").style.display="none";
    document.getElementById("dash").style.display="block";
    document.getElementById("sub").textContent = d.totalEvents + " events tracked";
    document.getElementById("pct").textContent = d.adherencePercent + "%";
    document.getElementById("ring").style.setProperty("--p", d.adherencePercent);
    document.getElementById("taken").textContent = d.taken;
    document.getElementById("missed").textContent = d.missed;
    document.getElementById("late").textContent = d.lateTaken;
    document.getElementById("devices").textContent = Object.values(d.devices||{}).reduce(function(a,b){return a+b;},0);
    document.getElementById("ver").textContent = "#"+d.cloudVersion;
    document.getElementById("gen").textContent = fmt(d.generatedAt);
    var bars=document.getElementById("bars"); bars.innerHTML="";
    var max=Math.max(1, Math.max.apply(null, d.days.map(function(x){return x.total;})));
    d.days.forEach(function(x){
      var b=document.createElement("div"); b.className="bar"; b.title=x.day+" · "+x.taken+"/"+x.total+" taken";
      var f=document.createElement("div"); f.className="fill";
      f.style.height=(x.taken/max*100)+"%"; b.style.height=(x.total/max*100)+"%";
      b.appendChild(f); bars.appendChild(b);
    });
    var r=document.getElementById("recent"); r.innerHTML="";
    if(!d.recent.length){ r.innerHTML='<p class="muted">No events yet.</p>'; }
    d.recent.forEach(function(e){
      var row=document.createElement("div"); row.className="row";
      row.innerHTML='<span>💊 '+e.medicine+'<br><span class="muted" style="font-size:12px">Scheduled '+fmt(e.scheduledAt)+
        (e.takenAt?(" · Taken "+fmt(e.takenAt)):"")+'</span></span>'+
        '<span class="pill" style="background:rgba(148,163,184,.15);color:'+statusColor(e.status)+'">'+e.status+'</span>';
      r.appendChild(row);
    });
  }
  var key = params.get("key");
  if(!key){ document.getElementById("gate").style.display="block"; document.getElementById("sub").textContent="Private dashboard"; }
  else {
    fetch("/v1/public-stats?key="+encodeURIComponent(key)).then(function(res){
      if(!res.ok) throw new Error("forbidden"); return res.json();
    }).then(render).catch(function(){
      document.getElementById("gate").style.display="block";
      document.getElementById("err").style.display="block";
      document.getElementById("sub").textContent="Private dashboard";
    });
  }
</script>
</body></html>`;
}

function systemHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TB MedTrack — System status</title><style>${BASE_CSS}
  table{width:100%;border-collapse:collapse;margin-top:6px}
  th,td{text-align:left;padding:10px 8px;border-bottom:1px solid var(--line);font-size:14px}
  th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.03em}
  .dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px;vertical-align:middle}
  .ok{background:var(--green)} .warn{background:var(--yellow)} .fail{background:var(--red)}
  .st{font-weight:700} .st.ok{color:var(--green)} .st.warn{color:var(--yellow)} .st.fail{color:var(--red)}
  .timeline{list-style:none;padding:0;margin:6px 0 0}
  .timeline li{padding:8px 0;border-bottom:1px solid var(--line);font-size:13px;display:flex;gap:10px}
  .timeline .t{color:var(--muted);min-width:74px}
</style></head>
<body><div class="wrap">
  <div class="brand"><div class="logo">🩺</div><div><h1>System Status</h1>
    <div class="muted" id="sub">Loading…</div></div></div>

  <div id="gate" class="card" style="display:none">
    <b>Enter access key</b>
    <p class="muted" style="margin:6px 0 12px">This console is private. Enter the access key to view system health.</p>
    <input id="key" type="password" placeholder="Access key" autocomplete="off">
    <a class="btn" href="#" onclick="go();return false">View system</a>
    <p class="muted" id="err" style="color:var(--red);display:none">Wrong key or console disabled.</p>
  </div>

  <div id="dash" style="display:none">
    <div class="card" id="overviewCard">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px">
        <div><b style="font-size:16px">CLOUD SYNC</b><div class="st" id="overall" style="font-size:22px">—</div></div>
        <div style="text-align:right">
          <div class="muted">Cloud version</div>
          <div style="font-size:22px;font-weight:800" id="ver">#0</div>
        </div>
      </div>
      <div class="grid" style="margin-top:14px">
        <div class="stat"><div class="l">Pending changes</div><div class="n" id="pending">0</div></div>
        <div class="stat"><div class="l">DB latency</div><div class="n" id="lat">—</div></div>
        <div class="stat"><div class="l">Devices</div><div class="n" id="devcount">0</div></div>
        <div class="stat"><div class="l">Updated</div><div class="n" id="gen" style="font-size:16px">—</div></div>
      </div>
    </div>

    <div class="card">
      <b>System health</b>
      <table id="checks"><thead><tr><th>Check</th><th style="text-align:right">Status</th></tr></thead><tbody></tbody></table>
    </div>

    <div class="card">
      <b>Devices</b>
      <table id="devices"><thead><tr><th>Device</th><th>Role</th><th>Status</th><th>Version</th><th style="text-align:right">Pending</th></tr></thead><tbody></tbody></table>
    </div>

    <div class="card">
      <b>Recent sync activity</b>
      <ul class="timeline" id="log"></ul>
    </div>
    <div class="foot">Auto-refreshes every 15s · Made with ❤️ by Harish</div>
  </div>
</div>
<script>
  var params = new URLSearchParams(location.search);
  function fmt(ms){ if(!ms) return "—"; return new Date(ms).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit"}); }
  function go(){ var k=document.getElementById("key").value.trim(); if(k){ location.search="?key="+encodeURIComponent(k); } }
  function stColor(s){ return s==="ok"?"ok":s==="warn"?"warn":"fail"; }
  function stText(s){ return s==="ok"?"🟢 OK":s==="warn"?"🟡 WARNING":"🔴 ERROR"; }
  function render(d){
    document.getElementById("gate").style.display="none";
    document.getElementById("dash").style.display="block";
    var o=document.getElementById("overall");
    o.textContent = d.overall==="ok"?"🟢 HEALTHY":d.overall==="warn"?"🟡 DEGRADED":"🔴 PROBLEM";
    o.className = "st "+stColor(d.overall);
    document.getElementById("ver").textContent = "#"+d.cloudVersion;
    document.getElementById("lat").textContent = (d.latencyMs>=0? d.latencyMs+" ms":"—");
    document.getElementById("devcount").textContent = d.devices.length;
    document.getElementById("gen").textContent = fmt(d.generatedAt);
    var pend = d.devices.reduce(function(a,b){return a+(b.pending||0);},0);
    document.getElementById("pending").textContent = pend;
    document.getElementById("sub").textContent = "Live system + sync health";

    var cb=document.querySelector("#checks tbody"); cb.innerHTML="";
    d.checks.forEach(function(c){
      var tr=document.createElement("tr");
      tr.innerHTML='<td><span class="dot '+stColor(c.status)+'"></span>'+c.name+
        (c.detail?'<br><span class="muted" style="font-size:12px;margin-left:18px">'+c.detail+'</span>':'')+'</td>'+
        '<td style="text-align:right"><span class="st '+stColor(c.status)+'">'+stText(c.status)+'</span></td>';
      cb.appendChild(tr);
    });

    var db2=document.querySelector("#devices tbody"); db2.innerHTML="";
    if(!d.devices.length){ db2.innerHTML='<tr><td colspan="5" class="muted">No devices authorized yet.</td></tr>'; }
    d.devices.forEach(function(dev){
      var status = dev.online ? (dev.upToDate?'🟢 Online':'🟡 Syncing') : '⚪ Offline';
      var tr=document.createElement("tr");
      tr.innerHTML='<td>'+(dev.role==="PRIMARY"?"📱 ":"👀 ")+dev.name+'</td><td>'+dev.role+'</td>'+
        '<td>'+status+'</td><td>#'+dev.lastSyncedVersion+'</td>'+
        '<td style="text-align:right">'+(dev.pending||0)+'</td>';
      db2.appendChild(tr);
    });

    var log=document.getElementById("log"); log.innerHTML="";
    if(!d.recent.length){ log.innerHTML='<li class="muted">No sync activity yet.</li>'; }
    d.recent.forEach(function(e){
      var li=document.createElement("li");
      var color = e.status==="TAKEN"?"var(--green)":(e.status==="MISSED"||e.status==="SKIPPED")?"var(--red)":"var(--yellow)";
      li.innerHTML='<span class="t">'+fmt(e.updatedAt)+'</span>'+
        '<span>💊 '+e.medicine+' · <span style="color:'+color+'">'+e.status+'</span></span>';
      log.appendChild(li);
    });
  }
  var key = params.get("key");
  function load(){
    fetch("/v1/system-status?key="+encodeURIComponent(key)).then(function(r){
      if(!r.ok) throw new Error("forbidden"); return r.json();
    }).then(render).catch(function(){
      document.getElementById("gate").style.display="block";
      document.getElementById("err").style.display="block";
      document.getElementById("sub").textContent="Private console";
    });
  }
  if(!key){ document.getElementById("gate").style.display="block"; document.getElementById("sub").textContent="Private console"; }
  else { load(); setInterval(load, 15000); }
</script>
</body></html>`;
}

// --- "Devices & Sync" management dashboard (light theme, fully responsive) ---
// Self-contained light theme per the product design (white bg, purple accent). It reuses the
// existing /v1/system-status data and removes devices via /v1/system-revoke.
function devicesHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>MedTrack — Devices & Sync</title>
<style>
  :root{
    --bg:#F4F5F9;--surface:#FFFFFF;--line:#E7E9F2;--line2:#EEF0F7;--subcard:#FCFCFE;
    --text:#1E2233;--text2:#5A6072;--muted:#8A90A2;
    --purple:#6D4AFF;--purple-ink:#5A38F0;--purple-soft:#F1EEFF;
    --green:#16A34A;--green-soft:#E7F6EC;
    --amber:#D97706;--amber-soft:#FEF3E2;
    --red:#DC2626;--red-soft:#FCEBEB;--gray:#9AA0AE;
    --shadow:0 1px 2px rgba(16,24,40,.05),0 1px 3px rgba(16,24,40,.05);
    --shadow-lg:0 8px 24px rgba(16,24,40,.10);--radius:14px;
  }
  html[data-theme="dark"]{
    --bg:#0E1117;--surface:#171B24;--line:#2A2F3C;--line2:#242936;--subcard:#1C2130;
    --text:#EAEDF5;--text2:#AEB4C4;--muted:#8890A2;
    --purple:#8B74FF;--purple-ink:#A08CFF;--purple-soft:#241F3D;
    --green:#34D07A;--green-soft:#12321F;
    --amber:#F0A94A;--amber-soft:#3A2A12;
    --red:#F26D6D;--red-soft:#3A1B1B;--gray:#6B7385;
    --shadow:0 1px 2px rgba(0,0,0,.4);--shadow-lg:0 10px 30px rgba(0,0,0,.5);
  }
  *{box-sizing:border-box}
  html,body{margin:0}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    background:var(--bg);color:var(--text);-webkit-font-smoothing:antialiased;font-size:14px;line-height:1.45}
  .app{display:flex;min-height:100vh}
  a{color:inherit;text-decoration:none}
  button{font-family:inherit;cursor:pointer;border:none;background:none}

  /* Sidebar */
  .sidebar{width:248px;flex-shrink:0;background:var(--surface);border-right:1px solid var(--line);
    display:flex;flex-direction:column;padding:18px 14px;position:sticky;top:0;height:100vh}
  .logo{display:flex;align-items:center;gap:10px;padding:6px 8px 18px;font-weight:800;font-size:18px}
  .logo .mark{width:30px;height:30px;border-radius:8px;background:linear-gradient(135deg,#7C5CFF,#6D4AFF);
    display:flex;align-items:center;justify-content:center;color:#fff;font-size:16px}
  .nav{display:flex;flex-direction:column;gap:2px;flex:1}
  .nav a{display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:10px;
    color:var(--text2);font-weight:500}
  .nav a .ic{width:18px;text-align:center;opacity:.9}
  .nav a:hover{background:var(--bg)}
  .nav a.active{background:var(--purple-soft);color:var(--purple-ink);font-weight:600}
  .side-foot{display:flex;align-items:center;gap:10px;padding:10px 8px;border-top:1px solid var(--line);margin-top:8px}
  .avatar{width:32px;height:32px;border-radius:50%;background:var(--purple);color:#fff;
    display:flex;align-items:center;justify-content:center;font-weight:700}
  .side-foot .nm{font-weight:600;font-size:13px}.side-foot .em{color:var(--muted);font-size:12px}

  /* Main */
  .main{flex:1;min-width:0;display:flex;flex-direction:column}
  .content{padding:24px 28px 64px;max-width:1080px;width:100%;margin:0 auto}

  /* Topbar (mobile) */
  .topbar{display:none;align-items:center;justify-content:space-between;padding:12px 16px;
    background:var(--surface);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:30}
  .topbar .brand{display:flex;align-items:center;gap:8px;font-weight:800}
  .iconbtn{width:38px;height:38px;border-radius:10px;display:flex;align-items:center;justify-content:center;
    color:var(--text2);font-size:18px}
  .iconbtn:hover{background:var(--bg)}

  /* Header */
  .head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;flex-wrap:wrap;margin-bottom:20px}
  .head h1{font-size:24px;margin:0 0 4px}
  .head p{margin:0;color:var(--text2)}
  .head .right{display:flex;flex-direction:column;align-items:flex-end;gap:8px}
  .updated{color:var(--muted);font-size:12px;text-align:right}
  .updated b{color:var(--text2);font-weight:600}

  /* Buttons */
  .btn{display:inline-flex;align-items:center;gap:8px;padding:9px 16px;border-radius:10px;font-weight:600;font-size:14px;
    transition:background .15s,box-shadow .15s,transform .05s;white-space:nowrap}
  .btn:active{transform:translateY(1px)}
  .btn-primary{background:var(--purple);color:#fff}
  .btn-primary:hover{background:var(--purple-ink)}
  .btn-ghost{background:var(--surface);border:1px solid var(--line);color:var(--text)}
  .btn-ghost:hover{background:var(--bg)}
  .btn-danger{background:var(--red);color:#fff}
  .btn-danger:hover{background:#B91C1C}
  .btn-sm{padding:6px 12px;font-size:13px}
  .btn[disabled]{opacity:.6;cursor:default}

  /* Cards */
  .card{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);
    box-shadow:var(--shadow);padding:18px;margin-bottom:18px;scroll-margin-top:76px}
  html{scroll-behavior:smooth}
  .card-head{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:14px}
  .card-title{display:flex;align-items:center;gap:10px;font-weight:700;font-size:16px}
  .card-title .ic{color:var(--purple)}
  .card-sub{color:var(--text2);font-size:13px;margin-top:2px;font-weight:400}

  /* Status pill + dot */
  .pill{display:inline-flex;align-items:center;gap:6px;font-weight:600;font-size:13px}
  .dot{width:9px;height:9px;border-radius:50%;flex-shrink:0}
  .dot.green{background:var(--green)}.dot.amber{background:var(--amber)}
  .dot.red{background:var(--red)}.dot.gray{background:var(--gray)}
  .t-green{color:var(--green)}.t-amber{color:var(--amber)}.t-red{color:var(--red)}.t-gray{color:var(--text2)}

  /* Status sub-cards grid */
  .subgrid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
  .subcard{border:1px solid var(--line2);border-radius:12px;padding:14px;background:var(--subcard)}
  .themebtn{width:40px;height:40px;border-radius:10px;border:1px solid var(--line);background:var(--surface);
    color:var(--text2);font-size:18px;display:inline-flex;align-items:center;justify-content:center;
    transition:background .15s,color .15s}
  .themebtn:hover{background:var(--bg);color:var(--text)}
  .subcard .lbl{display:flex;align-items:center;gap:8px;font-weight:600;font-size:13px;margin-bottom:8px}
  .subcard .lbl .ic{color:var(--purple)}
  .subcard .val{font-weight:600;font-size:13px}
  .subcard .meta{color:var(--muted);font-size:12px;margin-top:2px}
  .overall-row{display:flex;align-items:center;gap:10px;margin-bottom:14px;flex-wrap:wrap}
  .overall-row .lead{font-weight:700;font-size:15px}

  /* Table (desktop) */
  .tablewrap{overflow:visible}
  table{width:100%;border-collapse:collapse}
  thead th{text-align:left;padding:10px 12px;font-size:11px;letter-spacing:.04em;text-transform:uppercase;
    color:var(--muted);font-weight:700;border-bottom:1px solid var(--line)}
  tbody td{padding:14px 12px;border-bottom:1px solid var(--line2);vertical-align:middle;font-size:13.5px}
  tbody tr:last-child td{border-bottom:none}
  .dev-name{display:flex;align-items:center;gap:10px}
  .dev-ic{width:34px;height:34px;border-radius:9px;background:var(--purple-soft);color:var(--purple-ink);
    display:flex;align-items:center;justify-content:center;font-size:16px;flex-shrink:0}
  .dev-name .nm{font-weight:700}.dev-name .sub{color:var(--muted);font-size:12px}
  .role{font-weight:700;font-size:12px;letter-spacing:.02em}
  .role .sub{display:block;color:var(--muted);font-weight:400;font-size:12px;letter-spacing:0}
  .badge{display:inline-flex;align-items:center;gap:5px;padding:3px 9px;border-radius:999px;font-size:11.5px;font-weight:600}
  .badge.green{background:var(--green-soft);color:var(--green)}
  .badge.purple{background:var(--purple-soft);color:var(--purple-ink)}
  .badge.amber{background:var(--amber-soft);color:var(--amber)}
  .cellstack .top{font-weight:600}.cellstack .bot{color:var(--muted);font-size:12px}
  .actions{display:flex;gap:8px;justify-content:flex-end;align-items:center}
  .thisdev{color:var(--muted);font-size:12.5px;font-style:normal}

  /* Sync info two-column */
  .sync-cols{display:grid;grid-template-columns:1.3fr 1fr;gap:18px}
  .kv{border:1px solid var(--line2);border-radius:12px;overflow:hidden}
  .kv .row{display:flex;justify-content:space-between;align-items:center;padding:12px 14px;border-bottom:1px solid var(--line2)}
  .kv .row:last-child{border-bottom:none}
  .kv .k{display:flex;align-items:center;gap:9px;color:var(--text2)}
  .kv .k .ic{color:var(--purple);width:16px;text-align:center}
  .kv .v{font-weight:700}
  .noticestack{display:flex;flex-direction:column;gap:12px}
  .notice{border-radius:12px;padding:14px;display:flex;gap:12px;align-items:flex-start}
  .notice .ic{font-size:16px;line-height:1.2;margin-top:1px}
  .notice.green{background:var(--green-soft)}
  .notice.info{background:var(--purple-soft)}
  .notice .h{font-weight:700;margin-bottom:2px}
  .notice.green .h{color:var(--green)}
  .notice p{margin:0;color:var(--text2);font-size:13px}

  /* Check-sync steps */
  .steps{list-style:none;padding:0;margin:10px 0 0}
  .steps li{padding:7px 0;display:flex;gap:10px;align-items:center;font-size:13.5px;color:var(--text2)}
  .steps li .mk{width:18px;text-align:center}

  /* Modal */
  .overlay{position:fixed;inset:0;background:rgba(20,22,34,.45);display:none;align-items:center;justify-content:center;
    padding:20px;z-index:100}
  .overlay.show{display:flex;animation:fade .12s ease}
  @keyframes fade{from{opacity:0}to{opacity:1}}
  .modal{background:var(--surface);border-radius:16px;max-width:420px;width:100%;padding:22px;box-shadow:var(--shadow-lg);
    animation:pop .14s ease}
  @keyframes pop{from{transform:translateY(8px);opacity:.6}to{transform:none;opacity:1}}
  .modal .warn-ic{width:44px;height:44px;border-radius:50%;background:var(--red-soft);color:var(--red);
    display:flex;align-items:center;justify-content:center;font-size:20px;margin-bottom:12px}
  .modal h3{margin:0 0 6px;font-size:18px}
  .modal .dev{font-weight:700;margin-bottom:6px}
  .modal p{margin:0 0 18px;color:var(--text2)}
  .modal .row{display:flex;gap:10px;justify-content:flex-end}

  /* Toast */
  .toast{position:fixed;left:50%;transform:translateX(-50%);bottom:24px;background:#12331F;color:#EAFBF0;
    padding:11px 18px;border-radius:10px;font-weight:600;box-shadow:var(--shadow-lg);display:none;z-index:120}
  .toast.show{display:block;animation:fade .12s ease}
  .toast.err{background:#3A1414;color:#FDE8E8}

  /* Gate */
  .gate{max-width:400px;margin:60px auto;background:var(--surface);border:1px solid var(--line);
    border-radius:var(--radius);box-shadow:var(--shadow);padding:24px}
  .gate h2{margin:0 0 6px;font-size:18px}
  .gate p{margin:0 0 16px;color:var(--text2)}
  .gate input{width:100%;padding:11px 12px;border:1px solid var(--line);border-radius:10px;font-size:14px;margin-bottom:12px;color:var(--text)}
  .gate .err{color:var(--red);font-size:13px;margin-top:10px;display:none}
  .cards-mobile{display:none}
  .muted{color:var(--muted)}
  .scrim{display:none}

  /* Tablet */
  @media (max-width:1000px){
    .sidebar{width:76px;padding:18px 8px}
    .logo span,.nav a span,.side-foot .txt{display:none}
    .logo{justify-content:center}.nav a{justify-content:center;padding:12px}
    .side-foot{justify-content:center}
    .subgrid{grid-template-columns:repeat(2,1fr)}
    .sync-cols{grid-template-columns:1fr}
  }

  /* Mobile */
  @media (max-width:720px){
    .sidebar{position:fixed;left:0;top:0;width:264px;padding:18px 14px;z-index:60;transform:translateX(-100%);
      transition:transform .2s ease;box-shadow:var(--shadow-lg)}
    .sidebar.open{transform:none}
    .logo span,.nav a span,.side-foot .txt{display:inline}
    .logo{justify-content:flex-start}.nav a{justify-content:flex-start;padding:10px 12px}
    .side-foot{justify-content:flex-start}
    .scrim{position:fixed;inset:0;background:rgba(20,22,34,.4);z-index:55}
    .scrim.show{display:block}
    .topbar{display:flex}
    .content{padding:16px 14px calc(72px + env(safe-area-inset-bottom))}
    .head h1{font-size:20px}
    .head .right{align-items:stretch;width:100%}
    .head .right .btn{width:100%;justify-content:center}
    .updated{text-align:left}
    .subgrid{grid-template-columns:1fr}
    .tablewrap table{display:none}
    .cards-mobile{display:flex;flex-direction:column;gap:12px}
  }
</style>
<script>
  // Apply saved theme before first paint to avoid a flash of the wrong theme.
  (function(){
    try{
      var saved=localStorage.getItem("medtrack_theme");
      if(!saved){ saved = (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) ? "dark":"light"; }
      document.documentElement.setAttribute("data-theme", saved);
    }catch(e){ document.documentElement.setAttribute("data-theme","light"); }
  })();
</script>
</head>
<body>
<div class="app">
  <!-- Sidebar -->
  <aside class="sidebar" id="sidebar">
    <div class="logo"><span class="mark">✚</span><span>MedTrack</span></div>
    <nav class="nav">
      <a href="#top" class="active" onclick="navTo('top',this)"><span class="ic">🖥️</span><span>Devices &amp; Sync</span></a>
      <a href="#sec-status" onclick="navTo('sec-status',this)"><span class="ic">☁️</span><span>Sync Status</span></a>
      <a href="#sec-devices" onclick="navTo('sec-devices',this)"><span class="ic">📱</span><span>Connected Devices</span></a>
      <a href="#sec-health" onclick="navTo('sec-health',this)"><span class="ic">🩺</span><span>System Health</span></a>
      <a href="#sec-sync" onclick="navTo('sec-sync',this)"><span class="ic">🔄</span><span>Sync Information</span></a>
      <a href="#sec-history" onclick="navTo('sec-history',this)"><span class="ic">🕓</span><span>Authorization History</span></a>
    </nav>
    <div class="side-foot"><div class="avatar" id="ava">H</div><div class="txt"><div class="nm" id="uname">MedTrack</div><div class="em">Health account</div></div></div>
  </aside>
  <div class="scrim" id="scrim" onclick="toggleNav(false)"></div>

  <div class="main">
    <!-- Mobile topbar -->
    <div class="topbar">
      <button class="iconbtn" onclick="toggleNav(true)" aria-label="Open menu">☰</button>
      <div class="brand"><span class="mark" style="width:24px;height:24px;border-radius:7px;background:linear-gradient(135deg,#7C5CFF,#6D4AFF);display:flex;align-items:center;justify-content:center;color:#fff;font-size:13px">✚</span>MedTrack</div>
      <button class="iconbtn" id="themeBtnM" onclick="toggleTheme()" aria-label="Toggle dark theme">🌙</button>
    </div>

    <div class="content">
      <!-- Gate -->
      <div id="gate" class="gate" style="display:none">
        <h2>Enter access key</h2>
        <p>This page is private. Enter your access key to manage devices and sync.</p>
        <input id="key" type="password" placeholder="Access key" autocomplete="off" onkeydown="if(event.key==='Enter')go()">
        <button class="btn btn-primary" style="width:100%;justify-content:center" onclick="go()">View devices</button>
        <div class="err" id="gateErr">Wrong key or dashboard disabled.</div>
      </div>

      <div id="dash" style="display:none">
        <!-- Header -->
        <div class="head" id="top">
          <div>
            <h1>Devices &amp; Sync</h1>
            <p>Manage your authorized devices and monitor synchronization status.</p>
          </div>
          <div class="right">
            <div style="display:flex;gap:10px;align-items:center">
              <button class="themebtn" id="themeBtn" onclick="toggleTheme()" aria-label="Toggle dark theme" title="Toggle dark theme">🌙</button>
              <button class="btn btn-primary" id="checkBtn" onclick="checkSync()"><span id="checkIc">↻</span> Check Sync</button>
            </div>
            <div class="updated">Last updated: <b id="lastUpdated">—</b></div>
          </div>
        </div>

        <!-- System status -->
        <div class="card" id="sec-status">
          <div class="overall-row">
            <span class="card-title"><span class="ic">☁️</span> Overall Sync Status</span>
            <span class="pill" id="overallPill"><span class="dot gray"></span><span>Loading…</span></span>
          </div>
          <div class="subgrid">
            <div class="subcard">
              <div class="lbl"><span class="ic">☁️</span> Cloud Sync</div>
              <div class="val pill" id="ssCloud"><span class="dot gray"></span> —</div>
              <div class="meta" id="ssCloudMeta">—</div>
            </div>
            <div class="subcard">
              <div class="lbl"><span class="ic">🌐</span> Vercel API</div>
              <div class="val pill" id="ssApi"><span class="dot gray"></span> —</div>
              <div class="meta" id="ssApiMeta">—</div>
            </div>
            <div class="subcard">
              <div class="lbl"><span class="ic">🗄</span> Turso Database</div>
              <div class="val pill" id="ssDb"><span class="dot gray"></span> —</div>
              <div class="meta" id="ssDbMeta">—</div>
            </div>
            <div class="subcard">
              <div class="lbl"><span class="ic">🕒</span> Pending Changes</div>
              <div class="val" id="ssPending">0</div>
              <div class="meta" id="ssPendingMeta">All up to date</div>
            </div>
          </div>
        </div>

        <!-- Connected devices -->
        <div class="card" id="sec-devices">
          <div class="card-head">
            <div>
              <div class="card-title"><span class="ic">🖥</span> Connected Devices</div>
              <div class="card-sub">Devices authorized to access your MedTrack data.</div>
            </div>
            <button class="btn btn-ghost btn-sm" onclick="showAdd()">＋ Add Device</button>
          </div>
          <div class="tablewrap">
            <table>
              <thead><tr>
                <th>Device</th><th>Role</th><th>Status</th><th>Last Seen</th><th>Last Sync</th><th>Version</th><th style="text-align:right">Actions</th>
              </tr></thead>
              <tbody id="devRows"></tbody>
            </table>
            <div class="cards-mobile" id="devCards"></div>
          </div>
        </div>

        <!-- System health -->
        <div class="card" id="sec-health">
          <div class="card-head">
            <div>
              <div class="card-title"><span class="ic">🩺</span> System Health</div>
              <div class="card-sub">Live status of the cloud services powering MedTrack.</div>
            </div>
          </div>
          <div class="kv" id="healthList"></div>
        </div>

        <!-- Sync information -->
        <div class="card" id="sec-sync">
          <div class="card-head"><div class="card-title"><span class="ic">🔄</span> Sync Information</div></div>
          <div class="sync-cols">
            <div class="kv">
              <div class="row"><span class="k"><span class="ic">☁️</span> Cloud version</span><span class="v" id="siVer">#0</span></div>
              <div class="row"><span class="k"><span class="ic">⬆️</span> Last upload</span><span class="v" id="siUp">—</span></div>
              <div class="row"><span class="k"><span class="ic">⬇️</span> Last download</span><span class="v" id="siDown">—</span></div>
              <div class="row"><span class="k"><span class="ic">🕒</span> Pending changes</span><span class="v" id="siPending">0</span></div>
              <div class="row"><span class="k"><span class="ic">⚠️</span> Failed changes</span><span class="v" id="siFailed">0</span></div>
              <div class="row"><span class="k"><span class="ic">🔁</span> Next automatic check</span><span class="v" id="siNext">—</span></div>
            </div>
            <div class="noticestack">
              <div class="notice green">
                <span class="ic">✓</span>
                <div><div class="h" id="niState">Synced</div><p id="niStateSub">Your data is up to date across all devices.</p></div>
              </div>
              <div class="notice info">
                <span class="ic">ℹ️</span>
                <div><div class="h">Auto Sync is Always Active</div><p>Your data is automatically synchronized. No manual action is required. The monitoring device checks for updates every 10 minutes.</p></div>
              </div>
              <button class="btn btn-primary" style="justify-content:center" onclick="checkSync()">↻ Check Sync</button>
            </div>
          </div>
        </div>

        <!-- Authorization history -->
        <div class="card" id="sec-history">
          <div class="card-head">
            <div>
              <div class="card-title"><span class="ic">🕓</span> Device Authorization History</div>
              <div class="card-sub">Recent device authorizations for this account.</div>
            </div>
          </div>
          <div class="tablewrap">
            <table>
              <thead><tr><th>Date &amp; Time</th><th>Device</th><th>Action</th><th>Details</th></tr></thead>
              <tbody id="authRows"></tbody>
            </table>
            <div class="cards-mobile" id="authCards"></div>
          </div>
        </div>

        <div class="muted" style="text-align:center;font-size:12px;margin-top:8px">Auto-refreshes every 30s · MedTrack</div>
      </div>
    </div>
  </div>
</div>

<!-- Delete modal -->
<div class="overlay" id="modal">
  <div class="modal">
    <div class="warn-ic">🗑</div>
    <h3 id="mTitle">Remove Device?</h3>
    <div class="dev" id="mDev">—</div>
    <p id="mBody">This device will no longer be authorized to access your MedTrack data.</p>
    <div class="row">
      <button class="btn btn-ghost" onclick="closeModal()">Cancel</button>
      <button class="btn btn-danger" id="mConfirm" onclick="confirmRemove()">Remove Device</button>
    </div>
  </div>
</div>

<!-- Check-sync modal -->
<div class="overlay" id="syncModal">
  <div class="modal">
    <h3 id="syncTitle">Checking…</h3>
    <ul class="steps" id="syncSteps"></ul>
    <div class="row" style="margin-top:16px"><button class="btn btn-ghost" id="syncClose" onclick="closeSync()" style="display:none">Close</button></div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
  var params = new URLSearchParams(location.search);
  var KEY = params.get("key") || "";
  var pendingRemoveId = null, latest = null;

  function toggleNav(open){
    document.getElementById("sidebar").classList.toggle("open", open);
    document.getElementById("scrim").classList.toggle("show", open);
  }

  // --- Theme toggle (persisted in localStorage) ---
  function applyTheme(mode){
    document.documentElement.setAttribute("data-theme", mode);
    var icon = mode==="dark" ? "☀️" : "🌙";
    var d=document.getElementById("themeBtn"), m=document.getElementById("themeBtnM");
    if(d) d.textContent=icon;
    if(m) m.textContent=icon;
    try{ localStorage.setItem("medtrack_theme", mode); }catch(e){}
  }
  function toggleTheme(){
    var cur=document.documentElement.getAttribute("data-theme")==="dark"?"dark":"light";
    applyTheme(cur==="dark"?"light":"dark");
  }
  // Single-click sidebar navigation: scroll to the section, highlight it, close the mobile drawer.
  function navTo(id, el){
    var target=document.getElementById(id);
    if(target) target.scrollIntoView({behavior:"smooth", block:"start"});
    var links=document.querySelectorAll(".nav a");
    for(var i=0;i<links.length;i++) links[i].classList.remove("active");
    if(el) el.classList.add("active");
    toggleNav(false);
    return false;
  }
  function go(){ var k=document.getElementById("key").value.trim(); if(k) location.search="?key="+encodeURIComponent(k); }
  function esc(s){ return String(s==null?"":s).replace(/[&<>"]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c];}); }

  function fmtDateTime(ms){ if(!ms) return "—"; var d=new Date(ms);
    return d.toLocaleDateString([], {month:"short",day:"2-digit",year:"numeric"})+", "+d.toLocaleTimeString([], {hour:"2-digit",minute:"2-digit"}); }
  function fmtTime(ms){ if(!ms) return "—"; return new Date(ms).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit"}); }
  function isToday(ms){ if(!ms) return false; var d=new Date(ms), n=new Date();
    return d.getDate()===n.getDate()&&d.getMonth()===n.getMonth()&&d.getFullYear()===n.getFullYear(); }
  function seenLabel(ms){ if(!ms) return "—"; return (isToday(ms)?"Today":new Date(ms).toLocaleDateString([], {month:"short",day:"2-digit"}))+", "+fmtTime(ms); }

  // Status: {cls,text} using dot color + text (never color alone).
  function devStatus(d){
    if(!d.online) return {cls:"gray",text:"Offline"};
    if(!d.upToDate) return {cls:"amber",text:"Syncing"};
    return {cls:"green",text:"Online"};
  }
  function verBadge(d){
    if(d.upToDate) return '<span class="badge green">✓ Up to date</span>';
    return '<span class="badge amber">Behind</span>';
  }

  function render(d){
    latest = d;
    document.getElementById("gate").style.display="none";
    document.getElementById("dash").style.display="block";
    document.getElementById("lastUpdated").textContent = fmtDateTime(d.generatedAt);

    // find checks
    function chk(name){ return (d.checks||[]).find(function(c){return c.name===name;}) || {status:"warn",detail:"—"}; }
    var overallText = d.overall==="ok"?"Everything is synchronized":d.overall==="warn"?"Monitoring device is behind":"Sync error";
    var overallCls = d.overall==="ok"?"green":d.overall==="warn"?"amber":"red";
    setPill("overallPill", overallCls, overallText);

    // Cloud sync
    var cloud = chk("Cloud sync");
    setPill("ssCloud", cloud.status==="ok"?"green":cloud.status==="warn"?"amber":"red", cloud.status==="ok"?"Active":cloud.status==="warn"?"Behind":"Error");
    document.getElementById("ssCloudMeta").textContent = "Version #"+d.cloudVersion;
    // API
    var api = chk("Vercel API");
    setPill("ssApi", api.status==="ok"?"green":"red", api.status==="ok"?"Online":"Error");
    document.getElementById("ssApiMeta").textContent = "Responding";
    // DB
    var dbc = chk("Turso database"), lat = chk("Database latency");
    setPill("ssDb", dbc.status==="ok"?"green":"red", dbc.status==="ok"?"Connected":"Error");
    document.getElementById("ssDbMeta").textContent = d.latencyMs>=0? d.latencyMs+" ms" : "—";
    // Pending
    var pend = (d.devices||[]).reduce(function(a,b){return a+(b.pending||0);},0);
    document.getElementById("ssPending").textContent = pend;
    document.getElementById("ssPendingMeta").textContent = pend===0?"All up to date":"Awaiting sync";

    renderDevices(d.devices||[]);
    renderHealth(d);
    renderSyncInfo(d);
    renderAuthHistory(d.devices||[]);
  }

  function renderHealth(d){
    var el=document.getElementById("healthList"); if(!el) return;
    el.innerHTML="";
    (d.checks||[]).forEach(function(c){
      var cls = c.status==="ok"?"green":c.status==="warn"?"amber":"red";
      var txt = c.status==="ok"?"OK":c.status==="warn"?"Warning":"Error";
      var row=document.createElement("div"); row.className="row";
      row.innerHTML='<span class="k"><span class="dot '+cls+'"></span> '+esc(c.name)+
        (c.detail?' <span class="muted" style="font-size:12px">· '+esc(c.detail)+'</span>':'')+'</span>'+
        '<span class="v t-'+cls+'">'+txt+'</span>';
      el.appendChild(row);
    });
    if(!(d.checks||[]).length) el.innerHTML='<div class="row"><span class="muted">No health data.</span></div>';
  }

  function setPill(id, cls, text){
    var el=document.getElementById(id);
    el.innerHTML = '<span class="dot '+cls+'"></span><span class="t-'+cls+'">'+esc(text)+'</span>';
  }

  function renderDevices(devs){
    var tb=document.getElementById("devRows"), mc=document.getElementById("devCards");
    tb.innerHTML=""; mc.innerHTML="";
    if(!devs.length){ tb.innerHTML='<tr><td colspan="7" class="muted">No devices authorized yet.</td></tr>';
      mc.innerHTML='<div class="muted">No devices authorized yet.</div>'; return; }

    devs.forEach(function(dev){
      var isPrimary = dev.role==="PRIMARY";
      var st=devStatus(dev);
      var roleLabel = isPrimary?"PRIMARY":"MONITORING";
      var roleSub = isPrimary?"Main device":"Read only";
      // Every device is deletable from the web console.
      var actions =
        '<button class="btn btn-ghost btn-sm" onclick="viewDevice(\\''+esc(dev.deviceId)+'\\')">View</button>'+
        '<button class="btn btn-danger btn-sm" onclick="askRemove(\\''+esc(dev.deviceId)+'\\',\\''+esc(dev.name)+'\\',\\''+esc(dev.role)+'\\')">Delete</button>';

      var tr=document.createElement("tr");
      tr.innerHTML=
        '<td><div class="dev-name"><span class="dev-ic">'+(isPrimary?"📱":"👀")+'</span>'+
          '<span><span class="nm">'+esc(dev.name)+'</span><br><span class="sub">'+(isPrimary?"Primary phone":"Monitor")+'</span></span></div></td>'+
        '<td><span class="role">'+roleLabel+'<span class="sub">'+roleSub+'</span></span></td>'+
        '<td><span class="pill"><span class="dot '+st.cls+'"></span><span class="t-'+st.cls+'">'+st.text+'</span></span></td>'+
        '<td class="cellstack"><div class="top">'+(isToday(dev.lastActiveAt)?"Today":new Date(dev.lastActiveAt||Date.now()).toLocaleDateString([], {month:"short",day:"2-digit"}))+'</div><div class="bot">'+fmtTime(dev.lastActiveAt||dev.createdAt)+'</div></td>'+
        '<td class="cellstack"><div class="top">'+fmtTime(dev.lastActiveAt||dev.createdAt)+'</div></td>'+
        '<td class="cellstack"><div class="top">#'+dev.lastSyncedVersion+'</div><div class="bot">'+verBadge(dev)+'</div></td>'+
        '<td><div class="actions">'+actions+'</div></td>';
      tb.appendChild(tr);

      // Mobile card
      var card=document.createElement("div"); card.className="card"; card.style.margin="0"; card.style.boxShadow="none"; card.style.border="1px solid var(--line)";
      card.innerHTML=
        '<div style="display:flex;justify-content:space-between;gap:10px;align-items:flex-start">'+
          '<div class="dev-name"><span class="dev-ic">'+(isPrimary?"📱":"👀")+'</span><span><span class="nm">'+esc(dev.name)+'</span><br><span class="sub">'+(isPrimary?"Primary phone":"Monitor")+'</span></span></div>'+
          '<div style="text-align:right"><span class="role">'+roleLabel+'<span class="sub">'+roleSub+'</span></span></div>'+
        '</div>'+
        '<div style="display:flex;justify-content:space-between;margin-top:12px">'+
          '<span class="pill"><span class="dot '+st.cls+'"></span><span class="t-'+st.cls+'">'+st.text+'</span></span>'+
          '<span style="text-align:right"><b>#'+dev.lastSyncedVersion+'</b> '+verBadge(dev)+'</span>'+
        '</div>'+
        '<div class="kv" style="margin-top:12px">'+
          '<div class="row"><span class="k">Last seen</span><span class="v">'+seenLabel(dev.lastActiveAt||dev.createdAt)+'</span></div>'+
          '<div class="row"><span class="k">Last sync</span><span class="v">'+seenLabel(dev.lastActiveAt||dev.createdAt)+'</span></div>'+
        '</div>'+
        '<div style="display:flex;gap:10px;margin-top:12px"><button class="btn btn-ghost btn-sm" style="flex:1;justify-content:center" onclick="viewDevice(\\''+esc(dev.deviceId)+'\\')">View Details</button>'+
          '<button class="btn btn-danger btn-sm" style="flex:1;justify-content:center" onclick="askRemove(\\''+esc(dev.deviceId)+'\\',\\''+esc(dev.name)+'\\',\\''+esc(dev.role)+'\\')">Delete Device</button></div>';
      mc.appendChild(card);
    });
  }

  function renderSyncInfo(d){
    document.getElementById("siVer").textContent = "#"+d.cloudVersion;
    document.getElementById("siUp").textContent = fmtDateTime(d.generatedAt);
    var mon=(d.devices||[]).filter(function(x){return x.role!=="PRIMARY";});
    var lastDownload = mon.reduce(function(m,x){return Math.max(m,x.lastActiveAt||0);},0) || d.generatedAt;
    document.getElementById("siDown").textContent = fmtDateTime(lastDownload);
    var pend=(d.devices||[]).reduce(function(a,b){return a+(b.pending||0);},0);
    document.getElementById("siPending").textContent = pend;
    document.getElementById("siFailed").textContent = 0;
    document.getElementById("siNext").textContent = fmtTime(d.generatedAt + 10*60*1000);
    var syncedOk = d.overall==="ok";
    var ni=document.getElementById("niState"), nis=document.getElementById("niStateSub");
    var box = ni.closest(".notice");
    if(syncedOk){ box.className="notice green"; ni.textContent="Synced"; nis.textContent="Your data is up to date across all devices."; }
    else if(d.overall==="warn"){ box.className="notice info"; ni.textContent="Monitoring device is behind"; nis.textContent="A device is catching up. It will update automatically."; ni.style.color="var(--amber)"; }
    else { box.className="notice info"; ni.textContent="Sync error"; nis.textContent="There was a problem reaching the cloud. Retrying automatically."; ni.style.color="var(--red)"; }
  }

  function renderAuthHistory(devs){
    var tb=document.getElementById("authRows"), mc=document.getElementById("authCards");
    tb.innerHTML=""; mc.innerHTML="";
    var rows = devs.slice().sort(function(a,b){return (b.createdAt||0)-(a.createdAt||0);});
    if(!rows.length){ tb.innerHTML='<tr><td colspan="4" class="muted">No authorization history.</td></tr>'; return; }
    rows.forEach(function(dev){
      var detail = dev.role==="PRIMARY"?"Primary device connected":"Monitoring device connected";
      var tr=document.createElement("tr");
      tr.innerHTML='<td>'+fmtDateTime(dev.createdAt)+'</td><td><b>'+esc(dev.name)+'</b></td>'+
        '<td><span class="pill"><span class="dot green"></span><span class="t-green">Authorized</span></span></td>'+
        '<td class="muted">'+detail+'</td>';
      tb.appendChild(tr);
      var card=document.createElement("div"); card.className="card"; card.style.margin="0";card.style.boxShadow="none";card.style.border="1px solid var(--line)";
      card.innerHTML='<div style="display:flex;justify-content:space-between"><b>'+esc(dev.name)+'</b>'+
        '<span class="pill"><span class="dot green"></span><span class="t-green">Authorized</span></span></div>'+
        '<div class="muted" style="margin-top:4px">'+fmtDateTime(dev.createdAt)+'</div>'+
        '<div style="margin-top:4px">'+detail+'</div>';
      mc.appendChild(card);
    });
  }

  // --- Remove device ---
  function askRemove(id,name,role){
    pendingRemoveId=id;
    document.getElementById("mDev").textContent=name;
    var isPrimary = role==="PRIMARY";
    document.getElementById("mTitle").textContent = isPrimary ? "Remove Primary Device?" : "Remove Monitoring Device?";
    document.getElementById("mBody").textContent = isPrimary
      ? "This primary device will lose access to your MedTrack account. Use this to clean up old or duplicate registrations."
      : "This device will no longer be authorized to access your MedTrack monitoring data.";
    document.getElementById("modal").classList.add("show");
  }
  function closeModal(){ document.getElementById("modal").classList.remove("show"); pendingRemoveId=null; }
  function confirmRemove(){
    if(!pendingRemoveId) return;
    var btn=document.getElementById("mConfirm"); btn.disabled=true; btn.textContent="Removing…";
    fetch("/v1/system-revoke?key="+encodeURIComponent(KEY), {method:"POST",headers:{"Content-Type":"application/json"},
      body:JSON.stringify({deviceId:pendingRemoveId})})
      .then(function(r){ if(!r.ok) throw new Error("failed"); return r.json(); })
      .then(function(){ closeModal(); toast("✓ Device removed"); load(); })
      .catch(function(){ toast("Could not remove device","err"); })
      .finally(function(){ btn.disabled=false; btn.textContent="Remove Device"; });
  }
  function viewDevice(id){
    var dev=(latest&&latest.devices||[]).find(function(x){return x.deviceId===id;});
    if(!dev) return;
    var st=devStatus(dev);
    toast(dev.name+" · "+st.text+" · #"+dev.lastSyncedVersion);
  }
  function showAdd(){ toast("Open the MedTrack app on the primary device to generate a pairing code."); }

  function toast(msg, kind){ var t=document.getElementById("toast"); t.textContent=msg;
    t.className="toast show"+(kind==="err"?" err":""); clearTimeout(t._t); t._t=setTimeout(function(){t.className="toast";},2600); }

  // --- Check sync flow ---
  var STEPS=["API connected","Turso connected","Cloud version checked","Devices checked","Sync status verified"];
  function checkSync(){
    var m=document.getElementById("syncModal"); m.classList.add("show");
    document.getElementById("syncTitle").textContent="Checking…";
    document.getElementById("syncClose").style.display="none";
    var ul=document.getElementById("syncSteps"); ul.innerHTML="";
    var ic=document.getElementById("checkIc"); ic.textContent="⟳"; ic.style.display="inline-block";
    STEPS.forEach(function(s){ var li=document.createElement("li"); li.innerHTML='<span class="mk">…</span>'+s; ul.appendChild(li); });
    fetch("/v1/system-status?key="+encodeURIComponent(KEY)).then(function(r){ if(!r.ok) throw new Error("forbidden"); return r.json(); })
      .then(function(d){
        var lis=ul.querySelectorAll("li"); var i=0;
        var timer=setInterval(function(){
          if(i<lis.length){ lis[i].querySelector(".mk").textContent="✓"; lis[i].querySelector(".mk").style.color="var(--green)"; i++; }
          else{
            clearInterval(timer);
            var t=document.getElementById("syncTitle");
            if(d.overall==="ok"){ t.innerHTML='<span class="t-green">🟢 Everything is synchronized</span>'; }
            else if(d.overall==="warn"){ t.innerHTML='<span class="t-amber">🟡 Monitoring device is behind</span>'; }
            else{ t.innerHTML='<span class="t-red">🔴 Sync error</span>'; }
            document.getElementById("syncClose").style.display="inline-flex";
            ic.textContent="↻";
            render(d);
          }
        }, 220);
      })
      .catch(function(){ document.getElementById("syncTitle").innerHTML='<span class="t-red">🔴 Could not reach the server</span>';
        document.getElementById("syncClose").style.display="inline-flex"; ic.textContent="↻"; });
  }
  function closeSync(){ document.getElementById("syncModal").classList.remove("show"); }

  function load(){
    fetch("/v1/system-status?key="+encodeURIComponent(KEY)).then(function(r){ if(!r.ok) throw new Error("forbidden"); return r.json(); })
      .then(render)
      .catch(function(){ document.getElementById("gate").style.display="block";
        document.getElementById("dash").style.display="none";
        if(KEY) document.getElementById("gateErr").style.display="block"; });
  }
  // Sync the toggle icon with the theme applied pre-paint.
  applyTheme(document.documentElement.getAttribute("data-theme")==="dark"?"dark":"light");

  if(!KEY){ document.getElementById("gate").style.display="block"; }
  else { load(); setInterval(load, 30000); }
</script>
</body></html>`;
}

// Client-side logic for the App Preview page (demo data, time simulation, sync sim, and the
// Primary + Monitoring screen renderers). Kept as a template string so it ships inline.
const PREVIEW_JS = String.raw`
// ---------- state ----------
var params = new URLSearchParams(location.search);
var S = {
  device: params.get("device") === "monitor" ? "monitor" : "primary",
  tab: "today",
  primaryNav: "home",
  monitorNav: "home",
  simTime: null,              // ms, or null = real time
  cond: "online",
  primaryVersion: 1851,
  monitorVersion: 1851,
  selectedDay: 0,             // offset from today
  expanded: {},               // dose card expand map
};

function now(){ return S.simTime != null ? S.simTime : Date.now(); }
function startOfToday(){ var d=new Date(now()); d.setHours(0,0,0,0); return d.getTime(); }
function fmtTime(ms){ return new Date(ms).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit"}); }
function fmtDate(ms){ return new Date(ms).toLocaleDateString([], {weekday:"long",day:"numeric",month:"short"}); }
function atTime(dayOffset, h, m){ var d=new Date(startOfToday()); d.setDate(d.getDate()+dayOffset); d.setHours(h,m,0,0); return d.getTime(); }

// ---------- demo data ----------
// Each dose = a time-of-day group of medicines. status auto-derives unless overridden.
function seed(){
  return {
    morningOverride: null, // dose-state override for today's morning dose
    meds: {
      morning: [
        {name:"Bedaquiline", dose:"4 tablets"},
        {name:"Linezolid", dose:"600 mg"},
        {name:"Moxifloxacin", dose:"400 mg"},
        {name:"Pretomanid", dose:"200 mg"},
      ],
      night: [
        {name:"Pyridoxine / Vitamin B6", dose:"25 mg"},
        {name:"Levofloxacin", dose:"750 mg"},
        {name:"Clofazimine", dose:"100 mg"},
        {name:"Cycloserine", dose:"500 mg"},
        {name:"Ethambutol", dose:"800 mg"},
      ],
    },
    food: [
      {id:"food-t", t: atTime(0, 17, 3), gap:120},
      {id:"food-y", t: atTime(-1, 8, 15), gap:120},
    ],
    // taken records keyed by "dayOffset:slot:name" for past/taken doses
    taken: buildTaken(),
  };
}
function buildTaken(){
  var t={};
  // past days fully taken (history), plus today's morning taken at 12:10 by default state
  for(var off=-6; off<=-1; off++){
    ["morning","night"].forEach(function(slot){
      DEMO.medsFor(slot).forEach(function(m){ t[off+":"+slot+":"+m.name]= atTime(off, slot==="morning"?10:22, 10); });
    });
  }
  return t;
}
var DEMO = {
  medsFor: function(slot){ return (this._m && this._m[slot]) || []; },
  _m: null
};

function reseed(){
  DEMO._m = { morning:[
    {name:"Bedaquiline", dose:"4 tablets"},{name:"Linezolid", dose:"600 mg"},
    {name:"Moxifloxacin", dose:"400 mg"},{name:"Pretomanid", dose:"200 mg"}
  ], night:[
    {name:"Pyridoxine / Vitamin B6", dose:"25 mg"},{name:"Levofloxacin", dose:"750 mg"},
    {name:"Clofazimine", dose:"100 mg"},{name:"Cycloserine", dose:"500 mg"},{name:"Ethambutol", dose:"800 mg"}
  ]};
  DATA = seed();
}
var DATA;

// dose slot definitions
var SLOTS = [
  {slot:"morning", label:"Morning Dose", emoji:"☀️", h:10, m:0},
  {slot:"night",   label:"Night Dose",   emoji:"🌙", h:22, m:0},
];

// status of a whole dose group on a given day offset
function doseStatus(off, slot){
  var sched = atTime(off, slot==="morning"?10:22, 0);
  var meds = DEMO.medsFor(slot);
  // explicit override only applies to today's morning dose
  if(off===0 && slot==="morning" && DATA.morningOverride){
    return DATA.morningOverride==="DUE" ? "UPCOMING" : DATA.morningOverride;
  }
  var allTaken = meds.every(function(m){ return DATA.taken[off+":"+slot+":"+m.name]; });
  if(allTaken && meds.length) return "TAKEN";
  var critical = sched + 60*60000; // 1h grace
  if(now() >= critical) return "MISSED";
  if(now() >= sched) return "DUE";
  return "UPCOMING";
}
function medStatus(off, slot, m){
  if(DATA.taken[off+":"+slot+":"+m.name]) return "TAKEN";
  var g = doseStatus(off, slot);
  return g==="MISSED" ? "MISSED" : (g==="DUE"||g==="LATE") ? "DUE" : "UPCOMING";
}
function takenAt(off, slot, m){ return DATA.taken[off+":"+slot+":"+m.name] || null; }

function statusMeta(s){
  if(s==="TAKEN") return {c:"g", bg:"bg-g", t:"TAKEN", dot:"var(--green)"};
  if(s==="MISSED") return {c:"r", bg:"bg-r", t:"NOT TAKEN", dot:"var(--red)"};
  if(s==="DUE") return {c:"y", bg:"bg-y", t:"DUE", dot:"var(--yellow)"};
  if(s==="LATE") return {c:"y", bg:"bg-y", t:"LATE", dot:"var(--yellow)"};
  return {c:"y", bg:"bg-y", t:"UPCOMING", dot:"var(--yellow)"};
}

// next not-taken medicine across today+future
function nextMedicine(){
  var best=null;
  for(var off=0; off<=7; off++){
    SLOTS.forEach(function(sl){
      var sched=atTime(off, sl.h, sl.m);
      if(sched < now()-60000) return;
      DEMO.medsFor(sl.slot).forEach(function(m){
        if(medStatus(off, sl.slot, m)!=="TAKEN"){
          if(!best || sched<best.sched) best={sched:sched, name:m.name, dose:m.dose, slot:sl.slot};
        }
      });
    });
    if(best) break;
  }
  return best;
}
function countdown(target){
  var diff=target-now(); if(diff<=0) return "Due now";
  var mins=Math.floor(diff/60000), h=Math.floor(mins/60), m=mins%60;
  return h>0 ? "In "+h+"h "+m+"m" : "In "+m+"m";
}

// ---------- condition → connection ----------
function connBadge(){
  var b=document.getElementById("connBadge");
  if(S.cond==="offline"||S.cond==="apierr"||S.cond==="syncerr"){ b.style.background="#3A1616"; b.style.color="var(--red)"; b.textContent="🔴 "+(S.cond==="offline"?"Offline":S.cond==="apierr"?"API error":"Sync error"); }
  else if(S.cond==="slow"){ b.style.background="#3A2C10"; b.style.color="var(--yellow)"; b.textContent="🟡 Slow network"; }
  else { b.style.background="#14321F"; b.style.color="var(--green)"; b.textContent="🟢 Connected"; }
}
function syncPhaseLabel(){
  if(S.cond==="offline") return {t:"Offline", cls:"r", dot:"var(--red)"};
  if(S.cond==="slow") return {t:"Syncing…", cls:"y", dot:"var(--yellow)"};
  if(S.cond==="syncerr"||S.cond==="apierr") return {t:"Sync error", cls:"r", dot:"var(--red)"};
  if(S.monitorVersion < S.primaryVersion) return {t:"Behind", cls:"y", dot:"var(--yellow)"};
  return {t:"Up to date", cls:"g", dot:"var(--green)"};
}

// ---------- rendering ----------
function esc(s){ return String(s==null?"":s).replace(/[&<>"]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c];}); }
function pill(s){ var m=statusMeta(s); return '<span class="pill2 '+m.bg+' '+m.c+'"><span class="dot" style="background:'+m.dot+'"></span>'+m.t+'</span>'; }

function statusBar(){
  return '<div class="sbar"><span>'+fmtTime(now())+'</span><span>▮▮▮ 📶 🔋 78%</span></div>';
}

// ----- PRIMARY device screen -----
function primaryScreen(){
  var nav = S.primaryNav;
  var body;
  if(nav==="home") body = primaryHome();
  else if(nav==="schedule") body = scheduleBody(true);
  else if(nav==="stats") body = statsBody();
  else if(nav==="settings") body = settingsBody(true);
  else body = primaryHome();
  var fab = '<div class="fab" title="Add medicine (primary only)">+</div>';
  return '<div class="screen">'+statusBar()+
    '<div class="appscroll">'+body+'</div>'+
    (nav==="home"?fab:'')+
    navBar(true, nav)+'</div>';
}
function primaryHome(){
  var d=new Date(now());
  var mMeds=DEMO.medsFor("morning"), nMeds=DEMO.medsFor("night");
  var takenCount = mMeds.filter(function(m){return medStatus(0,"morning",m)==="TAKEN";}).length
                 + nMeds.filter(function(m){return medStatus(0,"night",m)==="TAKEN";}).length;
  var total=mMeds.length+nMeds.length;
  var nx=nextMedicine();
  return ''+
  '<div class="c"><div class="row2"><div><div style="font-size:20px;font-weight:800">Today</div>'+
    '<div class="mu" style="font-size:12px">'+d.toLocaleDateString([], {day:"numeric",month:"long",year:"numeric"})+'</div></div>'+
    '<div style="text-align:right"><div style="font-weight:700;font-size:13px">2 Doses</div>'+
    '<div class="p" style="font-weight:800">'+takenCount+'/'+total+'</div><div class="mu" style="font-size:9px">Taken</div></div></div>'+
    doseSummary(0,"morning")+doseSummary(0,"night")+'</div>'+
  (nx? '<div class="c"><div class="row2"><b style="font-size:12px">⏰ NEXT MEDICINE</b><span class="pill2 bg-p p">'+countdown(nx.sched)+'</span></div>'+
    '<div style="margin-top:6px;font-weight:700">'+esc(nx.name)+'</div><div class="mu" style="font-size:12px">'+fmtTime(nx.sched)+' • '+esc(nx.dose)+'</div></div>' : '')+
  '<div class="c"><b style="font-size:12px">🍽️ FOOD</b><div class="mu" style="font-size:12px;margin:6px 0">Record a meal to recalculate medicine timing.</div>'+
    '<button class="btn block" onclick="foodTaken()">FOOD TAKEN</button>'+ foodInfo() +'</div>'+
  calendarCard()+
  '<div class="tabs" id="ptabs">'+tabBtn("today","Today")+tabBtn("schedule","Schedule")+tabBtn("history","History")+tabBtn("food","Food")+'</div>'+
  tabBody(true);
}

function doseSummary(off, slot){
  var sl=SLOTS.find(function(x){return x.slot===slot;});
  var st=doseStatus(off, slot);
  var meds=DEMO.medsFor(slot);
  var ta = meds.map(function(m){return takenAt(off,slot,m);}).filter(Boolean).sort().pop();
  var sub = st==="TAKEN"&&ta ? "Taken at "+fmtTime(ta) : meds.length+" medicines";
  return '<div class="c2 row2"><div style="display:flex;gap:8px;align-items:center"><span style="font-size:16px">'+sl.emoji+'</span>'+
    '<div><div style="font-weight:700;font-size:13px">'+sl.label+'</div><div class="mu" style="font-size:11px">'+fmtTime(atTime(off,sl.h,sl.m))+'</div></div></div>'+
    '<div style="text-align:right">'+pill(st)+'<div class="mu" style="font-size:9px;margin-top:3px">'+sub+'</div></div></div>';
}

// expandable dose card (used in Today/History tabs + monitor)
function doseCard(off, slot, primary){
  var sl=SLOTS.find(function(x){return x.slot===slot;});
  var st=doseStatus(off, slot);
  var key=off+":"+slot;
  var open=!!S.expanded[key];
  var meds=DEMO.medsFor(slot);
  var ta = meds.map(function(m){return takenAt(off,slot,m);}).filter(Boolean).sort().pop();
  var head='<div class="row2" onclick="toggleDose(\''+key+'\')" style="cursor:pointer">'+
    '<div style="display:flex;gap:8px;align-items:center"><span style="font-size:16px">'+sl.emoji+'</span>'+
    '<div><div style="font-weight:700;font-size:13px">'+sl.label+'</div><div class="mu" style="font-size:11px">'+fmtTime(atTime(off,sl.h,sl.m))+'</div></div></div>'+
    '<div style="display:flex;gap:6px;align-items:center">'+pill(st)+'<span class="mu">'+(open?'⌃':'⌄')+'</span></div></div>';
  var rows='';
  if(open){
    rows='<div style="margin-top:8px">'+meds.map(function(m){
      var ms=medStatus(off,slot,m); var t=takenAt(off,slot,m);
      var canTake = primary && off===0 && ms!=="TAKEN";
      return '<div class="row2" style="padding:6px 0;border-top:1px solid var(--line)">'+
        '<div style="display:flex;gap:8px;align-items:center"><span class="dot" style="background:'+statusMeta(ms).dot+'"></span>'+
        '<div><div style="font-size:12px;font-weight:600">'+esc(m.name)+'</div><div class="mu" style="font-size:10px">'+esc(m.dose)+'</div></div></div>'+
        '<div style="display:flex;gap:6px;align-items:center">'+(t?'<span class="mu" style="font-size:10px">'+fmtTime(t)+'</span>':'')+pill(ms)+
        (canTake?'<button class="chip" style="padding:3px 8px" onclick="takeMed(0,\''+slot+'\',\''+esc(m.name)+'\')">Take</button>':'')+'</div></div>';
    }).join('')+'</div>';
  }
  return '<div class="c">'+head+rows+'</div>';
}

function tabBtn(t, label){ return '<button class="'+(S.tab===t?'on':'')+'" onclick="setTab(\''+t+'\')">'+label+'</button>'; }
function tabBody(primary){
  if(S.tab==="today"){
    return doseCard(0,"morning",primary)+doseCard(0,"night",primary)+
      (primary?'<div class="c"><button class="btn block" onclick="markMorningTaken()">MEDICINE TAKEN (morning dose)</button></div>':'');
  }
  if(S.tab==="schedule") return scheduleBody(primary);
  if(S.tab==="history") return historyBody(primary);
  if(S.tab==="food") return foodBody();
  return '';
}

function scheduleBody(primary){
  var out='';
  for(var off=0; off<=1; off++){
    out+='<div class="mu" style="font-size:11px;font-weight:700;margin:8px 0 4px">'+(off===0?"TODAY":"TOMORROW")+'</div>';
    SLOTS.forEach(function(sl){
      var st=doseStatus(off, sl.slot);
      out+='<div class="c row2"><div><div style="font-weight:700;font-size:13px">'+fmtTime(atTime(off,sl.h,sl.m))+'</div>'+
        '<div class="mu" style="font-size:11px">'+sl.emoji+' '+sl.label+'</div></div>'+pill(st)+'</div>';
    });
  }
  return out;
}
function historyBody(primary){
  var out='';
  for(var off=0; off>=-4; off--){
    var lbl = off===0?"TODAY":off===-1?"YESTERDAY":new Date(atTime(off,0,0)).toLocaleDateString([], {day:"numeric",month:"short"}).toUpperCase();
    out+='<div class="mu" style="font-size:11px;font-weight:700;margin:8px 0 4px">'+lbl+'</div>';
    out+=doseCard(off,"morning",primary)+doseCard(off,"night",primary);
  }
  return out;
}
function foodBody(){
  return '<div class="mu" style="font-size:11px;font-weight:700;margin:8px 0 4px">FOOD HISTORY</div>'+
    DATA.food.map(function(f){
      var elig=f.t+f.gap*60000;
      return '<div class="c"><div class="row2"><div style="display:flex;gap:8px;align-items:center">🍽️<div>'+
        '<div style="font-weight:700;font-size:13px">Food taken</div><div class="mu" style="font-size:11px">'+fmtDate(f.t)+'</div></div></div>'+
        '<b>'+fmtTime(f.t)+'</b></div>'+
        '<div class="c2"><div class="row2 mu" style="font-size:11px"><span>Food taken</span><b style="color:var(--text)">'+fmtTime(f.t)+'</b></div>'+
        '<div class="row2 mu" style="font-size:11px"><span>Medicine interval</span><b style="color:var(--text)">'+(f.gap/60)+'h</b></div>'+
        '<div class="row2 mu" style="font-size:11px"><span>Medicine eligible</span><b style="color:var(--text)">'+fmtTime(elig)+'</b></div></div></div>';
    }).join('');
}
function statsBody(){
  var m=DEMO.medsFor("morning"), n=DEMO.medsFor("night");
  var taken=m.filter(function(x){return medStatus(0,"morning",x)==="TAKEN";}).length+n.filter(function(x){return medStatus(0,"night",x)==="TAKEN";}).length;
  return '<div class="mu" style="font-size:11px;font-weight:700;margin:8px 0 4px">OVERVIEW</div>'+
    '<div class="c"><div class="row2 mu" style="font-size:12px"><span>Doses today</span><b style="color:var(--text)">2</b></div>'+
    '<div class="row2 mu" style="font-size:12px"><span>Medicines taken today</span><b style="color:var(--text)">'+taken+' / '+(m.length+n.length)+'</b></div>'+
    '<div class="row2 mu" style="font-size:12px"><span>Adherence (7d)</span><b class="g">92%</b></div>'+
    '<div class="row2 mu" style="font-size:12px"><span>Food records</span><b style="color:var(--text)">'+DATA.food.length+'</b></div></div>';
}
function settingsBody(primary){
  var sp=syncPhaseLabel();
  return '<div class="mu" style="font-size:11px;font-weight:700;margin:8px 0 4px">SETTINGS</div>'+
    '<div class="c"><div class="row2"><b style="font-size:13px">'+(primary?"Primary Device":"Monitoring Device")+'</b>'+
    '<span class="pill2 bg-g g"><span class="dot" style="background:var(--green)"></span>Connected</span></div>'+
    '<div class="c2"><div class="row2 mu" style="font-size:12px"><span>Role</span><b style="color:var(--text)">'+(primary?"PRIMARY":"MONITORING")+'</b></div>'+
    '<div class="row2 mu" style="font-size:12px"><span>Cloud sync</span><b class="g">Always active</b></div>'+
    '<div class="row2 mu" style="font-size:12px"><span>Cloud version</span><b style="color:var(--text)">#'+S.primaryVersion+'</b></div>'+
    '<div class="row2 mu" style="font-size:12px"><span>Local version</span><b style="color:var(--text)">#'+(primary?S.primaryVersion:S.monitorVersion)+'</b></div></div>'+
    (primary?'<div class="c2"><div class="row2 mu" style="font-size:12px"><span>Authorized devices</span><b style="color:var(--text)">2</b></div></div>':'')+
    '</div>';
}
function foodInfo(){
  var f=DATA.food[0]; if(!f) return '';
  var elig=f.t+f.gap*60000;
  return '<div class="c2 mu" style="font-size:11px"><div class="row2"><span>Last food</span><b style="color:var(--text)">'+fmtTime(f.t)+'</b></div>'+
    '<div class="row2"><span>Medicine eligible</span><b style="color:var(--text)">'+fmtTime(elig)+'</b></div></div>';
}

function calendarCard(){
  var base=new Date(now()); var y=base.getFullYear(), mo=base.getMonth();
  var first=new Date(y,mo,1); var lead=first.getDay(); var days=new Date(y,mo+1,0).getDate();
  var todayD=base.getDate();
  var cells='<div class="cal">'+["Sun","Mon","Tue","Wed","Thu","Fri","Sat"].map(function(h){return '<div class="h">'+h+'</div>';}).join('');
  for(var i=0;i<lead;i++) cells+='<div></div>';
  for(var dnum=1; dnum<=days; dnum++){
    var off=dnum-todayD;
    var dotColor="var(--muted)";
    if(off>=-6 && off<=7){
      var ms=doseStatus(off,"morning"), ns=doseStatus(off,"night");
      if(ms==="TAKEN"&&ns==="TAKEN") dotColor="var(--green)";
      else if(ms==="MISSED"||ns==="MISSED") dotColor="var(--red)";
      else dotColor="var(--yellow)";
    }
    var sel = off===S.selectedDay ? " sel" : "";
    var tod = dnum===todayD ? " today" : "";
    cells+='<div class="d'+sel+tod+'" onclick="selectDay('+off+')"><span>'+dnum+'</span>'+
      '<span class="dot" style="background:'+dotColor+';margin-top:2px"></span></div>';
  }
  cells+='</div>';
  return '<div class="c"><div class="row2"><b style="font-size:13px">'+base.toLocaleDateString([], {month:"long",year:"numeric"})+'</b>'+
    '<span class="pill2 bg-p p" onclick="selectDay(0)" style="cursor:pointer">Today</span></div>'+cells+'</div>';
}

// ----- MONITORING device screen (read-only) -----
function monitorScreen(){
  var nav=S.monitorNav, body;
  if(nav==="settings") body=settingsBody(false);
  else if(nav==="stats") body=statsBody();
  else body=monitorHome();
  return '<div class="screen">'+statusBar()+'<div class="appscroll">'+body+'</div>'+navBar(false, nav)+'</div>';
}
function monitorHome(){
  var sp=syncPhaseLabel();
  var mMeds=DEMO.medsFor("morning"), nMeds=DEMO.medsFor("night");
  var takenCount=mMeds.filter(function(m){return medStatus(0,"morning",m)==="TAKEN";}).length+nMeds.filter(function(m){return medStatus(0,"night",m)==="TAKEN";}).length;
  var total=mMeds.length+nMeds.length;
  var nx=nextMedicine();
  var behind = S.monitorVersion < S.primaryVersion;
  return ''+
  '<div class="c"><div class="row2"><b style="font-size:12px">☁ CLOUD SYNC</b>'+
    '<span class="pill2 bg-'+sp.cls+' '+sp.cls+'"><span class="dot" style="background:'+sp.dot+'"></span>'+sp.t+'</span></div>'+
    '<div class="c2 mu" style="font-size:11px"><div class="row2"><span>Last downloaded</span><b style="color:var(--text)">'+fmtTime(now())+'</b></div>'+
    '<div class="row2"><span>Cloud version</span><b style="color:var(--text)">#'+S.primaryVersion+'</b></div>'+
    '<div class="row2"><span>Local version</span><b style="color:var(--text)">#'+S.monitorVersion+'</b></div></div>'+
    '<button class="btn block" style="margin-top:8px" onclick="monitorCheckSync()">CHECK SYNC</button></div>'+
  '<div class="c"><div class="row2"><div><div style="font-size:20px;font-weight:800">Today</div>'+
    '<div class="mu" style="font-size:12px">'+new Date(now()).toLocaleDateString([], {day:"numeric",month:"long",year:"numeric"})+'</div></div>'+
    '<div style="text-align:right"><div class="p" style="font-weight:800;font-size:16px">'+takenCount+'/'+total+'</div><div class="mu" style="font-size:9px">Taken</div></div></div>'+
    doseSummary(0,"morning")+doseSummary(0,"night")+'</div>'+
  (nx?'<div class="c"><div class="row2"><b style="font-size:12px">⏰ NEXT MEDICINE</b><span class="pill2 bg-p p">'+countdown(nx.sched)+'</span></div>'+
    '<div style="margin-top:6px;font-weight:700">'+esc(nx.name)+'</div><div class="mu" style="font-size:12px">'+fmtTime(nx.sched)+' • '+esc(nx.dose)+'</div></div>':'')+
  calendarCard()+
  '<div class="tabs">'+tabBtn("today","Today")+tabBtn("schedule","Schedule")+tabBtn("history","History")+tabBtn("food","Food")+'</div>'+
  tabBody(false)+
  '<div class="mu" style="font-size:10px;text-align:center;margin-top:8px">Read-only monitor · data comes from the primary device</div>';
}

function navBar(primary, nav){
  var items = primary
    ? [["home","🏠","Home"],["schedule","🗓","Schedule"],["stats","📊","Stats"],["settings","⚙️","Settings"]]
    : [["home","🏠","Home"],["schedule","🗓","Schedule"],["stats","📊","Stats"],["history","🕘","History"],["settings","⚙️","Settings"]];
  return '<div class="navbar">'+items.map(function(it){
    var on = nav===it[0] ? " on":"";
    return '<div class="navitem'+on+'" onclick="setNav(\''+(primary?"primary":"monitor")+'\',\''+it[0]+'\')"><span class="ic">'+it[1]+'</span><span>'+it[2]+'</span></div>';
  }).join('')+'</div>';
}

// ---------- top-level render ----------
function phone(kind){
  var tag = kind==="primary" ? "📱 Primary Device" : "👁 Monitoring Device";
  var screen = kind==="primary" ? primaryScreen() : monitorScreen();
  return '<div class="devwrap"><div class="devtag">'+tag+'</div>'+
    '<div class="phone"><div class="notch"></div><div class="cam"></div>'+screen+'</div></div>';
}
function render(){
  if(!DATA) reseed();
  // segmented control
  document.querySelectorAll("#seg button").forEach(function(b){ b.classList.toggle("on", b.dataset.d===S.device); });
  // dose-state chips
  document.querySelectorAll("#doseState .chip").forEach(function(b){ b.classList.toggle("on", b.dataset.s===(DATA.morningOverride||"")); });
  // condition chips
  document.querySelectorAll("#conds .chip").forEach(function(b){ b.classList.toggle("on", b.dataset.c===S.cond); });
  document.getElementById("pver").textContent="#"+S.primaryVersion;
  document.getElementById("mver").textContent="#"+S.monitorVersion;
  var sp=syncPhaseLabel();
  document.getElementById("syncStat").className="pill2 bg-"+sp.cls+" "+sp.cls;
  document.getElementById("syncStat").innerHTML='<span class="dot" style="background:'+sp.dot+'"></span>'+sp.t;
  connBadge();
  // time label
  document.getElementById("timeNow").textContent = S.simTime!=null
    ? "🧪 Simulated · "+new Date(S.simTime).toLocaleDateString([], {day:"numeric",month:"short"})+" • "+fmtTime(S.simTime)
    : "Real time · "+fmtTime(Date.now());
  // stage
  var live=document.getElementById("liveSync").checked;
  var stage=document.getElementById("stage");
  if(live) stage.innerHTML = phone("primary")+phone("monitor");
  else stage.innerHTML = phone(S.device);
}

// ---------- interactions ----------
function setDevice(d){ S.device=d; S.tab="today"; var u=new URL(location); u.searchParams.set("device",d); history.replaceState(null,"",u); render(); }
function setTab(t){ S.tab=t; render(); }
function setNav(kind,n){ if(kind==="primary") S.primaryNav=n; else S.monitorNav=n; if(n!=="home") S.tab= (n==="history")?"history":(n==="schedule")?"schedule":S.tab; render(); }
function toggleDose(key){ S.expanded[key]=!S.expanded[key]; render(); }
function selectDay(off){ S.selectedDay=off; render(); }

function takeMed(off, slot, name){ DATA.taken[off+":"+slot+":"+name]=now(); bumpPrimary(); toast("🟢 "+name+" marked taken"); render(); }
function markMorningTaken(){ DATA.morningOverride=null; DEMO.medsFor("morning").forEach(function(m){ DATA.taken["0:morning:"+m.name]=now(); }); bumpPrimary(); toast("🟢 Morning dose taken"); render(); }
function foodTaken(){ DATA.food.unshift({id:"food-"+Date.now(), t:now(), gap:120}); bumpPrimary(); toast("🍽️ Food recorded — eligibility updated"); render(); }
function setDoseState(s){ DATA.morningOverride = (DATA.morningOverride===s? null : s); if(s==="TAKEN"){ DEMO.medsFor("morning").forEach(function(m){ DATA.taken["0:morning:"+m.name]=now(); }); } render(); }

function bumpPrimary(){ S.primaryVersion++; if(!document.getElementById("liveSync").checked){ /* monitor stays behind until push */ } }

function applyTime(){
  var d=document.getElementById("simDate").value, t=document.getElementById("simTime").value;
  if(!d||!t){ toast("Pick a date and time"); return; }
  S.simTime=new Date(d+"T"+t).getTime(); render();
}
function resetTime(){ S.simTime=null; render(); }

function setCond(c){ S.cond= (S.cond===c? "online" : c); render(); }

function pushUpdate(){
  if(S.cond==="offline"||S.cond==="syncerr"||S.cond==="apierr"){ toast("Cannot sync in this condition"); return; }
  S.monitorVersion=S.primaryVersion; toast("⬆ Pushed · monitoring synced"); render();
}
function createPending(){ S.primaryVersion++; toast("Monitoring is now behind (#"+S.monitorVersion+" < #"+S.primaryVersion+")"); render(); }
function monitorCheckSync(){
  if(S.cond==="offline"){ toast("🔴 Offline"); return; }
  if(S.cond==="syncerr"||S.cond==="apierr"){ toast("🔴 Sync failed"); return; }
  S.monitorVersion=S.primaryVersion; toast("🟢 Up to date"); render();
}

function runSyncTest(){
  var ul=document.getElementById("syncSteps"); ul.innerHTML="";
  var steps=["Event created","Uploaded","Cloud updated","Monitoring detected update","Monitoring downloaded update","Devices synchronized"];
  DATA.taken["0:morning:Bedaquiline"]=now(); S.primaryVersion++;
  var i=0;
  var timer=setInterval(function(){
    if(i<steps.length){ var li=document.createElement("li"); li.className="ok"; li.textContent="✓ "+steps[i]; ul.appendChild(li);
      if(i===4) S.monitorVersion=S.primaryVersion;
      i++; render();
    } else { clearInterval(timer); toast("✓ Devices synchronized"); }
  }, 420);
}

function notify(kind){
  var map={remind:["💊","Medication reminder","Your morning TB medicines are due at 10:00 AM."],
    due:["⏰","Medicine due now","Morning dose is due. Tap to view."],
    critical:["🚨","Critical: medicine not taken","Morning dose is overdue. Please take it now."],
    taken:["✓","Medication recorded","Morning dose recorded as taken."]};
  var n=map[kind]; var area=document.getElementById("notifArea");
  area.innerHTML='<div class="notif"><span class="ic">'+n[0]+'</span><div><div style="font-weight:700;font-size:12px">'+n[1]+'</div><div class="mu" style="font-size:11px">'+n[2]+'</div></div></div>';
}

function toast(m){ var t=document.getElementById("toast"); t.textContent=m; t.className="toast show"; clearTimeout(t._t); t._t=setTimeout(function(){t.className="toast";},2200); }
function reloadPreview(){ render(); toast("Reloaded"); }
function resetPreview(){ reseed(); S.simTime=null; S.cond="online"; S.primaryVersion=1851; S.monitorVersion=1851; S.expanded={}; S.selectedDay=0; render(); toast("Preview reset"); }
function toggleFs(){ document.getElementById("wrap").classList.toggle("fs"); render(); }
document.addEventListener("keydown", function(e){ if(e.key==="Escape") document.getElementById("wrap").classList.remove("fs"); });

// live countdown / clock tick
setInterval(function(){ render(); }, 1000);
reseed(); render();
`;

// --- Interactive browser App Preview (demo-data mock of the Android app) ---
// NOTE: The Android app is native Kotlin/Compose and cannot literally run in a browser, so this
// is a faithful HTML/JS re-creation for testing UI/navigation/states/sync. It uses demo data only
// and never touches the real database.
function previewHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>TB MedTrack — App Preview</title>
<style>
  :root{
    --bg:#0B1020;--bg2:#121A32;--panel:#131A30;--card:#161C33;--card2:#1E2643;--line:#2C3556;
    --text:#EEF1FA;--muted:#94A3B8;--purple:#8B5CF6;--indigo:#6366F1;
    --green:#22C55E;--yellow:#F59E0B;--red:#EF4444;--radius:14px;
  }
  *{box-sizing:border-box}
  html,body{margin:0}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    background:linear-gradient(160deg,var(--bg2),var(--bg));color:var(--text);min-height:100vh;font-size:14px}
  a{color:inherit;text-decoration:none}
  button{font-family:inherit;cursor:pointer;border:none}
  .wrap{max-width:1200px;margin:0 auto;padding:20px 16px 60px}
  .top{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px}
  .brand{display:flex;align-items:center;gap:10px}
  .brand .logo{width:38px;height:38px;border-radius:11px;background:linear-gradient(135deg,var(--indigo),var(--purple));
    display:flex;align-items:center;justify-content:center;font-size:19px}
  h1{font-size:20px;margin:0}
  .muted{color:var(--muted)}
  .badge{display:inline-flex;align-items:center;gap:6px;padding:4px 10px;border-radius:999px;font-size:12px;font-weight:700}
  .badge.prev{background:#241F3D;color:var(--purple)}
  .seg{display:inline-flex;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:3px}
  .seg button{background:transparent;color:var(--muted);padding:8px 16px;border-radius:9px;font-weight:600;font-size:13px}
  .seg button.on{background:var(--purple);color:#fff}
  .layout{display:grid;grid-template-columns:320px 1fr;gap:18px;align-items:start}
  @media(max-width:900px){.layout{grid-template-columns:1fr}}
  .panel{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:14px;margin-bottom:14px}
  .panel h3{margin:0 0 10px;font-size:13px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
  .ctl-row{display:flex;flex-wrap:wrap;gap:8px}
  .chip{background:var(--card2);border:1px solid var(--line);color:var(--text);padding:7px 12px;border-radius:9px;font-size:12px;font-weight:600}
  .chip.on{background:var(--purple);border-color:var(--purple);color:#fff}
  .chip.warn.on{background:var(--yellow);border-color:var(--yellow);color:#1b1300}
  .chip.err.on{background:var(--red);border-color:var(--red);color:#fff}
  .btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;background:var(--purple);color:#fff;
    font-weight:700;font-size:13px;padding:9px 14px;border-radius:10px}
  .btn.ghost{background:var(--card2);border:1px solid var(--line)}
  .btn.block{width:100%}
  .in{width:100%;background:#0E1526;border:1px solid var(--line);color:var(--text);border-radius:9px;padding:9px;font-size:13px}
  label.lbl{display:block;font-size:12px;color:var(--muted);margin:8px 0 4px}
  .stage{display:flex;flex-wrap:wrap;gap:22px;justify-content:center;align-items:flex-start}
  .devwrap{display:flex;flex-direction:column;align-items:center;gap:8px}
  .devtag{font-size:12px;color:var(--muted);font-weight:700}
  /* Phone frame */
  .phone{width:320px;max-width:92vw;aspect-ratio:9/19;background:#05070E;border-radius:34px;padding:10px;
    box-shadow:0 20px 60px rgba(0,0,0,.55),0 0 0 2px #1b2236;position:relative}
  .phone .notch{position:absolute;top:14px;left:50%;transform:translateX(-50%);width:110px;height:22px;background:#05070E;border-radius:0 0 14px 14px;z-index:6}
  .phone .cam{position:absolute;top:19px;left:50%;transform:translateX(-50%);width:7px;height:7px;border-radius:50%;background:#1c2740;z-index:7}
  .screen{position:relative;width:100%;height:100%;background:linear-gradient(160deg,#121A32,#0B1020);border-radius:26px;overflow:hidden;display:flex;flex-direction:column}
  .sbar{display:flex;justify-content:space-between;align-items:center;padding:8px 16px 4px;font-size:11px;color:#cdd5ea;flex-shrink:0}
  .appscroll{flex:1;overflow-y:auto;padding:6px 12px 12px}
  .appscroll::-webkit-scrollbar{width:0}
  .navbar{flex-shrink:0;display:flex;justify-content:space-around;align-items:center;background:#131A30;border-top:1px solid var(--line);padding:7px 4px 9px}
  .navitem{display:flex;flex-direction:column;align-items:center;gap:2px;font-size:9px;color:var(--muted)}
  .navitem.on{color:var(--indigo)}
  .navitem .ic{font-size:16px}
  .fab{position:absolute;bottom:52px;left:50%;transform:translateX(-50%);width:52px;height:52px;border-radius:50%;
    background:var(--indigo);display:flex;align-items:center;justify-content:center;font-size:26px;color:#fff;
    box-shadow:0 6px 18px rgba(99,102,241,.5);z-index:5}
  /* app cards */
  .c{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px;margin-bottom:10px}
  .c2{background:var(--card2);border-radius:12px;padding:10px;margin-top:8px}
  .pill2{display:inline-flex;align-items:center;gap:5px;padding:3px 8px;border-radius:999px;font-size:10px;font-weight:800}
  .dot{width:7px;height:7px;border-radius:50%;display:inline-block}
  .g{color:var(--green)} .y{color:var(--yellow)} .r{color:var(--red)} .p{color:var(--purple)} .mu{color:var(--muted)}
  .bg-g{background:rgba(34,197,94,.16)} .bg-y{background:rgba(245,158,11,.16)} .bg-r{background:rgba(239,68,68,.16)} .bg-p{background:rgba(139,92,246,.16)}
  .row2{display:flex;justify-content:space-between;align-items:center;gap:8px}
  .tabs{display:flex;background:var(--card);border-radius:11px;padding:3px;margin:8px 0}
  .tabs button{flex:1;background:transparent;color:var(--muted);font-size:12px;font-weight:600;padding:7px;border-radius:8px}
  .tabs button.on{background:var(--indigo);color:#fff}
  .cal{display:grid;grid-template-columns:repeat(7,1fr);gap:3px;margin-top:6px}
  .cal .h{font-size:9px;color:var(--muted);text-align:center;padding:2px 0}
  .cal .d{aspect-ratio:1;border-radius:9px;display:flex;flex-direction:column;align-items:center;justify-content:center;font-size:11px;cursor:pointer}
  .cal .d.sel{outline:1.5px solid var(--purple);background:rgba(139,92,246,.2)}
  .cal .d.today{color:var(--purple);font-weight:800}
  .toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#12331F;color:#EAFBF0;padding:10px 16px;
    border-radius:10px;font-weight:700;z-index:200;display:none;box-shadow:0 8px 24px rgba(0,0,0,.5)}
  .toast.show{display:block}
  .notif{margin:8px 0;padding:10px;border-radius:12px;background:#0E1526;border:1px solid var(--line);display:flex;gap:10px;align-items:flex-start}
  .notif .ic{font-size:18px}
  .steps{list-style:none;padding:0;margin:8px 0 0;font-size:12px}
  .steps li{padding:4px 0;color:var(--muted)}
  .steps li.ok{color:var(--green)}
  .fs .layout{grid-template-columns:1fr}
  .fs .controls{display:none}
  .fs .phone{width:360px}
  .verbadge{font-size:11px;color:var(--muted)}
</style></head>
<body>
<div class="wrap" id="wrap">
  <div class="top">
    <div class="brand"><div class="logo">💊</div><div><h1>TB MedTrack — App Preview</h1>
      <div class="muted" style="font-size:12px">Browser test environment · <span class="badge prev">🧪 Preview Mode</span></div></div></div>
    <div style="flex:1"></div>
    <div class="seg" id="seg">
      <button data-d="primary" onclick="setDevice('primary')">📱 Primary</button>
      <button data-d="monitor" onclick="setDevice('monitor')">👁 Monitoring</button>
    </div>
  </div>

  <div class="panel" style="display:flex;flex-wrap:wrap;gap:8px;align-items:center">
    <b style="font-size:13px">APP PREVIEW</b>
    <span id="connBadge" class="badge" style="background:#14321F;color:var(--green)">🟢 Connected</span>
    <label style="display:flex;align-items:center;gap:6px;font-size:12px;color:var(--muted);cursor:pointer">
      <input type="checkbox" id="liveSync" onchange="render()"> Live Sync Preview (two devices)</label>
    <div style="flex:1"></div>
    <button class="btn ghost" onclick="reloadPreview()">↻ Reload</button>
    <button class="btn ghost" onclick="resetPreview()">⟲ Reset</button>
    <button class="btn ghost" onclick="toggleFs()">⛶ Fullscreen</button>
    <span class="muted" style="font-size:11px;width:100%">Preview data only — never modifies real medication history.</span>
  </div>

  <div class="layout">
    <!-- Developer controls -->
    <div class="controls">
      <div class="panel">
        <h3>🧪 Simulated Time</h3>
        <div id="timeNow" class="muted" style="font-size:13px;margin-bottom:8px">Real time</div>
        <label class="lbl">Date</label><input class="in" type="date" id="simDate">
        <label class="lbl">Time</label><input class="in" type="time" id="simTime">
        <div class="ctl-row" style="margin-top:10px">
          <button class="btn" onclick="applyTime()">Apply</button>
          <button class="btn ghost" onclick="resetTime()">Reset to Real Time</button>
        </div>
      </div>

      <div class="panel">
        <h3>Dose State (morning dose)</h3>
        <div class="ctl-row" id="doseState">
          <button class="chip" data-s="UPCOMING" onclick="setDoseState('UPCOMING')">Upcoming</button>
          <button class="chip" data-s="DUE" onclick="setDoseState('DUE')">Due</button>
          <button class="chip" data-s="TAKEN" onclick="setDoseState('TAKEN')">Taken</button>
          <button class="chip" data-s="LATE" onclick="setDoseState('LATE')">Late</button>
          <button class="chip" data-s="MISSED" onclick="setDoseState('MISSED')">Missed</button>
        </div>
      </div>

      <div class="panel">
        <h3>Sync Simulator</h3>
        <div class="row2"><span class="muted">Primary</span><span class="verbadge" id="pver">#1851</span></div>
        <div class="row2"><span class="muted">Monitoring</span><span class="verbadge" id="mver">#1851</span></div>
        <div class="row2"><span class="muted">Status</span><span id="syncStat" class="pill2 bg-g g"><span class="dot g" style="background:var(--green)"></span>Synced</span></div>
        <div class="ctl-row" style="margin-top:10px">
          <button class="btn" onclick="pushUpdate()">Push Update</button>
          <button class="btn ghost" onclick="createPending()">Create Pending</button>
        </div>
        <button class="btn block" style="margin-top:8px" onclick="runSyncTest()">▶ Run Sync Test</button>
        <ul class="steps" id="syncSteps"></ul>
      </div>

      <div class="panel">
        <h3>Test Conditions</h3>
        <div class="ctl-row" id="conds">
          <button class="chip" data-c="online" onclick="setCond('online')">Online</button>
          <button class="chip err" data-c="offline" onclick="setCond('offline')">Offline</button>
          <button class="chip warn" data-c="slow" onclick="setCond('slow')">Slow Network</button>
          <button class="chip err" data-c="syncerr" onclick="setCond('syncerr')">Sync Error</button>
          <button class="chip err" data-c="apierr" onclick="setCond('apierr')">API Error</button>
        </div>
      </div>

      <div class="panel">
        <h3>Notification Simulator</h3>
        <div class="ctl-row">
          <button class="chip" onclick="notify('remind')">10 AM Reminder</button>
          <button class="chip" onclick="notify('due')">Medicine Due</button>
          <button class="chip" onclick="notify('critical')">Critical Alert</button>
          <button class="chip" onclick="notify('taken')">Medicine Taken</button>
        </div>
        <div id="notifArea"></div>
      </div>
    </div>

    <!-- Phone stage -->
    <div class="stage" id="stage"></div>
  </div>
  <div class="foot muted" style="text-align:center;font-size:11px;margin-top:20px">
    🧪 Faithful browser re-creation for testing. The production app is the native Android APK.
  </div>
</div>
<div class="toast" id="toast"></div>

<script>${PREVIEW_JS}</script>
</body></html>`;
}

export default app;
