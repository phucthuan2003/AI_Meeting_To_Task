package vn.aimtt;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.aimtt.job.*;
import vn.aimtt.llm.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SDS §14.4 review drafts on real PostgreSQL: versions, owner checks, current-set rules and provenance. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskReviewIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.properties(registry);
        registry.add("app.llm.openai.api-key", () -> "synthetic-openai-key");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalysisJobStore jobs;
    @Autowired AnalysisJobRunner runner;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean LlmHttpTransport transport;

    String token(String suffix) throws Exception {
        String id = UUID.randomUUID() + suffix;
        String credentials = json.writeValueAsString(Map.of("email", id + "@example.test", "password", "synthetic-password-123"));
        mvc.perform(post("/api/v1/auth/register").with(r -> { r.setRemoteAddr(id); return r; }).contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isCreated());
        return read(mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(id); return r; }).contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
    record Analysed(String meetingId, long inputVersion, UUID jobId) {}
    Analysed analysed(String token, String meetingDate) throws Exception {
        var input = new HashMap<String, Object>(Map.of("transcriptText", LlmFixtures.TEXT, "timezone", "Asia/Ho_Chi_Minh"));
        if (meetingDate != null) input.put("meetingDate", meetingDate);
        var meeting = read(mvc.perform(post("/api/v1/meetings").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return analyse(token, meeting.get("meetingId").asText(), meeting.get("inputVersion").asLong());
    }
    Analysed analyse(String token, String meetingId, long version) throws Exception {
        var job = read(mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs", meetingId).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedInputVersion", version, "providerId", "openai", "processingPolicyId", "llm-v1"))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        UUID jobId = UUID.fromString(job.get("jobId").asText());
        var source = jobs.analysisSource(jobs.get(jobId));
        String output = LlmFixtures.output().replace(LlmFixtures.SEGMENT.toString(), source.get(0).segmentId().toString());
        when(transport.send(any(), any())).thenReturn(new LlmHttpTransport.Response(200, json.writeValueAsBytes(
                Map.of("status", "completed", "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", output))))))));
        // The DB queue is shared with other test classes; drain until this job is terminal.
        for (int i = 0; i < 20 && jobs.get(jobId).status().active(); i++) runner.runOnce();
        assertThat(jobs.get(jobId).status()).isEqualTo(AnalysisJob.Status.COMPLETED);
        return new Analysed(meetingId, version, jobId);
    }
    JsonNode tasks(String token, String meetingId) throws Exception {
        return read(mvc.perform(get("/api/v1/meetings/{id}/tasks", meetingId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ResultActions patch(String token, String taskId, Map<String, ?> body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/tasks/{id}", taskId)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    JsonNode body(ResultActions actions) throws Exception { return read(actions.andReturn().getResponse().getContentAsString()); }

    @Test void aiCandidatesBecomeVersionedDraftsWithEvidenceAndServerWarnings() throws Exception {
        String token = token("a");
        var run = analysed(token, "2026-10-09");
        var list = tasks(token, run.meetingId());
        assertThat(list.get("analysis").get("jobId").asText()).isEqualTo(run.jobId().toString());
        var task = list.get("tasks").get(0);
        assertThat(task.get("origin").asText()).isEqualTo("AI");
        assertThat(task.get("version").asLong()).isEqualTo(1);
        assertThat(task.get("aiSuggestion").get("dueLocal").asText()).isEqualTo("2026-10-12T17:00");
        assertThat(task.get("dueLocal").isNull()).isTrue();
        assertThat(task.get("deadlineResolution").asText()).isEqualTo("AMBIGUOUS");
        assertThat(codes(task)).containsExactly("ASSIGNEE_NOT_RESOLVED_TO_MEMBER", "DEADLINE_NEEDS_CONFIRMATION");
        assertThat(task.get("readyForApproval").asBoolean()).isFalse();
        assertThat(task.get("evidence")).hasSize(3);
        assertThat(task.get("evidence").get(0).get("quote").asText()).isEqualTo(LlmFixtures.TEXT);
        assertThat(list.get("counts").get("pending").asInt()).isEqualTo(1);

        String id = task.get("taskId").asText();
        var evidence = body(mvc.perform(get("/api/v1/tasks/{id}/evidence", id).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertThat(evidence.get("sourceAvailable").asBoolean()).isTrue();
        assertThat(evidence.get("evidence").get(0).get("sourceLocator").has("rawStart")).isTrue();

        // Confirm the proposed deadline and explicitly choose no assignee: warnings clear, provenance kept.
        var confirmed = body(patch(token, id, Map.of("expectedVersion", 1, "deadlineDecision", "RESOLVED", "dueLocal", "2026-10-12T17:00",
                "timezone", "Asia/Ho_Chi_Minh", "memberDecision", "NONE_SELECTED")).andExpect(status().isOk()));
        assertThat(confirmed.get("version").asLong()).isEqualTo(2);
        assertThat(confirmed.get("dueAt").asText()).isEqualTo("2026-10-12T10:00:00Z");
        assertThat(confirmed.get("warnings")).isEmpty();
        assertThat(confirmed.get("readyForApproval").asBoolean()).isTrue();
        assertThat(confirmed.get("editedFields").toString()).contains("deadlineDecision", "memberDecision").doesNotContain("dueLocal", "taskName");
        assertThat(confirmed.get("aiSuggestion").get("assigneeRaw").asText()).isEqualTo("Mai");

        var renamed = body(patch(token, id, Map.of("expectedVersion", 2, "taskName", "Hoàn thiện login", "priority", "HIGH")).andExpect(status().isOk()));
        assertThat(renamed.get("editedFields").toString()).contains("taskName", "priority");
        assertThat(renamed.get("aiSuggestion").get("taskName").asText()).isEqualTo("Hoàn thành màn hình đăng nhập");
        // A no-op save keeps the version, so autosave does not create needless conflicts.
        assertThat(body(patch(token, id, Map.of("expectedVersion", 3, "priority", "HIGH")).andExpect(status().isOk())).get("version").asLong()).isEqualTo(3);
        // Clearing the decision returns to the undecided state derived from the raw deadline.
        var cleared = body(patch(token, id, Map.of("expectedVersion", 3, "deadlineDecision", "")).andExpect(status().isBadRequest()));
        assertThat(cleared.get("code").asText()).isEqualTo("INVALID_INPUT");
        var undecided = new HashMap<String, Object>(); undecided.put("expectedVersion", 3); undecided.put("deadlineDecision", null);
        assertThat(body(patch(token, id, undecided).andExpect(status().isOk())).get("deadlineResolution").asText()).isEqualTo("AMBIGUOUS");
    }

    @Test void staleVersionAndConcurrentEditsNeverOverwriteSilently() throws Exception {
        String token = token("b");
        var run = analysed(token, null);
        String id = tasks(token, run.meetingId()).get("tasks").get(0).get("taskId").asText();
        patch(token, id, Map.of("expectedVersion", 1, "taskName", "Tab A")).andExpect(status().isOk());
        var conflict = body(patch(token, id, Map.of("expectedVersion", 1, "taskName", "Tab B")).andExpect(status().isConflict()));
        assertThat(conflict.get("code").asText()).isEqualTo("STALE_VERSION");
        assertThat(conflict.get("details").get(0).get("currentVersion").asLong()).isEqualTo(2);
        assertThat(tasks(token, run.meetingId()).get("tasks").get(0).get("taskName").asText()).isEqualTo("Tab A");

        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (String name : List.of("Song song 1", "Song song 2")) futures.add(pool.submit(() -> {
                start.await(); return patch(token, id, Map.of("expectedVersion", 2, "taskName", name)).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            var statuses = new ArrayList<Integer>(); for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(tasks(token, run.meetingId()).get("tasks").get(0).get("version").asLong()).isEqualTo(3);
    }

    @Test void clientCannotWriteSystemFieldsOrAssignTrelloMembersBeforeDestination() throws Exception {
        String token = token("c");
        var run = analysed(token, null);
        String id = tasks(token, run.meetingId()).get("tasks").get(0).get("taskId").asText();
        var forbidden = body(patch(token, id, Map.of("expectedVersion", 1, "reviewStatus", "APPROVED", "trelloCardId", "x")).andExpect(status().isBadRequest()));
        assertThat(forbidden.get("code").asText()).isEqualTo("FIELD_NOT_EDITABLE");
        assertThat(forbidden.get("details")).hasSize(2);
        assertThat(body(patch(token, id, Map.of("expectedVersion", 1, "trelloMemberId", "member-1")).andExpect(status().isUnprocessableEntity())).get("code").asText())
                .isEqualTo("MEMBER_RESOLUTION_REQUIRES_TRELLO");
        assertThat(body(patch(token, id, Map.of("expectedVersion", 1, "taskName", "  ")).andExpect(status().isUnprocessableEntity())).get("code").asText()).isEqualTo("TASK_NAME_REQUIRED");
        patch(token, id, Map.of("taskName", "Thiếu version")).andExpect(status().isBadRequest());
        // DST gap: 02:30 does not exist in New York on 2026-03-08.
        assertThat(body(patch(token, id, Map.of("expectedVersion", 1, "dueLocal", "2026-03-08T02:30", "timezone", "America/New_York")).andExpect(status().isUnprocessableEntity()))
                .get("code").asText()).isEqualTo("DEADLINE_LOCAL_TIME_INVALID");
        assertThat(tasks(token, run.meetingId()).get("tasks").get(0).get("version").asLong()).isEqualTo(1);
    }

    @Test void deadlineBeforeMeetingOrInPastIsWarnedButNeverRewritten() throws Exception {
        String token = token("d");
        var run = analysed(token, "2026-10-09");
        String id = tasks(token, run.meetingId()).get("tasks").get(0).get("taskId").asText();
        var past = body(patch(token, id, Map.of("expectedVersion", 1, "dueLocal", "2026-10-01T09:00", "memberDecision", "NONE_SELECTED")).andExpect(status().isOk()));
        assertThat(past.get("dueLocal").asText()).isEqualTo("2026-10-01T09:00");
        assertThat(codes(past)).contains("DEADLINE_BEFORE_MEETING");
        assertThat(past.get("readyForApproval").asBoolean()).isTrue();
        var none = body(patch(token, id, Map.of("expectedVersion", 2, "deadlineDecision", "NONE_SELECTED")).andExpect(status().isOk()));
        assertThat(none.get("dueAt").isNull()).isTrue();
        assertThat(none.get("deadlineResolution").asText()).isEqualTo("NONE_SELECTED");
    }

    @Test void rejectRestoreAndManualTasksWithIdempotency() throws Exception {
        String token = token("e");
        var run = analysed(token, null);
        var ai = tasks(token, run.meetingId()).get("tasks").get(0);
        String id = ai.get("taskId").asText();
        mvc.perform(delete("/api/v1/tasks/{id}", id).header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/tasks/{id}", id).param("expectedVersion", "1").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/tasks/{id}", id).param("expectedVersion", "1").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        var rejected = tasks(token, run.meetingId());
        assertThat(rejected.get("tasks").get(0).get("reviewStatus").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("tasks").get(0).get("editable").asBoolean()).isFalse();
        assertThat(rejected.get("counts").get("rejected").asInt()).isEqualTo(1);
        assertThat(body(patch(token, id, Map.of("expectedVersion", 2, "taskName", "Sửa sau khi loại")).andExpect(status().isConflict())).get("code").asText()).isEqualTo("TASK_REJECTED");
        var restored = body(mvc.perform(post("/api/v1/tasks/{id}/restore", id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}")).andExpect(status().isOk()));
        assertThat(restored.get("reviewStatus").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(restored.get("version").asLong()).isEqualTo(3);

        String manual = json.writeValueAsString(Map.of("taskName", "Gửi biên bản họp", "assigneeRaw", "Nam", "dueLocal", "2026-10-15T17:00", "priority", "LOW"));
        var first = body(mvc.perform(post("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", "Bearer " + token).header("Idempotency-Key", "manual-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content(manual)).andExpect(status().isCreated()));
        assertThat(first.get("origin").asText()).isEqualTo("USER");
        assertThat(first.get("analysisJobId").isNull()).isTrue();
        assertThat(first.get("timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(first.get("deadlineResolution").asText()).isEqualTo("RESOLVED");
        assertThat(first.get("evidence")).isEmpty();
        var replay = body(mvc.perform(post("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", "Bearer " + token).header("Idempotency-Key", "manual-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content(manual)).andExpect(status().isCreated()));
        assertThat(replay.get("taskId").asText()).isEqualTo(first.get("taskId").asText());
        mvc.perform(post("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", "Bearer " + token).header("Idempotency-Key", "manual-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"taskName\":\"Khác\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"taskName\":\"x\",\"origin\":\"AI\"}")).andExpect(status().isBadRequest());
        var list = tasks(token, run.meetingId());
        assertThat(list.get("tasks")).hasSize(2);
        assertThat(list.get("tasks").get(1).get("origin").asText()).isEqualTo("USER");
        assertThat(jdbc.queryForObject("select count(*) from tasks where meeting_id = ?::uuid and origin = 'USER'", Integer.class, run.meetingId())).isEqualTo(1);

        var history = read(mvc.perform(get("/api/v1/meetings").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history.get("items").get(0).get("analysisStatus").asText()).isEqualTo("COMPLETED");
        assertThat(history.get("items").get(0).get("pendingTasks").asInt()).isEqualTo(2);
    }

    @Test void otherOwnersGetNotFoundOnEveryTaskEndpoint() throws Exception {
        String owner = token("f"), other = token("g");
        var run = analysed(owner, null);
        String id = tasks(owner, run.meetingId()).get("tasks").get(0).get("taskId").asText();
        String bearer = "Bearer " + other;
        mvc.perform(get("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"taskName\":\"x\"}")).andExpect(status().isNotFound());
        patch(other, id, Map.of("expectedVersion", 1, "taskName", "x")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/tasks/{id}", id).param("expectedVersion", "1").header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tasks/{id}/restore", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tasks/{id}/evidence", id).header("Authorization", bearer)).andExpect(status().isNotFound());
        assertThat(tasks(owner, run.meetingId()).get("tasks").get(0).get("version").asLong()).isEqualTo(1);
    }

    @Test void newInputOrReanalysisRetiresOldAiDraftsButKeepsManualTasks() throws Exception {
        String token = token("h");
        var run = analysed(token, null);
        String oldAi = tasks(token, run.meetingId()).get("tasks").get(0).get("taskId").asText();
        mvc.perform(post("/api/v1/meetings/{id}/tasks", run.meetingId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskName\":\"Việc thủ công\"}")).andExpect(status().isCreated());
        // Same input analysed again: a new candidate set replaces the old AI drafts in the current view.
        var again = analyse(token, run.meetingId(), run.inputVersion());
        var list = tasks(token, run.meetingId());
        assertThat(list.get("analysis").get("jobId").asText()).isEqualTo(again.jobId().toString());
        assertThat(list.get("tasks")).hasSize(2);
        assertThat(list.get("tasks").get(0).get("taskId").asText()).isNotEqualTo(oldAi);
        assertThat(body(patch(token, oldAi, Map.of("expectedVersion", 1, "taskName", "x")).andExpect(status().isConflict())).get("code").asText()).isEqualTo("TASK_NOT_CURRENT");
        mvc.perform(get("/api/v1/meetings/{id}/tasks", run.meetingId()).param("analysisJobId", run.jobId().toString()).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VIEW"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/meetings/{id}/input", run.meetingId()).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion", run.inputVersion(), "transcriptText", "Không có việc mới."))))
                .andExpect(status().isOk());
        var afterInput = tasks(token, run.meetingId());
        assertThat(afterInput.get("analysis").isNull()).isTrue();
        assertThat(afterInput.get("tasks")).hasSize(1);
        assertThat(afterInput.get("tasks").get(0).get("origin").asText()).isEqualTo("USER");
        assertThat(jdbc.queryForObject("select count(*) from tasks where meeting_id = ?::uuid and origin = 'AI'", Integer.class, run.meetingId())).isEqualTo(2);
    }

    @Test void v4BackfillRecreatesDraftsFromStoredResults() throws Exception {
        String token = token("i");
        var run = analysed(token, null);
        var before = tasks(token, run.meetingId()).get("tasks").get(0);
        jdbc.update("delete from tasks where analysis_job_id = ?", run.jobId());
        String script = new String(new ClassPathResource("db/migration/V4__review_tasks.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String backfill = script.substring(script.indexOf("-- Backfill"));
        for (String statement : backfill.split(";\\s*\\n")) if (!statement.isBlank()) jdbc.execute(statement.replace("WHERE c.value->>'taskId' IS NOT NULL",
                "WHERE j.id = '" + run.jobId() + "' AND c.value->>'taskId' IS NOT NULL").replace("WHERE (c.value->>'taskId')::uuid = t.id",
                "WHERE t.analysis_job_id = '" + run.jobId() + "' AND (c.value->>'taskId')::uuid = t.id"));
        var after = tasks(token, run.meetingId()).get("tasks").get(0);
        assertThat(after.get("taskId").asText()).isEqualTo(before.get("taskId").asText());
        assertThat(after.get("aiSuggestion")).isEqualTo(before.get("aiSuggestion"));
        assertThat(after.get("evidence")).isEqualTo(before.get("evidence"));
        assertThat(codes(after)).isEqualTo(codes(before));
    }

    private List<String> codes(JsonNode task) { var codes = new ArrayList<String>(); task.get("warnings").forEach(w -> codes.add(w.get("code").asText())); return codes; }
    private JsonNode read(String value) throws Exception { return json.readTree(value); }
}
