-- Live capture is STT-only; workers take turns between sessions (round robin) instead of oldest-session-first.
ALTER TABLE capture_sessions ADD COLUMN last_claimed_at TIMESTAMPTZ;
DROP INDEX IF EXISTS capture_queue_idx;
CREATE INDEX capture_queue_idx ON capture_sessions((coalesce(last_claimed_at, created_at))) WHERE status IN ('OPEN','DRAINING');
