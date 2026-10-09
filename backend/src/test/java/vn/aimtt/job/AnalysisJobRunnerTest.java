package vn.aimtt.job;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.AnalysisJob.*;
import static vn.aimtt.job.JobFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisJobRunnerTest {
    final AnalysisJobStore store = mock(AnalysisJobStore.class);
    final AnalysisPipeline pipeline = mock(AnalysisPipeline.class);
    final AnalysisJobRunner runner = new AnalysisJobRunner(store, properties(), pipeline);
    final AnalysisJob processing = job(Status.PROCESSING, false);
    final Lease lease = processing.lease();
    @BeforeEach void setup() {
        when(store.heartbeat(lease)).thenReturn(true);
        when(store.get(JOB)).thenReturn(processing);
    }
    @Test void recoveryContinuesAfterCommittedSequenceWithoutRecountingPreviousSegments() {
        var checkpoint = new Checkpoint(99, 100, 4000, false);
        var batch = List.of(new SourceSegment(100, "Mai: không đổi hạn."));
        when(store.checkpoint(JOB)).thenReturn(checkpoint);
        when(store.source(processing, 99, 100)).thenReturn(new SourceBatch(true, batch));
        when(store.saveCheckpoint(lease, checkpoint, batch, PreparationBudget.tokens(batch), true)).thenReturn(true);
        assertThat(runner.advance(lease)).isTrue();
        verify(store).saveCheckpoint(lease, checkpoint, batch, PreparationBudget.tokens(batch), true);
        verifyNoInteractions(pipeline);
    }
    @Test void cancelledOrLostLeaseStopsBeforeReadingSourceOrCallingProvider() {
        when(store.heartbeat(lease)).thenReturn(false);
        assertThat(runner.advance(lease)).isFalse();
        verify(store, never()).source(any(), anyInt(), anyInt());
        verifyNoInteractions(pipeline);
    }
    @Test void sourceMissingAndSequenceGapFailWithoutPublishingResults() {
        when(store.checkpoint(JOB)).thenReturn(new Checkpoint(-1, 0, 0, false));
        when(store.source(processing, -1, 100)).thenReturn(new SourceBatch(false, List.of()));
        assertThat(runner.advance(lease)).isFalse();
        verify(store).fail(lease, "SOURCE_UNAVAILABLE", false);
        when(store.source(processing, -1, 100)).thenReturn(new SourceBatch(true, List.of(new SourceSegment(4, "Wrong sequence"))));
        assertThat(runner.advance(lease)).isFalse();
        verify(store, times(2)).fail(lease, "SOURCE_UNAVAILABLE", false);
        verifyNoInteractions(pipeline);
    }
    @Test void overBudgetFailsBeforeProviderWithoutTruncatingSource() {
        when(store.checkpoint(JOB)).thenReturn(new Checkpoint(-1, 0, 0, false));
        when(store.source(processing, -1, 100)).thenReturn(new SourceBatch(true, List.of(new SourceSegment(0, "a".repeat(140000)))));
        assertThat(runner.advance(lease)).isFalse();
        verify(store).fail(lease, "TRANSCRIPT_OVER_BUDGET", false);
        verify(store, never()).saveCheckpoint(any(), any(), any(), anyLong(), anyBoolean());
        verifyNoInteractions(pipeline);
    }
    @Test void preparedInputWithoutAdapterIsFailedExplicitlyNotCompleted() {
        when(store.beginProvider(lease)).thenReturn(true);
        when(store.checkpoint(JOB)).thenReturn(new Checkpoint(2, 3, 200, true));
        var realRunner = new AnalysisJobRunner(store, properties(), new AnalysisPipeline());
        assertThat(realRunner.advance(lease)).isFalse();
        verify(store).fail(lease, "PROVIDER_NOT_CONFIGURED", false);
        verify(store, never()).source(any(), anyInt(), anyInt());
    }
    @Test void unexpectedExceptionStoresSanitizedFailure() {
        when(store.get(JOB)).thenThrow(new RuntimeException("private transcript and SQL secret"));
        assertThat(runner.advance(lease)).isFalse();
        verify(store).fail(lease, "INTERNAL_JOB_ERROR", true);
        assertThat(JobFailure.message("INTERNAL_JOB_ERROR")).doesNotContain("private", "SQL", "secret");
    }
    @Test void interruptedWorkerLeavesLeaseRecoverableWithoutFakeTerminalState() {
        when(store.claim()).thenReturn(Optional.of(lease));
        Thread.currentThread().interrupt();
        try { runner.runOnce(); } finally { Thread.interrupted(); }
        verify(store, never()).fail(any(), any(), anyBoolean());
        verify(store, never()).heartbeat(any());
    }
    @Test void leaseGenerationAndExpiryFenceStaleWorker() {
        assertThat(processing.heldBy(lease, NOW)).isTrue();
        assertThat(processing.heldBy(new Lease(JOB, java.util.UUID.randomUUID(), 1), NOW)).isFalse();
        assertThat(processing.heldBy(new Lease(JOB, LEASE, 0), NOW)).isFalse();
        assertThat(processing.heldBy(lease, NOW.plusSeconds(30))).isFalse();
        assertThat(job(Status.CANCELLED, false).heldBy(lease, NOW)).isFalse();
    }
    @Test void estimatorCountsUnicodeEscapesAndMetadataInsteadOfUsingCharCountAsTokens() {
        long ascii = PreparationBudget.tokens(List.of(new SourceSegment(0, "ab")));
        long unicode = PreparationBudget.tokens(List.of(new SourceSegment(0, "😀")));
        long escaped = PreparationBudget.tokens(List.of(new SourceSegment(0, "\u0001")));
        assertThat(unicode).isGreaterThan(ascii);
        assertThat(escaped).isGreaterThan(unicode);
        assertThat(PreparationBudget.tokens(List.of(new SourceSegment(0, "\ud800")))).isEqualTo(escaped);
        assertThat(PreparationBudget.exceeds(processing, processing.contextTokens())).isTrue();
    }
}
