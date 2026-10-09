package vn.aimtt.job;

import java.time.*;
import java.util.UUID;
import vn.aimtt.job.AnalysisJob.Status;

final class JobFixture {
    static final UUID OWNER = UUID.randomUUID(), MEETING = UUID.randomUUID(), REVISION = UUID.randomUUID(), JOB = UUID.randomUUID(), LEASE = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    static AnalysisProperties properties() { return new AnalysisProperties(false, Duration.ofSeconds(30), 3, 3, 2, 100, 131072, 8192); }
    static AnalysisJob job(Status status, boolean retryable) {
        return job(status, retryable, "unconfigured");
    }
    static AnalysisJob job(Status status, boolean retryable, String provider) {
        boolean leased = status == Status.PROCESSING || status == Status.CANCEL_REQUESTED;
        return new AnalysisJob(JOB, MEETING, REVISION, 7, "Planning", LocalDate.of(2026, 10, 9), "Asia/Ho_Chi_Minh",
                provider, "NOT_CONFIGURED", "foundation-v1", "pending-llm-v1", "action-items-v1", 131072, 8192,
                "fixture-key", status, leased ? "PREPARING_INPUT" : "WAITING", 0, null,
                leased ? LEASE : null, leased ? NOW.plusSeconds(30) : null, 1, 3, 0, NOW,
                retryable ? "INTERNAL_JOB_ERROR" : null, retryable, NOW, NOW, status.active() ? null : NOW);
    }
}
