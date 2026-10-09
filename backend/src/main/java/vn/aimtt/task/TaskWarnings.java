package vn.aimtt.task;

import java.time.*;
import java.util.*;

/**
 * Server-derived warnings (SDS §5.8, §5.10): recomputed from the stored draft on every read so a model flag
 * or a stale client copy can never mark a task as reviewed. Blocking warnings must be resolved before 14.5 sync.
 */
public final class TaskWarnings {
    private TaskWarnings() {}
    public record Warning(String code, String field, String message, boolean blocking) {}

    public static List<Warning> compute(TaskRow task, LocalDate meetingDate, Instant now, boolean sourceMissing) {
        return compute(task, meetingDate, now, sourceMissing, null, null);
    }
    public static List<Warning> compute(TaskRow task, LocalDate meetingDate, Instant now, boolean sourceMissing, Long destinationVersion, String memberName) {
        var result = new ArrayList<Warning>();
        boolean memberStale = List.of("RESOLVED", "SUGGESTED", "AMBIGUOUS").contains(task.memberResolution())
                && destinationVersion != null && !destinationVersion.equals(task.memberDestinationVersion());
        if (memberStale) result.add(new Warning("MEMBER_DESTINATION_CHANGED", "ASSIGNEE", "Đích Trello đã đổi; đối chiếu lại người phụ trách.", true));
        else switch (task.memberResolution()) {
            case "MISSING" -> result.add(task.assigneeRaw() == null
                    ? new Warning("ASSIGNEE_MISSING", "ASSIGNEE", "Chưa có người phụ trách. Chọn \"Không giao người\" hoặc chọn thành viên khi kết nối Trello.", true)
                    : new Warning("ASSIGNEE_NOT_RESOLVED_TO_MEMBER", "ASSIGNEE", "\"" + task.assigneeRaw() + "\" chưa được đối chiếu với thành viên Trello của Board đích.", true));
            case "SUGGESTED" -> result.add(new Warning("ASSIGNEE_SUGGESTED", "ASSIGNEE", "Gợi ý \"" + (memberName == null ? "thành viên" : memberName)
                    + "\" cho \"" + task.assigneeRaw() + "\" chỉ dựa trên tên gần giống; cần bạn xác nhận.", true));
            case "AMBIGUOUS" -> result.add(new Warning("ASSIGNEE_AMBIGUOUS", "ASSIGNEE", "Có nhiều thành viên Board khớp \"" + task.assigneeRaw() + "\"; chọn đúng người.", true));
            default -> { }
        }
        switch (task.deadlineResolution()) {
            case "MISSING" -> result.add(new Warning("DEADLINE_MISSING", "DEADLINE", "Chưa có hạn. Đặt hạn hoặc chọn \"Không đặt hạn\".", true));
            case "AMBIGUOUS" -> result.add(new Warning("DEADLINE_NEEDS_CONFIRMATION", "DEADLINE",
                    "Hạn \"" + task.deadlineRaw() + "\" cần bạn xác nhận ngày, giờ và múi giờ.", true));
            case "RESOLVED" -> {
                if (meetingDate != null && task.dueLocal().toLocalDate().isBefore(meetingDate)) {
                    result.add(new Warning("DEADLINE_BEFORE_MEETING", "DEADLINE", "Hạn đứng trước ngày họp. Kiểm tra lại; hệ thống không tự sửa.", false));
                }
                if (task.dueAt().isBefore(now)) result.add(new Warning("DEADLINE_IN_PAST", "DEADLINE", "Hạn đã qua so với thời điểm hiện tại.", false));
            }
            default -> { }
        }
        if (sourceMissing) result.add(new Warning("SOURCE_UNAVAILABLE", "TASK", "Nguồn bằng chứng không còn khả dụng (đã hết hạn lưu hoặc bị xóa).", false));
        return List.copyOf(result);
    }
}
