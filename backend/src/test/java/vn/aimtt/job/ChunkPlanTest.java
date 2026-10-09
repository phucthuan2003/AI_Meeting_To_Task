package vn.aimtt.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.llm.*;
import static org.assertj.core.api.Assertions.*;

class ChunkPlanTest {
    final ObjectMapper json = new ObjectMapper();
    AnalysisPipeline pipeline(int bytes, int overlap) {
        return new AnalysisPipeline(List.of(), LlmProperties.defaults(), json, new ExtractionValidator(json), new ChunkingProperties(true, bytes, overlap, 50, 512), null);
    }
    List<Extraction.Source> source(int n, int length) {
        var list = new ArrayList<Extraction.Source>();
        for (int i = 0; i < n; i++) list.add(new Extraction.Source(UUID.randomUUID(), i, "x".repeat(length)));
        return list;
    }

    @Test void partsCoverEverySegmentInOrderWithBoundedOverlapAndAlwaysProgress() {
        var plan = pipeline(2600, 2).plan(source(40, 100));
        assertThat(plan.size()).isGreaterThan(2);
        assertThat(plan.get(0).firstSequence()).isZero();
        assertThat(plan.get(0).overlapUntil()).isEqualTo(-1);
        for (int i = 1; i < plan.size(); i++) {
            var prev = plan.get(i - 1); var cur = plan.get(i);
            assertThat(cur.overlapUntil()).isEqualTo(prev.lastSequence());          // overlap ends where the previous part ended
            assertThat(prev.lastSequence() - cur.firstSequence() + 1).isBetween(0, 2); // at most N overlap segments
            assertThat(cur.lastSequence()).isGreaterThan(prev.lastSequence());       // progress
        }
        assertThat(plan.get(plan.size() - 1).lastSequence()).isEqualTo(39);
    }

    @Test void oneSegmentLargerThanAPartIsRejectedNotTruncated() {
        assertThatThrownBy(() -> pipeline(2600, 2).plan(source(3, 5000))).isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.code()).isEqualTo("TRANSCRIPT_OVER_BUDGET"));
    }

    @Test void overlapDuplicatesAreDroppedAndRemappedByTaskEvidence() {
        UUID seg = UUID.randomUUID(), later = UUID.randomUUID();
        var create = new Extraction.Event("CREATE", "docs", 5, "Viết tài liệu", null, null, null, List.of("TASK"), List.of(new Extraction.Evidence(seg.toString(), "TASK")), List.of());
        var earlier = List.of(new ExtractionValidator.Parsed(create, List.of()));
        var again = new Extraction.Event("CREATE", "docs-2", 5, "Viết tài liệu", null, null, null, List.of("TASK"), List.of(new Extraction.Evidence(seg.toString(), "TASK")), List.of());
        var update = new Extraction.Event("UPDATE", "docs-2", 9, null, "Mai", null, null, List.of("ASSIGNEE"), List.of(new Extraction.Evidence(later.toString(), "ASSIGNEE")), List.of());
        var result = AnalysisPipeline.dedupe(List.of(new ExtractionValidator.Parsed(again, List.of()), new ExtractionValidator.Parsed(update, List.of())), earlier, 6);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).event().task_ref()).isEqualTo("docs");
        assertThat(result.get(0).event().event_type()).isEqualTo("UPDATE");
    }
}
