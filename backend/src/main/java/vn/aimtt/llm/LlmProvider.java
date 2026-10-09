package vn.aimtt.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.BooleanSupplier;
import vn.aimtt.job.AnalysisJob;

public interface LlmProvider {
    record Output(String json, Long inputTokens, Long outputTokens, long latencyMs) {}
    String id();
    Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease);
}
