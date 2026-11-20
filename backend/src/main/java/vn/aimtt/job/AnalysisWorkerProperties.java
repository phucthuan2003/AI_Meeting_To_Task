package vn.aimtt.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * concurrency: analysis jobs run at the same time in this backend instance (each mostly waits on the network; the
 * provider limiter in the HTTP transport still caps in-flight calls per API key). autoRetry: transient provider errors
 * (timeout, 429, 5xx, connection, busy) requeue the job with exponential backoff while attempts remain.
 */
@ConfigurationProperties("app.analysis.workers")
public record AnalysisWorkerProperties(@DefaultValue("4") int concurrency, @DefaultValue("true") boolean autoRetry,
                                       @DefaultValue("5s") Duration retryBaseDelay, @DefaultValue("60s") Duration retryMaxDelay) {
    public AnalysisWorkerProperties {
        if (concurrency < 1 || concurrency > 64) throw new IllegalArgumentException("Analysis worker concurrency must be 1-64.");
        if (retryBaseDelay == null || retryMaxDelay == null || retryBaseDelay.isNegative() || retryMaxDelay.compareTo(retryBaseDelay) < 0)
            throw new IllegalArgumentException("Analysis retry delays are invalid.");
    }
    /** Pre-pool behaviour: one job at a time and no automatic requeue. */
    public static AnalysisWorkerProperties legacy() { return new AnalysisWorkerProperties(1, false, Duration.ofSeconds(5), Duration.ofSeconds(60)); }
    /** Delay before attempt {@code attempt + 1}: base * 2^(attempt-1), capped, plus up to 20% jitter so retries do not align. */
    public Duration retryDelay(int attempt, String code) {
        long base = retryBaseDelay.toMillis() * ("PROVIDER_RATE_LIMIT".equals(code) ? 3 : 1);
        long delay = Math.min(retryMaxDelay.toMillis(), base * (1L << Math.max(0, Math.min(10, attempt - 1))));
        return Duration.ofMillis(delay + (long) (delay * 0.2 * java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
    }
}
