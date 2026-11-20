package vn.aimtt.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.aimtt.job.AnalysisJob.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

@Component
public class AnalysisJobRunner {
    private static final Logger log = LoggerFactory.getLogger(AnalysisJobRunner.class);
    private final AnalysisJobStore jobs;
    private final AnalysisProperties properties;
    private final AnalysisPipeline pipeline;
    private final AnalysisWorkerProperties workers;
    /** Transient provider/transport failures that a later attempt can succeed on (same input, same request). */
    static final java.util.Set<String> TRANSIENT = java.util.Set.of("PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMIT", "PROVIDER_UNAVAILABLE",
            "PROVIDER_CONNECTION_ERROR", "PROVIDER_BUSY");
    public AnalysisJobRunner(AnalysisJobStore jobs, AnalysisProperties properties, AnalysisPipeline pipeline) {
        this(jobs, properties, pipeline, AnalysisWorkerProperties.legacy());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisJobRunner(AnalysisJobStore jobs, AnalysisProperties properties, AnalysisPipeline pipeline, org.springframework.beans.factory.ObjectProvider<AnalysisWorkerProperties> workers) {
        this(jobs, properties, pipeline, workers.getIfAvailable(AnalysisWorkerProperties::legacy));
    }
    public AnalysisJobRunner(AnalysisJobStore jobs, AnalysisProperties properties, AnalysisPipeline pipeline, AnalysisWorkerProperties workers) {
        this.jobs = jobs; this.properties = properties; this.pipeline = pipeline; this.workers = workers;
    }
    public void runOnce() { claim().ifPresent(this::process); }
    public java.util.Optional<Lease> claim() { return jobs.claim(); }
    public void process(Lease lease) {
        // Each step reads immutable source outside the short checkpoint transaction.
        while (!Thread.currentThread().isInterrupted() && advance(lease)) { }
        // On interruption no terminal state is fabricated. Another worker can recover the expired lease.
    }
    public boolean advance(Lease lease) {
        try {
            if (!jobs.heartbeat(lease)) return false;
            var job = jobs.get(lease.jobId());
            var checkpoint = jobs.checkpoint(job.id());
            if (checkpoint.prepared()) {
                if (!jobs.beginProvider(lease)) return false;
                var result = pipeline.processPrepared(job, lease, () -> {
                    var source = jobs.analysisSource(job);
                    if (source.size() != checkpoint.preparedSegments()) throw new JobFailure("SOURCE_UNAVAILABLE", false);
                    return source;
                }, () -> jobs.heartbeat(lease));
                String encoded = new ObjectMapper().writeValueAsString(result);
                if (encoded.getBytes(StandardCharsets.UTF_8).length > 1048576) throw new JobFailure("RESULT_OVER_BUDGET", false);
                jobs.complete(lease, encoded, result); return false;
            }
            var source = jobs.source(job, checkpoint.lastSequence(), properties.preparationBatchSize());
            if (!source.available()) throw new JobFailure("SOURCE_UNAVAILABLE", false);
            int expectedSequence = checkpoint.lastSequence() + 1;
            for (var segment : source.segments()) {
                if (segment.sequence() != expectedSequence++) throw new JobFailure("SOURCE_UNAVAILABLE", false);
            }
            long tokens = PreparationBudget.tokens(source.segments());
            if (PreparationBudget.exceeds(job, checkpoint.estimatedInputTokens() + tokens)) throw new JobFailure("TRANSCRIPT_OVER_BUDGET", false);
            boolean prepared = source.segments().size() < properties.preparationBatchSize();
            return jobs.saveCheckpoint(lease, checkpoint, source.segments(), tokens, prepared);
        } catch (JobFailure failure) {
            if (workers.autoRetry() && !failure.partial() && failure.retryable() && TRANSIENT.contains(failure.code())
                    && jobs.requeue(lease, failure.code(), workers.retryDelay(lease.attempt(), failure.code()))) return false;
            if (failure.partial()) jobs.failPartial(lease, failure.code(), failure.retryable());
            else jobs.fail(lease, failure.code(), failure.retryable());
            return false;
        } catch (Exception failure) {
            // Avoid source text, SQL parameters and provider messages in logs/errors.
            log.error("Analysis worker failed jobId={} type={}", lease.jobId(), failure.getClass().getSimpleName());
            try { jobs.fail(lease, "INTERNAL_JOB_ERROR", true); }
            catch (Exception persistenceFailure) { log.error("Analysis state persistence failed jobId={} type={}", lease.jobId(), persistenceFailure.getClass().getSimpleName()); }
            return false;
        }
    }
}
