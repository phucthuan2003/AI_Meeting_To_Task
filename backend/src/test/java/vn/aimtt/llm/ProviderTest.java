package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import java.net.http.HttpRequest;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.JobFailure;
import static vn.aimtt.llm.LlmFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ProviderTest {
    final ObjectMapper json = new ObjectMapper();
    final List<HttpRequest> requests = new ArrayList<>();
    LlmProvider provider(String id, int status, Object response) {
        LlmHttpTransport transport = (request, keepLease) -> {
            requests.add(request);
            try { return new LlmHttpTransport.Response(status, json.writeValueAsBytes(response)); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        return id.equals("openai") ? new OpenAiProvider(transport, properties(), json) : new GeminiProvider(transport, properties(), json);
    }
    Object success(String id) {
        return id.equals("openai") ? Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",output())))), "usage",Map.of("input_tokens",42,"output_tokens",50))
                : Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",output()))))), "usageMetadata", Map.of("promptTokenCount",42,"candidatesTokenCount",50));
    }
    String requestBody(HttpRequest request) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream(); var done = new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer value) { byte[] part = new byte[value.remaining()]; value.get(part); bytes.writeBytes(part); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(null); }
        }); done.get(1, TimeUnit.SECONDS); return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test void bothProvidersUsePinnedModelOutputContractAndServerCredentialsOnly() throws Exception {
        for (String id : List.of("openai","gemini")) {
            var result = provider(id,200,success(id)).extract(job(id), "transcript-data", json.createObjectNode().put("type","object"), () -> true);
            assertThat(result.json()).isEqualTo(output()); assertThat(result.inputTokens()).isEqualTo(42); assertThat(result.outputTokens()).isEqualTo(50);
            var request = requests.get(requests.size()-1); var body = json.readTree(requestBody(request));
            assertThat(request.uri().getScheme()).isEqualTo("https"); assertThat(request.uri().getQuery()).isNull();
            assertThat(requestBody(request)).doesNotContain("synthetic-openai-key", "synthetic-gemini-key");
            if (id.equals("openai")) {
                assertThat(request.uri().toString()).isEqualTo("https://api.openai.com/v1/responses");
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer synthetic-openai-key");
                assertThat(body.get("model").asText()).isEqualTo(job(id).model());
                assertThat(body.get("store").asBoolean()).isFalse(); assertThat(body.at("/text/format/strict").asBoolean()).isTrue();
                assertThat(body.at("/input/1/content").asText()).isEqualTo("transcript-data");
            } else {
                assertThat(request.headers().firstValue("x-goog-api-key")).contains("synthetic-gemini-key");
                assertThat(request.uri().toString()).endsWith("gemini-2.5-flash:generateContent");
                assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(job(id).reservedTokens());
                assertThat(body.at("/contents/0/parts/0/text").asText()).contains("transcript-data", ExtractionPrompt.SYSTEM, "JSON SCHEMA:", "\"type\":\"object\"");
                assertThat(body.has("systemInstruction")).isFalse();
            }
        }
    }
    @Test void gemini38UsesTextGenerationWithCanonicalSchemaInPromptAndPinnedOutputLimit() throws Exception {
        var original = job("gemini");
        var pinned = new vn.aimtt.job.AnalysisJob(original.id(), original.meetingId(), original.revisionId(), original.inputVersion(), original.title(), original.meetingDate(), original.timezone(), original.providerId(), "gemini-3.8-flash", original.policyId(), original.promptVersion(), original.schemaVersion(), original.contextTokens(), original.reservedTokens(), original.idempotencyKey(), original.status(), original.stage(), original.completedChunks(), original.totalChunks(), original.leaseOwner(), original.leaseExpiresAt(), original.attemptCount(), original.attemptLimit(), original.retryCount(), original.nextRetryAt(), original.errorCode(), original.errorRetryable(), original.createdAt(), original.updatedAt(), original.completedAt());
        JsonNode schema;
        try (var input = new org.springframework.core.io.ClassPathResource("llm/meeting-events-v1.schema.json").getInputStream()) { schema = json.readTree(input); }
        provider("gemini", 200, success("gemini")).extract(pinned, "data", schema, () -> true);
        var request = requests.get(0); var config = json.readTree(requestBody(request)).get("generationConfig");
        assertThat(request.uri().toString()).endsWith("gemini-3.8-flash:generateContent");
        assertThat(config.size()).isEqualTo(1);
        assertThat(config.path("maxOutputTokens").asInt()).isEqualTo(pinned.reservedTokens());
        var body = json.readTree(requestBody(request));
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/contents/0/parts/0/text").asText()).contains(schema.toString(), ExtractionPrompt.SYSTEM, "data");
    }
    @Test void httpFailuresAreSanitizedAndNeverFallbackToTheOtherProvider() {
        Map<Integer,String> errors=Map.of(401,"PROVIDER_AUTH_ERROR",403,"PROVIDER_AUTH_ERROR",429,"PROVIDER_RATE_LIMIT",500,"PROVIDER_UNAVAILABLE",400,"PROVIDER_REQUEST_REJECTED",302,"PROVIDER_REQUEST_REJECTED");
        for (String id : List.of("openai","gemini")) for (var entry : errors.entrySet()) {
            int before=requests.size();
            assertThatThrownBy(() -> provider(id,entry.getKey(),Map.of("error","private transcript/key details")).extract(job(id),"data",json.createObjectNode(),()->true))
                    .isInstanceOfSatisfying(JobFailure.class,e -> assertThat(e.code()).isEqualTo(entry.getValue()));
            assertThat(requests).hasSize(before+1);
        }
    }
    @Test void rejectedRequestLogsOnlyStatusAndSnapshotNeverVendorTextOrSecrets() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProviderSupport.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            for (String status : List.of("INVALID_ARGUMENT", "synthetic-gemini-key")) {
                assertThatThrownBy(() -> provider("gemini", 400, Map.of("error", Map.of("status", status, "message", "synthetic-gemini-key private transcript")))
                        .extract(job("gemini"), "private transcript", json.createObjectNode(), () -> true)).isInstanceOf(JobFailure.class);
            }
            var messages = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).hasSize(2).allMatch(value -> value.contains("httpStatus=400") && value.contains("model=gemini-2.5-flash"));
            assertThat(messages.get(0)).contains("vendorStatus=INVALID_ARGUMENT");
            assertThat(messages.get(1)).contains("vendorStatus=UNSPECIFIED");
            assertThat(String.join("\n", messages)).doesNotContain("synthetic-gemini-key", "private transcript");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void incompleteAndRefusedOutputsCannotBecomeCompletedCandidates() {
        for (var response : List.of(Map.of("status","incomplete"), Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","refusal")))))))
            assertThatThrownBy(() -> provider("openai",200,response).extract(job("openai"),"data",json.createObjectNode(),()->true)).isInstanceOf(JobFailure.class);
        for (String finish : List.of("MAX_TOKENS","SAFETY")) assertThatThrownBy(() -> provider("gemini",200,Map.of("candidates",List.of(Map.of("finishReason",finish))))
                .extract(job("gemini"),"data",json.createObjectNode(),()->true)).isInstanceOf(JobFailure.class);
    }
    @Test void responseLimitCancelsStreamingBeforeUnboundedAllocation() {
        var body=new JdkLlmHttpTransport.LimitedBody(5); var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled.set(true);}});
        body.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        assertThat(cancelled).isTrue(); assertThatThrownBy(() -> body.getBody().toCompletableFuture().join()).hasCauseInstanceOf(JobFailure.class);
    }
    @Test void missingCredentialsDoNotDispatchRequests() {
        LlmHttpTransport transport=(request,lease)->{throw new AssertionError("Must not dispatch");};
        assertThatThrownBy(()->new OpenAiProvider(transport,LlmProperties.defaults(),json).extract(job("openai"),"data",json.createObjectNode(),()->true)).isInstanceOf(JobFailure.class);
        assertThatThrownBy(()->new GeminiProvider(transport,LlmProperties.defaults(),json).extract(job("gemini"),"data",json.createObjectNode(),()->true)).isInstanceOf(JobFailure.class);
    }
}
