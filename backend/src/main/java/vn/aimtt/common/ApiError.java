package vn.aimtt.common;

import java.time.Instant;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

public record ApiError(Instant timestamp, String traceId, int status, String code,
                       String message, boolean retryable, List<?> details) {
    public static ApiError of(HttpServletRequest request, int status, String code, String message) {
        return new ApiError(Instant.now(), (String) request.getAttribute(TraceFilter.ATTRIBUTE),
                status, code, message, status == 429 || status == 503, List.of());
    }
}
