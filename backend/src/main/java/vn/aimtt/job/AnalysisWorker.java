package vn.aimtt.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.analysis.worker-enabled", havingValue = "true")
public class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private final AnalysisJobRunner runner;
    public AnalysisWorker(AnalysisJobRunner runner) { this.runner = runner; }
    @Scheduled(fixedDelayString = "${app.analysis.poll-delay-ms:1000}")
    public void poll() {
        try { runner.runOnce(); }
        catch (Exception failure) { log.error("Analysis claim failed type={}", failure.getClass().getSimpleName()); }
    }
}
