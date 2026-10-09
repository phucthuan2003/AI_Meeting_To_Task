package vn.aimtt.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.BooleanSupplier;
import vn.aimtt.job.AnalysisJob;

public interface LlmProvider {
    record Output(String json, Long inputTokens, Long outputTokens, long latencyMs) {}
    String id();
    default Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease) {
        return extract(job, data, schema, keepLease, ExtractionPrompt.SYSTEM);
    }
    /** {@code instructions} are backend-owned system rules (single call or one part of a chunked analysis). */
    Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease, String instructions);
}
