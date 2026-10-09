package vn.aimtt.job;

import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob.Status;

@Service
public class AnalysisJobService {
    public record StartInput(Long expectedInputVersion, String providerId, String processingPolicyId) {}
    public record ErrorView(String code, String message, boolean retryable) {}
    public record JobView(UUID jobId, UUID meetingId, UUID transcriptRevision, long inputVersion,
            String status, String stage, int completedChunks, Integer totalChunks,
            int preparedSegments, long estimatedInputTokens, String estimator,
            String providerId, String model, String processingPolicyId, String promptVersion, String schemaVersion,
            int attemptCount, int retryCount, ErrorView error, Instant createdAt, Instant updatedAt, Instant completedAt,
            String meetingUrl, String jobUrl) {}
    private final AnalysisJobStore jobs;
    private final AnalysisPolicy policy;
    private final AnalysisProperties properties;
    public AnalysisJobService(AnalysisJobStore jobs, AnalysisPolicy policy, AnalysisProperties properties) {
        this.jobs = jobs; this.policy = policy; this.properties = properties;
    }
    @Transactional
    public JobView start(UUID owner, UUID meetingId, StartInput input, String key) {
        if (input.expectedInputVersion() == null || input.expectedInputVersion() < 0) throw ApiException.invalid("Gửi expectedInputVersion hợp lệ.");
        if (key == null) key = UUID.randomUUID().toString();
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) throw ApiException.invalid("Idempotency-Key cần 8–128 ký tự ASCII chữ/số/._:-.");
        // Consistent owner -> meeting -> job locking serializes the per-user quota and input changes.
        jobs.lockOwner(owner);
        var meeting = jobs.lockMeeting(owner, meetingId);
        policy.validate(input.providerId(), input.processingPolicyId());
        var existing = jobs.byKey(meetingId, key);
        if (existing.isPresent()) {
            var job = existing.get();
            if (job.inputVersion() != input.expectedInputVersion() || !job.providerId().equals(input.providerId()) || !job.policyId().equals(input.processingPolicyId())) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Key này đã được dùng cho yêu cầu khác. Không gửi lại với input khác.");
            }
            return view(job);
        }
        var definition = policy.resolve(input.providerId(), input.processingPolicyId(), properties);
        if (meeting.inputVersion() != input.expectedInputVersion()) throw conflict("STALE_VERSION", "Input đã thay đổi. Tải lại trước khi phân tích.");
        if (!meeting.sourceAvailable()) throw conflict("SOURCE_UNAVAILABLE", "Nguồn đã mất. Nhập lại transcript.");
        if (jobs.active(meetingId)) throw conflict("RESOURCE_BUSY", "Meeting đang có job phân tích. Theo dõi hoặc hủy job đó.");
        if (jobs.activeForOwner(owner) >= properties.maxActivePerUser()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "USER_JOB_LIMIT", "Đã đạt giới hạn job đang chạy. Chờ hoặc hủy một job.");
        return view(jobs.create(meeting, key, definition));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JobView get(UUID owner, UUID id) { return view(jobs.owned(owner, id)); }
    @Transactional
    public JobView cancel(UUID owner, UUID id) {
        var owned = jobs.owned(owner, id);
        jobs.lockMeeting(owner, owned.meetingId());
        return view(jobs.cancelLocked(jobs.locked(id)));
    }
    @Transactional
    public JobView retry(UUID owner, UUID id) {
        jobs.lockOwner(owner);
        var owned = jobs.owned(owner, id);
        var meeting = jobs.lockMeeting(owner, owned.meetingId());
        var job = jobs.locked(id);
        if (job.inputVersion() != meeting.inputVersion() || !job.revisionId().equals(meeting.revisionId())) throw conflict("STALE_VERSION", "Job thuộc input cũ. Tạo lượt phân tích mới từ input hiện hành.");
        if ((job.status() != Status.FAILED && job.status() != Status.PARTIAL_FAILED) || !job.errorRetryable()) {
            throw conflict("JOB_NOT_RETRYABLE", "Job này không thể retry. Xử lý nguyên nhân rồi tạo lượt mới.");
        }
        if (job.retryCount() >= properties.maxManualRetries()) throw conflict("RETRY_LIMIT", "Đã hết số lần retry cho job này.");
        if (jobs.active(job.meetingId())) throw conflict("RESOURCE_BUSY", "Meeting đang có job khác hoạt động.");
        if (!meeting.sourceAvailable()) throw conflict("SOURCE_UNAVAILABLE", "Nguồn đã mất. Nhập lại transcript.");
        if (jobs.activeForOwner(owner) >= properties.maxActivePerUser()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "USER_JOB_LIMIT", "Đã đạt giới hạn job đang chạy.");
        return view(jobs.retryLocked(job));
    }
    private JobView view(AnalysisJob job) {
        var checkpoint = jobs.checkpoint(job.id());
        return new JobView(job.id(), job.meetingId(), job.revisionId(), job.inputVersion(), job.status().name(), job.stage(),
                job.completedChunks(), job.totalChunks(), checkpoint.preparedSegments(), checkpoint.estimatedInputTokens(), "UTF8_BYTE_UPPER_BOUND",
                job.providerId(), job.model(), job.policyId(), job.promptVersion(), job.schemaVersion(), job.attemptCount(), job.retryCount(),
                job.errorCode() == null ? null : new ErrorView(job.errorCode(), JobFailure.message(job.errorCode()), job.errorRetryable()),
                job.createdAt(), job.updatedAt(), job.completedAt(), "/api/v1/meetings/" + job.meetingId(), "/api/v1/jobs/" + job.id());
    }
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
