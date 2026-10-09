package vn.aimtt.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import vn.aimtt.llm.*;

/**
 * Runs extraction for a prepared job (SDS §5.6–§5.9). Single call when the transcript fits the pinned budget;
 * otherwise, only if chunking is enabled, ordered parts with bounded overlap, checkpointed per part, consolidated
 * through the same CREATE/UPDATE/CANCEL reconciliation and validated again before anything is published.
 */
@Component
public class AnalysisPipeline {
    private static final TypeReference<List<ExtractionValidator.Parsed>> PARSED = new TypeReference<>() {};
    private final List<LlmProvider> providers;
    private final LlmProperties properties;
    private final ObjectMapper json;
    private final ExtractionValidator validator;
    private final ChunkingProperties chunking;
    private final AnalysisJobStore store;

    public AnalysisPipeline() { this(List.of(), LlmProperties.defaults(), new ObjectMapper(), new ExtractionValidator(new ObjectMapper())); }
    public AnalysisPipeline(List<LlmProvider> providers, LlmProperties properties, ObjectMapper json, ExtractionValidator validator) {
        this(providers, properties, json, validator, ChunkingProperties.disabled(), null);
    }
    public AnalysisPipeline(List<LlmProvider> providers, LlmProperties properties, ObjectMapper json, ExtractionValidator validator,
                            ChunkingProperties chunking, AnalysisJobStore store) {
        this.providers = providers; this.properties = properties; this.json = json; this.validator = validator; this.chunking = chunking; this.store = store;
    }
    @Autowired
    public AnalysisPipeline(List<LlmProvider> providers, LlmProperties properties, ObjectMapper json, ExtractionValidator validator,
                            org.springframework.beans.factory.ObjectProvider<ChunkingProperties> chunking, org.springframework.beans.factory.ObjectProvider<AnalysisJobStore> store) {
        this(providers, properties, json, validator, chunking.getIfAvailable(ChunkingProperties::disabled), store.getIfAvailable());
    }

    public Extraction.Result processPrepared(AnalysisJob job, Supplier<List<Extraction.Source>> loadSource, BooleanSupplier keepLease) {
        return processPrepared(job, null, loadSource, keepLease);
    }

    public Extraction.Result processPrepared(AnalysisJob job, AnalysisJob.Lease lease, Supplier<List<Extraction.Source>> loadSource, BooleanSupplier keepLease) {
        if (!AnalysisPolicy.LLM_ID.equals(job.policyId())) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        if (!ExtractionPrompt.versionFor(job.providerId()).equals(job.promptVersion()) || !ExtractionPrompt.SCHEMA_VERSION.equals(job.schemaVersion())) throw new JobFailure("POLICY_VERSION_UNAVAILABLE", false);
        var provider = providers.stream().filter(p -> p.id().equals(job.providerId())).findFirst().orElseThrow(() -> new JobFailure("PROVIDER_NOT_CONFIGURED", false));
        if (!properties.credentials(job.providerId()).configured()) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        var source = loadSource.get(); if (source.isEmpty()) throw new JobFailure("SOURCE_UNAVAILABLE", false);
        for (int i = 0; i < source.size(); i++) if (source.get(i).sequence() != i) throw new JobFailure("SOURCE_UNAVAILABLE", false);
        try {
            JsonNode schema;
            try (var stream = new ClassPathResource("llm/meeting-events-v1.schema.json").getInputStream()) { schema = json.readTree(stream); }
            long envelope = ExtractionPrompt.chunked().getBytes(StandardCharsets.UTF_8).length + schema.toString().getBytes(StandardCharsets.UTF_8).length + 2048L;
            String input = data(job, source, null, List.of());
            long bytes = input.getBytes(StandardCharsets.UTF_8).length;
            boolean fits = bytes <= properties.maxInputBytes() && bytes + envelope + job.reservedTokens() <= job.contextTokens();
            boolean resumingChunks = store != null && lease != null && !store.chunks(job.id()).isEmpty();
            if (fits && !resumingChunks) {
                if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                var output = call(job, 0, () -> provider.extract(job, input, schema, keepLease));
                if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                return validator.validate(job, source, output);
            }
            if (!chunking.enabled() || store == null || lease == null) throw new JobFailure("TRANSCRIPT_OVER_BUDGET", false);
            return chunked(job, lease, source, schema, envelope, provider, keepLease);
        } catch (JobFailure failure) { throw failure; }
        catch (Exception failure) { throw new JobFailure("INTERNAL_JOB_ERROR", true); }
    }

    private LlmProvider.Output call(AnalysisJob job, int chunk, Supplier<LlmProvider.Output> request) {
        try {
            var output = request.get();
            if (store != null) store.log(job, chunk, output.inputTokens(), output.outputTokens(), output.latencyMs(), "OK", null);
            return output;
        } catch (JobFailure failure) {
            if (store != null) store.log(job, chunk, null, null, null, "FAILED", failure.code());
            throw failure;
        }
    }

    private Extraction.Result chunked(AnalysisJob job, AnalysisJob.Lease lease, List<Extraction.Source> source, JsonNode schema, long envelope,
                                      LlmProvider provider, BooleanSupplier keepLease) throws Exception {
        var rows = store.chunks(job.id());
        if (rows.isEmpty()) {
            var plan = plan(source);
            if (plan.size() > chunking.maxChunks()) throw new JobFailure("CHUNK_LIMIT_EXCEEDED", false);
            if (!store.savePlan(lease, plan)) throw new JobFailure("LEASE_LOST", false);
            rows = store.chunks(job.id());
        }
        var merged = new ArrayList<ExtractionValidator.Parsed>();
        for (var row : rows) {
            if ("COMPLETED".equals(row.status())) { merged.addAll(json.readValue(row.outputJson(), PARSED)); continue; }
            if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
            var part = source.subList(row.firstSequence(), row.lastSequence() + 1);
            var state = validator.reconcile(job, merged, null, null, 0);
            var known = state.candidates().stream().map(c -> {
                var m = new LinkedHashMap<String, Object>(); m.put("task_ref", c.taskRef()); m.put("task_name", c.taskName());
                m.put("assignee_raw", c.assigneeRaw()); m.put("deadline_raw", c.deadlineRaw()); return (Map<String, Object>) m;
            }).toList();
            String data = data(job, part, Map.of("index", row.index(), "total", rows.size(), "overlap_until_sequence", row.overlapUntil()), trim(known));
            long bytes = data.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > properties.maxInputBytes() || bytes + envelope + job.reservedTokens() > job.contextTokens()) throw new JobFailure("TRANSCRIPT_OVER_BUDGET", false);
            var knownRefs = new HashSet<String>();
            for (var event : merged) if ("CREATE".equals(event.event().event_type())) knownRefs.add(event.event().task_ref());
            List<ExtractionValidator.Parsed> parsed; LlmProvider.Output result;
            try {
                result = call(job, row.index(), () -> provider.extract(job, data, schema, keepLease, ExtractionPrompt.chunked()));
                if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                parsed = dedupe(validator.parse(part, result, knownRefs), merged, row.overlapUntil());
            } catch (JobFailure failure) {
                if ("LEASE_LOST".equals(failure.code())) throw failure;
                store.chunkFailed(job.id(), row.index(), failure.code());
                boolean anyDone = rows.stream().anyMatch(r -> "COMPLETED".equals(r.status())) || !merged.isEmpty();
                // Later parts depend on earlier ones (known tasks), so processing stops at the first failed part.
                throw new JobFailure(failure.code(), failure.retryable() || "PROVIDER_RESPONSE_INVALID".equals(failure.code()), anyDone || row.index() > 0);
            }
            if (!store.saveChunk(lease, row.index(), json.writeValueAsString(parsed), result.inputTokens(), result.outputTokens(), result.latencyMs())) throw new JobFailure("LEASE_LOST", false);
            merged.addAll(parsed);
        }
        // Usage is summed from checkpoints so a resumed job still reports the total.
        var totals = store.chunkUsage(job.id());
        merged.sort(Comparator.comparingInt(p -> p.event().sequence()));
        // Final validation after consolidation (SDS §5.9): the same reconciliation, over all parts in source order.
        return validator.reconcile(job, merged, totals[0], totals[1], totals[2] == null ? 0 : totals[2]);
    }
    /** Overlap events already produced by an earlier part are dropped; a re-created task in the overlap maps to the existing ref. */
    static List<ExtractionValidator.Parsed> dedupe(List<ExtractionValidator.Parsed> parsed, List<ExtractionValidator.Parsed> earlier, int overlapUntil) {
        var byTaskEvidence = new HashMap<String, String>();
        var seen = new HashSet<String>();
        for (var item : earlier) {
            var e = item.event();
            seen.add(e.event_type() + "|" + e.task_ref() + "|" + e.sequence());
            if ("CREATE".equals(e.event_type())) for (var ref : e.evidence_refs()) if ("TASK".equals(ref.field())) byTaskEvidence.put(ref.segment_id(), e.task_ref());
        }
        var remap = new HashMap<String, String>();
        var result = new ArrayList<ExtractionValidator.Parsed>();
        for (var item : parsed) {
            var e = item.event();
            String ref = remap.getOrDefault(e.task_ref(), e.task_ref());
            if (e.sequence() <= overlapUntil) {
                if ("CREATE".equals(e.event_type())) {
                    String existing = e.evidence_refs().stream().filter(r -> "TASK".equals(r.field())).map(r -> byTaskEvidence.get(r.segment_id()))
                            .filter(Objects::nonNull).findFirst().orElse(null);
                    if (existing != null) { remap.put(e.task_ref(), existing); continue; }
                } else if (seen.contains(e.event_type() + "|" + ref + "|" + e.sequence())) continue;
            }
            if (!ref.equals(e.task_ref())) e = new Extraction.Event(e.event_type(), ref, e.sequence(), e.task_name(), e.assignee_raw(), e.deadline_raw(), e.priority(),
                    e.changed_fields(), e.evidence_refs(), e.ambiguities());
            result.add(new ExtractionValidator.Parsed(e, item.quotes()));
        }
        return result;
    }

    /** Greedy split at segment boundaries; each part after the first starts with up to N already-covered overlap segments. */
    List<AnalysisJobStore.ChunkRow> plan(List<Extraction.Source> source) {
        int budget = chunking.inputBytes() - chunking.knownTasksBytes(), n = source.size();
        var sizes = source.stream().mapToInt(this::segmentBytes).toArray();
        var plan = new ArrayList<AnalysisJobStore.ChunkRow>();
        int next = 0; // first sequence not yet covered
        while (next < n) {
            if (sizes[next] > budget) throw new JobFailure("TRANSCRIPT_OVER_BUDGET", false);
            int start = plan.isEmpty() ? next : Math.max(next - chunking.overlapSegments(), plan.get(plan.size() - 1).firstSequence() + 1);
            int used = 0; for (int i = start; i < next; i++) used += sizes[i];
            while (start < next && used + sizes[next] > budget) { used -= sizes[start]; start++; }
            int end = next;
            while (end < n && used + sizes[end] <= budget) { used += sizes[end]; end++; }
            plan.add(new AnalysisJobStore.ChunkRow(plan.size(), start, end - 1, start < next ? next - 1 : -1, "PENDING", null, 0));
            next = end;
        }
        return plan;
    }
    private int segmentBytes(Extraction.Source s) {
        try { return json.writeValueAsBytes(s).length + 1; } catch (Exception e) { return Integer.MAX_VALUE / 4; }
    }
    private List<Map<String, Object>> trim(List<Map<String, Object>> known) throws Exception {
        var list = new ArrayList<>(known);
        while (!list.isEmpty() && json.writeValueAsBytes(list).length > chunking.knownTasksBytes()) list.remove(0);
        return list;
    }
    private String data(AnalysisJob job, List<Extraction.Source> segments, Map<String, Object> part, List<Map<String, Object>> known) throws Exception {
        var data = json.createObjectNode(); data.put("title", job.title()); data.put("meeting_date", job.meetingDate() == null ? null : job.meetingDate().toString());
        data.put("timezone", job.timezone());
        if (part != null) { data.set("part", json.valueToTree(part)); data.set("known_tasks", json.valueToTree(known)); }
        data.set("segments", json.valueToTree(segments));
        return json.writeValueAsString(data);
    }
}
