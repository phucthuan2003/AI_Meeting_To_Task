-- SDS §7.6: editable review drafts. analysis_results stays the immutable AI audit copy;
-- tasks hold the user's draft with optimistic version and two independent lifecycles.
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    analysis_job_id UUID,
    origin VARCHAR(8) NOT NULL CHECK (origin IN ('AI', 'USER')),
    source_revision_id UUID REFERENCES transcript_revisions(id) ON DELETE SET NULL,
    task_ref VARCHAR(80),
    ordinal INTEGER NOT NULL DEFAULT 0 CHECK (ordinal >= 0),
    task_name VARCHAR(255) NOT NULL CHECK (length(btrim(task_name)) > 0),
    description TEXT CHECK (description IS NULL OR length(description) <= 5000),
    assignee_raw TEXT CHECK (assignee_raw IS NULL OR length(assignee_raw) <= 255),
    trello_member_id VARCHAR(64),
    member_resolution VARCHAR(16) NOT NULL DEFAULT 'MISSING'
        CHECK (member_resolution IN ('MISSING', 'SUGGESTED', 'AMBIGUOUS', 'RESOLVED', 'NONE_SELECTED')),
    deadline_raw TEXT CHECK (deadline_raw IS NULL OR length(deadline_raw) <= 255),
    due_local TIMESTAMP,
    due_at TIMESTAMPTZ,
    timezone VARCHAR(64),
    deadline_resolution VARCHAR(16) NOT NULL DEFAULT 'MISSING'
        CHECK (deadline_resolution IN ('MISSING', 'AMBIGUOUS', 'RESOLVED', 'NONE_SELECTED')),
    priority VARCHAR(6) CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    -- Original AI values for provenance; user edits never overwrite this copy.
    ai_suggestion JSONB CHECK (ai_suggestion IS NULL OR jsonb_typeof(ai_suggestion) = 'object'),
    ai_notes JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(ai_notes) = 'array'),
    edited_fields JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(edited_fields) = 'array'),
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    sync_status VARCHAR(12) NOT NULL DEFAULT 'NOT_SYNCED'
        CHECK (sync_status IN ('NOT_SYNCED', 'QUEUED', 'SYNCING', 'SYNCED', 'FAILED', 'UNKNOWN')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    include_evidence_in_card BOOLEAN NOT NULL DEFAULT FALSE,
    trello_card_id VARCHAR(64),
    trello_card_url TEXT,
    create_key VARCHAR(128),
    create_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    rejected_at TIMESTAMPTZ,
    FOREIGN KEY (meeting_id, analysis_job_id) REFERENCES analysis_jobs(meeting_id, id),
    CHECK ((origin = 'AI') = (analysis_job_id IS NOT NULL)),
    CHECK ((origin = 'AI') = (ai_suggestion IS NOT NULL)),
    CHECK (origin = 'AI' OR task_ref IS NULL),
    CHECK ((deadline_resolution = 'RESOLVED') = (due_local IS NOT NULL AND due_at IS NOT NULL)),
    CHECK (deadline_resolution <> 'RESOLVED' OR timezone IS NOT NULL),
    CHECK (member_resolution <> 'RESOLVED' OR trello_member_id IS NOT NULL),
    CHECK ((review_status = 'REJECTED') = (rejected_at IS NOT NULL)),
    CHECK ((create_key IS NULL) = (create_hash IS NULL))
);
CREATE UNIQUE INDEX tasks_job_ref_unique ON tasks(analysis_job_id, task_ref) WHERE analysis_job_id IS NOT NULL;
CREATE UNIQUE INDEX tasks_create_key_unique ON tasks(meeting_id, create_key) WHERE create_key IS NOT NULL;
CREATE INDEX tasks_meeting_idx ON tasks(meeting_id, analysis_job_id);

-- Quotes are read from the segment at display time; the model's quote is never the canonical source.
CREATE TABLE task_evidence (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    segment_id UUID REFERENCES transcript_segments(id) ON DELETE SET NULL,
    source_revision_id UUID REFERENCES transcript_revisions(id) ON DELETE SET NULL,
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    field_role VARCHAR(8) NOT NULL CHECK (field_role IN ('TASK', 'ASSIGNEE', 'DEADLINE', 'PRIORITY')),
    UNIQUE (task_id, sequence, field_role)
);

-- Backfill drafts for analyses completed before V4 so reopening an old meeting keeps its candidates.
INSERT INTO tasks(id, meeting_id, analysis_job_id, origin, source_revision_id, task_ref, ordinal, task_name,
                  assignee_raw, member_resolution, deadline_raw, timezone, deadline_resolution, priority,
                  ai_suggestion, ai_notes, created_at, updated_at)
SELECT (c.value->>'taskId')::uuid, j.meeting_id, j.id, 'AI', j.revision_id, left(c.value->>'taskRef', 80),
       (c.ordinality - 1)::int, left(c.value->>'taskName', 255), left(c.value->>'assigneeRaw', 255), 'MISSING',
       left(c.value->>'deadlineRaw', 255), j.timezone,
       CASE WHEN c.value->>'deadlineRaw' IS NULL THEN 'MISSING' ELSE 'AMBIGUOUS' END,
       CASE WHEN c.value->>'priority' IN ('LOW', 'MEDIUM', 'HIGH') THEN c.value->>'priority' END,
       jsonb_build_object('taskName', c.value->'taskName', 'assigneeRaw', c.value->'assigneeRaw',
                          'deadlineRaw', c.value->'deadlineRaw', 'priority', c.value->'priority',
                          'dueLocal', c.value->'dueLocal', 'dueAt', c.value->'dueAt', 'timezone', c.value->'timezone'),
       COALESCE((SELECT jsonb_agg(w) FROM jsonb_array_elements_text(COALESCE(c.value->'warnings', '[]'::jsonb)) w
                 WHERE w NOT IN ('AI_REVIEW_REQUIRED', 'ASSIGNEE_MISSING', 'ASSIGNEE_NOT_RESOLVED_TO_MEMBER',
                                 'DEADLINE_MISSING', 'DEADLINE_NEEDS_CONFIRMATION', 'DEADLINE_AMBIGUOUS')), '[]'::jsonb),
       r.created_at, r.created_at
FROM analysis_results r
JOIN analysis_jobs j ON j.id = r.job_id AND j.status = 'COMPLETED'
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.result_json->'candidates', '[]'::jsonb)) WITH ORDINALITY AS c(value, ordinality)
WHERE c.value->>'taskId' IS NOT NULL AND length(btrim(COALESCE(c.value->>'taskName', ''))) > 0;

INSERT INTO task_evidence(id, task_id, segment_id, source_revision_id, sequence, field_role)
SELECT DISTINCT ON (t.id, (e.value->>'sequence')::int, e.value->>'field')
       gen_random_uuid(), t.id, s.id, t.source_revision_id, (e.value->>'sequence')::int, e.value->>'field'
FROM analysis_results r
JOIN tasks t ON t.analysis_job_id = r.job_id
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.result_json->'candidates', '[]'::jsonb)) c(value)
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(c.value->'evidence', '[]'::jsonb)) e(value)
LEFT JOIN transcript_segments s ON s.id = (e.value->>'segmentId')::uuid AND s.revision_id = t.source_revision_id
WHERE (c.value->>'taskId')::uuid = t.id
  AND e.value->>'field' IN ('TASK', 'ASSIGNEE', 'DEADLINE', 'PRIORITY')
  AND (e.value->>'sequence') ~ '^[0-9]+$';
