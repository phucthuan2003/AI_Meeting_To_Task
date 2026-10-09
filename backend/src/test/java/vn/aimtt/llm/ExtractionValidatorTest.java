package vn.aimtt.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.JobFailure;
import static vn.aimtt.llm.LlmFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ExtractionValidatorTest {
    final ExtractionValidator validator = new ExtractionValidator(new ObjectMapper());
    final List<Extraction.Source> source = List.of(new Extraction.Source(SEGMENT,0,TEXT));
    Extraction.Result result(String value) { return validator.validate(job("openai"),source,new LlmProvider.Output(value,42L,50L,10)); }
    @Test void sourceBackedCandidateUsesServerQuotesAndExplicitTimeInMeetingTimezone() {
        var result=result(output()); assertThat(result.candidates()).hasSize(1);
        var task=result.candidates().get(0); assertThat(task.assigneeRaw()).isEqualTo("Mai");
        assertThat(task.dueAt()).isEqualTo("2026-10-12T10:00:00Z"); assertThat(task.dueLocal()).isEqualTo("2026-10-12T17:00");
        assertThat(task.evidence()).allMatch(q->q.quote().equals(TEXT)); assertThat(task.needsConfirmation()).isTrue();
        assertThat(task.reviewStatus()).isEqualTo("PENDING_REVIEW"); assertThat(task.priority()).isNull();
    }
    @Test void emptyResultIsValidAndMissingFieldsRemainMissingRatherThanInvented() {
        assertThat(result("{\"events\":[]}").candidates()).isEmpty();
        var output="{\"events\":["+event().replace("\"Mai\"","null").replace("\"17:00 ngày 12/10/2026\"","null")+"]}";
        var task=result(output).candidates().get(0);
        assertThat(task.assigneeRaw()).isNull(); assertThat(task.deadlineRaw()).isNull(); assertThat(task.dueAt()).isNull();
        assertThat(task.warnings()).contains("ASSIGNEE_MISSING","DEADLINE_MISSING");
    }
    @Test void schemaViolationsDuplicateKeysTrailingTextAndUnknownEvidenceAreRejected() {
        for(String bad:List.of("", "null", output()+" trailing", "{\"events\":[],\"events\":[]}",output().replace("\"events\":","\"unknown\":true,\"events\":"),
                output().replace(SEGMENT.toString(),UUID.randomUUID().toString()),output().replace("\"Mai\"","\"Invented person\""),output().replace("\"17:00 ngày 12/10/2026\"","\"Invented date\""),
                output().replace("\"sequence\":0","\"sequence\":9"),output().replace("\"priority\":null","\"priority\":\"URGENT\"")))
            assertThatThrownBy(()->result(bad)).isInstanceOfSatisfying(JobFailure.class,e->assertThat(e.code()).isEqualTo("PROVIDER_RESPONSE_INVALID"));
    }
    @Test void updateAndCancelEventsReconcileInSourceOrderAndOrphansAreWarnings() {
        UUID second=UUID.randomUUID(),third=UUID.randomUUID();
        var sources=List.of(source.get(0),new Extraction.Source(second,1,"Nam: đổi người phụ trách sang Huy, bỏ hạn."),new Extraction.Source(third,2,"Nam: hủy công việc đăng nhập."));
        String update=event("UPDATE","task-1",1,second,"[\"ASSIGNEE\",\"DEADLINE\"]","null","\"Huy\"","null");
        String cancel=event("CANCEL","task-1",2,third,"[]","null","null","null");
        var changed=validator.validate(job("openai"),sources,new LlmProvider.Output("{\"events\":["+event()+","+update+"]}",0L,0L,0));
        assertThat(changed.candidates().get(0).assigneeRaw()).isEqualTo("Huy"); assertThat(changed.candidates().get(0).deadlineRaw()).isNull();
        var cancelled=validator.validate(job("openai"),sources,new LlmProvider.Output("{\"events\":["+event()+","+update+","+cancel+"]}",0L,0L,0));
        assertThat(cancelled.candidates()).isEmpty(); assertThat(cancelled.events()).hasSize(3);
        var orphan=validator.validate(job("openai"),sources,new LlmProvider.Output("{\"events\":["+cancel+"]}",0L,0L,0));
        assertThat(orphan.warnings()).contains("UNRESOLVED_CANCEL: task-1");
    }
    @Test void ambiguousDeadlineRemainsRawWithoutDefaultDateOrTime() {
        String raw=output().replace("17:00 ngày 12/10/2026","thứ Sáu");
        var result=validator.validate(job("openai"),List.of(new Extraction.Source(SEGMENT,0,TEXT.replace("17:00 ngày 12/10/2026","thứ Sáu"))),new LlmProvider.Output(raw,0L,0L,0));
        assertThat(result.candidates().get(0).dueAt()).isNull(); assertThat(result.candidates().get(0).warnings()).contains("DEADLINE_AMBIGUOUS");
    }
}
