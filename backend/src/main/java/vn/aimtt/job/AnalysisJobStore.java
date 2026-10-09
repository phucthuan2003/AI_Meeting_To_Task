package vn.aimtt.job;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob.*;
import vn.aimtt.llm.Extraction;
import vn.aimtt.task.TaskStore;

@Repository
public class AnalysisJobStore {
    private final JdbcTemplate jdbc;
    private final AnalysisProperties properties;
    private final TaskStore tasks;
    public AnalysisJobStore(JdbcTemplate jdbc, AnalysisProperties properties, TaskStore tasks) { this.jdbc = jdbc; this.properties = properties; this.tasks = tasks; }
    public record MeetingSnapshot(UUID id, UUID revisionId, long inputVersion, String title,
                                  LocalDate meetingDate, String timezone, boolean sourceAvailable) {}
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static final RowMapper<AnalysisJob> MAPPER = (r, row) -> new AnalysisJob(
            r.getObject("id", UUID.class), r.getObject("meeting_id", UUID.class), r.getObject("revision_id", UUID.class),
            r.getLong("input_version"), r.getString("title"), r.getObject("meeting_date", LocalDate.class), r.getString("timezone"),
            r.getString("provider_id"), r.getString("model"), r.getString("policy_id"), r.getString("prompt_version"), r.getString("schema_version"),
            r.getInt("context_tokens"), r.getInt("reserved_tokens"), r.getString("idempotency_key"), Status.valueOf(r.getString("status")),
            r.getString("stage"), r.getInt("completed_chunks"), r.getObject("total_chunks", Integer.class), r.getObject("lease_owner", UUID.class),
            instant(r, "lease_expires_at"), r.getInt("attempt_count"), r.getInt("attempt_limit"), r.getInt("retry_count"), instant(r, "next_retry_at"),
            r.getString("error_code"), r.getBoolean("error_retryable"), instant(r, "created_at"), instant(r, "updated_at"), instant(r, "completed_at"));
    private Optional<AnalysisJob> one(String sql, Object... args) { return jdbc.query(sql, MAPPER, args).stream().findFirst(); }
    public Instant now() { return jdbc.queryForObject("select clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant()); }
    public void lockOwner(UUID owner) {
        if (jdbc.query("select id from users where id = ? for update", (r, n) -> r.getObject(1, UUID.class), owner).isEmpty()) throw ApiException.notFound();
    }
    public MeetingSnapshot lockMeeting(UUID owner, UUID id) {
        return jdbc.query("""
                select m.*, (r.raw_content is not null and r.normalized_content is not null) as source_available
                from meetings m join transcript_revisions r on r.id = m.current_revision_id
                where m.id = ? and m.user_id = ? for update of m
                """, (r, n) -> new MeetingSnapshot(r.getObject("id", UUID.class), r.getObject("current_revision_id", UUID.class),
                r.getLong("input_version"), r.getString("title"), r.getObject("meeting_date", LocalDate.class),
                r.getString("timezone"), r.getBoolean("source_available")), id, owner).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public AnalysisJob owned(UUID owner, UUID id) {
        return one("select j.* from analysis_jobs j join meetings m on m.id = j.meeting_id where j.id = ? and m.user_id = ?", id, owner)
                .orElseThrow(ApiException::notFound);
    }
    public AnalysisJob locked(UUID id) { return one("select * from analysis_jobs where id = ? for update", id).orElseThrow(ApiException::notFound); }
    public AnalysisJob get(UUID id) { return one("select * from analysis_jobs where id = ?", id).orElseThrow(ApiException::notFound); }
    public Optional<AnalysisJob> byKey(UUID meeting, String key) { return one("select * from analysis_jobs where meeting_id = ? and idempotency_key = ?", meeting, key); }
    public boolean active(UUID meeting) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from analysis_jobs where meeting_id = ? and status in ('QUEUED','PROCESSING','CANCEL_REQUESTED'))", Boolean.class, meeting));
    }
    public int activeForOwner(UUID owner) {
        return jdbc.queryForObject("select count(*) from analysis_jobs j join meetings m on m.id = j.meeting_id where m.user_id = ? and j.status in ('QUEUED','PROCESSING','CANCEL_REQUESTED')", Integer.class, owner);
    }
    public Optional<AnalysisJob> current(UUID meeting, UUID revision, long version) {
        return one("""
                select j.* from analysis_jobs j join meetings m on m.current_analysis_job_id = j.id
                where m.id = ? and j.revision_id = ? and j.input_version = ?
                """, meeting, revision, version);
    }
    public void clearCurrent(UUID meeting) { jdbc.update("update meetings set current_analysis_job_id = null where id = ?", meeting); }
    public AnalysisJob create(MeetingSnapshot meeting, String key, AnalysisPolicy.Definition policy) {
        UUID id = UUID.randomUUID(); Instant now = now();
        jdbc.update("""
                insert into analysis_jobs(id, meeting_id, revision_id, input_version, title, meeting_date, timezone,
                provider_id, model, policy_id, prompt_version, schema_version, context_tokens, reserved_tokens,
                idempotency_key, status, stage, attempt_limit, next_retry_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 'WAITING', ?, ?, ?, ?)
                """, id, meeting.id(), meeting.revisionId(), meeting.inputVersion(), meeting.title(), meeting.meetingDate(), meeting.timezone(),
                policy.providerId(), policy.model(), policy.policyId(), policy.promptVersion(), policy.schemaVersion(), policy.contextTokens(), policy.reservedTokens(),
                key, properties.maxAttempts(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into analysis_checkpoints(job_id, updated_at) values (?, ?)", id, Timestamp.from(now));
        // Updating the job pointer must not increment the meeting's input version.
        jdbc.update("update meetings set current_analysis_job_id = ? where id = ?", id, meeting.id());
        return get(id);
    }
    public Checkpoint checkpoint(UUID id) {
        return jdbc.queryForObject("select * from analysis_checkpoints where job_id = ?", (r, n) -> new Checkpoint(
                r.getInt("last_sequence"), r.getInt("prepared_segments"), r.getLong("estimated_input_tokens"), r.getBoolean("prepared")), id);
    }

    @Transactional
    public Optional<Lease> claim() {
        Instant now = now();
        var eligible = one("""
                select * from analysis_jobs
                where (status = 'QUEUED' and next_retry_at <= ?)
                   or (status in ('PROCESSING','CANCEL_REQUESTED') and lease_expires_at <= ?)
                order by created_at, id limit 1 for update skip locked
                """, Timestamp.from(now), Timestamp.from(now));
        if (eligible.isEmpty()) return Optional.empty();
        var job = eligible.get();
        if (job.status() == Status.CANCEL_REQUESTED) {
            finishLocked(job, Status.CANCELLED, null, false, now); return Optional.empty();
        }
        if (job.attemptCount() >= job.attemptLimit()) {
            finishLocked(job, Status.FAILED, "LEASE_RECOVERY_EXHAUSTED", true, now); return Optional.empty();
        }
        UUID owner = UUID.randomUUID();
        String stage = checkpoint(job.id()).prepared() ? "READY_FOR_PROVIDER" : "PREPARING_INPUT";
        jdbc.update("""
                update analysis_jobs set status = 'PROCESSING', stage = ?, lease_owner = ?, lease_expires_at = ?,
                attempt_count = attempt_count + 1, updated_at = ? where id = ?
                """, stage, owner, Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now), job.id());
        return Optional.of(new Lease(job.id(), owner, job.attemptCount() + 1));
    }
    public SourceBatch source(AnalysisJob job, int after, int limit) {
        // A single SQL snapshot checks availability and reads only the pinned revision.
        record Row(boolean available, Integer sequence, String text) {}
        var rows = jdbc.query("""
                select (r.raw_content is not null and r.normalized_content is not null) as available, s.sequence, s.text
                from transcript_revisions r left join lateral (
                    select sequence, text from transcript_segments where revision_id = r.id and sequence > ?
                    order by sequence limit ?
                ) s on true where r.id = ? and r.meeting_id = ?
                """, (r, n) -> new Row(r.getBoolean("available"), r.getObject("sequence", Integer.class), r.getString("text")),
                after, limit, job.revisionId(), job.meetingId());
        if (rows.isEmpty() || !rows.get(0).available()) return new SourceBatch(false, List.of());
        return new SourceBatch(true, rows.stream().filter(r -> r.sequence() != null).map(r -> new SourceSegment(r.sequence(), r.text())).toList());
    }
    public List<Extraction.Source> analysisSource(AnalysisJob job) {
        record Row(boolean available, UUID id, Integer sequence, String text) {}
        var rows = jdbc.query("""
                select (r.raw_content is not null and r.normalized_content is not null) as available, s.id, s.sequence, s.text
                from transcript_revisions r left join transcript_segments s on s.revision_id = r.id
                where r.id = ? and r.meeting_id = ? order by s.sequence
                """, (r, n) -> new Row(r.getBoolean("available"), r.getObject("id", UUID.class), r.getObject("sequence", Integer.class), r.getString("text")), job.revisionId(), job.meetingId());
        if (rows.isEmpty() || !rows.get(0).available()) throw new JobFailure("SOURCE_UNAVAILABLE", false);
        return rows.stream().filter(r -> r.id() != null).map(r -> new Extraction.Source(r.id(), r.sequence(), r.text())).toList();
    }
    public Optional<AnalysisJob> currentOwned(UUID owner, UUID meetingId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from meetings where id = ? and user_id = ?)", Boolean.class, meetingId, owner))) throw ApiException.notFound();
        return one("""
                select j.* from analysis_jobs j join meetings m on m.current_analysis_job_id = j.id
                where m.id = ? and m.user_id = ? and j.revision_id = m.current_revision_id and j.input_version = m.input_version
                """, meetingId, owner);
    }
    public Optional<String> result(UUID job) { return jdbc.query("select result_json::text from analysis_results where job_id = ?", (r, n) -> r.getString(1), job).stream().findFirst(); }
    @Transactional
    public boolean beginProvider(Lease lease) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        jdbc.update("update analysis_jobs set stage = 'CALLING_LLM', updated_at = ? where id = ?", Timestamp.from(now), job.id()); return true;
    }
    @Transactional
    public boolean complete(Lease lease, String result, Extraction.Result metrics) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select raw_content is not null and normalized_content is not null from transcript_revisions where id = ?", Boolean.class, job.revisionId()))) throw new JobFailure("SOURCE_UNAVAILABLE", false);
        jdbc.update("""
                insert into analysis_results(job_id, result_json, input_tokens, output_tokens, latency_ms, created_at)
                values (?, ?::jsonb, ?, ?, ?, ?)
                """, job.id(), result, metrics.inputTokens(), metrics.outputTokens(), metrics.latencyMs(), Timestamp.from(now));
        // Review drafts are published in the same transaction as COMPLETED; a cancelled/lost lease publishes neither.
        tasks.insertCandidates(job, metrics.candidates(), now);
        jdbc.update("update analysis_jobs set total_chunks = coalesce(total_chunks, 1), completed_chunks = coalesce(total_chunks, 1) where id = ?", job.id());
        finishLocked(job, Status.COMPLETED, null, false, now); return true;
    }
    private boolean usableLocked(AnalysisJob job, Lease lease, Instant now) {
        if (!job.heldBy(lease, now)) return false;
        if (job.status() == Status.CANCEL_REQUESTED) {
            finishLocked(job, Status.CANCELLED, null, false, now); return false;
        }
        return true;
    }
    @Transactional
    public boolean saveCheckpoint(Lease lease, Checkpoint expected, List<SourceSegment> batch, long tokens, boolean prepared) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        var current = checkpoint(job.id());
        if (current.lastSequence() != expected.lastSequence() || current.prepared() != expected.prepared()) return false;
        int last = batch.isEmpty() ? current.lastSequence() : batch.get(batch.size() - 1).sequence();
        jdbc.update("""
                update analysis_checkpoints set last_sequence = ?, prepared_segments = prepared_segments + ?,
                estimated_input_tokens = estimated_input_tokens + ?, prepared = ?, updated_at = ? where job_id = ?
                """, last, batch.size(), tokens, prepared, Timestamp.from(now), job.id());
        jdbc.update("""
                update analysis_jobs set stage = ?, total_chunks = ?, lease_expires_at = ?, updated_at = ? where id = ?
                """, prepared ? "READY_FOR_PROVIDER" : "PREPARING_INPUT", prepared ? 1 : null,
                Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now), job.id());
        return true;
    }
    @Transactional
    public boolean heartbeat(Lease lease) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        jdbc.update("update analysis_jobs set lease_expires_at = ?, updated_at = ? where id = ?",
                Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now), job.id());
        return true;
    }
    @Transactional
    public boolean fail(Lease lease, String code, boolean retryable) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        finishLocked(job, Status.FAILED, code, retryable, now); return true;
    }
    private void finishLocked(AnalysisJob job, Status status, String code, boolean retryable, Instant now) {
        jdbc.update("""
                update analysis_jobs set status = ?, stage = ?, error_code = ?, error_retryable = ?,
                lease_owner = null, lease_expires_at = null, completed_at = ?, updated_at = ? where id = ?
                """, status.name(), status.name(), code, retryable, Timestamp.from(now), Timestamp.from(now), job.id());
    }
    public AnalysisJob cancelLocked(AnalysisJob job) {
        if (!job.status().active() || job.status() == Status.CANCEL_REQUESTED) return job;
        Instant now = now();
        if (job.status() == Status.QUEUED) finishLocked(job, Status.CANCELLED, null, false, now);
        else jdbc.update("update analysis_jobs set status = 'CANCEL_REQUESTED', stage = 'CANCELLING', updated_at = ? where id = ?", Timestamp.from(now), job.id());
        return get(job.id());
    }
    public AnalysisJob retryLocked(AnalysisJob job) {
        Instant now = now();
        jdbc.update("""
                update analysis_jobs set status = 'QUEUED', stage = 'WAITING', error_code = null, error_retryable = false,
                completed_at = null, next_retry_at = ?, updated_at = ?, retry_count = retry_count + 1, attempt_limit = ? where id = ?
                """, Timestamp.from(now), Timestamp.from(now), job.attemptCount() + properties.maxAttempts(), job.id());
        jdbc.update("update meetings set current_analysis_job_id = ? where id = ?", job.id(), job.meetingId());
        return get(job.id());
    }
    // ---------- chunk checkpoints (SDS §5.6, §7.5) ----------
    public record ChunkRow(int index, int firstSequence, int lastSequence, int overlapUntil, String status, String outputJson, int attempts) {}
    public List<ChunkRow> chunks(UUID jobId) {
        return jdbc.query("select chunk_index, first_sequence, last_sequence, overlap_until, status, output_json::text as output, attempts from chunk_results where job_id = ? order by chunk_index",
                (r, n) -> new ChunkRow(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getString(5), r.getString(6), r.getInt(7)), jobId);
    }
    /** Persists the chunk plan once; a resumed or retried job keeps the same boundaries. */
    @Transactional
    public boolean savePlan(Lease lease, List<ChunkRow> plan) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        if (!chunks(job.id()).isEmpty()) return true;
        for (var chunk : plan) jdbc.update("""
                insert into chunk_results(job_id, chunk_index, first_sequence, last_sequence, overlap_until, status, updated_at) values (?, ?, ?, ?, ?, 'PENDING', ?)""",
                job.id(), chunk.index(), chunk.firstSequence(), chunk.lastSequence(), chunk.overlapUntil(), Timestamp.from(now));
        jdbc.update("update analysis_jobs set total_chunks = ?, completed_chunks = 0, stage = 'CALLING_LLM', updated_at = ? where id = ?", plan.size(), Timestamp.from(now), job.id());
        return true;
    }
    @Transactional
    public boolean saveChunk(Lease lease, int index, String parsedJson, Long inputTokens, Long outputTokens, long latencyMs) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        jdbc.update("""
                update chunk_results set status = 'COMPLETED', output_json = ?::jsonb, attempts = attempts + 1, input_tokens = ?, output_tokens = ?, latency_ms = ?,
                error_code = null, updated_at = ? where job_id = ? and chunk_index = ?""", parsedJson, inputTokens, outputTokens, latencyMs, Timestamp.from(now), job.id(), index);
        jdbc.update("""
                update analysis_jobs set completed_chunks = (select count(*) from chunk_results where job_id = ? and status = 'COMPLETED'),
                lease_expires_at = ?, updated_at = ? where id = ?""", job.id(), Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now), job.id());
        return true;
    }
    public Long[] chunkUsage(UUID jobId) {
        return jdbc.queryForObject("""
                select case when bool_and(input_tokens is not null) then sum(input_tokens)::bigint end, case when bool_and(output_tokens is not null) then sum(output_tokens)::bigint end,
                coalesce(sum(latency_ms), 0)::bigint from chunk_results where job_id = ? and status = 'COMPLETED'""",
                (r, n) -> new Long[] {r.getObject(1, Long.class), r.getObject(2, Long.class), r.getObject(3, Long.class)}, jobId);
    }
    public void chunkFailed(UUID jobId, int index, String code) {
        jdbc.update("update chunk_results set status = 'FAILED', attempts = attempts + 1, error_code = ?, updated_at = now() where job_id = ? and chunk_index = ?", code, jobId, index);
    }
    /** At least one part is done but another failed: keep checkpoints, publish nothing, allow retry of the rest. */
    @Transactional
    public boolean failPartial(Lease lease, String code, boolean retryable) {
        var job = locked(lease.jobId()); Instant now = now();
        if (!usableLocked(job, lease, now)) return false;
        finishLocked(job, Status.PARTIAL_FAILED, code, retryable, now); return true;
    }
    /** SDS §7.9: usage/outcome only — never prompts, transcript or credentials. */
    public void log(AnalysisJob job, int chunk, Long inputTokens, Long outputTokens, Long latencyMs, String status, String code) {
        jdbc.update("""
                insert into processing_logs(id, job_id, chunk_index, provider, model, prompt_version, schema_version, input_tokens, output_tokens, latency_ms,
                attempt, status, error_code, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())""", UUID.randomUUID(), job.id(), chunk,
                job.providerId(), job.model(), job.promptVersion(), job.schemaVersion(), inputTokens, outputTokens, latencyMs, job.attemptCount(), status, code);
    }
}
