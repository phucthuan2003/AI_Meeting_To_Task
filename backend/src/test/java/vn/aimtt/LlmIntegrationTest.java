package vn.aimtt;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import vn.aimtt.job.*;
import vn.aimtt.llm.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
class LlmIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.properties(registry);
        registry.add("app.llm.openai.api-key",()->"synthetic-openai-key"); registry.add("app.llm.gemini.api-key",()->"synthetic-gemini-key");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalysisJobStore jobs;
    @Autowired AnalysisJobRunner runner;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean LlmHttpTransport transport;
    final List<UUID> meetings=new ArrayList<>();
    @AfterEach void cleanup() {
        for(UUID meeting:meetings) jdbc.update("UPDATE analysis_jobs SET status='CANCELLED', stage='CANCELLED', lease_owner=NULL, lease_expires_at=NULL, completed_at=now(), updated_at=now() WHERE meeting_id=? AND status IN ('QUEUED','PROCESSING','CANCEL_REQUESTED')", meeting);
    }
    UUID ownerId;
    String ownerToken;
    void owner() throws Exception {
        String id=UUID.randomUUID().toString();String credentials=json.writeValueAsString(Map.of("email",id+"@example.test","password","synthetic-password-123"));
        var user=read(mvc.perform(post("/api/v1/auth/register").with(r->{r.setRemoteAddr(id);return r;}).contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        ownerId=UUID.fromString(user.get("id").asText());
        ownerToken=read(mvc.perform(post("/api/v1/auth/login").with(r->{r.setRemoteAddr(id);return r;}).contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }
    record Started(JsonNode meeting,UUID id) {}
    Started start(String provider) throws Exception {
        var meeting=read(mvc.perform(post("/api/v1/meetings").header("Authorization","Bearer "+ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("transcriptText",LlmFixtures.TEXT,"timezone","Asia/Ho_Chi_Minh")))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID meetingId=UUID.fromString(meeting.get("meetingId").asText());meetings.add(meetingId);
        var job=read(mvc.perform(post("/api/v1/meetings/{id}/analysis-jobs",meetingId).header("Authorization","Bearer "+ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedInputVersion",meeting.get("inputVersion").asLong(),"providerId",provider,"processingPolicyId","llm-v1"))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        UUID jobId=UUID.fromString(job.get("jobId").asText());var actualSource=jobs.analysisSource(jobs.get(jobId));
        String output=LlmFixtures.output().replace(LlmFixtures.SEGMENT.toString(),actualSource.get(0).segmentId().toString());
        var response=provider.equals("openai") ? Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",output)))))
                : Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",output))))));
        when(transport.send(any(),any())).thenReturn(new LlmHttpTransport.Response(200,json.writeValueAsBytes(response)));
        return new Started(meeting,jobId);
    }
    @Test void bothAdaptersPublishValidatedCandidatesAtomicallyAndResultCanBeReopened() throws Exception {
        owner();
        for(String provider:List.of("openai","gemini")) {
            var fixture=start(provider);runner.runOnce();
            assertThat(jobs.get(fixture.id()).status()).isEqualTo(AnalysisJob.Status.COMPLETED);assertThat(jobs.get(fixture.id()).completedChunks()).isEqualTo(1);
            for(int i=0;i<2;i++) mvc.perform(get("/api/v1/meetings/{id}/tasks",fixture.meeting().get("meetingId").asText()).param("analysisJobId",fixture.id().toString()).header("Authorization","Bearer "+ownerToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.providerId").value(provider)).andExpect(jsonPath("$.result.candidates.length()").value(1))
                    .andExpect(jsonPath("$.result.candidates[0].assigneeRaw").value("Mai")).andExpect(jsonPath("$.result.candidates[0].reviewStatus").value("PENDING_REVIEW"));
        }
    }
    @Test void cancellationDuringHttpRequestDiscardsProviderResult() throws Exception {
        owner();var fixture=start("openai");var response=new LlmHttpTransport.Response(200,json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text","{\"events\":[]}")))))));
        when(transport.send(any(),any())).thenAnswer(invocation->{mvc.perform(post("/api/v1/jobs/{id}/cancel",fixture.id()).header("Authorization","Bearer "+ownerToken)).andExpect(status().isAccepted());return response;});
        runner.runOnce();assertThat(jobs.get(fixture.id()).status()).isEqualTo(AnalysisJob.Status.CANCELLED);assertThat(jobs.result(fixture.id())).isEmpty();
    }
    @Test void schemaInvalidOutputNeverPublishesAndOwnersCannotReadEachOthersCandidates() throws Exception {
        owner();var fixture=start("gemini");when(transport.send(any(),any())).thenReturn(new LlmHttpTransport.Response(200,json.writeValueAsBytes(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text","{\"events\":[{}]}")))))))));
        runner.runOnce();assertThat(jobs.get(fixture.id()).errorCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");assertThat(jobs.result(fixture.id())).isEmpty();
        mvc.perform(get("/api/v1/meetings/{id}/tasks",fixture.meeting().get("meetingId").asText()).header("Authorization","Bearer "+ownerToken)).andExpect(status().isConflict());
        String previous=ownerToken;owner();
        mvc.perform(get("/api/v1/meetings/{id}/tasks",fixture.meeting().get("meetingId").asText()).header("Authorization","Bearer "+ownerToken)).andExpect(status().isNotFound());
        ownerToken=previous;
    }
    @Test void replacingInputAfterCompletedAnalysisClearsCurrentResults() throws Exception {
        owner();var fixture=start("openai");runner.runOnce();
        mvc.perform(patch("/api/v1/meetings/{id}/input",fixture.meeting().get("meetingId").asText()).header("Authorization","Bearer "+ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion",fixture.meeting().get("inputVersion").asLong(),"transcriptText","Không có công việc mới."))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/meetings/{id}/tasks",fixture.meeting().get("meetingId").asText()).param("analysisJobId",fixture.id().toString()).header("Authorization","Bearer "+ownerToken)).andExpect(status().isConflict());
        assertThat(jobs.result(fixture.id())).isPresent();
    }
    private JsonNode read(String value) throws Exception {return json.readTree(value);}
}
