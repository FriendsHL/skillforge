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
import java.util.Set;
import java.util.Optional;

public class GenerateImageTool implements Tool {

    public static final String NAME = "GenerateImage";
    private static final Logger log = LoggerFactory.getLogger(GenerateImageTool.class);
    private static final int MAX_PROMPT_CHARS = 2_000;
    private static final Set<String> ALLOWED_SIZES = Set.of("2K", "3K", "4K");

    private final ArkImageGenerationClient client;
    private final ChatAttachmentService attachmentService;

    public GenerateImageTool(ArkImageGenerationClient client, ChatAttachmentService attachmentService) {
        this.client = client;
        this.attachmentService = attachmentService;
    }

    @Override public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "Generate one image from a text prompt with the configured Ark Seedream model and publish it "
                + "as an image attachment in the current chat.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string", "description", "Detailed image description."));
        properties.put("size", Map.of("type", "string", "enum", List.of("2K", "3K", "4K"),
                "description", "Output resolution tier. Defaults to 2K."));
        properties.put("watermark", Map.of("type", "boolean", "description", "Whether to retain provider watermark. Defaults to true."));
        properties.put("caption", Map.of("type", "string", "description", "Optional short caption shown in chat."));
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object", "properties", properties, "required", List.of("prompt")));
    }

    @Override public boolean isReadOnly() { return false; }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (input == null || !(input.get("prompt") instanceof String prompt) || prompt.isBlank()) {
            return SkillResult.validationError("prompt is required");
        }
        if (prompt.length() > MAX_PROMPT_CHARS) return SkillResult.validationError("prompt exceeds 2000 characters");
        String size = input.get("size") instanceof String value ? value : "2K";
        if (!ALLOWED_SIZES.contains(size)) return SkillResult.validationError("size must be 2K, 3K, or 4K");
        boolean watermark = !(input.get("watermark") instanceof Boolean value) || value;
        String caption = input.get("caption") instanceof String value ? value : null;
        if (!validContext(context)) return SkillResult.error("GenerateImage is unavailable outside an active artifact workspace");

        Optional<ChatAttachmentEntity> replay = attachmentService.findGeneratedByToolUse(
                context.getSessionId(), context.getToolUseId());
        if (replay.isPresent()) return publishedResult(replay.get(), null);

        Path workspace = Path.of(context.getArtifactOutputDirectory()).toAbsolutePath().normalize();
        Path generated = workspace.resolve("seedream-" + context.getToolUseId() + ".jpg").normalize();
        if (!generated.startsWith(workspace)) {
            return SkillResult.error("GenerateImage artifact path is invalid");
        }
        try {
            ArkImageGenerationClient.GeneratedImage result = client.generate(prompt, size, watermark);
            client.download(result, generated);
            ChatAttachmentEntity attachment = attachmentService.importGeneratedFile(
                    context.getSessionId(), context.getUserId(), context.getToolUseId(), generated,
                    caption, workspace);
            return publishedResult(attachment, result.size());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return SkillResult.error("GenerateImage: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(generated);
            } catch (Exception e) {
                log.warn("Failed to remove Seedream staging file for session [{}]", context.getSessionId());
            }
        }
    }

    private static SkillResult publishedResult(ChatAttachmentEntity attachment, String resultSize) {
        PublishedArtifact artifact = new PublishedArtifact(
                attachment.getId(), "image_ref", attachment.getFilename(), attachment.getMimeType(),
                null, null, attachment.getCaption());
        String suffix = resultSize == null ? "" : " (" + resultSize + ")";
        return SkillResult.success("Image generated and published" + suffix, List.of(artifact));
    }

    private static boolean validContext(SkillContext context) {
        return context != null && context.getSessionId() != null && !context.getSessionId().isBlank()
                && context.getUserId() != null && context.getToolUseId() != null && !context.getToolUseId().isBlank()
                && context.getArtifactOutputDirectory() != null && !context.getArtifactOutputDirectory().isBlank();
    }
}
