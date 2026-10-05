package vn.aimtt.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> domain(ApiException e, HttpServletRequest request) {
        return error(request, e.status().value(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        var fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", "Giá trị không hợp lệ.")).distinct().toList();
        var base = ApiError.of(request, 400, "INVALID_INPUT", "Kiểm tra các trường đã nhập.");
        return ResponseEntity.badRequest().body(new ApiError(base.timestamp(), base.traceId(), 400,
                base.code(), base.message(), false, fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiError> malformed(Exception e, HttpServletRequest request) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyLimitFilter.BodyLimitExceeded) {
                return error(request, 413, "REQUEST_TOO_LARGE", "JSON request vượt giới hạn.");
            }
        }
        return error(request, 400, "INVALID_INPUT", "Request không hợp lệ.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(Exception e, HttpServletRequest request) {
        return error(request, 415, "INVALID_FILE_TYPE", "Dùng JSON paste hoặc multipart file.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception e, HttpServletRequest request) {
        return error(request, 405, "METHOD_NOT_ALLOWED", "Method không được hỗ trợ.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception e, HttpServletRequest request) {
        return error(request, 404, "RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> large(Exception e, HttpServletRequest request) {
        return error(request, 413, "FILE_TOO_LARGE", "File vượt giới hạn 10 MiB.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(Exception e, HttpServletRequest request) {
        return error(request, 409, "STALE_VERSION", "Dữ liệu đã thay đổi. Hãy tải lại.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(Exception e, HttpServletRequest request) {
        return error(request, 409, "RESOURCE_CONFLICT", "Yêu cầu xung đột với dữ liệu hiện có.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        // Exception messages may contain SQL parameters or parser source text.
        log.error("Unhandled error type={} traceId={}", e.getClass().getSimpleName(),
                request.getAttribute(TraceFilter.ATTRIBUTE));
        return error(request, 500, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Dùng traceId để đối chiếu.");
    }

    private ResponseEntity<ApiError> error(HttpServletRequest request, int status, String code, String message) {
        var builder = ResponseEntity.status(status);
        if (status == 429) builder.header("Retry-After", "60");
        return builder.body(ApiError.of(request, status, code, message));
    }
}
