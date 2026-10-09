package vn.aimtt.sync;

import java.time.format.DateTimeFormatter;
import java.util.*;
import vn.aimtt.task.TaskRow;
import vn.aimtt.task.TaskStore;
import vn.aimtt.trello.DestinationService;

/**
 * Builds the exact card payload that is previewed and snapshotted (SDS §5.13, §5.15). Priority goes into the
 * description (no labels in MVP); evidence is included only when the user opted in; every card carries a stable
 * reference marker derived from the logical task ID, with no secrets.
 */
public final class CardPayloads {
    private CardPayloads() {}
    public static final String MARKER_PREFIX = "AI_MTT_REF=";
    private static final Map<String, String> PRIORITY = Map.of("LOW", "Thấp", "MEDIUM", "Trung bình", "HIGH", "Cao");

    public record Payload(String name, String desc, String idList, List<String> idMembers, String due, String boardId, String listId,
                          String boardName, String listName, String memberName, String dueLocal, String timezone, String marker, List<String> evidenceQuotes) {}

    public static String marker(UUID taskId) { return MARKER_PREFIX + taskId; }

    public static Payload build(TaskRow task, String meetingTitle, java.time.LocalDate meetingDate, DestinationService.Destination destination,
                                List<TaskStore.Evidence> evidence) {
        var desc = new StringBuilder();
        if (task.description() != null) desc.append(task.description().strip()).append("\n\n");
        var details = new ArrayList<String>();
        boolean assigned = "RESOLVED".equals(task.memberResolution()) && task.trelloMemberId() != null;
        if (!assigned && task.assigneeRaw() != null) details.add("Người phụ trách (theo cuộc họp, chưa giao trên Trello): " + task.assigneeRaw());
        if (task.priority() != null) details.add("Ưu tiên: " + PRIORITY.get(task.priority()));
        if (task.deadlineRaw() != null) details.add("Hạn theo cuộc họp: \"" + task.deadlineRaw() + "\"");
        if (meetingTitle != null) details.add("Cuộc họp: " + meetingTitle + (meetingDate == null ? "" : " (" + meetingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")"));
        if (!details.isEmpty()) desc.append(String.join("\n", details)).append("\n\n");
        var quotes = new ArrayList<String>();
        if (task.includeEvidenceInCard()) {
            var seen = new LinkedHashSet<String>();
            for (var item : evidence) if (item.sourceAvailable() && item.quote() != null) seen.add(item.quote().strip());
            for (String quote : seen) { String trimmed = quote.length() > 500 ? quote.substring(0, 500) + "…" : quote; quotes.add(trimmed); }
            if (!quotes.isEmpty()) { desc.append("Trích dẫn từ transcript:\n"); quotes.forEach(q -> desc.append("> ").append(q.replace("\n", " ")).append('\n')); desc.append('\n'); }
        }
        desc.append("Tạo bởi AI Meeting to Task · ").append(marker(task.id()));
        String memberName = null;
        if (assigned) memberName = destination.members().stream().filter(m -> m.id().equals(task.trelloMemberId()))
                .map(m -> m.fullName() != null ? m.fullName() : m.username()).findFirst().orElse(task.trelloMemberId());
        return new Payload(task.taskName(), desc.toString(), destination.listId(), assigned ? List.of(task.trelloMemberId()) : List.of(),
                "RESOLVED".equals(task.deadlineResolution()) ? task.dueAt().toString() : null, destination.boardId(), destination.listId(),
                destination.boardName(), destination.listName(), memberName, task.dueLocal() == null ? null : task.dueLocal().toString(),
                task.timezone(), marker(task.id()), List.copyOf(quotes));
    }
}
