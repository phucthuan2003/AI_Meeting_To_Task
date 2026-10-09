-- SDS §7.3, §7.7, §7.8: Trello connection, destination, immutable snapshots and sync items.
CREATE TABLE trello_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    trello_member_id VARCHAR(64) NOT NULL,
    trello_username VARCHAR(255),
    trello_full_name VARCHAR(255),
    auth_type VARCHAR(8) NOT NULL CHECK (auth_type IN ('OAUTH2', 'TOKEN')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')),
    access_token_enc TEXT,
    refresh_token_enc TEXT,
    expires_at TIMESTAMPTZ,
    scopes VARCHAR(512),
    key_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    disconnected_at TIMESTAMPTZ,
    CHECK (status <> 'DISCONNECTED' OR (access_token_enc IS NULL AND refresh_token_enc IS NULL)),
    CHECK (status <> 'ACTIVE' OR access_token_enc IS NOT NULL),
    UNIQUE (user_id, id)
);
-- MVP: one usable connection per user (SDS §1.4).
CREATE UNIQUE INDEX trello_one_connection_per_user ON trello_connections(user_id) WHERE status IN ('ACTIVE', 'REAUTH_REQUIRED');

CREATE TABLE authorization_transactions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    session_id UUID NOT NULL,
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    pkce_verifier_enc TEXT NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'DENIED', 'FAILED', 'EXPIRED')),
    error_code VARCHAR(64),
    connection_id UUID REFERENCES trello_connections(id),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'PENDING') = (consumed_at IS NULL))
);

CREATE TABLE meeting_destinations (
    meeting_id UUID PRIMARY KEY REFERENCES meetings(id),
    connection_id UUID NOT NULL REFERENCES trello_connections(id),
    trello_member_id VARCHAR(64) NOT NULL,
    board_id VARCHAR(64) NOT NULL,
    board_name VARCHAR(512),
    list_id VARCHAR(64) NOT NULL,
    list_name VARCHAR(512),
    board_members JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(board_members) = 'array'),
    members_fetched_at TIMESTAMPTZ,
    version BIGINT NOT NULL CHECK (version > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE member_aliases (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    trello_identity VARCHAR(64) NOT NULL,
    board_id VARCHAR(64) NOT NULL,
    alias VARCHAR(255) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, trello_identity, board_id, alias)
);

ALTER TABLE tasks ADD COLUMN member_candidates JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(member_candidates) = 'array');
ALTER TABLE tasks ADD COLUMN member_destination_version BIGINT;
ALTER TABLE tasks ADD CONSTRAINT tasks_member_needs_destination
    CHECK (member_resolution NOT IN ('RESOLVED', 'SUGGESTED', 'AMBIGUOUS') OR member_destination_version IS NOT NULL);

-- Immutable within the processing lifecycle; content may be scrubbed after retention (scrubbed_at).
CREATE TABLE task_snapshots (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    task_version BIGINT NOT NULL,
    destination_version BIGINT NOT NULL,
    trello_identity VARCHAR(64) NOT NULL,
    board_id VARCHAR(64) NOT NULL,
    list_id VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL CHECK (jsonb_typeof(payload_json) = 'object'),
    payload_hash VARCHAR(64) NOT NULL,
    approved_by UUID NOT NULL REFERENCES users(id),
    approved_at TIMESTAMPTZ NOT NULL,
    scrubbed_at TIMESTAMPTZ
);

CREATE TABLE sync_jobs (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'NEEDS_ACTION')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX sync_jobs_meeting_idx ON sync_jobs(meeting_id, created_at DESC);

CREATE TABLE sync_items (
    id UUID PRIMARY KEY,
    sync_job_id UUID NOT NULL REFERENCES sync_jobs(id),
    task_id UUID NOT NULL REFERENCES tasks(id),
    snapshot_id UUID NOT NULL REFERENCES task_snapshots(id),
    status VARCHAR(12) NOT NULL CHECK (status IN ('QUEUED', 'SYNCING', 'SYNCED', 'FAILED', 'UNKNOWN', 'SUPERSEDED')),
    dispatch_state VARCHAR(16) NOT NULL DEFAULT 'NOT_DISPATCHED' CHECK (dispatch_state IN ('NOT_DISPATCHED', 'DISPATCHED', 'RESOLVED')),
    reference_marker VARCHAR(80) NOT NULL,
    card_id VARCHAR(64),
    card_url TEXT,
    error_code VARCHAR(64),
    error_retryable BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    reconcile_count INTEGER NOT NULL DEFAULT 0 CHECK (reconcile_count >= 0),
    next_retry_at TIMESTAMPTZ NOT NULL,
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
    CHECK ((status = 'SYNCING') = (lease_owner IS NOT NULL)),
    CHECK (status <> 'SYNCED' OR card_id IS NOT NULL)
);
-- One active, unknown or synced item per task (SDS §3.3, §7.8). FAILED/SUPERSEDED stay for audit.
CREATE UNIQUE INDEX sync_items_one_live_per_task ON sync_items(task_id) WHERE status IN ('QUEUED', 'SYNCING', 'UNKNOWN', 'SYNCED');
CREATE INDEX sync_items_claim_idx ON sync_items(status, next_retry_at);
CREATE INDEX sync_items_job_idx ON sync_items(sync_job_id);

CREATE TABLE sync_attempts (
    id UUID PRIMARY KEY,
    sync_item_id UUID NOT NULL REFERENCES sync_items(id),
    attempt_no INTEGER NOT NULL,
    phase VARCHAR(12) NOT NULL CHECK (phase IN ('VALIDATE', 'DISPATCH', 'RECONCILE', 'LINK', 'RECREATE', 'RECOVERY')),
    outcome VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    http_status INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sync_attempts_item_idx ON sync_attempts(sync_item_id, started_at);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    meeting_id UUID,
    task_id UUID,
    event_type VARCHAR(40) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX audit_events_user_idx ON audit_events(user_id, created_at DESC);
