// TB MedTrack sync backend (reference implementation).
//
// Responsibilities:
//  - Hold the Turso token (env var, never in the app) and talk to Turso over libSQL.
//  - Expose an authenticated HTTPS API the Android app uses (SyncClient / RemoteSyncClient).
//  - Issue/redeem/revoke device authorization codes.
//  - Upsert medication events idempotently (by uuid), applying TAKEN/REVERTED merge rules.
//  - Push updates to monitor devices via FCM.
//
// This is a starting point you deploy (Render/Railway/Fly/etc.). Harden auth, validation,
// and token hashing before real use.

import express from "express";
import crypto from "node:crypto";
import { createClient } from "@libsql/client";

const {
  TURSO_DATABASE_URL,
  TURSO_AUTH_TOKEN,
  PORT = 8080,
  SESSION_SIGNING_SECRET = "change-me",
  GOOGLE_APPLICATION_CREDENTIALS,
} = process.env;

if (!TURSO_DATABASE_URL || !TURSO_AUTH_TOKEN) {
  console.error("Missing TURSO_DATABASE_URL / TURSO_AUTH_TOKEN. See .env.example.");
  process.exit(1);
}

const db = createClient({ url: TURSO_DATABASE_URL, authToken: TURSO_AUTH_TOKEN });

// Optional FCM
let messaging = null;
if (GOOGLE_APPLICATION_CREDENTIALS) {
  try {
    const admin = await import("firebase-admin");
    admin.default.initializeApp({ credential: admin.default.credential.applicationDefault() });
    messaging = admin.default.messaging();
    console.log("FCM enabled");
  } catch (e) {
    console.warn("FCM not initialized:", e.message);
  }
}

const app = express();
app.use(express.json({ limit: "1mb" }));

// --- auth middleware: Bearer <deviceSessionToken> ---
async function auth(req, res, next) {
  const header = req.headers["authorization"] || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: "missing token" });
  const row = await db.execute({
    sql: "SELECT device_id, user_id, revoked FROM devices WHERE session_token = ? LIMIT 1",
    args: [hash(token)],
  });
  const device = row.rows[0];
  if (!device || device.revoked) return res.status(401).json({ error: "invalid or revoked" });
  req.device = device;
  next();
}

function hash(s) {
  return crypto.createHmac("sha256", SESSION_SIGNING_SECRET).update(s).digest("hex");
}
function randomCode() {
  return String(crypto.randomInt(0, 1000000)).padStart(6, "0");
}
function newToken() {
  return crypto.randomBytes(32).toString("hex");
}

app.get("/health", (_req, res) => res.json({ ok: true }));

// Primary device creates a short-lived pairing code.
app.post("/v1/devices/auth-code", auth, async (req, res) => {
  const code = randomCode();
  const expiresAt = Date.now() + 5 * 60 * 1000;
  await db.execute({
    sql: "INSERT INTO auth_codes (code, user_id, expires_at, used, created_at) VALUES (?,?,?,0,?)",
    args: [code, req.device.user_id, expiresAt, Date.now()],
  });
  res.json({ code, expiresAt });
});

// New device redeems a code -> becomes a MONITOR, gets a session token.
app.post("/v1/devices/redeem", async (req, res) => {
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
});

// Primary device revokes another device.
app.post("/v1/devices/revoke", auth, async (req, res) => {
  const { deviceId } = req.body || {};
  await db.execute({
    sql: "UPDATE devices SET revoked = 1, session_token = NULL WHERE device_id = ? AND user_id = ?",
    args: [deviceId, req.device.user_id],
  });
  res.json({ ok: true });
});

// Register this device's FCM token.
app.post("/v1/devices/fcm-token", auth, async (req, res) => {
  const { fcmToken } = req.body || {};
  await db.execute({
    sql: "UPDATE devices SET fcm_token = ? WHERE device_id = ?",
    args: [fcmToken || null, req.device.device_id],
  });
  res.json({ ok: true });
});

// Pull events updated since a watermark.
app.get("/v1/events", auth, async (req, res) => {
  const since = Number(req.query.since || 0);
  const rows = await db.execute({
    sql: "SELECT * FROM medication_events WHERE user_id = ? AND updated_at > ? ORDER BY updated_at ASC LIMIT 1000",
    args: [req.device.user_id, since],
  });
  res.json({ events: rows.rows.map(toDto) });
});

// Upload events. Idempotent upsert by uuid with TAKEN/REVERTED merge.
app.post("/v1/events", auth, async (req, res) => {
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
});

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
  if (!messaging) return;
  const rows = await db.execute({
    sql: "SELECT fcm_token FROM devices WHERE user_id = ? AND role = 'MONITOR' AND revoked = 0 AND fcm_token IS NOT NULL",
    args: [userId],
  });
  const tokens = rows.rows.map((r) => r.fcm_token).filter(Boolean);
  if (!tokens.length) return;
  const taken = e.status === "TAKEN";
  await messaging.sendEachForMulticast({
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

app.listen(PORT, () => console.log(`TB MedTrack backend on :${PORT}`));
