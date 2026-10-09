package vn.aimtt.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Short DB transactions around Trello calls; no lock is held while waiting for HTTP (SDS §4.5). */
@Repository
public class SyncItemStore {
    public record Item(UUID id, UUID jobId, UUID taskId, UUID snapshotId, String status, String dispatchState, String marker, String cardId,
                       String errorCode, boolean retryable, int attemptCount, int reconcileCount, UUID leaseOwner, Instant leaseExpiresAt) {}
    public record Snapshot(UUID id, UUID taskId, UUID meetingId, long taskVersion, long destinationVersion, String trelloIdentity, String boardId,
                           String listId, String payloadJson, UUID owner) {}
    public record Lease(UUID itemId, UUID owner) {}

    private final JdbcTemplate jdbc;
    private final SyncStore sync;
    private final SyncProperties properties;
    private final ObjectMapper json;
    static final RowMapper<Item> ITEM = (r, n) -> new Item(r.getObject("id", UUID.class), r.getObject("sync_job_id", UUID.class), r.getObject("task_id", UUID.class),
            r.getObject("snapshot_id", UUID.class), r.getString("status"), r.getString("dispatch_state"), r.getString("reference_marker"), r.getString("card_id"),
            r.getString("error_code"), r.getBoolean("error_retryable"), r.getInt("attempt_count"), r.getInt("reconcile_count"), r.getObject("lease_owner", UUID.class),
            r.getTimestamp("lease_expires_at") == null ? null : r.getTimestamp("lease_expires_at").toInstant());

    public SyncItemStore(JdbcTemplate jdbc, SyncStore sync, SyncProperties properties, ObjectMapper json) {
        this.jdbc = jdbc; this.sync = sync; this.properties = properties; this.json = json;
    }
    Instant now() { return jdbc.queryForObject("select clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant()); }
    public Item item(UUID id) { return jdbc.query("select * from sync_items where id = ?", ITEM, id).get(0); }
    public Snapshot snapshot(UUID id) {
        return jdbc.query("select * from task_snapshots where id = ?", (r, n) -> new Snapshot(r.getObject("id", UUID.class), r.getObject("task_id", UUID.class),
                r.getObject("meeting_id", UUID.class), r.getLong("task_version"), r.getLong("destination_version"), r.getString("trello_identity"), r.getString("board_id"),
                r.getString("list_id"), r.getString("payload_json"), r.getObject("approved_by", UUID.class)), id).get(0);
    }

    /** Expired leases: a dispatched create may have succeeded → UNKNOWN; otherwise it is safe to queue again (SDS §4.5, AT23). */
    @Transactional
    public int recover() {
        Instant now = now();
        var rows = jdbc.query("select * from sync_items where status = 'SYNCING' and lease_expires_at < ? for update skip locked", ITEM, Timestamp.from(now));
        for (var item : rows) {
            boolean dispatched = "DISPATCHED".equals(item.dispatchState());
            jdbc.update("""
                    update sync_items set status = ?, error_code = ?, next_retry_at = ?, lease_owner = null, lease_expires_at = null, updated_at = ? where id = ?""",
                    dispatched ? "UNKNOWN" : "QUEUED", dispatched ? "WORKER_LOST_AFTER_DISPATCH" : item.errorCode(),
                    Timestamp.from(dispatched ? now.plus(properties.reconcileDelay()) : now), Timestamp.from(now), item.id());
            taskStatus(item.taskId(), dispatched ? "UNKNOWN" : "QUEUED", now);
            attempt(item.id(), "RECOVERY", dispatched ? "UNKNOWN" : "REQUEUED", dispatched ? "WORKER_LOST_AFTER_DISPATCH" : null, null, now, now);
            sync.refreshJob(item.jobId());
        }
        return rows.size();
    }
    @Transactional
    public Optional<Lease> claim() {
        Instant now = now();
        var rows = jdbc.query("select * from sync_items where status = 'QUEUED' and next_retry_at <= ? order by created_at, id limit 1 for update skip locked",
                ITEM, Timestamp.from(now));
        if (rows.isEmpty()) return Optional.empty();
        var item = rows.get(0); UUID lease = UUID.randomUUID();
        jdbc.update("update sync_items set status = 'SYNCING', lease_owner = ?, lease_expires_at = ?, attempt_count = attempt_count + 1, updated_at = ? where id = ?",
                lease, Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now), item.id());
        taskStatus(item.taskId(), "SYNCING", now);
        sync.refreshJob(item.jobId());
        return Optional.of(new Lease(item.id(), lease));
    }
    /** Soft-claims an UNKNOWN item for automatic reconciliation by pushing its next check past the lease window. */
    @Transactional
    public Optional<UUID> claimReconcile() {
        Instant now = now();
        var rows = jdbc.query("""
                select * from sync_items where status = 'UNKNOWN' and next_retry_at <= ? and reconcile_count < ?
                and coalesce(error_code, '') not in ('DUPLICATE_DETECTED', 'NOT_FOUND_AFTER_RECONCILE') order by next_retry_at limit 1 for update skip locked""",
                ITEM, Timestamp.from(now), properties.maxAutoReconcile());
        if (rows.isEmpty()) return Optional.empty();
        jdbc.update("update sync_items set next_retry_at = ? where id = ?", Timestamp.from(now.plus(properties.leaseDuration())), rows.get(0).id());
        return Optional.of(rows.get(0).id());
    }
    private Optional<Item> held(Lease lease, Instant now) {
        var rows = jdbc.query("select * from sync_items where id = ? for update", ITEM, lease.itemId());
        if (rows.isEmpty()) return Optional.empty();
        var item = rows.get(0);
        if (!"SYNCING".equals(item.status()) || !lease.owner().equals(item.leaseOwner()) || item.leaseExpiresAt().isBefore(now)) return Optional.empty();
        return Optional.of(item);
    }
    /** Must commit before the create request is sent: from here on a lost worker means UNKNOWN, never a blind re-create. */
    @Transactional
    public boolean markDispatched(Lease lease) {
        Instant now = now();
        if (held(lease, now).isEmpty()) return false;
        jdbc.update("update sync_items set dispatch_state = 'DISPATCHED', lease_expires_at = ?, updated_at = ? where id = ?",
                Timestamp.from(now.plus(properties.leaseDuration())), Timestamp.from(now), lease.itemId());
        return true;
    }
    /** Not dispatched (validation, rate limit before create): definite failure or bounded requeue. */
    @Transactional
    public void finishNotDispatched(Lease lease, String code, boolean retryable, boolean requeue, Duration delay, Integer httpStatus, String phase) {
        Instant now = now();
        var held = held(lease, now);
        if (held.isEmpty()) return;
        var item = held.get();
        String status = requeue ? "QUEUED" : "FAILED";
        jdbc.update("""
                update sync_items set status = ?, dispatch_state = 'NOT_DISPATCHED', error_code = ?, error_retryable = ?, next_retry_at = ?,
                lease_owner = null, lease_expires_at = null, updated_at = ?, completed_at = ? where id = ?""", status, code, retryable,
                Timestamp.from(now.plus(delay == null ? Duration.ZERO : delay)), Timestamp.from(now), requeue ? null : Timestamp.from(now), item.id());
        taskStatus(item.taskId(), status, now);
        attempt(item.id(), phase, requeue ? "REQUEUED" : "FAILED", code, httpStatus, now, now);
        sync.refreshJob(item.jobId());
    }
    /** Trello definitely rejected the create: no card exists for this attempt. */
    @Transactional
    public void finishRejected(Lease lease, String code, boolean retryable, Integer httpStatus) {
        Instant now = now();
        var rows = jdbc.query("select * from sync_items where id = ? for update", ITEM, lease.itemId());
        var item = rows.get(0);
        if (!"SYNCING".equals(item.status()) || !"DISPATCHED".equals(item.dispatchState())) return;
        jdbc.update("""
                update sync_items set status = 'FAILED', dispatch_state = 'RESOLVED', error_code = ?, error_retryable = ?, lease_owner = null, lease_expires_at = null,
                updated_at = ?, completed_at = ? where id = ?""", code, retryable, Timestamp.from(now), Timestamp.from(now), item.id());
        taskStatus(item.taskId(), "FAILED", now);
        attempt(item.id(), "DISPATCH", "FAILED", code, httpStatus, now, now);
        sync.refreshJob(item.jobId());
    }
    /** Outcome cannot be known (timeout, 5xx, broken response): only reconciliation may resolve it (SDS §5.16). */
    @Transactional
    public void finishUnknown(UUID itemId, String code, Integer httpStatus) {
        Instant now = now();
        var item = jdbc.query("select * from sync_items where id = ? for update", ITEM, itemId).get(0);
        if (!"SYNCING".equals(item.status())) return;
        jdbc.update("""
                update sync_items set status = 'UNKNOWN', error_code = ?, error_retryable = false, next_retry_at = ?, lease_owner = null, lease_expires_at = null,
                updated_at = ? where id = ?""", code, Timestamp.from(now.plus(properties.reconcileDelay())), Timestamp.from(now), itemId);
        taskStatus(item.taskId(), "UNKNOWN", now);
        attempt(itemId, "DISPATCH", "UNKNOWN", code, httpStatus, now, now);
        sync.refreshJob(item.jobId());
    }
    /** Card ID is stored as soon as Trello confirms it; later retries reuse it and never create again (AT24). */
    @Transactional
    public boolean complete(UUID itemId, String cardId, String cardUrl, String phase) {
        Instant now = now();
        var item = jdbc.query("select * from sync_items where id = ? for update", ITEM, itemId).get(0);
        if (!Set.of("SYNCING", "UNKNOWN").contains(item.status()) || item.cardId() != null) return false;
        jdbc.update("""
                update sync_items set status = 'SYNCED', dispatch_state = 'RESOLVED', card_id = ?, card_url = ?, error_code = null, error_retryable = false,
                lease_owner = null, lease_expires_at = null, updated_at = ?, completed_at = ? where id = ?""", cardId, cardUrl, Timestamp.from(now), Timestamp.from(now), itemId);
        jdbc.update("update tasks set sync_status = 'SYNCED', trello_card_id = ?, trello_card_url = ?, updated_at = ? where id = ?", cardId, cardUrl, Timestamp.from(now), item.taskId());
        attempt(itemId, phase, "SYNCED", null, null, now, now);
        sync.refreshJob(item.jobId());
        return true;
    }
    @Transactional
    public void reconcileMiss(UUID itemId, String code, boolean countIt, Duration nextDelay) {
        Instant now = now();
        var item = jdbc.query("select * from sync_items where id = ? for update", ITEM, itemId).get(0);
        if (!"UNKNOWN".equals(item.status())) return;
        jdbc.update("update sync_items set error_code = ?, reconcile_count = reconcile_count + ?, next_retry_at = ?, updated_at = ? where id = ?",
                code, countIt ? 1 : 0, Timestamp.from(now.plus(nextDelay)), Timestamp.from(now), itemId);
        attempt(itemId, "RECONCILE", "DUPLICATE_DETECTED".equals(code) ? "DUPLICATE" : "NOT_FOUND", code, null, now, now);
        sync.refreshJob(item.jobId());
    }
    void taskStatus(UUID taskId, String status, Instant now) {
        jdbc.update("update tasks set sync_status = ?, updated_at = ? where id = ?", status, Timestamp.from(now), taskId);
    }
    void attempt(UUID itemId, String phase, String outcome, String code, Integer httpStatus, Instant started, Instant finished) {
        int no = jdbc.queryForObject("select count(*) + 1 from sync_attempts where sync_item_id = ?", Integer.class, itemId);
        jdbc.update("insert into sync_attempts(id, sync_item_id, attempt_no, phase, outcome, error_code, http_status, started_at, finished_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), itemId, no, phase, outcome, code, httpStatus, Timestamp.from(started), Timestamp.from(finished));
    }
}
