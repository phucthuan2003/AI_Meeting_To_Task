package vn.aimtt.capture;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CaptureWorkerTest {
    final ObjectMapper json=new ObjectMapper();final CaptureService store=mock(CaptureService.class);final SttProvider stt=mock(SttProvider.class);
    final UUID id=UUID.randomUUID(),owner=UUID.randomUUID();final CaptureService.Lease lease=new CaptureService.Lease(id,UUID.randomUUID());
    CaptureService.Session session(String mode,String status,int next,int processed){
        return new CaptureService.Session(id,owner,mode,status,"Test",LocalDate.of(2026,10,28),"Asia/Ho_Chi_Minh","gemini","gemini-3.5-flash-lite","p","s",
                "meeting-intelligence",1,next,processed,"[]","[]","{}",null,null,lease.token(),Instant.now().plusSeconds(600));
    }
    final CaptureService.Session session=session("LIVE","DRAINING",1,-1);
    CaptureWorker worker(){
        when(store.encode(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
        when(store.get(id)).thenReturn(session);when(store.sources(any())).thenReturn(List.of());when(store.heartbeat(lease)).thenReturn(true);
        var worker=new CaptureWorker(store,stt,json);worker.retryBaseMs=1;return worker;
    }
    @Test void liveChunkIsTranscribedAndCheckpointedWithoutAnyLanguageModelCall() throws Exception {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(new byte[]{1},"wav",30000,null));
        when(stt.transcribe(any(),any(),any(),any())).thenReturn(List.of(new SttProvider.Segment(0,2000,"Mai làm API.")));
        worker.process(lease);
        verify(store).checkpointStt(eq(lease),anyList());
        verify(store).completeChunk(eq(lease),argThat(s->s.size()==1&&s.get(0).startMs()==30000&&s.get(0).text().equals("Mai làm API.")));
        verify(store,never()).fail(any(),any());
    }
    @Test void resumeUsesSttCheckpoint() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(null,"wav",0,"[{\"startMs\":0,\"endMs\":1000,\"text\":\"Mai làm API.\"}]"));
        worker.process(lease);
        verifyNoInteractions(stt);verify(store).completeChunk(eq(lease),argThat(s->s.size()==1));
    }
    @Test void sourceBudgetFailsTheChunk() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(null,"wav",0,"[{\"startMs\":0,\"endMs\":1000,\"text\":\""+"a".repeat(200001)+"\"}]"));
        worker.process(lease);verify(store).fail(lease,"TRANSCRIPT_OVER_BUDGET");
    }
    @Test void emptyTranscriptCompletesWithoutSources() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(new byte[]{1},"wav",0,null));
        when(stt.transcribe(any(),any(),any(),any())).thenReturn(List.of());
        worker.process(lease);
        verify(store).checkpointStt(lease,List.of());verify(store).completeChunk(lease,List.of());verify(store,never()).fail(any(),any());
    }
    @Test void transientSttErrorsAreRetriedWithoutFailingTheSession() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(new byte[]{1},"wav",0,null));
        when(stt.transcribe(any(),any(),any(),any())).thenThrow(new JobFailure("STT_REQUEST_FAILED",true))
                .thenThrow(new JobFailure("PROVIDER_CONNECTION_ERROR",true)).thenReturn(List.of(new SttProvider.Segment(0,1000,"Chào.")));
        worker.process(lease);
        verify(stt,times(3)).transcribe(any(),any(),any(),any());
        verify(store,never()).fail(any(),any());verify(store).completeChunk(eq(lease),anyList());
    }
    @Test void retriesAreBoundedAndNonTransientErrorsFailImmediately() {
        var worker=worker();when(store.chunk(session)).thenReturn(new CaptureService.Chunk(new byte[]{1},"wav",0,null));
        when(stt.transcribe(any(),any(),any(),any())).thenThrow(new JobFailure("STT_REQUEST_FAILED",true));
        worker.process(lease);
        verify(stt,times(CaptureWorker.MAX_RETRIES+1)).transcribe(any(),any(),any(),any());verify(store).fail(lease,"STT_REQUEST_FAILED");
        reset(stt);when(stt.transcribe(any(),any(),any(),any())).thenThrow(new JobFailure("STT_AUTH_ERROR",false));
        worker.process(lease);
        verify(stt,times(1)).transcribe(any(),any(),any(),any());verify(store).fail(lease,"STT_AUTH_ERROR");
    }
    @Test void drainedLiveSessionIsPublishedAutomaticallyButFileSessionIsNot() {
        var worker=worker();var ready=session("LIVE","READY",1,0);
        when(store.get(id)).thenReturn(session("LIVE","DRAINING",1,0),ready);
        worker.process(lease);
        verify(store).drained(lease);verify(store).publish(owner,id);
        reset(store);when(store.heartbeat(lease)).thenReturn(true);when(store.get(id)).thenReturn(session("FILE","DRAINING",1,0));
        worker.process(lease);
        verify(store).drained(lease);verify(store,never()).publish(any(),any());
    }
    @Test void autoPublishWithNothingToSaveLeavesTheSessionAlone() {
        var worker=worker();when(store.get(id)).thenReturn(session("LIVE","DRAINING",1,0),session("LIVE","READY",1,0));
        when(store.publish(owner,id)).thenThrow(vn.aimtt.common.ApiException.invalid("empty"));
        worker.process(lease);
        verify(store,never()).fail(any(),any());
    }
    @Test void pollDrainsEveryPendingChunkInOneTick() {
        var worker=worker();when(store.claim()).thenReturn(Optional.of(lease),Optional.of(lease),Optional.empty());
        when(store.chunk(session)).thenReturn(new CaptureService.Chunk(null,"wav",0,"[]"));
        worker.poll();
        verify(store,times(2)).completeChunk(eq(lease),anyList());verify(store,times(3)).claim();
    }
}
