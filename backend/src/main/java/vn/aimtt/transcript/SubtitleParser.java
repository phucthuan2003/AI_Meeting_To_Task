package vn.aimtt.transcript;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import vn.aimtt.common.ApiException;

/** Cue parser for UTF-8 VTT/SRT. Keeps source offsets; never infers a speaker from audio. */
final class SubtitleParser {
    private static final Pattern LINE = Pattern.compile("([^\\r\\n]*)(?:\\r\\n|\\r|\\n|$)");
    private static final Pattern TIME = Pattern.compile("(?:(\\d{2,}):)?([0-5]\\d):([0-5]\\d)[.,](\\d{3})");
    private static final Pattern VOICE = Pattern.compile("<v(?:\\.[^\\s>]+)*[ \\t]+([^>]+)>");
    private record Line(String text, int start, int end) {}
    private record Stamp(long millis, String text) {}
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SUBTITLE", "Phụ đề không đúng cấu trúc cue/timestamp VTT hoặc SRT.");
    }
    private static Stamp stamp(String value, boolean vtt) {
        var m = TIME.matcher(value);
        if (!m.matches() || (!vtt && (m.group(1) == null || !value.contains(","))) || (vtt && !value.contains("."))) throw invalid();
        try {
            long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            if (hours > 999) throw invalid(); // also fits timestamp_raw VARCHAR(16)
            long ms = ((hours * 60 + Integer.parseInt(m.group(2))) * 60 + Integer.parseInt(m.group(3))) * 1000 + Integer.parseInt(m.group(4));
            return new Stamp(ms, "%02d:%02d:%02d.%03d".formatted(hours, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        } catch (NumberFormatException e) { throw invalid(); }
    }
    private static String plain(String value) {
        // Only subtitle markup is removed. Unknown text is retained and rendered as text by the UI.
        return Normalizer.normalize(value.replaceAll("</?(?:v|c|b|i|u|ruby|rt|lang)(?:[ .][^>]*)?>", "")
                .replaceAll("<(?:(?:\\d{2,}:)?[0-5]\\d:[0-5]\\d\\.\\d{3})>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replace("&lrm;", "").replace("&rlm;", "").replace("&amp;", "&"), Normalizer.Form.NFC)
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .replaceAll("[\\p{Zs}\\t\\r\\n]+", " ").strip();
    }
    static ParsedTranscript parse(String raw, boolean vtt, int maxChars) {
        var lines = new ArrayList<Line>(); var matcher = LINE.matcher(raw);
        while (matcher.find()) if (matcher.start() < raw.length()) lines.add(new Line(matcher.group(1), matcher.start(), matcher.start() + matcher.group(1).length()));
        int i = 0;
        if (vtt) {
            if (lines.isEmpty() || !lines.get(0).text().matches("WEBVTT(?:[ \\t].*)?")) throw invalid();
            i = 1;
            while (i < lines.size() && !lines.get(i).text().isBlank()) {
                if (lines.get(i).text().contains("-->")) throw invalid();
                i++;
            }
        }
        var segments = new ArrayList<ParsedTranscript.Segment>(); var normalized = new StringBuilder();
        long previous = -1; int cue = 0; boolean skipped = false;
        while (i < lines.size()) {
            if (lines.get(i).text().isBlank()) { i++; continue; }
            int begin = i;
            while (i < lines.size() && !lines.get(i).text().isBlank()) i++;
            int end = i;
            String first = lines.get(begin).text();
            if (vtt && (first.matches("NOTE(?:[ \\t].*)?") || first.equals("STYLE") || first.equals("REGION"))) { skipped = true; continue; }
            int timing = first.contains("-->") ? begin : begin + 1;
            if (timing >= end || (!vtt && (timing != begin + 1 || !first.matches("\\d+")))) throw invalid();
            var parts = lines.get(timing).text().strip().split("[ \\t]+-->[ \\t]+", -1);
            if (parts.length != 2) throw invalid();
            var finishAndSettings = parts[1].split("[ \\t]+", 2);
            if (!vtt && finishAndSettings.length != 1) throw invalid();
            Stamp start = stamp(parts[0], vtt), finish = stamp(finishAndSettings[0], vtt);
            if (finish.millis() <= start.millis() || start.millis() < previous || timing + 1 >= end) throw invalid();
            previous = start.millis(); cue++;
            int from = lines.get(timing + 1).start(), to = lines.get(end - 1).end();
            String payload = raw.substring(from, to);
            if (payload.contains("-->")) throw invalid();
            var voices = new LinkedHashSet<String>(); var voice = VOICE.matcher(payload);
            while (voice.find()) voices.add(plain(voice.group(1)));
            String speaker = voices.size() == 1 ? voices.iterator().next() : null;
            if (speaker != null && speaker.isBlank()) speaker = null;
            String text = plain(VOICE.matcher(payload).replaceAll("$1: "));
            if (text.isBlank()) throw invalid();
            if (!normalized.isEmpty()) normalized.append('\n');
            int offset = normalized.length(); normalized.append(text);
            if (normalized.length() > maxChars) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TEXT_TOO_LARGE", "Transcript vượt giới hạn ký tự.");
            var locator = new LinkedHashMap<String, Object>();
            locator.put("sourceType", vtt ? "VTT" : "SRT"); locator.put("cueIndex", cue - 1);
            if (timing != begin) locator.put("cueId", first);
            locator.put("rawStart", from); locator.put("rawEnd", to); locator.put("lineIndex", timing + 1);
            locator.put("startMs", start.millis()); locator.put("endMs", finish.millis());
            locator.put("timestampEnd", finish.text());
            segments.add(new ParsedTranscript.Segment(segments.size(), speaker, start.text(), text, offset, normalized.length(), locator));
        }
        if (segments.isEmpty()) throw invalid();
        return new ParsedTranscript(vtt ? "VTT" : "SRT", raw, normalized.toString(), List.copyOf(segments),
                skipped ? List.of("SUBTITLE_METADATA_OMITTED") : List.of());
    }
}
