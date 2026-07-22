package com.skillforge.server.tool;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.media.ArkImageGenerationClient;
import com.skillforge.server.service.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateImageToolTest {

    @TempDir Path workspace;

    @Test
    void execute_validPrompt_publishesImageArtifactAndRemovesStagingFile() throws Exception {
        ArkImageGenerationClient client = mock(ArkImageGenerationClient.class);
        ChatAttachmentService attachments = mock(ChatAttachmentService.class);
        when(client.generate("blue anvil", "2K", true)).thenReturn(
                new ArkImageGenerationClient.GeneratedImage("https://media.example/a.jpg", "2048x2048", 10));
        org.mockito.Mockito.doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.writeString(target, "jpeg");
            return null;
        }).when(client).download(any(), any());
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId("attachment-1");
        entity.setFilename("generated.jpg");
        entity.setMimeType("image/jpeg");
        entity.setCaption("caption");
        when(attachments.findGeneratedByToolUse("session-1", "tool-1")).thenReturn(Optional.empty());
        when(attachments.importGeneratedFile(eq("session-1"), eq(7L), eq("tool-1"), any(),
                eq("caption"), eq(workspace.toAbsolutePath().normalize()))).thenReturn(entity);
        GenerateImageTool tool = new GenerateImageTool(client, attachments);

        SkillResult result = tool.execute(Map.of("prompt", "blue anvil", "caption", "caption"), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getArtifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.getAttachmentId()).isEqualTo("attachment-1");
            assertThat(artifact.getBlockType()).isEqualTo("image_ref");
        });
        assertThat(workspace.resolve("seedream-tool-1.jpg")).doesNotExist();
        verify(client).generate("blue anvil", "2K", true);
    }

    @Test
    void execute_replayedToolUse_returnsExistingArtifactWithoutProviderCharge() {
        ArkImageGenerationClient client = mock(ArkImageGenerationClient.class);
        ChatAttachmentService attachments = mock(ChatAttachmentService.class);
        ChatAttachmentEntity existing = new ChatAttachmentEntity();
        existing.setId("existing");
        existing.setFilename("existing.jpg");
        existing.setMimeType("image/jpeg");
        when(attachments.findGeneratedByToolUse("session-1", "tool-1")).thenReturn(Optional.of(existing));

        SkillResult result = new GenerateImageTool(client, attachments)
                .execute(Map.of("prompt", "blue anvil"), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getArtifacts()).singleElement()
                .extracting(com.skillforge.core.skill.PublishedArtifact::getAttachmentId)
                .isEqualTo("existing");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void execute_invalidInputs_returnValidationErrorsWithoutCallingProvider() {
        GenerateImageTool tool = new GenerateImageTool(
                mock(ArkImageGenerationClient.class), mock(ChatAttachmentService.class));

        assertThat(tool.execute(Map.of(), context()).getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(tool.execute(Map.of("prompt", "ok", "size", "8K"), context()).getErrorType())
                .isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    private SkillContext context() {
        SkillContext context = new SkillContext();
        context.setSessionId("session-1");
        context.setUserId(7L);
        context.setToolUseId("tool-1");
        context.setArtifactOutputDirectory(workspace.toString());
        return context;
    }
}
