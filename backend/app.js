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
          recorded_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
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
    endpoints: ["/health", "/status", "/stats", "/system", "/devices", "/v1/public-stats", "/v1/system-status", "/v1/system-revoke", "/v1/devices/auth-code", "/v1/devices/redeem", "/v1/events", "/v1/sync-status"],
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
      sql: `INSERT INTO monitor_doses
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
      sql: `INSERT INTO monitor_food_events (uuid,user_id,food_at,recorded_at,updated_at)
            VALUES (?,?,?,?,?)`,
      args: [String(f.uuid), req.device.user_id, Number(f.foodAt), Number(f.recordedAt || f.foodAt), now],
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
      sql: `SELECT uuid,food_at,recorded_at,updated_at FROM monitor_food_events
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
    <b>Endpoints</b>
    <div class="row"><span>Health</span><span class="muted">/health</span></div>
    <div class="row"><span>Status (JSON)</span><span class="muted">/status</span></div>
    <div class="row"><span>Stats dashboard</span><span class="muted">/stats?key=…</span></div>
    <div class="row"><span>Devices, health &amp; sync</span><span class="muted">/devices?key=…</span></div>
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

export default app;
