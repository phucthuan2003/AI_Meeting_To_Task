package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.aimtt.job.*;

@Component
public class GeminiProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final Set<String> FAILOVER_CODES = Set.of(
            "PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMIT", "PROVIDER_UNAVAILABLE",
            "PROVIDER_CONNECTION_ERROR", "PROVIDER_AUTH_ERROR"
    );
    private final LlmHttpTransport transport;
    private final LlmProperties properties;
    private final ObjectMapper json;
    private final AtomicInteger keyCursor = new AtomicInteger();
    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json) {
        this.transport = transport; this.properties = properties; this.json = json;
    }
    public String id() { return "gemini"; }
    public Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease, String instructions) {
        List<String> keys = properties.gemini().apiKeys();
        if (keys.isEmpty()) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        // Use text generation compatibility; the backend remains responsible for strict schema/evidence validation.
        var payload = Map.of("contents", List.of(Map.of("role", "user", "parts",
                List.of(Map.of("text", ExtractionPrompt.geminiText(instructions, data, schema.toString()))))),
                "generationConfig", Map.of("maxOutputTokens", job.reservedTokens()));
        String encodedPayload = ProviderSupport.encode(json, payload);
        URI endpoint = URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + job.model() + ":generateContent");
        int startIdx = keys.size() == 1 ? 0 : Math.floorMod(keyCursor.getAndIncrement(), keys.size());
        for (int attempt = 0; attempt < keys.size(); attempt++) {
            if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
            String key = keys.get((startIdx + attempt) % keys.size());
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(properties.requestTimeout()).header("x-goog-api-key", key)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(encodedPayload)).build();
            try {
                long start = System.nanoTime();
                var body = ProviderSupport.body(json, transport.send(request, keepLease), job);
                return parseResponse(body, start);
            } catch (JobFailure failure) {
                boolean hasNext = attempt + 1 < keys.size();
                if (!hasNext || !FAILOVER_CODES.contains(failure.code())) throw failure;
                log.warn("Gemini attempt={}/{} failed with {}, rotating to next API key jobId={}",
                        attempt + 1, keys.size(), failure.code(), job.id());
            }
        }
        throw new JobFailure("PROVIDER_UNAVAILABLE", true);
    }

    private Output parseResponse(JsonNode body, long start) {
        if (body.path("promptFeedback").has("blockReason")) throw new JobFailure("PROVIDER_REFUSED", false);
        var candidates = body.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) throw new JobFailure("PROVIDER_RESPONSE_INVALID", false);
        var candidate = candidates.get(0); String finish = candidate.path("finishReason").asText();
        if ("MAX_TOKENS".equals(finish)) throw new JobFailure("PROVIDER_OUTPUT_INCOMPLETE", true);
        if (!"STOP".equals(finish)) throw new JobFailure("PROVIDER_REFUSED", false);
        StringBuilder text = new StringBuilder();
        for (var part : candidate.path("content").path("parts")) if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
        if (text.isEmpty()) throw new JobFailure("PROVIDER_RESPONSE_INVALID", false);
        return new Output(unwrapJsonFence(text.toString()), ProviderSupport.count(body.path("usageMetadata").path("promptTokenCount")),
                ProviderSupport.count(body.path("usageMetadata").path("candidatesTokenCount")), (System.nanoTime() - start) / 1000000);
    }
    private static String unwrapJsonFence(String text) {
        String value = text.strip();
        // Accept only one fence wrapping the entire response; never fish JSON out of prose or a partial response.
        var fence = java.util.regex.Pattern.compile("\\A```(?:json)?[ \\t]*\\R([\\s\\S]*?)\\R```\\z").matcher(value);
        return fence.matches() ? fence.group(1).strip() : value;
    }
}
