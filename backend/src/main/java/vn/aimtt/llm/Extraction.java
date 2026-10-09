package vn.aimtt.llm;

import java.time.*;
import java.util.*;

public final class Extraction {
    private Extraction() {}
    public record Source(UUID segmentId, int sequence, String text) {}
    public record Evidence(String segment_id, String field) {}
    public record Event(String event_type, String task_ref, int sequence, String task_name, String assignee_raw,
                        String deadline_raw, String priority, List<String> changed_fields, List<Evidence> evidence_refs, List<String> ambiguities) {}
    public record Quote(UUID segmentId, int sequence, String field, String quote) {}
    public record Candidate(UUID taskId, String taskRef, String taskName, String assigneeRaw, String deadlineRaw,
                            String priority, String dueLocal, String dueAt, String timezone, List<Quote> evidence,
                            List<String> warnings, boolean needsConfirmation, String reviewStatus) {}
    public record Result(List<Event> events, List<Candidate> candidates, List<String> warnings,
                         Long inputTokens, Long outputTokens, long latencyMs) {}
}
