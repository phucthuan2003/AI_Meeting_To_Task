package vn.aimtt.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import vn.aimtt.MeetingToTaskApplication;
import vn.aimtt.llm.LlmHttpTransport;

/**
 * End-to-end test server: the real application with only the LLM HTTP transport replaced by a deterministic,
 * rule-based stand-in (no API key, no network). Used by tools/e2e to drive the real extension against real
 * PostgreSQL and the local Trello double. Test classpath only — never packaged.
 */
public class E2eServer {
    public static void main(String[] args) {
        new SpringApplicationBuilder(MeetingToTaskApplication.class, FakeLlm.class).run(args);
    }

    @Configuration
    public static class FakeLlm {
        // "Nam: Mai hoàn thành màn hình đăng nhập trước thứ Sáu nhé." → CREATE(assignee Mai, deadline "trước thứ Sáu")
        static final Pattern TASK = Pattern.compile("^(?:[^:]{1,40}:\\s*)?(?<who>[A-ZĐ][\\p{L}]*)\\s+(?<what>.+?)(?:\\s+(?<deadline>trước\\s.+?|vào\\s.+?))?\\s+nhé\\.?$");
        @Bean @Primary
        LlmHttpTransport fakeTransport(ObjectMapper json) {
            return (request, keepLease) -> {
                try {
                    Thread.sleep(800); // visible "Đang gọi AI" stage in the panel
                    var payload = json.readTree(body(request));
                    var data = json.readTree(payload.get("input").get(1).get("content").asText());
                    var events = new ArrayList<Map<String, Object>>();
                    int n = 0;
                    for (var seg : data.get("segments")) {
                        var m = TASK.matcher(seg.get("text").asText().strip());
                        if (!m.matches()) continue;
                        String id = seg.get("segmentId").asText(), what = m.group("what"), deadline = m.group("deadline");
                        var refs = new ArrayList<Map<String, String>>(List.of(Map.of("segment_id", id, "field", "TASK"), Map.of("segment_id", id, "field", "ASSIGNEE")));
                        if (deadline != null) refs.add(Map.of("segment_id", id, "field", "DEADLINE"));
                        var event = new LinkedHashMap<String, Object>();
                        event.put("event_type", "CREATE"); event.put("task_ref", "t" + (++n)); event.put("sequence", seg.get("sequence").asInt());
                        event.put("task_name", Character.toUpperCase(what.charAt(0)) + what.substring(1)); event.put("assignee_raw", m.group("who"));
                        event.put("deadline_raw", deadline); event.put("priority", null); event.put("changed_fields", List.of("TASK"));
                        event.put("evidence_refs", refs); event.put("ambiguities", List.of());
                        events.add(event);
                    }
                    String output = json.writeValueAsString(Map.of("events", events));
                    return new LlmHttpTransport.Response(200, json.writeValueAsBytes(Map.of("status", "completed", "usage", Map.of("input_tokens", 321, "output_tokens", 45),
                            "output", List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", output)))))));
                } catch (Exception e) { return new LlmHttpTransport.Response(500, new byte[0]); }
            };
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
    }
}
