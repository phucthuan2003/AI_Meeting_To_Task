package vn.aimtt.llm;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.JobFailure;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdkTransportTest {
    final HttpClient client=mock(HttpClient.class);
    final HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses")).POST(HttpRequest.BodyPublishers.ofString("fixture")).build();
    @Test void waitingForProviderRenewsLeaseAndCancellationAbortsFutureWithoutPublishing() {
        var future=new CompletableFuture<HttpResponse<byte[]>>();
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(future);
        var calls=new AtomicInteger();
        assertThatThrownBy(()->new JdkLlmHttpTransport(client,1,LlmFixtures.properties()).send(request,()->calls.incrementAndGet()<3))
                .isInstanceOfSatisfying(JobFailure.class,e->assertThat(e.code()).isEqualTo("LEASE_LOST"));
        assertThat(calls.get()).isEqualTo(3);assertThat(future.isCancelled()).isTrue();
        verify(client,times(1)).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }
    @Test void leaseLostBeforeDispatchDoesNotSendAnything() {
        assertThatThrownBy(()->new JdkLlmHttpTransport(client,1,LlmFixtures.properties()).send(request,()->false)).isInstanceOf(JobFailure.class);
        verifyNoInteractions(client);
    }
    @Test void interruptedAndFailedRequestsAreBoundedSanitizedAndNotRetried() {
        var future=new CompletableFuture<HttpResponse<byte[]>>();
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(future);
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(()->new JdkLlmHttpTransport(client,1,LlmFixtures.properties()).send(request,()->true)).isInstanceOf(JobFailure.class); }
        finally { Thread.interrupted(); }
        assertThat(future.isCancelled()).isTrue();
        var failed=CompletableFuture.<HttpResponse<byte[]>>failedFuture(new HttpTimeoutException("private details"));
        when(client.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(failed);
        assertThatThrownBy(()->new JdkLlmHttpTransport(client,1,LlmFixtures.properties()).send(request,()->true))
                .isInstanceOfSatisfying(JobFailure.class,e->{assertThat(e.code()).isEqualTo("PROVIDER_TIMEOUT");assertThat(e.getMessage()).doesNotContain("private");});
    }
}
