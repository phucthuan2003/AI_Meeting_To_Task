package vn.aimtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import vn.aimtt.job.*;
import vn.aimtt.job.AnalysisJob.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisJobIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { TestDatabase.properties(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisJobStore jobs;
    @Autowired AnalysisJobRunner runner;
    private final List<UUID> createdJobs = new ArrayList<>();
    private final List<UUID> createdMeetings = new ArrayList<>();

    @AfterEach void releaseFixtures() {
        // Keep other classes/users intact when using a supplied test database.
        for (UUID id : createdMeetings) {
            jdbc.update("""
                    update analysis_jobs set status = 'CANCELLED', stage = 'CANCELLED', lease_owner = null,
                    lease_expires_at = null, completed_at = clock_timestamp() where meeting_id = ? and status in ('QUEUED','PROCESSING','CANCEL_REQUESTED')
                    """, id);
        }
    }
    record User(String token, UUID id) {}
    User user() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String credentials = json.writeValueAsString(Map.of("email", email, "password", "strong-password-123"));
        var registered = body(mvc.perform(post("/api/v1/auth/register").with(r -> { r.setRemoteAddr(email); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isCreated()).andReturn());
        String token = body(mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(email); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
        return new User(token, UUID.fromString(registered.get("id").asText()));
    }
    JsonNode meeting(User owner, String text) throws Exception {
        var meeting = body(mvc.perform(post("/api/v1/meetings").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "Planning", "meetingDate", "2026-10-09",
                        "timezone", "Asia/Ho_Chi_Minh", "transcriptText", text)))).andExpect(status().isCreated()).andReturn());
        createdMeetings.add(UUID.fromString(meeting.get("meetingId").asText()));
        return meeting;
    }
    String startBody(JsonNode meeting) throws Exception {
        return json.writeValueAsString(Map.of("expectedInputVersion", meeting.get("inputVersion").asLong(), "providerId", "unconfigured", "processingPolicyId", "foundation-v1"));
    }
    JsonNode start(User owner, JsonNode meeting) throws Exception { return start(owner, meeting, UUID.randomUUID().toString()); }
    JsonNode start(User owner, JsonNode meeting, String key) throws Exception {
        var result = body(mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText())
                .header("Authorization", bearer(owner)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(startBody(meeting))).andExpect(status().isAccepted()).andReturn());
        UUID id = UUID.fromString(result.get("jobId").asText());
        if (!createdJobs.contains(id)) createdJobs.add(id);
        return result;
    }
    Lease claimSpecific(UUID id) {
        // All owned active fixtures from previous tests are terminal. Fresh DB/supplied test DB must not contain external queued jobs.
        var lease = jobs.claim().orElseThrow();
        assertThat(lease.jobId()).isEqualTo(id); return lease;
    }
    @Test void enqueueSnapshotsMetadataAndIdempotencyWithoutIncrementingInputVersion() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Mai: sửa login."); String key = UUID.randomUUID().toString();
        var job = start(owner, meeting, key); var repeat = start(owner, meeting, key);
        assertThat(repeat.get("jobId")).isEqualTo(job.get("jobId"));
        UUID id = UUID.fromString(job.get("jobId").asText());
        var stored = jobs.get(id);
        assertThat(stored.title()).isEqualTo("Planning");
        assertThat(stored.meetingDate().toString()).isEqualTo("2026-10-09");
        assertThat(stored.inputVersion()).isEqualTo(meeting.get("inputVersion").asLong());
        assertThat(stored.status()).isEqualTo(Status.QUEUED);
        mvc.perform(get("/api/v1/meetings/{id}", meeting.get("meetingId").asText()).header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.inputVersion").value(meeting.get("inputVersion").asLong()))
                .andExpect(jsonPath("$.currentAnalysisJobId").value(id.toString())).andExpect(jsonPath("$.analysisStatus").value("QUEUED"));
        mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText()).header("Authorization", bearer(owner))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(startBody(meeting).replace("foundation-v1", "other")))
                .andExpect(status().isBadRequest());
    }
    @Test void ownerChecksCoverGetCancelRetryAndEnqueue() throws Exception {
        var owner = user(); var other = user(); var meeting = meeting(owner, "Nam: làm A."); var job = start(owner, meeting);
        mvc.perform(get("/api/v1/jobs/{id}", job.get("jobId").asText()).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        for (String action : List.of("cancel", "retry")) mvc.perform(post("/api/v1/jobs/{id}/" + action, job.get("jobId").asText())
                .header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText()).header("Authorization", bearer(other))
                .contentType(MediaType.APPLICATION_JSON).content(startBody(meeting))).andExpect(status().isNotFound());
    }
    @Test void activeJobBlocksInputAndQueuedCancelIsIdempotentThenAllowsNewRevision() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Mai: làm A."); var job = start(owner, meeting);
        String input = json.writeValueAsString(Map.of("expectedVersion", meeting.get("inputVersion").asLong(), "transcriptText", "Mai: đổi A sang B."));
        mvc.perform(patch("/api/v1/meetings/{id}/input", meeting.get("meetingId").asText()).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/v1/jobs/{id}/cancel", job.get("jobId").asText()).header("Authorization", bearer(owner)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(patch("/api/v1/meetings/{id}/input", meeting.get("meetingId").asText()).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.currentAnalysisJobId").isEmpty());
    }
    @Test void runningCancelIsAcknowledgedAndCheckpointCannotPublishAfterCancel() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Mai: làm A."); var job = start(owner, meeting);
        UUID id = UUID.fromString(job.get("jobId").asText()); Lease lease = claimSpecific(id);
        mvc.perform(post("/api/v1/jobs/{id}/cancel", id).header("Authorization", bearer(owner)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
        assertThat(runner.advance(lease)).isFalse();
        assertThat(jobs.get(id).status()).isEqualTo(Status.CANCELLED);
        assertThat(jobs.checkpoint(id).preparedSegments()).isZero();
        assertThat(jobs.fail(lease, "INTERNAL_JOB_ERROR", true)).isFalse();
    }
    @Test void durableCheckpointSurvivesLeaseRecoveryAndFencesPreviousWorker() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Nam: làm A.\nMai: làm B.\nHuy: kiểm thử."); var job = start(owner, meeting);
        UUID id = UUID.fromString(job.get("jobId").asText()); Lease first = claimSpecific(id);
        var checkpoint = jobs.checkpoint(id);
        var source = jobs.source(jobs.get(id), -1, 1).segments();
        assertThat(jobs.saveCheckpoint(first, checkpoint, source, PreparationBudget.tokens(source), false)).isTrue();
        jdbc.update("update analysis_jobs set lease_expires_at = clock_timestamp() - interval '1 second' where id = ?", id);
        Lease second = claimSpecific(id);
        assertThat(second.attempt()).isEqualTo(first.attempt() + 1);
        assertThat(second.owner()).isNotEqualTo(first.owner());
        assertThat(jobs.heartbeat(first)).isFalse();
        assertThat(jobs.fail(first, "INTERNAL_JOB_ERROR", true)).isFalse();
        assertThat(runner.advance(second)).isTrue();
        assertThat(jobs.checkpoint(id).preparedSegments()).isEqualTo(3);
        assertThat(jobs.checkpoint(id).lastSequence()).isEqualTo(2);
        assertThat(runner.advance(second)).isFalse();
        assertThat(jobs.get(id).status()).isEqualTo(Status.FAILED);
        assertThat(jobs.get(id).errorCode()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        assertThat(jobs.get(id).completedChunks()).isZero();
    }
    @Test void expiredCancellationIsRecoveredWithoutCallingPipeline() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Nam: làm A."); var job = start(owner, meeting);
        UUID id = UUID.fromString(job.get("jobId").asText()); claimSpecific(id);
        mvc.perform(post("/api/v1/jobs/{id}/cancel", id).header("Authorization", bearer(owner))).andExpect(status().isAccepted());
        jdbc.update("update analysis_jobs set lease_expires_at = clock_timestamp() - interval '1 second' where id = ?", id);
        assertThat(jobs.claim()).isEmpty();
        assertThat(jobs.get(id).status()).isEqualTo(Status.CANCELLED);
    }
    @Test void leaseRecoveryBoundAndManualRetryKeepOriginalSnapshotAndCheckpoint() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Nam: làm A."); var job = start(owner, meeting);
        UUID id = UUID.fromString(job.get("jobId").asText()); claimSpecific(id);
        jdbc.update("update analysis_jobs set attempt_count = attempt_limit, lease_expires_at = clock_timestamp() - interval '1 second' where id = ?", id);
        assertThat(jobs.claim()).isEmpty();
        assertThat(jobs.get(id).errorCode()).isEqualTo("LEASE_RECOVERY_EXHAUSTED");
        mvc.perform(post("/api/v1/jobs/{id}/retry", id).header("Authorization", bearer(owner)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(id.toString())).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.retryCount").value(1)).andExpect(jsonPath("$.transcriptRevision").value(meeting.get("transcriptRevision").asText()));
        assertThat(jobs.get(id).attemptLimit()).isEqualTo(6);
    }
    @Test void providerUnavailableFailsExplicitlyAndIsNotRetryable() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Mai: sửa login."); var job = start(owner, meeting);
        UUID id = UUID.fromString(job.get("jobId").asText()); runner.runOnce();
        mvc.perform(get("/api/v1/jobs/{id}", id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("PROVIDER_NOT_CONFIGURED")).andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.preparedSegments").value(1)).andExpect(jsonPath("$.completedChunks").value(0));
        mvc.perform(post("/api/v1/jobs/{id}/retry", id).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_NOT_RETRYABLE"));
        mvc.perform(post("/api/v1/jobs/{id}/retry", id).header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcriptText\":\"Cannot replace frozen source\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    @Test void concurrentEnqueueAndWorkersClaimOnlyOnce() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Mai: sửa login."); String body = startBody(meeting);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<MvcResult> enqueue = () -> { start.await(); return mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText())
                    .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(); };
            var a = pool.submit(enqueue); var b = pool.submit(enqueue); start.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(202, 409);
            UUID id = UUID.fromString(body(results.stream().filter(r -> r.getResponse().getStatus() == 202).findFirst().orElseThrow()).get("jobId").asText());
            createdJobs.add(id);
            var gate = new CountDownLatch(1);
            Callable<Optional<Lease>> claim = () -> { gate.await(); return jobs.claim(); };
            var x = pool.submit(claim); var y = pool.submit(claim); gate.countDown();
            assertThat(List.of(x.get(10, TimeUnit.SECONDS), y.get(10, TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(jobs.get(id).attemptCount()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void ownerQuotaAndStaleInputReturnStructuredErrors() throws Exception {
        var owner = user(); var a = meeting(owner, "A."); var b = meeting(owner, "B."); var c = meeting(owner, "C.");
        start(owner, a); start(owner, b);
        mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", c.get("meetingId").asText()).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(startBody(c))).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("USER_JOB_LIMIT")).andExpect(header().string("Retry-After", "60"));
        mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", c.get("meetingId").asText()).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedInputVersion", 0, "providerId", "unconfigured", "processingPolicyId", "foundation-v1"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }
    @Test void editingAfterFailureInvalidatesCurrentJobAndRejectsOldRetry() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Mai: làm A."); var job = start(owner, meeting);
        UUID id = UUID.fromString(job.get("jobId").asText()); Lease lease = claimSpecific(id);
        jobs.fail(lease, "INTERNAL_JOB_ERROR", true);
        mvc.perform(patch("/api/v1/meetings/{id}/input", meeting.get("meetingId").asText()).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion", meeting.get("inputVersion").asLong(), "transcriptText", "Mai: làm B."))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisStatus").value("NOT_STARTED"));
        mvc.perform(post("/api/v1/jobs/{id}/retry", id).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(get("/api/v1/jobs/{id}", id).header("Authorization", bearer(owner))).andExpect(status().isOk());
    }
    @Test void queuePersistsWithoutPanelAndAuthenticatedPolicyListsNoSecrets() throws Exception {
        var owner = user(); var meeting = meeting(owner, "Nam: làm A."); var job = start(owner, meeting);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        runner.runOnce();
        assertThat(jobs.get(UUID.fromString(job.get("jobId").asText())).status()).isEqualTo(Status.FAILED);
        var other = user();
        mvc.perform(get("/api/v1/analysis-policies").header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].providerReady").value(false));
        mvc.perform(get("/api/v1/jobs/{id}", job.get("jobId").asText()).header("Authorization", bearer(owner))).andExpect(status().isUnauthorized());
    }
    @Test void providerChoicesArePersistedAndChangingProviderRequiresANewIdempotencyKey() throws Exception {
        var owner = user();
        mvc.perform(get("/api/v1/analysis-policies").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].providerId").value("openai")).andExpect(jsonPath("$[1].providerId").value("gemini"));
        var meeting = meeting(owner, "Mai: sửa login.");
        for (String provider : List.of("openai", "gemini")) {
            String key = UUID.randomUUID().toString();
            String input = startBody(meeting).replace("unconfigured", provider);
            var response = body(mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText())
                    .header("Authorization", bearer(owner)).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.providerId").value(provider)).andReturn());
            UUID id = UUID.fromString(response.get("jobId").asText());
            assertThat(jobs.get(id).providerId()).isEqualTo(provider);
            mvc.perform(post("/api/v1/jobs/{id}/cancel", id).header("Authorization", bearer(owner))).andExpect(status().isAccepted());
            mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText())
                    .header("Authorization", bearer(owner)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .content(input.replace(provider, provider.equals("openai") ? "gemini" : "openai")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
            mvc.perform(get("/api/v1/jobs/{id}", id).header("Authorization", bearer(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.providerId").value(provider));
        }
    }
    private String bearer(User user) { return "Bearer " + user.token(); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
}
