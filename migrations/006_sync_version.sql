-- 006_sync_version.sql
-- Per-device sync watermark so we can tell whether each device (including monitors)
-- has actually received the latest cloud version, not just that Turso has it.
--
-- cloudVersion = MAX(updated_at) across a user's medication_events. Each device records the
-- highest updated_at it has downloaded in devices.last_synced_version. A monitor whose
-- last_synced_version < cloudVersion is still "pending".

ALTER TABLE devices ADD COLUMN last_synced_version INTEGER NOT NULL DEFAULT 0;
