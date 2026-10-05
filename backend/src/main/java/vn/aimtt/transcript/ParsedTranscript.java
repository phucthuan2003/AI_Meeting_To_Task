package vn.aimtt.transcript;

import java.util.List;
import java.util.Map;

public record ParsedTranscript(String sourceType, String rawText, String normalizedText,
                               List<Segment> segments, List<String> warnings) {
    public record Segment(int sequence, String speaker, String timestamp, String text,
                          int normalizedStart, int normalizedEnd, Map<String, Object> sourceLocator) {}
}
