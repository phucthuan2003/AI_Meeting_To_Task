package vn.aimtt.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob;
import vn.aimtt.job.AnalysisJobStore;
import vn.aimtt.sync.SyncErrors;
import vn.aimtt.sync.SyncStore;
import vn.aimtt.trello.DestinationService;

/**
 * Review drafts (SDS §5.10, §6.5). The backend is the source of truth for saved drafts; every write checks the
 * owner, that the task still belongs to the current review set, and the optimistic version.
 */
@Service
public class TaskService {
    public record EvidenceView(UUID segmentId, int sequence, String field, String quote, String speaker, String timestamp, boolean sourceAvailable) {}
    public record EvidenceDetail(UUID segmentId, int sequence, String field, String quote, String speaker, String timestamp,
                                 Map<String, Object> sourceLocator, boolean sourceAvailable) {}
    public record EvidencePage(UUID taskId, String origin, boolean sourceAvailable, List<EvidenceDetail> evidence, List<TaskWarnings.Warning> warnings) {}
    public record TaskView(UUID taskId, UUID meetingId, String origin, UUID analysisJobId, long version, String taskName, String description,
                           String assigneeRaw, String trelloMemberId, String memberResolution, String deadlineRaw, String dueLocal, Instant dueAt,
                           String timezone, String deadlineResolution, String priority, boolean includeEvidenceInCard, String reviewStatus,
                           String syncStatus, String trelloCardUrl, boolean current, boolean editable, boolean readyForApproval,
                           boolean needsConfirmation, List<TaskWarnings.Warning> warnings, Map<String, Object> aiSuggestion, List<String> aiNotes,
                           List<String> editedFields, List<EvidenceView> evidence, Instant createdAt, Instant updatedAt, Instant rejectedAt,
                           String trelloMemberName, List<Map<String, Object>> memberCandidates, SyncView sync) {}
    public record SyncView(UUID syncItemId, UUID syncJobId, String status, String cardUrl, String errorCode, String message, boolean retryable,
                           List<String> actions) {}
    public record AnalysisView(UUID jobId, String status, String providerId, String model, Long inputTokens, Long outputTokens,
                               Long latencyMs, List<String> warnings) {}
    public record Counts(int pending, int rejected, int readyForApproval, int needsAttention) {}
    public record TaskList(UUID meetingId, long inputVersion, UUID transcriptRevision, AnalysisView analysis, List<TaskView> tasks, Counts counts) {}

    static final int MAX_MANUAL_TASKS = 200;
    private static final Set<String> PATCH_FIELDS = Set.of("expectedVersion", "taskName", "description", "assigneeRaw", "trelloMemberId",
            "memberDecision", "deadlineRaw", "dueLocal", "timezone", "deadlineDecision", "priority", "includeEvidenceInCard", "rememberAlias");
    private static final Set<String> SERVER_FIELDS = Set.of("taskId", "id", "meetingId", "origin", "analysisJobId", "version", "reviewStatus",
            "syncStatus", "trelloCardId", "trelloCardUrl", "dueAt", "warnings", "aiSuggestion", "aiNotes", "editedFields", "evidence",
            "memberResolution", "deadlineResolution", "createdAt", "updatedAt", "rejectedAt");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final TaskStore store;
    private final AnalysisJobStore jobs;
    private final ObjectMapper json;
    private final ObjectMapper canonical;
    private final DestinationService destinations;
    private final SyncStore sync;

    public TaskService(TaskStore store, AnalysisJobStore jobs, ObjectMapper json, DestinationService destinations, SyncStore sync) {
        this.store = store; this.jobs = jobs; this.json = json; this.destinations = destinations; this.sync = sync;
        this.canonical = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    // ---------- reads ----------

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskList list(UUID owner, UUID meetingId, UUID expectedJob) {
        var meeting = store.meeting(owner, meetingId);
        var job = jobs.currentOwned(owner, meetingId);
        if (expectedJob != null && (job.isEmpty() || !expectedJob.equals(job.get().id()))) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VIEW", "Meeting đã có lượt phân tích khác. Tải lại để xem đúng kết quả.");
        }
        UUID aiJob = completedJob(job);
        var rows = store.current(meetingId, aiJob);
        var views = views(rows, meeting, aiJob, store.now());
        int pending = 0, rejected = 0, ready = 0, attention = 0;
        for (var view : views) {
            if ("REJECTED".equals(view.reviewStatus())) { rejected++; continue; }
            if ("PENDING_REVIEW".equals(view.reviewStatus())) pending++;
            if (view.readyForApproval()) ready++; else if (view.editable()) attention++;
        }
        return new TaskList(meetingId, meeting.inputVersion(), meeting.revisionId(), job.map(this::analysis).orElse(null), views,
                new Counts(pending, rejected, ready, attention));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvidencePage evidence(UUID owner, UUID taskId) {
        UUID meetingId = store.ownedMeetingOf(owner, taskId);
        var meeting = store.meeting(owner, meetingId);
        var task = store.get(taskId);
        var rows = store.evidence(List.of(taskId));
        var details = rows.stream().map(e -> new EvidenceDetail(e.segmentId(), e.sequence(), e.field(), e.quote(), e.speaker(), e.timestamp(),
                locator(e.sourceLocator()), e.sourceAvailable())).toList();
        boolean missing = rows.stream().anyMatch(e -> !e.sourceAvailable());
        return new EvidencePage(taskId, task.origin(), !missing, details, TaskWarnings.compute(task, meeting.meetingDate(), store.now(), missing));
    }

    // ---------- writes ----------

    @Transactional
    public TaskView create(UUID owner, UUID meetingId, JsonNode body, String key) {
        requireObject(body);
        if (body.has("expectedVersion")) throw ApiException.invalid("Task mới không dùng expectedVersion.");
        validateFields(body);
        if (key != null && !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) throw ApiException.invalid("Idempotency-Key cần 8–128 ký tự ASCII chữ/số/._:-.");
        var meeting = store.lockMeeting(owner, meetingId);
        String hash = key == null ? null : hash(body);
        if (key != null) {
            var existing = store.byCreateKey(meetingId, key);
            if (existing.isPresent()) {
                if (!hash.equals(store.createHash(existing.get().id()).orElse(""))) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key này đã được dùng cho task khác. Không gửi lại với nội dung khác.");
                }
                return view(owner, existing.get(), meeting);
            }
        }
        if (!body.hasNonNull("taskName")) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TASK_NAME_REQUIRED", "Nhập tên công việc.");
        if (body.has("includeEvidenceInCard") && body.get("includeEvidenceInCard").asBoolean(false)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_EVIDENCE", "Task thủ công không có bằng chứng AI để đính kèm.");
        }
        long manual = store.current(meetingId, null).size();
        if (manual >= MAX_MANUAL_TASKS) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TASK_LIMIT", "Meeting đã đạt giới hạn " + MAX_MANUAL_TASKS + " task thủ công.");
        if (body.has("rememberAlias")) throw ApiException.invalid("rememberAlias chỉ dùng khi xác nhận thành viên của task đã có.");
        var empty = new TaskRow.Draft(null, null, null, null, "MISSING", null, null, null, meeting.timezone(), "MISSING", null, false, null);
        var draft = apply(empty, body, meeting);
        var created = store.insertManual(meetingId, draft, key, hash, store.now());
        return view(owner, created, meeting);
    }

    @Transactional
    public TaskView patch(UUID owner, UUID taskId, JsonNode body) {
        requireObject(body);
        validateFields(body);
        long expected = expectedVersion(body);
        var locked = lockForWrite(owner, taskId);
        var task = locked.task();
        requireEditable(locked);
        requireVersion(task, expected);
        var draft = apply(task.draft(), body, locked.meeting());
        boolean remember = body.has("rememberAlias") && body.get("rememberAlias").asBoolean(false);
        if (remember && (!"RESOLVED".equals(draft.memberResolution()) || task.assigneeRaw() == null)) {
            throw ApiException.invalid("Chỉ ghi nhớ tên khi đã chọn thành viên cho task có tên người phụ trách.");
        }
        if (task.ai() && draft.includeEvidenceInCard() && !task.includeEvidenceInCard() && store.evidence(List.of(task.id())).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_EVIDENCE", "Task không có bằng chứng để đính kèm.");
        }
        if (!task.ai() && draft.includeEvidenceInCard()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_EVIDENCE", "Task thủ công không có bằng chứng AI để đính kèm.");
        boolean reopening = "APPROVED".equals(task.reviewStatus());
        if (draft.equals(task.draft()) && !reopening) return view(owner, task, locked.meeting());
        Instant now = store.now();
        // Editing after a definite failure retires the failed item and returns the task to review (SDS §5.14).
        if (reopening) sync.supersedeFailed(task.id(), now);
        var updated = store.update(task, draft, editedFields(task, draft), "PENDING_REVIEW", reopening ? "NOT_SYNCED" : task.syncStatus(), task.rejectedAt(), now)
                .orElseThrow(() -> stale(store.get(taskId)));
        if (remember) destinations.rememberAlias(owner, destinations.requireMember(task.meetingId(), draft.trelloMemberId()), task.assigneeRaw(), draft.trelloMemberId());
        return view(owner, updated, locked.meeting());
    }

    /** DELETE = soft reject (SDS §6.5). Repeating the request on a rejected task is a no-op. */
    @Transactional
    public void reject(UUID owner, UUID taskId, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 1) throw ApiException.invalid("Gửi expectedVersion của task.");
        var locked = lockForWrite(owner, taskId);
        var task = locked.task();
        if ("REJECTED".equals(task.reviewStatus())) return;
        requireEditable(locked);
        requireVersion(task, expectedVersion);
        Instant now = store.now();
        boolean reopening = "APPROVED".equals(task.reviewStatus());
        if (reopening) sync.supersedeFailed(task.id(), now);
        store.update(task, task.draft(), task.editedFields(), "REJECTED", reopening ? "NOT_SYNCED" : task.syncStatus(), now, now).orElseThrow(() -> stale(store.get(taskId)));
    }

    @Transactional
    public TaskView restore(UUID owner, UUID taskId, JsonNode body) {
        requireObject(body);
        for (var names = body.fieldNames(); names.hasNext(); ) {
            if (!names.next().equals("expectedVersion")) throw ApiException.invalid("Restore chỉ nhận expectedVersion.");
        }
        long expected = expectedVersion(body);
        var locked = lockForWrite(owner, taskId);
        var task = locked.task();
        if ("PENDING_REVIEW".equals(task.reviewStatus()) && locked.current()) return view(owner, task, locked.meeting());
        if (!locked.current()) throw notCurrent();
        if (!"REJECTED".equals(task.reviewStatus()) || !"NOT_SYNCED".equals(task.syncStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_EDITABLE", "Chỉ khôi phục được task đã loại bỏ và chưa đồng bộ.");
        }
        requireVersion(task, expected);
        var restored = store.update(task, task.draft(), task.editedFields(), "PENDING_REVIEW", null, store.now())
                .orElseThrow(() -> stale(store.get(taskId)));
        return view(owner, restored, locked.meeting());
    }

    // ---------- rules ----------

    private record Locked(TaskStore.MeetingContext meeting, TaskRow task, boolean current) {}

    /** Lock order meeting → task matches input replacement and job creation, so "current" cannot change mid-write. */
    private Locked lockForWrite(UUID owner, UUID taskId) {
        UUID meetingId = store.ownedMeetingOf(owner, taskId);
        var meeting = store.lockMeeting(owner, meetingId);
        var task = store.lock(meetingId, taskId);
        UUID aiJob = completedJob(jobs.currentOwned(owner, meetingId));
        return new Locked(meeting, task, isCurrent(task, aiJob));
    }
    private static boolean isCurrent(TaskRow task, UUID aiJob) { return !task.ai() || task.analysisJobId().equals(aiJob); }
    /** Draft states the user may change: undecided drafts, and approved tasks whose card creation definitely failed. */
    public static boolean editable(TaskRow task, boolean current) {
        return current && (("PENDING_REVIEW".equals(task.reviewStatus()) && "NOT_SYNCED".equals(task.syncStatus()))
                || ("APPROVED".equals(task.reviewStatus()) && "FAILED".equals(task.syncStatus())));
    }
    private void requireEditable(Locked locked) {
        if (!locked.current()) throw notCurrent();
        var task = locked.task();
        if ("REJECTED".equals(task.reviewStatus())) throw new ApiException(HttpStatus.CONFLICT, "TASK_REJECTED", "Task đã bị loại bỏ. Khôi phục trước khi sửa.");
        if (!editable(task, true)) throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_EDITABLE", "Task đang tạo card, chờ đối soát hoặc đã có card nên không sửa được.");
    }
    private static ApiException notCurrent() {
        return new ApiException(HttpStatus.CONFLICT, "TASK_NOT_CURRENT", "Task thuộc lượt phân tích hoặc input cũ. Tải lại danh sách hiện hành.");
    }
    private static void requireVersion(TaskRow task, long expected) {
        if (task.version() != expected) throw stale(task);
    }
    private static ApiException stale(TaskRow task) {
        return new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Task đã thay đổi ở nơi khác. Tải lại để xem phiên bản mới.",
                List.of(Map.of("taskId", task.id(), "currentVersion", task.version())));
    }
    private static UUID completedJob(Optional<AnalysisJob> job) {
        return job.filter(j -> j.status() == AnalysisJob.Status.COMPLETED).map(AnalysisJob::id).orElse(null);
    }

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.invalid("Body phải là JSON object.");
    }
    private static void validateFields(JsonNode body) {
        var forbidden = new ArrayList<Map<String, String>>();
        for (var names = body.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (SERVER_FIELDS.contains(name)) forbidden.add(Map.of("field", name, "message", "Trường do hệ thống quản lý."));
            else if (!PATCH_FIELDS.contains(name)) forbidden.add(Map.of("field", name, "message", "Trường không được hỗ trợ."));
        }
        if (!forbidden.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_NOT_EDITABLE", "Request chứa trường không được sửa.", forbidden);
    }
    private static long expectedVersion(JsonNode body) {
        var value = body.get("expectedVersion");
        if (value == null || !value.isIntegralNumber() || value.asLong() < 1) throw ApiException.invalid("Gửi expectedVersion của task.");
        return value.asLong();
    }

    private static String text(JsonNode body, String field, int max, boolean required) {
        var node = body.get(field);
        if (node.isNull()) {
            if (required) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TASK_NAME_REQUIRED", "Nhập tên công việc.");
            return null;
        }
        if (!node.isTextual()) throw ApiException.invalid(field + " phải là chuỗi.");
        String value = node.asText().strip();
        if (value.isEmpty()) {
            if (required) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TASK_NAME_REQUIRED", "Nhập tên công việc.");
            return null;
        }
        if (value.length() > max) throw ApiException.invalid(field + " tối đa " + max + " ký tự.");
        return value;
    }
    private static String enumValue(JsonNode body, String field, Set<String> allowed) {
        var node = body.get(field);
        if (node.isNull()) return null;
        if (!node.isTextual() || !allowed.contains(node.asText())) throw ApiException.invalid(field + " không hợp lệ.");
        return node.asText();
    }

    /** Applies present fields; an absent field keeps its value and an explicit null clears it. */
    TaskRow.Draft apply(TaskRow.Draft base, JsonNode body, TaskStore.MeetingContext meeting) {
        String taskName = body.has("taskName") ? text(body, "taskName", 255, true) : base.taskName();
        String description = body.has("description") ? text(body, "description", 5000, false) : base.description();
        String assignee = body.has("assigneeRaw") ? text(body, "assigneeRaw", 255, false) : base.assigneeRaw();
        String deadlineRaw = body.has("deadlineRaw") ? text(body, "deadlineRaw", 255, false) : base.deadlineRaw();
        String priority = body.has("priority") ? enumValue(body, "priority", Set.of("LOW", "MEDIUM", "HIGH")) : base.priority();
        boolean evidence = base.includeEvidenceInCard();
        if (body.has("includeEvidenceInCard")) {
            if (!body.get("includeEvidenceInCard").isBoolean()) throw ApiException.invalid("includeEvidenceInCard phải là true/false.");
            evidence = body.get("includeEvidenceInCard").asBoolean();
        }

        // Member: Trello member IDs only become valid with a Board destination (SDS §5.11, chặng 14.5).
        String memberId = base.trelloMemberId(), memberResolution = base.memberResolution();
        Long memberVersion = base.memberDestinationVersion();
        String chosen = null;
        if (body.has("trelloMemberId") && !body.get("trelloMemberId").isNull()) {
            if (!body.get("trelloMemberId").isTextual()) throw ApiException.invalid("trelloMemberId phải là chuỗi.");
            chosen = body.get("trelloMemberId").asText();
        }
        String memberChoice = body.has("memberDecision") ? enumValue(body, "memberDecision", Set.of("NONE_SELECTED", "RESOLVED")) : chosen != null ? "RESOLVED" : "KEEP";
        if ("RESOLVED".equals(memberChoice)) {
            // A suggestion becomes RESOLVED only by an explicit choice of a member ID on the destination Board.
            String target = chosen != null ? chosen : ("SUGGESTED".equals(base.memberResolution()) ? base.trelloMemberId() : null);
            if (target == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEMBER_RESOLUTION_REQUIRES_TRELLO", "Chọn thành viên Trello sau khi kết nối và chọn Board đích.");
            var destination = destinations.requireMember(meeting.id(), target);
            if (destination == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEMBER_RESOLUTION_REQUIRES_TRELLO", "Chọn Board/List đích trước khi chọn thành viên.");
            memberId = target; memberResolution = "RESOLVED"; memberVersion = destination.version();
        } else if (chosen != null) {
            throw ApiException.invalid("Gửi trelloMemberId cùng memberDecision RESOLVED.");
        } else if (!"KEEP".equals(memberChoice)) {
            memberId = null; memberVersion = null; memberResolution = memberChoice == null ? "MISSING" : "NONE_SELECTED";
        }

        // Deadline (SDS §5.12): keep the raw sentence, never infer from upload time, and store local + UTC together.
        String timezone = base.timezone();
        if (body.has("timezone")) {
            timezone = text(body, "timezone", 64, false);
            if (timezone != null && !ZoneId.getAvailableZoneIds().contains(timezone)) throw ApiException.invalid("Dùng timezone IANA hợp lệ, ví dụ Asia/Ho_Chi_Minh.");
        }
        LocalDateTime dueLocal = base.dueLocal();
        boolean dueGiven = body.has("dueLocal");
        if (dueGiven) dueLocal = parseLocal(body.get("dueLocal"));
        String decision;
        if (body.has("deadlineDecision")) decision = Optional.ofNullable(enumValue(body, "deadlineDecision", Set.of("RESOLVED", "NONE_SELECTED"))).orElse("UNDECIDED");
        else if (dueGiven) decision = dueLocal == null ? "UNDECIDED" : "RESOLVED";
        else decision = switch (base.deadlineResolution()) { case "RESOLVED" -> "RESOLVED"; case "NONE_SELECTED" -> "NONE_SELECTED"; default -> "UNDECIDED"; };
        String resolution; Instant dueAt = null;
        switch (decision) {
            case "RESOLVED" -> {
                if (timezone == null) timezone = meeting.timezone();
                if (dueLocal == null || timezone == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNRESOLVED_DEADLINE", "Cần đủ ngày, giờ và múi giờ để đặt hạn.");
                var offsets = ZoneId.of(timezone).getRules().getValidOffsets(dueLocal);
                if (offsets.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEADLINE_LOCAL_TIME_INVALID", "Giờ này không tồn tại ở múi giờ đã chọn (chuyển giờ mùa hè). Chọn giờ khác.");
                if (offsets.size() > 1) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEADLINE_LOCAL_TIME_AMBIGUOUS", "Giờ này lặp lại ở múi giờ đã chọn. Chọn giờ khác.");
                dueAt = dueLocal.atOffset(offsets.get(0)).toInstant();
                resolution = "RESOLVED";
            }
            case "NONE_SELECTED" -> {
                if (dueGiven && dueLocal != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEADLINE_DECISION_CONFLICT", "Chọn \"Không đặt hạn\" thì không gửi dueLocal.");
                dueLocal = null; resolution = "NONE_SELECTED";
            }
            default -> {
                if (body.has("deadlineDecision") && dueGiven && dueLocal != null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEADLINE_DECISION_CONFLICT", "Bỏ quyết định hạn thì không gửi dueLocal.");
                dueLocal = null; resolution = deadlineRaw == null ? "MISSING" : "AMBIGUOUS";
            }
        }
        return new TaskRow.Draft(taskName, description, assignee, memberId, memberResolution, deadlineRaw, dueLocal, dueAt, timezone, resolution, priority, evidence, memberVersion);
    }
    private static LocalDateTime parseLocal(JsonNode node) {
        if (node.isNull()) return null;
        if (!node.isTextual()) throw ApiException.invalid("dueLocal dạng yyyy-MM-ddTHH:mm.");
        try {
            var value = LocalDateTime.parse(node.asText());
            if (value.getYear() < 2000 || value.getYear() > 2100) throw ApiException.invalid("dueLocal ngoài khoảng năm hỗ trợ.");
            return value.withNano(0);
        } catch (DateTimeParseException e) { throw ApiException.invalid("dueLocal dạng yyyy-MM-ddTHH:mm."); }
    }

    /** Provenance: which values differ from the AI proposal. USER tasks are entirely user content. */
    static List<String> editedFields(TaskRow task, TaskRow.Draft draft) {
        if (!task.ai()) return List.of();
        var ai = task.aiSuggestion();
        var edited = new ArrayList<String>();
        if (!Objects.equals(draft.taskName(), ai.get("taskName"))) edited.add("taskName");
        if (draft.description() != null) edited.add("description");
        if (!Objects.equals(draft.assigneeRaw(), ai.get("assigneeRaw"))) edited.add("assigneeRaw");
        if (!Objects.equals(draft.deadlineRaw(), ai.get("deadlineRaw"))) edited.add("deadlineRaw");
        if (!Objects.equals(draft.priority(), ai.get("priority"))) edited.add("priority");
        if ("RESOLVED".equals(draft.deadlineResolution()) && !Objects.equals(draft.dueLocal().toString(), ai.get("dueLocal"))) edited.add("dueLocal");
        if (Set.of("RESOLVED", "NONE_SELECTED").contains(draft.deadlineResolution())) edited.add("deadlineDecision");
        if (Set.of("NONE_SELECTED", "RESOLVED").contains(draft.memberResolution())) edited.add("memberDecision");
        if (draft.includeEvidenceInCard()) edited.add("includeEvidenceInCard");
        return List.copyOf(edited);
    }

    // ---------- views ----------

    private TaskView view(UUID owner, TaskRow task, TaskStore.MeetingContext meeting) {
        UUID aiJob = completedJob(jobs.currentOwned(owner, meeting.id()));
        return views(List.of(task), meeting, aiJob, store.now()).get(0);
    }
    private List<TaskView> views(List<TaskRow> rows, TaskStore.MeetingContext meeting, UUID aiJob, Instant now) {
        var evidence = new HashMap<UUID, List<TaskStore.Evidence>>();
        var ids = rows.stream().map(TaskRow::id).toList();
        for (var item : store.evidence(ids)) evidence.computeIfAbsent(item.taskId(), k -> new ArrayList<>()).add(item);
        var syncs = store.latestSync(ids);
        var destination = destinations.find(meeting.id()).orElse(null);
        var memberNames = new HashMap<String, String>();
        if (destination != null) destination.members().forEach(m -> memberNames.put(m.id(), m.fullName() != null ? m.fullName() : m.username()));
        return rows.stream().map(task -> {
            var items = evidence.getOrDefault(task.id(), List.of());
            boolean missing = items.stream().anyMatch(e -> !e.sourceAvailable());
            boolean current = isCurrent(task, aiJob);
            boolean editable = editable(task, current);
            var warnings = TaskWarnings.compute(task, meeting.meetingDate(), now, missing, destination == null ? null : destination.version(),
                    task.trelloMemberId() == null ? null : memberNames.get(task.trelloMemberId()));
            var item = syncs.get(task.id());
            var syncView = item == null ? null : new SyncView(item.syncItemId(), item.syncJobId(), item.status(), item.cardUrl(), item.errorCode(),
                    SyncErrors.message(item.status(), item.errorCode()), item.retryable(), SyncErrors.actions(item.status(), item.errorCode(), item.retryable(), item.reconcileCount()));
            boolean blocked = warnings.stream().anyMatch(TaskWarnings.Warning::blocking);
            return new TaskView(task.id(), task.meetingId(), task.origin(), task.analysisJobId(), task.version(), task.taskName(), task.description(),
                    task.assigneeRaw(), task.trelloMemberId(), task.memberResolution(), task.deadlineRaw(),
                    task.dueLocal() == null ? null : task.dueLocal().toString(), task.dueAt(), task.timezone(), task.deadlineResolution(),
                    task.priority(), task.includeEvidenceInCard(), task.reviewStatus(), task.syncStatus(), task.trelloCardUrl(), current, editable,
                    editable && "PENDING_REVIEW".equals(task.reviewStatus()) && !blocked, !warnings.isEmpty(), warnings, task.aiSuggestion(), task.aiNotes(), task.editedFields(),
                    items.stream().map(e -> new EvidenceView(e.segmentId(), e.sequence(), e.field(), e.quote(), e.speaker(), e.timestamp(), e.sourceAvailable())).toList(),
                    task.createdAt(), task.updatedAt(), task.rejectedAt(), task.trelloMemberId() == null ? null : memberNames.get(task.trelloMemberId()),
                    task.memberCandidates(), syncView);
        }).toList();
    }
    private AnalysisView analysis(AnalysisJob job) {
        Long input = null, output = null, latency = null; List<String> warnings = List.of();
        if (job.status() == AnalysisJob.Status.COMPLETED) {
            var stored = jobs.result(job.id()).orElseThrow(() -> new IllegalStateException("Missing completed analysis result"));
            try {
                var node = json.readTree(stored);
                input = node.path("inputTokens").isNumber() ? node.get("inputTokens").asLong() : null;
                output = node.path("outputTokens").isNumber() ? node.get("outputTokens").asLong() : null;
                latency = node.path("latencyMs").isNumber() ? node.get("latencyMs").asLong() : null;
                var list = new ArrayList<String>();
                node.path("warnings").forEach(w -> list.add(w.asText()));
                warnings = List.copyOf(list);
            } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored analysis result"); }
        }
        return new AnalysisView(job.id(), job.status().name(), job.providerId(), job.model(), input, output, latency, warnings);
    }
    private Map<String, Object> locator(String value) {
        if (value == null) return null;
        try { return json.readValue(value, MAP); } catch (JsonProcessingException e) { return null; }
    }
    private String hash(JsonNode body) {
        try {
            var normalized = canonical.writeValueAsString(canonical.treeToValue(body, Object.class));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) { throw new IllegalStateException("Hash failed"); }
    }
}
