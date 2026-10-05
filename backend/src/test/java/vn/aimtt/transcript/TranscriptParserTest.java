package vn.aimtt.transcript;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockMultipartFile;
import vn.aimtt.common.ApiException;
import static org.assertj.core.api.Assertions.*;

class TranscriptParserTest {
    private final TranscriptParser parser = new TranscriptParser(limits(200000, 20971520, 52428800));

    @AfterEach void restorePoiGlobalLimits() {
        new TranscriptParser(limits(200000, 20971520, 52428800));
    }

    private InputProperties limits(int chars, long entry, long total) {
        return new InputProperties(10485760, chars, entry, total, 1000, 0.01, Duration.ofDays(30));
    }

    @Test void shortActionIsValidAndNormalizationPreservesNegationAndSource() {
        String raw = "  Nam:  không đổi hạn nhé.\r\n\r\nMai: bo\u0309 login.  ";
        var result = parser.paste(raw);
        assertThat(result.rawText()).isEqualTo(raw);
        assertThat(result.normalizedText()).isEqualTo("Nam: không đổi hạn nhé.\nMai: bỏ login.");
        assertThat(result.segments()).hasSize(2);
        var second = result.segments().get(1);
        int from = (int) second.sourceLocator().get("rawStart");
        int to = (int) second.sourceLocator().get("rawEnd");
        assertThat(raw.substring(from, to)).isEqualTo("Mai: bo\u0309 login.  ");
        assertThat(result.normalizedText().substring(second.normalizedStart(), second.normalizedEnd())).isEqualTo(second.text());
        assertThat(parser.paste("Sửa login.").segments()).hasSize(1);
    }

    @Test void unicodeSpeakerLabelsAreRetainedWithoutInventingIdentity() {
        var result = parser.paste("[00:32:12] Nam: Long sửa login nhé.");
        assertThat(result.segments().get(0).speaker()).isNull();
        assertThat(result.segments().get(0).timestamp()).isEqualTo("00:32:12");
        assertThat(result.normalizedText()).contains("Nam:");
    }

    @Test void strictUtf8AndBomHandling() {
        var bom = new MockMultipartFile("file", "meeting.txt", "text/plain", "\uFEFFMai: sửa API.".getBytes(StandardCharsets.UTF_8));
        assertThat(parser.file(bom).rawText()).isEqualTo("Mai: sửa API.");
        var invalid = new MockMultipartFile("file", "meeting.txt", "text/plain", new byte[]{(byte) 0xC3, 0x28});
        assertCode(() -> parser.file(invalid), "INVALID_ENCODING");
    }

    @Test void docxKeepsParagraphAndTableOrderAndLocators() throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Nam: việc đầu.");
            var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Mai: việc trong bảng.");
            table.getRow(0).getCell(1).setText("Long: không làm API.");
            doc.createParagraph().createRun().setText("Bỏ việc đầu.");
            doc.write(bytes);
            var result = parser.file(docx(bytes.toByteArray()));
            assertThat(result.segments()).extracting(ParsedTranscript.Segment::text).containsExactly(
                    "Nam: việc đầu.", "Mai: việc trong bảng.", "Long: không làm API.", "Bỏ việc đầu.");
            assertThat(result.segments().get(1).sourceLocator()).containsEntry("tableIndex", 0).containsEntry("rowIndex", 0).containsEntry("cellIndex", 0);
            assertThat(result.segments().get(3).sourceLocator()).containsEntry("paragraphIndex", 1);
        }
    }

    @Test void inputAndActualZipInflationLimits() throws Exception {
        assertCode(() -> parser.paste("  \n\t"), "INVALID_INPUT");
        var small = new TranscriptParser(limits(8, 64, 100));
        assertCode(() -> small.paste("123456789"), "TEXT_TOO_LARGE");
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("a".repeat(120).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry(); zip.finish();
            assertCode(() -> small.file(docx(out.toByteArray())), "DOCX_ZIP_LIMIT");
        }
    }

    @Test void rejectsWrongTypeSpoofedMimeAndCorruptDocx() {
        assertCode(() -> parser.file(new MockMultipartFile("file", "bad.docm", "application/octet-stream", new byte[]{1})), "INVALID_FILE_TYPE");
        assertCode(() -> parser.file(new MockMultipartFile("file", "bad.txt", "image/png", "text".getBytes())), "INVALID_FILE_TYPE");
        assertCode(() -> parser.file(docx("not a zip".getBytes())), "INVALID_FILE");
    }

    private MockMultipartFile docx(byte[] bytes) {
        return new MockMultipartFile("file", "meeting.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo(code);
    }
}
