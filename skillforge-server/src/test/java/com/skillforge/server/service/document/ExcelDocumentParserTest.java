package com.skillforge.server.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ExcelDocumentParser}. Fixtures built programmatically.
 */
class ExcelDocumentParserTest {

    @TempDir
    static Path tmp;

    private static Path twoSheetXlsx;
    private static Path tooManyRowsXlsx;
    private static Path csvFile;
    private static Path quotedCsv;
    private static Path hugeXlsx;

    // Wave 3 r2: ExcelDocumentParser is now a static utility — call methods directly.

    @BeforeAll
    static void buildFixtures() throws Exception {
        twoSheetXlsx = tmp.resolve("two-sheet.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(twoSheetXlsx)) {
            // Sheet A — 5 rows × 3 cols, header + numeric body
            Sheet a = wb.createSheet("Metrics");
            Row ha = a.createRow(0);
            ha.createCell(0).setCellValue("name");
            ha.createCell(1).setCellValue("score");
            ha.createCell(2).setCellValue("active");
            for (int r = 1; r <= 4; r++) {
                Row row = a.createRow(r);
                row.createCell(0).setCellValue("user" + r);
                row.createCell(1).setCellValue(r * 10.0);
                row.createCell(2).setCellValue(r % 2 == 0);
            }

            // Sheet B — header + formula row (sum of B column)
            Sheet b = wb.createSheet("Totals");
            Row hb = b.createRow(0);
            hb.createCell(0).setCellValue("label");
            hb.createCell(1).setCellValue("value");
            Row r1 = b.createRow(1);
            r1.createCell(0).setCellValue("a");
            r1.createCell(1).setCellValue(2.0);
            Row r2 = b.createRow(2);
            r2.createCell(0).setCellValue("b");
            r2.createCell(1).setCellValue(3.0);
            Row r3 = b.createRow(3);
            r3.createCell(0).setCellValue("sum");
            Cell formula = r3.createCell(1);
            formula.setCellFormula("SUM(B2:B3)");

            wb.write(out);
        }

        tooManyRowsXlsx = tmp.resolve("toomany.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(tooManyRowsXlsx)) {
            Sheet s = wb.createSheet("Big");
            for (int r = 0; r <= ExcelDocumentParser.MAX_ROWS_PER_SHEET; r++) {
                // r ranges 0..200 inclusive → 201 rows → triggers the cap.
                Row row = s.createRow(r);
                row.createCell(0).setCellValue("row" + r);
            }
            wb.write(out);
        }

        csvFile = tmp.resolve("simple.csv");
        Files.writeString(csvFile,
                "name,score,active\n"
                        + "alice,99,true\n"
                        + "bob,87,false\n",
                StandardCharsets.UTF_8);

        quotedCsv = tmp.resolve("quoted.csv");
        // Field 2 contains a comma + escaped quote. Should parse as one logical field.
        Files.writeString(quotedCsv,
                "title,desc\n"
                        + "\"Hello, world\",\"He said \"\"hi\"\"\"\n"
                        + "plain,trailing\n",
                StandardCharsets.UTF_8);

        hugeXlsx = tmp.resolve("huge.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(hugeXlsx)) {
            // 199 rows × 50 cols of "longstring" → ~400k chars output, well over 20k cap.
            Sheet s = wb.createSheet("Wide");
            String filler = "longstring";
            for (int r = 0; r < ExcelDocumentParser.MAX_ROWS_PER_SHEET - 1; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < ExcelDocumentParser.MAX_COLS; c++) {
                    row.createCell(c).setCellValue(filler);
                }
            }
            wb.write(out);
        }
    }

    @Test
    @DisplayName("parseToMarkdown(two-sheet.xlsx) renders both sheets with headers + tables")
    void parseToMarkdown_xlsx_rendersAllSheets() throws Exception {
        String md = ExcelDocumentParser.parseToMarkdown(twoSheetXlsx);
        assertThat(md).contains("## Sheet: Metrics");
        assertThat(md).contains("| name | score | active |");
        assertThat(md).contains("|---|---|---|");
        // Numeric formatting should drop trailing .0 for whole numbers.
        // Fixture sets boolean as r%2==0 — so user1/user3 = false, user2/user4 = true.
        assertThat(md).contains("| user1 | 10 | false |");
        assertThat(md).contains("| user4 | 40 | true |");
        // Booleans
        assertThat(md).contains("| user2 | 20 | true |");
        assertThat(md).contains("| user3 | 30 | false |");

        assertThat(md).contains("## Sheet: Totals");
        // SUM(2,3) = 5 → should render as plain integer string
        assertThat(md).contains("| sum | 5 |");
    }

    @Test
    @DisplayName("parseToMarkdown rejects sheet exceeding row cap")
    void parseToMarkdown_tooManyRows_throws() {
        assertThatThrownBy(() -> ExcelDocumentParser.parseToMarkdown(tooManyRowsXlsx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("EXCEL_SHEET_TOO_LARGE:")
                .hasMessageContaining("Big");
    }

    @Test
    @DisplayName("parseToMarkdown(simple.csv) renders header + body table")
    void parseToMarkdown_csv_rendersTable() throws Exception {
        String md = ExcelDocumentParser.parseToMarkdown(csvFile);
        assertThat(md).contains("## Sheet: simple.csv");
        assertThat(md).contains("| name | score | active |");
        assertThat(md).contains("| alice | 99 | true |");
        assertThat(md).contains("| bob | 87 | false |");
    }

    @Test
    @DisplayName("parseToMarkdown(quoted.csv) handles RFC 4180 quoted fields with comma and escaped quote")
    void parseToMarkdown_csv_quoted_handlesRfc4180() throws Exception {
        String md = ExcelDocumentParser.parseToMarkdown(quotedCsv);
        // The comma inside quotes must NOT split the field
        assertThat(md).contains("| Hello, world | He said \"hi\" |");
        assertThat(md).contains("| plain | trailing |");
    }

    @Test
    @DisplayName("parseCsvContent handles trailing newline and quoted newline inside field")
    void parseCsvContent_innerNewline_kept() {
        String input = "a,\"b\nstill b\",c\n";
        List<List<String>> rows = ExcelDocumentParser.parseCsvContent(input);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("a", "b\nstill b", "c");
    }

    @Test
    @DisplayName("parseToMarkdown truncates xlsx output at MAX_OUTPUT_CHARS with marker")
    void parseToMarkdown_huge_truncates() throws Exception {
        String md = ExcelDocumentParser.parseToMarkdown(hugeXlsx);
        assertThat(md).endsWith(ExcelDocumentParser.TRUNCATION_MARKER);
        int expected = ExcelDocumentParser.MAX_OUTPUT_CHARS
                + ExcelDocumentParser.TRUNCATION_MARKER.length();
        assertThat(md.length()).isBetween(expected - 1, expected);
    }

    @Test
    @DisplayName("parseToMarkdown(missing.xlsx) throws EXCEL_PARSE_FAILED")
    void parseToMarkdown_missingFile_throws() {
        Path nope = tmp.resolve("does-not-exist.xlsx");
        assertThatThrownBy(() -> ExcelDocumentParser.parseToMarkdown(nope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("EXCEL_PARSE_FAILED:");
    }

    @Test
    @DisplayName("parseToMarkdown rejects unsupported extension")
    void parseToMarkdown_unsupportedExtension_throws() throws Exception {
        Path bogus = tmp.resolve("data.tsv");
        Files.writeString(bogus, "a\tb\n");
        assertThatThrownBy(() -> ExcelDocumentParser.parseToMarkdown(bogus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXCEL_PARSE_FAILED")
                .hasMessageContaining("unsupported extension");
    }

    @Test
    @DisplayName("parseToMarkdownWithMetadata exposes sheet_count from a two-sheet workbook")
    void parseToMarkdownWithMetadata_xlsx_capturesSheetCount() throws Exception {
        ExcelDocumentParser.ExcelParseResult result = ExcelDocumentParser.parseToMarkdownWithMetadata(twoSheetXlsx);
        assertThat(result.sheetCount()).isEqualTo(2);
        assertThat(result.markdown()).contains("## Sheet: Metrics");
        assertThat(result.markdown()).contains("## Sheet: Totals");
    }

    @Test
    @DisplayName("parseToMarkdownWithMetadata returns sheetCount=1 for CSV files")
    void parseToMarkdownWithMetadata_csv_singleSheet() throws Exception {
        ExcelDocumentParser.ExcelParseResult result = ExcelDocumentParser.parseToMarkdownWithMetadata(csvFile);
        assertThat(result.sheetCount()).isEqualTo(1);
        assertThat(result.markdown()).contains("## Sheet: simple.csv");
    }
}
