package vn.aimtt.sync;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.task.*;
import vn.aimtt.trello.DestinationService;
import static org.assertj.core.api.Assertions.*;

class SyncRulesTest {
    final UUID taskId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    final DestinationService.Destination destination = new DestinationService.Destination(UUID.randomUUID(), UUID.randomUUID(), "me", "board0000001", "Board",
            "list00000001", "To Do", List.of(new DestinationService.MemberView("member000001", "mai", "Nguyễn Thị Mai")), Instant.now(), 3);

    TaskRow task(String member, String resolution, boolean evidence, String priority) {
        return new TaskRow(taskId, UUID.randomUUID(), UUID.randomUUID(), "AI", UUID.randomUUID(), "t1", 0, "Sửa login", "Chi tiết", "Mai", member, resolution,
                "trước thứ Sáu", LocalDateTime.of(2026, 10, 16, 17, 0), Instant.parse("2026-10-16T10:00:00Z"), "Asia/Ho_Chi_Minh", "RESOLVED", priority,
                Map.of(), List.of(), List.of(), "PENDING_REVIEW", "NOT_SYNCED", 4, evidence, null, null, Instant.now(), Instant.now(), null, List.of(), 3L);
    }

    @Test void payloadCarriesMarkerPriorityAndOnlyOptInEvidence() {
        var evidence = List.of(new TaskStore.Evidence(taskId, UUID.randomUUID(), 0, "TASK", "Nam: Mai sửa login trước thứ Sáu.", null, null, null, true));
        var unassigned = CardPayloads.build(task(null, "NONE_SELECTED", false, "HIGH"), "Planning", LocalDate.of(2026, 10, 9), destination, evidence);
        assertThat(unassigned.idMembers()).isEmpty();
        assertThat(unassigned.desc()).contains("Chi tiết", "Người phụ trách (theo cuộc họp, chưa giao trên Trello): Mai", "Ưu tiên: Cao",
                "Cuộc họp: Planning (09/10/2026)", "AI_MTT_REF=" + taskId).doesNotContain("Trích dẫn");
        assertThat(unassigned.due()).isEqualTo("2026-10-16T10:00:00Z");
        var assigned = CardPayloads.build(task("member000001", "RESOLVED", true, null), null, null, destination, evidence);
        assertThat(assigned.idMembers()).containsExactly("member000001");
        assertThat(assigned.memberName()).isEqualTo("Nguyễn Thị Mai");
        assertThat(assigned.desc()).doesNotContain("Người phụ trách").contains("> Nam: Mai sửa login trước thứ Sáu.");
    }

    @Test void markerMustMatchExactlyAndCardReferencesAreParsedStrictly() {
        String marker = CardPayloads.marker(taskId);
        assertThat(Reconciler.containsMarker("abc\n" + marker, marker)).isTrue();
        assertThat(Reconciler.containsMarker("x " + marker + "0", marker)).isFalse();
        assertThat(Reconciler.containsMarker("x " + marker + "-1", marker)).isFalse();
        assertThat(SyncActions.parseCardRef("https://trello.com/c/AbCd1234/12-sua-login")).isEqualTo("AbCd1234");
        assertThat(SyncActions.parseCardRef("AbCd1234")).isEqualTo("AbCd1234");
        assertThatThrownBy(() -> SyncActions.parseCardRef("javascript:alert(1)")).isInstanceOf(vn.aimtt.common.ApiException.class);
    }

    @Test void unknownNeverOffersRetryAndRecreateOnlyAfterReconcileMiss() {
        assertThat(SyncErrors.actions("UNKNOWN", "DISPATCH_TIMEOUT", false, 0)).doesNotContain("RETRY", "RECREATE");
        assertThat(SyncErrors.actions("UNKNOWN", "NOT_FOUND_YET", false, 1)).contains("RECREATE");
        assertThat(SyncErrors.actions("UNKNOWN", "DUPLICATE_DETECTED", false, 2)).doesNotContain("RECREATE");
        assertThat(SyncErrors.actions("FAILED", "TRELLO_UNAVAILABLE", true, 0)).contains("RETRY");
        assertThat(SyncErrors.actions("FAILED", "TRELLO_REJECTED", false, 0)).containsExactly("EDIT");
    }
}
