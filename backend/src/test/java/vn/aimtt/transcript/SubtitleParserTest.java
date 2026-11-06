package vn.aimtt.transcript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.aimtt.common.ApiException;
import static org.assertj.core.api.Assertions.*;

class SubtitleParserTest {
    final TranscriptParser parser = new TranscriptParser(new InputProperties(10485760, 200000, 20971520, 52428800, 1000, 0.01, Duration.ofDays(30)));
    ParsedTranscript file(String name, String body) {
        return parser.file(new MockMultipartFile("file", name, name.endsWith("vtt") ? "text/vtt" : "application/x-subrip", body.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void vttKeepsCueOffsetsVoiceAndMillisecondsWhileOmittingMetadata() {
        String raw = "\uFEFFWEBVTT\r\n\r\nNOTE ignored\r\nmetadata\r\n\r\ncue-1\r\n00:01.250 --> 00:04.500 align:start\r\n<v Nam>Mai làm <b>API</b> &amp; test\r\ntrước ngày mai.</v>\r\n\r\n00:03.000 --> 00:05.000\r\nKhông đổi hạn.\r\n";
        var parsed = file("meeting.vtt", raw);
        assertThat(parsed.sourceType()).isEqualTo("VTT");
        assertThat(parsed.segments()).hasSize(2);
        var first = parsed.segments().get(0);
        assertThat(first.speaker()).isEqualTo("Nam");
        assertThat(first.timestamp()).isEqualTo("00:00:01.250");
        assertThat(first.text()).isEqualTo("Nam: Mai làm API & test trước ngày mai.");
        assertThat(first.sourceLocator()).containsEntry("startMs", 1250L).containsEntry("endMs", 4500L).containsEntry("cueId", "cue-1");
        int start = (int) first.sourceLocator().get("rawStart"), end = (int) first.sourceLocator().get("rawEnd");
        assertThat(parsed.rawText().substring(start, end)).startsWith("<v Nam>").endsWith("</v>");
        assertThat(parsed.normalizedText().substring(first.normalizedStart(), first.normalizedEnd())).isEqualTo(first.text());
        assertThat(parsed.segments().get(1).speaker()).isNull();
        assertThat(parsed.warnings()).contains("SUBTITLE_METADATA_OMITTED");
    }
    @Test void srtPreservesMultilineTextAndNeverInfersSpeaker() {
        var parsed = file("meeting.srt", "1\n00:00:01,000 --> 00:00:03,200\nMai: Hoàn thành API\ntrước 17 giờ.\n\n2\n00:00:04,000 --> 00:00:05,000\nKhông có việc mới.");
        assertThat(parsed.sourceType()).isEqualTo("SRT");
        assertThat(parsed.segments()).hasSize(2);
        assertThat(parsed.segments().get(0).text()).isEqualTo("Mai: Hoàn thành API trước 17 giờ.");
        assertThat(parsed.segments().get(0).speaker()).isNull();
        assertThat(parsed.segments().get(1).sourceLocator()).containsEntry("cueId", "2");
    }
    @Test void multipleVoicesDoNotBecomeOneInventedSpeaker() {
        var parsed = file("meeting.vtt", "WEBVTT\n\n00:01.000 --> 00:02.000\n<v Nam>Làm API.</v> <v Mai>Đồng ý.</v>");
        assertThat(parsed.segments().get(0).speaker()).isNull();
        assertThat(parsed.segments().get(0).text()).contains("Nam: Làm API.", "Mai: Đồng ý.");
    }
    @Test void malformedOrDisguisedFilesFailRatherThanPublishingPartialTranscript() {
        for (String body : new String[]{"plain text", "WEBVTT\n\n00:02.000 --> 00:01.000\nX", "WEBVTT\n\n00:61.000 --> 00:62.000\nX",
                "WEBVTT\n\n00:01.000 --> 00:02.000\n", "WEBVTT\n\n00:01.000 --> 00:02.000\nX\n\nbroken block",
                "WEBVTT\n\n00:02.000 --> 00:03.000\nX\n\n00:01.000 --> 00:02.000\nY"}) {
            assertThatThrownBy(() -> file("bad.vtt", body)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("INVALID_SUBTITLE"));
        }
        assertThatThrownBy(() -> file("bad.srt", "1\n00:00:01.000 --> 00:00:02.000\nX")).isInstanceOf(ApiException.class);
    }
    @Test void subtitleLimitsAndUtf8StillApply() {
        assertThatThrownBy(() -> SubtitleParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\n12345", true, 4)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("TEXT_TOO_LARGE"));
        assertThatThrownBy(() -> parser.file(new MockMultipartFile("file", "bad.vtt", "text/vtt", new byte[]{(byte) 0xff})))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("INVALID_ENCODING"));
    }
}
