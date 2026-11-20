package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Token-lean view of segments for the model (SDS §5.7). A 36-character UUID costs roughly 20 tokens every time it is
 * sent and every time the model cites it, so segments go out as {"seq", "text"} and evidence cites the sequence as a
 * string. {@link #restoreIds} maps those references back to the stored segment UUIDs before validation, so the
 * validator, evidence and persistence keep working on UUIDs exactly as before.
 */
public final class CompactSource {
    private CompactSource() {}

    public static ArrayNode segments(ObjectMapper json, List<Extraction.Source> source) {
        var array = json.createArrayNode();
        for (var segment : source) array.addObject().put("seq", segment.sequence()).put("text", TextNormalizer.forModel(segment.text()));
        return array;
    }

    /**
     * Replaces evidence_refs[].segment_id values that name a sequence of {@code source} with that segment's UUID.
     * Anything else (unknown sequence, malformed JSON) is left untouched for the validator to reject.
     */
    public static LlmProvider.Output restoreIds(ObjectMapper json, LlmProvider.Output output, List<Extraction.Source> source) {
        JsonNode root;
        // Parse as strictly as the validator: duplicate keys or trailing text must still reach it unchanged (and be rejected).
        try { root = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output.json()); }
        catch (Exception malformed) { return output; }
        if (root == null || !root.path("events").isArray()) return output;
        Map<String, String> ids = new HashMap<>();
        for (var segment : source) ids.put(Integer.toString(segment.sequence()), segment.segmentId().toString());
        boolean changed = false;
        for (var event : root.path("events")) {
            for (var ref : event.path("evidence_refs")) {
                if (!(ref instanceof ObjectNode object) || !(object.path("segment_id").isTextual() || object.path("segment_id").isIntegralNumber())) continue;
                String value = object.get("segment_id").asText().strip();
                if (value.startsWith("s") || value.startsWith("S")) value = value.substring(1);
                String id = ids.get(value);
                if (id != null) { object.put("segment_id", id); changed = true; }
            }
        }
        if (!changed) return output;
        try { return new LlmProvider.Output(json.writeValueAsString(root), output.inputTokens(), output.outputTokens(), output.latencyMs()); }
        catch (Exception failure) { return output; }
    }
}
