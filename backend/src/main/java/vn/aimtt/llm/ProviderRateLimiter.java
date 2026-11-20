package vn.aimtt.llm;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Sliding one-minute request/token budget for one provider host, plus a cooldown after HTTP 429. {@link #delayNanos}
 * either admits a request (records it, returns 0) or says how long to wait; the caller waits outside the lock while
 * keeping its lease alive. Only counters are kept: never prompts, transcript or keys.
 */
final class ProviderRateLimiter {
    private static final long MINUTE = 60_000_000_000L;
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"");
    private record Entry(long at, int tokens) {}
    private final ArrayDeque<Entry> window = new ArrayDeque<>();
    private final int rpm, tpm;
    private long tokens, cooldownUntil;

    ProviderRateLimiter(int rpm, int tpm) { this.rpm = rpm; this.tpm = tpm; }

    /** 0 = admitted and recorded now; otherwise nanoseconds to wait before asking again. */
    synchronized long delayNanos(int estimatedTokens, long now) {
        while (!window.isEmpty() && now - window.peekFirst().at() >= MINUTE) tokens -= window.pollFirst().tokens();
        if (cooldownUntil > now) return cooldownUntil - now;
        boolean overRequests = rpm > 0 && window.size() >= rpm;
        // A request bigger than the whole budget is still admitted once the window is empty (never starved).
        boolean overTokens = tpm > 0 && !window.isEmpty() && tokens + estimatedTokens > tpm;
        if (overRequests || overTokens) return Math.max(1, window.peekFirst().at() + MINUTE - now);
        window.addLast(new Entry(now, estimatedTokens)); tokens += estimatedTokens;
        return 0;
    }
    synchronized void cooldown(long nanos, long now) { cooldownUntil = Math.max(cooldownUntil, now + nanos); }

    /** Rough input-token estimate: ~3 UTF-8 bytes per token for mixed Vietnamese/JSON text. */
    static int estimateTokens(long bodyBytes) { return (int) Math.min(Integer.MAX_VALUE, Math.max(1, bodyBytes / 3)); }

    /** Provider-supplied wait after 429: Retry-After seconds header or Google RetryInfo "retryDelay"; else the fallback. */
    static long retryDelayNanos(String retryAfterHeader, byte[] body, long fallbackNanos) {
        try { if (retryAfterHeader != null) return Math.min(300, Long.parseLong(retryAfterHeader.strip())) * 1_000_000_000L; }
        catch (NumberFormatException ignored) { }
        if (body != null) {
            var match = RETRY_DELAY.matcher(new String(body, 0, Math.min(body.length, 16384), java.nio.charset.StandardCharsets.UTF_8));
            if (match.find()) return (long) (Math.min(300, Double.parseDouble(match.group(1))) * 1_000_000_000L);
        }
        return fallbackNanos;
    }
}
