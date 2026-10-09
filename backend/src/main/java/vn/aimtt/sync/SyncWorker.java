package vn.aimtt.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.sync.worker-enabled", havingValue = "true")
public class SyncWorker {
    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);
    private final SyncRunner runner;
    public SyncWorker(SyncRunner runner) { this.runner = runner; }
    @Scheduled(fixedDelayString = "${app.sync.poll-delay-ms:1000}")
    public void poll() {
        try { for (int i = 0; i < 10 && runner.runOnce(); i++) { } }
        catch (Exception failure) { log.error("Sync worker tick failed type={}", failure.getClass().getSimpleName()); }
    }
}
