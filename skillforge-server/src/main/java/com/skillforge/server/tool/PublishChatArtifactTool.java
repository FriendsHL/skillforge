package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
                + "HTML and HTM files are not supported; publish an offline Personal App with "
                + "PublishInteractiveArtifact instead. The final file must be newly written under the "
                + "current run artifact workspace provided in the system prompt.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to a final ordinary supported file, not HTML or HTM, inside "
                        + "the current run artifact workspace. A historical run file may be read as a "
                        + "reference but must be rewritten here before publishing."));
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
        Path requestedFile;
        try {
            requestedFile = Path.of(filePath);
        } catch (InvalidPathException e) {
            return SkillResult.validationError("file_path is invalid");
        }
        Path basename = requestedFile.getFileName();
        if (basename != null) {
            String normalized = basename.toString().toLowerCase(Locale.ROOT);
            if (normalized.endsWith(".html") || normalized.endsWith(".htm")) {
                return SkillResult.validationError(
                        "HTML and HTM files must be published with PublishInteractiveArtifact");
            }
        }
        if (context == null || blank(context.getSessionId()) || context.getUserId() == null
                || blank(context.getToolUseId()) || blank(context.getArtifactOutputDirectory())) {
            return SkillResult.error("PublishChatArtifact is unavailable outside an active artifact workspace");
        }
        String caption = input.get("caption") instanceof String value ? value : null;
        try {
            ChatAttachmentEntity attachment = attachmentService.importGeneratedFile(
                    context.getSessionId(), context.getUserId(), context.getToolUseId(),
                    requestedFile, caption, Path.of(context.getArtifactOutputDirectory()));
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
