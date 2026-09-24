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
    endpoints: ["/health", "/status", "/stats", "/v1/public-stats", "/v1/devices/auth-code", "/v1/devices/redeem", "/v1/events", "/v1/sync-status"],
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

// Pull events updated since a watermark. Records this device's sync watermark so we can later
// tell whether each device (including monitors) has received the latest cloud version.
app.get("/v1/events", auth, async (req, res) => {
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
});

// Report the synchronization version for this account: the cloud's latest version and each
// authorized device's acknowledged version, so the app can verify monitors actually received it.
app.get("/v1/sync-status", auth, async (req, res) => {
  const userId = req.device.user_id;
  const verRow = await db.execute({
    sql: "SELECT COALESCE(MAX(updated_at),0) AS v FROM medication_events WHERE user_id = ?",
    args: [userId],
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
    <a class="btn" href="/stats">📊 Open stats dashboard</a>
    <p class="muted" style="font-size:13px">The dashboard requires an access key.</p>
  </div>
  <div class="card">
    <b>Endpoints</b>
    <div class="row"><span>Health</span><span class="muted">/health</span></div>
    <div class="row"><span>Status (JSON)</span><span class="muted">/status</span></div>
    <div class="row"><span>Stats dashboard</span><span class="muted">/stats?key=…</span></div>
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

export default app;
