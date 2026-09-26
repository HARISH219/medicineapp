-- 007_monitor_snapshot.sql
-- Authoritative, concrete read-only projection published by the PRIMARY device.
-- MONITOR devices render these occurrences and food events without generating schedules.

CREATE TABLE IF NOT EXISTS monitor_doses (
  occurrence_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  medicine_name TEXT NOT NULL,
  dose_text TEXT NOT NULL,
  scheduled_at INTEGER NOT NULL,
  scheduled_epoch_day INTEGER NOT NULL,
  status TEXT NOT NULL,
  taken_at INTEGER,
  eligible_at INTEGER NOT NULL,
  critical_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitor_doses_user_day
  ON monitor_doses(user_id, scheduled_epoch_day);

CREATE TABLE IF NOT EXISTS monitor_food_events (
  uuid TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  food_at INTEGER NOT NULL,
  recorded_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitor_food_user_time
  ON monitor_food_events(user_id, food_at);

CREATE TABLE IF NOT EXISTS monitor_snapshot_meta (
  user_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL,
  emergency_contact TEXT NOT NULL DEFAULT ''
);
