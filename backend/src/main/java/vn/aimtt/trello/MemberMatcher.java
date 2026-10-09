package vn.aimtt.trello;

import java.text.Normalizer;
import java.util.*;

/**
 * Suggests Trello members for a spoken name (SDS §5.11). A name match is only ever a suggestion:
 * one match → SUGGESTED, several → AMBIGUOUS, none → MISSING. Only a user-confirmed alias/ID becomes RESOLVED.
 */
public final class MemberMatcher {
    private MemberMatcher() {}
    public record Outcome(String resolution, List<TrelloClient.Member> candidates) {}

    public static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9@._ -]+", " ").trim().replaceAll("\\s+", " ");
    }
    private static List<String> tokens(String value) {
        return Arrays.stream(normalize(value).split("[ ._-]+")).filter(t -> !t.isBlank() && !t.equals("@")).toList();
    }

    public static Outcome match(String spoken, List<TrelloClient.Member> members) {
        var wanted = tokens(spoken.replaceFirst("^@", ""));
        if (wanted.isEmpty()) return new Outcome("MISSING", List.of());
        String handle = normalize(spoken.replaceFirst("^@", ""));
        var matches = new ArrayList<TrelloClient.Member>();
        for (var member : members) {
            if (member.username() != null && normalize(member.username()).equals(handle)) { matches.add(member); continue; }
            var name = new HashSet<>(tokens(member.fullName()));
            name.addAll(tokens(member.username()));
            if (name.containsAll(wanted)) matches.add(member);
        }
        if (matches.isEmpty()) return new Outcome("MISSING", List.of());
        return new Outcome(matches.size() == 1 ? "SUGGESTED" : "AMBIGUOUS", List.copyOf(matches));
    }
}
