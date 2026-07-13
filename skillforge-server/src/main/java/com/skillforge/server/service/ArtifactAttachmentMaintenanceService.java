package com.skillforge.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
public class ArtifactAttachmentMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactAttachmentMaintenanceService.class);
    private final ChatAttachmentRepository attachmentRepository;
    private final SessionMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Clock clock;

    @Autowired
    public ArtifactAttachmentMaintenanceService(
            ChatAttachmentRepository attachmentRepository,
            SessionMessageRepository messageRepository,
            ObjectMapper objectMapper,
            @Value("${skillforge.chat.attachments.root:./data/chat-attachments}") String storageRoot) {
        this(attachmentRepository, messageRepository, objectMapper, storageRoot, Clock.systemUTC());
    }

    ArtifactAttachmentMaintenanceService(
            ChatAttachmentRepository attachmentRepository,
            SessionMessageRepository messageRepository,
            ObjectMapper objectMapper,
            String storageRoot,
            Clock clock) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.clock = clock;
    }

    public record Result(int repaired, int deleted, int rejectedPaths) {
    }

    public Result repairAndCleanup(int ttlHours) {
        var cutoff = clock.instant().minus(Duration.ofHours(Math.max(1, ttlHours)));
        List<ChatAttachmentEntity> candidates = attachmentRepository.findStaleGeneratedArtifacts(cutoff);
        int repaired = 0;
        int deleted = 0;
        int rejected = 0;
        for (ChatAttachmentEntity candidate : candidates) {
            if (isReferenced(candidate)) {
                repaired += attachmentRepository.markGeneratedPublished(candidate.getId());
                continue;
            }
            String previousStatus = candidate.getStatus();
            if (attachmentRepository.claimStaleForCleanup(
                    candidate.getId(), cutoff, clock.instant()) != 1) continue;
            if (isReferenced(candidate)) {
                repaired += attachmentRepository.markGeneratedPublished(candidate.getId());
                continue;
            }
            Path path;
            try {
                path = requireManagedPath(candidate);
            } catch (SecurityException e) {
                log.error("Refusing cleanup of attachment [{}] with unmanaged storage path", candidate.getId());
                releaseClaim(candidate, previousStatus);
                rejected++;
                continue;
            }
            try {
                if (attachmentRepository.deleteClaimedArtifact(candidate.getId()) != 1) continue;
                deleted++;
                Files.deleteIfExists(path);
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to clean generated attachment [{}]: {}", candidate.getId(), e.getMessage());
            }
        }
        return new Result(repaired, deleted, rejected);
    }

    private void releaseClaim(ChatAttachmentEntity candidate, String previousStatus) {
        String restored = "deleting".equals(previousStatus) ? "publishing" : previousStatus;
        attachmentRepository.releaseCleanupClaim(
                candidate.getId(), restored, candidate.getBoundAt());
    }

    public boolean isReferenced(ChatAttachmentEntity attachment) {
        return messageRepository.findContentJsonCandidates(attachment.getSessionId(), attachment.getId())
                .stream().anyMatch(json -> containsAttachmentRef(json, attachment.getId()));
    }

    private boolean containsAttachmentRef(String json, String attachmentId) {
        try {
            return containsAttachmentRef(objectMapper.readTree(json), attachmentId);
        } catch (IOException e) {
            log.warn("Ignoring malformed assistant message while repairing attachment [{}]", attachmentId);
            return false;
        }
    }

    private static boolean containsAttachmentRef(JsonNode node, String attachmentId) {
        if (node == null) return false;
        if (node.isObject()) {
            String type = node.path("type").asText();
            String id = node.has("attachment_id")
                    ? node.path("attachment_id").asText() : node.path("attachmentId").asText();
            if (type.endsWith("_ref") && attachmentId.equals(id)) return true;
        }
        for (JsonNode child : node) {
            if (containsAttachmentRef(child, attachmentId)) return true;
        }
        return false;
    }

    private Path requireManagedPath(ChatAttachmentEntity attachment) {
        try {
            Path normalizedRoot = storageRoot.toAbsolutePath().normalize();
            Path sessionRoot = normalizedRoot.resolve(attachment.getSessionId()).normalize();
            Path configured = Path.of(attachment.getStoragePath()).toAbsolutePath().normalize();
            if (!configured.startsWith(sessionRoot)) {
                throw new SecurityException("Attachment path is not managed");
            }
            if (!Files.exists(configured)) return configured;
            Path realRoot = normalizedRoot.toRealPath();
            Path realSessionRoot = realRoot.resolve(attachment.getSessionId()).normalize();
            Path realFile = configured.toRealPath();
            if (!realFile.startsWith(realSessionRoot) || !Files.isRegularFile(realFile)) {
                throw new SecurityException("Attachment path is not managed");
            }
            return realFile;
        } catch (IOException | RuntimeException e) {
            throw new SecurityException("Attachment path is not managed", e);
        }
    }
}
