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

    private MockMultipartFile upload(String name, String mime, byte[] bytes) { return new MockMultipartFile("file", name, mime, bytes); }
    private java.util.List<String> texts(ParsedTranscript result) { return result.segments().stream().map(ParsedTranscript.Segment::text).toList(); }

    @Test void rtfKeepsUnicodeAndCodepageTextAndSkipsTables() {
        String rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Arial;}}{\\colortbl;\\red0\\green0\\blue0;}"
                + "{\\*\\generator Word;}\\pard Nam: s\\u7917?a API caf\\'e9.\\par\n"
                + "{\\field{\\*\\fldinst HYPERLINK \"x\"}{\\fldrslt Mai l\\u224?m login}}\\par }";
        var result = parser.file(upload("meeting.rtf", "application/rtf", rtf.getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(result.sourceType()).isEqualTo("RTF");
        assertThat(texts(result)).containsExactly("Nam: sửa API café.", "Mai làm login");
        assertThat(result.segments().get(1).sourceLocator()).containsEntry("paragraphIndex", 1);
    }

    @Test void legacyDocSavedAsRtfOrHtmlStillImports() {
        var rtf = parser.file(upload("old.doc", "application/msword", "{\\rtf1\\ansi Long: deploy.\\par}".getBytes(StandardCharsets.US_ASCII)));
        assertThat(rtf.sourceType()).isEqualTo("RTF");assertThat(texts(rtf)).containsExactly("Long: deploy.");
        var html = parser.file(upload("old.doc", "application/msword", "<html><body><p>Mai: test</p></body></html>".getBytes(StandardCharsets.UTF_8)));
        assertThat(html.sourceType()).isEqualTo("HTML");assertThat(texts(html)).containsExactly("Mai: test");
        assertCode(() -> parser.file(upload("old.doc", "application/msword", "random bytes".getBytes())), "INVALID_FILE");
    }

    @Test void htmlDropsScriptsStylesAndDecodesEntities() {
        String html = "<!doctype html><html><head><title>x</title><style>p{}</style></head><body>"
                + "<script>alert('x')</script><h1>Họp&nbsp;tuần</h1><p>Nam: s&#7917;a API &amp; login</p>"
                + "<table><tr><td>Mai</td><td>&quot;deploy&quot;</td></tr></table><!-- note --></body></html>";
        var result = parser.file(upload("meeting.html", "text/html", html.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.sourceType()).isEqualTo("HTML");
        assertThat(texts(result)).containsExactly("Họp tuần", "Nam: sửa API & login", "Mai \"deploy\"");
    }

    @Test void odtParagraphsHeadingsSpacesAndTables() throws Exception {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\">"
                + "<office:body><office:text><text:h>Biên bản</text:h><text:p>Nam:<text:s/>sửa<text:s text:c=\"2\"/>API<text:note><text:note-body><text:p>chú thích</text:p></text:note-body></text:note></text:p>"
                + "<table:table><table:table-row><table:table-cell><text:p>Mai làm login</text:p></table:table-cell></table:table-row></table:table>"
                + "</office:text></office:body></office:document-content>";
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype")); zip.write("application/vnd.oasis.opendocument.text".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml")); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); zip.finish();
            var result = parser.file(upload("meeting.odt", "application/vnd.oasis.opendocument.text", out.toByteArray()));
            assertThat(result.sourceType()).isEqualTo("ODT");
            assertThat(texts(result)).containsExactly("Biên bản", "Nam: sửa API", "Mai làm login");
        }
        assertCode(() -> parser.file(upload("x.odt", "", "<?xml?><!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>".getBytes())), "INVALID_FILE");
    }

    @Test void markdownAndUtf16TextFiles() {
        var md = parser.file(upload("notes.md", "text/markdown", "# Họp\n- Nam: sửa API".getBytes(StandardCharsets.UTF_8)));
        assertThat(md.sourceType()).isEqualTo("MD");assertThat(texts(md)).containsExactly("# Họp", "- Nam: sửa API");
        byte[] body = "Mai: làm login".getBytes(StandardCharsets.UTF_16LE), bom = new byte[body.length + 2];
        bom[0] = (byte) 0xFF; bom[1] = (byte) 0xFE; System.arraycopy(body, 0, bom, 2, body.length);
        assertThat(texts(parser.file(upload("u.txt", "text/plain", bom)))).containsExactly("Mai: làm login");
    }

    @Test void pdfRequiresPdfBytesAndRejectsMedia() {
        assertCode(() -> parser.file(upload("meeting.pdf", "application/pdf", "not a pdf".getBytes())), "INVALID_FILE");
        assertCode(() -> parser.file(upload("meeting.pdf", "image/png", "%PDF-1.4".getBytes())), "INVALID_FILE_TYPE");
        assertCode(() -> parser.file(upload("photo.rtf", "text/rtf", "%PDF-1.4".getBytes())), "INVALID_FILE_TYPE");
    }

    /** Real PDFBox only (skipped when compiled against API stubs). */
    @Test void pdfTextIsExtractedPerPageAndScannedPdfIsExplained() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(classExists("org.apache.pdfbox.pdmodel.PDPage"));
        var result = parser.file(upload("meeting.pdf", "application/pdf", pdf("Nam: fix API", "Mai: login")));
        assertThat(result.sourceType()).isEqualTo("PDF");
        assertThat(texts(result)).containsExactly("Nam: fix API", "Mai: login");
        assertThat(result.segments().get(1).sourceLocator()).containsEntry("pageIndex", 1);
        assertCode(() -> parser.file(upload("scan.pdf", "application/pdf", pdf())), "NO_TEXT_LAYER");
    }

    private static boolean classExists(String name) {
        try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /** Minimal valid PDF, one page per ASCII line (Helvetica). */
    static byte[] pdf(String... pages) {
        var objects = new java.util.ArrayList<String>();
        int count = Math.max(1, pages.length);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < count; i++) kids.append(4 + i * 2).append(" 0 R ");
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + count + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int i = 0; i < count; i++) {
            String stream = pages.length == 0 ? "" : "BT /F1 12 Tf 72 720 Td (" + pages[i] + ") Tj ET";
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents " + (5 + i * 2) + " 0 R >>");
            objects.add("<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream");
        }
        var out = new StringBuilder("%PDF-1.4\n");
        var offsets = new java.util.ArrayList<Integer>();
        for (int i = 0; i < objects.size(); i++) { offsets.add(out.length()); out.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n"); }
        int xref = out.length();
        out.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) out.append(String.format("%010d 00000 n \n", offset));
        out.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private MockMultipartFile docx(byte[] bytes) {
        return new MockMultipartFile("file", "meeting.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo(code);
    }
}
