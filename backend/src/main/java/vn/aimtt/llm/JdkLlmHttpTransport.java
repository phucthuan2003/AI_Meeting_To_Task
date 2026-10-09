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
    @Autowired public JdkLlmHttpTransport(AnalysisProperties analysis, LlmProperties properties) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                Math.max(250, analysis.leaseDuration().toMillis() / 3), properties);
    }
    JdkLlmHttpTransport(HttpClient client, long heartbeatMs, LlmProperties properties) {
        this.client = client; this.heartbeatMs = heartbeatMs; this.properties = properties;
    }
    @Override public Response send(HttpRequest request, BooleanSupplier keepLease) {
        if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
        var pending = client.sendAsync(request, ignored -> new LimitedBody(1048576));
        long deadline = System.nanoTime() + properties.requestTimeout().toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new JobFailure("PROVIDER_TIMEOUT", true);
                try {
                    var response = pending.get(Math.min(heartbeatMs, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
                    if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
                    return new Response(response.statusCode(), response.body());
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
