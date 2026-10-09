package vn.aimtt.job;

import java.nio.charset.StandardCharsets;
import java.util.List;
import vn.aimtt.job.AnalysisJob.SourceSegment;

public final class PreparationBudget {
    private PreparationBudget() {}
    // Conservative UTF-8-byte upper bound for future byte-based tokenizers; not a model tokenizer.
    // JSON quoting and delimiters are included. The real adapter must estimate its complete prompt again.
    public static long tokens(List<SourceSegment> segments) {
        long bytes = 0;
        for (var segment : segments) {
            bytes += 64 + Integer.toString(segment.sequence()).length();
            for (int offset = 0; offset < segment.text().length();) {
                int cp = segment.text().codePointAt(offset);
                offset += Character.charCount(cp);
                bytes += cp < 0x20 || (cp >= 0xd800 && cp <= 0xdfff) ? 6 : cp == '"' || cp == '\\' ? 2
                        : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return bytes;
    }
    public static long metadataTokens(AnalysisJob job) {
        String metadata = String.valueOf(job.title()) + String.valueOf(job.meetingDate()) + String.valueOf(job.timezone());
        // Each UTF-8 byte might require escaping; safe for this development preflight.
        return 128L + 6L * metadata.getBytes(StandardCharsets.UTF_8).length;
    }
    public static boolean exceeds(AnalysisJob job, long inputTokens) {
        return inputTokens + metadataTokens(job) + job.reservedTokens() > job.contextTokens();
    }
}
