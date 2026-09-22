-- 002_medication_events.sql
-- Append-oriented medication events. UUID primary key makes upserts idempotent.

CREATE TABLE IF NOT EXISTS medication_events (
    uuid                 TEXT PRIMARY KEY,
    user_id              TEXT NOT NULL,
    device_id            TEXT,
    medicine_id          TEXT,
    schedule_id          TEXT,
    medicine_name        TEXT,
    dose_text            TEXT,
    scheduled_at         INTEGER NOT NULL,
    scheduled_epoch_day  INTEGER NOT NULL,
    status               TEXT NOT NULL,
    taken_at             INTEGER,
    reverted_at          INTEGER,
    historical           INTEGER NOT NULL DEFAULT 0,
    taken_time_precision TEXT DEFAULT 'EXACT',
    snooze_count         INTEGER NOT NULL DEFAULT 0,
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL,
    deleted_at           INTEGER
);

CREATE INDEX IF NOT EXISTS idx_events_user ON medication_events(user_id);
CREATE INDEX IF NOT EXISTS idx_events_day ON medication_events(scheduled_epoch_day);
CREATE INDEX IF NOT EXISTS idx_events_updated ON medication_events(updated_at);
