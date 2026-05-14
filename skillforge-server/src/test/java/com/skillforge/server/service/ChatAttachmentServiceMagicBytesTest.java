package com.skillforge.server.service;

import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL-MVP r2 B1: magic-byte validation in
 * {@link ChatAttachmentService#upload}. The HTTP {@code Content-Type} header is
 * attacker-controlled and MUST NOT be the source of truth for kind / MIME.
 * Server-detected MIME is what lands in the DB row and downstream payloads.
 *
 * <p>Test cases mirror the brief's contract:</p>
 * <ul>
 *   <li>Legitimate PNG with declared {@code image/png} → accepted.</li>
 *   <li>Legitimate JPEG with declared {@code image/jpeg} → accepted.</li>
 *   <li>Legitimate PDF with declared {@code application/pdf} → accepted.</li>
 *   <li>ZIP renamed {@code .png} with declared {@code image/png} → rejected.</li>
 *   <li>Truncated PNG (only first byte) → rejected.</li>
 *   <li>Spoofed declared type vs detected → rejected.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceMagicBytesTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    @TempDir
    Path tempStorage;

    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, tempStorage.toString());
        // Rejection tests don't reach repository.save(...); accept-tests do. lenient()
        // so Mockito doesn't complain about the unused stub on rejection runs.
        lenient().when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------ legitimate uploads ------------------------

    @Test
    @DisplayName("legitimate PNG with matching Content-Type → accepted; server-detected MIME stored")
    void legitimatePng_accepted() {
        byte[] pngBytes = concat(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
                // ... arbitrary tail; magic detection only reads the leader
                new byte[]{0, 0, 0, 0, 'I', 'H', 'D', 'R'});
        MultipartFile file = new MockMultipartFile("file", "screen.png", "image/png", pngBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("image");
        assertThat(saved.getMimeType()).isEqualTo("image/png");
        verify(attachmentRepository).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("legitimate JPEG → accepted")
    void legitimateJpeg_accepted() {
        byte[] jpegBytes = concat(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
                new byte[]{0, 0x10, 'J', 'F', 'I', 'F'});
        MultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("image");
        assertThat(saved.getMimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("legitimate JPEG with browser-misnamed image/jpg header → accepted (tolerance)")
    void legitimateJpeg_withImageJpgHeader_accepted() {
        byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        MultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpg", jpegBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        // Server-detected MIME is the canonical image/jpeg, NOT the request header image/jpg.
        assertThat(saved.getMimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("legitimate PDF → accepted")
    void legitimatePdf_accepted() {
        byte[] pdfBytes = "%PDF-1.4\n%âãÏÓ\n".getBytes();
        MultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", pdfBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("pdf");
        assertThat(saved.getMimeType()).isEqualTo("application/pdf");
    }

    // ------------------------ rejections ------------------------

    @Test
    @DisplayName("ZIP renamed .png with image/png header → rejected (no DB row, no file)")
    void zipRenamedAsPng_rejected() {
        // ZIP magic: PK\x03\x04
        byte[] zipBytes = new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0, 0, 0};
        MultipartFile file = new MockMultipartFile("file", "malicious.png", "image/png", zipBytes);

        assertThatThrownBy(() -> service.upload("sess-1", 42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported or unrecognized");
        // Iron Law: rejection means NO DB row written (no orphan).
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("spoofed declared type (PNG bytes claim application/pdf) → rejected")
    void mimeHeaderSpoof_rejected() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        // Real PNG content but claims application/pdf in header.
        MultipartFile file = new MockMultipartFile("file", "spoof.pdf", "application/pdf", pngBytes);

        assertThatThrownBy(() -> service.upload("sess-1", 42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("truncated file (only first byte) → rejected")
    void truncatedFile_rejected() {
        // Only the first byte of PNG magic — too short to match any signature.
        byte[] truncated = new byte[]{(byte) 0x89};
        MultipartFile file = new MockMultipartFile("file", "tiny.png", "image/png", truncated);

        assertThatThrownBy(() -> service.upload("sess-1", 42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported or unrecognized");
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("missing Content-Type header but valid PNG bytes → accepted (header optional)")
    void missingContentType_acceptedWhenBytesValid() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        // Content-Type missing — Spring sends null. We must rely on magic bytes.
        MultipartFile file = new MockMultipartFile("file", "img.png", null, pngBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getKind()).isEqualTo("image");
        assertThat(saved.getMimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("application/octet-stream header treated as 'unspecified' — magic detection is authoritative")
    void octetStreamHeader_detectionAuthoritative() {
        // curl --data-binary defaults to application/octet-stream; we treat it as
        // "header not provided" so the upload still works when the underlying file
        // is legitimately one of our accepted types.
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        MultipartFile file = new MockMultipartFile("file", "img.bin", "application/octet-stream", pngBytes);

        ChatAttachmentEntity saved = service.upload("sess-1", 42L, file);

        assertThat(saved.getMimeType()).isEqualTo("image/png");
    }

    // ------------------------ helpers ------------------------

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
