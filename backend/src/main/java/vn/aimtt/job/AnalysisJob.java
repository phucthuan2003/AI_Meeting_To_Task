package vn.aimtt.job;

import java.time.*;
import java.util.UUID;

public record AnalysisJob(UUID id, UUID meetingId, UUID revisionId, long inputVersion,
        String title, LocalDate meetingDate, String timezone, String providerId, String model,
        String policyId, String promptVersion, String schemaVersion, int contextTokens, int reservedTokens,
        String idempotencyKey, Status status, String stage, int completedChunks, Integer totalChunks,
        UUID leaseOwner, Instant leaseExpiresAt, int attemptCount, int attemptLimit, int retryCount,
        Instant nextRetryAt, String errorCode, boolean errorRetryable, Instant createdAt, Instant updatedAt, Instant completedAt) {
    public enum Status {
        QUEUED, PROCESSING, COMPLETED, PARTIAL_FAILED, FAILED, CANCEL_REQUESTED, CANCELLED;
        public boolean active() { return this == QUEUED || this == PROCESSING || this == CANCEL_REQUESTED; }
    }
    public record Lease(UUID jobId, UUID owner, int attempt) {}
    public Lease lease() { return new Lease(id, leaseOwner, attemptCount); }
    public boolean heldBy(Lease lease, Instant now) {
        return id.equals(lease.jobId()) && lease.owner() != null && lease.owner().equals(leaseOwner)
                && attemptCount == lease.attempt() && leaseExpiresAt != null && leaseExpiresAt.isAfter(now)
                && (status == Status.PROCESSING || status == Status.CANCEL_REQUESTED);
    }
    public record Checkpoint(int lastSequence, int preparedSegments, long estimatedInputTokens, boolean prepared) {}
    public record SourceSegment(int sequence, String text) {}
    public record SourceBatch(boolean available, java.util.List<SourceSegment> segments) {}
}
