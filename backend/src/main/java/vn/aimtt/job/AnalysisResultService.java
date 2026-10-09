package vn.aimtt.job;

import com.fasterxml.jackson.databind.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.aimtt.common.ApiException;

@Service
public class AnalysisResultService {
    public record View(UUID jobId, UUID meetingId, UUID transcriptRevision, long inputVersion, String providerId, String model, JsonNode result) {}
    private final AnalysisJobStore jobs;
    private final ObjectMapper json;
    public AnalysisResultService(AnalysisJobStore jobs, ObjectMapper json) { this.jobs = jobs; this.json = json; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID owner, UUID meetingId, UUID expectedJob) {
        var job = jobs.currentOwned(owner, meetingId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY", "Meeting chưa có kết quả phân tích hiện hành."));
        if (expectedJob != null && !expectedJob.equals(job.id())) throw new ApiException(HttpStatus.CONFLICT, "STALE_VIEW", "Meeting đã có job mới. Tải lại để xem đúng kết quả.");
        if (job.status() != AnalysisJob.Status.COMPLETED) throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY", "Job chưa hoàn tất; chưa có kết quả được công bố.");
        String result = jobs.result(job.id()).orElseThrow(() -> new IllegalStateException("Missing completed analysis result"));
        try { return new View(job.id(), job.meetingId(), job.revisionId(), job.inputVersion(), job.providerId(), job.model(), json.readTree(result)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid stored analysis result"); }
    }
}
