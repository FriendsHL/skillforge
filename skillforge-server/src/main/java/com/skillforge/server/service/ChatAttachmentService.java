package com.skillforge.server.service;

import com.skillforge.core.engine.MessageMaterializer;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ChatAttachmentService implements MessageMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 25L * 1024 * 1024;
    private static final int MAX_PDF_TEXT_CHARS = 20_000;
    /**
     * V73 — MULTIMODAL-OBSERVABILITY-COLUMNS. Cap stored {@code error_message}
     * so a misbehaving stack trace can't blow up the row. UI displays a "see
     * server log" hint when truncation occurs.
     */
    private static final int MAX_ERROR_MESSAGE_CHARS = 1024;

    // ─── processing_mode values (V73, MULTIMODAL-OBSERVABILITY-COLUMNS) ───
    // String constants (NOT an enum). DB stores VARCHAR(50); wire payload is JSON
    // strings; Wave 2 IMAGE-COMPRESSION + PDF-SCAN-FALLBACK will add more values
    // (e.g. IMAGE_BLOCK_COMPRESSED / PDF_PAGE_IMAGE). Keeping these as String
    // constants avoids an enum contract that future waves would have to extend.
    public static final String MODE_IMAGE_BLOCK_INLINE = "IMAGE_BLOCK_INLINE";
    public static final String MODE_PDF_TEXT = "PDF_TEXT";
    public static final String MODE_PDF_TEXT_TRUNCATED = "PDF_TEXT_TRUNCATED";
    public static final String MODE_PDF_TEXT_EMPTY = "PDF_TEXT_EMPTY";
    /**
     * MULTIMODAL-MVP r2 W5 / tech-design §"安全与限制": cap pages extracted from a
     * PDF. Prevents a 500-page PDF from spending memory + CPU on full extraction
     * just to truncate the result.
     */
    private static final int MAX_PDF_PAGES = 20;

    /**
     * MULTIMODAL-MVP r2 B1: magic-byte signatures for image / PDF detection. We
     * never trust {@code MultipartFile.getContentType()} alone (attacker-controlled
     * HTTP header). The server-detected kind is what lands in the DB row.
     *
     * <p>Detection is intentionally simple — magic bytes were chosen for formats we
     * accept (no polyglot detection). Apache Tika would be more thorough but it
     * pulls in 20+ MB of transitive deps, not justified for 4 file types.</p>
     */
    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] WEBP_RIFF = new byte[]{'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_WEBP = new byte[]{'W', 'E', 'B', 'P'};
    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};

    private final ChatAttachmentRepository attachmentRepository;
    private final Path storageRoot;

    public ChatAttachmentService(ChatAttachmentRepository attachmentRepository,
                                 @Value("${skillforge.chat.attachments.root:./data/chat-attachments}") String storageRoot) {
        this.attachmentRepository = attachmentRepository;
        this.storageRoot = Path.of(storageRoot);
    }

    @Transactional
    public ChatAttachmentEntity upload(String sessionId, Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
        // MULTIMODAL-MVP r2 B1 / PRD §"图片处理":
        // Never trust file.getContentType() alone — Content-Type header is attacker-
        // controlled. Read the first 12 bytes and detect by magic bytes. The detected
        // MIME is what we persist to DB (header MIME is discarded). When header and
        // detected MIME conflict (e.g. ZIP renamed .png with Content-Type=image/png),
        // refuse — this is almost always malicious or misconfigured.
        byte[] head = readHeader(file);
        DetectedMime detected = detectMagic(head);
        String headerMime = file.getContentType() != null ? file.getContentType() : "";
        // V73 / OBS-COLUMNS: every rejection path below throws BEFORE we ever
        // call repository.save(...). The Iron Law: a rejected upload writes NO
        // DB row (we intentionally do NOT persist a "FAILED" placeholder for
        // unsupported / mime-mismatched / oversized uploads). The
        // ChatAttachmentServiceMagicBytesTest cases verify-no-save covers this.
        // Future enhancement candidate: persist FAILED rows so admins can see
        // attempted-but-rejected uploads in the same query view. Out of scope
        // for Wave 1-A; tracked in the MULTIMODAL-OBSERVABILITY-COLUMNS spec.
        if (detected == null) {
            throw new IllegalArgumentException("Unsupported or unrecognized file content "
                    + "(only PNG / JPEG / WebP / PDF accepted)");
        }
        if (!headerMime.isBlank()
                && !"application/octet-stream".equals(headerMime)
                && !mimeAgreesWithDetection(headerMime, detected)) {
            // Spec §"安全与限制": MIME + magic bytes 双校验. Don't accept the upload when
            // the two disagree — usually a polyglot / spoofed-extension attempt.
            throw new IllegalArgumentException("Declared content-type `" + headerMime
                    + "` does not match detected file type `" + detected.mime + "`");
        }
        String mimeType = detected.mime;
        String kind = detected.kind;
        long size = file.getSize();
        if ("image".equals(kind) && size > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image attachment exceeds 10MB");
        }
        if ("pdf".equals(kind) && size > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("PDF attachment exceeds 25MB");
        }

        String id = UUID.randomUUID().toString();
        String filename = sanitizeFilename(file.getOriginalFilename());
        String ext = extensionFor(kind, mimeType, filename);
        Path normalizedRoot = storageRoot.toAbsolutePath().normalize();
        Path target = storageRoot.resolve(sessionId).resolve(id + ext).toAbsolutePath().normalize();
        // MULTIMODAL-MVP r2 W3 / tech-design §"安全": path-traversal guard.
        // `.normalize()` resolves `..` syntactically but does NOT enforce containment.
        // sessionId is server-controlled (UUID) so practical risk is low, but defense
        // in depth is cheap and protects against future regressions if sessionId ever
        // accepts user-supplied chars.
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalStateException("Resolved attachment path escapes storage root: " + target);
        }
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store chat attachment", e);
        }

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setKind(kind);
        // Persist the SERVER-DETECTED MIME (not the request header). Downstream
        // materializer / FE both read this field — a single source of truth.
        entity.setMimeType(mimeType);
        entity.setFilename(filename);
        entity.setSizeBytes(size);
        entity.setStoragePath(target.toString());
        entity.setStatus("uploaded");
        if ("pdf".equals(kind)) {
            entity.setPageCount(readPdfPageCountQuietly(target));
            // V73 / OBS-COLUMNS: default the processing_mode at upload time so an
            // attachment that is never materialized still carries a "intended" mode
            // signal. Refined to PDF_TEXT_TRUNCATED / PDF_TEXT_EMPTY at first
            // materialize. Wave 2 PDF-SCAN-FALLBACK may overwrite with PDF_PAGE_IMAGE.
            entity.setProcessingMode(MODE_PDF_TEXT);
        } else if ("image".equals(kind)) {
            // Wave 2 IMAGE-COMPRESSION may overwrite with IMAGE_BLOCK_COMPRESSED.
            entity.setProcessingMode(MODE_IMAGE_BLOCK_INLINE);
        }
        return attachmentRepository.save(entity);
    }

    /**
     * MULTIMODAL-MVP r2 B1: read up to the first 12 bytes from a {@link MultipartFile}.
     * Used only for magic-byte detection — we don't keep the bytes around (the file
     * is re-streamed to disk via {@code file.transferTo}).
     */
    private static byte[] readHeader(MultipartFile file) {
        try (var stream = file.getInputStream()) {
            byte[] head = new byte[12];
            int read = 0;
            int n;
            while (read < head.length && (n = stream.read(head, read, head.length - read)) != -1) {
                read += n;
            }
            if (read < head.length) {
                byte[] truncated = new byte[read];
                System.arraycopy(head, 0, truncated, 0, read);
                return truncated;
            }
            return head;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read upload header for magic-byte validation", e);
        }
    }

    /** Server-detected file type. */
    private static final class DetectedMime {
        final String mime;
        final String kind;
        DetectedMime(String mime, String kind) {
            this.mime = mime;
            this.kind = kind;
        }
    }

    /**
     * MULTIMODAL-MVP r2 B1: detect MIME by leading bytes. Returns {@code null} when
     * the content does not match any allowed format. WebP requires both the RIFF
     * leader AND the WEBP tag at offset 8 (RIFF alone could be WAV / AVI).
     */
    private static DetectedMime detectMagic(byte[] head) {
        if (startsWith(head, PNG_MAGIC)) return new DetectedMime("image/png", "image");
        if (startsWith(head, JPEG_MAGIC)) return new DetectedMime("image/jpeg", "image");
        if (startsWith(head, WEBP_RIFF) && head.length >= 12 && matchesAt(head, 8, WEBP_WEBP)) {
            return new DetectedMime("image/webp", "image");
        }
        if (startsWith(head, PDF_MAGIC)) return new DetectedMime("application/pdf", "pdf");
        return null;
    }

    private static boolean startsWith(byte[] src, byte[] prefix) {
        return matchesAt(src, 0, prefix);
    }

    private static boolean matchesAt(byte[] src, int offset, byte[] expected) {
        if (src == null || src.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (src[offset + i] != expected[i]) return false;
        }
        return true;
    }

    /**
     * Returns true when the request's declared Content-Type is consistent with the
     * server-detected type. Treats {@code image/jpg} as equivalent to
     * {@code image/jpeg} (common browser misnomer).
     */
    private static boolean mimeAgreesWithDetection(String headerMime, DetectedMime detected) {
        String h = headerMime.toLowerCase();
        if (h.equals(detected.mime)) return true;
        // Browsers sometimes send image/jpg for jpegs; accept it.
        if ("image/jpg".equals(h) && "image/jpeg".equals(detected.mime)) return true;
        return false;
    }

    @Transactional
    public void bindToMessage(String sessionId, Long userId, List<String> attachmentIds, long seqNo) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        List<ChatAttachmentEntity> attachments = loadOwned(sessionId, userId, attachmentIds);
        if (attachments.size() != attachmentIds.size()) {
            throw new IllegalArgumentException("One or more attachments are missing or not owned by this session");
        }
        Instant now = Instant.now();
        for (ChatAttachmentEntity attachment : attachments) {
            if (attachment.getSeqNo() != null && attachment.getSeqNo() != seqNo) {
                throw new IllegalStateException("Attachment is already bound to a message");
            }
            attachment.setSeqNo(seqNo);
            attachment.setStatus("bound");
            attachment.setBoundAt(now);
        }
        attachmentRepository.saveAll(attachments);
    }

    public List<ContentBlock> referenceBlocks(String sessionId, Long userId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentEntity> attachments = loadOwned(sessionId, userId, attachmentIds);
        if (attachments.size() != attachmentIds.size()) {
            throw new IllegalArgumentException("One or more attachments are missing or not owned by this session");
        }
        Map<String, ChatAttachmentEntity> byId = new LinkedHashMap<>();
        for (ChatAttachmentEntity attachment : attachments) {
            byId.put(attachment.getId(), attachment);
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (String id : attachmentIds) {
            ChatAttachmentEntity attachment = byId.get(id);
            if ("image".equals(attachment.getKind())) {
                blocks.add(ContentBlock.imageRef(attachment.getId(), attachment.getMimeType(), attachment.getFilename()));
            } else if ("pdf".equals(attachment.getKind())) {
                blocks.add(ContentBlock.pdfRef(attachment.getId(), attachment.getFilename(), attachment.getPageCount()));
            }
        }
        return blocks;
    }

    /**
     * MULTIMODAL-MVP r2 (B2 fix): per-message materialization. The engine's
     * {@link com.skillforge.core.engine.MessageMaterializer} contract calls this
     * via {@link #expandForProvider(String, List)} just before each LLM call.
     *
     * <p>Returns the input {@link Message} reference unchanged when no expansion
     * happened — callers rely on that identity to detect "no work to do".</p>
     */
    public Message materializeForProvider(String sessionId, Message message) {
        if (message == null || !(message.getContent() instanceof List<?> blocks)) {
            return message;
        }
        boolean changed = false;
        List<Object> out = new ArrayList<>(blocks.size());
        for (Object block : blocks) {
            String type = blockType(block);
            String attachmentId = blockString(block, "attachment_id", "attachmentId");
            if ("image_ref".equals(type)) {
                ChatAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                        .filter(a -> sessionId.equals(a.getSessionId()))
                        .orElseThrow(() -> new IllegalArgumentException("Image attachment not found: " + attachmentId));
                out.add(ContentBlock.image(attachment.getMimeType(), readBase64(attachment)));
                changed = true;
            } else if ("pdf_ref".equals(type)) {
                ChatAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                        .filter(a -> sessionId.equals(a.getSessionId()))
                        .orElseThrow(() -> new IllegalArgumentException("PDF attachment not found: " + attachmentId));
                // V73 / OBS-COLUMNS: pdfTextBlock additionally mutates the entity
                // (extracted_text_chars + refined processing_mode based on outcome).
                // We persist the refined entity here because pdfTextBlock has the
                // smallest diff path — it already has the entity in scope. The
                // alternative (compute textLen here, refine mode here, leave
                // pdfTextBlock pure) would duplicate the text-empty / truncated
                // detection logic. Saving once per pdf_ref expansion: typical
                // message has 0–2 PDF refs so DB write cost is acceptable.
                String previousMode = attachment.getProcessingMode();
                Integer previousChars = attachment.getExtractedTextChars();
                out.add(ContentBlock.text(pdfTextBlock(attachment)));
                boolean refined = !java.util.Objects.equals(previousMode, attachment.getProcessingMode())
                        || !java.util.Objects.equals(previousChars, attachment.getExtractedTextChars());
                if (refined) {
                    attachmentRepository.save(attachment);
                }
                changed = true;
            } else {
                out.add(block);
            }
        }
        if (!changed) {
            return message;
        }
        Message copy = new Message();
        copy.setRole(message.getRole());
        copy.setContent(out);
        copy.setReasoningContent(message.getReasoningContent());
        return copy;
    }

    /**
     * MULTIMODAL-MVP r2 (B2 fix): {@link MessageMaterializer} implementation —
     * walks each message in the engine's request copy and applies
     * {@link #materializeForProvider(String, Message)} per message.
     *
     * <p>Returns the input list reference unchanged when no message contains
     * a reference block (common text-only path, no allocation).</p>
     *
     * <p>This is called engine-side IMMEDIATELY before {@code LlmProvider.chatStream}.
     * The engine's own {@code messages} list is not touched; only the request copy
     * sees the expanded form. That separation is the entire point of the B2 fix —
     * the persisted DB row matches the engine's in-memory list (both
     * {@code image_ref}), so {@code SessionService.updateSessionMessages} never
     * triggers the mid-prefix divergence guard for multimodal turns.</p>
     */
    @Override
    public List<Message> expandForProvider(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Message> out = null;
        for (int i = 0; i < messages.size(); i++) {
            Message original = messages.get(i);
            Message materialized = materializeForProvider(sessionId, original);
            if (materialized != original) {
                if (out == null) {
                    out = new ArrayList<>(messages);
                }
                out.set(i, materialized);
            }
        }
        return out != null ? out : messages;
    }

    private List<ChatAttachmentEntity> loadOwned(String sessionId, Long userId, List<String> attachmentIds) {
        List<ChatAttachmentEntity> attachments = attachmentRepository.findBySessionIdAndIdIn(sessionId, attachmentIds);
        return attachments.stream()
                .filter(a -> userId != null && userId.equals(a.getUserId()))
                .toList();
    }

    /**
     * Phase 2 read endpoint — fetch the entity for inline display in the chat
     * UI ({@code GET /api/chat/attachments/{id}/data}). Ownership check runs
     * against both session id (caller already proved session ownership in the
     * controller) and user id (defense-in-depth). Returns null when the
     * attachment is missing or fails any ownership check — the controller maps
     * that to {@code 404}.
     */
    public ChatAttachmentEntity findReadable(String attachmentId, String sessionId, Long userId) {
        if (attachmentId == null || attachmentId.isBlank()) return null;
        if (userId == null) return null;
        ChatAttachmentEntity attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null) return null;
        if (sessionId != null && !sessionId.equals(attachment.getSessionId())) return null;
        if (!userId.equals(attachment.getUserId())) return null;
        return attachment;
    }

    /**
     * Phase 2 read endpoint — read the bytes for an attachment that has
     * already passed {@link #findReadable}. Wraps {@link Files#readAllBytes}
     * with the same {@link IllegalStateException} surface the upload path
     * uses for I/O failures so the controller catch block is symmetric.
     */
    public byte[] readBytes(ChatAttachmentEntity attachment) {
        try {
            return Files.readAllBytes(Path.of(attachment.getStoragePath()));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read attachment bytes: " + attachment.getId(), e);
        }
    }

    private static String sanitizeFilename(String original) {
        String name = original == null || original.isBlank() ? "attachment" : Path.of(original).getFileName().toString();
        name = name.replaceAll("[\\r\\n\\t]", "_").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isBlank()) {
            name = "attachment";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String extensionFor(String kind, String mimeType, String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            return filename.substring(dot);
        }
        if ("pdf".equals(kind)) return ".pdf";
        if ("image/jpeg".equals(mimeType)) return ".jpg";
        if ("image/webp".equals(mimeType)) return ".webp";
        return ".png";
    }

    private static Integer readPdfPageCountQuietly(Path path) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            return null;
        }
    }

    private static String blockType(Object block) {
        if (block instanceof ContentBlock cb) return cb.getType();
        if (block instanceof Map<?, ?> map && map.get("type") != null) return map.get("type").toString();
        return null;
    }

    private static String blockString(Object block, String snakeKey, String camelKey) {
        Object value = null;
        if (block instanceof ContentBlock cb) {
            value = cb.getAttachmentId();
        } else if (block instanceof Map<?, ?> map) {
            value = map.get(snakeKey);
            if (value == null) value = map.get(camelKey);
        }
        return value != null ? value.toString() : null;
    }

    private String readBase64(ChatAttachmentEntity attachment) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(attachment.getStoragePath())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read image attachment", e);
        }
    }

    // ------------------------------------------------------------------
    // ATTACHMENT-CLEANUP (Wave1-B): nightly cron + admin manual trigger.
    // ------------------------------------------------------------------

    /**
     * ATTACHMENT-CLEANUP result envelope returned by {@link #cleanupOrphans}.
     *
     * @param orphanRowsDeleted DB rows removed (status=uploaded, never bound, older than threshold)
     * @param filesDeleted      physical files removed (orphan-row files + DB-less files on disk)
     * @param errors            per-step error messages (best-effort — cleanup never throws)
     */
    public record CleanupResult(int orphanRowsDeleted, int filesDeleted, List<String> errors) {}

    /** Expose the configured storage root so the scheduler / admin endpoint can log it. */
    public Path getStorageRoot() {
        return storageRoot;
    }

    /**
     * Daily / admin-triggered orphan cleanup. Two phases:
     *
     * <ol>
     *   <li><b>Step A — orphan DB rows:</b> {@code status='uploaded' AND seq_no IS NULL AND
     *   created_at &lt; now - thresholdHours}. For each row, delete the on-disk file (best
     *   effort, logged on failure, continue) then delete the DB row.</li>
     *   <li><b>Step B — DB-less disk files:</b> walk {@code skillforge.chat.attachments.root}
     *   and compute the set difference vs {@link ChatAttachmentRepository#findAllStoragePaths()}.
     *   Files on disk with no corresponding DB row are removed. This covers files orphaned
     *   by {@code ON DELETE CASCADE} when a session row is deleted (the DB CASCADE drops
     *   the {@code t_chat_attachment} row but the physical file stays).</li>
     * </ol>
     *
     * <p>{@code dryRun=true} runs the same scans but skips every delete; the returned counts
     * reflect what <i>would</i> be deleted. INFO-level log line summarizes regardless.</p>
     *
     * <p><b>Transactional choice:</b> intentionally NOT {@code @Transactional} at method
     * level. Step A row deletes use Spring Data's per-row {@code delete()} (each runs in its
     * own short JPA transaction), so a single bad row doesn't roll back the whole sweep.
     * Step B is filesystem-only — no DB writes. This matches the brief's "row-by-row deletes"
     * option and minimises lock duration on {@code t_chat_attachment}.</p>
     *
     * <p><b>Symlink loops:</b> {@link Files#walk(Path, java.nio.file.FileVisitOption...)} with
     * no options does <i>not</i> follow symbolic links — protects against an attacker (or
     * careless ops) wiring {@code storageRoot/sess/x} to {@code /etc} and the cron happily
     * deleting "unreferenced" /etc/* files.</p>
     *
     * <p><b>Failure isolation:</b> every per-row / per-file action is wrapped in try/catch
     * and contributes a string to {@code errors}. This method never throws — the scheduler
     * relies on that contract.</p>
     *
     * <p><b>Multi-JVM note:</b> in a 2+ replica deployment two JVMs would each fire the cron
     * at the same wall-clock time. Both racing against {@code t_chat_attachment} is benign —
     * {@code deleteIfExists} + {@code attachmentRepository.delete()} on a missing row are
     * idempotent; the loser logs nothing scary. We do not currently use a leader-election
     * lock because SkillForge runs single-instance; revisit if we cluster.</p>
     */
    public CleanupResult cleanupOrphans(int thresholdHours, boolean dryRun) {
        int hours = Math.max(thresholdHours, 1);
        Instant before = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<String> errors = new ArrayList<>();
        int orphanRowsDeleted = 0;
        int filesDeleted = 0;

        // ---------- Step A: orphan DB rows (uploaded but never bound) ----------
        List<ChatAttachmentEntity> orphanRows;
        try {
            orphanRows = attachmentRepository
                    .findByStatusAndSeqNoIsNullAndCreatedAtBefore("uploaded", before);
        } catch (RuntimeException e) {
            errors.add("findByStatusAndSeqNoIsNullAndCreatedAtBefore failed: " + e.getMessage());
            orphanRows = List.of();
        }

        Set<String> orphanRowPaths = new HashSet<>();
        for (ChatAttachmentEntity row : orphanRows) {
            String storagePath = row.getStoragePath();
            if (storagePath != null) {
                orphanRowPaths.add(normalizeForCompare(storagePath));
            }
            try {
                // File first, then row. If file delete fails, the row still gets removed so
                // we don't keep retrying the broken pair every night. The file (if any) then
                // becomes a Step B orphan on the next sweep and gets cleaned then. The
                // symmetric ordering (row first) risked the inverse: file kept while row
                // gone — which Step B can't see because we no longer know which session the
                // file belonged to.
                if (storagePath != null) {
                    try {
                        if (!dryRun) {
                            if (Files.deleteIfExists(Path.of(storagePath))) {
                                filesDeleted++;
                            }
                        } else if (Files.exists(Path.of(storagePath))) {
                            // dry-run: count the file we WOULD delete
                            filesDeleted++;
                        }
                    } catch (IOException ioe) {
                        log.warn("ATTACHMENT-CLEANUP: failed to delete orphan file path={} reason={}",
                                storagePath, ioe.getMessage());
                        errors.add("delete file " + storagePath + ": " + ioe.getMessage());
                    }
                }
                if (!dryRun) {
                    attachmentRepository.delete(row);
                }
                orphanRowsDeleted++;
            } catch (RuntimeException e) {
                log.warn("ATTACHMENT-CLEANUP: failed to delete orphan row id={} reason={}",
                        row.getId(), e.getMessage());
                errors.add("delete row " + row.getId() + ": " + e.getMessage());
            }
        }

        // ---------- Step B: physical files with no DB row ----------
        try {
            Set<String> referencedPaths = new HashSet<>();
            try {
                for (String p : attachmentRepository.findAllStoragePaths()) {
                    if (p != null) referencedPaths.add(normalizeForCompare(p));
                }
            } catch (RuntimeException e) {
                errors.add("findAllStoragePaths failed: " + e.getMessage());
            }

            if (Files.exists(storageRoot)) {
                // Files.walk default: does NOT follow symbolic links. Intentional —
                // see method-level javadoc for the symlink-loop defense rationale.
                try (Stream<Path> stream = Files.walk(storageRoot)) {
                    List<Path> diskFiles = stream
                            .filter(Files::isRegularFile)
                            .toList();
                    for (Path file : diskFiles) {
                        String norm = normalizeForCompare(file.toString());
                        if (referencedPaths.contains(norm)) continue;
                        // Files we just deleted (or would delete) in Step A are already
                        // counted; skip them in the unreferenced sweep to avoid double counting.
                        if (orphanRowPaths.contains(norm)) continue;
                        try {
                            if (!dryRun) {
                                if (Files.deleteIfExists(file)) {
                                    filesDeleted++;
                                }
                            } else {
                                filesDeleted++;
                            }
                        } catch (IOException ioe) {
                            log.warn("ATTACHMENT-CLEANUP: failed to delete unreferenced file path={} reason={}",
                                    file, ioe.getMessage());
                            errors.add("delete unreferenced " + file + ": " + ioe.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("ATTACHMENT-CLEANUP: storage walk failed at root={} reason={}",
                    storageRoot, e.getMessage());
            errors.add("walk " + storageRoot + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("ATTACHMENT-CLEANUP: unexpected error during Step B reason={}", e.getMessage());
            errors.add("step B: " + e.getMessage());
        }

        log.info("ATTACHMENT-CLEANUP: {} orphanRowsDeleted={} filesDeleted={} errors={} thresholdHours={}",
                dryRun ? "[DRY-RUN]" : "[LIVE]", orphanRowsDeleted, filesDeleted, errors.size(), hours);
        return new CleanupResult(orphanRowsDeleted, filesDeleted, errors);
    }

    /**
     * Path compare uses absolute + normalized form so a relative path stored in DB
     * (e.g. {@code ./data/chat-attachments/sess/x.png}) matches an absolute path returned
     * by {@code Files.walk(storageRoot)}.
     */
    private static String normalizeForCompare(String raw) {
        try {
            return Path.of(raw).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /**
     * Extract text from the given PDF attachment AND (V73) refine the entity's
     * {@code processing_mode} + {@code extracted_text_chars} fields based on
     * the outcome. The caller in {@link #materializeForProvider} is responsible
     * for persisting these mutations (it has the repository in scope).
     *
     * <p>Outcomes:</p>
     * <ul>
     *   <li>Text within budget → {@code PDF_TEXT} (unchanged from upload-time default).</li>
     *   <li>Text exceeded {@link #MAX_PDF_TEXT_CHARS} and was truncated → {@code PDF_TEXT_TRUNCATED}.</li>
     *   <li>No text could be extracted (scan-only PDF, parse failure, zero pages) → {@code PDF_TEXT_EMPTY}.</li>
     * </ul>
     *
     * <p>{@code extracted_text_chars} records what the LLM actually sees
     * (post-truncation), useful for the admin endpoint to flag suspicious
     * 0-char PDFs that should fall back to OCR (Wave 2 PDF-SCAN-FALLBACK).</p>
     */
    private String pdfTextBlock(ChatAttachmentEntity attachment) {
        String text = "";
        try (PDDocument doc = Loader.loadPDF(Path.of(attachment.getStoragePath()).toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // MULTIMODAL-MVP r2 W5 / tech-design §"PDF 处理": cap the page range
            // BEFORE extraction so a 500-page PDF doesn't allocate a multi-MB string
            // we'd then truncate. setStartPage=1 is also explicit (default) so a future
            // reader doesn't have to look up PDFBox defaults to be sure.
            // Defensive Math.max(1, ...) — PDFTextStripper requires endPage >= startPage;
            // a zero-page or malformed PDF would otherwise throw on getText.
            int pages = doc.getNumberOfPages();
            int endPage = Math.max(1, Math.min(pages, MAX_PDF_PAGES));
            stripper.setStartPage(1);
            stripper.setEndPage(endPage);
            if (pages > 0) {
                text = stripper.getText(doc);
            }
        } catch (IOException e) {
            text = "";
        }
        boolean empty = text == null || text.isBlank();
        boolean truncated = false;
        if (!empty && text.length() > MAX_PDF_TEXT_CHARS) {
            text = text.substring(0, MAX_PDF_TEXT_CHARS);
            truncated = true;
        }
        // V73 / OBS-COLUMNS: record post-truncation char count + refine
        // processing_mode based on outcome. Caller persists if mutated.
        int extractedChars = empty ? 0 : text.length();
        attachment.setExtractedTextChars(extractedChars);
        if (empty) {
            attachment.setProcessingMode(MODE_PDF_TEXT_EMPTY);
        } else if (truncated) {
            attachment.setProcessingMode(MODE_PDF_TEXT_TRUNCATED);
        } else {
            attachment.setProcessingMode(MODE_PDF_TEXT);
        }
        if (empty) {
            return "[PDF attachment: " + attachment.getFilename() + "]";
        }
        return "[PDF attachment: " + attachment.getFilename() + "]\n" + text;
    }
}
