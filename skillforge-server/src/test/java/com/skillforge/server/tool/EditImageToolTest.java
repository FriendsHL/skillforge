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

class EditImageToolTest {

    @TempDir Path workspace;

    @Test
    void execute_ownedImage_editsIntoNewDerivedAttachment() throws Exception {
        ArkImageGenerationClient client = mock(ArkImageGenerationClient.class);
        ChatAttachmentService attachments = mock(ChatAttachmentService.class);
        ChatAttachmentEntity source = attachment("source", "source.jpg");
        source.setSessionId("session-1");
        source.setUserId(7L);
        source.setKind("image");
        when(attachments.findReadable("source", "session-1", 7L)).thenReturn(source);
        when(attachments.readBytes(source)).thenReturn(new byte[]{1, 2, 3});
        when(attachments.findGeneratedByToolUse("session-1", "tool-1")).thenReturn(Optional.empty());
        when(client.generate(eq("make it rainy"), eq("2K"), eq(true),
                any(byte[].class), eq("image/jpeg"))).thenReturn(
                new ArkImageGenerationClient.GeneratedImage("https://media.example/edit.jpg", "2048x2048", 10));
        org.mockito.Mockito.doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[]{4, 5, 6});
            return null;
        }).when(client).download(any(), any());
        ChatAttachmentEntity edited = attachment("edited", "edited.jpg");
        when(attachments.importGeneratedFile(eq("session-1"), eq(7L), eq("tool-1"), any(),
                eq("rain version"), eq(workspace.toAbsolutePath().normalize()))).thenReturn(edited);

        SkillResult result = new EditImageTool(client, attachments).execute(Map.of(
                "source_attachment_id", "source",
                "prompt", "make it rainy",
                "caption", "rain version"), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getArtifacts()).singleElement()
                .extracting(com.skillforge.core.skill.PublishedArtifact::getAttachmentId)
                .isEqualTo("edited");
        verify(attachments).recordDerivation("edited", "source", "EDIT_IMAGE");
        verify(client).generate("make it rainy", "2K", true, new byte[]{1, 2, 3}, "image/jpeg");
    }

    @Test
    void execute_foreignOrNonImageSource_rejectsBeforeProviderCall() {
        ArkImageGenerationClient client = mock(ArkImageGenerationClient.class);
        ChatAttachmentService attachments = mock(ChatAttachmentService.class);
        when(attachments.findGeneratedByToolUse("session-1", "tool-1")).thenReturn(Optional.empty());
        when(attachments.findReadable("foreign", "session-1", 7L)).thenReturn(null);

        SkillResult missing = new EditImageTool(client, attachments).execute(Map.of(
                "source_attachment_id", "foreign", "prompt", "edit"), context());

        ChatAttachmentEntity pdf = attachment("pdf", "source.pdf");
        pdf.setKind("pdf");
        when(attachments.findReadable("pdf", "session-1", 7L)).thenReturn(pdf);
        SkillResult wrongKind = new EditImageTool(client, attachments).execute(Map.of(
                "source_attachment_id", "pdf", "prompt", "edit"), context());

        assertThat(missing.isSuccess()).isFalse();
        assertThat(wrongKind.isSuccess()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void execute_replayedToolUse_repairsDerivationWithoutProviderCharge() {
        ArkImageGenerationClient client = mock(ArkImageGenerationClient.class);
        ChatAttachmentService attachments = mock(ChatAttachmentService.class);
        ChatAttachmentEntity source = attachment("source", "source.jpg");
        source.setKind("image");
        ChatAttachmentEntity existing = attachment("existing", "edited.jpg");
        when(attachments.findReadable("source", "session-1", 7L)).thenReturn(source);
        when(attachments.findGeneratedByToolUse("session-1", "tool-1"))
                .thenReturn(Optional.of(existing));

        SkillResult result = new EditImageTool(client, attachments).execute(Map.of(
                "source_attachment_id", "source", "prompt", "make it rainy"), context());

        assertThat(result.isSuccess()).isTrue();
        verify(attachments).recordDerivation("existing", "source", "EDIT_IMAGE");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private ChatAttachmentEntity attachment(String id, String filename) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setFilename(filename);
        entity.setMimeType("image/jpeg");
        return entity;
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
