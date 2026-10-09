package vn.aimtt.job;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.analysis")
public record AnalysisProperties(boolean workerEnabled, @NotNull Duration leaseDuration,
        @Min(1) @Max(10) int maxAttempts, @Min(1) @Max(10) int maxManualRetries,
        @Min(1) @Max(20) int maxActivePerUser, @Min(1) @Max(500) int preparationBatchSize,
        @Min(4096) @Max(1000000) int contextTokens, @Min(1) int reservedTokens) {
    public AnalysisProperties {
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Analysis lease must be between 5 seconds and 5 minutes.");
        }
        if (reservedTokens >= contextTokens) throw new IllegalArgumentException("Analysis token reserve must fit context.");
    }
}
