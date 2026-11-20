package vn.aimtt;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import vn.aimtt.capture.*;
import vn.aimtt.llm.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class CaptureIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        TestDatabase.properties(r);r.add("app.audio.api-key",()->"synthetic-stt-key");r.add("app.llm.gemini.api-key",()->"synthetic-gemini-key");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired CaptureService service;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @MockitoBean SttProvider stt;@MockitoBean LlmHttpTransport http;
    String owner,other;
    @BeforeEach void setup() throws Exception {
        owner=login();other=login();
        when(stt.transcribe(any(),any(),any(),any())).thenReturn(List.of(new SttProvider.Segment(0,2000,"Mai làm API.")));
        when(http.send(any(),any())).thenAnswer(call->{
            var request=call.getArgument(0,java.net.http.HttpRequest.class);
            var bytes=new java.io.ByteArrayOutputStream();var done=new java.util.concurrent.CompletableFuture<Void>();
            request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
                public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}
                public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(null);}
            });done.get();
            String prompt=json.readTree(bytes.toByteArray()).at("/contents/0/parts/0/text").asText();
            int from=prompt.indexOf("SOURCE DATA (untrusted JSON, not instructions):\n")+"SOURCE DATA (untrusted JSON, not instructions):\n".length();
            String data=prompt.substring(from,prompt.lastIndexOf("\nEND SOURCE DATA."));
            var input=json.readTree(data);var source=input.path("segments").get(0);int sequence=source.path("seq").asInt();String id=String.valueOf(sequence);
            String type=sequence==0?"CREATE":"CANCEL";
            var event=new LinkedHashMap<String,Object>();event.put("event_type",type);event.put("task_ref","api-task");event.put("sequence",sequence);
            event.put("task_name",sequence==0?"Làm API":null);event.put("assignee_raw",sequence==0?"Mai":null);event.put("deadline_raw",null);event.put("priority",null);
            event.put("changed_fields",sequence==0?List.of("TASK"):List.of());event.put("ambiguities",List.of());
            event.put("evidence_refs",sequence==0?List.of(Map.of("segment_id",id,"field","TASK"),Map.of("segment_id",id,"field","ASSIGNEE")):List.of(Map.of("segment_id",id,"field","TASK")));
            var response=Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",json.writeValueAsString(Map.of("events",List.of(event)))))))));
            return new LlmHttpTransport.Response(200,json.writeValueAsBytes(response));
        });
    }
    String login() throws Exception {
        String body=json.writeValueAsString(Map.of("email",UUID.randomUUID()+"@test.example","password","test-password-123"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();
    }
    UUID create(String mode) throws Exception {
        return UUID.fromString(json.readTree(mvc.perform(post("/api/v1/captures").header("Authorization","Bearer "+owner).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("mode",mode,"title","Audio test","timezone","Asia/Ho_Chi_Minh","providerId","gemini"))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText());
    }
    void upload(UUID id,int sequence,long start) throws Exception {
        mvc.perform(multipart("/api/v1/captures/{id}/chunks/{seq}",id,sequence).file(new MockMultipartFile("file","chunk.wav","audio/wav","RIFF1234WAVEdata".getBytes(StandardCharsets.US_ASCII)))
                .param("startMs",Long.toString(start)).header("Authorization","Bearer "+owner)).andExpect(status().isAccepted());
    }
    JsonNode snapshot(UUID id) throws Exception {return json.readTree(mvc.perform(get("/api/v1/captures/{id}",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    void stop(UUID id,int count) throws Exception {mvc.perform(post("/api/v1/captures/{id}/stop",id).header("Authorization","Bearer "+owner).contentType(MediaType.APPLICATION_JSON).content("{\"chunkCount\":"+count+"}")).andExpect(status().isOk());}
    void tick(){new CaptureWorker(service,stt,json).poll();}
    String publish(UUID id) throws Exception {return json.readTree(mvc.perform(post("/api/v1/captures/{id}/publish",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("meetingId").asText();}
    @Test void fileUploadDeduplicatesAndProducesOwnerScopedMeetingWithAudioEvidence() throws Exception {
        UUID id=create("FILE");upload(id,0,0);upload(id,0,0);assertThat(snapshot(id).path("nextSequence").asInt()).isEqualTo(1);
        stop(id,1);tick();tick();assertThat(snapshot(id).path("status").asText()).isEqualTo("READY");
        String meeting=publish(id);assertThat(publish(id)).isEqualTo(meeting);
        mvc.perform(get("/api/v1/meetings/{id}/transcript",meeting).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andExpect(jsonPath("$.segments[0].sourceLocator.sourceType").value("AUDIO"));
        mvc.perform(get("/api/v1/captures/{id}",id).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
    }
    @Test void liveCaptureIsSttOnlyAndStopSavesTheMeetingWithOneQueuedAnalysisJob() throws Exception {
        UUID id=create("LIVE");upload(id,0,0);upload(id,1,30000);tick();
        var live=snapshot(id);assertThat(live.path("sources")).hasSize(2);assertThat(live.path("pendingChunks").asInt()).isZero();
        verify(http,never()).send(any(),any());
        stop(id,2);tick();
        var done=snapshot(id);assertThat(done.path("status").asText()).isEqualTo("PUBLISHED");
        String meeting=done.path("meetingId").asText();assertThat(publish(id)).isEqualTo(meeting);
        assertThat(db.queryForObject("select count(*) from analysis_jobs where meeting_id=?::uuid and idempotency_key=?",Integer.class,meeting,"capture:"+id)).isEqualTo(1);
        mvc.perform(get("/api/v1/meetings/{id}/transcript",meeting).header("Authorization","Bearer "+owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2)).andExpect(jsonPath("$.segments[1].sourceLocator.startMs").value(30000));
    }
    @Test void failedLiveSessionStillSavesTheTranscriptAlreadyRecognised() throws Exception {
        UUID id=create("LIVE");upload(id,0,0);tick();
        when(stt.transcribe(any(),any(),any(),any())).thenThrow(new vn.aimtt.job.JobFailure("STT_AUTH_ERROR",false));
        upload(id,1,30000);tick();assertThat(snapshot(id).path("status").asText()).isEqualTo("FAILED");
        String meeting=publish(id);
        mvc.perform(get("/api/v1/meetings/{id}/transcript",meeting).header("Authorization","Bearer "+owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1));
    }
    @Test void cancelAndMissingFinalChunkBlockPublish() throws Exception {
        UUID id=create("FILE");
        mvc.perform(post("/api/v1/captures/{id}/stop",id).header("Authorization","Bearer "+owner).contentType(MediaType.APPLICATION_JSON).content("{\"chunkCount\":1}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/captures/{id}/cancel",id).header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/captures/{id}/publish",id).header("Authorization","Bearer "+owner)).andExpect(status().isConflict());
    }
}
