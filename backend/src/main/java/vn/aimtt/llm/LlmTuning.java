package vn.aimtt.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Throughput/rate-limit knobs for provider calls. All users share one API key, so these are per provider host for this
 * backend instance.
 * <ul>
 * <li>geminiStructuredOutput: schema sent as responseJsonSchema (not as ~1.2 KB of prompt text on every call); a model
 *     that rejects it falls back to text mode once and is remembered.</li>
 * <li>geminiThinkingLevel: blank = model default; thinking tokens are billed as output and count against max-output-tokens.</li>
 * <li>maxConcurrentRequests: in-flight requests.</li>
 * <li>requestsPerMinute / tokensPerMinute: client-side budget matching the key's quota (0 = off). A request waits for
 *     budget instead of being sent and rejected with HTTP 429. Tokens are estimated from the request size.</li>
 * <li>rateLimitCooldown: after an HTTP 429 the host is paused for the provider's retry delay (or this default), so
 *     other jobs stop hammering the key too.</li>
 * <li>maxQueueWait: longest wait for a slot/budget; then PROVIDER_BUSY (retryable, the job is requeued).</li>
 * </ul>
 */
@ConfigurationProperties("app.llm.tuning")
public record LlmTuning(@DefaultValue("true") boolean geminiStructuredOutput, @DefaultValue("") String geminiThinkingLevel,
                        @DefaultValue("4") int maxConcurrentRequests, @DefaultValue("120s") Duration maxQueueWait,
                        @DefaultValue("0") int requestsPerMinute, @DefaultValue("0") int tokensPerMinute,
                        @DefaultValue("20s") Duration rateLimitCooldown) {
    public LlmTuning {
        if (geminiThinkingLevel == null) geminiThinkingLevel = "";
        if (!geminiThinkingLevel.isEmpty() && !geminiThinkingLevel.matches("minimal|low|medium|high"))
            throw new IllegalArgumentException("Gemini thinking level must be minimal, low, medium or high.");
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 64) throw new IllegalArgumentException("LLM concurrency must be 1-64.");
        if (maxQueueWait == null || maxQueueWait.isNegative() || maxQueueWait.toMinutes() > 10) throw new IllegalArgumentException("LLM queue wait must be 0-10 minutes.");
        if (requestsPerMinute < 0 || tokensPerMinute < 0) throw new IllegalArgumentException("LLM rate limits must be >= 0.");
        if (rateLimitCooldown == null || rateLimitCooldown.isNegative() || rateLimitCooldown.toMinutes() > 5) throw new IllegalArgumentException("LLM cooldown must be 0-5 minutes.");
    }
    /** Behaviour of the code before tuning existed: text mode, no client-side limits. */
    public static LlmTuning legacy() { return new LlmTuning(false, "", 64, Duration.ofSeconds(120), 0, 0, Duration.ZERO); }
    public static LlmTuning defaults() { return new LlmTuning(true, "", 4, Duration.ofSeconds(120), 0, 0, Duration.ofSeconds(20)); }
}
