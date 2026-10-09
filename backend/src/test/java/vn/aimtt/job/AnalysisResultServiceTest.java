package vn.aimtt.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.common.ApiException;
import static vn.aimtt.job.JobFixture.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisResultServiceTest {
    final AnalysisJobStore store=mock(AnalysisJobStore.class);
    final AnalysisResultService service=new AnalysisResultService(store,new ObjectMapper());
    @Test void onlyCurrentOwnedCompletedResultCanBeReadAndOldJobResponseIsRejected() {
        when(store.currentOwned(OWNER,MEETING)).thenReturn(Optional.of(job(AnalysisJob.Status.COMPLETED,false,"gemini")));
        when(store.result(JOB)).thenReturn(Optional.of("{\"candidates\":[],\"warnings\":[]}"));
        assertThat(service.get(OWNER,MEETING,JOB).providerId()).isEqualTo("gemini");
        assertThatThrownBy(()->service.get(OWNER,MEETING,UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("STALE_VIEW"));
        UUID other=UUID.randomUUID();when(store.currentOwned(other,MEETING)).thenThrow(ApiException.notFound());
        assertThatThrownBy(()->service.get(other,MEETING,JOB)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
    @Test void queuedFailedOrAbsentAnalysisCannotPublishCandidateResults() {
        for (var status:List.of(AnalysisJob.Status.QUEUED,AnalysisJob.Status.FAILED,AnalysisJob.Status.CANCELLED)) {
            when(store.currentOwned(OWNER,MEETING)).thenReturn(Optional.of(job(status,false)));
            assertThatThrownBy(()->service.get(OWNER,MEETING,JOB)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ANALYSIS_NOT_READY"));
        }
        when(store.currentOwned(OWNER,MEETING)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.get(OWNER,MEETING,null)).isInstanceOf(ApiException.class);verify(store,never()).result(any());
    }
}
