package vn.aimtt.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared sync bookkeeping: job summary status and superseding a failed item after the user edits the task. */
@Repository
public class SyncStore {
    private final JdbcTemplate jdbc;
    public SyncStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** SDS §5.17 job status from its non-superseded items. */
    public void refreshJob(UUID jobId) {
        var counts = new HashMap<String, Integer>();
        jdbc.query("select status, count(*) from sync_items where sync_job_id = ? and status <> 'SUPERSEDED' group by status",
                r -> { counts.put(r.getString(1), r.getInt(2)); }, jobId);
        int queued = counts.getOrDefault("QUEUED", 0), syncing = counts.getOrDefault("SYNCING", 0), synced = counts.getOrDefault("SYNCED", 0),
                failed = counts.getOrDefault("FAILED", 0), unknown = counts.getOrDefault("UNKNOWN", 0);
        String status;
        if (syncing > 0 || (queued > 0 && synced + failed + unknown > 0)) status = "RUNNING";
        else if (queued > 0) status = "QUEUED";
        else if (unknown > 0) status = "NEEDS_ACTION";
        else if (synced > 0 && failed == 0) status = "COMPLETED";
        else if (synced > 0) status = "PARTIAL_FAILED";
        else status = "FAILED";
        boolean done = Set.of("COMPLETED", "PARTIAL_FAILED", "FAILED").contains(status);
        jdbc.update("update sync_jobs set status = ?, updated_at = ?, completed_at = case when ? then coalesce(completed_at, ?) else null end where id = ?",
                status, Timestamp.from(Instant.now()), done, Timestamp.from(Instant.now()), jobId);
    }

    /** Editing a task whose card creation definitely failed retires that item so a new snapshot can be approved. */
    public void supersedeFailed(UUID taskId, Instant now) {
        var jobs = jdbc.query("update sync_items set status = 'SUPERSEDED', updated_at = ?, completed_at = coalesce(completed_at, ?) where task_id = ? and status = 'FAILED' returning sync_job_id",
                (r, n) -> r.getObject(1, UUID.class), Timestamp.from(now), Timestamp.from(now), taskId);
        new HashSet<>(jobs).forEach(this::refreshJob);
    }

    public boolean busy(UUID meetingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from sync_items i join tasks t on t.id = i.task_id where t.meeting_id = ? and i.status in ('QUEUED','SYNCING','UNKNOWN'))""",
                Boolean.class, meetingId));
    }
    public Optional<UUID> latestJobOwned(UUID owner, UUID meetingId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from meetings where id = ? and user_id = ?)", Boolean.class, meetingId, owner))) {
            throw vn.aimtt.common.ApiException.notFound();
        }
        return latestJob(meetingId);
    }
    public Optional<UUID> latestJob(UUID meetingId) {
        return jdbc.query("select id from sync_jobs where meeting_id = ? order by created_at desc limit 1", (r, n) -> r.getObject(1, UUID.class), meetingId).stream().findFirst();
    }
    public void audit(UUID owner, UUID meetingId, UUID taskId, String type, String detailsJson) {
        jdbc.update("insert into audit_events(id, user_id, meeting_id, task_id, event_type, details, created_at) values (?, ?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), owner, meetingId, taskId, type, detailsJson == null ? "{}" : detailsJson, Timestamp.from(Instant.now()));
    }
}
