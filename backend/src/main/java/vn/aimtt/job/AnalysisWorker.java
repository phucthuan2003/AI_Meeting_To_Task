package vn.aimtt.job;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final Semaphore slots;
    private final ExecutorService pool;

    public AnalysisWorker(AnalysisJobRunner runner, @Value("${app.analysis.max-workers:3}") int maxWorkers) {
        int workers = Math.max(1, maxWorkers);
        this.runner = runner;
        this.slots = new Semaphore(workers);
        var threadId = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(workers, runnable -> {
            var thread = new Thread(runnable, "analysis-worker-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${app.analysis.poll-delay-ms:1000}")
    public void poll() {
        dispatch();
    }

    private void dispatch() {
        if (!slots.tryAcquire()) return;
        try {
            pool.execute(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted() && runner.runOnce(this::dispatch)) { }
                } catch (Exception failure) {
                    log.error("Analysis claim failed type={}", failure.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException failure) {
            slots.release();
            throw failure;
        }
    }

    @PreDestroy
    public void close() {
        pool.shutdownNow();
    }
}
