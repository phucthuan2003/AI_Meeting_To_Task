package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import vn.aimtt.job.*;

@Component
public class OpenAiProvider implements LlmProvider {
    private final LlmHttpTransport transport;
    private final LlmProperties properties;
    private final ObjectMapper json;
    public OpenAiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json) {
        this.transport = transport; this.properties = properties; this.json = json;
    }
    public String id() { return "openai"; }
    public Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease, String instructions) {
        if (!properties.openai().configured()) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        var payload = Map.of("model", job.model(), "store", false, "max_output_tokens", job.reservedTokens(),
                "input", List.of(Map.of("role", "system", "content", instructions), Map.of("role", "user", "content", data)),
                "text", Map.of("format", Map.of("type", "json_schema", "name", "meeting_events", "strict", true, "schema", schema)));
        var request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                .timeout(properties.requestTimeout()).header("Authorization", "Bearer " + properties.openai().apiKey())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ProviderSupport.encode(json, payload))).build();
        long start = System.nanoTime(); var body = ProviderSupport.body(json, transport.send(request, keepLease), job);
        if (!"completed".equals(body.path("status").asText())) throw new JobFailure("PROVIDER_OUTPUT_INCOMPLETE", true);
        StringBuilder text = new StringBuilder();
        for (var item : body.path("output")) for (var content : item.path("content")) {
            if ("refusal".equals(content.path("type").asText())) throw new JobFailure("PROVIDER_REFUSED", false);
            if ("output_text".equals(content.path("type").asText())) text.append(content.path("text").asText());
        }
        if (text.isEmpty()) throw new JobFailure("PROVIDER_RESPONSE_INVALID", false);
        return new Output(text.toString(), ProviderSupport.count(body.path("usage").path("input_tokens")),
                ProviderSupport.count(body.path("usage").path("output_tokens")), (System.nanoTime() - start) / 1000000);
    }
}
