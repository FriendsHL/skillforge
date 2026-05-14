package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_chat_attachment", indexes = {
        @Index(name = "idx_chat_attachment_session", columnList = "session_id, created_at"),
        @Index(name = "idx_chat_attachment_session_seq", columnList = "session_id, seq_no")
})
public class ChatAttachmentEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "seq_no")
    private Long seqNo;

    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    @Column(name = "mime_type", length = 128, nullable = false)
    private String mimeType;

    @Column(name = "filename", length = 255, nullable = false)
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * Structural unit count for the attachment, nullable.
     *
     * <p><b>Dual semantics (Wave 3 WORD-EXCEL, 2026-05-14)</b>: reused for Excel
     * sheet count when {@link #kind} = {@code "excel"}. Wire JSON serializes as
     * {@code page_count} on {@code pdf_ref} ContentBlocks and as
     * {@code sheet_count} on {@code excel_ref} ContentBlocks — the column name
     * stays {@code page_count} for backward compatibility with the V70 schema
     * + existing FE consumers that expect {@code pageCount} on the upload
     * response. Consider rename to {@code unitCount} or split into two columns
     * in a future migration if a third kind needs its own count semantics.</p>
     *
     * <p>Population sites:</p>
     * <ul>
     *   <li>PDF: set at upload via {@code readPdfPageCountQuietly}.</li>
     *   <li>Excel: starts null at upload; refined on first {@code materializeForProvider}
     *       once the parser exposes {@code sheetCount}.</li>
     *   <li>Image / Word / CSV: always null (no structural unit semantics).</li>
     * </ul>
     */
    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "storage_path", columnDefinition = "TEXT", nullable = false)
    private String storagePath;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "uploaded";

    // ─── Observability columns (V73, MULTIMODAL-OBSERVABILITY-COLUMNS) ───
    // All four are nullable. Wire-level field names use camelCase
    // (errorCode / errorMessage / processingMode / extractedTextChars) — see
    // java.md footgun #6 (FE-BE contract drift). Future Wave 2 IMAGE-COMPRESSION
    // and Wave 2 PDF-SCAN-FALLBACK will add new processingMode values
    // (IMAGE_BLOCK_COMPRESSED / PDF_PAGE_IMAGE) and populate error_code on
    // failure paths (PDF_PARSE_FAILED, PDF_TEXT_EMPTY_NEEDS_VISION,
    // IMAGE_TOO_LARGE, etc.).

    /** Short stable code for the failure that produced this row (or null on success). */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    /** Free-text human-readable failure detail (truncated to ~1KB at write site). */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Which materialization path produced this attachment. Populated on the success
     * path: {@code IMAGE_BLOCK_INLINE} for images at upload, {@code PDF_TEXT} for PDFs
     * at upload (refined to {@code PDF_TEXT_TRUNCATED} / {@code PDF_TEXT_EMPTY} when
     * the engine first materializes the row). Empty string / null means no signal.
     */
    @Column(name = "processing_mode", length = 50)
    private String processingMode;

    /** Number of characters extracted from PDF text (informational; only PDF path uses today). */
    @Column(name = "extracted_text_chars")
    private Integer extractedTextChars;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "bound_at")
    private Instant boundAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSeqNo() { return seqNo; }
    public void setSeqNo(Long seqNo) { this.seqNo = seqNo; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getProcessingMode() { return processingMode; }
    public void setProcessingMode(String processingMode) { this.processingMode = processingMode; }
    public Integer getExtractedTextChars() { return extractedTextChars; }
    public void setExtractedTextChars(Integer extractedTextChars) { this.extractedTextChars = extractedTextChars; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
}
