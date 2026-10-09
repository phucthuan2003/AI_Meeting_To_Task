package vn.aimtt.job;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJob.Status;
import vn.aimtt.job.AnalysisJobService.StartInput;
import static vn.aimtt.job.JobFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisJobServiceTest {
    final AnalysisJobStore store = mock(AnalysisJobStore.class);
    final AnalysisJobService service = new AnalysisJobService(store, new AnalysisPolicy(), properties());
    final StartInput input = new StartInput(7L, "unconfigured", "foundation-v1");
    @BeforeEach void setup() {
        when(store.lockMeeting(OWNER, MEETING)).thenReturn(new AnalysisJobStore.MeetingSnapshot(MEETING, REVISION, 7, "Planning", null, null, true));
        when(store.byKey(any(), any())).thenReturn(Optional.empty());
        when(store.checkpoint(JOB)).thenReturn(new AnalysisJob.Checkpoint(-1, 0, 0, false));
    }
    void expectCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    @Test void startPinsInputAndReturnsQueuedWithoutProviderCall() {
        when(store.create(any(), eq("fixture-key"), argThat(p -> p != null && p.providerId().equals("unconfigured")))).thenReturn(job(Status.QUEUED, false));
        var result = service.start(OWNER, MEETING, input, "fixture-key");
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.inputVersion()).isEqualTo(7);
        assertThat(result.transcriptRevision()).isEqualTo(REVISION);
        assertThat(result.totalChunks()).isNull();
        assertThat(result.completedChunks()).isZero();
    }
    @Test void repeatedKeyReturnsSameJobEvenAfterFinishButDifferentInputConflicts() {
        when(store.byKey(MEETING, "fixture-key")).thenReturn(Optional.of(job(Status.FAILED, false)));
        assertThat(service.start(OWNER, MEETING, input, "fixture-key").jobId()).isEqualTo(JOB);
        expectCode(() -> service.start(OWNER, MEETING, new StartInput(8L, "unconfigured", "foundation-v1"), "fixture-key"), "IDEMPOTENCY_CONFLICT");
        expectCode(() -> service.start(OWNER, MEETING, new StartInput(7L, "gemini", "foundation-v1"), "fixture-key"), "IDEMPOTENCY_CONFLICT");
        verify(store, never()).create(any(), any(), any());
    }
    @Test void staleVersionAndActiveJobDoNotEnqueue() {
        expectCode(() -> service.start(OWNER, MEETING, new StartInput(6L, "unconfigured", "foundation-v1"), "fixture-key"), "STALE_VERSION");
        when(store.active(MEETING)).thenReturn(true);
        expectCode(() -> service.start(OWNER, MEETING, input, "fixture-key"), "RESOURCE_BUSY");
        verify(store, never()).create(any(), any(), any());
    }
    @Test void invalidPolicyAndKeyCannotEnqueue() {
        expectCode(() -> service.start(OWNER, MEETING, new StartInput(7L, null, "llm-v1"), "fixture-key"), "INVALID_INPUT");
        expectCode(() -> service.start(OWNER, MEETING, new StartInput(7L, "remote-custom", "custom"), "fixture-key"), "INVALID_INPUT");
        expectCode(() -> service.start(OWNER, MEETING, input, "bad key"), "INVALID_INPUT");
        expectCode(() -> service.start(OWNER, MEETING, new StartInput(7L, "OpenAI", "foundation-v1"), "fixture-key"), "INVALID_INPUT");
        verify(store, never()).create(any(), any(), any());
    }
    @Test void replayOfAcceptedLlmJobSurvivesKeyRemovalButNewJobRequiresConfiguration() {
        var accepted = mock(AnalysisJob.class);
        when(accepted.id()).thenReturn(JOB); when(accepted.status()).thenReturn(Status.FAILED);
        when(accepted.inputVersion()).thenReturn(7L); when(accepted.providerId()).thenReturn("openai"); when(accepted.policyId()).thenReturn("llm-v1");
        when(store.byKey(MEETING, "fixture-key")).thenReturn(Optional.of(accepted));
        var llmInput = new StartInput(7L, "openai", "llm-v1");
        assertThat(service.start(OWNER, MEETING, llmInput, "fixture-key").jobId()).isEqualTo(JOB);
        expectCode(() -> service.start(OWNER, MEETING, llmInput, "different-key"), "INVALID_INPUT");
        verify(store, never()).create(any(), any(), any());
    }
    @Test void ownerQuotaReturns429WithoutCreatingJob() {
        when(store.activeForOwner(OWNER)).thenReturn(2);
        assertThatThrownBy(() -> service.start(OWNER, MEETING, input, "fixture-key")).isInstanceOfSatisfying(ApiException.class,
                e -> { assertThat(e.code()).isEqualTo("USER_JOB_LIMIT"); assertThat(e.status().value()).isEqualTo(429); });
        verify(store, never()).create(any(), any(), any());
    }
    @Test void sourceUnavailableCannotEnqueue() {
        when(store.lockMeeting(OWNER, MEETING)).thenReturn(new AnalysisJobStore.MeetingSnapshot(MEETING, REVISION, 7, null, null, null, false));
        expectCode(() -> service.start(OWNER, MEETING, input, "fixture-key"), "SOURCE_UNAVAILABLE");
    }
    @Test void policyListOffersExactlyTwoChoicesWithoutClaimingAnAdapterIsReady() {
        var policies = new AnalysisPolicy().views();
        assertThat(policies).extracting(AnalysisPolicy.View::providerId).containsExactly("openai", "gemini");
        assertThat(policies).extracting(AnalysisPolicy.View::displayName).containsExactly("OpenAI", "Gemini");
        assertThat(policies).allMatch(p -> !p.providerReady() && p.processingPolicyId().equals("foundation-v1"));
    }
    @Test void chosenProviderIsPassedToPersistenceAndReturnedInJobSnapshot() {
        for (String provider : java.util.List.of("openai", "gemini")) {
            when(store.create(any(), eq("fixture-key"), argThat(p -> p != null && p.providerId().equals(provider)))).thenReturn(job(Status.QUEUED, false, provider));
            var result = service.start(OWNER, MEETING, new StartInput(7L, provider, "foundation-v1"), "fixture-key");
            assertThat(result.providerId()).isEqualTo(provider);
            assertThat(result.model()).isEqualTo("NOT_CONFIGURED");
            verify(store).create(any(), eq("fixture-key"), argThat(p -> p != null && p.providerId().equals(provider)));
        }
    }
    @Test void anotherOwnerCannotReadOrCancelJob() {
        UUID other = UUID.randomUUID();
        when(store.owned(other, JOB)).thenThrow(ApiException.notFound());
        expectCode(() -> service.get(other, JOB), "RESOURCE_NOT_FOUND");
        expectCode(() -> service.cancel(other, JOB), "RESOURCE_NOT_FOUND");
        verify(store, never()).cancelLocked(any());
    }
    @Test void retryPreservesJobAndRejectsOldRevisionOrNonretryableFailure() {
        var failed = job(Status.FAILED, true);
        when(store.owned(OWNER, JOB)).thenReturn(failed);
        when(store.locked(JOB)).thenReturn(failed);
        when(store.retryLocked(failed)).thenReturn(job(Status.QUEUED, false));
        assertThat(service.retry(OWNER, JOB).jobId()).isEqualTo(JOB);
        when(store.lockMeeting(OWNER, MEETING)).thenReturn(new AnalysisJobStore.MeetingSnapshot(MEETING, UUID.randomUUID(), 8, null, null, null, true));
        expectCode(() -> service.retry(OWNER, JOB), "STALE_VERSION");
        when(store.lockMeeting(OWNER, MEETING)).thenReturn(new AnalysisJobStore.MeetingSnapshot(MEETING, REVISION, 7, null, null, null, true));
        when(store.locked(JOB)).thenReturn(job(Status.FAILED, false));
        expectCode(() -> service.retry(OWNER, JOB), "JOB_NOT_RETRYABLE");
    }
}
