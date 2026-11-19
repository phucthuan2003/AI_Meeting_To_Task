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
    private final long hedgeDelayMs;
    private final AtomicInteger keyCursor = new AtomicInteger();
    private final ConcurrentHashMap<String, Instant> cooldownUntil = new ConcurrentHashMap<>();
    private final ExecutorService hedgeExecutor;

    @Autowired
    public GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json) {
        this(transport, properties, json, DEFAULT_HEDGE_DELAY_MS);
    }

    GeminiProvider(LlmHttpTransport transport, LlmProperties properties, ObjectMapper json, long hedgeDelayMs) {
        this.transport = transport;
        this.properties = properties;
        this.json = json;
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
        // Use text generation compatibility; the backend remains responsible for strict schema/evidence validation.
        var payload = Map.of("contents", List.of(Map.of("role", "user", "parts",
                List.of(Map.of("text", ExtractionPrompt.geminiText(instructions, data, schema.toString()))))),
                "generationConfig", Map.of("maxOutputTokens", job.reservedTokens()));
        String encodedPayload = ProviderSupport.encode(json, payload);
        URI endpoint = URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + job.model() + ":generateContent");
        if (keys.size() == 1) {
            return sendOnce(job, endpoint, encodedPayload, keys.get(0), keepLease);
        }
        return extractHedged(job, endpoint, encodedPayload, keys, keepLease);
    }

    private Output extractHedged(AnalysisJob job, URI endpoint, String encodedPayload, List<String> keys, BooleanSupplier keepLease) {
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
            active.add(submitAttempt(completion, job, endpoint, encodedPayload, orderedKeys.get(nextIndex++), syncedLease));
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
                        active.add(submitAttempt(completion, job, endpoint, encodedPayload, orderedKeys.get(nextIndex++), syncedLease));
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
                        active.add(submitAttempt(completion, job, endpoint, encodedPayload, orderedKeys.get(nextIndex++), syncedLease));
                        inFlight++;
                    }
                }
            }
            throw lastFailure != null ? lastFailure : new JobFailure("PROVIDER_UNAVAILABLE", true);
        } finally {
            for (var future : active) future.cancel(true);
        }
    }

    private Future<Output> submitAttempt(ExecutorCompletionService<Output> completion, AnalysisJob job, URI endpoint,
                                         String encodedPayload, String key, BooleanSupplier keepLease) {
        return completion.submit(() -> {
            try {
                return sendOnce(job, endpoint, encodedPayload, key, keepLease);
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

    private Output sendOnce(AnalysisJob job, URI endpoint, String encodedPayload, String key, BooleanSupplier keepLease) {
        if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout()).header("x-goog-api-key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(encodedPayload)).build();
        long start = System.nanoTime();
        var body = ProviderSupport.body(json, transport.send(request, keepLease), job);
        return parseResponse(body, start);
    }

    @PreDestroy
    public void close() {
        hedgeExecutor.shutdownNow();
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
