package vn.aimtt.job;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Claims analysis jobs while a worker slot is free and runs each on its own thread, so N meetings are analysed at
 * once instead of one after another. Claims use FOR UPDATE SKIP LOCKED plus leases, so several threads and several
 * backend instances can poll the same queue safely.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.analysis.worker-enabled", havingValue = "true")
public class AnalysisWorker implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private final AnalysisJobRunner runner;
    private final Semaphore slots;
    private final ExecutorService pool;
    public AnalysisWorker(AnalysisJobRunner runner, AnalysisWorkerProperties workers) {
        this.runner = runner;
        this.slots = new Semaphore(workers.concurrency());
        var counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(workers.concurrency(), task -> {
            var thread = new Thread(task, "analysis-worker-" + counter.incrementAndGet()); thread.setDaemon(true); return thread;
        });
    }
    @Scheduled(fixedDelayString = "${app.analysis.poll-delay-ms:1000}")
    public void poll() {
        // Fill every free slot now; an idle queue returns immediately.
        while (slots.tryAcquire()) {
            java.util.Optional<AnalysisJob.Lease> claimed;
            try { claimed = runner.claim(); }
            catch (Exception failure) { slots.release(); log.error("Analysis claim failed type={}", failure.getClass().getSimpleName()); return; }
            if (claimed.isEmpty()) { slots.release(); return; }
            var lease = claimed.get();
            try {
                pool.execute(() -> {
                    try { runner.process(lease); }
                    catch (Exception failure) { log.error("Analysis worker failed jobId={} type={}", lease.jobId(), failure.getClass().getSimpleName()); }
                    finally { slots.release(); }
                });
            } catch (RejectedExecutionException shuttingDown) {
                // The lease expires and another worker recovers the job; nothing terminal is written here.
                slots.release(); return;
            }
        }
    }
    int freeSlots() { return slots.availablePermits(); }
    @Override public void destroy() { pool.shutdownNow(); }
}
