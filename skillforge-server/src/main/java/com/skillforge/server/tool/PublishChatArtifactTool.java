package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PublishChatArtifactTool implements Tool {

    public static final String NAME = "PublishChatArtifact";
    private final ChatAttachmentService attachmentService;

    public PublishChatArtifactTool(ChatAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Publish a generated image, PDF, Word, Excel, or CSV file into the current chat. "
                + "The file must be under the artifact workspace provided in the system prompt.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to a final file inside this run's artifact workspace."));
        properties.put("caption", Map.of(
                "type", "string",
                "description", "Optional short caption shown with the attachment."));
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("file_path")));
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (input == null || !(input.get("file_path") instanceof String filePath)
                || filePath.isBlank()) {
            return SkillResult.validationError("file_path is required");
        }
        if (context == null || blank(context.getSessionId()) || context.getUserId() == null
                || blank(context.getToolUseId()) || blank(context.getArtifactOutputDirectory())) {
            return SkillResult.error("PublishChatArtifact is unavailable outside an active artifact workspace");
        }
        String caption = input.get("caption") instanceof String value ? value : null;
        try {
            ChatAttachmentEntity attachment = attachmentService.importGeneratedFile(
                    context.getSessionId(), context.getUserId(), context.getToolUseId(),
                    Path.of(filePath), caption, Path.of(context.getArtifactOutputDirectory()));
            PublishedArtifact artifact = new PublishedArtifact(
                    attachment.getId(), attachment.getKind() + "_ref", attachment.getFilename(),
                    attachment.getMimeType(), attachment.getPageCount(),
                    "excel".equals(attachment.getKind()) ? attachment.getPageCount() : null,
                    attachment.getCaption());
            return SkillResult.success("Artifact published: " + attachment.getFilename(), List.of(artifact));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return SkillResult.error("PublishChatArtifact: " + e.getMessage());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
