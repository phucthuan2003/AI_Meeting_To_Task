package vn.aimtt.llm;

import java.time.*;
import java.util.*;
import vn.aimtt.job.AnalysisJob;

public final class LlmFixtures {
    private LlmFixtures() {}
    public static final UUID SEGMENT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final String TEXT = "Nam: Mai hoàn thành màn hình đăng nhập trước 17:00 ngày 12/10/2026.";
    public static LlmProperties properties() {
        return new LlmProperties(new LlmProperties.Credentials("synthetic-openai-key", "gpt-4.1-mini-2025-04-14"),
                new LlmProperties.Credentials("synthetic-gemini-key", "gemini-2.5-flash"), Duration.ofSeconds(5), 49152, 4096);
    }
    public static AnalysisJob job(String provider) {
        Instant now = Instant.now();
        return new AnalysisJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Planning", LocalDate.of(2026,10,9),
                "Asia/Ho_Chi_Minh", provider, properties().credentials(provider).model(), "llm-v1", ExtractionPrompt.versionFor(provider), ExtractionPrompt.SCHEMA_VERSION,
                65536, 4096, "fixture-key", AnalysisJob.Status.PROCESSING, "READY_FOR_PROVIDER", 0, 1, UUID.randomUUID(), now.plusSeconds(30), 1, 3, 0, now, null, false, now, now, null);
    }
    public static String event(String type, String ref, int sequence, UUID segment, String changed, String name, String assignee, String deadline) {
        return """
                {"event_type":"%s","task_ref":"%s","sequence":%d,"task_name":%s,"assignee_raw":%s,"deadline_raw":%s,"priority":null,
                "changed_fields":%s,"evidence_refs":[{"segment_id":"%s","field":"TASK"},{"segment_id":"%s","field":"ASSIGNEE"},{"segment_id":"%s","field":"DEADLINE"}],"ambiguities":[]}
                """.formatted(type,ref,sequence,name,assignee,deadline,changed,segment,segment,segment);
    }
    public static String event() { return event("CREATE", "task-1", 0, SEGMENT, "[\"TASK\"]", "\"Hoàn thành màn hình đăng nhập\"", "\"Mai\"", "\"17:00 ngày 12/10/2026\""); }
    public static String output() { return "{\"events\":[" + event() + "]}"; }
}
