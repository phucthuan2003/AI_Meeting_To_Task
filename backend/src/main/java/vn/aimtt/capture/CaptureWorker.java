package vn.aimtt.capture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.JobFailure;

/**
 * Pipeline: record → STT per chunk (here) → transcript → one analysis job after the meeting (AnalysisWorker).
 * Live capture no longer calls the LLM per chunk; a provider outage can therefore never lose the transcript.
 * Sessions are served round robin; with concurrency > 1 several chunks are transcribed at once (useful only when
 * the STT service has more than one slot, e.g. a GPU).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.audio.worker-enabled",havingValue="true")
public class CaptureWorker implements DisposableBean {
    private static final Logger log=LoggerFactory.getLogger(CaptureWorker.class);
    private final CaptureService store; private final SttProvider stt; private final ObjectMapper json;
    private final Semaphore slots; private final ExecutorService pool;
    static final Set<String> TRANSIENT=Set.of("STT_RATE_LIMIT","STT_REQUEST_FAILED","PROVIDER_UNAVAILABLE","PROVIDER_RATE_LIMIT",
            "PROVIDER_TIMEOUT","PROVIDER_CONNECTION_ERROR","PROVIDER_BUSY");
    /** A local STT service may be restarting or busy: wait up to ~1 minute (2+4+8+16+30 s) before failing the session. */
    static final int MAX_RETRIES=5;
    static final int DRAIN_LIMIT=500;
    long retryBaseMs=2000, retryMaxMs=30000;

    /** Synchronous worker (tests): poll() processes every pending chunk on the calling thread. */
    public CaptureWorker(CaptureService store, SttProvider stt, ObjectMapper json) {
        this.store=store;this.stt=stt;this.json=json;this.slots=null;this.pool=null;
    }
    @Autowired
    public CaptureWorker(CaptureService store, SttProvider stt, ObjectMapper json, @Value("${app.audio.worker-concurrency:1}") int concurrency) {
        this.store=store;this.stt=stt;this.json=json;
        int size=Math.max(1,Math.min(8,concurrency)); this.slots=new Semaphore(size);
        var counter=new AtomicInteger();
        this.pool=Executors.newFixedThreadPool(size,task->{var thread=new Thread(task,"capture-worker-"+counter.incrementAndGet());thread.setDaemon(true);return thread;});
    }
    private <T> T withRetry(CaptureService.Lease lease, java.util.function.Supplier<T> call) {
        for(int attempt=1;;attempt++) {
            try { return call.get(); }
            catch(JobFailure failure) {
                if(!failure.retryable()||!TRANSIENT.contains(failure.code())||attempt>MAX_RETRIES) throw failure;
                long until=System.currentTimeMillis()+Math.min(retryMaxMs,retryBaseMs*(1L<<(attempt-1)));
                while(System.currentTimeMillis()<until) {
                    try { Thread.sleep(Math.max(1,Math.min(1000,until-System.currentTimeMillis()))); }
                    catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new JobFailure("LEASE_LOST",false); }
                    if(!store.heartbeat(lease)) throw new JobFailure("LEASE_LOST",false);
                }
            }
        }
    }
    @Scheduled(fixedDelay=1000)
    public void poll() {
        if(pool==null) { drain(); return; }
        // Fill every free slot; each thread keeps claiming until the queue is empty, so chunks are not 1 s apart.
        while(slots.tryAcquire()) {
            Optional<CaptureService.Lease> claimed;
            try { claimed=store.claim(); }
            catch(Exception failure) { slots.release(); log.error("Capture claim failed type={}",failure.getClass().getSimpleName()); return; }
            if(claimed.isEmpty()) { slots.release(); return; }
            var first=claimed.get();
            try {
                pool.execute(()->{
                    try { process(first); drain(); }
                    catch(Exception failure) { log.error("Capture worker failed type={}",failure.getClass().getSimpleName()); }
                    finally { slots.release(); }
                });
            } catch(RejectedExecutionException shuttingDown) { slots.release(); return; }
        }
    }
    void drain() {
        for(int i=0;i<DRAIN_LIMIT;i++) {
            var next=store.claim(); if(next.isEmpty()) return;
            process(next.get());
        }
    }
    @Scheduled(fixedDelay=600000)
    public void expire() { store.expire(); }
    public void process(CaptureService.Lease lease) {
        try {
            var s=store.get(lease.id());
            if(s.processed()+1==s.next()) {
                store.drained(lease);
                if("LIVE".equals(s.mode())) autoPublish(lease.id());
                return;
            }
            var chunk=store.chunk(s);
            List<SttProvider.Segment> transcript;
            if(chunk.transcript()!=null) transcript=json.readValue(chunk.transcript(),new TypeReference<List<SttProvider.Segment>>(){});
            else {
                transcript=withRetry(lease,()->stt.transcribe(chunk.audio(),chunk.suffix(),s.sttModel(),()->store.heartbeat(lease)));
                store.checkpointStt(lease,transcript);
            }
            var sources=new ArrayList<>(store.sources(s));
            for(var row:transcript) {
                long from=chunk.startMs()+row.startMs(),to=chunk.startMs()+row.endMs();
                sources.add(new CaptureService.Source(UUID.randomUUID(),sources.size(),row.text().replaceAll("[\\r\\n]+"," "),from,to));
            }
            if(sources.stream().mapToInt(v->v.text().length()+1).sum()>200000) throw new JobFailure("TRANSCRIPT_OVER_BUDGET",false);
            if(store.encode(sources).getBytes(StandardCharsets.UTF_8).length>1048576) throw new JobFailure("RESULT_OVER_BUDGET",false);
            store.completeChunk(lease,sources);
        } catch(JobFailure e) { store.fail(lease,e.code()); }
        catch(Exception e) { store.fail(lease,"CAPTURE_PROCESSING_FAILED"); }
    }
    /** Last chunk done: save the transcript as a meeting and queue its single analysis job, without waiting for a click. */
    private void autoPublish(UUID id) {
        try {
            var s=store.get(id);
            if("READY".equals(s.status())) store.publish(s.owner(),id);
        } catch(ApiException nothingToSave) {
            // Empty transcript or a concurrent manual publish: the session stays as is and the panel explains.
        } catch(Exception failure) { log.error("Capture auto-publish failed type={}",failure.getClass().getSimpleName()); }
    }
    @Override public void destroy() { if(pool!=null) pool.shutdownNow(); }
}
