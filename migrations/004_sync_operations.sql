-- 004_sync_operations.sql
-- Server-side record of processed sync operations for idempotency/debugging.

CREATE TABLE IF NOT EXISTS sync_operations (
    operation_id    TEXT PRIMARY KEY,   -- unique -> replays are ignored
    device_id       TEXT,
    user_id         TEXT,
    record_id       TEXT,
    operation_type  TEXT NOT NULL,
    created_at      INTEGER NOT NULL,
    processed_at    INTEGER
);

CREATE INDEX IF NOT EXISTS idx_ops_user ON sync_operations(user_id);
