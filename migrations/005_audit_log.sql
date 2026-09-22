-- 005_audit_log.sql
-- Immutable audit trail of medication-event status changes (TAKEN -> REVERTED -> ...).

CREATE TABLE IF NOT EXISTS medication_event_audit (
    uuid                 TEXT PRIMARY KEY,
    medication_event_id  TEXT NOT NULL,   -- medication_events.uuid
    user_id              TEXT,
    action               TEXT NOT NULL,   -- MARK_TAKEN | REVERT | SNOOZE | IMPORT_HISTORICAL | MISS
    old_status           TEXT,
    new_status           TEXT,
    timestamp            INTEGER NOT NULL,
    device_id            TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_event ON medication_event_audit(medication_event_id);
CREATE INDEX IF NOT EXISTS idx_audit_time ON medication_event_audit(timestamp);
