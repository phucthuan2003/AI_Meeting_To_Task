package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.aimtt.job.*;

@Component
public class GeminiProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final Set<String> FAILOVER_CODES = Set.of(
            "PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMIT", "PROVIDER_UNAVAILABLE",
            "PROVIDER_CONNECTION_ERROR", "PROVIDER_AUTH_ERROR"
    );
    private static final long DEFAULT_HEDGE_DELAY_MS = 5000L;
    private static final int MAX_CONCURRENT_HEDGES = 3;
    private static final int MAX_KEY_ATTEMPTS = 6;
    private static final Duration KEY_COOLDOWN = Duration.ofSeconds(45);

    private final LlmHttpTransport transport;
    private final LlmProperties properties;
    private final ObjectMapper json;
    private final LlmTuning tuning;
    private final long hedgeDelayMs;
    /** Models that answered HTTP 400 to JSON mode: they use text mode for the rest of this process (no double requests). */
    private final Set<String> textOnlyModels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger keyCursor = new AtomicInteger();
    private final ConcurrentHashMap<String, Instant> cooldownUntil = new ConcurrentHashMap<>();
    private final ExecutorService hedgeExecutor;

    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json) {
        this(transport, properties, json, LlmTuning.legacy(), DEFAULT_HEDGE_DELAY_MS);
    }
    @Autowired
    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json, org.springframework.beans.factory.ObjectProvider<LlmTuning> tuning) {
        this(transport, properties, json, tuning.getIfAvailable(LlmTuning::defaults), DEFAULT_HEDGE_DELAY_MS);
    }
    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json, LlmTuning tuning) {
        this(transport, properties, json, tuning, DEFAULT_HEDGE_DELAY_MS);
    }
    GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json, LlmTuning tuning, long hedgeDelayMs) {
        this.transport = transport;
        this.properties = properties;
        this.json = json;
        this.tuning = tuning;
        this.hedgeDelayMs = Math.max(10L, hedgeDelayMs);
        var threadSeq = new AtomicInteger();
        this.hedgeExecutor = Executors.newCachedThreadPool(runnable -> {
            var t = new Thread(runnable, "gemini-hedge-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
    public String id() { return "gemini"; }
    public Output extract(AnalysisJob job, String data, JsonNode schema, BooleanSupplier keepLease, String instructions) {
        List<String> keys = properties.gemini().apiKeys();
        if (keys.isEmpty()) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        if (keys.size() == 1) {
            return extractWithKey(job, data, schema, instructions, keys.get(0), keepLease);
        }
        return extractHedged(job, data, schema, instructions, keys, keepLease);
    }

    private Output extractHedged(AnalysisJob job, String data, JsonNode schema, String instructions, List<String> keys, BooleanSupplier keepLease) {
        List<String> orderedKeys = prioritizeKeys(keys);
        int maxAttempts = Math.min(orderedKeys.size(), MAX_KEY_ATTEMPTS);
        var completion = new ExecutorCompletionService<Output>(hedgeExecutor);
        var active = new ArrayList<Future<Output>>();
        var leaseLock = new Object();
        BooleanSupplier syncedLease = () -> {
            synchronized (leaseLock) {
                return keepLease.getAsBoolean();
            }
        };

        int nextIndex = 0;
        int inFlight = 0;
        JobFailure lastFailure = null;
        try {
            active.add(submitAttempt(completion, job, data, schema, instructions, orderedKeys.get(nextIndex++), syncedLease));
            inFlight++;

            while (inFlight > 0 || nextIndex < maxAttempts) {
                long waitMs = (inFlight < MAX_CONCURRENT_HEDGES && nextIndex < maxAttempts) ? hedgeDelayMs : 30000L;
                Future<Output> finished;
                try {
                    finished = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new JobFailure("LEASE_LOST", false);
                }

                if (finished == null) {
                    if (!syncedLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                    if (inFlight < MAX_CONCURRENT_HEDGES && nextIndex < maxAttempts) {
                        log.info("Gemini hedge launching backup key attempt={}/{} after {}ms jobId={}",
                                nextIndex + 1, maxAttempts, hedgeDelayMs, job.id());
                        active.add(submitAttempt(completion, job, data, schema, instructions, orderedKeys.get(nextIndex++), syncedLease));
                        inFlight++;
                    }
                    continue;
                }

                inFlight--;
                try {
                    return finished.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new JobFailure("LEASE_LOST", false);
                } catch (ExecutionException exec) {
                    JobFailure failure = exec.getCause() instanceof JobFailure jf
                            ? jf : new JobFailure("PROVIDER_CONNECTION_ERROR", true);
                    if (!FAILOVER_CODES.contains(failure.code())) throw failure;
                    lastFailure = failure;
                    if (nextIndex < maxAttempts && inFlight < MAX_CONCURRENT_HEDGES) {
                        log.warn("Gemini attempt failed with {}, rotating to backup key attempt={}/{} jobId={}",
                                failure.code(), nextIndex + 1, maxAttempts, job.id());
                        active.add(submitAttempt(completion, job, data, schema, instructions, orderedKeys.get(nextIndex++), syncedLease));
                        inFlight++;
                    }
                }
            }
            throw lastFailure != null ? lastFailure : new JobFailure("PROVIDER_UNAVAILABLE", true);
        } finally {
            for (var future : active) future.cancel(true);
        }
    }

    private Future<Output> submitAttempt(ExecutorCompletionService<Output> completion, AnalysisJob job, String data,
                                         JsonNode schema, String instructions, String key, BooleanSupplier keepLease) {
        return completion.submit(() -> {
            try {
                return extractWithKey(job, data, schema, instructions, key, keepLease);
            } catch (JobFailure failure) {
                if (FAILOVER_CODES.contains(failure.code())) {
                    cooldownUntil.put(key, Instant.now().plus(KEY_COOLDOWN));
                }
                throw failure;
            }
        });
    }

    private List<String> prioritizeKeys(List<String> keys) {
        int startIdx = Math.floorMod(keyCursor.getAndIncrement(), keys.size());
        Instant now = Instant.now();
        var healthy = new ArrayList<String>(keys.size());
        var cooling = new ArrayList<String>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get((startIdx + i) % keys.size());
            Instant until = cooldownUntil.get(key);
            if (until != null && until.isAfter(now)) cooling.add(key);
            else healthy.add(key);
        }
        healthy.addAll(cooling);
        return healthy;
    }

    private Output extractWithKey(AnalysisJob job, String data, JsonNode schema, String instructions, String key, BooleanSupplier keepLease) {
        long start = System.nanoTime();
        JsonNode body;
        if (tuning.geminiStructuredOutput() && !textOnlyModels.contains(job.model())) {
            try {
                body = send(job, structured(job, data, schema, instructions), key, keepLease);
            } catch (JobFailure rejected) {
                if (!"PROVIDER_REQUEST_REJECTED".equals(rejected.code())) throw rejected;
                // The model/endpoint refused JSON mode or the adapted schema: remember it and use the text contract.
                textOnlyModels.add(job.model());
                log.warn("Gemini JSON mode rejected; using text mode model={}", job.model().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}") ? job.model() : "INVALID_MODEL_ID");
                if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                body = send(job, text(job, data, schema, instructions), key, keepLease);
            }
        } else {
            body = send(job, text(job, data, schema, instructions), key, keepLease);
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
    private JsonNode send(AnalysisJob job, Map<String, Object> payload, String key, BooleanSupplier keepLease) {
        var request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + job.model() + ":generateContent"))
                .timeout(properties.requestTimeout()).header("x-goog-api-key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ProviderSupport.encode(json, payload))).build();
        return ProviderSupport.body(json, transport.send(request, keepLease), job);
    }
    private static String unwrapJsonFence(String text) {
        String value = text.strip();
        // Accept only one fence wrapping the entire response; never fish JSON out of prose or a partial response.
        var fence = java.util.regex.Pattern.compile("\\A```(?:json)?[ \\t]*\\R([\\s\\S]*?)\\R```\\z").matcher(value);
        return fence.matches() ? fence.group(1).strip() : value;
    }
    @PreDestroy
    public void close() {
        hedgeExecutor.shutdownNow();
    }
}
