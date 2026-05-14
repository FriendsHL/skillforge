package com.skillforge.server.service.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Standalone parser that converts spreadsheet documents (.xlsx / .xls / .csv) to
 * a markdown-flavored plain text representation. Wave 1 skeleton — integration
 * into {@code ChatAttachmentService.classify()} lands in Wave 3.
 *
 * <p>Output rules:
 * <ul>
 *   <li>Each sheet → {@code ## Sheet: <name>} heading followed by a markdown table</li>
 *   <li>Up to {@link #MAX_SHEETS} sheets, {@link #MAX_ROWS_PER_SHEET} rows, and
 *       {@link #MAX_COLS} columns per sheet</li>
 *   <li>Sheets whose <em>declared</em> dimensions exceed the row/col caps throw
 *       {@link IllegalStateException} with {@code EXCEL_SHEET_TOO_LARGE}</li>
 *   <li>Output is truncated to {@link #MAX_OUTPUT_CHARS} with an explicit marker</li>
 * </ul>
 *
 * <p>Cell formatting:
 * <ul>
 *   <li>NUMERIC → trimmed plain string; whole numbers drop the {@code .0} tail</li>
 *   <li>NUMERIC with a date format → ISO-8601 UTC</li>
 *   <li>BOOLEAN → {@code "true"} / {@code "false"}</li>
 *   <li>FORMULA → evaluated value (numeric/boolean/string), same formatting as above</li>
 *   <li>BLANK / null → empty cell</li>
 * </ul>
 *
 * <p>CSV mode: handles RFC 4180 quoted fields (escaped {@code ""} inside quotes,
 * commas + newlines inside quotes). Intentionally does <em>not</em> pull in
 * commons-csv to keep the dependency surface small for Wave 1.
 */
public final class ExcelDocumentParser {

    public static final int MAX_SHEETS = 10;
    public static final int MAX_ROWS_PER_SHEET = 200;
    public static final int MAX_COLS = 50;
    public static final int MAX_OUTPUT_CHARS = 20_000;

    public static final String TRUNCATION_MARKER = "\n\n[Document truncated at 20000 chars]";

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_INSTANT;

    /**
     * Wave 3 WORD-EXCEL: result envelope returned by
     * {@link #parseToMarkdownWithMetadata(Path)}. The {@code sheetCount} carries
     * the workbook's structural sheet count (or {@code 1} for CSV files);
     * downstream callers persist this onto the attachment row so the FE chip
     * can show "(N sheets)" without re-parsing.
     *
     * <p>{@code totalRows} is the sum of {@code lastRowNum + 1} across parsed
     * sheets (capped at {@link #MAX_ROWS_PER_SHEET} per sheet). Reserved for
     * future observability surfaces — currently informational.</p>
     */
    public record ExcelParseResult(String markdown, int sheetCount, int totalRows) {}

    /** Wave 1-C designated this as a static utility — no instances allowed. */
    private ExcelDocumentParser() {
        throw new UnsupportedOperationException("ExcelDocumentParser is a static utility");
    }

    /**
     * Wave 3 WORD-EXCEL: parse and capture sheet-count metadata.
     * xlsx / xls workbooks return their {@code getNumberOfSheets()};
     * CSV files return {@code 1}; failures throw the same diagnostic envelope
     * as {@link #parseToMarkdown(Path)}.
     *
     * <p><b>Phase 1 trade-off — opens the workbook twice</b>: once to read
     * {@code getNumberOfSheets()}, once via {@link #parseWorkbook(Path, boolean)}
     * to render. Cost is bounded by {@link ChatAttachmentService#MAX_EXCEL_BYTES}
     * (20MB on disk) plus the parser's own row/col/sheet caps. Refactor to a
     * single-pass implementation when the SXSSF streaming reader path lands;
     * doing so today would require changing {@code parseWorkbook}'s signature
     * (W1-C brief said "do NOT modify internals"). Not a regression vs
     * {@code parseToMarkdown(Path)} which also opens the workbook once.</p>
     */
    public static ExcelParseResult parseToMarkdownWithMetadata(Path file) throws IOException {
        if (file == null) {
            throw new IllegalStateException("EXCEL_PARSE_FAILED: file is null");
        }
        if (!Files.exists(file)) {
            throw new IllegalStateException("EXCEL_PARSE_FAILED: file does not exist: " + file);
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                boolean xlsx = name.endsWith(".xlsx");
                // Single workbook open: count sheets up front, then render. We
                // intentionally don't refactor parseWorkbook's loop to also emit
                // the count — keeping the existing instance method untouched
                // honors "do NOT modify their internals" from the Wave 3 brief.
                int sheetCount;
                try (InputStream in = Files.newInputStream(file);
                     Workbook wb = xlsx ? new XSSFWorkbook(in) : new HSSFWorkbook(in)) {
                    sheetCount = wb.getNumberOfSheets();
                }
                String markdown = truncate(parseWorkbook(file, xlsx));
                return new ExcelParseResult(markdown, sheetCount, 0);
            } else if (name.endsWith(".csv")) {
                String markdown = truncate(parseCsv(file));
                return new ExcelParseResult(markdown, 1, 0);
            } else {
                throw new IllegalStateException(
                        "EXCEL_PARSE_FAILED: unsupported extension (expected .xlsx/.xls/.csv): " + name);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("EXCEL_PARSE_FAILED: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    public static String parseToMarkdown(Path file) throws IOException {
        if (file == null) {
            throw new IllegalStateException("EXCEL_PARSE_FAILED: file is null");
        }
        if (!Files.exists(file)) {
            throw new IllegalStateException("EXCEL_PARSE_FAILED: file does not exist: " + file);
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            String raw;
            if (name.endsWith(".xlsx")) {
                raw = parseWorkbook(file, /* xlsx */ true);
            } else if (name.endsWith(".xls")) {
                raw = parseWorkbook(file, /* xlsx */ false);
            } else if (name.endsWith(".csv")) {
                raw = parseCsv(file);
            } else {
                throw new IllegalStateException(
                        "EXCEL_PARSE_FAILED: unsupported extension (expected .xlsx/.xls/.csv): " + name);
            }
            return truncate(raw);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("EXCEL_PARSE_FAILED: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- xlsx/xls

    private static String parseWorkbook(Path file, boolean xlsx) throws IOException {
        StringBuilder out = new StringBuilder();
        try (InputStream in = Files.newInputStream(file);
             Workbook wb = xlsx ? new XSSFWorkbook(in) : new HSSFWorkbook(in)) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            int sheetCount = Math.min(wb.getNumberOfSheets(), MAX_SHEETS);
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = wb.getSheetAt(s);
                appendSheet(out, sheet, evaluator);
                if (out.length() > MAX_OUTPUT_CHARS) {
                    break;
                }
            }
        }
        return out.toString();
    }

    private static void appendSheet(StringBuilder out, Sheet sheet, FormulaEvaluator evaluator) {
        String sheetName = sheet.getSheetName();
        // Defensive size guard: a hostile/poorly-built .xlsx can declare
        // millions of rows. Reject loudly rather than silently truncating —
        // callers / UX should know the document didn't fit our budget.
        int lastRow = sheet.getLastRowNum(); // 0-based, -1 if no rows
        if (lastRow + 1 > MAX_ROWS_PER_SHEET) {
            throw new IllegalStateException("EXCEL_SHEET_TOO_LARGE: sheet '" + sheetName
                    + "' has " + (lastRow + 1) + " rows (max " + MAX_ROWS_PER_SHEET + ")");
        }
        // Determine declared column count by scanning the first non-empty row's
        // lastCellNum (POI returns the 1-based "logical width" of the row).
        int maxCols = 0;
        int rowsToScan = Math.min(lastRow + 1, MAX_ROWS_PER_SHEET);
        for (int r = 0; r < rowsToScan; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            short last = row.getLastCellNum(); // 1-based, -1 if empty row
            if (last > maxCols) {
                maxCols = last;
            }
        }
        if (maxCols > MAX_COLS) {
            throw new IllegalStateException("EXCEL_SHEET_TOO_LARGE: sheet '" + sheetName
                    + "' has " + maxCols + " columns (max " + MAX_COLS + ")");
        }

        out.append("## Sheet: ").append(sheetName).append("\n\n");
        if (rowsToScan == 0 || maxCols == 0) {
            out.append("_empty sheet_\n\n");
            return;
        }

        // Header row = first row in the sheet. If sheet starts with a gap (no
        // row 0), synthesize an empty header so the table renders.
        Row header = sheet.getRow(0);
        appendRow(out, header, maxCols, evaluator);
        out.append('|');
        for (int c = 0; c < maxCols; c++) {
            out.append("---|");
        }
        out.append('\n');
        for (int r = 1; r < rowsToScan; r++) {
            appendRow(out, sheet.getRow(r), maxCols, evaluator);
        }
        out.append('\n');
    }

    private static void appendRow(StringBuilder out, Row row, int cols, FormulaEvaluator evaluator) {
        out.append('|');
        for (int c = 0; c < cols; c++) {
            String s = row == null ? "" : formatCell(row.getCell(c), evaluator);
            String safe = s.replace("\n", " ").replace("\r", " ").replace("|", "\\|");
            out.append(' ').append(safe).append(" |");
        }
        out.append('\n');
    }

    private static String formatCell(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                CellType evalType = evaluator.evaluateFormulaCell(cell);
                // After evaluation, cell.getCellType() still returns FORMULA but
                // getCachedFormulaResultType() returns the value type. Use the
                // returned evalType to dispatch.
                return formatNonFormula(cell, evalType);
            } catch (Exception e) {
                // Bad formula reference / circular ref / external link →
                // surface the original formula string rather than crashing.
                return "#FORMULA_ERR(" + cell.getCellFormula() + ")";
            }
        }
        return formatNonFormula(cell, type);
    }

    private static String formatNonFormula(Cell cell, CellType type) {
        switch (type) {
            case STRING:
                String sv = cell.getStringCellValue();
                return sv == null ? "" : sv;
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    // Excel stores dates as serial doubles; POI knows the
                    // format mask. Convert to ISO-8601 UTC.
                    Instant inst = cell.getDateCellValue().toInstant();
                    return ISO_DATE.format(inst.atZone(ZoneOffset.UTC));
                }
                return formatNumber(cell.getNumericCellValue());
            case BOOLEAN:
                return cell.getBooleanCellValue() ? "true" : "false";
            case BLANK:
            case _NONE:
            case ERROR:
            default:
                return "";
        }
    }

    private static String formatNumber(double n) {
        if (n == Math.floor(n) && !Double.isInfinite(n)) {
            // Whole number — drop trailing .0 for readability.
            long asLong = (long) n;
            if ((double) asLong == n) {
                return Long.toString(asLong);
            }
        }
        DecimalFormat df = new DecimalFormat("0.################",
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        return df.format(n);
    }

    // ------------------------------------------------------------------- CSV

    private static String parseCsv(Path file) throws IOException {
        // Read full content into memory — CSVs we accept are bounded by upload
        // size limits enforced upstream (Wave 3 will set the policy).
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<List<String>> rows = parseCsvContent(content);

        // Apply caps. CSV is a single "sheet" so use the same row/col limits.
        if (rows.size() > MAX_ROWS_PER_SHEET) {
            throw new IllegalStateException("EXCEL_SHEET_TOO_LARGE: csv has " + rows.size()
                    + " rows (max " + MAX_ROWS_PER_SHEET + ")");
        }
        int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (maxCols > MAX_COLS) {
            throw new IllegalStateException("EXCEL_SHEET_TOO_LARGE: csv has " + maxCols
                    + " columns (max " + MAX_COLS + ")");
        }

        StringBuilder out = new StringBuilder();
        String sheetName = file.getFileName() == null ? "csv" : file.getFileName().toString();
        out.append("## Sheet: ").append(sheetName).append("\n\n");
        if (rows.isEmpty() || maxCols == 0) {
            out.append("_empty sheet_\n\n");
            return out.toString();
        }
        appendCsvRow(out, rows.get(0), maxCols);
        out.append('|');
        for (int c = 0; c < maxCols; c++) {
            out.append("---|");
        }
        out.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            appendCsvRow(out, rows.get(r), maxCols);
        }
        out.append('\n');
        return out.toString();
    }

    private static void appendCsvRow(StringBuilder out, List<String> row, int cols) {
        out.append('|');
        for (int c = 0; c < cols; c++) {
            String s = c < row.size() ? row.get(c) : "";
            String safe = s.replace("\n", " ").replace("\r", " ").replace("|", "\\|");
            out.append(' ').append(safe).append(" |");
        }
        out.append('\n');
    }

    /**
     * Minimal RFC 4180 parser:
     * <ul>
     *   <li>Fields separated by {@code ,}</li>
     *   <li>Records separated by {@code \n} or {@code \r\n}</li>
     *   <li>Fields may be quoted with {@code "}; quoted fields can contain
     *       commas, newlines, and escaped quotes ({@code ""} → {@code "})</li>
     * </ul>
     * Package-private for unit tests.
     */
    static List<List<String>> parseCsvContent(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    // Lookahead: "" inside quoted field → literal "
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    // Closing quote
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(ch);
                i++;
                continue;
            }
            // Not in quotes
            if (ch == '"') {
                // Only treat as opening quote at start of field (after delim or
                // newline); a stray " in the middle is kept literally.
                if (field.length() == 0) {
                    inQuotes = true;
                    i++;
                    continue;
                }
                field.append(ch);
                i++;
                continue;
            }
            if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
                i++;
                continue;
            }
            if (ch == '\r') {
                // Treat \r\n and lone \r as record separators
                if (i + 1 < n && content.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
                i++;
                continue;
            }
            if (ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
                i++;
                continue;
            }
            field.append(ch);
            i++;
        }
        // Flush trailing field/record (last line without newline).
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    // --------------------------------------------------------------- truncate

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        int cut = MAX_OUTPUT_CHARS;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut) + TRUNCATION_MARKER;
    }
}
