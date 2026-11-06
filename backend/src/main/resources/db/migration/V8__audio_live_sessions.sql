CREATE TABLE capture_sessions (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), mode VARCHAR(8) NOT NULL CHECK(mode IN ('FILE','LIVE')),
 status VARCHAR(16) NOT NULL CHECK(status IN ('OPEN','DRAINING','READY','FAILED','CANCELLED','PUBLISHED')),
 title VARCHAR(255), meeting_date DATE, timezone VARCHAR(64), provider_id VARCHAR(16), model VARCHAR(128),
 prompt_version VARCHAR(80), schema_version VARCHAR(80), stt_model VARCHAR(80) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, next_sequence INTEGER NOT NULL DEFAULT 0, processed_sequence INTEGER NOT NULL DEFAULT -1,
 sources JSONB NOT NULL DEFAULT '[]', events JSONB NOT NULL DEFAULT '[]', overrides JSONB NOT NULL DEFAULT '{}',
 llm_calls INTEGER NOT NULL DEFAULT 0, llm_input_tokens BIGINT DEFAULT 0, llm_output_tokens BIGINT DEFAULT 0, llm_latency_ms BIGINT NOT NULL DEFAULT 0,
 error_code VARCHAR(80), meeting_id UUID REFERENCES meetings(id) ON DELETE SET NULL,
 create_key VARCHAR(128) NOT NULL, lease_id UUID, lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '24 hours',
 UNIQUE(user_id,create_key)
);
CREATE TABLE capture_chunks (
 session_id UUID NOT NULL REFERENCES capture_sessions(id) ON DELETE CASCADE, sequence INTEGER NOT NULL,
 content_hash VARCHAR(64) NOT NULL, suffix VARCHAR(8) NOT NULL, audio BYTEA, start_ms BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','PROCESSING','TRANSCRIBED','COMPLETED','FAILED')),
 transcript JSONB, error_code VARCHAR(80), PRIMARY KEY(session_id,sequence)
);
CREATE INDEX capture_queue_idx ON capture_sessions(created_at) WHERE status IN ('OPEN','DRAINING');

ALTER TABLE transcript_revisions DROP CONSTRAINT transcript_revisions_source_type_check;
ALTER TABLE transcript_revisions ADD CONSTRAINT transcript_revisions_source_type_check CHECK(source_type IN ('PASTE','TXT','DOCX','VTT','SRT','AUDIO'));
