package vn.aimtt.privacy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SDS §2.3, §10.3: source kept 30 days (input.source-retention), at most 24h grace for active work, logs 90 days. */
@ConfigurationProperties("app.retention")
public record RetentionProperties(boolean enabled, long pollDelayMs, Duration grace, Duration logRetention, Duration sessionRetention) {
    public RetentionProperties {
        if (grace == null || grace.isNegative() || grace.toHours() > 24) throw new IllegalArgumentException("Retention grace must be 0–24h.");
        if (logRetention == null || logRetention.toDays() < 1 || sessionRetention == null) throw new IllegalArgumentException("Retention periods are required.");
    }
}
