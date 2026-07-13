package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactAttachmentMaintenanceServiceTest {

    @Mock ChatAttachmentRepository attachmentRepository;
    @Mock SessionMessageRepository messageRepository;
    @TempDir Path root;

    @Test
    void repairsReferencedUploadedArtifactAndDeletesOnlyContainedOrphan() throws Exception {
        Path referencedFile = create("session-1/referenced.pdf");
        Path orphanFile = create("session-1/orphan.pdf");
        Path outside = Files.createTempFile("outside-artifact", ".pdf");
        ChatAttachmentEntity referenced = row("referenced", referencedFile);
        ChatAttachmentEntity orphan = row("orphan", orphanFile);
        ChatAttachmentEntity escaped = row("escaped", outside);
        when(attachmentRepository.findStaleGeneratedArtifacts(any()))
                .thenReturn(List.of(referenced, orphan, escaped));
        when(attachmentRepository.markGeneratedPublished("referenced")).thenReturn(1);
        when(attachmentRepository.claimStaleForCleanup(any(), any(), any())).thenReturn(1);
        when(attachmentRepository.deleteClaimedArtifact("orphan")).thenReturn(1);
        when(messageRepository.findContentJsonCandidates("session-1", "referenced"))
                .thenReturn(List.of("[{\"type\":\"pdf_ref\",\"attachment_id\":\"referenced\"}]"));
        when(messageRepository.findContentJsonCandidates("session-1", "orphan")).thenReturn(List.of());
        when(messageRepository.findContentJsonCandidates("session-1", "escaped")).thenReturn(List.of());
        ArtifactAttachmentMaintenanceService service = new ArtifactAttachmentMaintenanceService(
                attachmentRepository, messageRepository, new ObjectMapper(), root.toString(),
                Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC));

        ArtifactAttachmentMaintenanceService.Result result = service.repairAndCleanup(24);

        assertThat(referencedFile).exists();
        assertThat(orphanFile).doesNotExist();
        assertThat(outside).exists();
        verify(attachmentRepository).deleteClaimedArtifact("orphan");
        verify(attachmentRepository, never()).deleteClaimedArtifact("escaped");
        assertThat(result.repaired()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.rejectedPaths()).isEqualTo(1);
    }

    @Test
    void referenceAppearingAfterCleanupClaimRepairsInsteadOfDeleting() throws Exception {
        Path file = create("session-1/racing.pdf");
        ChatAttachmentEntity candidate = row("racing", file);
        when(attachmentRepository.findStaleGeneratedArtifacts(any())).thenReturn(List.of(candidate));
        when(messageRepository.findContentJsonCandidates("session-1", "racing"))
                .thenReturn(
                        List.of(),
                        List.of("[{\"type\":\"pdf_ref\",\"attachment_id\":\"racing\"}]"));
        when(attachmentRepository.claimStaleForCleanup(any(), any(), any())).thenReturn(1);
        when(attachmentRepository.markGeneratedPublished("racing")).thenReturn(1);
        ArtifactAttachmentMaintenanceService service = new ArtifactAttachmentMaintenanceService(
                attachmentRepository, messageRepository, new ObjectMapper(), root.toString(),
                Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC));

        ArtifactAttachmentMaintenanceService.Result result = service.repairAndCleanup(24);

        assertThat(file).exists();
        assertThat(result.repaired()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        verify(attachmentRepository, never()).deleteClaimedArtifact(any());
    }

    private Path create(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "%PDF-1.4\n");
    }

    private ChatAttachmentEntity row(String id, Path path) {
        ChatAttachmentEntity row = new ChatAttachmentEntity();
        row.setId(id);
        row.setSessionId("session-1");
        row.setOrigin("agent_generated");
        row.setStatus("uploaded");
        row.setStoragePath(path.toString());
        return row;
    }
}
