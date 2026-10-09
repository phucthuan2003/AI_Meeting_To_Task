package vn.aimtt.sync;

import java.util.List;

/** User-facing meaning and allowed actions for each sync item outcome (SDS §5.16, §5.17). */
public final class SyncErrors {
    private SyncErrors() {}
    public static String message(String status, String code) {
        if ("SYNCED".equals(status)) return "Đã tạo card trên Trello.";
        if ("QUEUED".equals(status)) return code == null ? "Đang chờ tạo card." : "Đang chờ thử lại sau lỗi tạm thời của Trello.";
        if ("SYNCING".equals(status)) return "Đang tạo card.";
        if (code == null) return null;
        return switch (code) {
            case "DISPATCH_TIMEOUT", "TRELLO_SERVER_ERROR_AFTER_DISPATCH", "WORKER_LOST_AFTER_DISPATCH", "RESPONSE_NOT_SAVED", "INVALID_RESPONSE_AFTER_DISPATCH" ->
                    "Có thể card đã được tạo nhưng chưa xác nhận được. Hệ thống đang đối soát; không tạo lại tự động.";
            case "NOT_FOUND_YET" -> "Chưa tìm thấy card trên Board. Sẽ đối soát lại; bạn có thể mở Trello để kiểm tra.";
            case "NOT_FOUND_AFTER_RECONCILE" -> "Đã đối soát nhiều lần nhưng không thấy card. Mở Trello kiểm tra; nếu có card hãy liên kết, nếu chắc chắn chưa có mới tạo lại.";
            case "DUPLICATE_DETECTED" -> "Phát hiện nhiều card cùng mã tham chiếu trên Board. Kiểm tra trên Trello rồi liên kết đúng card.";
            case "TRELLO_REAUTH_REQUIRED" -> "Kết nối Trello đã hết quyền. Kết nối lại rồi thử lại.";
            case "TRELLO_DISCONNECTED" -> "Kết nối Trello đã bị ngắt trước khi gửi. Kết nối lại rồi thử lại.";
            case "DESTINATION_INVALID" -> "Board/List đích không còn hợp lệ (đã đóng, bị xóa hoặc mất quyền). Đổi đích rồi duyệt lại task.";
            case "MEMBER_INVALID" -> "Thành viên được giao không còn thuộc Board. Sửa người phụ trách rồi duyệt lại.";
            case "TRELLO_FORBIDDEN" -> "Tài khoản Trello không có quyền tạo card trong List này.";
            case "TRELLO_REJECTED" -> "Trello từ chối dữ liệu card. Sửa task rồi duyệt lại.";
            case "TRELLO_UNAVAILABLE" -> "Trello tạm thời không phản hồi trước khi gửi; chưa có card nào được tạo. Có thể thử lại.";
            case "TRELLO_RATE_LIMIT" -> "Trello giới hạn request; chưa tạo card. Có thể thử lại sau.";
            default -> "Tạo card không thành công. Xem mã lỗi để xử lý.";
        };
    }
    public static List<String> actions(String status, String code, boolean retryable, int reconcileCount) {
        return switch (status) {
            case "SYNCED" -> List.of("OPEN_CARD");
            case "FAILED" -> retryable ? List.of("RETRY", "EDIT") : List.of("EDIT");
            case "UNKNOWN" -> "DUPLICATE_DETECTED".equals(code) ? List.of("LINK_CARD", "OPEN_BOARD")
                    : reconcileCount >= 1 && ("NOT_FOUND_YET".equals(code) || "NOT_FOUND_AFTER_RECONCILE".equals(code))
                    ? List.of("RECONCILE", "LINK_CARD", "RECREATE", "OPEN_BOARD") : List.of("RECONCILE", "LINK_CARD", "OPEN_BOARD");
            default -> List.of();
        };
    }
}
