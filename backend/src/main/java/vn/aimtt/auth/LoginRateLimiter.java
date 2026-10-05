package vn.aimtt.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.aimtt.common.ApiException;

/** Single-process development limit; shared enforcement is needed before multiple replicas. */
@Component
public class LoginRateLimiter {
    private record Window(Instant expiresAt, int attempts) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) { this.clock = clock; }

    public synchronized void check(String remoteAddress) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Window window = windows.get(remoteAddress);
        if (window == null) {
            if (windows.size() >= 10000) deny();
            window = new Window(now.plusSeconds(60), 0);
        }
        if (window.attempts() >= 20) deny();
        windows.put(remoteAddress, new Window(window.expiresAt(), window.attempts() + 1));
    }

    private void deny() {
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT", "Thử lại sau một phút.");
    }
}
