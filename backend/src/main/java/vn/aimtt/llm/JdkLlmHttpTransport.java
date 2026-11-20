package vn.aimtt.llm;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import vn.aimtt.job.AnalysisProperties;
import vn.aimtt.job.JobFailure;

@Component
public class JdkLlmHttpTransport implements LlmHttpTransport {
    private final HttpClient client;
    private final long heartbeatMs;
    private final LlmProperties properties;
    private final LlmTuning tuning;
    /** One permit pool per provider host: every worker thread and the live-capture worker share the API key's concurrency. */
    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProviderRateLimiter> limits = new ConcurrentHashMap<>();
    @Autowired public JdkLlmHttpTransport(AnalysisProperties analysis, LlmProperties properties, org.springframework.beans.factory.ObjectProvider<LlmTuning> tuning) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                Math.max(250, analysis.leaseDuration().toMillis() / 3), properties, tuning.getIfAvailable(LlmTuning::defaults));
    }
    JdkLlmHttpTransport(HttpClient client, long heartbeatMs, LlmProperties properties) {
        this(client, heartbeatMs, properties, LlmTuning.legacy());
    }
    JdkLlmHttpTransport(HttpClient client, long heartbeatMs, LlmProperties properties, LlmTuning tuning) {
        this.client = client; this.heartbeatMs = heartbeatMs; this.properties = properties; this.tuning = tuning;
    }
    @Override public Response send(HttpRequest request, BooleanSupplier keepLease) {
        if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
        String host = String.valueOf(request.uri().getHost());
        long deadline = System.nanoTime() + tuning.maxQueueWait().toNanos();
        var limit = limits.computeIfAbsent(host, ignored -> new ProviderRateLimiter(tuning.requestsPerMinute(), tuning.tokensPerMinute()));
        // 1) minute budget / 429 cooldown, without holding a concurrency slot; 2) concurrency slot.
        admit(limit, ProviderRateLimiter.estimateTokens(request.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L)), keepLease, deadline);
        var permit = permits.computeIfAbsent(host, ignored -> new Semaphore(tuning.maxConcurrentRequests(), true));
        acquire(permit, keepLease, deadline);
        try {
            var response = dispatch(request, keepLease);
            if (response.status() == 429 && !tuning.rateLimitCooldown().isZero())
                limit.cooldown(ProviderRateLimiter.retryDelayNanos(response.retryAfter(), response.body(), tuning.rateLimitCooldown().toNanos()), System.nanoTime());
            return new Response(response.status(), response.body());
        } finally { permit.release(); }
    }
    /** Waits (lease kept alive) until the host's minute budget admits this request; bounded by max-queue-wait. */
    private void admit(ProviderRateLimiter limit, int tokens, BooleanSupplier keepLease, long deadline) {
        while (true) {
            long now = System.nanoTime(), wait = limit.delayNanos(tokens, now);
            if (wait == 0) return;
            if (now >= deadline) throw new JobFailure("PROVIDER_BUSY", true);
            long sleep = Math.min(Math.min(wait, deadline - now), TimeUnit.MILLISECONDS.toNanos(heartbeatMs));
            try { TimeUnit.NANOSECONDS.sleep(Math.max(1_000_000, sleep)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new JobFailure("LEASE_LOST", false); }
            if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
        }
    }
    /** Waiting for a slot is not provider time: the request timeout starts only after the permit is held. */
    private void acquire(Semaphore permit, BooleanSupplier keepLease, long deadline) {
        if (permit.tryAcquire()) return; // uncontended fast path (no wait, no interruption point)
        try {
            while (!permit.tryAcquire(heartbeatMs, TimeUnit.MILLISECONDS)) {
                if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                if (System.nanoTime() >= deadline) throw new JobFailure("PROVIDER_BUSY", true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new JobFailure("LEASE_LOST", false);
        }
    }
    int available(String host) { var permit = permits.get(host); return permit == null ? tuning.maxConcurrentRequests() : permit.availablePermits(); }
    private record Dispatched(int status, byte[] body, String retryAfter) {}
    private Dispatched dispatch(HttpRequest request, BooleanSupplier keepLease) {
        var pending = client.sendAsync(request, ignored -> new LimitedBody(1048576));
        long deadline = System.nanoTime() + properties.requestTimeout().toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new JobFailure("PROVIDER_TIMEOUT", true);
                try {
                    var response = pending.get(Math.min(heartbeatMs, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
                    if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                    return new Dispatched(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null));
                } catch (TimeoutException timeout) {
                    if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new JobFailure("LEASE_LOST", false);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof JobFailure typed) throw typed;
            if (failure.getCause() instanceof HttpTimeoutException) throw new JobFailure("PROVIDER_TIMEOUT", true);
            throw new JobFailure("PROVIDER_CONNECTION_ERROR", true);
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return completion; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); completion.completeExceptionally(new JobFailure("PROVIDER_RESPONSE_INVALID", false)); return;
                }
                byte[] value = new byte[buffer.remaining()]; buffer.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { completion.completeExceptionally(failure); }
        public void onComplete() { completion.complete(bytes.toByteArray()); }
    }
}
