package vn.aimtt.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final java.util.List<?> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, java.util.List.of());
    }

    public ApiException(HttpStatus status, String code, String message, java.util.List<?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = java.util.List.copyOf(details);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public java.util.List<?> details() { return details; }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên.");
    }
}
