CREATE TABLE analysis_jobs (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    revision_id UUID NOT NULL,
    input_version BIGINT NOT NULL CHECK (input_version >= 0),
    title VARCHAR(255),
    meeting_date DATE,
    timezone VARCHAR(64),
    provider_id VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    policy_id VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    context_tokens INTEGER NOT NULL CHECK (context_tokens > 0),
    reserved_tokens INTEGER NOT NULL CHECK (reserved_tokens >= 0 AND reserved_tokens < context_tokens),
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED')),
    stage VARCHAR(32) NOT NULL,
    completed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (completed_chunks >= 0),
    total_chunks INTEGER CHECK (total_chunks > 0 AND completed_chunks <= total_chunks),
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit INTEGER NOT NULL CHECK (attempt_limit > 0),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_retry_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(64),
    error_retryable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (meeting_id, id),
    UNIQUE (meeting_id, idempotency_key),
    FOREIGN KEY (meeting_id, revision_id) REFERENCES transcript_revisions(meeting_id, id),
    CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
    CHECK ((status IN ('PROCESSING', 'CANCEL_REQUESTED')) = (lease_owner IS NOT NULL)),
    CHECK ((status IN ('COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')) = (completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX analysis_one_active_per_meeting ON analysis_jobs(meeting_id)
    WHERE status IN ('QUEUED', 'PROCESSING', 'CANCEL_REQUESTED');
CREATE INDEX analysis_claim_idx ON analysis_jobs(status, next_retry_at, lease_expires_at, created_at);
ALTER TABLE meetings ADD COLUMN current_analysis_job_id UUID;
ALTER TABLE meetings ADD CONSTRAINT meetings_current_analysis_fk
    FOREIGN KEY (id, current_analysis_job_id) REFERENCES analysis_jobs(meeting_id, id);

-- Preparation checkpoints contain counters only, not another copy of the transcript.
CREATE TABLE analysis_checkpoints (
    job_id UUID PRIMARY KEY REFERENCES analysis_jobs(id),
    last_sequence INTEGER NOT NULL DEFAULT -1 CHECK (last_sequence >= -1),
    prepared_segments INTEGER NOT NULL DEFAULT 0 CHECK (prepared_segments >= 0),
    estimated_input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (estimated_input_tokens >= 0),
    prepared BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL
);
