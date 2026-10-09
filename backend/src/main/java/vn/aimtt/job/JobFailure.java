package vn.aimtt.job;

public final class JobFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public JobFailure(String code, boolean retryable) {
        super(code); this.code = code; this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public static String message(String code) {
        if (code == null) return null;
        return switch (code) {
            case "PROVIDER_NOT_CONFIGURED" -> "Job dùng chế độ chuẩn bị hoặc backend chưa có API key của AI đã chọn. Cấu hình key rồi tạo job mới để phân tích.";
            case "POLICY_VERSION_UNAVAILABLE" -> "Policy của job cũ không còn được hỗ trợ. Tạo job mới từ meeting hiện hành.";
            case "PROVIDER_AUTH_ERROR" -> "AI từ chối API key hoặc quyền truy cập model. Kiểm tra cấu hình ở backend.";
            case "PROVIDER_RATE_LIMIT" -> "AI đang giới hạn request hoặc quota. Kiểm tra quota và chờ trước khi thử lại; không tự chuyển provider.";
            case "PROVIDER_UNAVAILABLE", "PROVIDER_CONNECTION_ERROR" -> "Không kết nối được AI hoặc dịch vụ tạm thời không sẵn sàng. Có thể yêu cầu thử lại.";
            case "PROVIDER_TIMEOUT" -> "Đã hết thời gian chờ AI. Request đã gửi có thể vẫn được tính phí; kiểm tra job trước khi retry.";
            case "PROVIDER_OUTPUT_INCOMPLETE" -> "AI trả kết quả chưa hoàn tất hoặc bị cắt. Không có công việc nào được công bố; có thể thử lại.";
            case "PROVIDER_RESPONSE_INVALID" -> "Kết quả AI sai cấu trúc hoặc có nguồn không hợp lệ. Không công bố kết quả này.";
            case "PROVIDER_REQUEST_REJECTED" -> "AI từ chối request/model/schema. Kiểm tra model cấu hình và log trace; không gửi API key vào chat.";
            case "PROVIDER_REFUSED" -> "AI từ chối xử lý nội dung này. Chưa có công việc được công bố.";
            case "RESULT_OVER_BUDGET" -> "Kết quả và evidence vượt giới hạn lưu. Giảm nội dung rồi tạo job mới.";
            case "LEASE_LOST" -> "Worker đã mất lease hoặc job bị hủy. Không công bố kết quả của lượt này.";
            case "SOURCE_UNAVAILABLE" -> "Nguồn transcript không còn khả dụng. Nhập lại nội dung để tạo lượt mới.";
            case "TRANSCRIPT_OVER_BUDGET" -> "Input vượt ngân sách chuẩn bị của policy. Giảm nội dung; chunking chưa được bật.";
            case "LEASE_RECOVERY_EXHAUSTED" -> "Worker đã hết số lần khôi phục. Có thể yêu cầu thử lại.";
            case "INTERNAL_JOB_ERROR" -> "Job gặp lỗi nội bộ. Có thể yêu cầu thử lại.";
            default -> "Phân tích không hoàn tất. Kiểm tra trạng thái job trước khi thử lại.";
        };
    }
}
