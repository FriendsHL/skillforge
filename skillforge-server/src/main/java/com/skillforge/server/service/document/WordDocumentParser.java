package com.skillforge.server.service.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.BodyElementType;

/**
 * Standalone parser that converts Word documents (.doc / .docx) to a
 * markdown-flavored plain text representation. Wave 1 skeleton — integration
 * into {@code ChatAttachmentService.classify()} lands in Wave 3.
 *
 * <p>Output rules:
 * <ul>
 *   <li>Headings styled "Heading 1/2/3" → markdown {@code #/##/###}</li>
 *   <li>Regular paragraphs → text followed by a blank line</li>
 *   <li>Tables → GitHub-flavored markdown tables</li>
 *   <li>Inline images / OLE objects / drawings are stripped (text-only output)</li>
 *   <li>Output is truncated to {@link #MAX_OUTPUT_CHARS} with an explicit marker</li>
 * </ul>
 *
 * <p>Failure mode: any parse error surfaces as {@link IllegalStateException} with
 * a {@code WORD_PARSE_FAILED: <cause>} prefix so callers can pattern-match.
 */
public final class WordDocumentParser {

    /** Hard cap on returned markdown length to avoid blowing context window. */
    public static final int MAX_OUTPUT_CHARS = 20_000;

    /** Marker appended when output is truncated. */
    public static final String TRUNCATION_MARKER = "\n\n[Document truncated at 20000 chars]";

    /** Wave 1-C designated this as a static utility — no instances allowed. */
    private WordDocumentParser() {
        throw new UnsupportedOperationException("WordDocumentParser is a static utility");
    }

    public static String parseToMarkdown(Path file) throws IOException {
        if (file == null) {
            throw new IllegalStateException("WORD_PARSE_FAILED: file is null");
        }
        if (!Files.exists(file)) {
            throw new IllegalStateException("WORD_PARSE_FAILED: file does not exist: " + file);
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            String raw;
            if (name.endsWith(".docx")) {
                raw = parseDocx(file);
            } else if (name.endsWith(".doc")) {
                raw = parseDoc(file);
            } else {
                throw new IllegalStateException(
                        "WORD_PARSE_FAILED: unsupported extension (expected .doc or .docx): " + name);
            }
            return truncate(raw);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // POI throws a variety of checked + unchecked types (NotOLE2FileException, etc.).
            // Surface them all under the same diagnostic envelope.
            throw new IllegalStateException("WORD_PARSE_FAILED: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ .docx

    private static String parseDocx(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            // Iterate body elements in document order so paragraphs and tables
            // interleave correctly (a typical .docx mixes both).
            List<IBodyElement> elements = doc.getBodyElements();
            for (IBodyElement el : elements) {
                if (el.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) el;
                    appendParagraph(out, p);
                } else if (el.getElementType() == BodyElementType.TABLE) {
                    XWPFTable t = (XWPFTable) el;
                    appendTable(out, t);
                }
                // BodyElementType.CONTENTCONTROL etc. — skip silently (no text we
                // can extract without deeper structural knowledge).

                // Early bail-out: once we've already produced more than the cap,
                // there's no point iterating the rest of a huge document.
                if (out.length() > MAX_OUTPUT_CHARS) {
                    break;
                }
            }
        }
        return out.toString();
    }

    private static void appendParagraph(StringBuilder out, XWPFParagraph p) {
        // text() concatenates all run text; embedded images/drawings already
        // contribute no characters here, so we don't need to scrub anything
        // extra.
        String text = p.getText();
        if (text == null) {
            text = "";
        }
        text = text.strip();
        if (text.isEmpty()) {
            // Preserve a blank line for empty paragraphs (visual rhythm) but
            // collapse runs of >2 newlines below.
            out.append('\n');
            return;
        }
        String style = p.getStyle(); // raw style ID; usually "Heading1" / "Heading2" / "Heading3"
        int headingLevel = detectHeadingLevel(style);
        if (headingLevel > 0) {
            out.append("#".repeat(headingLevel)).append(' ').append(text).append("\n\n");
        } else {
            out.append(text).append("\n\n");
        }
    }

    private static int detectHeadingLevel(String style) {
        if (style == null) {
            return 0;
        }
        // POI exposes style IDs like "Heading1" / "berschrift1" etc. Be conservative:
        // only honor the canonical English IDs for the first three levels.
        String normalized = style.replace(" ", "").toLowerCase(Locale.ROOT);
        if (normalized.equals("heading1") || normalized.equals("title")) {
            return 1;
        }
        if (normalized.equals("heading2")) {
            return 2;
        }
        if (normalized.equals("heading3")) {
            return 3;
        }
        return 0;
    }

    private static void appendTable(StringBuilder out, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        // Row 0 → header, then separator, then body rows.
        appendDocxRow(out, rows.get(0));
        int cols = rows.get(0).getTableCells().size();
        out.append('|');
        for (int i = 0; i < cols; i++) {
            out.append("---|");
        }
        out.append('\n');
        for (int i = 1; i < rows.size(); i++) {
            appendDocxRow(out, rows.get(i));
        }
        out.append('\n');
    }

    private static void appendDocxRow(StringBuilder out, XWPFTableRow row) {
        out.append('|');
        for (XWPFTableCell cell : row.getTableCells()) {
            String cellText = cell.getText();
            if (cellText == null) {
                cellText = "";
            }
            // Collapse newlines + escape pipes so the markdown table stays valid.
            String safe = cellText.replace("\n", " ").replace("\r", " ").replace("|", "\\|").strip();
            out.append(' ').append(safe).append(" |");
        }
        out.append('\n');
    }

    // ------------------------------------------------------------------- .doc

    private static String parseDoc(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            // .doc is a binary format with much weaker style introspection via
            // POI. We fall back to flat text — paragraphs separated by blank
            // lines, no heading detection, no table reconstruction. This is
            // intentional: the binary format is legacy and the rich path is
            // .docx.
            StringBuilder out = new StringBuilder();
            String[] paragraphs = extractor.getParagraphText();
            for (String para : paragraphs) {
                if (para == null) {
                    continue;
                }
                String text = para.replace("\r", "").strip();
                if (text.isEmpty()) {
                    out.append('\n');
                } else {
                    out.append(text).append("\n\n");
                }
                if (out.length() > MAX_OUTPUT_CHARS) {
                    break;
                }
            }
            return out.toString();
        }
    }

    // --------------------------------------------------------------- truncate

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        // Cut on a UTF-16 char boundary that is not a high-surrogate (defensive
        // for documents with emoji / supplementary chars; otherwise substring
        // could split a surrogate pair and produce a malformed string).
        int cut = MAX_OUTPUT_CHARS;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut) + TRUNCATION_MARKER;
    }
}
