-- 001_initial_schema.sql
-- Core account + medicine tables. Run in order (001..005).

CREATE TABLE IF NOT EXISTS users (
    id          TEXT PRIMARY KEY,
    created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS medicines (
    uuid            TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    name            TEXT NOT NULL,
    dose            TEXT NOT NULL,
    unit            TEXT NOT NULL,
    type            TEXT,
    food_timing     TEXT,
    notes           TEXT,
    active          INTEGER NOT NULL DEFAULT 1,
    part_of_tb      INTEGER NOT NULL DEFAULT 1,
    schedule_rule   TEXT,
    start_date      INTEGER NOT NULL,
    end_date        INTEGER,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE TABLE IF NOT EXISTS dose_schedules (
    uuid           TEXT PRIMARY KEY,
    medicine_uuid  TEXT NOT NULL,
    time_minutes   INTEGER NOT NULL,
    frequency      TEXT NOT NULL,
    days_of_week   TEXT,
    interval_days  INTEGER NOT NULL DEFAULT 1,
    anchor_day     INTEGER,
    enabled        INTEGER NOT NULL DEFAULT 1,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_medicines_user ON medicines(user_id);
CREATE INDEX IF NOT EXISTS idx_schedules_medicine ON dose_schedules(medicine_uuid);
