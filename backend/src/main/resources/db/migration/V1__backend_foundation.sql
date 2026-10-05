CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_sessions_user_idx ON auth_sessions(user_id);

CREATE TABLE meetings (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(255),
    meeting_date DATE,
    timezone VARCHAR(64),
    input_version BIGINT NOT NULL DEFAULT 0,
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX meetings_owner_idx ON meetings(user_id, created_at DESC, id);

CREATE TABLE transcript_revisions (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    source_type VARCHAR(8) NOT NULL CHECK (source_type IN ('PASTE', 'TXT', 'DOCX')),
    content_hash VARCHAR(64) NOT NULL,
    raw_content TEXT,
    normalized_content TEXT,
    parser_warnings JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (meeting_id, revision),
    UNIQUE (meeting_id, id)
);
ALTER TABLE meetings ADD CONSTRAINT meetings_current_revision_fk
    FOREIGN KEY (id, current_revision_id) REFERENCES transcript_revisions(meeting_id, id);

CREATE TABLE transcript_segments (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES transcript_revisions(id),
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    speaker TEXT,
    timestamp_raw VARCHAR(16),
    text TEXT NOT NULL,
    normalized_start INTEGER NOT NULL,
    normalized_end INTEGER NOT NULL,
    source_locator JSONB NOT NULL,
    CHECK (normalized_end >= normalized_start),
    UNIQUE (revision_id, sequence)
);
