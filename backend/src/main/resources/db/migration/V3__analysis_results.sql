CREATE TABLE analysis_results (
    job_id UUID PRIMARY KEY REFERENCES analysis_jobs(id),
    result_json JSONB NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
    created_at TIMESTAMPTZ NOT NULL
);
