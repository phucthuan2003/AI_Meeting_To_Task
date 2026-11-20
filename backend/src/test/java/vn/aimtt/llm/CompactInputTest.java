package vn.aimtt.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompactInputTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID first = UUID.fromString("00000000-0000-4000-8000-000000000010"), second = UUID.fromString("00000000-0000-4000-8000-000000000011");
    final List<Extraction.Source> source = List.of(new Extraction.Source(first, 0, "Nam:  Mai​ làm   login\n trước thứ Sáu."),
            new Extraction.Source(second, 1, "Mai: OK."));

    @Test void normalizerKeepsMeaningButDropsInvisibleAndRepeatedWhitespace() {
        assertThat(TextNormalizer.forModel(source.get(0).text())).isEqualTo("Nam: Mai làm login trước thứ Sáu.");
        // NFD (decomposed accents, e.g. pasted from macOS) and NFC compare equal; case is ignored, accents are not.
        String nfd = java.text.Normalizer.normalize("trước thứ Sáu", java.text.Normalizer.Form.NFD);
        assertThat(TextNormalizer.contains(source.get(0).text(), nfd)).isTrue();
        assertThat(TextNormalizer.contains(source.get(0).text(), "TRƯỚC THỨ SÁU")).isTrue();
        assertThat(TextNormalizer.contains(source.get(0).text(), "truoc thu Sau")).isFalse();
        assertThat(TextNormalizer.contains(source.get(0).text(), " ")).isFalse();
    }
    @Test void segmentsAreSentWithoutUuidsAndAreMuchSmaller() throws Exception {
        String compact = json.writeValueAsString(CompactSource.segments(json, source));
        String legacy = json.writeValueAsString(source);
        assertThat(compact).doesNotContain(first.toString()).contains("\"seq\":0", "\"seq\":1");
        assertThat(compact.length()).isLessThan(legacy.length() * 2 / 3);
    }
    @Test void sequenceReferencesAreMappedBackToUuidsAndUnknownOnesAreLeftForTheValidator() throws Exception {
        String output = """
                {"events":[{"evidence_refs":[{"segment_id":"0","field":"TASK"},{"segment_id":"s1","field":"ASSIGNEE"},{"segment_id":1,"field":"DEADLINE"},{"segment_id":"9","field":"TASK"}]}]}""";
        var restored = json.readTree(CompactSource.restoreIds(json, new LlmProvider.Output(output, 1L, 2L, 3), source).json());
        var refs = restored.at("/events/0/evidence_refs");
        assertThat(refs.get(0).get("segment_id").asText()).isEqualTo(first.toString());
        assertThat(refs.get(1).get("segment_id").asText()).isEqualTo(second.toString());
        assertThat(refs.get(2).get("segment_id").asText()).isEqualTo(second.toString());
        assertThat(refs.get(3).get("segment_id").asText()).isEqualTo("9");
        var broken = new LlmProvider.Output("not json", 1L, 2L, 3);
        assertThat(CompactSource.restoreIds(json, broken, source)).isSameAs(broken);
    }
    @Test void compactAnswerPassesTheUnchangedValidatorIncludingWhitespaceAndCaseDifferences() {
        String output = """
                {"events":[{"event_type":"CREATE","task_ref":"t1","sequence":0,"task_name":"Làm login","assignee_raw":"mai","deadline_raw":"trước thứ Sáu",
                "priority":null,"changed_fields":["TASK"],"evidence_refs":[{"segment_id":"0","field":"TASK"},{"segment_id":"0","field":"ASSIGNEE"},
                {"segment_id":"0","field":"DEADLINE"}],"ambiguities":[]}]}""";
        var restored = CompactSource.restoreIds(json, new LlmProvider.Output(output, 1L, 2L, 3), source);
        var parsed = new ExtractionValidator(json).parse(source, restored, Set.of());
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).quotes()).allMatch(q -> q.segmentId().equals(first));
    }
    @Test void computedDatesAreStillRejected() {
        String output = """
                {"events":[{"event_type":"CREATE","task_ref":"t1","sequence":0,"task_name":"Làm login","assignee_raw":null,"deadline_raw":"21/11/2026",
                "priority":null,"changed_fields":["TASK"],"evidence_refs":[{"segment_id":"0","field":"TASK"},{"segment_id":"0","field":"DEADLINE"}],"ambiguities":[]}]}""";
        var restored = CompactSource.restoreIds(json, new LlmProvider.Output(output, 1L, 2L, 3), source);
        assertThatThrownBy(() -> new ExtractionValidator(json).parse(source, restored, Set.of())).isInstanceOf(vn.aimtt.job.JobFailure.class);
    }
}
