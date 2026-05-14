package com.skillforge.server.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link WordDocumentParser}. Fixtures are built programmatically
 * in {@link #buildFixtures} so the repo doesn't carry binary .docx test files.
 */
class WordDocumentParserTest {

    @TempDir
    static Path tmp;

    private static Path simpleDocx;
    private static Path withTableDocx;
    private static Path hugeDocx;

    // Wave 3 r2: WordDocumentParser is now a static utility — call methods directly.

    @BeforeAll
    static void buildFixtures() throws Exception {
        simpleDocx = tmp.resolve("simple.docx");
        try (XWPFDocument doc = new XWPFDocument();
             OutputStream out = Files.newOutputStream(simpleDocx)) {
            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading1");
            XWPFRun run = h1.createRun();
            run.setText("Top Heading");

            XWPFParagraph body1 = doc.createParagraph();
            body1.createRun().setText("First plain paragraph.");

            XWPFParagraph body2 = doc.createParagraph();
            body2.createRun().setText("Second plain paragraph.");
            doc.write(out);
        }

        withTableDocx = tmp.resolve("table.docx");
        try (XWPFDocument doc = new XWPFDocument();
             OutputStream out = Files.newOutputStream(withTableDocx)) {
            XWPFParagraph p = doc.createParagraph();
            p.setStyle("Heading2");
            p.createRun().setText("Table Section");

            XWPFTable table = doc.createTable(3, 2);
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("Name");
            header.getCell(1).setText("Score");
            XWPFTableRow r1 = table.getRow(1);
            r1.getCell(0).setText("Alice");
            r1.getCell(1).setText("99");
            XWPFTableRow r2 = table.getRow(2);
            r2.getCell(0).setText("Bob");
            r2.getCell(1).setText("87");
            doc.write(out);
        }

        hugeDocx = tmp.resolve("huge.docx");
        try (XWPFDocument doc = new XWPFDocument();
             OutputStream out = Files.newOutputStream(hugeDocx)) {
            // Each paragraph contributes ~500+ chars. 200 paragraphs ≈ 100k chars
            // — comfortably above MAX_OUTPUT_CHARS (20k).
            //
            // Vary the content per paragraph: POI 5.x trips a "zip bomb"
            // detector when uniform filler compresses too aggressively
            // (compressed:uncompressed ratio < 1:100 by default). A random
            // suffix breaks the compressor's run-length advantage so the
            // compressed body stays large enough to pass the heuristic.
            java.util.Random rng = new java.util.Random(42);
            for (int i = 0; i < 200; i++) {
                StringBuilder sb = new StringBuilder(520);
                sb.append("para-").append(i).append(": ");
                for (int j = 0; j < 500; j++) {
                    // printable ASCII range 33..126
                    sb.append((char) (33 + rng.nextInt(94)));
                }
                doc.createParagraph().createRun().setText(sb.toString());
            }
            doc.write(out);
        }
    }

    @Test
    @DisplayName("parseToMarkdown(simple.docx) renders heading + paragraphs")
    void parseToMarkdown_simple_returnsMarkdownShape() throws Exception {
        String md = WordDocumentParser.parseToMarkdown(simpleDocx);
        assertThat(md).contains("# Top Heading");
        assertThat(md).contains("First plain paragraph.");
        assertThat(md).contains("Second plain paragraph.");
        // Heading line should not be confused with body paragraph (no inline "Top Heading" without #).
        assertThat(md).contains("# Top Heading\n\n");
    }

    @Test
    @DisplayName("parseToMarkdown(table.docx) renders markdown table with header + rows")
    void parseToMarkdown_table_rendersMarkdownTable() throws Exception {
        String md = WordDocumentParser.parseToMarkdown(withTableDocx);
        assertThat(md).contains("## Table Section");
        // Header row + separator row + body rows
        assertThat(md).contains("| Name | Score |");
        assertThat(md).contains("|---|---|");
        assertThat(md).contains("| Alice | 99 |");
        assertThat(md).contains("| Bob | 87 |");
    }

    @Test
    @DisplayName("parseToMarkdown truncates output at MAX_OUTPUT_CHARS with marker")
    void parseToMarkdown_huge_truncatesWithMarker() throws Exception {
        String md = WordDocumentParser.parseToMarkdown(hugeDocx);
        assertThat(md).endsWith(WordDocumentParser.TRUNCATION_MARKER);
        // Output length = exactly MAX_OUTPUT_CHARS + marker (or one char less if
        // surrogate-pair guard tripped, which it won't here for ASCII fixture).
        int expected = WordDocumentParser.MAX_OUTPUT_CHARS
                + WordDocumentParser.TRUNCATION_MARKER.length();
        assertThat(md.length()).isBetween(expected - 1, expected);
    }

    @Test
    @DisplayName("parseToMarkdown(missing.docx) throws WORD_PARSE_FAILED")
    void parseToMarkdown_missingFile_throws() {
        Path nope = tmp.resolve("does-not-exist.docx");
        assertThatThrownBy(() -> WordDocumentParser.parseToMarkdown(nope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("WORD_PARSE_FAILED:");
    }

    @Test
    @DisplayName("parseToMarkdown rejects unsupported extension")
    void parseToMarkdown_unsupportedExtension_throws() throws Exception {
        Path bogus = tmp.resolve("data.rtf");
        Files.writeString(bogus, "{\\rtf1}");
        assertThatThrownBy(() -> WordDocumentParser.parseToMarkdown(bogus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORD_PARSE_FAILED")
                .hasMessageContaining("unsupported extension");
    }
}
