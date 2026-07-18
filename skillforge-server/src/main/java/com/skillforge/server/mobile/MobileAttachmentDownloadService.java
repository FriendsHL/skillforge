package com.skillforge.server.mobile;

import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ArtifactAttachmentMaintenanceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

@Service
public class MobileAttachmentDownloadService {

    private final SessionRepository sessionRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ArtifactAttachmentMaintenanceService maintenanceService;
    private final Path storageRoot;

    public MobileAttachmentDownloadService(
            SessionRepository sessionRepository,
            ChatAttachmentRepository attachmentRepository,
            ArtifactAttachmentMaintenanceService maintenanceService,
            @Value("${skillforge.chat.attachments.root:./data/chat-attachments}") String storageRoot) {
        this.sessionRepository = sessionRepository;
        this.attachmentRepository = attachmentRepository;
        this.maintenanceService = maintenanceService;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /** An integrity-verified open file handle. The response writer owns and closes it. */
    public static final class Download implements AutoCloseable {
        private final SeekableByteChannel channel;
        private final long size;
        private final String mimeType;
        private final String filename;
        private final String sha256;
        private final boolean inline;

        Download(SeekableByteChannel channel, long size, String mimeType, String filename,
                 String sha256, boolean inline) {
            this.channel = channel;
            this.size = size;
            this.mimeType = mimeType;
            this.filename = filename;
            this.sha256 = sha256;
            this.inline = inline;
        }

        Download(Path path, long size, String mimeType, String filename,
                 String sha256, boolean inline) throws IOException {
            this(Files.newByteChannel(path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)),
                    size, mimeType, filename, sha256, inline);
        }

        public long size() { return size; }
        public String mimeType() { return mimeType; }
        public String filename() { return filename; }
        public String sha256() { return sha256; }
        public boolean inline() { return inline; }

        public void writeTo(OutputStream output, long start, long length) throws IOException {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read < 0) throw new IOException("Verified attachment ended unexpectedly");
                if (read == 0) continue;
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    public Optional<Download> findAssistantArtifact(
            String sessionId, String attachmentId, Long principalUserId) {
        return findOwnedReferencedAttachment(sessionId, attachmentId, principalUserId)
                .flatMap(this::openVerifiedDownload);
    }

    public Optional<String> findAssistantArtifactManifest(
            String sessionId, String attachmentId, Long principalUserId) {
        return findOwnedReferencedAttachment(sessionId, attachmentId, principalUserId)
                .filter(attachment -> "interactive".equals(attachment.getKind()))
                .filter(attachment -> "text/html".equals(attachment.getMimeType()))
                .map(ChatAttachmentEntity::getInteractiveManifestJson)
                .filter(manifest -> !manifest.isBlank());
    }

    private Optional<ChatAttachmentEntity> findOwnedReferencedAttachment(
            String sessionId, String attachmentId, Long principalUserId) {
        if (principalUserId == null || principalUserId == 0L) return Optional.empty();
        boolean ownsSession = sessionRepository.findById(sessionId)
                .filter(session -> principalUserId.equals(session.getUserId()))
                .isPresent();
        if (!ownsSession) return Optional.empty();
        return attachmentRepository.findById(attachmentId)
                .filter(attachment -> sessionId.equals(attachment.getSessionId()))
                .filter(attachment -> principalUserId.equals(attachment.getUserId()))
                .filter(attachment -> "agent_generated".equals(attachment.getOrigin()))
                .filter(maintenanceService::isReferenced);
    }

    private Optional<Download> openVerifiedDownload(ChatAttachmentEntity attachment) {
        String expectedHash = attachment.getSha256();
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) return Optional.empty();
        SeekableByteChannel channel = null;
        try {
            Path root = storageRoot.toRealPath();
            Path sessionRoot = root.resolve(attachment.getSessionId()).normalize();
            Path configuredPath = Path.of(attachment.getStoragePath()).toAbsolutePath().normalize();
            Path path = configuredPath.startsWith(storageRoot)
                    ? root.resolve(storageRoot.relativize(configuredPath)).normalize()
                    : configuredPath;
            if (!path.startsWith(sessionRoot)
                    || !Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS)
                    || !sessionRoot.toRealPath().equals(sessionRoot)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || hasSymbolicLinkComponent(sessionRoot, path)) return Optional.empty();

            channel = Files.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            long actualSize = channel.size();
            String actualHash = hash(channel);
            if (actualSize != attachment.getSizeBytes() || !expectedHash.equals(actualHash)) {
                channel.close();
                return Optional.empty();
            }
            channel.position(0);
            boolean inline = "image".equals(attachment.getKind()) || "pdf".equals(attachment.getKind());
            return Optional.of(new Download(channel, actualSize, attachment.getMimeType(),
                    attachment.getFilename(), expectedHash, inline));
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Preserve the validation failure.
                }
            }
            return Optional.empty();
        }
    }

    private static String hash(SeekableByteChannel channel) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean hasSymbolicLinkComponent(Path base, Path path) {
        Path current = base;
        for (Path component : base.relativize(path)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }
}
