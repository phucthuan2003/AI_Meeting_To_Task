package vn.aimtt.capture;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.llm.*;
import vn.aimtt.job.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CaptureWorkerTest {
    final ObjectMapper json=new ObjectMapper();final CaptureService store=mock(CaptureService.class);final SttProvider stt=mock(SttProvider.class);
    final LlmProvider llm=mock(LlmProvider.class);final UUID id=UUID.randomUUID();final CaptureService.Lease lease=new CaptureService.Lease(id,UUID.randomUUID());
    final CaptureService.Session session=new CaptureService.Session(id,UUID.randomUUID(),"LIVE","DRAINING","Test",LocalDate.of(2026,10,28),"Asia/Ho_Chi_Minh","gemini","gemini-3.8-flash",ExtractionPrompt.GEMINI_VERSION,ExtractionPrompt.SCHEMA_VERSION,"whisper-large-v3-turbo",1,1,-1,"[]","[]","{}",null,null,lease.token(),Instant.now().plusSeconds(600));
    CaptureWorker worker(){
        when(store.encode(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
        when(store.get(id)).thenReturn(session);when(store.sources(session)).thenReturn(List.of());when(store.events(session)).thenReturn(List.of());
        when(store.snapshot(session)).thenReturn(vn.aimtt.llm.LlmFixtures.job("gemini"));when(store.heartbeat(lease)).thenReturn(true);when(llm.id()).thenReturn("gemini");
        return new CaptureWorker(store,stt,List.of(llm),new ExtractionValidator(json),json);
    }
    @Test void transcribesOnceCheckpointsBeforeLlmAndPublishesOnlyValidatedOutput() throws Exception {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(new byte[]{1},"wav",15000,null));
        when(stt.transcribe(any(),any(),any(),any())).thenReturn(List.of(new SttProvider.Segment(0,2000,"Mai làm API.")));
        when(llm.extract(any(),anyString(),any(),any(),anyString())).thenAnswer(call->{
            verify(store).checkpointStt(eq(lease),anyList());
            var source=json.readTree(call.getArgument(1,String.class)).path("segments").get(0);
            String event="{\"events\":[{\"event_type\":\"CREATE\",\"task_ref\":\"task1\",\"sequence\":0,\"task_name\":\"Làm API\",\"assignee_raw\":\"Mai\",\"deadline_raw\":null,\"priority\":null,\"changed_fields\":[\"TASK\"],\"evidence_refs\":[{\"segment_id\":\"ID\",\"field\":\"TASK\"},{\"segment_id\":\"ID\",\"field\":\"ASSIGNEE\"}],\"ambiguities\":[]}]}".replace("ID",source.path("seq").asText());
            return new LlmProvider.Output(event,20L,30L,10);
        });worker.process(lease);
        verify(store).completeChunk(eq(lease),argThat(s->s.size()==1&&s.get(0).startMs()==15000),argThat(e->e.size()==1&&e.get(0).event().assignee_raw().equals("Mai")));
        verify(store,never()).fail(any(),any());
    }
    @Test void resumeUsesSttCheckpointAndInvalidAiDoesNotPublish() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(null,"wav",0,"[{\"startMs\":0,\"endMs\":1000,\"text\":\"Mai làm API.\"}]"));
        when(llm.extract(any(),anyString(),any(),any(),anyString())).thenReturn(new LlmProvider.Output("{\"events\":[{}]}",null,null,0));worker.process(lease);
        verifyNoInteractions(stt);verify(store).fail(lease,"PROVIDER_RESPONSE_INVALID");verify(store,never()).completeChunk(any(),any(),any());
    }
    @Test void sourceBudgetStopsBeforeLlm() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(null,"wav",0,"[{\"startMs\":0,\"endMs\":1000,\"text\":\""+"a".repeat(200001)+"\"}]"));
        worker.process(lease);verify(store).fail(lease,"TRANSCRIPT_OVER_BUDGET");verify(llm,never()).extract(any(),anyString(),any(),any(),anyString());
    }
}
