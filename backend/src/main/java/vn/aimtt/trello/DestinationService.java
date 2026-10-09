package vn.aimtt.trello;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob;
import vn.aimtt.job.AnalysisJobStore;
import vn.aimtt.task.TaskStore;
import vn.aimtt.trello.TrelloClient.*;

/**
 * Meeting destination and member/deadline resolution (SDS §5.11, §6.7). Trello reads happen outside DB
 * transactions; writes re-check owner, versions and that no sync item is in flight.
 */
@Service
public class DestinationService {
    public record MemberView(String id, String username, String fullName) {}
    public record DestinationView(UUID meetingId, UUID connectionId, String trelloIdentity, String boardId, String boardName, String listId,
                                  String listName, long version, List<MemberView> members, Instant membersFetchedAt, String connectionStatus) {}
    public record Destination(UUID meetingId, UUID connectionId, String trelloIdentity, String boardId, String boardName, String listId, String listName,
                              List<MemberView> members, Instant membersFetchedAt, long version) {}
    public record MemberResolution(UUID taskId, String assigneeRaw, String memberResolution, String trelloMemberId, List<MemberView> candidates, long version) {}
    public record DeadlineSuggestionView(UUID taskId, String deadlineRaw, String suggestedDueLocal, String timezone, String rule, String note) {}
    private static final TypeReference<List<MemberView>> MEMBERS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TrelloConnectionService connections;
    private final TrelloClient client;
    private final TaskStore tasks;
    private final AnalysisJobStore jobs;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public DestinationService(JdbcTemplate jdbc, TrelloConnectionService connections, TrelloClient client, TaskStore tasks,
                              AnalysisJobStore jobs, ObjectMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc; this.connections = connections; this.client = client; this.tasks = tasks; this.jobs = jobs; this.json = json; this.tx = tx;
    }

    public Optional<Destination> find(UUID meetingId) {
        return jdbc.query("select * from meeting_destinations where meeting_id = ?", (r, n) -> new Destination(r.getObject("meeting_id", UUID.class),
                r.getObject("connection_id", UUID.class), r.getString("trello_member_id"), r.getString("board_id"), r.getString("board_name"),
                r.getString("list_id"), r.getString("list_name"), decode(r.getString("board_members")),
                r.getTimestamp("members_fetched_at") == null ? null : r.getTimestamp("members_fetched_at").toInstant(), r.getLong("version")), meetingId).stream().findFirst();
    }
    public Optional<Destination> lockedFind(UUID meetingId) {
        jdbc.query("select meeting_id from meeting_destinations where meeting_id = ? for update", (r, n) -> 1, meetingId);
        return find(meetingId);
    }
    public DestinationView view(UUID owner, UUID meetingId) {
        tasks.meeting(owner, meetingId);
        return find(meetingId).map(this::view).orElse(null);
    }
    private DestinationView view(Destination d) {
        String status = jdbc.query("select status from trello_connections where id = ?", (r, n) -> r.getString(1), d.connectionId()).stream().findFirst().orElse("DISCONNECTED");
        return new DestinationView(d.meetingId(), d.connectionId(), d.trelloIdentity(), d.boardId(), d.boardName(), d.listId(), d.listName(), d.version(),
                d.members(), d.membersFetchedAt(), status);
    }

    // ---------- Trello browsing (always through a connection owned by the user) ----------
    public List<Board> boards(UUID owner, UUID connectionId) { var c = connections.get(owner, connectionId); return trello(c, cred -> client.boards(cred)); }
    public List<TrelloList> lists(UUID owner, UUID connectionId, String boardId) {
        var c = connections.get(owner, connectionId); TrelloClient.id(boardId);
        return trello(c, cred -> client.lists(cred, boardId));
    }
    public List<MemberView> members(UUID owner, UUID connectionId, String boardId) {
        var c = connections.get(owner, connectionId); TrelloClient.id(boardId);
        return trello(c, cred -> client.members(cred, boardId)).stream().map(m -> new MemberView(m.id(), m.username(), m.fullName())).toList();
    }
    private <T> T trello(TrelloConnectionService.Connection c, java.util.function.Function<TrelloClient.Credentials, T> call) {
        try { return connections.call(c, call); }
        catch (TrelloException e) { throw TrelloConnectionService.translate(e, c); }
    }

    // ---------- destination ----------
    public DestinationView save(UUID owner, UUID meetingId, UUID connectionId, String boardId, String listId, long expectedVersion) {
        tasks.meeting(owner, meetingId);
        var connection = connections.get(owner, connectionId);
        if (!"ACTIVE".equals(connection.status())) throw TrelloConnectionService.reauth();
        TrelloClient.id(boardId); TrelloClient.id(listId);
        // Validate on Trello before taking DB locks: List belongs to Board, both open.
        record Checked(Board board, TrelloList list, List<MemberView> members) {}
        var checked = trello(connection, cred -> {
            var board = client.board(cred, boardId);
            var list = client.list(cred, listId);
            var members = client.members(cred, boardId).stream().map(m -> new MemberView(m.id(), m.username(), m.fullName())).toList();
            return new Checked(board, list, members);
        });
        if (checked.board().closed() || checked.list().closed()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_CLOSED", "Board hoặc List đã đóng/lưu trữ.");
        if (!boardId.equals(checked.list().idBoard())) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LIST_NOT_IN_BOARD", "List không thuộc Board đã chọn.");
        return tx.execute(s -> {
            tasks.lockMeeting(owner, meetingId);
            requireNoSyncInFlight(meetingId);
            var current = lockedFind(meetingId);
            long version = current.map(Destination::version).orElse(0L);
            if (version != expectedVersion) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Đích Trello đã thay đổi ở nơi khác. Tải lại.",
                    List.of(Map.of("currentVersion", version)));
            long next = version + 1; Instant now = Instant.now();
            String members = encode(checked.members());
            jdbc.update("""
                    insert into meeting_destinations(meeting_id, connection_id, trello_member_id, board_id, board_name, list_id, list_name, board_members,
                    members_fetched_at, version, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    on conflict (meeting_id) do update set connection_id = excluded.connection_id, trello_member_id = excluded.trello_member_id,
                    board_id = excluded.board_id, board_name = excluded.board_name, list_id = excluded.list_id, list_name = excluded.list_name,
                    board_members = excluded.board_members, members_fetched_at = excluded.members_fetched_at, version = excluded.version, updated_at = excluded.updated_at
                    """, meetingId, connectionId, connection.trelloMemberId(), boardId, checked.board().name(), listId, checked.list().name(), members,
                    Timestamp.from(now), next, Timestamp.from(now));
            boolean sameBoard = current.isPresent() && current.get().boardId().equals(boardId) && current.get().trelloIdentity().equals(connection.trelloMemberId());
            if (sameBoard) {
                // Same Board: confirmed members are still Board members (re-checked below); move them to the new destination version.
                var ids = checked.members().stream().map(MemberView::id).toList();
                jdbc.update("""
                        update tasks set member_destination_version = ? where meeting_id = ? and member_resolution in ('RESOLVED','SUGGESTED','AMBIGUOUS')
                        and review_status = 'PENDING_REVIEW' and sync_status in ('NOT_SYNCED','FAILED')""", next, meetingId);
                resetMembers(meetingId, ids, now);
            } else resetMembers(meetingId, List.of(), now);
            return view(find(meetingId).orElseThrow());
        });
    }
    /** Mappings to members outside the (new) Board become MISSING again; the task version changes so stale approvals fail. */
    private void resetMembers(UUID meetingId, List<String> keep, Instant now) {
        var rows = jdbc.query("""
                select id, trello_member_id from tasks where meeting_id = ? and member_resolution in ('RESOLVED','SUGGESTED','AMBIGUOUS')
                and review_status = 'PENDING_REVIEW' and sync_status in ('NOT_SYNCED','FAILED')""", (r, n) -> new String[] {r.getString(1), r.getString(2)}, meetingId);
        for (var row : rows) {
            if (row[1] != null && keep.contains(row[1])) continue;
            jdbc.update("""
                    update tasks set member_resolution = 'MISSING', trello_member_id = null, member_candidates = '[]'::jsonb, member_destination_version = null,
                    version = version + 1, updated_at = ? where id = ?""", Timestamp.from(now), UUID.fromString(row[0]));
        }
    }
    void requireNoSyncInFlight(UUID meetingId) {
        Boolean busy = jdbc.queryForObject("""
                select exists(select 1 from sync_items i join tasks t on t.id = i.task_id where t.meeting_id = ? and i.status in ('QUEUED','SYNCING','UNKNOWN'))""", Boolean.class, meetingId);
        if (Boolean.TRUE.equals(busy)) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_BUSY", "Có card đang được tạo hoặc chờ đối soát. Xử lý xong trước khi đổi đích.");
    }

    // ---------- member resolution ----------
    public List<MemberResolution> resolveMembers(UUID owner, UUID meetingId, long expectedDestinationVersion) {
        tasks.meeting(owner, meetingId);
        var destination = find(meetingId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_REQUIRED", "Chọn Board/List trước khi đối chiếu thành viên."));
        if (destination.version() != expectedDestinationVersion) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Đích Trello đã thay đổi. Tải lại.");
        var connection = connections.activeFor(owner, destination.trelloIdentity()).orElseThrow(TrelloConnectionService::reauth);
        var fresh = trello(connection, cred -> client.members(cred, destination.boardId()));
        var members = fresh.stream().map(m -> new MemberView(m.id(), m.username(), m.fullName())).toList();
        return tx.execute(s -> {
            var meeting = tasks.lockMeeting(owner, meetingId);
            var current = lockedFind(meetingId).orElseThrow();
            if (current.version() != expectedDestinationVersion) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Đích Trello đã thay đổi. Tải lại.");
            Instant now = Instant.now();
            jdbc.update("update meeting_destinations set board_members = ?::jsonb, members_fetched_at = ? where meeting_id = ?", encode(members), Timestamp.from(now), meetingId);
            var aliases = new HashMap<String, String>();
            jdbc.query("select alias, member_id from member_aliases where user_id = ? and trello_identity = ? and board_id = ?",
                    r -> { aliases.put(r.getString(1), r.getString(2)); }, owner, current.trelloIdentity(), current.boardId());
            UUID aiJob = jobs.currentOwned(owner, meetingId).filter(j -> j.status() == AnalysisJob.Status.COMPLETED).map(AnalysisJob::id).orElse(null);
            var result = new ArrayList<MemberResolution>();
            for (var task : tasks.current(meetingId, aiJob)) {
                if (!"PENDING_REVIEW".equals(task.reviewStatus()) || !"NOT_SYNCED".equals(task.syncStatus())) continue;
                String resolution = task.memberResolution(), memberId = task.trelloMemberId(); List<MemberView> candidates = List.of();
                if ("NONE_SELECTED".equals(resolution)) continue;
                final String confirmed = memberId;
                if ("RESOLVED".equals(resolution) && members.stream().anyMatch(m -> m.id().equals(confirmed))) {
                    result.add(new MemberResolution(task.id(), task.assigneeRaw(), resolution, memberId, List.of(), task.version()));
                    if (!Objects.equals(task.memberDestinationVersion(), current.version())) jdbc.update("update tasks set member_destination_version = ? where id = ?", current.version(), task.id());
                    continue;
                }
                if (task.assigneeRaw() == null) { resolution = "MISSING"; memberId = null; }
                else {
                    String alias = aliases.get(MemberMatcher.normalize(task.assigneeRaw()));
                    if (alias != null && members.stream().anyMatch(m -> m.id().equals(alias))) { resolution = "RESOLVED"; memberId = alias; }
                    else {
                        var outcome = MemberMatcher.match(task.assigneeRaw(), fresh);
                        resolution = outcome.resolution();
                        candidates = outcome.candidates().stream().map(m -> new MemberView(m.id(), m.username(), m.fullName())).toList();
                        memberId = "SUGGESTED".equals(resolution) ? candidates.get(0).id() : null;
                    }
                }
                long version = task.version();
                boolean changed = !resolution.equals(task.memberResolution()) || !Objects.equals(memberId, task.trelloMemberId())
                        || !encode(candidates).equals(encode(task.memberCandidates())) || !Objects.equals(task.memberDestinationVersion(), current.version());
                if (changed) {
                    jdbc.update("""
                            update tasks set member_resolution = ?, trello_member_id = ?, member_candidates = ?::jsonb, member_destination_version = ?,
                            version = version + 1, updated_at = ? where id = ?""", resolution, memberId, encode(candidates),
                            "MISSING".equals(resolution) ? null : current.version(), Timestamp.from(now), task.id());
                    version++;
                }
                result.add(new MemberResolution(task.id(), task.assigneeRaw(), resolution, memberId, candidates, version));
            }
            return result;
        });
    }
    /** Called by task PATCH: the chosen member must be on the current destination Board. */
    public Destination requireMember(UUID meetingId, String memberId) {
        var destination = find(meetingId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEMBER_RESOLUTION_REQUIRES_TRELLO", "Chọn Board/List đích trước khi chọn thành viên."));
        if (destination.members().stream().noneMatch(m -> m.id().equals(memberId))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEMBER_NOT_IN_BOARD", "Thành viên không thuộc Board đích. Tải lại danh sách thành viên.");
        }
        return destination;
    }
    public void rememberAlias(UUID owner, Destination destination, String spoken, String memberId) {
        if (spoken == null || MemberMatcher.normalize(spoken).isBlank()) return;
        jdbc.update("""
                insert into member_aliases(id, user_id, trello_identity, board_id, alias, member_id, confirmed_at) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, trello_identity, board_id, alias) do update set member_id = excluded.member_id, confirmed_at = excluded.confirmed_at""",
                UUID.randomUUID(), owner, destination.trelloIdentity(), destination.boardId(), MemberMatcher.normalize(spoken), memberId, Timestamp.from(Instant.now()));
    }

    // ---------- deadline suggestions ----------
    public List<DeadlineSuggestionView> resolveDeadlines(UUID owner, UUID meetingId) {
        var meeting = tasks.meeting(owner, meetingId);
        UUID aiJob = jobs.currentOwned(owner, meetingId).filter(j -> j.status() == AnalysisJob.Status.COMPLETED).map(AnalysisJob::id).orElse(null);
        var result = new ArrayList<DeadlineSuggestionView>();
        for (var task : tasks.current(meetingId, aiJob)) {
            if (!"AMBIGUOUS".equals(task.deadlineResolution()) || !"PENDING_REVIEW".equals(task.reviewStatus())) continue;
            String zone = task.timezone() != null ? task.timezone() : meeting.timezone();
            var suggestion = DeadlineSuggester.suggest(task.deadlineRaw(), meeting.meetingDate());
            result.add(suggestion.map(s -> new DeadlineSuggestionView(task.id(), task.deadlineRaw(), s.dueLocal(), zone, s.rule(), s.note()))
                    .orElse(new DeadlineSuggestionView(task.id(), task.deadlineRaw(), null, zone, "NONE",
                            meeting.meetingDate() == null ? "Chưa có ngày họp nên không diễn giải được hạn tương đối." : "Không diễn giải được câu này; hãy chọn ngày giờ.")));
        }
        return result;
    }

    private List<MemberView> decode(String value) {
        try { return json.readValue(value, MEMBERS); } catch (JsonProcessingException e) { return List.of(); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
