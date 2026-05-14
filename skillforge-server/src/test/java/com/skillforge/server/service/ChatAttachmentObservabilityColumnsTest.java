package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V73 / MULTIMODAL-OBSERVABILITY-COLUMNS — Wave 1-A test coverage for the four new
 * observability columns on {@code t_chat_attachment}: {@code processingMode},
 * {@code extractedTextChars}, {@code errorCode}, {@code errorMessage}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Entity getter/setter round-trip with all 4 fields set + default NULL state.</li>
 *   <li>{@link ChatAttachmentService#upload} sets {@code processingMode = IMAGE_BLOCK_INLINE}
 *       on image success path.</li>
 *   <li>{@link ChatAttachmentService#upload} sets {@code processingMode = PDF_TEXT}
 *       (default, refined later at materialize) on PDF success path.</li>
 *   <li>{@link ChatAttachmentService#materializeForProvider} refines
 *       {@code processingMode} to {@code PDF_TEXT_EMPTY} when the PDF yields no
 *       extractable text (scan-only / parse failure / zero pages).</li>
 *   <li>{@link ChatAttachmentService#materializeForProvider} persists the refined
 *       entity (verify {@code repository.save} called when fields changed).</li>
 * </ul>
 *
 * <p>Pattern mirrors {@link ChatAttachmentServiceMagicBytesTest} (Mockito repo +
 * {@link TempDir} storage). JPA persistence proper is exercised by the regular
 * Spring Boot infrastructure running the V73 migration; the assertions here cover
 * the service-side population logic.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentObservabilityColumnsTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    @TempDir
    Path tempStorage;

    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, tempStorage.toString());
        // upload() saves; materialize() also saves when refined. lenient() to avoid
        // unused-stub warnings in the entity-roundtrip case which doesn't go via service.
        lenient().when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── 1. entity round-trip ───

    @Test
    @DisplayName("entity getter/setter: all 4 OBS fields round-trip including NULL")
    void entityRoundTrip_allFieldsAndNulls() {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        // baseline required fields
        e.setId("att-1");
        e.setSessionId("sess-1");
        e.setUserId(42L);
        e.setKind("pdf");
        e.setMimeType("application/pdf");
        e.setFilename("paper.pdf");
        e.setSizeBytes(1024L);
        e.setStoragePath("/tmp/paper.pdf");
        e.setStatus("uploaded");

        // ── default NULL state (no setter calls) ──
        assertThat(e.getProcessingMode()).isNull();
        assertThat(e.getExtractedTextChars()).isNull();
        assertThat(e.getErrorCode()).isNull();
        assertThat(e.getErrorMessage()).isNull();

        // ── set all 4 ──
        e.setProcessingMode("PDF_TEXT_TRUNCATED");
        e.setExtractedTextChars(20_000);
        e.setErrorCode("PDF_TEXT_EMPTY_NEEDS_VISION");
        e.setErrorMessage("page 1 yielded no text — likely scan");

        assertThat(e.getProcessingMode()).isEqualTo("PDF_TEXT_TRUNCATED");
        assertThat(e.getExtractedTextChars()).isEqualTo(20_000);
        assertThat(e.getErrorCode()).isEqualTo("PDF_TEXT_EMPTY_NEEDS_VISION");
        assertThat(e.getErrorMessage()).startsWith("page 1 yielded no text");

        // ── re-null individually (caller chooses to clear) ──
        e.setErrorCode(null);
        e.setErrorMessage(null);
        assertThat(e.getErrorCode()).isNull();
        assertThat(e.getErrorMessage()).isNull();
        assertThat(e.getProcessingMode()).isEqualTo("PDF_TEXT_TRUNCATED");  // still set
    }

    // ─── 2. upload success — default processingMode ───

    @Test
    @DisplayName("upload(image): processingMode defaults to IMAGE_BLOCK_INLINE on success path")
    void uploadImage_setsImageBlockInline() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        MultipartFile file = new MockMultipartFile("file", "screen.png", "image/png", pngBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getProcessingMode()).isEqualTo("IMAGE_BLOCK_INLINE");
        assertThat(saved.getExtractedTextChars()).isNull();  // image path doesn't extract text
        assertThat(saved.getErrorCode()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("upload(pdf): processingMode defaults to PDF_TEXT on success path")
    void uploadPdf_setsPdfTextDefault() {
        byte[] pdfBytes = "%PDF-1.4\n%âãÏÓ\n".getBytes();
        MultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", pdfBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        // PDF_TEXT is the upload-time default; materialize will refine to
        // PDF_TEXT_TRUNCATED / PDF_TEXT_EMPTY when the engine first expands it.
        assertThat(saved.getProcessingMode()).isEqualTo("PDF_TEXT");
        assertThat(saved.getErrorCode()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    // ─── 3. magic-bytes rejection — NO DB row (verifies comment claim) ───

    @Test
    @DisplayName("upload rejection (unsupported content): no DB row written, no OBS fields populated")
    void uploadRejection_writesNoDbRow() {
        byte[] zipBytes = new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0, 0, 0};
        MultipartFile file = new MockMultipartFile("file", "bad.png", "image/png", zipBytes);

        try {
            service.upload("sess-1", 42L, file);
        } catch (IllegalArgumentException ignored) {
            // expected
        }
        // Iron Law from upload() comment: rejected = NO save. Documented behavior;
        // future FAILED-row enhancement is out of scope.
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
    }

    // ─── 4. materializeForProvider refines PDF processingMode + extractedTextChars ───

    @Test
    @DisplayName("materializeForProvider(pdf_ref): empty-text PDF refines mode to PDF_TEXT_EMPTY and saves")
    void materialize_pdfTextEmpty_refinesModeAndSaves() throws Exception {
        // The path doesn't point to a real PDF, so PDFBox.Loader.loadPDF throws
        // IOException → text remains "" → PDF_TEXT_EMPTY mode.
        ChatAttachmentEntity pdfRow = new ChatAttachmentEntity();
        pdfRow.setId("att-pdf");
        pdfRow.setSessionId("sess-1");
        pdfRow.setUserId(42L);
        pdfRow.setKind("pdf");
        pdfRow.setMimeType("application/pdf");
        pdfRow.setFilename("scan.pdf");
        pdfRow.setSizeBytes(10L);
        Path fakePdf = tempStorage.resolve("scan.pdf");
        Files.writeString(fakePdf, "not a real pdf, will fail to parse");
        pdfRow.setStoragePath(fakePdf.toString());
        pdfRow.setStatus("uploaded");
        pdfRow.setProcessingMode("PDF_TEXT");  // upload-time default
        pdfRow.setExtractedTextChars(null);

        when(attachmentRepository.findById(eq("att-pdf"))).thenReturn(Optional.of(pdfRow));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.pdfRef("att-pdf", "scan.pdf", 1)));

        Message expanded = service.materializeForProvider("sess-1", msg);

        // text block is expanded
        assertThat(expanded).isNotSameAs(msg);
        // entity mutated to reflect empty-extraction outcome
        assertThat(pdfRow.getProcessingMode()).isEqualTo("PDF_TEXT_EMPTY");
        assertThat(pdfRow.getExtractedTextChars()).isEqualTo(0);
        // and persisted (refined ≠ initial)
        ArgumentCaptor<ChatAttachmentEntity> captor = ArgumentCaptor.forClass(ChatAttachmentEntity.class);
        verify(attachmentRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("att-pdf");
        assertThat(captor.getValue().getProcessingMode()).isEqualTo("PDF_TEXT_EMPTY");
    }

    @Test
    @DisplayName("materializeForProvider(image_ref): does NOT save the entity (image path has no refinement)")
    void materialize_imageRef_doesNotSaveEntity() throws Exception {
        ChatAttachmentEntity imgRow = new ChatAttachmentEntity();
        imgRow.setId("att-img");
        imgRow.setSessionId("sess-1");
        imgRow.setUserId(42L);
        imgRow.setKind("image");
        imgRow.setMimeType("image/png");
        imgRow.setFilename("a.png");
        imgRow.setSizeBytes(8L);
        Path fakeImg = tempStorage.resolve("a.png");
        Files.write(fakeImg, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        imgRow.setStoragePath(fakeImg.toString());
        imgRow.setStatus("uploaded");
        imgRow.setProcessingMode("IMAGE_BLOCK_INLINE");

        when(attachmentRepository.findById(eq("att-img"))).thenReturn(Optional.of(imgRow));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.imageRef("att-img", "image/png", "a.png")));

        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);  // image_ref → image was expanded
        // No refinement → no save (image path stays IMAGE_BLOCK_INLINE).
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
        assertThat(imgRow.getProcessingMode()).isEqualTo("IMAGE_BLOCK_INLINE");
    }
}
