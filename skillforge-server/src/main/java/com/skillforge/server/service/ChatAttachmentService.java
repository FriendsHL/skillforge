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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatAttachmentService implements MessageMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 25L * 1024 * 1024;
    private static final int MAX_PDF_TEXT_CHARS = 20_000;
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
                out.add(ContentBlock.text(pdfTextBlock(attachment)));
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
        if (text == null || text.isBlank()) {
            return "[PDF attachment: " + attachment.getFilename() + "]";
        }
        if (text.length() > MAX_PDF_TEXT_CHARS) {
            text = text.substring(0, MAX_PDF_TEXT_CHARS);
        }
        return "[PDF attachment: " + attachment.getFilename() + "]\n" + text;
    }
}
