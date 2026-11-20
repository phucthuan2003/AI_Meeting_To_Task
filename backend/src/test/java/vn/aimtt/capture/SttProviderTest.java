package vn.aimtt.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.JobFailure;
import vn.aimtt.llm.LlmHttpTransport;
import static org.assertj.core.api.Assertions.*;

class SttProviderTest {
    final ObjectMapper json=new ObjectMapper();
    HttpSttProvider provider(int status, String body) {
        return new HttpSttProvider(new AudioProperties("synthetic-stt-key",null,true,null,null),(request,lease)->{
            assertThat(request.uri().toString()).isEqualTo("https://api.groq.com/openai/v1/audio/transcriptions");
            assertThat(request.uri().getQuery()).isNull();assertThat(request.headers().firstValue("Authorization")).contains("Bearer synthetic-stt-key");
            assertThat(request.headers().firstValue("Content-Type").orElseThrow()).contains("multipart/form-data; boundary=aimtt");
            return new LlmHttpTransport.Response(status,body.getBytes(StandardCharsets.UTF_8));
        },json);
    }
    @Test void validatesTimestampedTranscriptAndDoesNotInventSpeaker() {
        var result=provider(200,"{\"duration\":3,\"segments\":[{\"start\":0.25,\"end\":2.5,\"text\":\" Mai làm API. \"}]}")
                .transcribe(new byte[]{1},"wav","whisper-large-v3-turbo",()->true);
        assertThat(result).containsExactly(new SttProvider.Segment(250,2500,"Mai làm API."));
    }
    @Test void silenceIsEmptyAndMalformedOrOversizedResultsFailClosed() {
        assertThat(provider(200,"{\"duration\":2,\"segments\":[]}").transcribe(new byte[]{1},"wav","whisper-large-v3-turbo",()->true)).isEmpty();
        for(String body:List.of("{}","{\"duration\":3601,\"segments\":[]}","{\"duration\":3,\"segments\":[{\"start\":2,\"end\":1,\"text\":\"bad\"}]}"))
            assertThatThrownBy(()->provider(200,body).transcribe(new byte[]{1},"wav","whisper-large-v3-turbo",()->true)).isInstanceOf(JobFailure.class);
    }
    @Test void filtersReportedOutroIncludingSplitAndDecomposedVietnamese() throws Exception {
        for (String text : List.of(
                "Hãy subscribe cho kênh Ghiền Mì Gõ Để không bỏ lỡ những video hấp dẫn",
                "Hãy subscribe cho kênh Ghiền Mì Gõ!",
                "Để không bỏ lỡ những video hấp dẫn.",
                java.text.Normalizer.normalize("HÃY SUBSCRIBE CHO KÊNH GHIỀN MÌ GÕ.", java.text.Normalizer.Form.NFD))) {
            var body = json.createObjectNode().put("duration", 15);
            body.putArray("segments").addObject().put("start", 0).put("end", 4).put("text", text);
            assertThat(provider(200, json.writeValueAsString(body))
                    .transcribe(new byte[]{1}, "wav", "whisper-large-v3-turbo", () -> true)).isEmpty();
        }
    }
    @Test void rejectsUncertainNonSpeechAndKeepsConfidentSpeechAndOriginalTimestamps() {
        var result = provider(200, """
                {"duration":15,"segments":[
                  {"start":0,"end":3,"text":"Âm nhiễu bị đoán thành lời.","no_speech_prob":0.95,"avg_logprob":-1.4},
                  {"start":4,"end":6,"text":"Mai làm API.","no_speech_prob":0.9,"avg_logprob":-0.2},
                  {"start":7,"end":9,"text":"Nam sửa nút subscribe cho kênh Ghiền Mì Gõ."},
                  {"start":10,"end":12,"text":"Kiểm tra đăng nhập.","avg_logprob":-1.2},
                  {"start":12,"end":14,"text":"Gửi báo cáo.","no_speech_prob":0.1,"avg_logprob":-1.2}
                ]}
                """).transcribe(new byte[]{1}, "wav", "whisper-large-v3-turbo", () -> true);
        assertThat(result).containsExactly(
                new SttProvider.Segment(4000, 6000, "Mai làm API."),
                new SttProvider.Segment(7000, 9000, "Nam sửa nút subscribe cho kênh Ghiền Mì Gõ."),
                new SttProvider.Segment(10000, 12000, "Kiểm tra đăng nhập."),
                new SttProvider.Segment(12000, 14000, "Gửi báo cáo."));
    }
    @Test void groqRequestExplicitlySelectsVietnamese() {
        var provider = new HttpSttProvider(new AudioProperties("key", null, true, null, null), (request, lease) -> {
            var bytes = new ByteArrayOutputStream();
            var done = new CompletableFuture<Void>();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer buffer) {
                    byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
                }
                public void onError(Throwable error) { done.completeExceptionally(error); }
                public void onComplete() { done.complete(null); }
            });
            done.join();
            assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("name=\"language\"\r\n\r\nvi\r\n");
            return new LlmHttpTransport.Response(200, "{\"duration\":2,\"segments\":[]}".getBytes(StandardCharsets.UTF_8));
        }, json);
        assertThat(provider.transcribe(new byte[]{1}, "wav", "whisper-large-v3-turbo", () -> true)).isEmpty();
    }
    @Test void authRateLimitAndRejectionNeverExposeRawProviderResponse() {
        for(int status:List.of(400,401,403,429,500)) assertThatThrownBy(()->provider(status,"secret audio/key").transcribe(new byte[]{1},"wav","whisper-large-v3-turbo",()->true))
                .isInstanceOf(JobFailure.class).hasMessageNotContaining("secret");
    }
    @Test void disabledSttDoesNotDispatchAndConfigDoesNotPrintSecret() {
        var properties=new AudioProperties("secret",null,true,null,null);assertThat(properties.toString()).doesNotContain("secret");
        var disabled=new HttpSttProvider(new AudioProperties("",null,true,null,null),(r,l)->{throw new AssertionError();},json);
        assertThatThrownBy(()->disabled.transcribe(new byte[]{1},"wav","whisper-large-v3-turbo",()->true))
                .isInstanceOfSatisfying(JobFailure.class,e->assertThat(e.code()).isEqualTo("STT_NOT_CONFIGURED"));
    }
    @Test void intelligenceUsesItsOwnEndpointAndMapsTimestamps() {
        var properties = new AudioProperties("groq-key", null, true, URI.create("http://localhost:8000"), "local-key");
        assertThat(properties.captureModel()).isEqualTo("meeting-intelligence");
        assertThat(properties.configured()).isTrue();
        var provider = new HttpSttProvider(properties, (request, lease) -> {
            assertThat(request.uri()).isEqualTo(URI.create("http://localhost:8000/api/v1/transcribe/file"));
            assertThat(request.version()).contains(HttpClient.Version.HTTP_1_1);
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer local-key");
            return new LlmHttpTransport.Response(200, """
                    {"duration_seconds":3,"segments":[{"start":0.2,"end":2.5,"text":"Mai làm API."}]}
                    """.getBytes(StandardCharsets.UTF_8));
        }, json);
        assertThat(provider.transcribe(new byte[]{1}, "wav", properties.captureModel(), () -> true))
                .containsExactly(new SttProvider.Segment(200, 2500, "Mai làm API."));
    }
    @Test void anExistingGroqCaptureKeepsItsProviderAfterSwitch() {
        var properties = new AudioProperties("groq-key", null, true, URI.create("http://localhost:8000"), "local-key");
        var provider = new HttpSttProvider(properties, (request, lease) -> {
            assertThat(request.uri().getHost()).isEqualTo("api.groq.com");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer groq-key");
            return new LlmHttpTransport.Response(200, "{\"duration\":2,\"segments\":[]}".getBytes(StandardCharsets.UTF_8));
        }, json);
        assertThat(provider.transcribe(new byte[]{1}, "wav", "whisper-large-v3", () -> true)).isEmpty();
    }
    @Test void containerSniffAndLimitsRejectRenamedText() {
        assertThat(AudioInput.validate("a.wav","RIFF1234WAVEdata".getBytes(StandardCharsets.US_ASCII))).isEqualTo("wav");
        for(String name:List.of("a.mp3","a.wav","a.webm","a.m4a","a.exe")) assertThatThrownBy(()->AudioInput.validate(name,"not audio".getBytes())).isInstanceOf(vn.aimtt.common.ApiException.class);
        assertThat(CaptureService.stamp(3723250)).isEqualTo("01:02:03.250");
    }
}
