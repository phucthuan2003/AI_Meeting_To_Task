package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.aimtt.job.*;

@Component
public class GeminiProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private final LlmHttpTransport transport;
    private final LlmProperties properties;
    private final ObjectMapper json;
    private final LlmTuning tuning;
    /** Models that answered HTTP 400 to JSON mode: they use text mode for the rest of this process (no double requests). */
    private final Set<String> textOnlyModels = ConcurrentHashMap.newKeySet();

    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json) {
        this(transport, properties, json, LlmTuning.legacy());
    }
    @Autowired
    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json, org.springframework.beans.factory.ObjectProvider<LlmTuning> tuning) {
        this(transport, properties, json, tuning.getIfAvailable(LlmTuning::defaults));
    }
    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json, LlmTuning tuning) {
        this.transport = transport; this.properties = properties; this.json = json; this.tuning = tuning;
    }
    public String id() { return "gemini"; }
    public Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease, String instructions) {
        if (!properties.gemini().configured()) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        long start = System.nanoTime();
        JsonNode body;
        if (tuning.geminiStructuredOutput() && !textOnlyModels.contains(job.model())) {
            try {
                body = send(job, structured(job, data, schema, instructions), keepLease);
            } catch (JobFailure rejected) {
                if (!"PROVIDER_REQUEST_REJECTED".equals(rejected.code())) throw rejected;
                // The model/endpoint refused JSON mode or the adapted schema: remember it and use the text contract.
                textOnlyModels.add(job.model());
                log.warn("Gemini JSON mode rejected; using text mode model={}", job.model().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}") ? job.model() : "INVALID_MODEL_ID");
                if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                body = send(job, text(job, data, schema, instructions), keepLease);
            }
        } else {
            body = send(job, text(job, data, schema, instructions), keepLease);
        }
        if (body.path("promptFeedback").has("blockReason")) throw new JobFailure("PROVIDER_REFUSED", false);
        var candidates = body.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) throw new JobFailure("PROVIDER_RESPONSE_INVALID", false);
        var candidate = candidates.get(0); String finish = candidate.path("finishReason").asText();
        if ("MAX_TOKENS".equals(finish)) throw new JobFailure("PROVIDER_OUTPUT_INCOMPLETE", true);
        if (!"STOP".equals(finish)) throw new JobFailure("PROVIDER_REFUSED", false);
        StringBuilder text = new StringBuilder();
        for (var part : candidate.path("content").path("parts")) if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
        if (text.isEmpty()) throw new JobFailure("PROVIDER_RESPONSE_INVALID", false);
        var usage = body.path("usageMetadata");
        Long output = ProviderSupport.count(usage.path("candidatesTokenCount")), thoughts = ProviderSupport.count(usage.path("thoughtsTokenCount"));
        // Thinking tokens are billed as output and consume maxOutputTokens, so they are reported with the output.
        if (output != null && thoughts != null) output += thoughts;
        return new Output(unwrapJsonFence(text.toString()), ProviderSupport.count(usage.path("promptTokenCount")), output, (System.nanoTime() - start) / 1000000);
    }
    private Map<String, Object> structured(AnalysisJob job, String data, JsonNode schema, String instructions) {
        var config = new LinkedHashMap<String, Object>();
        config.put("maxOutputTokens", job.reservedTokens());
        config.put("responseMimeType", "application/json");
        config.put("responseJsonSchema", GeminiSchema.adapt(schema));
        if (!tuning.geminiThinkingLevel().isEmpty()) config.put("thinkingConfig", Map.of("thinkingLevel", tuning.geminiThinkingLevel()));
        var payload = new LinkedHashMap<String, Object>();
        // Rules are trusted system instructions; transcript data stays in the user turn.
        payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instructions))));
        payload.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", ExtractionPrompt.geminiData(data))))));
        payload.put("generationConfig", config);
        return payload;
    }
    private Map<String, Object> text(AnalysisJob job, String data, JsonNode schema, String instructions) {
        // Text generation compatibility; the backend remains responsible for strict schema/evidence validation.
        return Map.of("contents", List.of(Map.of("role", "user", "parts",
                List.of(Map.of("text", ExtractionPrompt.geminiText(instructions, data, schema.toString()))))),
                "generationConfig", Map.of("maxOutputTokens", job.reservedTokens()));
    }
    private JsonNode send(AnalysisJob job, Map<String, Object> payload, BooleanSupplier keepLease) {
        var request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + job.model() + ":generateContent"))
                .timeout(properties.requestTimeout()).header("x-goog-api-key", properties.gemini().apiKey())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ProviderSupport.encode(json, payload))).build();
        return ProviderSupport.body(json, transport.send(request, keepLease), job);
    }
    private static String unwrapJsonFence(String text) {
        String value = text.strip();
        // Accept only one fence wrapping the entire response; never fish JSON out of prose or a partial response.
        var fence = java.util.regex.Pattern.compile("\\A```(?:json)?[ \\t]*\\R([\\s\\S]*?)\\R```\\z").matcher(value);
        return fence.matches() ? fence.group(1).strip() : value;
    }
}
