package vn.aimtt.trello;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TrelloRulesTest {
    final List<TrelloClient.Member> members = List.of(new TrelloClient.Member("1", "mainguyen", "Nguyễn Thị Mai"),
            new TrelloClient.Member("2", "longtran", "Trần Long"), new TrelloClient.Member("3", "longpham", "Phạm Long"),
            new TrelloClient.Member("4", "duc.do", "Đỗ Đức"));

    @Test void nameMatchesAreSuggestionsOnlyAndDuplicatesAreAmbiguous() {
        assertThat(MemberMatcher.match("Mai", members).resolution()).isEqualTo("SUGGESTED");
        assertThat(MemberMatcher.match("Long", members).resolution()).isEqualTo("AMBIGUOUS");
        assertThat(MemberMatcher.match("Long", members).candidates()).hasSize(2);
        assertThat(MemberMatcher.match("Trần Long", members).candidates()).extracting(TrelloClient.Member::id).containsExactly("2");
        assertThat(MemberMatcher.match("@duc.do", members).candidates()).extracting(TrelloClient.Member::id).containsExactly("4");
        assertThat(MemberMatcher.match("duc", members).resolution()).isEqualTo("SUGGESTED");   // diacritics + đ folded
        assertThat(MemberMatcher.match("Hùng", members).resolution()).isEqualTo("MISSING");
        assertThat(MemberMatcher.match("  ", members).resolution()).isEqualTo("MISSING");
    }

    @Test void deadlineSuggestionsUseMeetingDateNeverToday() {
        LocalDate friday = LocalDate.of(2026, 10, 9);
        assertThat(DeadlineSuggester.suggest("17:00 ngày 12/10/2026", null).orElseThrow().dueLocal()).isEqualTo("2026-10-12T17:00");
        assertThat(DeadlineSuggester.suggest("ngày mai", friday).orElseThrow().dueLocal()).isEqualTo("2026-10-10T17:00");
        assertThat(DeadlineSuggester.suggest("ngày mai", null)).isEmpty();                      // AT07: relative without meeting date
        var beforeFriday = DeadlineSuggester.suggest("trước thứ Sáu", friday).orElseThrow();
        assertThat(beforeFriday.dueLocal()).isEqualTo("2026-10-16T17:00");
        assertThat(beforeFriday.note()).contains("trước").contains("17:00");
        assertThat(DeadlineSuggester.suggest("thứ 2 tuần sau 9h", friday).orElseThrow().dueLocal()).isEqualTo("2026-10-12T09:00");
        assertThat(DeadlineSuggester.suggest("by Monday 5pm", friday).orElseThrow().dueLocal()).isEqualTo("2026-10-12T17:00");
        assertThat(DeadlineSuggester.suggest("15/10", friday).orElseThrow().dueLocal()).isEqualTo("2026-10-15T17:00");
        assertThat(DeadlineSuggester.suggest("cuối tuần", friday)).isEmpty();
        assertThat(DeadlineSuggester.suggest("sớm nhất có thể", friday)).isEmpty();
        assertThat(DeadlineSuggester.suggest("31/02/2026", friday)).isEmpty();
    }

    @Test void urlsMustBeHttpsExceptExplicitLoopbackDevelopment() {
        assertThatThrownBy(() -> TrelloProperties.checkUrl("http://api.trello.com/1", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrelloProperties.checkUrl("http://127.0.0.1:9999/1", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrelloProperties.checkUrl("https://user:pass@api.trello.com/1", false)).isInstanceOf(IllegalArgumentException.class);
        TrelloProperties.checkUrl("http://127.0.0.1:9999/1", true);
        TrelloProperties.checkUrl("https://api.trello.com/1", false);
    }

    @Test void pkceChallengeIsS256OfVerifier() {
        // RFC 7636 appendix B example.
        assertThat(TrelloConnectionService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test void trelloIdsAreValidatedBeforeBecomingUrlSegments() {
        assertThat(TrelloClient.id("b0000000000000000000000a")).isEqualTo("b0000000000000000000000a");
        assertThatThrownBy(() -> TrelloClient.id("../members")).isInstanceOf(TrelloClient.TrelloException.class);
        assertThatThrownBy(() -> TrelloClient.id("abc?x=1")).isInstanceOf(TrelloClient.TrelloException.class);
    }
}
