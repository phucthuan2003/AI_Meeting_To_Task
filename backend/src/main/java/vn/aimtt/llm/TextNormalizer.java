package vn.aimtt.llm;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One normalisation for text sent to a model and for the verbatim checks on its answer. The model sees the compact
 * form, so a phrase it copies is compared against the same compact form of the stored segment (never the raw bytes).
 */
public final class TextNormalizer {
    private TextNormalizer() {}
    // Zero-width/format characters, BOM and C0/C1 controls (except whitespace, collapsed below).
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD\\p{Cc}&&[^\\t\\n\\r\\f\\u000B]]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    /** NFC, invisible characters removed, every whitespace run collapsed to one space, trimmed. Meaning is unchanged. */
    public static String forModel(String text) {
        if (text == null) return null;
        String value = Normalizer.normalize(text, Normalizer.Form.NFC);
        value = INVISIBLE.matcher(value).replaceAll("");
        return SPACES.matcher(value).replaceAll(" ").strip();
    }
    /** Case-insensitive comparison key; Vietnamese diacritics are kept because they change meaning. */
    public static String key(String text) { return text == null ? null : forModel(text).toLowerCase(Locale.ROOT); }
    /** True when {@code phrase} occurs in {@code text} after both are normalised the same way. */
    public static boolean contains(String text, String phrase) {
        if (text == null || phrase == null) return false;
        String needle = key(phrase);
        return !needle.isEmpty() && key(text).contains(needle);
    }
}
