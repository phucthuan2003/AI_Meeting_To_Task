package vn.aimtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.aimtt.job.*;
import vn.aimtt.llm.*;
import vn.aimtt.sync.SyncRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared fixtures for full-flow tests: real PostgreSQL, mocked LLM transport, in-JVM fake Trello over HTTP. */
abstract class IntegrationSupport {
    static final FakeTrello TRELLO = new FakeTrello();
    static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.properties(registry);
        registry.add("app.llm.openai.api-key", () -> "synthetic-openai-key");
        registry.add("app.sync.worker-enabled", () -> false);
        registry.add("app.trello.api-base-url", () -> TRELLO.base() + "/1");
        registry.add("app.trello.token-url", () -> TRELLO.base() + "/oauth/token");
        registry.add("app.trello.authorize-url", () -> TRELLO.base() + "/authorize");
        registry.add("app.trello.callback-url", () -> "http://127.0.0.1:8080/api/v1/trello/oauth/callback");
        registry.add("app.trello.client-id", () -> "client-id-test");
        registry.add("app.trello.client-secret", () -> "client-secret-test");
        registry.add("app.trello.api-key", () -> "apikeytest");
        registry.add("app.trello.allow-local-http", () -> true);
        registry.add("app.trello.token-encryption-key", () -> KEY);
        registry.add("app.trello.request-timeout", () -> "2s");
        registry.add("app.sync.retry-base-delay", () -> "0s");
        registry.add("app.sync.reconcile-delay", () -> "0s");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalysisJobStore jobs;
    @Autowired AnalysisJobRunner analysis;
    @Autowired SyncRunner syncRunner;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean LlmHttpTransport transport;

    String user() throws Exception {
        String id = UUID.randomUUID().toString();
        String credentials = json.writeValueAsString(Map.of("email", id + "@example.test", "password", "synthetic-password-123"));
        mvc.perform(post("/api/v1/auth/register").with(r -> { r.setRemoteAddr(id); return r; }).contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isCreated());
        return read(mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(id); return r; }).contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
    ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request);
    }
    JsonNode ok(ResultActions actions) throws Exception { return read(actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()); }
    JsonNode body(ResultActions actions) throws Exception { return read(actions.andReturn().getResponse().getContentAsString()); }
    JsonNode read(String value) throws Exception { return value.isEmpty() ? json.nullNode() : json.readTree(value); }

    record Meeting(String id, UUID jobId) {}
    /** Creates a meeting, runs one analysis whose events are produced from the real segment IDs, and returns it COMPLETED. */
    Meeting analysed(String token, String text, String meetingDate, Function<List<Extraction.Source>, String> events) throws Exception {
        var input = new HashMap<String, Object>(Map.of("transcriptText", text, "timezone", "Asia/Ho_Chi_Minh", "title", "Sprint Planning"));
        if (meetingDate != null) input.put("meetingDate", meetingDate);
        var meeting = ok(call(token, post("/api/v1/meetings"), input));
        var job = ok(call(token, post("/api/v1/meetings/{id}/analysis-jobs", meeting.get("meetingId").asText()),
                Map.of("expectedInputVersion", meeting.get("inputVersion").asLong(), "providerId", "openai", "processingPolicyId", "llm-v1")));
        UUID jobId = UUID.fromString(job.get("jobId").asText());
        String output = "{\"events\":[" + events.apply(jobs.analysisSource(jobs.get(jobId))) + "]}";
        when(transport.send(any(), any())).thenReturn(new LlmHttpTransport.Response(200, json.writeValueAsBytes(
                Map.of("status", "completed", "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", output))))))));
        for (int i = 0; i < 20 && jobs.get(jobId).status().active(); i++) analysis.runOnce();
        assertThat(jobs.get(jobId).status()).isEqualTo(AnalysisJob.Status.COMPLETED);
        return new Meeting(meeting.get("meetingId").asText(), jobId);
    }
    /** CREATE event with TASK + optional ASSIGNEE/DEADLINE evidence from one segment. */
    static String create(String ref, Extraction.Source segment, String task, String assignee, String deadline) {
        var refs = new ArrayList<String>(); refs.add("{\"segment_id\":\"" + segment.segmentId() + "\",\"field\":\"TASK\"}");
        if (assignee != null) refs.add("{\"segment_id\":\"" + segment.segmentId() + "\",\"field\":\"ASSIGNEE\"}");
        if (deadline != null) refs.add("{\"segment_id\":\"" + segment.segmentId() + "\",\"field\":\"DEADLINE\"}");
        return "{\"event_type\":\"CREATE\",\"task_ref\":\"" + ref + "\",\"sequence\":" + segment.sequence() + ",\"task_name\":\"" + task + "\",\"assignee_raw\":"
                + (assignee == null ? "null" : "\"" + assignee + "\"") + ",\"deadline_raw\":" + (deadline == null ? "null" : "\"" + deadline + "\"")
                + ",\"priority\":null,\"changed_fields\":[\"TASK\"],\"evidence_refs\":[" + String.join(",", refs) + "],\"ambiguities\":[]}";
    }
    JsonNode tasks(String token, String meetingId) throws Exception { return ok(call(token, get("/api/v1/meetings/{id}/tasks", meetingId), null)); }
    JsonNode task(String token, String meetingId, String name) throws Exception {
        for (var t : tasks(token, meetingId).get("tasks")) if (t.get("taskName").asText().equals(name)) return t;
        throw new AssertionError("task not found: " + name);
    }
    String connectToken(String token) throws Exception {
        return ok(call(token, post("/api/v1/trello/connections/token"), Map.of("token", "validtoken000000000000000000000001"))).get("connectionId").asText();
    }
    JsonNode destination(String token, String meetingId, String connection, long expected) throws Exception {
        return ok(call(token, put("/api/v1/meetings/{id}/destination", meetingId), Map.of("connectionId", connection, "boardId", FakeTrello.BOARD, "listId", FakeTrello.LIST, "expectedVersion", expected)));
    }
    /** Confirms deadline and member decisions so the task is ready for approval. */
    JsonNode ready(String token, JsonNode task, String memberId) throws Exception {
        var body = new HashMap<String, Object>(Map.of("expectedVersion", task.get("version").asLong(), "deadlineDecision", "RESOLVED",
                "dueLocal", "2026-10-16T17:00", "timezone", "Asia/Ho_Chi_Minh"));
        if (memberId == null) body.put("memberDecision", "NONE_SELECTED"); else { body.put("memberDecision", "RESOLVED"); body.put("trelloMemberId", memberId); }
        return ok(call(token, patch("/api/v1/tasks/{id}", task.get("taskId").asText()), body));
    }
    JsonNode approve(String token, String meetingId, long destinationVersion, List<JsonNode> tasks, String key) throws Exception {
        var list = tasks.stream().map(t -> Map.of("taskId", t.get("taskId").asText(), "expectedVersion", t.get("version").asLong())).toList();
        return ok(call(token, post("/api/v1/meetings/{id}/approve-and-sync", meetingId).header("Idempotency-Key", key),
                Map.of("expectedDestinationVersion", destinationVersion, "tasks", list)));
    }
    void drainSync() { for (int i = 0; i < 30 && syncRunner.runOnce(); i++) { } }
    JsonNode syncJob(String token, String jobId) throws Exception { return ok(call(token, get("/api/v1/sync-jobs/{id}", jobId), null)); }
}
