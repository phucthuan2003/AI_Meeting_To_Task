package vn.aimtt.transcript;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.aimtt.common.ApiException;

@Component
public class TranscriptParser {
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MAIN_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[?((?:\\d{2}:)?\\d{2}:\\d{2})\\]?\\s+");
    private final InputProperties limits;

    public TranscriptParser(InputProperties limits) {
        this.limits = limits;
        ZipSecureFile.setMaxEntrySize(limits.maxZipEntryBytes());
        ZipSecureFile.setMaxTextSize(limits.maxExtractedChars());
        ZipSecureFile.setMinInflateRatio(limits.minInflateRatio());
    }

    public ParsedTranscript paste(String text) {
        if (text == null) throw ApiException.invalid("Nhập transcriptText.");
        checkTextSize(text.length());
        return normalize("PASTE", text, sourceLines(text, Map.of("sourceType", "PASTE")), List.of());
    }

    public ParsedTranscript file(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.invalid("File không có nội dung.");
        if (file.getSize() > limits.maxUploadBytes()) throw tooLarge("FILE_TOO_LARGE", "File vượt giới hạn.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        String mime = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        String ext = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);
        boolean txt = Set.of("txt", "text", "md", "markdown").contains(ext);
        boolean docx = ext.equals("docx");
        boolean vtt = ext.equals("vtt"), srt = ext.equals("srt");
        boolean document = DOCUMENTS.contains(ext);
        if (!txt && !docx && !vtt && !srt && !document) throw unsupported();
        if (document) {
            // Browsers report many MIME types for these (or none); the bytes are checked below. Reject only media.
            if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")) throw unsupported();
        } else {
            Set<String> accepted = vtt || srt ? Set.of("text/vtt", "application/x-subrip", "text/srt", "text/plain", "application/octet-stream", "")
                    : txt ? Set.of("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream", "")
                    : Set.of(DOCX_MIME, "application/octet-stream", "");
            if (!accepted.contains(mime)) throw unsupported();
        }
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(Math.toIntExact(limits.maxUploadBytes() + 1));
            if (bytes.length > limits.maxUploadBytes()) throw tooLarge("FILE_TOO_LARGE", "File vượt giới hạn.");
            if (vtt || srt) {
                String text = decodeUtf8(bytes);
                checkTextSize(text.length());
                return SubtitleParser.parse(text, vtt, limits.maxExtractedChars());
            }
            if (txt) {
                String text = decodeUtf8(bytes);
                checkTextSize(text.length());
                String type = ext.startsWith("m") ? "MD" : "TXT";
                return normalize(type, text, sourceLines(text, Map.of("sourceType", type)), List.of());
            }
            if (docx && !DocumentText.isZip(bytes) && (DocumentText.isOle2(bytes) || DocumentText.isRtf(bytes))) return document(bytes, "doc");
            if (docx) return docx(bytes);
            return document(bytes, ext);
        } catch (ApiException e) { throw e; }
        catch (IOException e) { throw invalidFile(); }
    }

    private static final Set<String> DOCUMENTS = Set.of("pdf", "doc", "rtf", "odt", "html", "htm");

    /**
     * PDF, Word 97-2003, RTF, OpenDocument and HTML. The real format is decided by the file's bytes, so a ".doc" that
     * Word saved as RTF/HTML (common) or a renamed DOCX still imports.
     */
    private ParsedTranscript document(byte[] bytes, String ext) {
        int max = limits.maxExtractedChars();
        try {
            if (DocumentText.isPdf(bytes)) {
                if (!ext.equals("pdf")) throw unsupported();
                var blocks = DocumentText.pdf(bytes, max);
                if (blocks.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "NO_TEXT_LAYER",
                        "PDF không có lớp chữ (có thể là bản scan ảnh). Chạy OCR hoặc dùng file DOCX/TXT.");
                return fromBlocks("PDF", blocks, List.of());
            }
            if (ext.equals("pdf")) throw invalidFile();
            if (DocumentText.isRtf(bytes)) return fromBlocks("RTF", DocumentText.rtf(bytes, max), List.of());
            if (DocumentText.isOle2(bytes)) {
                if (!ext.equals("doc")) throw unsupported();
                return fromBlocks("DOC", DocumentText.doc(bytes, max), List.of());
            }
            if (DocumentText.isZip(bytes)) {
                if (ext.equals("odt")) {
                    preflightZip(bytes, "content.xml");
                    return fromBlocks("ODT", DocumentText.odt(bytes, max, limits.maxZipEntryBytes()), List.of());
                }
                if (ext.equals("doc")) return docx(bytes); // DOCX renamed to .doc
                throw invalidFile();
            }
            if (Set.of("html", "htm", "doc").contains(ext) && DocumentText.isHtml(bytes) || Set.of("html", "htm").contains(ext)) {
                return fromBlocks("HTML", DocumentText.html(DocumentText.htmlText(bytes), max), List.of());
            }
            throw invalidFile();
        } catch (ApiException e) { throw e; }
        catch (DocumentText.TextLimit e) { throw tooLarge("TEXT_TOO_LARGE", "Transcript vượt giới hạn ký tự."); }
        catch (Exception e) {
            var failure = invalidFile();
            failure.initCause(e);
            throw failure;
        }
    }

    private ParsedTranscript fromBlocks(String type, List<DocumentText.Block> blocks, List<String> warnings) {
        var raw = new StringBuilder();
        var sources = new ArrayList<SourceLine>();
        for (var block : blocks) appendSource(raw, sources, block.text(), block.locator());
        if (raw.toString().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "NO_BODY_TEXT", "File không có nội dung văn bản.");
        return normalize(type, raw.toString(), sources, warnings);
    }

    private String decodeUtf8(byte[] bytes) {
        // Notepad "Unicode" files are UTF-16 with a BOM.
        if (bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE || (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)) {
            String text = new String(bytes, 2, bytes.length - 2, (bytes[0] & 0xFF) == 0xFF ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
            if (text.indexOf('\0') >= 0) throw invalidFile();
            return text;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.indexOf('\0') >= 0) throw invalidFile();
            return text;
        } catch (CharacterCodingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENCODING", "Chuyển file văn bản/phụ đề sang UTF-8.");
        }
    }

    private ParsedTranscript docx(byte[] bytes) {
        preflightZip(bytes, "[Content_Types].xml", "word/document.xml");
        try (var pkg = OPCPackage.open(new ByteArrayInputStream(bytes)); var doc = new XWPFDocument(pkg)) {
            if (!MAIN_MIME.equals(doc.getPackagePart().getContentType())) throw unsupported();
            var raw = new StringBuilder();
            var sources = new ArrayList<SourceLine>();
            int paragraph = 0;
            int table = 0;
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    appendSource(raw, sources, p.getText(), Map.of("paragraphIndex", paragraph++));
                } else if (element instanceof XWPFTable t) {
                    appendTable(raw, sources, t, Map.of("tableIndex", table++));
                }
            }
            List<String> warnings = (!doc.getHeaderList().isEmpty() || !doc.getFooterList().isEmpty()
                    || (doc.getComments() != null && doc.getComments().length > 0))
                    ? List.of("DOCX_NON_BODY_CONTENT_OMITTED") : List.of();
            if (raw.toString().isBlank() && !warnings.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NO_BODY_TEXT", "DOCX chỉ có nội dung ngoài body.");
            }
            return normalize("DOCX", raw.toString(), sources, warnings);
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            var failure = invalidFile();
            failure.initCause(e);
            throw failure;
        }
    }

    private void appendTable(StringBuilder raw, List<SourceLine> sources, XWPFTable table, Map<String, Object> parent) {
        for (int r = 0; r < table.getRows().size(); r++) {
            var row = table.getRow(r);
            for (int c = 0; c < row.getTableCells().size(); c++) {
                var cell = row.getCell(c);
                int pIndex = 0;
                int tIndex = 0;
                for (IBodyElement element : cell.getBodyElements()) {
                    var locator = new LinkedHashMap<String, Object>(parent);
                    locator.put("rowIndex", r); locator.put("cellIndex", c);
                    if (element instanceof XWPFParagraph p) {
                        locator.put("cellParagraphIndex", pIndex++);
                        appendSource(raw, sources, p.getText(), locator);
                    } else if (element instanceof XWPFTable nested) {
                        appendTable(raw, sources, nested, Map.of("parent", locator, "nestedTableIndex", tIndex++));
                    }
                }
            }
        }
    }

    private void appendSource(StringBuilder raw, List<SourceLine> sources, String text, Map<String, Object> locator) {
        checkTextSize(raw.length() + text.length() + (raw.isEmpty() ? 0 : 1));
        if (!raw.isEmpty()) raw.append('\n');
        int offset = raw.length();
        for (SourceLine line : sourceLines(text, locator)) {
            sources.add(new SourceLine(offset + line.start(), offset + line.end(), line.locator()));
        }
        raw.append(text);
    }

    /** Reads actual inflated bytes, rather than trusting ZIP central-directory sizes. */
    private void preflightZip(byte[] bytes, String... required) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw invalidFile();
        long total = 0;
        int count = 0;
        var names = new HashSet<String>();
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > limits.maxZipEntries() || !names.add(entry.getName())) throw zipLimit();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin")) throw unsupported();
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read; total += read;
                    if (size > limits.maxZipEntryBytes() || total > limits.maxZipTotalBytes()) throw zipLimit();
                }
                long compressed = entry.getCompressedSize();
                if (size > 1024 && compressed > 0 && (double) compressed / size < limits.minInflateRatio()) throw zipLimit();
            }
            for (String part : required) if (!names.contains(part)) throw invalidFile();
        } catch (ApiException e) { throw e; }
        catch (IOException e) { throw invalidFile(); }
    }

    private record SourceLine(int start, int end, Map<String, Object> locator) {}

    private List<SourceLine> sourceLines(String text, Map<String, Object> base) {
        var lines = new ArrayList<SourceLine>();
        var matcher = Pattern.compile("\\r\\n|\\r|\\n").matcher(text);
        int start = 0; int lineIndex = 0;
        while (matcher.find()) {
            var locator = new LinkedHashMap<String, Object>(base); locator.put("lineIndex", lineIndex++);
            lines.add(new SourceLine(start, matcher.start(), locator)); start = matcher.end();
        }
        var locator = new LinkedHashMap<String, Object>(base); locator.put("lineIndex", lineIndex);
        lines.add(new SourceLine(start, text.length(), locator));
        return lines;
    }

    private ParsedTranscript normalize(String type, String raw, List<SourceLine> lines, List<String> warnings) {
        var normalized = new StringBuilder();
        var segments = new ArrayList<ParsedTranscript.Segment>();
        for (SourceLine line : lines) {
            String text = Normalizer.normalize(raw.substring(line.start(), line.end()), Normalizer.Form.NFC)
                    .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                    .replaceAll("[\\p{Zs}\\t]+", " ").strip();
            if (text.isBlank()) continue;
            if (!normalized.isEmpty()) normalized.append('\n');
            int start = normalized.length();
            normalized.append(text);
            var locator = new LinkedHashMap<String, Object>(line.locator());
            locator.put("rawStart", line.start()); locator.put("rawEnd", line.end());
            var timestamp = TIMESTAMP.matcher(text);
            String stamp = timestamp.find() ? timestamp.group(1) : null;
            // Speaker labels need verification in the analysis pipeline; do not infer identity here.
            segments.add(new ParsedTranscript.Segment(segments.size(), null, stamp, text, start, normalized.length(), locator));
        }
        checkTextSize(normalized.length());
        if (segments.isEmpty()) throw ApiException.invalid("Transcript không có văn bản.");
        return new ParsedTranscript(type, raw, normalized.toString(), List.copyOf(segments), warnings);
    }

    private void checkTextSize(int size) {
        if (size > limits.maxExtractedChars()) throw tooLarge("TEXT_TOO_LARGE", "Transcript vượt giới hạn ký tự.");
    }
    private ApiException tooLarge(String code, String message) { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, code, message); }
    private ApiException zipLimit() { return tooLarge("DOCX_ZIP_LIMIT", "File nén (DOCX/ODT) vượt giới hạn giải nén."); }
    private ApiException unsupported() { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_FILE_TYPE", "Dùng file .txt, .md, .docx, .doc, .pdf, .rtf, .odt, .html, .vtt hoặc .srt hợp lệ."); }
    private ApiException invalidFile() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "File hỏng, mã hóa hoặc không đúng cấu trúc."); }
}
