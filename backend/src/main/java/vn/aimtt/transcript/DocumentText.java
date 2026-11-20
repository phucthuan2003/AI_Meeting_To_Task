package vn.aimtt.transcript;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Text extraction for the "common" document formats besides DOCX/TXT/subtitles: PDF, legacy Word (.doc), RTF,
 * OpenDocument (.odt), HTML and Markdown. Each format yields ordered blocks (one paragraph/page each) with a source
 * locator, which TranscriptParser turns into segments exactly like DOCX paragraphs.
 */
final class DocumentText {
    record Block(String text, Map<String, Object> locator) {}

    private DocumentText() {}

    static boolean isPdf(byte[] b) { return startsWith(b, "%PDF-") || indexOf(b, "%PDF-", 1024) >= 0; }
    static boolean isOle2(byte[] b) { return b.length >= 8 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0; }
    static boolean isZip(byte[] b) { return b.length >= 4 && b[0] == 'P' && b[1] == 'K'; }
    static boolean isRtf(byte[] b) { return startsWith(skipBom(b), "{\\rtf"); }
    static boolean isHtml(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 1024), StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("\u00ef\u00bb\u00bf")) head = head.substring(3).stripLeading();
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<?xml") && head.contains("<html");
    }
    private static byte[] skipBom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? Arrays.copyOfRange(b, 3, Math.min(b.length, 64)) : b;
    }
    private static boolean startsWith(byte[] b, String prefix) {
        if (b.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (b[i] != prefix.charAt(i)) return false;
        return true;
    }
    private static int indexOf(byte[] b, String needle, int limit) {
        outer:
        for (int i = 0; i + needle.length() <= Math.min(b.length, limit); i++) {
            for (int j = 0; j < needle.length(); j++) if (b[i + j] != needle.charAt(j)) continue outer;
            return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- PDF (Apache PDFBox)

    /** One block per page. Returns an empty list for a PDF without a text layer (scanned images). */
    static List<Block> pdf(byte[] bytes, int maxChars) throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            var blocks = new ArrayList<Block>();
            int total = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).replace("\f", "").strip();
                total += text.length();
                if (total > maxChars) throw new TextLimit();
                if (!text.isBlank()) blocks.add(new Block(text, locator("PDF", "pageIndex", page - 1)));
            }
            return blocks;
        }
    }

    // ---------------------------------------------------------------- Word 97-2003 (.doc, Apache POI HWPF)

    static List<Block> doc(byte[] bytes, int maxChars) throws Exception {
        try (var document = new org.apache.poi.hwpf.HWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getRange().text();
            if (text.length() > maxChars * 2L) throw new TextLimit();
            var blocks = new ArrayList<Block>();
            int index = 0;
            for (String paragraph : stripWordFields(text).split("\r")) {
                String clean = paragraph.replace('\u0007', '\t').replace('\u000B', '\n').replace('\u000C', '\n');
                blocks.add(new Block(clean, locator("DOC", "paragraphIndex", index++)));
            }
            return blocks;
        }
    }

    /** Word field = \u0013 instruction \u0014 result \u0015: keep only the visible result. */
    static String stripWordFields(String text) {
        var out = new StringBuilder(text.length());
        var stack = new ArrayDeque<Boolean>(); // per open field: true while inside its instruction part
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0013') { stack.push(true); continue; }
            if (c == '\u0014') { if (!stack.isEmpty()) { stack.pop(); stack.push(false); } continue; }
            if (c == '\u0015') { if (!stack.isEmpty()) stack.pop(); continue; }
            if (stack.contains(Boolean.TRUE)) continue;
            out.append(c);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- RTF

    private static final Set<String> RTF_SKIP = Set.of("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header",
            "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf", "fldinst", "xmlnstbl", "listtable",
            "listoverridetable", "rsidtbl", "generator", "themedata", "colorschememapping", "datastore", "latentstyles",
            "pgdsctbl", "filetbl", "revtbl", "nonshppict", "shppict", "bkmkstart", "bkmkend", "footnote", "annotation",
            "atnid", "atnauthor", "template", "userprops", "mmathPr", "wgrffmtfilter", "panose", "falt", "listtext", "pntext");

    static List<Block> rtf(byte[] bytes, int maxChars) {
        String source = new String(bytes, StandardCharsets.ISO_8859_1);
        var out = new StringBuilder();
        var pending = new ByteArrayOutputStream();
        Charset codepage = Charset.forName("windows-1252");
        var skipStack = new ArrayDeque<boolean[]>(); // {skip}
        var ucStack = new ArrayDeque<Integer>();
        boolean skip = false; int uc = 1; int skipChars = 0; boolean ignorableNext = false;
        int i = 0, n = source.length();
        while (i < n) {
            if (out.length() > maxChars * 2) throw new TextLimit();
            char c = source.charAt(i);
            if (c == '{') { flush(pending, codepage, out, skip); skipStack.push(new boolean[]{skip}); ucStack.push(uc); i++; continue; }
            if (c == '}') { flush(pending, codepage, out, skip); skip = skipStack.isEmpty() ? skip : skipStack.pop()[0]; uc = ucStack.isEmpty() ? uc : ucStack.pop(); skipChars = 0; i++; continue; }
            if (c == '\r' || c == '\n') { i++; continue; }
            if (c != '\\') {
                flush(pending, codepage, out, skip);
                if (skipChars > 0) skipChars--; else if (!skip) out.append(c);
                i++; continue;
            }
            // control symbol or word
            if (i + 1 >= n) break;
            char next = source.charAt(i + 1);
            if (next == '\'') {
                if (i + 4 <= n) {
                    try {
                        int value = Integer.parseInt(source.substring(i + 2, i + 4), 16);
                        if (skipChars > 0) skipChars--; else if (!skip) pending.write(value);
                    } catch (NumberFormatException ignored) { }
                }
                i += 4; continue;
            }
            flush(pending, codepage, out, skip);
            if (!Character.isLetter(next)) {
                i += 2;
                if (skipChars > 0) { skipChars--; continue; }
                switch (next) {
                    case '\\', '{', '}' -> { if (!skip) out.append(next); }
                    case '~' -> { if (!skip) out.append(' '); }
                    case '_' -> { if (!skip) out.append('-'); }
                    case '*' -> ignorableNext = true;
                    case '\r', '\n' -> { if (!skip) out.append('\n'); }
                    default -> { }
                }
                continue;
            }
            int start = i + 1, end = start;
            while (end < n && Character.isLetter(source.charAt(end))) end++;
            String word = source.substring(start, end);
            int paramStart = end;
            if (end < n && (source.charAt(end) == '-' || Character.isDigit(source.charAt(end)))) {
                end++;
                while (end < n && Character.isDigit(source.charAt(end))) end++;
            }
            Integer param = null;
            if (end > paramStart) { try { param = Integer.parseInt(source.substring(paramStart, end)); } catch (NumberFormatException e) { param = 0; } }
            if (end < n && source.charAt(end) == ' ') end++;
            i = end;
            if (ignorableNext || RTF_SKIP.contains(word)) { ignorableNext = false; skip = true; continue; }
            if (skip) { if (word.equals("bin") && param != null) i += Math.max(0, param); continue; }
            switch (word) {
                case "par", "line", "sect", "page", "row" -> out.append('\n');
                case "tab", "cell" -> out.append('\t');
                case "emdash" -> out.append('\u2014');
                case "endash" -> out.append('\u2013');
                case "bullet" -> out.append('\u2022');
                case "lquote" -> out.append('\u2018');
                case "rquote" -> out.append('\u2019');
                case "ldblquote" -> out.append('\u201C');
                case "rdblquote" -> out.append('\u201D');
                case "uc" -> uc = param == null ? 1 : Math.max(0, param);
                case "u" -> { if (param != null) { out.append((char) (param < 0 ? param + 65536 : param)); skipChars = uc; } }
                case "ansicpg" -> codepage = rtfCharset(param, codepage);
                case "bin" -> i += param == null ? 0 : Math.max(0, param);
                default -> { }
            }
        }
        flush(pending, codepage, out, skip);
        var blocks = new ArrayList<Block>();
        int index = 0;
        for (String paragraph : out.toString().split("\n", -1)) blocks.add(new Block(paragraph, locator("RTF", "paragraphIndex", index++)));
        return blocks;
    }

    private static void flush(ByteArrayOutputStream pending, Charset codepage, StringBuilder out, boolean skip) {
        if (pending.size() == 0) return;
        if (!skip) out.append(new String(pending.toByteArray(), codepage));
        pending.reset();
    }

    private static Charset rtfCharset(Integer codepage, Charset fallback) {
        if (codepage == null) return fallback;
        for (String name : List.of("windows-" + codepage, "cp" + codepage, "x-windows-" + codepage)) {
            try { if (Charset.isSupported(name)) return Charset.forName(name); } catch (IllegalCharsetNameException ignored) { }
        }
        return fallback;
    }

    // ---------------------------------------------------------------- OpenDocument text (.odt)

    static List<Block> odt(byte[] bytes, int maxChars, long maxEntryBytes) throws Exception {
        byte[] content = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("content.xml")) { content = zip.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxEntryBytes + 1)); break; }
            }
        }
        if (content == null || content.length > maxEntryBytes) throw new IllegalArgumentException("content.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        var blocks = new ArrayList<Block>();
        int[] counter = {0, 0};
        collectOdt(document.getDocumentElement(), blocks, counter, maxChars);
        return blocks;
    }

    private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

    private static void collectOdt(Element element, List<Block> blocks, int[] counter, int maxChars) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element e)) continue;
            boolean text = TEXT_NS.equals(e.getNamespaceURI());
            if (text && (e.getLocalName().equals("p") || e.getLocalName().equals("h"))) {
                var sb = new StringBuilder(); odtInline(e, sb);
                counter[1] += sb.length();
                if (counter[1] > maxChars) throw new TextLimit();
                blocks.add(new Block(sb.toString(), locator("ODT", "paragraphIndex", counter[0]++)));
            } else if (!(text && (e.getLocalName().equals("note") || e.getLocalName().equals("tracked-changes")))) {
                collectOdt(e, blocks, counter, maxChars);
            }
        }
    }

    private static void odtInline(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Text t) { sb.append(t.getData()); continue; }
            if (!(child instanceof Element e)) continue;
            if (TEXT_NS.equals(e.getNamespaceURI())) {
                switch (e.getLocalName()) {
                    case "s" -> { int count = 1; try { count = Integer.parseInt(e.getAttributeNS(TEXT_NS, "c")); } catch (NumberFormatException ignored) { } sb.append(" ".repeat(Math.max(1, Math.min(count, 100)))); continue; }
                    case "tab" -> { sb.append('\t'); continue; }
                    case "line-break" -> { sb.append('\n'); continue; }
                    case "note", "bookmark", "bookmark-start", "bookmark-end", "annotation" -> { continue; }
                    default -> { }
                }
            }
            odtInline(e, sb);
        }
    }

    // ---------------------------------------------------------------- HTML

    private static final Pattern DROP = Pattern.compile("(?is)<!--.*?-->|<(script|style|head|template|noscript|svg)\\b.*?</\\1\\s*>");
    private static final Pattern BLOCK = Pattern.compile("(?i)<\\s*/?\\s*(p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|header|footer|blockquote|pre|hr|dt|dd|title)\\b[^>]*>");
    private static final Pattern CELL = Pattern.compile("(?i)<\\s*/?\\s*(td|th)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});?");
    private static final Pattern META_CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)");
    private static final Map<String, Integer> ENTITIES = new HashMap<>();
    static {
        Object[] pairs = {
            "AElig",198,"Aacute",193,"Acirc",194,"Agrave",192,"Alpha",913,"Aring",197,"Atilde",195,"Auml",196,"Beta",914,
            "Ccedil",199,"Chi",935,"Dagger",8225,"Delta",916,"ETH",208,"Eacute",201,"Ecirc",202,"Egrave",200,"Epsilon",917,
            "Eta",919,"Euml",203,"Gamma",915,"Iacute",205,"Icirc",206,"Igrave",204,"Iota",921,"Iuml",207,"Kappa",922,
            "Lambda",923,"Mu",924,"Ntilde",209,"Nu",925,"OElig",338,"Oacute",211,"Ocirc",212,"Ograve",210,"Omega",937,
            "Omicron",927,"Oslash",216,"Otilde",213,"Ouml",214,"Phi",934,"Pi",928,"Prime",8243,"Psi",936,"Rho",929,
            "Scaron",352,"Sigma",931,"THORN",222,"Tau",932,"Theta",920,"Uacute",218,"Ucirc",219,"Ugrave",217,"Upsilon",933,
            "Uuml",220,"Xi",926,"Yacute",221,"Yuml",376,"Zeta",918,"aacute",225,"acirc",226,"acute",180,"aelig",230,
            "agrave",224,"alefsym",8501,"alpha",945,"amp",38,"and",8743,"ang",8736,"aring",229,"asymp",8776,"atilde",227,
            "auml",228,"bdquo",8222,"beta",946,"brvbar",166,"bull",8226,"cap",8745,"ccedil",231,"cedil",184,"cent",162,
            "chi",967,"circ",710,"clubs",9827,"cong",8773,"copy",169,"crarr",8629,"cup",8746,"curren",164,"dArr",8659,
            "dagger",8224,"darr",8595,"deg",176,"delta",948,"diams",9830,"divide",247,"eacute",233,"ecirc",234,
            "egrave",232,"empty",8709,"emsp",8195,"ensp",8194,"epsilon",949,"equiv",8801,"eta",951,"eth",240,"euml",235,
            "euro",8364,"exist",8707,"fnof",402,"forall",8704,"frac12",189,"frac14",188,"frac34",190,"frasl",8260,
            "gamma",947,"ge",8805,"gt",62,"hArr",8660,"harr",8596,"hearts",9829,"hellip",8230,"iacute",237,"icirc",238,
            "iexcl",161,"igrave",236,"image",8465,"infin",8734,"int",8747,"iota",953,"iquest",191,"isin",8712,"iuml",239,
            "kappa",954,"lArr",8656,"lambda",955,"lang",9001,"laquo",171,"larr",8592,"lceil",8968,"ldquo",8220,"le",8804,
            "lfloor",8970,"lowast",8727,"loz",9674,"lrm",8206,"lsaquo",8249,"lsquo",8216,"lt",60,"macr",175,"mdash",8212,
            "micro",181,"middot",183,"minus",8722,"mu",956,"nabla",8711,"nbsp",160,"ndash",8211,"ne",8800,"ni",8715,
            "not",172,"notin",8713,"nsub",8836,"ntilde",241,"nu",957,"oacute",243,"ocirc",244,"oelig",339,"ograve",242,
            "oline",8254,"omega",969,"omicron",959,"oplus",8853,"or",8744,"ordf",170,"ordm",186,"oslash",248,"otilde",245,
            "otimes",8855,"ouml",246,"para",182,"part",8706,"permil",8240,"perp",8869,"phi",966,"pi",960,"piv",982,
            "plusmn",177,"pound",163,"prime",8242,"prod",8719,"prop",8733,"psi",968,"quot",34,"rArr",8658,"radic",8730,
            "rang",9002,"raquo",187,"rarr",8594,"rceil",8969,"rdquo",8221,"real",8476,"reg",174,"rfloor",8971,"rho",961,
            "rlm",8207,"rsaquo",8250,"rsquo",8217,"sbquo",8218,"scaron",353,"sdot",8901,"sect",167,"shy",173,"sigma",963,
            "sigmaf",962,"sim",8764,"spades",9824,"sub",8834,"sube",8838,"sum",8721,"sup",8835,"sup1",185,"sup2",178,
            "sup3",179,"supe",8839,"szlig",223,"tau",964,"there4",8756,"theta",952,"thetasym",977,"thinsp",8201,
            "thorn",254,"tilde",732,"times",215,"trade",8482,"uArr",8657,"uacute",250,"uarr",8593,"ucirc",251,"ugrave",249,
            "uml",168,"upsih",978,"upsilon",965,"uuml",252,"weierp",8472,"xi",958,"yacute",253,"yen",165,"yuml",255,
            "zeta",950,"zwj",8205,"zwnj",8204
        };
        for (int k = 0; k < pairs.length; k += 2) ENTITIES.put((String) pairs[k], (Integer) pairs[k + 1]);
    }

    static List<Block> html(String markup, int maxChars) {
        if (markup.length() > maxChars * 20L) throw new TextLimit();
        String body = DROP.matcher(markup).replaceAll(" ");
        body = BLOCK.matcher(body).replaceAll("\n");
        body = CELL.matcher(body).replaceAll("\t");
        body = TAG.matcher(body).replaceAll("");
        body = decodeEntities(body);
        var blocks = new ArrayList<Block>();
        int index = 0;
        for (String line : body.split("\n", -1)) blocks.add(new Block(line, locator("HTML", "blockIndex", index++)));
        return blocks;
    }

    static String decodeEntities(String text) {
        Matcher m = ENTITY.matcher(text);
        var out = new StringBuilder(text.length());
        while (m.find()) {
            String name = m.group(1);
            Integer code = null;
            try {
                if (name.startsWith("#x") || name.startsWith("#X")) code = Integer.parseInt(name.substring(2), 16);
                else if (name.startsWith("#")) code = Integer.parseInt(name.substring(1));
                else code = ENTITIES.get(name);
            } catch (NumberFormatException ignored) { }
            String replacement = code != null && Character.isValidCodePoint(code) && code != 0 ? new String(Character.toChars(code)) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replace('\u00A0', ' ');
    }

    /** BOM, then a declared charset, then strict UTF-8, then Windows-1252 for old pages. */
    static String htmlText(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        var declared = META_CHARSET.matcher(new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1));
        if (declared.find()) {
            try { return new String(bytes, Charset.forName(declared.group(1))); } catch (Exception ignored) { }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    // ----------------------------------------------------------------

    private static Map<String, Object> locator(String type, String key, int index) {
        var map = new LinkedHashMap<String, Object>();
        map.put("sourceType", type); map.put(key, index);
        return map;
    }

    /** Extracted text exceeded the configured limit before normalisation. */
    static final class TextLimit extends RuntimeException {
        TextLimit() { super(null, null, false, false); }
    }
}
