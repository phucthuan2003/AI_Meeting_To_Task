package vn.aimtt.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sync worker limits (SDS §2.3, §12.3). Retries are bounded; UNKNOWN is only ever reconciled, never re-created automatically. */
@ConfigurationProperties("app.sync")
public record SyncProperties(boolean workerEnabled, long pollDelayMs, Duration leaseDuration, int maxTransientAttempts,
                             Duration retryBaseDelay, Duration reconcileDelay, int maxAutoReconcile, int reconcilePages) {
    public SyncProperties {
        if (leaseDuration == null || leaseDuration.toSeconds() < 5 || leaseDuration.toMinutes() > 10) throw new IllegalArgumentException("Sync lease out of bounds.");
        if (maxTransientAttempts < 1 || maxTransientAttempts > 10 || maxAutoReconcile < 0 || maxAutoReconcile > 10 || reconcilePages < 1 || reconcilePages > 20) {
            throw new IllegalArgumentException("Sync retry/reconcile budgets out of bounds.");
        }
        if (retryBaseDelay == null || reconcileDelay == null) throw new IllegalArgumentException("Sync delays are required.");
    }
}
