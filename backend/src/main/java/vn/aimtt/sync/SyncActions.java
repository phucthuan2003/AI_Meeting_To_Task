package vn.aimtt.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.aimtt.common.ApiException;
import vn.aimtt.trello.*;
import vn.aimtt.trello.TrelloClient.*;

/** Result view and user actions on sync items (SDS §6.8): retry FAILED, reconcile/link/recreate UNKNOWN. */
@Service
public class SyncActions {
    public record ItemView(UUID syncItemId, UUID taskId, String taskName, String status, String cardId, String cardUrl, String errorCode, String message,
                           boolean retryable, List<String> actions, int attemptCount, int reconcileCount, String boardName, String listName,
                           String dueLocal, String timezone, String memberName, String boardUrl) {}
    public record Summary(int queued, int syncing, int synced, int failed, int unknown) {}
    public record JobView(UUID syncJobId, UUID meetingId, String status, Summary summary, List<ItemView> results, Instant createdAt, Instant completedAt) {}

    private static final Pattern CARD_URL = Pattern.compile("^https?://[^/]+/c/([A-Za-z0-9]{6,64})(/.*)?$");
    private final JdbcTemplate jdbc;
    private final SyncItemStore store;
    private final SyncStore sync;
    private final Reconciler reconciler;
    private final TrelloConnectionService connections;
    private final TrelloClient client;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public SyncActions(JdbcTemplate jdbc, SyncItemStore store, SyncStore sync, Reconciler reconciler, TrelloConnectionService connections,
                       TrelloClient client, ObjectMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc; this.store = store; this.sync = sync; this.reconciler = reconciler; this.connections = connections; this.client = client; this.json = json; this.tx = tx;
    }

    public JobView job(UUID owner, UUID jobId) {
        var rows = jdbc.query("select * from sync_jobs where id = ? and user_id = ?", (r, n) -> new Object[] {r.getObject("meeting_id", UUID.class),
                r.getString("status"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("completed_at")}, jobId, owner);
        if (rows.isEmpty()) throw ApiException.notFound();
        var row = rows.get(0);
        var items = jdbc.query("""
                select i.*, t.task_name, s.payload_json::text as payload from sync_items i join tasks t on t.id = i.task_id join task_snapshots s on s.id = i.snapshot_id
                where i.sync_job_id = ? and i.status <> 'SUPERSEDED' order by i.created_at, i.id""", (r, n) -> {
            JsonNode payload;
            try { payload = json.readTree(r.getString("payload")); } catch (Exception e) { payload = json.createObjectNode(); }
            String status = r.getString("status"), code = r.getString("error_code"); boolean retryable = r.getBoolean("error_retryable");
            int reconcile = r.getInt("reconcile_count");
            return new ItemView(r.getObject("id", UUID.class), r.getObject("task_id", UUID.class), r.getString("task_name"), status, r.getString("card_id"),
                    r.getString("card_url"), code, SyncErrors.message(status, code), retryable, SyncErrors.actions(status, code, retryable, reconcile),
                    r.getInt("attempt_count"), reconcile, payload.path("boardName").asText(null), payload.path("listName").asText(null),
                    payload.path("dueLocal").asText(null), payload.path("timezone").asText(null), payload.path("memberName").asText(null),
                    reconciler.safeUrl("https://trello.com/b/" + payload.path("boardId").asText("")));
        }, jobId);
        var counts = new HashMap<String, Integer>(); items.forEach(i -> counts.merge(i.status(), 1, Integer::sum));
        Timestamp completed = (Timestamp) row[3];
        return new JobView(jobId, (UUID) row[0], (String) row[1], new Summary(counts.getOrDefault("QUEUED", 0), counts.getOrDefault("SYNCING", 0),
                counts.getOrDefault("SYNCED", 0), counts.getOrDefault("FAILED", 0), counts.getOrDefault("UNKNOWN", 0)), items, (Instant) row[2],
                completed == null ? null : completed.toInstant());
    }

    private record Owned(SyncItemStore.Item item, SyncItemStore.Snapshot snapshot) {}
    private Owned owned(UUID owner, UUID itemId, boolean lock) {
        var rows = jdbc.query("select i.* from sync_items i join sync_jobs j on j.id = i.sync_job_id where i.id = ? and j.user_id = ?" + (lock ? " for update of i" : ""),
                SyncItemStore.ITEM, itemId, owner);
        if (rows.isEmpty()) throw ApiException.notFound();
        return new Owned(rows.get(0), store.snapshot(rows.get(0).snapshotId()));
    }

    /** Retry a definite failure with the same snapshot; never for UNKNOWN (SDS §6.8). */
    public JobView retry(UUID owner, UUID itemId) {
        UUID jobId = tx.execute(s -> {
            var o = owned(owner, itemId, true);
            var item = o.item();
            switch (item.status()) {
                case "SYNCED", "QUEUED", "SYNCING" -> { return item.jobId(); }
                case "UNKNOWN" -> throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_REQUIRED", "Kết quả tạo card chưa rõ. Đối soát hoặc liên kết card; không thể retry.");
                case "SUPERSEDED" -> throw new ApiException(HttpStatus.CONFLICT, "ITEM_SUPERSEDED", "Lượt này đã được thay bằng bản duyệt mới.");
                default -> { }
            }
            if (!item.retryable()) throw new ApiException(HttpStatus.CONFLICT, "NOT_RETRYABLE", "Lỗi này cần sửa task hoặc đích rồi duyệt lại, không retry được.");
            long destination = jdbc.query("select version from meeting_destinations where meeting_id = ?", (r, n) -> r.getLong(1), o.snapshot().meetingId()).stream().findFirst().orElse(-1L);
            if (destination != o.snapshot().destinationVersion()) throw new ApiException(HttpStatus.CONFLICT, "DESTINATION_CHANGED", "Đích Trello đã đổi sau khi duyệt. Sửa task rồi duyệt lại để dùng đích mới.");
            if (connections.activeFor(owner, o.snapshot().trelloIdentity()).isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Kết nối lại Trello trước khi thử lại.");
            Instant now = store.now();
            jdbc.update("""
                    update sync_items set status = 'QUEUED', dispatch_state = 'NOT_DISPATCHED', error_code = null, error_retryable = false, next_retry_at = ?,
                    completed_at = null, updated_at = ? where id = ?""", Timestamp.from(now), Timestamp.from(now), itemId);
            store.taskStatus(item.taskId(), "QUEUED", now);
            store.attempt(itemId, "VALIDATE", "RETRY_REQUESTED", null, null, now, now);
            sync.refreshJob(item.jobId());
            return item.jobId();
        });
        return job(owner, jobId);
    }

    public JobView reconcile(UUID owner, UUID itemId) {
        var o = owned(owner, itemId, false);
        if (!"UNKNOWN".equals(o.item().status())) return job(owner, o.item().jobId());
        var result = reconciler.reconcile(itemId, true);
        if (result == Reconciler.Result.REAUTH_REQUIRED) throw new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Kết nối lại Trello để đối soát.");
        if (result == Reconciler.Result.UNAVAILABLE) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRELLO_UNAVAILABLE", "Không đọc được Board trên Trello. Thử đối soát lại sau.");
        return job(owner, o.item().jobId());
    }

    /** Link a card the user found on Trello; it must be on the snapshot Board and carry the marker unless acknowledged. */
    public JobView linkCard(UUID owner, UUID itemId, String cardRef, boolean acknowledgeNoMarker) {
        var o = owned(owner, itemId, false);
        if (!Set.of("UNKNOWN", "FAILED").contains(o.item().status())) throw new ApiException(HttpStatus.CONFLICT, "LINK_NOT_ALLOWED", "Chỉ liên kết card cho task chưa rõ kết quả hoặc lỗi.");
        String id = parseCardRef(cardRef);
        var connection = connections.activeFor(owner, o.snapshot().trelloIdentity()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Kết nối lại Trello để liên kết card."));
        Card card;
        try { card = connections.call(connection, cred -> client.card(cred, id)); }
        catch (TrelloException e) { throw TrelloConnectionService.translate(e, connection); }
        if (!o.snapshot().boardId().equals(card.idBoard())) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_NOT_ON_BOARD", "Card không thuộc Board đích của task.");
        boolean marked = card.desc() != null && Reconciler.containsMarker(card.desc(), o.item().marker());
        if (!marked && !acknowledgeNoMarker) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_MARKER_MISSING", "Card không có mã tham chiếu của task. Xác nhận nếu bạn chắc đây đúng card.");
        boolean taken = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sync_items where card_id = ? and id <> ?)", Boolean.class, card.id(), itemId));
        if (taken) throw new ApiException(HttpStatus.CONFLICT, "CARD_ALREADY_LINKED", "Card này đã liên kết với task khác.");
        tx.executeWithoutResult(s -> {
            var locked = owned(owner, itemId, true).item();
            if ("FAILED".equals(locked.status())) {
                // FAILED → SYNCED is allowed only when no other live item exists (unique index protects the task).
                jdbc.update("update sync_items set status = 'UNKNOWN', updated_at = now() where id = ?", itemId);
            }
            if (!store.complete(itemId, card.id(), reconciler.safeUrl(card.url()), "LINK")) throw new ApiException(HttpStatus.CONFLICT, "LINK_NOT_ALLOWED", "Trạng thái đã thay đổi. Tải lại.");
            jdbc.update("update tasks set review_status = 'APPROVED' where id = ?", locked.taskId());
            sync.audit(owner, o.snapshot().meetingId(), locked.taskId(), "LINK_CARD", "{\"marker\":" + marked + "}");
        });
        return job(owner, o.item().jobId());
    }

    /** Explicit re-create after reconciliation found nothing; warns about duplicates and is always audited. */
    public JobView recreate(UUID owner, UUID itemId, JsonNode body) {
        if (body == null || !body.path("acknowledgementDuplicateRisk").asBoolean(false) || !body.path("expectedVersion").isIntegralNumber()) {
            throw ApiException.invalid("Cần expectedVersion và acknowledgementDuplicateRisk=true.");
        }
        UUID jobId = tx.execute(s -> {
            var o = owned(owner, itemId, true);
            var item = o.item();
            if (!"UNKNOWN".equals(item.status()) || item.reconcileCount() < 1 || !Set.of("NOT_FOUND_YET", "NOT_FOUND_AFTER_RECONCILE").contains(item.errorCode())) {
                throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_REQUIRED", "Chỉ tạo lại sau khi đã đối soát và không tìm thấy card.");
            }
            long version = jdbc.queryForObject("select version from tasks where id = ? for update", Long.class, item.taskId());
            if (version != body.get("expectedVersion").asLong()) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Task đã thay đổi. Tải lại.");
            if (connections.activeFor(owner, o.snapshot().trelloIdentity()).isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Kết nối lại Trello trước khi tạo lại.");
            Instant now = store.now();
            jdbc.update("update sync_items set status = 'SUPERSEDED', error_code = 'RECREATED', updated_at = ?, completed_at = ? where id = ?", Timestamp.from(now), Timestamp.from(now), itemId);
            UUID fresh = UUID.randomUUID();
            jdbc.update("""
                    insert into sync_items(id, sync_job_id, task_id, snapshot_id, status, reference_marker, next_retry_at, created_at, updated_at)
                    values (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?)""", fresh, item.jobId(), item.taskId(), item.snapshotId(), item.marker(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            store.taskStatus(item.taskId(), "QUEUED", now);
            store.attempt(itemId, "RECREATE", "SUPERSEDED", null, null, now, now);
            sync.audit(owner, o.snapshot().meetingId(), item.taskId(), "RECREATE_AFTER_RECONCILE", "{\"previousItem\":\"" + itemId + "\",\"newItem\":\"" + fresh + "\"}");
            sync.refreshJob(item.jobId());
            return item.jobId();
        });
        return job(owner, jobId);
    }

    static String parseCardRef(String value) {
        if (value == null) throw ApiException.invalid("Nhập link hoặc ID card Trello.");
        String trimmed = value.trim();
        var m = CARD_URL.matcher(trimmed);
        if (m.matches()) return m.group(1);
        if (trimmed.matches("[A-Za-z0-9]{6,64}")) return trimmed;
        throw ApiException.invalid("Link card Trello có dạng https://trello.com/c/<mã>/…");
    }
}
