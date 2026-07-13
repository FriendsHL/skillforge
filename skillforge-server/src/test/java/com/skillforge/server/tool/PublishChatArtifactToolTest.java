package com.skillforge.server.tool;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishChatArtifactToolTest {

    @Mock ChatAttachmentService attachmentService;

    @Test
    void returnsTypedArtifactSidecarWithoutEmbeddingMetadataInText() {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("attachment-1");
        attachment.setKind("pdf");
        attachment.setFilename("report.pdf");
        attachment.setMimeType("application/pdf");
        attachment.setPageCount(4);
        attachment.setCaption("Report");
        Path workspace = Path.of("/tmp/workspace");
        Path file = workspace.resolve("report.pdf");
        when(attachmentService.importGeneratedFile(
                "session-1", 7L, "tool-1", file, "Report", workspace)).thenReturn(attachment);
        SkillContext context = new SkillContext("/repo/worktree", "session-1", 7L);
        context.setArtifactOutputDirectory(workspace.toString());
        context.setToolUseId("tool-1");

        var result = new PublishChatArtifactTool(attachmentService).execute(
                Map.of("file_path", file.toString(), "caption", "Report"), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Artifact published: report.pdf");
        assertThat(context.getWorkingDirectory()).isEqualTo("/repo/worktree");
        assertThat(result.getArtifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.getAttachmentId()).isEqualTo("attachment-1");
            assertThat(artifact.getBlockType()).isEqualTo("pdf_ref");
            assertThat(artifact.getPageCount()).isEqualTo(4);
            assertThat(artifact.getCaption()).isEqualTo("Report");
        });
    }

    @Test
    void failsClosedWithoutBoundWorkspaceIdentity() {
        SkillContext context = new SkillContext("/repo/worktree", "session-1", 7L);
        context.setToolUseId("tool-1");

        var result = new PublishChatArtifactTool(attachmentService).execute(
                Map.of("file_path", "/etc/passwd"), context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("active artifact workspace");
    }
}
