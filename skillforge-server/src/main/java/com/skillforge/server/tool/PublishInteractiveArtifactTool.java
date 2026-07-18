package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import com.skillforge.server.artifact.InteractiveArtifactValidator;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.PersonalAppTemplateCatalog;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PublishInteractiveArtifactTool implements Tool {

    public static final String NAME = "PublishInteractiveArtifact";

    private final ChatAttachmentService attachmentService;
    private final PersonalAppTemplateCatalog templateCatalog;
    private final InteractiveArtifactValidator artifactValidator;

    public PublishInteractiveArtifactTool(
            ChatAttachmentService attachmentService,
            PersonalAppTemplateCatalog templateCatalog,
            InteractiveArtifactValidator artifactValidator) {
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.templateCatalog = Objects.requireNonNull(templateCatalog);
        this.artifactValidator = Objects.requireNonNull(artifactValidator);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Publish one offline HTML Personal App into the current chat. "
                + "Provide exactly one source: template_id for a platform template, or file_path for "
                + "a custom self-contained offline HTML file newly written in the current run artifact "
                + "workspace. Historical run files are reference-only: rewrite the final file in the "
                + "current run workspace and never publish a historical path directly. "
                + "Remote resources, network access, device permissions, and tool calls are forbidden.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("template_id", Map.of(
                "type", "string",
                "enum", templateCatalog.templateIds(),
                "description", "Platform-owned Personal App template. Do not combine with file_path."));
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to a custom UTF-8 self-contained HTML file in the current "
                        + "run artifact workspace. Historical run files must be rewritten into the current "
                        + "run first. Do not combine with template_id."));
        properties.put("title", Map.of("type", "string", "minLength", 1, "maxLength", 80));
        properties.put("fallback", Map.of("type", "string", "minLength", 1, "maxLength", 500));
        properties.put("initial_data", Map.of(
                "type", "object",
                "description", "Initial template data, at most 32 KiB with a resource-safety nesting "
                        + "limit of 64. JSON null values are supported. In template mode this overrides "
                        + "packaged demo data."));
        properties.put("state_schema", Map.of(
                "type", "object",
                "description", "Required for custom file mode. In template mode omit it, or provide the "
                        + "exact platform state schema. Supported types are string, number, integer, "
                        + "boolean, object, and array. Objects support properties, required, and "
                        + "additionalProperties (boolean or schema); arrays support items. All unsupported "
                        + "keywords are rejected. Maximum 16 KiB, depth 8, and 1024 JSON value nodes."));
        properties.put("caption", Map.of("type", "string", "maxLength", 1000));
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("title", "fallback")));
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (input == null) {
            return SkillResult.validationError("title, fallback, and exactly one of file_path or template_id are required");
        }
        String title = string(input, "title");
        String fallback = string(input, "fallback");
        if (title == null || fallback == null) {
            return SkillResult.validationError("title and fallback are required");
        }

        String filePath = string(input, "file_path");
        String templateId = string(input, "template_id");
        if ((filePath == null) == (templateId == null)) {
            return SkillResult.validationError("Provide exactly one of file_path or template_id");
        }
        if (context == null || blank(context.getSessionId()) || context.getUserId() == null
                || blank(context.getToolUseId())) {
            return SkillResult.error("PublishInteractiveArtifact is unavailable outside an active session");
        }

        Map<String, Object> suppliedInitialData = optionalMap(input, "initial_data");
        if (input.containsKey("initial_data") && suppliedInitialData == null) {
            return SkillResult.validationError("initial_data must be an object");
        }

        try {
            if (templateId != null) {
                return publishTemplate(input, context, templateId, title, fallback, suppliedInitialData);
            }
            return publishCustom(input, context, filePath, title, fallback, suppliedInitialData);
        } catch (RuntimeException e) {
            return failure(e);
        }
    }

    private SkillResult publishTemplate(
            Map<String, Object> input,
            SkillContext context,
            String templateId,
            String title,
            String fallback,
            Map<String, Object> suppliedInitialData) {
        PersonalAppTemplateCatalog.Template template = templateCatalog.find(templateId).orElse(null);
        if (template == null) {
            return SkillResult.validationError("Unknown template_id: " + templateId);
        }

        Map<String, Object> stateSchema = template.manifest().stateSchema();
        if (input.containsKey("state_schema")) {
            Map<String, Object> suppliedStateSchema = optionalMap(input, "state_schema");
            if (suppliedStateSchema == null || !stateSchema.equals(suppliedStateSchema)) {
                return SkillResult.validationError("Template mode state_schema must match the platform state_schema");
            }
        }
        Map<String, Object> initialData = suppliedInitialData != null
                ? suppliedInitialData : template.manifest().initialData();
        InteractiveArtifactManifest manifest = manifest(
                title, fallback, initialData, stateSchema);
        artifactValidator.validateManifest(manifest);

        ChatAttachmentEntity attachment = attachmentService.importInteractiveArtifactBytes(
                context.getSessionId(), context.getUserId(), context.getToolUseId(),
                template.filename(), string(input, "caption"), template.htmlBytes(), manifest);
        return success(attachment, manifest);
    }

    private SkillResult publishCustom(
            Map<String, Object> input,
            SkillContext context,
            String filePath,
            String title,
            String fallback,
            Map<String, Object> suppliedInitialData) {
        if (blank(context.getArtifactOutputDirectory())) {
            return SkillResult.error("PublishInteractiveArtifact custom file mode requires an active artifact workspace");
        }
        Map<String, Object> stateSchema = optionalMap(input, "state_schema");
        if (stateSchema == null) {
            return SkillResult.validationError("state_schema is required for custom file mode");
        }
        InteractiveArtifactManifest manifest = manifest(
                title, fallback, suppliedInitialData != null ? suppliedInitialData : Map.of(), stateSchema);
        artifactValidator.validateManifest(manifest);

        Path file = Path.of(filePath);
        Path workspace = Path.of(context.getArtifactOutputDirectory());
        ChatAttachmentEntity attachment = attachmentService.importInteractiveArtifact(
                context.getSessionId(), context.getUserId(), context.getToolUseId(), file,
                string(input, "caption"), workspace, manifest);
        return success(attachment, manifest);
    }

    private static InteractiveArtifactManifest manifest(
            String title,
            String fallback,
            Map<String, Object> initialData,
            Map<String, Object> stateSchema) {
        return new InteractiveArtifactManifest(
                1, title, fallback, List.of(), List.of(), initialData, stateSchema);
    }

    private static SkillResult success(
            ChatAttachmentEntity attachment,
            InteractiveArtifactManifest manifest) {
        PublishedArtifact artifact = new PublishedArtifact(
                attachment.getId(), "interactive_artifact_ref", attachment.getFilename(),
                attachment.getMimeType(), null, null, manifest.fallback(), manifest.title(), 1);
        return SkillResult.success(
                "Personal App published: " + manifest.title(), List.of(artifact));
    }

    private static SkillResult failure(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
        return SkillResult.error("PublishInteractiveArtifact: " + detail);
    }

    private static Map<String, Object> optionalMap(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String stringKey)) return null;
            typed.put(stringKey, entry.getValue());
        }
        return typed;
    }

    private static String string(Map<String, Object> input, String key) {
        return input.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
