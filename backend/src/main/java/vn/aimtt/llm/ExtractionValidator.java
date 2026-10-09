package vn.aimtt.llm;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import vn.aimtt.job.*;
import static vn.aimtt.llm.Extraction.*;

@Component
public class ExtractionValidator {
    private final ObjectMapper json;
    private static final Set<String> FIELDS = Set.of("TASK", "ASSIGNEE", "DEADLINE", "PRIORITY");
    private static final Set<String> EVENT_KEYS = Set.of("event_type", "task_ref", "sequence", "task_name", "assignee_raw", "deadline_raw", "priority", "changed_fields", "evidence_refs", "ambiguities");
    public ExtractionValidator(ObjectMapper mapper) {
        json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private static JobFailure invalid() { return new JobFailure("PROVIDER_RESPONSE_INVALID", false); }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static void keys(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject()); var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add); require(actual.equals(fields));
    }
    private static String text(JsonNode node, int max, boolean nullable) {
        if (nullable && node.isNull()) return null;
        require(node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max); return node.asText();
    }
    private static List<String> strings(JsonNode node, int maxItems, int maxLength) {
        require(node.isArray() && node.size() <= maxItems); var values = new ArrayList<String>();
        for (var item : node) values.add(text(item, maxLength, false)); return List.copyOf(values);
    }
    /** An event that passed schema/source/evidence checks, with server-side quotes from its segments. */
    public record Parsed(Event event, List<Quote> quotes) {}

    public Result validate(AnalysisJob job, List<Source> source, LlmProvider.Output output) {
        return reconcile(job, parse(source, output, Set.of()), output.inputTokens(), output.outputTokens(), output.latencyMs());
    }

    /**
     * Validates one model output against the segments it was given. For chunked analysis, {@code knownRefs} are
     * task refs created by earlier chunks: UPDATE/CANCEL may target them, a second CREATE may not.
     */
    public List<Parsed> parse(List<Source> source, LlmProvider.Output output, Set<String> knownRefs) {
        JsonNode root;
        try { root = json.readTree(output.json()); } catch (Exception failure) { throw invalid(); }
        keys(root, Set.of("events")); var rows = root.get("events"); require(rows.isArray() && rows.size() <= 100);
        Map<UUID, Source> byId = new HashMap<>(); Set<Integer> sequences = new HashSet<>();
        for (var segment : source) { byId.put(segment.segmentId(), segment); sequences.add(segment.sequence()); }
        var parsed = new ArrayList<Parsed>(); var created = new HashSet<String>();
        int previous = -1;
        for (var row : rows) {
            keys(row, EVENT_KEYS);
            String type = text(row.get("event_type"), 6, false); require(Set.of("CREATE", "UPDATE", "CANCEL").contains(type));
            String ref = text(row.get("task_ref"), 80, false);
            require(row.get("sequence").isIntegralNumber() && row.get("sequence").canConvertToInt()); int sequence = row.get("sequence").intValue();
            require(sequence >= previous && sequences.contains(sequence)); previous = sequence;
            String task = text(row.get("task_name"), 255, true), assignee = text(row.get("assignee_raw"), 255, true), deadline = text(row.get("deadline_raw"), 255, true), priority = text(row.get("priority"), 6, true);
            require(priority == null || Set.of("LOW", "MEDIUM", "HIGH").contains(priority));
            var changed = strings(row.get("changed_fields"), 4, 8);
            require(new HashSet<>(changed).size() == changed.size() && FIELDS.containsAll(changed));
            require(!type.equals("CREATE") || (task != null && changed.contains("TASK")));
            require(!type.equals("UPDATE") || !changed.isEmpty());
            require(!type.equals("UPDATE") || !changed.contains("TASK") || task != null);
            require(!type.equals("CANCEL") || changed.isEmpty());
            var ambiguity = strings(row.get("ambiguities"), 10, 255);
            var evidence = row.get("evidence_refs"); require(evidence.isArray() && evidence.size() > 0 && evidence.size() <= 12);
            var refs = new ArrayList<Evidence>(); var quotes = new ArrayList<Quote>(); var supported = new HashSet<String>(); boolean atSequence = false;
            for (var item : evidence) {
                keys(item, Set.of("segment_id", "field")); String field = text(item.get("field"), 8, false); require(FIELDS.contains(field));
                String id = text(item.get("segment_id"), 36, false); UUID uuid;
                try { uuid = UUID.fromString(id); } catch (Exception failure) { throw invalid(); }
                Source segment = byId.get(uuid); require(segment != null && segment.sequence() <= sequence);
                atSequence |= segment.sequence() == sequence; supported.add(field);
                refs.add(new Evidence(id, field)); quotes.add(new Quote(uuid, segment.sequence(), field, segment.text()));
            }
            require(atSequence);
            var needed = new HashSet<>(changed);
            if (type.equals("CREATE") || type.equals("CANCEL") || task != null) needed.add("TASK");
            if (assignee != null) needed.add("ASSIGNEE"); if (deadline != null) needed.add("DEADLINE"); if (priority != null) needed.add("PRIORITY");
            require(supported.containsAll(needed));
            require(assignee == null || quotes.stream().anyMatch(q -> q.field().equals("ASSIGNEE") && q.quote().contains(assignee)));
            require(deadline == null || quotes.stream().anyMatch(q -> q.field().equals("DEADLINE") && q.quote().contains(deadline)));
            if (type.equals("CREATE")) require(!knownRefs.contains(ref) && created.add(ref));
            parsed.add(new Parsed(new Event(type, ref, sequence, task, assignee, deadline, priority, changed, refs, ambiguity), List.copyOf(quotes)));
        }
        return List.copyOf(parsed);
    }

    /** Applies CREATE/UPDATE/CANCEL in sequence order and builds candidates (also the final check after consolidation). */
    public Result reconcile(AnalysisJob job, List<Parsed> parsed, Long inputTokens, Long outputTokens, long latencyMs) {
        var events = new ArrayList<Event>(); var tasks = new LinkedHashMap<String, Mutable>(); var warnings = new ArrayList<String>();
        for (var item : parsed) {
            var event = item.event(); var quotes = item.quotes();
            String type = event.event_type(), ref = event.task_ref(), task = event.task_name(), assignee = event.assignee_raw(),
                    deadline = event.deadline_raw(), priority = event.priority();
            var changed = event.changed_fields(); var ambiguity = event.ambiguities();
            events.add(event);
            if (type.equals("CREATE")) {
                require(!tasks.containsKey(ref)); tasks.put(ref, new Mutable(task, assignee, deadline, priority, quotes, ambiguity));
            } else {
                var target = tasks.get(ref);
                if (target == null || target.cancelled) { warnings.add("UNRESOLVED_" + type + ": " + ref); continue; }
                if (type.equals("CANCEL")) target.cancelled = true;
                else {
                    if (changed.contains("TASK")) target.task = task;
                    if (changed.contains("ASSIGNEE")) target.assignee = assignee;
                    if (changed.contains("DEADLINE")) target.deadline = deadline;
                    if (changed.contains("PRIORITY")) target.priority = priority;
                }
                target.evidence.addAll(quotes); target.warnings.addAll(ambiguity);
            }
        }
        var candidates = new ArrayList<Candidate>();
        for (var entry : tasks.entrySet()) {
            var task = entry.getValue(); if (task.cancelled) continue;
            var issues = new LinkedHashSet<>(task.warnings); issues.add("AI_REVIEW_REQUIRED");
            if (task.assignee == null) issues.add("ASSIGNEE_MISSING"); else issues.add("ASSIGNEE_NOT_RESOLVED_TO_MEMBER");
            String local = null, instant = null;
            if (task.deadline == null) issues.add("DEADLINE_MISSING");
            else {
                issues.add("DEADLINE_NEEDS_CONFIRMATION");
                var explicit = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2}).*?(\\d{1,2})/(\\d{1,2})/(\\d{4})(?!\\d)").matcher(task.deadline);
                if (explicit.find() && job.timezone() != null) {
                    try {
                        var time = LocalDateTime.of(Integer.parseInt(explicit.group(5)), Integer.parseInt(explicit.group(4)), Integer.parseInt(explicit.group(3)), Integer.parseInt(explicit.group(1)), Integer.parseInt(explicit.group(2)));
                        var zone = ZoneId.of(job.timezone()); var offsets = zone.getRules().getValidOffsets(time);
                        if (offsets.size() == 1) { local = time.toString(); instant = time.atOffset(offsets.get(0)).toInstant().toString(); }
                        else issues.add("DEADLINE_AMBIGUOUS");
                    } catch (DateTimeException failure) { issues.add("DEADLINE_AMBIGUOUS"); }
                } else issues.add("DEADLINE_AMBIGUOUS");
            }
            candidates.add(new Candidate(UUID.randomUUID(), entry.getKey(), task.task, task.assignee, task.deadline, task.priority,
                    local, instant, job.timezone(), List.copyOf(task.evidence), List.copyOf(issues), true, "PENDING_REVIEW"));
        }
        return new Result(List.copyOf(events), List.copyOf(candidates), List.copyOf(warnings), inputTokens, outputTokens, latencyMs);
    }
    private static class Mutable {
        String task, assignee, deadline, priority; boolean cancelled;
        final List<Quote> evidence; final List<String> warnings;
        Mutable(String task, String assignee, String deadline, String priority, List<Quote> evidence, List<String> warnings) {
            this.task = task; this.assignee = assignee; this.deadline = deadline; this.priority = priority;
            this.evidence = new ArrayList<>(evidence); this.warnings = new ArrayList<>(warnings);
        }
    }
}
