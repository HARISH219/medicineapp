-- 003_device_authorization.sql
-- Authorized devices + short-lived pairing codes + settings.

CREATE TABLE IF NOT EXISTS devices (
    device_id       TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    name            TEXT NOT NULL,
    role            TEXT NOT NULL,          -- PRIMARY | MONITOR
    fcm_token       TEXT,
    session_token   TEXT,                   -- hashed server-side in production
    revoked         INTEGER NOT NULL DEFAULT 0,
    last_active_at  INTEGER,
    created_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_codes (
    code        TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL,
    expires_at  INTEGER NOT NULL,
    used        INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (
    user_id              TEXT PRIMARY KEY,
    treatment_start_day  INTEGER,
    tracking_start_day   INTEGER,
    night_med_minutes    INTEGER,
    morning_dose_minutes INTEGER,
    escalation_interval  INTEGER,
    updated_at           INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
