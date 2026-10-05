package vn.aimtt.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import vn.aimtt.common.ApiException;
import static org.assertj.core.api.Assertions.*;

class LoginRateLimiterTest {
    @Test void blocksBurstAndAllowsNextWindow() {
        var clock = new MutableClock();
        var limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < 20; i++) limiter.check("local");
        assertThatThrownBy(() -> limiter.check("local")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("RATE_LIMIT");
        limiter.check("different-ip");
        clock.now = clock.now.plusSeconds(60);
        assertThatCode(() -> limiter.check("local")).doesNotThrowAnyException();
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
