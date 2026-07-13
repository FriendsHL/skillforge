package com.skillforge.server.service;

import com.skillforge.core.engine.MessageMaterializer;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.service.document.ExcelDocumentParser;
import com.skillforge.server.service.document.ImageScaler;
import com.skillforge.server.service.document.PdfPageImageRenderer;
import com.skillforge.server.service.document.WordDocumentParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Service
public class ChatAttachmentService implements MessageMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 25L * 1024 * 1024;
    private static final int MAX_PDF_TEXT_CHARS = 20_000;
    /** Wave 3 WORD-EXCEL: per-upload size caps for text-extraction file types. */
    private static final long MAX_WORD_BYTES = 20L * 1024 * 1024;
    private static final long MAX_EXCEL_BYTES = 20L * 1024 * 1024;
    private static final long MAX_CSV_BYTES = 10L * 1024 * 1024;
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
    /**
     * Wave 2-D IMAGE-COMPRESSION: image attachment was down-scaled (long edge
     * &gt; 2048px OR raw bytes &gt; 1MB) and re-encoded as JPEG by
     * {@link ImageScaler#maybeCompress} before being shipped to the LLM. The
     * original file on disk is unchanged — only the transient provider request
     * carries the compressed payload. Set in {@link #materializeForProvider}
     * on the image_ref branch when compression actually ran; left at
     * {@link #MODE_IMAGE_BLOCK_INLINE} when bypassed.
     */
    public static final String MODE_IMAGE_BLOCK_COMPRESSED = "IMAGE_BLOCK_COMPRESSED";
    /**
     * Wave 2-D IMAGE-COMPRESSION error code — image read OR encode OR scale
     * step threw inside {@link ImageScaler}; the materializer fell back to the
     * original uncompressed bytes so the LLM request still succeeds, and
     * recorded this code on {@link ChatAttachmentEntity#getErrorCode()} for
     * later admin visibility. Symptom is usually "input was not a decodable
     * image despite passing magic-byte upload validation" — corruption or a
     * format the JVM's ImageIO doesn't understand.
     */
    public static final String IMAGE_COMPRESSION_FAILED = "IMAGE_COMPRESSION_FAILED";
    public static final String MODE_PDF_TEXT = "PDF_TEXT";
    public static final String MODE_PDF_TEXT_TRUNCATED = "PDF_TEXT_TRUNCATED";
    public static final String MODE_PDF_TEXT_EMPTY = "PDF_TEXT_EMPTY";
    /**
     * Wave 2 PDF-SCAN-FALLBACK: PDF text extraction yielded &lt; {@link #PDF_SCAN_TEXT_THRESHOLD_CHARS}
     * characters; rendered the first {@link #MAX_PDF_PAGE_IMAGES} pages as PNG images for
     * vision-based analysis. Set in {@link #materializeForProvider} after the renderer
     * succeeds; left at the previous PDF_TEXT_* value if the render path fails.
     */
    public static final String MODE_PDF_PAGE_IMAGE = "PDF_PAGE_IMAGE";

    // ─── Wave 3 WORD-EXCEL processing_mode constants ───
    /** Word document (.doc / .docx) was/will be parsed to markdown text. */
    public static final String MODE_WORD_TEXT = "WORD_TEXT";
    /** Excel workbook (.xlsx / .xls) was/will be parsed to markdown tables. */
    public static final String MODE_EXCEL_TEXT = "EXCEL_TEXT";
    /** CSV file was/will be parsed to a single markdown table. */
    public static final String MODE_CSV_TEXT = "CSV_TEXT";
    /** Word parser threw at materialization — text-only placeholder shipped. */
    public static final String WORD_PARSE_FAILED = "WORD_PARSE_FAILED";
    /** Excel parser threw at materialization — text-only placeholder shipped. */
    public static final String EXCEL_PARSE_FAILED = "EXCEL_PARSE_FAILED";
    /** CSV parser threw at materialization — text-only placeholder shipped. */
    public static final String CSV_PARSE_FAILED = "CSV_PARSE_FAILED";
    /** Magic bytes didn't match the declared Office MIME (e.g. .docx not actually a ZIP). */
    public static final String WORD_MIME_MISMATCH = "WORD_MIME_MISMATCH";
    public static final String EXCEL_MIME_MISMATCH = "EXCEL_MIME_MISMATCH";
    /** CSV upload contained non-printable bytes (likely a binary file masquerading as CSV). */
    public static final String CSV_NOT_TEXT = "CSV_NOT_TEXT";

    // ─── Wave 3 WORD-EXCEL MIME allowlist (top-of-file for visibility) ───
    static final String MIME_DOC = AttachmentTypeDetector.MIME_DOC;
    static final String MIME_DOCX = AttachmentTypeDetector.MIME_DOCX;
    static final String MIME_XLS = AttachmentTypeDetector.MIME_XLS;
    static final String MIME_XLSX = AttachmentTypeDetector.MIME_XLSX;
    static final String MIME_CSV = AttachmentTypeDetector.MIME_CSV;

    /**
     * Wave 2 PDF-SCAN-FALLBACK error code — recorded on
     * {@link ChatAttachmentEntity#getErrorCode()} when text extraction yielded near-empty
     * content AND the page-image fallback also failed (corruption / encrypted / etc.).
     * Surfaces in admin observability queries so we can tell apart "text-empty + image
     * fallback worked" (mode=PDF_PAGE_IMAGE, no error_code) from "text-empty + nothing
     * worked" (mode=PDF_TEXT_EMPTY, error_code=PDF_TEXT_EMPTY_NEEDS_VISION).
     */
    public static final String PDF_TEXT_EMPTY_NEEDS_VISION = "PDF_TEXT_EMPTY_NEEDS_VISION";

    // ─── Wave 2 PDF-SCAN-FALLBACK tunables ───
    /** Below this many extracted chars we trigger the page-image fallback. */
    private static final int PDF_SCAN_TEXT_THRESHOLD_CHARS = 200;
    /** Cap pages rendered as images to bound memory / token cost. */
    private static final int MAX_PDF_PAGE_IMAGES = 5;
    /** Render DPI for the page-image fallback. Renderer applies a 1MB per-page hard cap. */
    private static final float PDF_RENDER_DPI = 150f;
    /**
     * MULTIMODAL-MVP r2 W5 / tech-design §"安全与限制": cap pages extracted from a
     * PDF. Prevents a 500-page PDF from spending memory + CPU on full extraction
     * just to truncate the result.
     */
    private static final int MAX_PDF_PAGES = 20;


    private final ChatAttachmentRepository attachmentRepository;
    private final Path storageRoot;
    private final Consumer<Path> generatedSourceOpenedHook;
    private static final Object[] GENERATED_IMPORT_LOCKS = new Object[256];

    static {
        java.util.Arrays.setAll(GENERATED_IMPORT_LOCKS, ignored -> new Object());
    }

    @Autowired
    public ChatAttachmentService(ChatAttachmentRepository attachmentRepository,
                                 @Value("${skillforge.chat.attachments.root:./data/chat-attachments}") String storageRoot) {
        this(attachmentRepository, storageRoot, ignored -> { });
    }

    ChatAttachmentService(ChatAttachmentRepository attachmentRepository, String storageRoot,
                          Consumer<Path> generatedSourceOpenedHook) {
        this.attachmentRepository = attachmentRepository;
        this.storageRoot = Path.of(storageRoot);
        this.generatedSourceOpenedHook = generatedSourceOpenedHook;
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
        AttachmentTypeDetector.DetectedType detected = AttachmentTypeDetector.detect(file);
        // V73 / OBS-COLUMNS: every rejection path below throws BEFORE we ever
        // call repository.save(...). The Iron Law: a rejected upload writes NO
        // DB row (we intentionally do NOT persist a "FAILED" placeholder for
        // unsupported / mime-mismatched / oversized uploads). The
        // ChatAttachmentServiceMagicBytesTest cases verify-no-save covers this.
        // Future enhancement candidate: persist FAILED rows so admins can see
        // attempted-but-rejected uploads in the same query view. Out of scope
        // for Wave 1-A; tracked in the MULTIMODAL-OBSERVABILITY-COLUMNS spec.
        String mimeType = detected.mimeType();
        String kind = detected.kind();
        long size = file.getSize();
        if ("image".equals(kind) && size > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image attachment exceeds 10MB");
        }
        if ("pdf".equals(kind) && size > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("PDF attachment exceeds 25MB");
        }
        // Wave 3 WORD-EXCEL: size caps for text-extraction file types. These are
        // bytes-on-disk limits, NOT parse-time memory limits — POI can still OOM
        // on a 20MB .xlsx with millions of populated cells. Parse-time bounds
        // live in ExcelDocumentParser (MAX_ROWS_PER_SHEET / MAX_COLS / MAX_SHEETS)
        // which raise EXCEL_SHEET_TOO_LARGE before the row scan blows memory.
        if ("word".equals(kind) && size > MAX_WORD_BYTES) {
            throw new IllegalArgumentException("Word attachment exceeds 20MB");
        }
        if ("excel".equals(kind) && size > MAX_EXCEL_BYTES) {
            throw new IllegalArgumentException("Excel attachment exceeds 20MB");
        }
        if ("csv".equals(kind) && size > MAX_CSV_BYTES) {
            throw new IllegalArgumentException("CSV attachment exceeds 10MB");
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
        } else if ("word".equals(kind)) {
            // Wave 3 WORD-EXCEL: parser runs at first materialize. Failure path
            // sets error_code=WORD_PARSE_FAILED there.
            entity.setProcessingMode(MODE_WORD_TEXT);
        } else if ("excel".equals(kind)) {
            // sheet_count populated by the parser on first materialize.
            entity.setProcessingMode(MODE_EXCEL_TEXT);
        } else if ("csv".equals(kind)) {
            entity.setProcessingMode(MODE_CSV_TEXT);
        }
        return attachmentRepository.save(entity);
    }

    /**
     * Import an agent-produced file into managed attachment storage. The source path is
     * untrusted tool output and must resolve under this run's staging directory or the
     * current session's managed directory.
     */
    public ChatAttachmentEntity importGeneratedFile(
            String sessionId,
            Long userId,
            String toolUseId,
            Path source,
            String caption,
            Path artifactWorkspace) {
        requireGeneratedIdentity(sessionId, userId, toolUseId);
        String lockKey = sessionId + '\0' + toolUseId;
        Object lock = GENERATED_IMPORT_LOCKS[Math.floorMod(lockKey.hashCode(), GENERATED_IMPORT_LOCKS.length)];
        synchronized (lock) {
            return importGeneratedFileLocked(
                    sessionId, userId, toolUseId, source, caption, artifactWorkspace);
        }
    }

    private ChatAttachmentEntity importGeneratedFileLocked(
            String sessionId, Long userId, String toolUseId, Path source,
            String caption, Path artifactWorkspace) {
        String id = UUID.nameUUIDFromBytes((sessionId + "\n" + toolUseId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Path sessionRoot = storageRoot.toAbsolutePath().normalize().resolve(sessionId).normalize();
        requireUnderStorageRoot(sessionRoot);
        Path part = sessionRoot.resolve(id + "." + UUID.randomUUID() + ".part").normalize();
        requireUnderStorageRoot(part);
        Path installedTarget = null;

        try {
            Files.createDirectories(sessionRoot);
            requireCanonicalDirectoryUnderStorageRoot(sessionRoot);
            try (OpenedGeneratedSource opened = openAllowedGeneratedSource(
                    sessionId, source, artifactWorkspace)) {
                SeekableByteChannel input = opened.channel();
                generatedSourceOpenedHook.accept(opened.displayPath());
                AttachmentTypeDetector.DetectedType type = AttachmentTypeDetector.detect(
                        input, opened.filename());
                long sourceSize = input.size();
                enforceSize(type.kind(), sourceSize);
                String copiedHash = copyAndHash(input, part);
                opened.verify(copiedHash);

                var existing = attachmentRepository.findBySessionIdAndSourceToolUseId(sessionId, toolUseId);
                if (existing.isPresent()) return reserveMatchingReplay(existing.get(), copiedHash);

                Path target = sessionRoot.resolve(
                        id + "." + UUID.randomUUID() + type.extension()).normalize();
                requireUnderStorageRoot(target);
                installTarget(part, target);
                installedTarget = target;
                ChatAttachmentEntity entity = generatedEntity(
                        id, sessionId, userId, toolUseId, opened.filename(), caption, type,
                        sourceSize, copiedHash, target);
                try {
                    ChatAttachmentEntity saved = attachmentRepository.saveAndFlush(entity);
                    installedTarget = null;
                    return saved;
                } catch (DataIntegrityViolationException conflict) {
                    deleteGeneratedAttempt(target, sessionId);
                    installedTarget = null;
                    return attachmentRepository.findBySessionIdAndSourceToolUseId(sessionId, toolUseId)
                            .map(row -> reserveMatchingReplay(row, copiedHash))
                            .orElseThrow(() -> new IllegalStateException(
                                    "Generated artifact idempotency conflict could not be resolved", conflict));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to import generated artifact", e);
        } finally {
            if (installedTarget != null) deleteGeneratedAttempt(installedTarget, sessionId);
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                log.warn("Failed to remove generated artifact partial file for session [{}]", sessionId);
            }
        }
    }

    public static String sha256(Path path) {
        MessageDigest digest = sha256Digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash generated artifact", e);
        }
    }

    private ChatAttachmentEntity generatedEntity(
            String id, String sessionId, Long userId, String toolUseId, String filename,
            String caption, AttachmentTypeDetector.DetectedType type, long size,
            String hash, Path target) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setKind(type.kind());
        entity.setMimeType(type.mimeType());
        entity.setFilename(sanitizeFilename(filename));
        entity.setSizeBytes(size);
        entity.setStoragePath(target.toString());
        entity.setStatus("publishing");
        entity.setOrigin("agent_generated");
        entity.setSourceToolUseId(toolUseId);
        entity.setSha256(hash);
        entity.setCaption(sanitizeCaption(caption));
        entity.setBoundAt(Instant.now());
        if ("pdf".equals(type.kind())) entity.setPageCount(readPdfPageCountQuietly(target));
        setInitialProcessingMode(entity, type.kind());
        return entity;
    }

    private static void setInitialProcessingMode(ChatAttachmentEntity entity, String kind) {
        switch (kind) {
            case "image" -> entity.setProcessingMode(MODE_IMAGE_BLOCK_INLINE);
            case "pdf" -> entity.setProcessingMode(MODE_PDF_TEXT);
            case "word" -> entity.setProcessingMode(MODE_WORD_TEXT);
            case "excel" -> entity.setProcessingMode(MODE_EXCEL_TEXT);
            case "csv" -> entity.setProcessingMode(MODE_CSV_TEXT);
            default -> throw new IllegalArgumentException("Unsupported generated artifact type");
        }
    }

    private OpenedGeneratedSource openAllowedGeneratedSource(
            String sessionId, Path source, Path workspace) throws IOException {
        if (source == null || workspace == null) {
            throw new SecurityException("Generated artifact source is outside the run workspace");
        }
        Path sourcePath = source.toAbsolutePath().normalize();
        Path workspacePath = workspace.toAbsolutePath().normalize();
        Path realWorkspace = workspace.toRealPath();
        Path relative = relativeUnder(sourcePath, workspacePath, realWorkspace);
        Path secureRoot = realWorkspace;

        if (relative == null) {
            Path managedSession = storageRoot.toAbsolutePath().normalize().resolve(sessionId).normalize();
            if (Files.exists(managedSession, LinkOption.NOFOLLOW_LINKS)) {
                Path realManagedSession = managedSession.toRealPath();
                relative = relativeUnder(sourcePath, managedSession, realManagedSession);
                secureRoot = realManagedSession;
            }
        }
        if (relative == null || relative.getNameCount() == 0 || relative.isAbsolute()) {
            throw new SecurityException("Generated artifact source is outside the allowed session roots");
        }
        for (Path component : relative) {
            String name = component.toString();
            if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
                throw new SecurityException("Generated artifact source contains an unsafe component");
            }
        }
        return openRelativeNoFollow(secureRoot, relative, sourcePath);
    }

    private static Path relativeUnder(Path source, Path configuredRoot, Path realRoot) {
        if (source.startsWith(configuredRoot)) return configuredRoot.relativize(source);
        if (source.startsWith(realRoot)) return realRoot.relativize(source);
        return null;
    }

    private static OpenedGeneratedSource openRelativeNoFollow(
            Path root, Path relative, Path displayPath) throws IOException {
        BasicFileAttributes rootBefore = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (rootBefore.isSymbolicLink() || !rootBefore.isDirectory()) {
            throw new SecurityException("Generated artifact root is not a directory");
        }
        List<DirectoryStream<Path>> openedDirectories = new ArrayList<>();
        SeekableByteChannel channel = null;
        boolean returned = false;
        try {
            DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
            openedDirectories.add(rootStream);
            if (!(rootStream instanceof SecureDirectoryStream<?>)) {
                return openRelativeWithVerification(root, relative, displayPath);
            }
            SecureDirectoryStream<Path> current = requireSecureDirectory(rootStream);
            BasicFileAttributes rootAfter = Files.readAttributes(
                    root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFile(rootBefore, rootAfter)) {
                throw new SecurityException("Generated artifact root changed while opening the file");
            }

            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                Path component = relative.getName(i);
                BasicFileAttributes attrs = attributes(current, component);
                if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                    throw new SecurityException("Generated artifact path contains an unsafe directory");
                }
                DirectoryStream<Path> child = current.newDirectoryStream(
                        component, LinkOption.NOFOLLOW_LINKS);
                openedDirectories.add(child);
                current = requireSecureDirectory(child);
            }

            Path filename = relative.getName(relative.getNameCount() - 1);
            BasicFileAttributes attrs = attributes(current, filename);
            if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                throw new SecurityException("Generated artifact source is not a regular file");
            }
            channel = current.newByteChannel(
                    filename, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            OpenedGeneratedSource result = new OpenedGeneratedSource(
                    channel, displayPath, filename.toString(), ignored -> { });
            returned = true;
            return result;
        } finally {
            for (int i = openedDirectories.size() - 1; i >= 0; i--) {
                try {
                    openedDirectories.get(i).close();
                } catch (IOException ignored) {
                    // The returned file descriptor remains valid after directory handles close.
                }
            }
            if (!returned && channel != null) channel.close();
        }
    }

    private static OpenedGeneratedSource openRelativeWithVerification(
            Path root, Path relative, Path displayPath) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("Generated artifact source escapes its allowed root");
        }
        List<PathIdentity> before = capturePathIdentity(root, relative);
        SeekableByteChannel channel = Files.newByteChannel(
                target, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        return new OpenedGeneratedSource(
                channel,
                displayPath,
                relative.getName(relative.getNameCount() - 1).toString(),
                expectedHash -> verifyFallbackSource(root, relative, target, before, expectedHash));
    }

    private static void verifyFallbackSource(
            Path root, Path relative, Path target, List<PathIdentity> before,
            String expectedHash) throws IOException {
        requireSameIdentity(before, capturePathIdentity(root, relative));
        String currentHash;
        try (SeekableByteChannel verification = Files.newByteChannel(
                target, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            currentHash = hashChannel(verification);
        }
        requireSameIdentity(before, capturePathIdentity(root, relative));
        if (!expectedHash.equals(currentHash)) {
            throw new SecurityException("Generated artifact source changed while it was imported");
        }
    }

    private static List<PathIdentity> capturePathIdentity(Path root, Path relative) throws IOException {
        List<PathIdentity> identities = new ArrayList<>();
        Path current = root;
        for (int i = -1; i < relative.getNameCount(); i++) {
            if (i >= 0) current = current.resolve(relative.getName(i));
            BasicFileAttributes attrs = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean finalComponent = i == relative.getNameCount() - 1;
            if (attrs.isSymbolicLink()
                    || (finalComponent && !attrs.isRegularFile())
                    || (!finalComponent && !attrs.isDirectory())
                    || attrs.fileKey() == null) {
                throw new SecurityException("Generated artifact path cannot be verified safely");
            }
            identities.add(new PathIdentity(current, attrs.fileKey(), attrs.size(), attrs.lastModifiedTime().toMillis()));
        }
        return identities;
    }

    private static void requireSameIdentity(List<PathIdentity> expected, List<PathIdentity> actual) {
        if (!expected.equals(actual)) {
            throw new SecurityException("Generated artifact path changed while it was imported");
        }
    }

    private static String hashChannel(SeekableByteChannel source) throws IOException {
        MessageDigest digest = sha256Digest();
        source.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (source.read(buffer) >= 0) {
            if (buffer.position() > 0) {
                digest.update(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static BasicFileAttributes attributes(
            SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("Basic file attributes are unavailable");
        return view.readAttributes();
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> requireSecureDirectory(DirectoryStream<Path> stream) {
        if (!(stream instanceof SecureDirectoryStream<?> secure)) {
            throw new SecurityException("Secure directory traversal is unavailable");
        }
        return (SecureDirectoryStream<Path>) secure;
    }

    private static boolean sameFile(BasicFileAttributes left, BasicFileAttributes right) {
        return left.isDirectory() == right.isDirectory()
                && left.isSymbolicLink() == right.isSymbolicLink()
                && (left.fileKey() == null || Objects.equals(left.fileKey(), right.fileKey()));
    }

    @FunctionalInterface
    private interface SourceVerifier {
        void verify(String expectedHash) throws IOException;
    }

    private record PathIdentity(Path path, Object fileKey, long size, long modifiedMillis) { }

    private record OpenedGeneratedSource(
            SeekableByteChannel channel,
            Path displayPath,
            String filename,
            SourceVerifier verifier) implements AutoCloseable {
        void verify(String expectedHash) throws IOException {
            verifier.verify(expectedHash);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private ChatAttachmentEntity reserveMatchingReplay(ChatAttachmentEntity existing, String hash) {
        if (!hash.equals(existing.getSha256())) {
            throw new IllegalStateException("The same tool call attempted to publish different content");
        }
        if ("published".equals(existing.getStatus())) return existing;
        Instant now = Instant.now();
        if (attachmentRepository.reserveForPublishing(existing.getId(), now) == 1) {
            existing.setStatus("publishing");
            existing.setBoundAt(now);
            return existing;
        }
        ChatAttachmentEntity current = attachmentRepository.findById(existing.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Generated artifact disappeared while reserving replay"));
        if (!hash.equals(current.getSha256()) || !"published".equals(current.getStatus())) {
            throw new IllegalStateException("Generated artifact is being cleaned and cannot be replayed");
        }
        return current;
    }

    private static void requireGeneratedIdentity(String sessionId, Long userId, String toolUseId) {
        if (sessionId == null || sessionId.isBlank() || userId == null
                || toolUseId == null || toolUseId.isBlank() || toolUseId.length() > 128) {
            throw new IllegalArgumentException("Generated artifact requires session, user, and bounded tool identity");
        }
    }

    private void requireUnderStorageRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot.toAbsolutePath().normalize())) {
            throw new SecurityException("Managed attachment path escapes storage root");
        }
    }

    private void requireCanonicalDirectoryUnderStorageRoot(Path directory) throws IOException {
        Path root = storageRoot.toAbsolutePath().normalize().toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(root) || Files.isSymbolicLink(directory)) {
            throw new SecurityException("Managed attachment directory escapes storage root");
        }
    }

    private static String copyAndHash(SeekableByteChannel source, Path part) throws IOException {
        MessageDigest digest = sha256Digest();
        source.position(0);
        try (OutputStream output = Files.newOutputStream(
                part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            while ((read = source.read(byteBuffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                byteBuffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void installTarget(Path part, Path target) throws IOException {
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteGeneratedAttempt(Path target, String sessionId) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            log.warn("Failed to remove losing generated artifact file for session [{}]", sessionId);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void enforceSize(String kind, long size) {
        long max = switch (kind) {
            case "image" -> MAX_IMAGE_BYTES;
            case "pdf" -> MAX_PDF_BYTES;
            case "word" -> MAX_WORD_BYTES;
            case "excel" -> MAX_EXCEL_BYTES;
            case "csv" -> MAX_CSV_BYTES;
            default -> 0;
        };
        if (size > max) throw new IllegalArgumentException("Generated artifact exceeds the " + kind + " size limit");
    }

    private static String sanitizeCaption(String caption) {
        if (caption == null || caption.isBlank()) return null;
        String clean = caption.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

    /**
     * Wave 3 WORD-EXCEL: peek at the leading bytes of a multipart upload to
     * classify the kind <em>without</em> persisting anything (no DB row, no
     * file write, no disk I/O beyond reading the in-memory upload). Used by
     * {@code ChatController.uploadAttachment} to decide whether the
     * vision-capability gate applies (image/pdf require it; word/excel/csv
     * are text-extraction paths and don't).
     *
     * <p>Returns one of {@code image} / {@code pdf} / {@code word} /
     * {@code excel} / {@code csv}, or {@code null} when the file can't be
     * classified (the upload itself will reject with the same {@code null}
     * branch). Re-reading the head from the same {@link MultipartFile} is
     * safe — Spring's MultipartFile implementations buffer to memory / temp
     * file and {@code getInputStream()} returns a fresh stream each call.</p>
     */
    public String previewKind(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return AttachmentTypeDetector.detect(file).kind();
        } catch (RuntimeException e) {
            return null;
        }
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
            } else if ("word".equals(attachment.getKind())) {
                // Wave 3 WORD-EXCEL: no structural metadata at this point — the
                // parser runs in materializeForProvider.
                blocks.add(ContentBlock.wordRef(attachment.getId(), attachment.getFilename()));
            } else if ("excel".equals(attachment.getKind())) {
                // sheet_count starts null at upload; refined by the materializer
                // on first parse and persisted back via attachment.pageCount
                // (dual semantics: pages for PDF, sheets for Excel). Subsequent
                // materializations of the same row carry the cached count.
                blocks.add(ContentBlock.excelRef(attachment.getId(), attachment.getFilename(),
                        attachment.getPageCount()));
            } else if ("csv".equals(attachment.getKind())) {
                blocks.add(ContentBlock.csvRef(attachment.getId(), attachment.getFilename()));
            }
        }
        return blocks;
    }

    @Transactional
    public void markPublishedFromMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            if (message == null || !(message.getContent() instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                String type = blockType(block);
                if (isAttachmentRef(type)) {
                    String id = blockString(block, "attachment_id", "attachmentId");
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }
        }
        for (String id : ids) {
            attachmentRepository.markGeneratedPublished(id);
        }
    }

    private static boolean isAttachmentRef(String type) {
        return "image_ref".equals(type) || "pdf_ref".equals(type) || "word_ref".equals(type)
                || "excel_ref".equals(type) || "csv_ref".equals(type);
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
                // Wave 2-D IMAGE-COMPRESSION: scale-down + JPEG re-encode for
                // oversized images (long edge > 2048px OR raw bytes > 1MB) via
                // ImageScaler.maybeCompress. The original file on disk is NEVER
                // modified — only the transient ContentBlock.image we add to the
                // outgoing provider request carries the compressed payload. The
                // persisted message in the engine's messages list stays in
                // image_ref form (persistence-shape invariant unchanged: see
                // .claude/rules/persistence-shape-invariant.md). Entity-side
                // mutations are limited to V73 observability columns
                // (processingMode / errorCode / errorMessage), persisted with the
                // same Objects.equals guard pattern used for pdf_ref below.
                byte[] originalBytes = readBytes(attachment);
                byte[] providerBytes = originalBytes;
                String providerMime = attachment.getMimeType();
                boolean entityChanged = false;
                try {
                    ImageScaler.CompressedImage compressed =
                            ImageScaler.maybeCompress(originalBytes, attachment.getMimeType());
                    if (compressed != null) {
                        providerBytes = compressed.bytes();
                        providerMime = compressed.mimeType();
                        if (!MODE_IMAGE_BLOCK_COMPRESSED.equals(attachment.getProcessingMode())) {
                            attachment.setProcessingMode(MODE_IMAGE_BLOCK_COMPRESSED);
                            entityChanged = true;
                        }
                    }
                } catch (IOException e) {
                    // Graceful fallback: don't fail the LLM call — log + persist
                    // an error code so admin observability can spot recurring
                    // failures, then ship the original (uncompressed) bytes.
                    log.warn("Image compression failed for attachment {} (mime={}); falling back to original bytes: {}",
                            attachment.getId(), attachment.getMimeType(), e.toString());
                    if (!IMAGE_COMPRESSION_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(IMAGE_COMPRESSION_FAILED);
                        attachment.setErrorMessage(truncateErrorMessage(e.toString()));
                        entityChanged = true;
                    }
                    // providerBytes / providerMime stay as originals.
                }
                if (entityChanged) {
                    attachmentRepository.save(attachment);
                }
                out.add(ContentBlock.image(providerMime,
                        Base64.getEncoder().encodeToString(providerBytes)));
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
                String pdfText = pdfTextBlock(attachment);
                // Wave 2 PDF-SCAN-FALLBACK: when text extraction yields near-empty
                // content (< PDF_SCAN_TEXT_THRESHOLD_CHARS), render the leading
                // pages as PNG images and let the provider's vision pipeline read
                // them. Vision-capability gating happens UPSTREAM in
                // ChatService.runLoop's `messageHasMultimodalBlocks` check:
                // pdf_ref already marks the message multimodal, so a vision-
                // incapable agent is rejected with MultimodalNoVisionException
                // before we ever reach this code. The added `image` blocks here
                // are emitted to the request copy only (materializer contract) —
                // the engine's in-memory message list keeps the pdf_ref shape, so
                // persistence-shape invariant (java.md footgun #4) is preserved.
                Integer chars = attachment.getExtractedTextChars();
                boolean fallbackEngaged = false;
                if (chars != null && chars < PDF_SCAN_TEXT_THRESHOLD_CHARS) {
                    try {
                        List<byte[]> pageImages = PdfPageImageRenderer.renderFirstPages(
                                Path.of(attachment.getStoragePath()), MAX_PDF_PAGE_IMAGES, PDF_RENDER_DPI);
                        if (!pageImages.isEmpty()) {
                            // Clarifying text block first (keeps the [PDF attachment: name]
                            // prefix the LLM relies on for context), then rendered pages.
                            out.add(ContentBlock.text(pdfText + "\n\n[Rendered " + pageImages.size()
                                    + " page(s) as images for vision-based analysis]"));
                            for (byte[] png : pageImages) {
                                out.add(ContentBlock.image("image/png",
                                        Base64.getEncoder().encodeToString(png)));
                            }
                            attachment.setProcessingMode(MODE_PDF_PAGE_IMAGE);
                            // Clear any prior fallback error code — this render succeeded.
                            if (PDF_TEXT_EMPTY_NEEDS_VISION.equals(attachment.getErrorCode())) {
                                attachment.setErrorCode(null);
                                attachment.setErrorMessage(null);
                            }
                            fallbackEngaged = true;
                        }
                    } catch (IOException e) {
                        // Page-image render failed too — leave the text-only path
                        // intact and record the failure on the entity for the admin
                        // observability endpoint. We do NOT rethrow: the LLM should
                        // still get the [PDF attachment: name] placeholder rather
                        // than a hard 500 on the chat turn.
                        log.warn("PDF page-image fallback failed for attachment={} ({} chars text): {}",
                                attachment.getId(), chars, e.toString());
                        attachment.setErrorCode(PDF_TEXT_EMPTY_NEEDS_VISION);
                        attachment.setErrorMessage(truncateErrorMessage(e.toString()));
                    }
                }
                if (!fallbackEngaged) {
                    // Normal text-only path: PDF had enough text OR fallback failed.
                    out.add(ContentBlock.text(pdfText));
                }
                boolean refined = !java.util.Objects.equals(previousMode, attachment.getProcessingMode())
                        || !java.util.Objects.equals(previousChars, attachment.getExtractedTextChars())
                        || fallbackEngaged
                        || PDF_TEXT_EMPTY_NEEDS_VISION.equals(attachment.getErrorCode());
                if (refined) {
                    attachmentRepository.save(attachment);
                }
                changed = true;
            } else if ("word_ref".equals(type)) {
                // Wave 3 WORD-EXCEL: text extraction at materialization. Mirror
                // pdf_ref's failure semantics — never fail the LLM call; emit a
                // placeholder text block and persist error_code for admin
                // observability. Persistence-shape invariant unchanged: the
                // engine's in-memory message list keeps the word_ref shape, only
                // the transient request copy carries the expanded text.
                ChatAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                        .filter(a -> sessionId.equals(a.getSessionId()))
                        .orElseThrow(() -> new IllegalArgumentException("Word attachment not found: " + attachmentId));
                boolean wordEntityChanged = false;
                try {
                    String markdown = WordDocumentParser.parseToMarkdown(Path.of(attachment.getStoragePath()));
                    out.add(ContentBlock.text("[Word Document: " + attachment.getFilename() + "]\n\n" + markdown));
                    // Success → clear any prior parse-failure error_code (re-uploaded
                    // file might fix a previously corrupted blob).
                    if (WORD_PARSE_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(null);
                        attachment.setErrorMessage(null);
                        wordEntityChanged = true;
                    }
                } catch (Exception e) {
                    log.warn("Word parsing failed for attachment {} (filename={}): {}",
                            attachment.getId(), attachment.getFilename(), e.toString());
                    if (!WORD_PARSE_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(WORD_PARSE_FAILED);
                        attachment.setErrorMessage(truncateErrorMessage(e.toString()));
                        wordEntityChanged = true;
                    }
                    out.add(ContentBlock.text("[Word Document: " + attachment.getFilename()
                            + "]\n\n(failed to parse: see error log)"));
                }
                if (wordEntityChanged) {
                    attachmentRepository.save(attachment);
                }
                changed = true;
            } else if ("excel_ref".equals(type)) {
                ChatAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                        .filter(a -> sessionId.equals(a.getSessionId()))
                        .orElseThrow(() -> new IllegalArgumentException("Excel attachment not found: " + attachmentId));
                boolean excelEntityChanged = false;
                try {
                    ExcelDocumentParser.ExcelParseResult result =
                            ExcelDocumentParser.parseToMarkdownWithMetadata(Path.of(attachment.getStoragePath()));
                    int sheetCount = result.sheetCount();
                    String header = "[Excel Spreadsheet: " + attachment.getFilename()
                            + " (" + sheetCount + " sheet" + (sheetCount == 1 ? "" : "s") + ")]";
                    out.add(ContentBlock.text(header + "\n\n" + result.markdown()));
                    // Persist sheet_count onto the entity's pageCount column —
                    // dual semantics (pages for PDF, sheets for Excel). Subsequent
                    // materializations + referenceBlocks see the cached value
                    // and FE chip can show "(N sheets)" without re-parsing.
                    if (attachment.getPageCount() == null || attachment.getPageCount() != sheetCount) {
                        attachment.setPageCount(sheetCount);
                        excelEntityChanged = true;
                    }
                    if (EXCEL_PARSE_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(null);
                        attachment.setErrorMessage(null);
                        excelEntityChanged = true;
                    }
                } catch (Exception e) {
                    log.warn("Excel parsing failed for attachment {} (filename={}): {}",
                            attachment.getId(), attachment.getFilename(), e.toString());
                    if (!EXCEL_PARSE_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(EXCEL_PARSE_FAILED);
                        attachment.setErrorMessage(truncateErrorMessage(e.toString()));
                        excelEntityChanged = true;
                    }
                    out.add(ContentBlock.text("[Excel Spreadsheet: " + attachment.getFilename()
                            + "]\n\n(failed to parse: see error log)"));
                }
                if (excelEntityChanged) {
                    attachmentRepository.save(attachment);
                }
                changed = true;
            } else if ("csv_ref".equals(type)) {
                ChatAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                        .filter(a -> sessionId.equals(a.getSessionId()))
                        .orElseThrow(() -> new IllegalArgumentException("CSV attachment not found: " + attachmentId));
                boolean csvEntityChanged = false;
                try {
                    // ExcelDocumentParser handles CSV via the .csv branch; we
                    // intentionally reuse it (single parser surface for
                    // tabular data) rather than spawning a separate CsvParser.
                    String markdown = ExcelDocumentParser.parseToMarkdown(Path.of(attachment.getStoragePath()));
                    out.add(ContentBlock.text("[CSV File: " + attachment.getFilename() + "]\n\n" + markdown));
                    if (CSV_PARSE_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(null);
                        attachment.setErrorMessage(null);
                        csvEntityChanged = true;
                    }
                } catch (Exception e) {
                    log.warn("CSV parsing failed for attachment {} (filename={}): {}",
                            attachment.getId(), attachment.getFilename(), e.toString());
                    if (!CSV_PARSE_FAILED.equals(attachment.getErrorCode())) {
                        attachment.setErrorCode(CSV_PARSE_FAILED);
                        attachment.setErrorMessage(truncateErrorMessage(e.toString()));
                        csvEntityChanged = true;
                    }
                    out.add(ContentBlock.text("[CSV File: " + attachment.getFilename()
                            + "]\n\n(failed to parse: see error log)"));
                }
                if (csvEntityChanged) {
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

    /** Hard cap on the admin attachment listing — the JPQL query is bounded by this. */
    public static final int ADMIN_ATTACHMENTS_MAX_LIMIT = 500;

    /**
     * Admin listing — fetch attachments matching the optional error-code /
     * processing-mode / session-id filters, newest first. Blank filter strings are
     * normalized to {@code null} so "filter not provided" and "filter is blank" are
     * treated the same by the {@code IS NULL OR equals} JPQL. The requested limit is
     * clamped to {@link #ADMIN_ATTACHMENTS_MAX_LIMIT}; callers validate {@code limit > 0}
     * before calling.
     */
    public List<ChatAttachmentEntity> listAttachmentsByFilters(String errorCode, String processingMode,
                                                               String sessionId, int limit) {
        int effectiveLimit = Math.min(limit, ADMIN_ATTACHMENTS_MAX_LIMIT);
        String ec = (errorCode != null && !errorCode.isBlank()) ? errorCode : null;
        String pm = (processingMode != null && !processingMode.isBlank()) ? processingMode : null;
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : null;
        return attachmentRepository.findByFilters(ec, pm, sid,
                org.springframework.data.domain.PageRequest.of(0, effectiveLimit));
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

    /**
     * V73 / OBS-COLUMNS — clamp the {@code error_message} we write to the DB so a
     * misbehaving stack trace can't blow the row's column budget. The
     * {@code error_message} column is TEXT (no hard SQL limit), but
     * {@link #MAX_ERROR_MESSAGE_CHARS} keeps storage + UI rendering predictable.
     * Anything longer is implicitly "see server log" — the full trace lives in
     * {@code logs/skillforge-server.log}.
     */
    private static String truncateErrorMessage(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length() <= MAX_ERROR_MESSAGE_CHARS) {
            return raw;
        }
        return raw.substring(0, MAX_ERROR_MESSAGE_CHARS);
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
                    .findByOriginAndStatusAndSeqNoIsNullAndCreatedAtBefore(
                            "user_upload", "uploaded", before);
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
                        Path managedPath = requireManagedCleanupPath(row.getSessionId(), storagePath);
                        if (!dryRun) {
                            if (Files.deleteIfExists(managedPath)) {
                                filesDeleted++;
                            }
                        } else if (Files.exists(managedPath)) {
                            // dry-run: count the file we WOULD delete
                            filesDeleted++;
                        }
                    } catch (IOException | SecurityException ioe) {
                        log.warn("ATTACHMENT-CLEANUP: failed to delete orphan file id={} reason={}",
                                row.getId(), ioe.getMessage());
                        errors.add("delete file for row " + row.getId() + ": " + ioe.getMessage());
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
                        try {
                            if (!Files.getLastModifiedTime(file).toInstant().isBefore(before)) continue;
                        } catch (IOException ioe) {
                            errors.add("read modified time for " + file.getFileName() + ": " + ioe.getMessage());
                            continue;
                        }
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

    private Path requireManagedCleanupPath(String sessionId, String rawPath) throws IOException {
        Path normalizedRoot = storageRoot.toAbsolutePath().normalize();
        Path sessionRoot = normalizedRoot.resolve(sessionId).normalize();
        Path configured = Path.of(rawPath).toAbsolutePath().normalize();
        if (!configured.startsWith(sessionRoot)) {
            throw new SecurityException("Attachment cleanup path is outside managed storage");
        }
        if (!Files.exists(configured)) return configured;
        Path realRoot = normalizedRoot.toRealPath();
        Path realFile = configured.toRealPath();
        if (!realFile.startsWith(realRoot.resolve(sessionId).normalize()) || !Files.isRegularFile(realFile)) {
            throw new SecurityException("Attachment cleanup path is outside managed storage");
        }
        return realFile;
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
