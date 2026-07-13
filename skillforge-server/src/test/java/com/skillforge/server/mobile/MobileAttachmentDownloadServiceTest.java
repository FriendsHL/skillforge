package com.skillforge.server.mobile;

import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ArtifactAttachmentMaintenanceService;
import com.skillforge.server.service.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileAttachmentDownloadServiceTest {

    @Mock SessionRepository sessionRepository;
    @Mock ChatAttachmentRepository attachmentRepository;
    @Mock ArtifactAttachmentMaintenanceService maintenanceService;
    @TempDir Path root;

    @Test
    void exactPrincipalOwnershipIsRequiredAndSystemUserIsNeverWildcard() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId(7L);
        Path file = root.resolve("session-1/artifact.pdf");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "%PDF-1.4\n");
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("attachment-1");
        attachment.setSessionId("session-1");
        attachment.setUserId(7L);
        attachment.setOrigin("agent_generated");
        attachment.setKind("pdf");
        attachment.setMimeType("application/pdf");
        attachment.setFilename("artifact.pdf");
        attachment.setSizeBytes(Files.size(file));
        attachment.setSha256(ChatAttachmentService.sha256(file));
        attachment.setStoragePath(file.toString());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(attachmentRepository.findById("attachment-1")).thenReturn(Optional.of(attachment));
        when(maintenanceService.isReferenced(attachment)).thenReturn(true);
        MobileAttachmentDownloadService service = new MobileAttachmentDownloadService(
                sessionRepository, attachmentRepository, maintenanceService, root.toString());

        assertThat(service.findAssistantArtifact("session-1", "attachment-1", 7L)).isPresent();
        assertThat(service.findAssistantArtifact("session-1", "attachment-1", 8L)).isEmpty();
        assertThat(service.findAssistantArtifact("session-1", "attachment-1", 0L)).isEmpty();
    }

    @Test
    void canonicalPathEscapeReturnsNotFound() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId(7L);
        Path outside = Files.createTempFile("outside", ".pdf");
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("attachment-1");
        attachment.setSessionId("session-1");
        attachment.setUserId(7L);
        attachment.setOrigin("agent_generated");
        attachment.setSha256("a".repeat(64));
        attachment.setStoragePath(outside.toString());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(attachmentRepository.findById("attachment-1")).thenReturn(Optional.of(attachment));
        when(maintenanceService.isReferenced(attachment)).thenReturn(true);
        MobileAttachmentDownloadService service = new MobileAttachmentDownloadService(
                sessionRepository, attachmentRepository, maintenanceService, root.toString());

        assertThat(service.findAssistantArtifact("session-1", "attachment-1", 7L)).isEmpty();
    }

    @Test
    void rejectsManagedFileWhoseContentDoesNotMatchStoredHash() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId(7L);
        Path file = Files.createDirectories(root.resolve("session-1")).resolve("artifact.pdf");
        Files.writeString(file, "%PDF-1.4\nchanged");
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("attachment-1");
        attachment.setSessionId("session-1");
        attachment.setUserId(7L);
        attachment.setOrigin("agent_generated");
        attachment.setKind("pdf");
        attachment.setMimeType("application/pdf");
        attachment.setFilename("artifact.pdf");
        attachment.setSizeBytes(Files.size(file));
        attachment.setSha256("0".repeat(64));
        attachment.setStoragePath(file.toString());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(attachmentRepository.findById("attachment-1")).thenReturn(Optional.of(attachment));
        when(maintenanceService.isReferenced(attachment)).thenReturn(true);
        MobileAttachmentDownloadService service = new MobileAttachmentDownloadService(
                sessionRepository, attachmentRepository, maintenanceService, root.toString());

        assertThat(service.findAssistantArtifact("session-1", "attachment-1", 7L)).isEmpty();
    }

    @Test
    void streamsVerifiedOpenHandleAfterPathIsReplaced() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId(7L);
        Path file = Files.createDirectories(root.resolve("session-1")).resolve("artifact.pdf");
        Files.writeString(file, "%PDF-1.4\noriginal");
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("attachment-1");
        attachment.setSessionId("session-1");
        attachment.setUserId(7L);
        attachment.setOrigin("agent_generated");
        attachment.setKind("pdf");
        attachment.setMimeType("application/pdf");
        attachment.setFilename("artifact.pdf");
        attachment.setSizeBytes(Files.size(file));
        attachment.setSha256(ChatAttachmentService.sha256(file));
        attachment.setStoragePath(file.toString());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(attachmentRepository.findById("attachment-1")).thenReturn(Optional.of(attachment));
        when(maintenanceService.isReferenced(attachment)).thenReturn(true);
        MobileAttachmentDownloadService service = new MobileAttachmentDownloadService(
                sessionRepository, attachmentRepository, maintenanceService, root.toString());

        MobileAttachmentDownloadService.Download download = service
                .findAssistantArtifact("session-1", "attachment-1", 7L).orElseThrow();
        Files.move(file, file.resolveSibling("opened-original.pdf"));
        Files.writeString(file, "%PDF-1.4\nreplacement");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (download) {
            download.writeTo(output, 0, download.size());
        }

        assertThat(output.toString()).isEqualTo("%PDF-1.4\noriginal");
    }

    @Test
    void rejectsSymlinkInsideManagedSessionEvenWhenTargetHashMatches() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId(7L);
        Path outside = Files.createTempFile("outside-artifact", ".pdf");
        Files.writeString(outside, "%PDF-1.4\noutside");
        Path link = Files.createDirectories(root.resolve("session-1")).resolve("artifact.pdf");
        Files.createSymbolicLink(link, outside);
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("attachment-1");
        attachment.setSessionId("session-1");
        attachment.setUserId(7L);
        attachment.setOrigin("agent_generated");
        attachment.setKind("pdf");
        attachment.setMimeType("application/pdf");
        attachment.setFilename("artifact.pdf");
        attachment.setSizeBytes(Files.size(outside));
        attachment.setSha256(ChatAttachmentService.sha256(outside));
        attachment.setStoragePath(link.toString());
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(attachmentRepository.findById("attachment-1")).thenReturn(Optional.of(attachment));
        when(maintenanceService.isReferenced(attachment)).thenReturn(true);
        MobileAttachmentDownloadService service = new MobileAttachmentDownloadService(
                sessionRepository, attachmentRepository, maintenanceService, root.toString());

        assertThat(service.findAssistantArtifact("session-1", "attachment-1", 7L)).isEmpty();
    }
}
