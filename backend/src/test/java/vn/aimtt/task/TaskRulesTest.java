package vn.aimtt.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.AnalysisJobStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TaskRulesTest {
    final ObjectMapper json = new ObjectMapper();
    final TaskService service = new TaskService(mock(TaskStore.class), mock(AnalysisJobStore.class), json, mock(vn.aimtt.trello.DestinationService.class), mock(vn.aimtt.sync.SyncStore.class));
    final TaskStore.MeetingContext meeting = new TaskStore.MeetingContext(UUID.randomUUID(), 1, UUID.randomUUID(), LocalDate.of(2026, 10, 9), "Asia/Ho_Chi_Minh", null, "Planning");
    final TaskRow.Draft undecided = new TaskRow.Draft("Sửa login", null, "Long", null, "MISSING", "trước thứ Sáu", null, null, "Asia/Ho_Chi_Minh", "AMBIGUOUS", null, false, null);

    TaskRow.Draft apply(TaskRow.Draft base, String body) throws Exception { return service.apply(base, json.readTree(body), meeting); }
    TaskRow ai(TaskRow.Draft draft) {
        var suggestion = new HashMap<String, Object>(); suggestion.put("taskName", "Sửa login"); suggestion.put("assigneeRaw", "Long");
        suggestion.put("deadlineRaw", "trước thứ Sáu"); suggestion.put("priority", null); suggestion.put("dueLocal", null);
        return new TaskRow(UUID.randomUUID(), meeting.id(), UUID.randomUUID(), "AI", meeting.revisionId(), "t1", 0, draft.taskName(), draft.description(),
                draft.assigneeRaw(), draft.trelloMemberId(), draft.memberResolution(), draft.deadlineRaw(), draft.dueLocal(), draft.dueAt(), draft.timezone(),
                draft.deadlineResolution(), draft.priority(), suggestion, List.of(), List.of(), "PENDING_REVIEW", "NOT_SYNCED", 1, false, null, null,
                Instant.now(), Instant.now(), null, List.of(), null);
    }

    @Test void confirmedDateTimeStoresLocalAndUtcWithoutUsingUploadTime() throws Exception {
        var draft = apply(undecided, "{\"dueLocal\":\"2026-10-16T17:00\"}");
        assertThat(draft.deadlineResolution()).isEqualTo("RESOLVED");
        assertThat(draft.dueAt()).isEqualTo(Instant.parse("2026-10-16T10:00:00Z"));
        assertThat(draft.deadlineRaw()).isEqualTo("trước thứ Sáu");
        var moved = apply(draft, "{\"timezone\":\"Europe/London\"}");
        assertThat(moved.dueAt()).isEqualTo(Instant.parse("2026-10-16T16:00:00Z"));
    }

    @Test void absentKeepsAndNullClearsAndDecisionsAreExplicit() throws Exception {
        var none = apply(undecided, "{\"deadlineDecision\":\"NONE_SELECTED\",\"memberDecision\":\"NONE_SELECTED\"}");
        assertThat(none.deadlineResolution()).isEqualTo("NONE_SELECTED");
        assertThat(none.memberResolution()).isEqualTo("NONE_SELECTED");
        assertThat(none.taskName()).isEqualTo("Sửa login");
        var reset = apply(none, "{\"deadlineDecision\":null,\"memberDecision\":null,\"deadlineRaw\":null}");
        assertThat(reset.deadlineResolution()).isEqualTo("MISSING");
        assertThat(reset.memberResolution()).isEqualTo("MISSING");
        assertThatThrownBy(() -> apply(undecided, "{\"deadlineDecision\":\"NONE_SELECTED\",\"dueLocal\":\"2026-10-16T17:00\"}"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DEADLINE_DECISION_CONFLICT"));
        assertThatThrownBy(() -> apply(undecided, "{\"deadlineDecision\":\"AMBIGUOUS\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> apply(undecided, "{\"priority\":\"URGENT\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> apply(undecided, "{\"timezone\":\"Mars/Base\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> apply(undecided, "{\"dueLocal\":\"16/10/2026\"}")).isInstanceOf(ApiException.class);
    }

    @Test void resolvedDeadlineNeedsTimezone() throws Exception {
        var noZone = new TaskStore.MeetingContext(meeting.id(), 1, meeting.revisionId(), null, null, null, null);
        var base = new TaskRow.Draft("x", null, null, null, "MISSING", null, null, null, null, "MISSING", null, false, null);
        assertThatThrownBy(() -> service.apply(base, json.readTree("{\"dueLocal\":\"2026-10-16T17:00\"}"), noZone))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UNRESOLVED_DEADLINE"));
    }

    @Test void provenanceTracksOnlyValuesThatDifferFromTheAiProposal() throws Exception {
        var task = ai(undecided);
        assertThat(TaskService.editedFields(task, undecided)).isEmpty();
        var edited = apply(undecided, "{\"taskName\":\"Sửa Login API\",\"priority\":\"HIGH\",\"dueLocal\":\"2026-10-16T17:00\"}");
        assertThat(TaskService.editedFields(task, edited)).containsExactly("taskName", "priority", "dueLocal", "deadlineDecision");
    }

    @Test void warningsAreSpecificAndOnlyUndecidedFieldsBlock() {
        var now = Instant.parse("2026-10-09T03:00:00Z");
        var fresh = TaskWarnings.compute(ai(undecided), meeting.meetingDate(), now, false);
        assertThat(fresh).extracting(TaskWarnings.Warning::code).containsExactly("ASSIGNEE_NOT_RESOLVED_TO_MEMBER", "DEADLINE_NEEDS_CONFIRMATION");
        assertThat(fresh).allMatch(TaskWarnings.Warning::blocking);
        assertThat(fresh.get(1).message()).contains("trước thứ Sáu");
        var decided = new TaskRow.Draft("x", null, null, null, "NONE_SELECTED", null, LocalDateTime.of(2026, 10, 1, 9, 0),
                Instant.parse("2026-10-01T02:00:00Z"), "Asia/Ho_Chi_Minh", "RESOLVED", null, false, null);
        var past = TaskWarnings.compute(ai(decided), meeting.meetingDate(), now, true);
        assertThat(past).extracting(TaskWarnings.Warning::code).containsExactly("DEADLINE_BEFORE_MEETING", "DEADLINE_IN_PAST", "SOURCE_UNAVAILABLE");
        assertThat(past).noneMatch(TaskWarnings.Warning::blocking);
        var missing = new TaskRow.Draft("x", null, null, null, "MISSING", null, null, null, null, "MISSING", null, false, null);
        assertThat(TaskWarnings.compute(ai(missing), null, now, false)).extracting(TaskWarnings.Warning::code).containsExactly("ASSIGNEE_MISSING", "DEADLINE_MISSING");
    }
}
