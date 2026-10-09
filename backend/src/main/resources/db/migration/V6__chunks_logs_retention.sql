-- SDS §5.6, §7.5: chunk checkpoints for long transcripts. Output JSON has evidence refs and is purged with the transcript.
CREATE TABLE chunk_results (
    job_id UUID NOT NULL REFERENCES analysis_jobs(id),
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    first_sequence INTEGER NOT NULL,
    last_sequence INTEGER NOT NULL,
    overlap_until INTEGER NOT NULL DEFAULT -1,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    output_json JSONB,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    input_tokens BIGINT,
    output_tokens BIGINT,
    latency_ms BIGINT,
    error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_id, chunk_index),
    CHECK (last_sequence >= first_sequence),
    CHECK (status <> 'COMPLETED' OR output_json IS NOT NULL)
);

-- SDS §7.9: usage and outcome per LLM call, never prompt/transcript/secret content; kept after source purge.
CREATE TABLE processing_logs (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES analysis_jobs(id),
    chunk_index INTEGER NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    latency_ms BIGINT,
    attempt INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX processing_logs_job_idx ON processing_logs(job_id, created_at);
CREATE INDEX processing_logs_created_idx ON processing_logs(created_at);

-- SDS §10.3 retention bookkeeping.
ALTER TABLE transcript_revisions ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE analysis_results ADD COLUMN scrubbed_at TIMESTAMPTZ;
CREATE INDEX transcript_revisions_expiry_idx ON transcript_revisions(expires_at) WHERE purged_at IS NULL;
