package vn.aimtt.llm;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderRateLimiterTest {
    static final long SECOND = 1_000_000_000L;
    @Test void requestsPerMinuteAdmitsUpToTheQuotaThenWaitsForTheOldestToExpire() {
        var limit = new ProviderRateLimiter(2, 0);
        assertThat(limit.delayNanos(10, 0)).isZero();
        assertThat(limit.delayNanos(10, SECOND)).isZero();
        assertThat(limit.delayNanos(10, 2 * SECOND)).isEqualTo(58 * SECOND);
        assertThat(limit.delayNanos(10, 60 * SECOND)).isZero();
    }
    @Test void tokensPerMinuteCountsEstimatedTokensButNeverStarvesALargeRequest() {
        var limit = new ProviderRateLimiter(0, 1000);
        assertThat(limit.delayNanos(600, 0)).isZero();
        assertThat(limit.delayNanos(600, SECOND)).isPositive();
        assertThat(limit.delayNanos(400, SECOND)).isZero();
        var big = new ProviderRateLimiter(0, 1000);
        assertThat(big.delayNanos(5000, 0)).isZero();
    }
    @Test void cooldownAfter429PausesEveryCallerAndUsesTheProviderRetryDelay() {
        var limit = new ProviderRateLimiter(0, 0);
        limit.cooldown(ProviderRateLimiter.retryDelayNanos(null,
                "{\"error\":{\"details\":[{\"retryDelay\":\"17s\"}]}}".getBytes(StandardCharsets.UTF_8), 20 * SECOND), 0);
        assertThat(limit.delayNanos(1, SECOND)).isEqualTo(16 * SECOND);
        assertThat(limit.delayNanos(1, 17 * SECOND)).isZero();
        assertThat(ProviderRateLimiter.retryDelayNanos("5", null, 20 * SECOND)).isEqualTo(5 * SECOND);
        assertThat(ProviderRateLimiter.retryDelayNanos(null, "{}".getBytes(), 20 * SECOND)).isEqualTo(20 * SECOND);
        assertThat(ProviderRateLimiter.estimateTokens(3000)).isEqualTo(1000);
    }
}
