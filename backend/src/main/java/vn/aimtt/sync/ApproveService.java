package vn.aimtt.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob;
import vn.aimtt.job.AnalysisJobStore;
import vn.aimtt.task.*;
import vn.aimtt.trello.DestinationService;
import vn.aimtt.trello.TrelloConnectionService;

/**
 * "Tạo N card" (SDS §5.14, §6.8): one transaction checks owner, versions, destination and resolutions, writes
 * immutable snapshots and sync items, and marks tasks APPROVED/QUEUED. All-or-none for the request.
 */
@Service
public class ApproveService {
    public record Accepted(UUID syncJobId, String status, int taskCount) {}
    public record CardPreview(UUID taskId, long taskVersion, long destinationVersion, CardPayloads.Payload payload, boolean readyForApproval, List<String> blockers) {}
    static final int MAX_TASKS = 50;

    private final JdbcTemplate jdbc;
    private final TaskStore tasks;
    private final AnalysisJobStore jobs;
    private final DestinationService destinations;
    private final TrelloConnectionService connections;
    private final SyncStore sync;
    private final ObjectMapper json;
    private final ObjectMapper canonical;

    public ApproveService(JdbcTemplate jdbc, TaskStore tasks, AnalysisJobStore jobs, DestinationService destinations,
                          TrelloConnectionService connections, SyncStore sync, ObjectMapper json) {
        this.jdbc = jdbc; this.tasks = tasks; this.jobs = jobs; this.destinations = destinations; this.connections = connections; this.sync = sync; this.json = json;
        this.canonical = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional(readOnly = true)
    public CardPreview preview(UUID owner, UUID taskId) {
        UUID meetingId = tasks.ownedMeetingOf(owner, taskId);
        var meeting = tasks.meeting(owner, meetingId);
        var task = tasks.get(taskId);
        var destination = destinations.find(meetingId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_REQUIRED", "Chọn Board/List đích để xem trước card."));
        var payload = CardPayloads.build(task, meeting.title(), meeting.meetingDate(), destination, tasks.evidence(List.of(taskId)));
        var blockers = blockers(task, destination, isCurrent(owner, meetingId, task));
        return new CardPreview(taskId, task.version(), destination.version(), payload, blockers.isEmpty(), blockers.stream().map(b -> b.get("code").toString()).toList());
    }

    @Transactional
    public Accepted approve(UUID owner, UUID meetingId, JsonNode body, String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) throw ApiException.invalid("Gửi Idempotency-Key 8–128 ký tự cho thao tác tạo card.");
        if (body == null || !body.isObject() || !body.path("expectedDestinationVersion").isIntegralNumber() || !body.path("tasks").isArray()) {
            throw ApiException.invalid("Gửi expectedDestinationVersion và danh sách tasks {taskId, expectedVersion}.");
        }
        for (var names = body.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!Set.of("expectedDestinationVersion", "tasks").contains(name)) throw ApiException.invalid("Trường không được hỗ trợ: " + name);
        }
        String hash = hash(body);
        var meeting = tasks.lockMeeting(owner, meetingId);
        var existing = jdbc.query("select id, request_hash, meeting_id from sync_jobs where user_id = ? and idempotency_key = ?",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class)}, owner, key);
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.get(0)[1]) || !meetingId.equals(existing.get(0)[2])) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key này đã dùng cho yêu cầu tạo card khác.");
            }
            UUID jobId = (UUID) existing.get(0)[0];
            int count = jdbc.queryForObject("select count(*) from sync_items where sync_job_id = ? and status <> 'SUPERSEDED'", Integer.class, jobId);
            String status = jdbc.queryForObject("select status from sync_jobs where id = ?", String.class, jobId);
            return new Accepted(jobId, status, count);
        }
        // Requested tasks: unique IDs, bounded size.
        var requested = new LinkedHashMap<UUID, Long>();
        for (var item : body.get("tasks")) {
            if (!item.isObject() || !item.path("taskId").isTextual() || !item.path("expectedVersion").isIntegralNumber()) throw ApiException.invalid("Mỗi task cần taskId và expectedVersion.");
            UUID id;
            try { id = UUID.fromString(item.get("taskId").asText()); } catch (IllegalArgumentException e) { throw ApiException.invalid("taskId không hợp lệ."); }
            if (requested.put(id, item.get("expectedVersion").asLong()) != null) throw ApiException.invalid("Mỗi task chỉ xuất hiện một lần.");
        }
        if (requested.isEmpty() || requested.size() > MAX_TASKS) throw ApiException.invalid("Chọn từ 1 đến " + MAX_TASKS + " task.");

        var destination = destinations.lockedFind(meetingId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_REQUIRED", "Chọn Board/List đích trước khi tạo card."));
        long expectedDestination = body.get("expectedDestinationVersion").asLong();
        if (destination.version() != expectedDestination) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Đích Trello đã thay đổi. Tải lại rồi xác nhận lại.", List.of(Map.of("currentDestinationVersion", destination.version())));
        }
        connections.activeFor(owner, destination.trelloIdentity()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Kết nối lại đúng tài khoản Trello của Board đích trước khi tạo card."));
        UUID aiJob = jobs.currentOwned(owner, meetingId).filter(j -> j.status() == AnalysisJob.Status.COMPLETED).map(AnalysisJob::id).orElse(null);

        // Lock tasks in ID order to avoid deadlocks between concurrent approvals (SDS §7.8).
        var ordered = new ArrayList<>(requested.keySet()); ordered.sort(Comparator.naturalOrder());
        var locked = new ArrayList<TaskRow>(); var errors = new ArrayList<Map<String, Object>>(); boolean stale = false;
        for (UUID id : ordered) {
            var rows = jdbc.query("select id from tasks where id = ? and meeting_id = ? for update", (r, n) -> 1, id, meetingId);
            if (rows.isEmpty()) { errors.add(Map.of("taskId", id, "code", "TASK_NOT_FOUND")); continue; }
            var task = tasks.get(id);
            boolean current = !task.ai() || task.analysisJobId().equals(aiJob);
            if (task.version() != requested.get(id)) { stale = true; errors.add(Map.of("taskId", id, "code", "STALE_VERSION", "currentVersion", task.version())); continue; }
            var blockers = blockers(task, destination, current);
            if (!blockers.isEmpty()) { errors.addAll(blockers.stream().map(b -> { var m = new LinkedHashMap<String, Object>(b); m.put("taskId", id); return (Map<String, Object>) m; }).toList()); continue; }
            locked.add(task);
        }
        if (!errors.isEmpty()) {
            if (stale) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Có task đã thay đổi. Tải lại để duyệt phiên bản mới.", errors);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_REJECTED", "Có task chưa đủ điều kiện tạo card; chưa task nào được gửi.", errors);
        }
        Instant now = tasks.now();
        UUID jobId = UUID.randomUUID();
        jdbc.update("insert into sync_jobs(id, meeting_id, user_id, status, idempotency_key, request_hash, created_at, updated_at) values (?, ?, ?, 'QUEUED', ?, ?, ?, ?)",
                jobId, meetingId, owner, key, hash, Timestamp.from(now), Timestamp.from(now));
        var evidence = new HashMap<UUID, List<TaskStore.Evidence>>();
        for (var e : tasks.evidence(locked.stream().map(TaskRow::id).toList())) evidence.computeIfAbsent(e.taskId(), k -> new ArrayList<>()).add(e);
        for (var task : locked) {
            var payload = CardPayloads.build(task, meeting.title(), meeting.meetingDate(), destination, evidence.getOrDefault(task.id(), List.of()));
            String encoded = write(payload);
            UUID snapshot = UUID.randomUUID();
            jdbc.update("""
                    insert into task_snapshots(id, task_id, meeting_id, task_version, destination_version, trello_identity, board_id, list_id, payload_json,
                    payload_hash, approved_by, approved_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""", snapshot, task.id(), meetingId, task.version() + 1,
                    destination.version(), destination.trelloIdentity(), destination.boardId(), destination.listId(), encoded, sha256(encoded), owner, Timestamp.from(now));
            jdbc.update("""
                    insert into sync_items(id, sync_job_id, task_id, snapshot_id, status, reference_marker, next_retry_at, created_at, updated_at)
                    values (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?)""", UUID.randomUUID(), jobId, task.id(), snapshot, payload.marker(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            int changed = jdbc.update("update tasks set review_status = 'APPROVED', sync_status = 'QUEUED', version = version + 1, updated_at = ? where id = ? and version = ?",
                    Timestamp.from(now), task.id(), task.version());
            if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Task đã thay đổi trong lúc xác nhận.");
        }
        sync.audit(owner, meetingId, null, "APPROVE_AND_SYNC", write(Map.of("syncJobId", jobId, "taskCount", locked.size(), "destinationVersion", destination.version())));
        return new Accepted(jobId, "QUEUED", locked.size());
    }

    /** Everything that must be decided before a card can be created; empty means ready. */
    List<Map<String, Object>> blockers(TaskRow task, DestinationService.Destination destination, boolean current) {
        var result = new ArrayList<Map<String, Object>>();
        if (!current) { result.add(Map.of("code", "TASK_NOT_CURRENT")); return result; }
        if ("REJECTED".equals(task.reviewStatus())) { result.add(Map.of("code", "TASK_REJECTED")); return result; }
        if ("SYNCED".equals(task.syncStatus())) { result.add(Map.of("code", "ALREADY_SYNCED")); return result; }
        if (!"PENDING_REVIEW".equals(task.reviewStatus()) || !"NOT_SYNCED".equals(task.syncStatus())) {
            result.add(Map.of("code", "FAILED".equals(task.syncStatus()) ? "RETRY_OR_EDIT_FAILED_ITEM" : "TASK_NOT_EDITABLE")); return result;
        }
        boolean memberOk = "NONE_SELECTED".equals(task.memberResolution()) || ("RESOLVED".equals(task.memberResolution())
                && Objects.equals(task.memberDestinationVersion(), destination.version())
                && destination.members().stream().anyMatch(m -> m.id().equals(task.trelloMemberId())));
        if (!memberOk) result.add(Map.of("code", "UNRESOLVED_MEMBER"));
        if (!Set.of("RESOLVED", "NONE_SELECTED").contains(task.deadlineResolution())) result.add(Map.of("code", "UNRESOLVED_DEADLINE"));
        return result;
    }
    private boolean isCurrent(UUID owner, UUID meetingId, TaskRow task) {
        UUID aiJob = jobs.currentOwned(owner, meetingId).filter(j -> j.status() == AnalysisJob.Status.COMPLETED).map(AnalysisJob::id).orElse(null);
        return !task.ai() || task.analysisJobId().equals(aiJob);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private String hash(JsonNode body) {
        try { return sha256(canonical.writeValueAsString(canonical.treeToValue(body, Object.class))); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
