package vn.aimtt;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.aimtt.job.AnalysisJob;
import vn.aimtt.llm.LlmHttpTransport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** SDS §14.6 and AT11–AT13: corrections/cancellations across parts, overlap dedupe, partial failure and resume. */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkingIntegrationTest extends IntegrationSupport {
    @DynamicPropertySource static void chunking(DynamicPropertyRegistry registry) {
        registry.add("app.chunking.enabled", () -> true);
        registry.add("app.chunking.input-bytes", () -> 3000);
        registry.add("app.chunking.known-tasks-bytes", () -> 600);
        registry.add("app.chunking.overlap-segments", () -> 2);
        registry.add("app.llm.max-input-bytes", () -> 4096);
    }

    static String transcript() {
        var lines = new ArrayList<String>();
        lines.add("Nam: Long làm màn hình login nhé.");
        lines.add("Nam: Viết tài liệu API cho team mobile.");
        for (int i = 0; i < 40; i++) lines.add("Nam: Cập nhật tình hình chung số " + i + ", chưa có quyết định mới về phạm vi sprint này.");
        lines.add("Nam: À không, Mai làm login.");
        lines.add("Nam: Bỏ việc viết tài liệu, sprint này chưa làm.");
        return String.join("\n", lines);
    }

    /** A rule-based stand-in for the model that only sees what the backend sends for this part. */
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger failPart = new AtomicInteger(Integer.MIN_VALUE);
    LlmHttpTransport.Response answer(HttpRequest request) throws Exception {
        var payload = json.readTree(body(request));
        var data = json.readTree(payload.get("input").get(1).get("content").asText());
        requests.add(data);
        int part = data.path("part").path("index").asInt(-1);
        if (part == failPart.get()) { failPart.set(Integer.MIN_VALUE); return new LlmHttpTransport.Response(500, "{}".getBytes()); }
        int overlap = data.path("part").path("overlap_until_sequence").asInt(-1);
        var known = new HashMap<String, String>();
        data.path("known_tasks").forEach(t -> known.put(t.get("task_name").asText(), t.get("task_ref").asText()));
        var events = new ArrayList<String>();
        for (var seg : data.get("segments")) {
            String id = seg.get("segmentId").asText(), text = seg.get("text").asText(); int seq = seg.get("sequence").asInt();
            // A naive model re-emits the docs task from the overlap with a new ref: the backend must dedupe it.
            boolean inOverlap = seq <= overlap;
            if (inOverlap && !text.contains("Viết tài liệu")) continue;
            if (text.contains("Long làm màn hình login")) events.add(create("login", id, seq, "Làm màn hình login", "Long"));
            if (text.contains("Viết tài liệu API")) events.add(create(inOverlap ? "docs-again" : "docs", id, seq, "Viết tài liệu API", null));
            if (text.contains("Mai làm login")) events.add("{\"event_type\":\"UPDATE\",\"task_ref\":\"" + known.getOrDefault("Làm màn hình login", "login") + "\",\"sequence\":" + seq
                    + ",\"task_name\":null,\"assignee_raw\":\"Mai\",\"deadline_raw\":null,\"priority\":null,\"changed_fields\":[\"ASSIGNEE\"],\"evidence_refs\":[{\"segment_id\":\"" + id
                    + "\",\"field\":\"ASSIGNEE\"}],\"ambiguities\":[]}");
            if (text.contains("Bỏ việc viết tài liệu")) events.add("{\"event_type\":\"CANCEL\",\"task_ref\":\"" + known.getOrDefault("Viết tài liệu API", "docs") + "\",\"sequence\":" + seq
                    + ",\"task_name\":null,\"assignee_raw\":null,\"deadline_raw\":null,\"priority\":null,\"changed_fields\":[],\"evidence_refs\":[{\"segment_id\":\"" + id
                    + "\",\"field\":\"TASK\"}],\"ambiguities\":[]}");
        }
        String output = "{\"events\":[" + String.join(",", events) + "]}";
        return new LlmHttpTransport.Response(200, json.writeValueAsBytes(Map.of("status", "completed", "usage", Map.of("input_tokens", 100, "output_tokens", 10),
                "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", output)))))));
    }
    static String create(String ref, String segment, int seq, String name, String assignee) {
        return "{\"event_type\":\"CREATE\",\"task_ref\":\"" + ref + "\",\"sequence\":" + seq + ",\"task_name\":\"" + name + "\",\"assignee_raw\":"
                + (assignee == null ? "null" : "\"" + assignee + "\"") + ",\"deadline_raw\":null,\"priority\":null,\"changed_fields\":[\"TASK\"],\"evidence_refs\":[{\"segment_id\":\""
                + segment + "\",\"field\":\"TASK\"}" + (assignee == null ? "" : ",{\"segment_id\":\"" + segment + "\",\"field\":\"ASSIGNEE\"}") + "],\"ambiguities\":[]}";
    }
    static String body(HttpRequest request) throws Exception {
        var out = new java.io.ByteArrayOutputStream(); var done = new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] a = new byte[b.remaining()]; b.get(a); out.writeBytes(a); }
            public void onError(Throwable t) { done.completeExceptionally(t); }
            public void onComplete() { done.complete(null); }
        });
        done.get(5, TimeUnit.SECONDS);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    UUID start(String token, String meetingId, long version) throws Exception {
        when(transport.send(any(), any())).thenAnswer(invocation -> answer(invocation.getArgument(0)));
        var job = ok(call(token, post("/api/v1/meetings/{id}/analysis-jobs", meetingId), Map.of("expectedInputVersion", version, "providerId", "openai", "processingPolicyId", "llm-v1")));
        return UUID.fromString(job.get("jobId").asText());
    }
    void drain(UUID job) { for (int i = 0; i < 40 && jobs.get(job).status().active(); i++) analysis.runOnce(); }

    @Test void correctionAndCancellationInLaterPartsProduceOneCorrectTask() throws Exception {   // AT11, AT12
        String token = user();
        var meeting = ok(call(token, post("/api/v1/meetings"), Map.of("transcriptText", transcript(), "timezone", "Asia/Ho_Chi_Minh")));
        UUID job = start(token, meeting.get("meetingId").asText(), meeting.get("inputVersion").asLong());
        drain(job);
        var done = jobs.get(job);
        assertThat(done.status()).isEqualTo(AnalysisJob.Status.COMPLETED);
        assertThat(done.totalChunks()).isGreaterThanOrEqualTo(3);
        assertThat(done.completedChunks()).isEqualTo(done.totalChunks());
        assertThat(requests).hasSize(done.totalChunks());
        // Every part after the first carries the known tasks and the overlap boundary.
        assertThat(requests.get(1).path("part").path("overlap_until_sequence").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(requests.get(requests.size() - 1).path("known_tasks").toString()).contains("Làm màn hình login");
        var tasks = tasks(token, meeting.get("meetingId").asText()).get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("taskName").asText()).isEqualTo("Làm màn hình login");
        assertThat(tasks.get(0).get("assigneeRaw").asText()).isEqualTo("Mai");
        assertThat(tasks.get(0).get("evidence").toString()).contains("Long làm màn hình login", "Mai làm login");
        assertThat(jdbc.queryForObject("select count(*) from processing_logs where job_id = ?", Integer.class, job)).isEqualTo(done.totalChunks());
        assertThat(jdbc.queryForObject("select count(*) from processing_logs where job_id = ? and status = 'OK' and input_tokens = 100", Integer.class, job)).isEqualTo(done.totalChunks());
        var result = json.readTree(jobs.result(job).orElseThrow());
        assertThat(result.get("inputTokens").asLong()).isEqualTo(100L * done.totalChunks());
    }

    @Test void failedPartIsPartialFailedPublishesNothingAndRetryResumesFromIt() throws Exception {   // AT13
        String token = user();
        var meeting = ok(call(token, post("/api/v1/meetings"), Map.of("transcriptText", transcript(), "timezone", "Asia/Ho_Chi_Minh")));
        failPart.set(1);
        UUID job = start(token, meeting.get("meetingId").asText(), meeting.get("inputVersion").asLong());
        drain(job);
        var failed = jobs.get(job);
        assertThat(failed.status()).isEqualTo(AnalysisJob.Status.PARTIAL_FAILED);
        assertThat(failed.errorRetryable()).isTrue();
        assertThat(failed.completedChunks()).isEqualTo(1);
        assertThat(jobs.result(job)).isEmpty();
        var list = tasks(token, meeting.get("meetingId").asText());
        assertThat(list.get("analysis").get("status").asText()).isEqualTo("PARTIAL_FAILED");
        assertThat(list.get("tasks")).isEmpty();
        int calls = requests.size();
        ok(call(token, post("/api/v1/jobs/{id}/retry", job.toString()), null));
        drain(job);
        assertThat(jobs.get(job).status()).isEqualTo(AnalysisJob.Status.COMPLETED);
        // Part 0 was checkpointed and is not sent again.
        assertThat(requests.subList(calls, requests.size()).stream().map(r -> r.path("part").path("index").asInt())).doesNotContain(0);
        assertThat(tasks(token, meeting.get("meetingId").asText()).get("tasks")).hasSize(1);
    }

    @Test void shortTranscriptStillUsesSingleCall() throws Exception {   // AT01 with chunking on
        String token = user();
        var meeting = ok(call(token, post("/api/v1/meetings"), Map.of("transcriptText", "Nam: Long làm màn hình login nhé.", "timezone", "Asia/Ho_Chi_Minh")));
        UUID job = start(token, meeting.get("meetingId").asText(), meeting.get("inputVersion").asLong());
        drain(job);
        assertThat(jobs.get(job).status()).as(String.valueOf(jobs.get(job).errorCode())).isEqualTo(AnalysisJob.Status.COMPLETED);
        assertThat(jobs.get(job).totalChunks()).isEqualTo(1);
        assertThat(requests.get(0).has("part")).isFalse();
        assertThat(tasks(token, meeting.get("meetingId").asText()).get("tasks")).hasSize(1);
    }
}
