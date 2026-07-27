package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.media.ArkImageGenerationClient;
import com.skillforge.server.service.ChatAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EditImageTool implements Tool {

    public static final String NAME = "EditImage";
    private static final Logger log = LoggerFactory.getLogger(EditImageTool.class);
    private static final int MAX_PROMPT_CHARS = 2_000;
    private static final Set<String> ALLOWED_SIZES = Set.of("2K", "3K", "4K");

    private final ArkImageGenerationClient client;
    private final ChatAttachmentService attachmentService;

    public EditImageTool(ArkImageGenerationClient client, ChatAttachmentService attachmentService) {
        this.client = client;
        this.attachmentService = attachmentService;
    }

    @Override public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "Edit an existing image attachment in the current session with Ark Seedream. "
                + "Always pass the exact source_attachment_id; the source image is preserved.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source_attachment_id", Map.of("type", "string",
                "description", "Exact image attachment ID from the current session."));
        properties.put("prompt", Map.of("type", "string",
                "description", "Explicit edits and the details that must remain unchanged."));
        properties.put("size", Map.of("type", "string", "enum", List.of("2K", "3K", "4K"),
                "description", "Output resolution tier. Defaults to 2K."));
        properties.put("watermark", Map.of("type", "boolean",
                "description", "Whether to retain provider watermark. Defaults to true."));
        properties.put("caption", Map.of("type", "string",
                "description", "Optional short caption shown in chat."));
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object", "properties", properties,
                "required", List.of("source_attachment_id", "prompt")));
    }

    @Override public boolean isReadOnly() { return false; }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String sourceId = stringInput(input, "source_attachment_id");
        String prompt = stringInput(input, "prompt");
        if (sourceId == null || prompt == null) {
            return SkillResult.validationError("source_attachment_id and prompt are required");
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return SkillResult.validationError("prompt exceeds 2000 characters");
        }
        String size = input.get("size") instanceof String value ? value : "2K";
        if (!ALLOWED_SIZES.contains(size)) {
            return SkillResult.validationError("size must be 2K, 3K, or 4K");
        }
        boolean watermark = !(input.get("watermark") instanceof Boolean value) || value;
        String caption = input.get("caption") instanceof String value ? value : null;
        if (!validContext(context)) {
            return SkillResult.error("EditImage is unavailable outside an active artifact workspace");
        }

        ChatAttachmentEntity source = attachmentService.findReadable(
                sourceId, context.getSessionId(), context.getUserId());
        if (source == null) return SkillResult.error("Source image is missing or not owned by this session");
        if (!"image".equals(source.getKind())) return SkillResult.validationError("Source attachment must be an image");

        Optional<ChatAttachmentEntity> replay = attachmentService.findGeneratedByToolUse(
                context.getSessionId(), context.getToolUseId());
        if (replay.isPresent()) {
            attachmentService.recordDerivation(replay.get().getId(), source.getId(), "EDIT_IMAGE");
            return publishedResult(replay.get(), null);
        }

        Path workspace = Path.of(context.getArtifactOutputDirectory()).toAbsolutePath().normalize();
        Path generated = workspace.resolve("seedream-edit-" + context.getToolUseId() + ".jpg").normalize();
        if (!generated.startsWith(workspace)) return SkillResult.error("EditImage artifact path is invalid");
        try {
            byte[] sourceBytes = attachmentService.readBytes(source);
            ArkImageGenerationClient.GeneratedImage result = client.generate(
                    prompt, size, watermark, sourceBytes, source.getMimeType());
            client.download(result, generated);
            ChatAttachmentEntity attachment = attachmentService.importGeneratedFile(
                    context.getSessionId(), context.getUserId(), context.getToolUseId(), generated,
                    caption, workspace);
            attachmentService.recordDerivation(attachment.getId(), source.getId(), "EDIT_IMAGE");
            return publishedResult(attachment, result.size());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return SkillResult.error("EditImage: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(generated);
            } catch (Exception e) {
                log.warn("Failed to remove Seedream edit staging file for session [{}]", context.getSessionId());
            }
        }
    }

    private static String stringInput(Map<String, Object> input, String key) {
        if (input == null || !(input.get(key) instanceof String value) || value.isBlank()) return null;
        return value.trim();
    }

    private static SkillResult publishedResult(ChatAttachmentEntity attachment, String resultSize) {
        PublishedArtifact artifact = new PublishedArtifact(
                attachment.getId(), "image_ref", attachment.getFilename(), attachment.getMimeType(),
                null, null, attachment.getCaption());
        String suffix = resultSize == null ? "" : " (" + resultSize + ")";
        return SkillResult.success("Image edited and published" + suffix, List.of(artifact));
    }

    private static boolean validContext(SkillContext context) {
        return context != null && context.getSessionId() != null && !context.getSessionId().isBlank()
                && context.getUserId() != null && context.getToolUseId() != null
                && !context.getToolUseId().isBlank()
                && context.getArtifactOutputDirectory() != null
                && !context.getArtifactOutputDirectory().isBlank();
    }
}
