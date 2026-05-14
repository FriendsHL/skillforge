package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 3 WORD-EXCEL — coverage for the integration of {@code WordDocumentParser}
 * / {@code ExcelDocumentParser} into {@link ChatAttachmentService}'s upload +
 * dispatch + materialize pipeline. Pattern mirrors
 * {@link ChatAttachmentServicePdfScanFallbackTest} (Mockito repo +
 * {@link TempDir} storage).
 *
 * <p>Scenarios covered:</p>
 * <ul>
 *   <li><b>Upload</b> — .docx / .xlsx / .csv accepted with kind binding + default
 *       processing_mode + correct size enforcement.</li>
 *   <li><b>Materialize</b> — word_ref / excel_ref / csv_ref expand to a single
 *       text ContentBlock with the expected header envelope. Excel header
 *       carries "(N sheets)" derived from {@code parseToMarkdownWithMetadata}.</li>
 *   <li><b>Failure</b> — corrupted bytes → error_code persisted, text-only
 *       placeholder still shipped (LLM call never fails).</li>
 *   <li><b>previewKind</b> — header-aware classification used by the controller
 *       to bypass the vision gate for text-extraction file types.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceWordExcelIntegrationTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    @TempDir
    Path tempStorage;

    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, tempStorage.toString());
        lenient().when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── upload ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("upload .docx → kind=word, processing_mode=WORD_TEXT")
    void upload_docx_setsWordKind() throws Exception {
        byte[] docxBytes = buildSimpleDocx();
        MockMultipartFile file = new MockMultipartFile("file", "report.docx",
                ChatAttachmentService.MIME_DOCX, docxBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("word");
        assertThat(saved.getMimeType()).isEqualTo(ChatAttachmentService.MIME_DOCX);
        assertThat(saved.getProcessingMode()).isEqualTo(ChatAttachmentService.MODE_WORD_TEXT);
        // page_count not populated for word at upload (we don't pre-parse).
        assertThat(saved.getPageCount()).isNull();
    }

    @Test
    @DisplayName("upload .xlsx → kind=excel, processing_mode=EXCEL_TEXT, sheet_count refined on materialize")
    void upload_xlsx_setsExcelKind() throws Exception {
        byte[] xlsxBytes = buildTwoSheetXlsx();
        MockMultipartFile file = new MockMultipartFile("file", "data.xlsx",
                ChatAttachmentService.MIME_XLSX, xlsxBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("excel");
        assertThat(saved.getMimeType()).isEqualTo(ChatAttachmentService.MIME_XLSX);
        assertThat(saved.getProcessingMode()).isEqualTo(ChatAttachmentService.MODE_EXCEL_TEXT);
        // sheet_count starts null — materialize refines.
        assertThat(saved.getPageCount()).isNull();
    }

    @Test
    @DisplayName("upload .csv → kind=csv, processing_mode=CSV_TEXT")
    void upload_csv_setsCsvKind() {
        byte[] csvBytes = "name,score\nalice,99\nbob,87\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "data.csv",
                ChatAttachmentService.MIME_CSV, csvBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("csv");
        assertThat(saved.getMimeType()).isEqualTo(ChatAttachmentService.MIME_CSV);
        assertThat(saved.getProcessingMode()).isEqualTo(ChatAttachmentService.MODE_CSV_TEXT);
    }

    @Test
    @DisplayName("upload ZIP file with application/zip header (no docx/xlsx) → rejected")
    void upload_zipWithoutOfficeMime_rejected() {
        // ZIP magic but generic application/zip header — refuse: detectMagic
        // cannot bind the kind without a Word/Excel MIME hint.
        byte[] zipBytes = new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "archive.zip",
                "application/zip", zipBytes);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.upload("sess-1", 42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported or unrecognized");
    }

    @Test
    @DisplayName("upload .csv with embedded NUL byte → rejected (CSV_NOT_TEXT guard)")
    void upload_csvWithNul_rejected() {
        byte[] mixedBytes = new byte[]{'a', ',', 'b', 0x00, '\n', 'c', ',', 'd'};
        MockMultipartFile file = new MockMultipartFile("file", "binary.csv",
                ChatAttachmentService.MIME_CSV, mixedBytes);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.upload("sess-1", 42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported or unrecognized");
    }

    // ─── previewKind (used by controller vision gate) ────────────────────

    @Test
    @DisplayName("previewKind classifies .docx / .xlsx / .csv without side effects")
    void previewKind_returnsKindWithoutSideEffects() throws Exception {
        MockMultipartFile docx = new MockMultipartFile("file", "r.docx",
                ChatAttachmentService.MIME_DOCX, buildSimpleDocx());
        MockMultipartFile xlsx = new MockMultipartFile("file", "d.xlsx",
                ChatAttachmentService.MIME_XLSX, buildTwoSheetXlsx());
        MockMultipartFile csv = new MockMultipartFile("file", "d.csv",
                ChatAttachmentService.MIME_CSV,
                "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile pdf = new MockMultipartFile("file", "p.pdf",
                "application/pdf", "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8));

        assertThat(service.previewKind(docx)).isEqualTo("word");
        assertThat(service.previewKind(xlsx)).isEqualTo("excel");
        assertThat(service.previewKind(csv)).isEqualTo("csv");
        assertThat(service.previewKind(pdf)).isEqualTo("pdf");
        // No DB writes from preview-only path.
        org.mockito.Mockito.verifyNoInteractions(attachmentRepository);
    }

    // ─── materialize ─────────────────────────────────────────────────────

    @Test
    @DisplayName("materializeForProvider(word_ref) emits one text block with header + markdown")
    void materialize_wordRef_emitsTextBlock() throws Exception {
        Path docxFile = tempStorage.resolve("report.docx");
        Files.write(docxFile, buildSimpleDocx());

        ChatAttachmentEntity row = newAttachmentRow("att-w", "word", docxFile, "report.docx");
        row.setProcessingMode(ChatAttachmentService.MODE_WORD_TEXT);
        when(attachmentRepository.findById(eq("att-w"))).thenReturn(Optional.of(row));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.wordRef("att-w", "report.docx")));

        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        assertThat(blocks).hasSize(1);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("text");
        assertThat(cb.getText()).startsWith("[Word Document: report.docx]\n\n");
        assertThat(cb.getText()).contains("Hello world");
        // No parse error → error_code stays null.
        assertThat(row.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("materializeForProvider(excel_ref) emits one text block; header carries '(N sheets)'; sheet_count persisted")
    void materialize_excelRef_emitsTextBlockWithSheetCount() throws Exception {
        Path xlsxFile = tempStorage.resolve("data.xlsx");
        Files.write(xlsxFile, buildTwoSheetXlsx());

        ChatAttachmentEntity row = newAttachmentRow("att-e", "excel", xlsxFile, "data.xlsx");
        row.setProcessingMode(ChatAttachmentService.MODE_EXCEL_TEXT);
        when(attachmentRepository.findById(eq("att-e"))).thenReturn(Optional.of(row));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.excelRef("att-e", "data.xlsx", null)));

        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        assertThat(blocks).hasSize(1);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("text");
        assertThat(cb.getText()).startsWith("[Excel Spreadsheet: data.xlsx (2 sheets)]\n\n");
        assertThat(cb.getText()).contains("## Sheet:");
        // sheet_count persisted onto entity.pageCount (dual semantics).
        assertThat(row.getPageCount()).isEqualTo(2);
        verify(attachmentRepository, atLeastOnce()).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("materializeForProvider(csv_ref) emits one text block with CSV header")
    void materialize_csvRef_emitsTextBlock() throws Exception {
        Path csvFile = tempStorage.resolve("data.csv");
        Files.writeString(csvFile, "name,score\nalice,99\nbob,87\n", StandardCharsets.UTF_8);

        ChatAttachmentEntity row = newAttachmentRow("att-c", "csv", csvFile, "data.csv");
        row.setProcessingMode(ChatAttachmentService.MODE_CSV_TEXT);
        when(attachmentRepository.findById(eq("att-c"))).thenReturn(Optional.of(row));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.csvRef("att-c", "data.csv")));

        Message expanded = service.materializeForProvider("sess-1", msg);

        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        assertThat(blocks).hasSize(1);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("text");
        assertThat(cb.getText()).startsWith("[CSV File: data.csv]\n\n");
        assertThat(cb.getText()).contains("| alice | 99 |");
    }

    @Test
    @DisplayName("materializeForProvider(excel_ref) with corrupted file → error_code=EXCEL_PARSE_FAILED + text fallback")
    void materialize_excelRef_corrupted_setsErrorCode() throws Exception {
        // Plain text file renamed .xlsx → POI's XSSFWorkbook rejects (not a ZIP).
        // parseToMarkdownWithMetadata wraps the underlying POI exception in an
        // IllegalStateException with the EXCEL_PARSE_FAILED diagnostic prefix.
        Path bogus = tempStorage.resolve("bogus.xlsx");
        Files.writeString(bogus, "not really an xlsx file", StandardCharsets.UTF_8);

        ChatAttachmentEntity row = newAttachmentRow("att-bad-e", "excel", bogus, "bogus.xlsx");
        row.setProcessingMode(ChatAttachmentService.MODE_EXCEL_TEXT);
        when(attachmentRepository.findById(eq("att-bad-e"))).thenReturn(Optional.of(row));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.excelRef("att-bad-e", "bogus.xlsx", null)));

        Message expanded = service.materializeForProvider("sess-1", msg);

        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        assertThat(blocks).hasSize(1);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("text");
        assertThat(cb.getText()).contains("(failed to parse");
        // Error envelope shape: prefix matches the spec ("[Excel Spreadsheet: ...]").
        assertThat(cb.getText()).startsWith("[Excel Spreadsheet: bogus.xlsx]");
        assertThat(row.getErrorCode()).isEqualTo(ChatAttachmentService.EXCEL_PARSE_FAILED);
        assertThat(row.getErrorMessage()).isNotBlank();
        // sheet_count never populated because parse failed before reaching the
        // success-path setter — pageCount stays null.
        assertThat(row.getPageCount()).isNull();
        verify(attachmentRepository, atLeastOnce()).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("materializeForProvider(csv_ref) with non-UTF-8 bytes → error_code=CSV_PARSE_FAILED + text fallback")
    void materialize_csvRef_corrupted_setsErrorCode() throws Exception {
        // 0xFF is not a valid UTF-8 leading byte; Files.readString(UTF_8) inside
        // ExcelDocumentParser.parseCsv throws MalformedInputException which the
        // parser wraps with the CSV_PARSE_FAILED diagnostic envelope.
        Path bogus = tempStorage.resolve("invalid.csv");
        Files.write(bogus, new byte[]{0x68, 0x69, (byte) 0xFF, 0x21});

        ChatAttachmentEntity row = newAttachmentRow("att-bad-c", "csv", bogus, "invalid.csv");
        row.setProcessingMode(ChatAttachmentService.MODE_CSV_TEXT);
        when(attachmentRepository.findById(eq("att-bad-c"))).thenReturn(Optional.of(row));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.csvRef("att-bad-c", "invalid.csv")));

        Message expanded = service.materializeForProvider("sess-1", msg);

        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        assertThat(blocks).hasSize(1);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("text");
        assertThat(cb.getText()).contains("(failed to parse");
        assertThat(cb.getText()).startsWith("[CSV File: invalid.csv]");
        assertThat(row.getErrorCode()).isEqualTo(ChatAttachmentService.CSV_PARSE_FAILED);
        assertThat(row.getErrorMessage()).isNotBlank();
        verify(attachmentRepository, atLeastOnce()).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("materializeForProvider(word_ref) with corrupted file → error_code=WORD_PARSE_FAILED + text fallback")
    void materialize_wordRef_corrupted_setsErrorCode() throws Exception {
        Path bogus = tempStorage.resolve("bogus.docx");
        Files.writeString(bogus, "not really a docx file", StandardCharsets.UTF_8);

        ChatAttachmentEntity row = newAttachmentRow("att-bad-w", "word", bogus, "bogus.docx");
        row.setProcessingMode(ChatAttachmentService.MODE_WORD_TEXT);
        when(attachmentRepository.findById(eq("att-bad-w"))).thenReturn(Optional.of(row));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.wordRef("att-bad-w", "bogus.docx")));

        Message expanded = service.materializeForProvider("sess-1", msg);

        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        assertThat(blocks).hasSize(1);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("text");
        assertThat(cb.getText()).contains("(failed to parse");
        assertThat(row.getErrorCode()).isEqualTo(ChatAttachmentService.WORD_PARSE_FAILED);
        assertThat(row.getErrorMessage()).isNotBlank();
        verify(attachmentRepository, atLeastOnce()).save(any(ChatAttachmentEntity.class));
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private ChatAttachmentEntity newAttachmentRow(String id, String kind, Path file, String filename) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId("sess-1");
        e.setUserId(42L);
        e.setKind(kind);
        e.setFilename(filename);
        e.setSizeBytes(file.toFile().length());
        e.setStoragePath(file.toString());
        e.setStatus("uploaded");
        if ("word".equals(kind)) e.setMimeType(ChatAttachmentService.MIME_DOCX);
        else if ("excel".equals(kind)) e.setMimeType(ChatAttachmentService.MIME_XLSX);
        else if ("csv".equals(kind)) e.setMimeType(ChatAttachmentService.MIME_CSV);
        return e;
    }

    private static byte[] buildSimpleDocx() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun run = p.createRun();
            run.setText("Hello world");
            doc.write(baos);
        }
        return baos.toByteArray();
    }

    private static byte[] buildTwoSheetXlsx() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = baos) {
            Sheet a = wb.createSheet("Alpha");
            Row ha = a.createRow(0);
            ha.createCell(0).setCellValue("name");
            ha.createCell(1).setCellValue("score");
            Row r1 = a.createRow(1);
            r1.createCell(0).setCellValue("alice");
            r1.createCell(1).setCellValue(99.0);

            Sheet b = wb.createSheet("Beta");
            Row hb = b.createRow(0);
            hb.createCell(0).setCellValue("label");
            hb.createCell(1).setCellValue("value");
            Row r2 = b.createRow(1);
            r2.createCell(0).setCellValue("x");
            r2.createCell(1).setCellValue(1.0);

            wb.write(out);
        }
        return baos.toByteArray();
    }
}
