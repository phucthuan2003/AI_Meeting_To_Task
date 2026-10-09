package vn.aimtt.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob;
import vn.aimtt.llm.Extraction;

@Repository
public class TaskStore {
    /** Meeting fields that decide whether a task belongs to the current review set. */
    public record MeetingContext(UUID id, long inputVersion, UUID revisionId, LocalDate meetingDate, String timezone, UUID currentJobId, String title) {}
    public record Evidence(UUID taskId, UUID segmentId, int sequence, String field, String quote, String speaker,
                           String timestamp, String sourceLocator, boolean sourceAvailable) {}
    public record MeetingStats(String analysisStatus, int pendingTasks, int rejectedTasks) {}
    public record SyncInfo(UUID syncItemId, UUID syncJobId, String status, String cardId, String cardUrl, String errorCode, boolean retryable,
                           int reconcileCount, long snapshotDestinationVersion) {}

    static final Set<String> SERVER_CODES = Set.of("AI_REVIEW_REQUIRED", "ASSIGNEE_MISSING", "ASSIGNEE_NOT_RESOLVED_TO_MEMBER",
            "DEADLINE_MISSING", "DEADLINE_NEEDS_CONFIRMATION", "DEADLINE_AMBIGUOUS");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final RowMapper<TaskRow> mapper;

    public TaskStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.json = json;
        this.mapper = (r, n) -> new TaskRow(r.getObject("id", UUID.class), r.getObject("meeting_id", UUID.class),
                r.getObject("analysis_job_id", UUID.class), r.getString("origin"), r.getObject("source_revision_id", UUID.class),
                r.getString("task_ref"), r.getInt("ordinal"), r.getString("task_name"), r.getString("description"),
                r.getString("assignee_raw"), r.getString("trello_member_id"), r.getString("member_resolution"),
                r.getString("deadline_raw"), r.getObject("due_local", LocalDateTime.class), instant(r, "due_at"), r.getString("timezone"),
                r.getString("deadline_resolution"), r.getString("priority"), decode(r.getString("ai_suggestion"), MAP),
                decode(r.getString("ai_notes"), LIST), decode(r.getString("edited_fields"), LIST), r.getString("review_status"),
                r.getString("sync_status"), r.getLong("version"), r.getBoolean("include_evidence_in_card"), r.getString("trello_card_id"),
                r.getString("trello_card_url"), instant(r, "created_at"), instant(r, "updated_at"), instant(r, "rejected_at"),
                decode(r.getString("member_candidates"), MAPS), r.getObject("member_destination_version", Long.class));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private <T> T decode(String value, TypeReference<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored task JSON"); }
    }
    private String encode(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("Task JSON encoding failed"); }
    }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    public Instant now() { return jdbc.queryForObject("select clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant()); }

    private static final RowMapper<MeetingContext> MEETING = (r, n) -> new MeetingContext(r.getObject("id", UUID.class),
            r.getLong("input_version"), r.getObject("current_revision_id", UUID.class), r.getObject("meeting_date", LocalDate.class),
            r.getString("timezone"), r.getObject("current_analysis_job_id", UUID.class), r.getString("title"));
    private static final String MEETING_SQL = "select id, input_version, current_revision_id, meeting_date, timezone, current_analysis_job_id, title from meetings where id = ? and user_id = ?";

    public MeetingContext meeting(UUID owner, UUID meetingId) {
        return jdbc.query(MEETING_SQL, MEETING, meetingId, owner).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public MeetingContext lockMeeting(UUID owner, UUID meetingId) {
        return jdbc.query(MEETING_SQL + " for update", MEETING, meetingId, owner).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    /** Owner check without locking; callers lock the meeting before locking the task row. */
    public UUID ownedMeetingOf(UUID owner, UUID taskId) {
        return jdbc.query("select t.meeting_id from tasks t join meetings m on m.id = t.meeting_id where t.id = ? and m.user_id = ?",
                (r, n) -> r.getObject(1, UUID.class), taskId, owner).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public TaskRow lock(UUID meetingId, UUID taskId) {
        return jdbc.query("select * from tasks where id = ? and meeting_id = ? for update", mapper, taskId, meetingId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public TaskRow get(UUID taskId) {
        return jdbc.query("select * from tasks where id = ?", mapper, taskId).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    /** USER tasks always belong to the meeting; AI tasks only for the published current analysis. */
    public List<TaskRow> current(UUID meetingId, UUID aiJobId) {
        return jdbc.query("""
                select * from tasks where meeting_id = ? and (origin = 'USER' or analysis_job_id = ?)
                order by case origin when 'AI' then 0 else 1 end, ordinal, created_at, id
                """, mapper, meetingId, aiJobId);
    }

    public Optional<TaskRow> byCreateKey(UUID meetingId, String key) {
        return jdbc.query("select * from tasks where meeting_id = ? and create_key = ?", mapper, meetingId, key).stream().findFirst();
    }
    public Optional<String> createHash(UUID taskId) {
        return jdbc.query("select create_hash from tasks where id = ?", (r, n) -> r.getString(1), taskId).stream().findFirst();
    }

    /** Called inside the job-completion transaction so results and drafts are published together. */
    public void insertCandidates(AnalysisJob job, List<Extraction.Candidate> candidates, Instant now) {
        int ordinal = 0;
        for (var candidate : candidates) {
            var suggestion = new LinkedHashMap<String, Object>();
            suggestion.put("taskName", candidate.taskName()); suggestion.put("assigneeRaw", candidate.assigneeRaw());
            suggestion.put("deadlineRaw", candidate.deadlineRaw()); suggestion.put("priority", candidate.priority());
            suggestion.put("dueLocal", candidate.dueLocal()); suggestion.put("dueAt", candidate.dueAt()); suggestion.put("timezone", candidate.timezone());
            var notes = candidate.warnings().stream().filter(w -> !SERVER_CODES.contains(w)).distinct().toList();
            jdbc.update("""
                    insert into tasks(id, meeting_id, analysis_job_id, origin, source_revision_id, task_ref, ordinal, task_name,
                    assignee_raw, member_resolution, deadline_raw, timezone, deadline_resolution, priority, ai_suggestion, ai_notes,
                    created_at, updated_at) values (?, ?, ?, 'AI', ?, ?, ?, ?, ?, 'MISSING', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                    """, candidate.taskId(), job.meetingId(), job.id(), job.revisionId(), candidate.taskRef(), ordinal++,
                    candidate.taskName(), candidate.assigneeRaw(), candidate.deadlineRaw(), job.timezone(),
                    candidate.deadlineRaw() == null ? "MISSING" : "AMBIGUOUS", candidate.priority(), encode(suggestion), encode(notes),
                    ts(now), ts(now));
            var seen = new HashSet<String>();
            for (var quote : candidate.evidence()) {
                if (!seen.add(quote.sequence() + ":" + quote.field())) continue;
                jdbc.update("insert into task_evidence(id, task_id, segment_id, source_revision_id, sequence, field_role) values (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), candidate.taskId(), quote.segmentId(), job.revisionId(), quote.sequence(), quote.field());
            }
        }
    }

    public TaskRow insertManual(UUID meetingId, TaskRow.Draft draft, String key, String hash, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into tasks(id, meeting_id, origin, task_name, description, assignee_raw, member_resolution, deadline_raw,
                due_local, due_at, timezone, deadline_resolution, priority, include_evidence_in_card, create_key, create_hash, created_at, updated_at)
                values (?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?)
                """, id, meetingId, draft.taskName(), draft.description(), draft.assigneeRaw(), draft.memberResolution(), draft.deadlineRaw(),
                draft.dueLocal(), ts(draft.dueAt()), draft.timezone(), draft.deadlineResolution(), draft.priority(), key, hash, ts(now), ts(now));
        return get(id);
    }

    /** Compare-and-update on version (SDS §7.8). Empty means another writer won. */
    public Optional<TaskRow> update(TaskRow current, TaskRow.Draft draft, List<String> editedFields, String reviewStatus, Instant rejectedAt, Instant now) {
        return update(current, draft, editedFields, reviewStatus, current.syncStatus(), rejectedAt, now);
    }
    public Optional<TaskRow> update(TaskRow current, TaskRow.Draft draft, List<String> editedFields, String reviewStatus, String syncStatus, Instant rejectedAt, Instant now) {
        // Candidates are only kept while the member decision is still an unconfirmed suggestion.
        boolean keepCandidates = Objects.equals(draft.memberResolution(), current.memberResolution()) && Objects.equals(draft.trelloMemberId(), current.trelloMemberId());
        int changed = jdbc.update("""
                update tasks set task_name = ?, description = ?, assignee_raw = ?, trello_member_id = ?, member_resolution = ?,
                deadline_raw = ?, due_local = ?, due_at = ?, timezone = ?, deadline_resolution = ?, priority = ?,
                include_evidence_in_card = ?, edited_fields = ?::jsonb, review_status = ?, sync_status = ?, rejected_at = ?,
                member_destination_version = ?, member_candidates = case when ? then member_candidates else '[]'::jsonb end,
                version = version + 1, updated_at = ? where id = ? and version = ?
                """, draft.taskName(), draft.description(), draft.assigneeRaw(), draft.trelloMemberId(), draft.memberResolution(),
                draft.deadlineRaw(), draft.dueLocal(), ts(draft.dueAt()), draft.timezone(), draft.deadlineResolution(), draft.priority(),
                draft.includeEvidenceInCard(), encode(editedFields), reviewStatus, syncStatus, ts(rejectedAt),
                List.of("RESOLVED", "SUGGESTED", "AMBIGUOUS").contains(draft.memberResolution()) ? draft.memberDestinationVersion() : null, keepCandidates,
                ts(now), current.id(), current.version());
        return changed == 1 ? Optional.of(get(current.id())) : Optional.empty();
    }

    public List<Evidence> evidence(Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) return List.of();
        return named.query("""
                select e.task_id, e.segment_id, e.sequence, e.field_role, s.text, s.speaker, s.timestamp_raw, s.source_locator::text as locator,
                       (s.id is not null and r.raw_content is not null and r.normalized_content is not null) as available
                from task_evidence e
                left join transcript_segments s on s.id = e.segment_id
                left join transcript_revisions r on r.id = e.source_revision_id
                where e.task_id in (:ids) order by e.task_id, e.sequence, e.field_role
                """, new MapSqlParameterSource("ids", taskIds), (r, n) -> {
            boolean available = r.getBoolean("available");
            return new Evidence(r.getObject("task_id", UUID.class), r.getObject("segment_id", UUID.class), r.getInt("sequence"),
                    r.getString("field_role"), available ? r.getString("text") : null, available ? r.getString("speaker") : null,
                    available ? r.getString("timestamp_raw") : null, available ? r.getString("locator") : null, available);
        });
    }

    /** History summary: current analysis status and open/rejected draft counts, all owner-scoped by the caller. */
    public Map<UUID, MeetingStats> stats(Collection<UUID> meetingIds) {
        if (meetingIds.isEmpty()) return Map.of();
        var result = new HashMap<UUID, MeetingStats>();
        named.query("""
                select m.id,
                  (select j.status from analysis_jobs j where j.id = m.current_analysis_job_id
                     and j.revision_id = m.current_revision_id and j.input_version = m.input_version) as status,
                  (select count(*) from tasks t where t.meeting_id = m.id and t.review_status = 'PENDING_REVIEW' and
                     (t.origin = 'USER' or (t.analysis_job_id = m.current_analysis_job_id and exists (select 1 from analysis_jobs j
                      where j.id = m.current_analysis_job_id and j.status = 'COMPLETED' and j.revision_id = m.current_revision_id
                      and j.input_version = m.input_version)))) as pending,
                  (select count(*) from tasks t where t.meeting_id = m.id and t.review_status = 'REJECTED' and
                     (t.origin = 'USER' or t.analysis_job_id = m.current_analysis_job_id)) as rejected
                from meetings m where m.id in (:ids)
                """, new MapSqlParameterSource("ids", meetingIds), r -> {
            String status = r.getString("status");
            result.put(r.getObject("id", UUID.class), new MeetingStats(status == null ? "NOT_STARTED" : status, r.getInt("pending"), r.getInt("rejected")));
        });
        return result;
    }

    /** Latest non-superseded sync item per task, for the review/result view. */
    public Map<UUID, SyncInfo> latestSync(Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        var result = new HashMap<UUID, SyncInfo>();
        named.query("""
                select distinct on (i.task_id) i.*, s.destination_version from sync_items i join task_snapshots s on s.id = i.snapshot_id
                where i.task_id in (:ids) and i.status <> 'SUPERSEDED' order by i.task_id, i.created_at desc
                """, new MapSqlParameterSource("ids", taskIds), r -> {
            result.put(r.getObject("task_id", UUID.class), new SyncInfo(r.getObject("id", UUID.class), r.getObject("sync_job_id", UUID.class),
                    r.getString("status"), r.getString("card_id"), r.getString("card_url"), r.getString("error_code"), r.getBoolean("error_retryable"),
                    r.getInt("reconcile_count"), r.getLong("destination_version")));
        });
        return result;
    }
}
