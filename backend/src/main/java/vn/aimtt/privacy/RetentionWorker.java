package vn.aimtt.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true")
public class RetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(RetentionWorker.class);
    private final PrivacyService privacy;
    public RetentionWorker(PrivacyService privacy) { this.privacy = privacy; }
    @Scheduled(initialDelayString = "60000", fixedDelayString = "${app.retention.poll-delay-ms:600000}")
    public void run() {
        try { privacy.runRetention(); } catch (Exception e) { log.error("Retention pass failed type={}", e.getClass().getSimpleName()); }
    }
}
