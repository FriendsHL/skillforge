package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import com.skillforge.server.artifact.InteractiveArtifactValidator;
import com.skillforge.server.artifact.InteractiveArtifactViolationException;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.PersonalAppTemplateCatalog;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PublishInteractiveArtifactTool implements Tool {

    public static final String NAME = "PublishInteractiveArtifact";

    private final ChatAttachmentService attachmentService;
    private final PersonalAppTemplateCatalog templateCatalog;
    private final InteractiveArtifactValidator artifactValidator;
    private final ObjectMapper objectMapper;

    public PublishInteractiveArtifactTool(
            ChatAttachmentService attachmentService,
            PersonalAppTemplateCatalog templateCatalog,
            InteractiveArtifactValidator artifactValidator,
            ObjectMapper objectMapper) {
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.templateCatalog = Objects.requireNonNull(templateCatalog);
        this.artifactValidator = Objects.requireNonNull(artifactValidator);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Publish one offline HTML Personal App into the current chat. Platform templates are an "
                + "optional fast path; custom file mode is a first-class option when the user's layout or "
                + "interaction needs do not fit a template. Provide exactly one source: template_id, or "
                + "file_path for a custom self-contained offline HTML file newly written in the current run artifact "
                + "workspace. Custom pages should be responsive on iPhone and desktop, use clear hierarchy "
                + "and expandable detail where useful; source links must put the absolute "
                + "http(s) URL in data-sf-url on a button or link-like control; the platform bridge handles "
                + "its click with user confirmation. URLs inside executable JavaScript are forbidden. "
                + "Ordinary href navigation is forbidden; escape untrusted data. Do not use inline on* event "
                + "handlers; attach events with addEventListener. Do not use innerHTML, outerHTML, or "
                + "insertAdjacentHTML; build dynamic content with createElement and textContent. "
                + "Historical run files are reference-only: rewrite the final file "
                + "in the current run workspace and never publish a historical path directly. Remote "
                + "resources, network access, device permissions, and tool calls are forbidden.";
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
            return validationFailure("ARTIFACT_INPUT_REQUIRED", "input", false,
                    "Provide title, fallback, and exactly one of template_id or file_path.");
        }
        String title = string(input, "title");
        String fallback = string(input, "fallback");
        if (title == null || fallback == null) {
            return validationFailure("ARTIFACT_METADATA_REQUIRED", "title|fallback", false,
                    "Provide non-empty title and fallback fields within the advertised limits.");
        }

        String filePath = string(input, "file_path");
        String templateId = string(input, "template_id");
        if ((filePath == null) == (templateId == null)) {
            return validationFailure("ARTIFACT_MODE_CONFLICT", "file_path|template_id", false,
                    "Choose exactly one mode: template_id or file_path.");
        }
        if (context == null || blank(context.getSessionId()) || context.getUserId() == null
                || blank(context.getToolUseId())) {
            return executionFailure("ARTIFACT_SESSION_UNAVAILABLE", "session", false,
                    "Publish from an active chat session and current tool call.");
        }

        Map<String, Object> suppliedInitialData = optionalMap(input, "initial_data");
        if (input.containsKey("initial_data") && suppliedInitialData == null) {
            return validationFailure("ARTIFACT_INITIAL_DATA_INVALID", "initial_data", false,
                    "Pass initial_data as a JSON object.");
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
            return validationFailure("ARTIFACT_TEMPLATE_UNKNOWN", "template_id", false,
                    "Use one of the template_id enum values, or switch to custom file mode.");
        }

        Map<String, Object> stateSchema = template.manifest().stateSchema();
        if (input.containsKey("state_schema")) {
            Map<String, Object> suppliedStateSchema = optionalMap(input, "state_schema");
            if (suppliedStateSchema == null || !stateSchema.equals(suppliedStateSchema)) {
                return validationFailure("ARTIFACT_SCHEMA_INVALID", "state_schema", false,
                        "Omit state_schema in template mode or pass the exact platform schema.");
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
            return executionFailure("ARTIFACT_WORKSPACE_UNAVAILABLE", "file_path", false,
                    "Create the custom file in the artifact workspace for the current run.");
        }
        Map<String, Object> stateSchema = optionalMap(input, "state_schema");
        if (stateSchema == null) {
            return validationFailure("ARTIFACT_SCHEMA_REQUIRED", "state_schema", false,
                    "Provide a supported state_schema for custom file mode.");
        }
        InteractiveArtifactManifest manifest = manifest(
                title, fallback, suppliedInitialData != null ? suppliedInitialData : Map.of(), stateSchema);
        artifactValidator.validateManifest(manifest);

        Path file;
        Path workspace;
        try {
            Path suppliedFile = Path.of(filePath);
            if (!suppliedFile.isAbsolute()) {
                return validationFailure("ARTIFACT_FILE_PATH_INVALID", "file_path", false,
                        "Use an absolute file_path inside the current run artifact workspace.");
            }
            file = suppliedFile.normalize();
            workspace = Path.of(context.getArtifactOutputDirectory()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return validationFailure("ARTIFACT_FILE_PATH_INVALID", "file_path", false,
                    "Use an absolute file_path inside the current run artifact workspace.");
        }
        if (!file.startsWith(workspace)) {
            return validationFailure("ARTIFACT_WORKSPACE_MISMATCH", "file_path", false,
                    "Rewrite the final HTML inside the current run artifact workspace, then publish that new path.");
        }
        String filename = file.getFileName() == null
                ? "" : file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(filename.endsWith(".html") || filename.endsWith(".htm"))) {
            return validationFailure("ARTIFACT_FILE_EXTENSION_INVALID", "file_path", false,
                    "Write the custom Personal App as a .html or .htm file in the current run workspace.");
        }
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return validationFailure("ARTIFACT_FILE_NOT_FOUND", "file_path", false,
                    "Write the final HTML file in the current run workspace before publishing it.");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return validationFailure("ARTIFACT_FILE_INVALID", "file_path", false,
                    "Use a regular, non-symlink HTML file inside the current run workspace.");
        }
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

    private SkillResult success(
            ChatAttachmentEntity attachment,
            InteractiveArtifactManifest manifest) {
        PublishedArtifact artifact = new PublishedArtifact(
                attachment.getId(), "interactive_artifact_ref", attachment.getFilename(),
                attachment.getMimeType(), null, null, manifest.fallback(), manifest.title(), 1);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("artifactId", attachment.getId());
        output.put("address", "/api/chat/attachments/" + attachment.getId() + "/data");
        output.put("status", "published");
        output.put("title", manifest.title());
        return SkillResult.success(json(output), List.of(artifact));
    }

    private SkillResult failure(RuntimeException failure) {
        if (failure instanceof InteractiveArtifactViolationException violation) {
            return forbiddenCapabilityFailure(violation);
        }
        String detail = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
        if (detail.contains("state") && detail.contains("schema")) {
            return validationFailure("ARTIFACT_SCHEMA_INVALID", "state_schema", false,
                    "Use only the supported state_schema types and keywords within size and depth limits.");
        }
        if (detail.contains("initialdata") || detail.contains("initial_data")) {
            return validationFailure("ARTIFACT_INITIAL_DATA_INVALID", "initial_data", false,
                    "Reduce or correct initial_data and keep it within the advertised limits.");
        }
        if (detail.contains("forbidden")) {
            return validationFailure("ARTIFACT_FORBIDDEN_CAPABILITY", "file_path", false,
                    "Remove active external resources, network/device access, inline handlers, or other forbidden capabilities.");
        }
        if (failure instanceof SecurityException
                || detail.contains("workspace") || detail.contains("unsafe component")
                || detail.contains("outside the current run")) {
            return validationFailure("ARTIFACT_WORKSPACE_MISMATCH", "file_path", false,
                    "Rewrite the final HTML inside the current run artifact workspace, then publish that new path.");
        }
        if (detail.contains("size limit") || detail.contains("exceeds")) {
            return validationFailure("ARTIFACT_FILE_TOO_LARGE", "file_path", false,
                    "Reduce the self-contained HTML below the advertised size limit.");
        }
        if (detail.contains("different content") || detail.contains("idempotency")) {
            return executionFailure("ARTIFACT_IDEMPOTENCY_CONFLICT", "file_path|template_id", false,
                    "Use a new tool call for different content; do not reuse an earlier tool call identity.");
        }
        if (detail.contains("valid utf-8") || detail.contains("html is required")) {
            return validationFailure("ARTIFACT_HTML_INVALID", "file_path", false,
                    "Write a non-empty UTF-8 HTML document and publish that file again.");
        }
        if (detail.contains("title length") || detail.contains("fallback length")
                || detail.contains("manifest is required") || detail.contains("schemaversion")) {
            return validationFailure("ARTIFACT_METADATA_INVALID", "title|fallback", false,
                    "Correct the Personal App title, fallback, or manifest metadata before publishing.");
        }
        if (failure instanceof IllegalArgumentException) {
            return validationFailure("ARTIFACT_INPUT_INVALID", "file_path|state_schema|initial_data", false,
                    "Correct the invalid Personal App input before publishing again.");
        }
        return executionFailure("ARTIFACT_IO_FAILURE", "file_path|template_id", true,
                "Keep the user goal unchanged and retry once after checking the selected source.");
    }

    private SkillResult validationFailure(String code, String field, boolean retryable, String action) {
        return structuredFailure(SkillResult.ErrorType.VALIDATION, code, field, retryable, action);
    }

    private SkillResult executionFailure(String code, String field, boolean retryable, String action) {
        return structuredFailure(SkillResult.ErrorType.EXECUTION, code, field, retryable, action);
    }

    private SkillResult forbiddenCapabilityFailure(InteractiveArtifactViolationException violation) {
        Map<String, Object> payload = baseFailure(
                SkillResult.ErrorType.VALIDATION,
                "ARTIFACT_FORBIDDEN_CAPABILITY",
                "file_path",
                false,
                violation.getSuggestedAction());
        payload.put("violationCode", violation.getViolationCode());
        payload.put("message", violation.getMessage());
        return SkillResult.validationError(json(payload));
    }

    private SkillResult structuredFailure(
            SkillResult.ErrorType type,
            String code,
            String field,
            boolean retryable,
            String action) {
        Map<String, Object> payload = baseFailure(type, code, field, retryable, action);
        return type == SkillResult.ErrorType.VALIDATION
                ? SkillResult.validationError(json(payload))
                : SkillResult.error(json(payload));
    }

    private static Map<String, Object> baseFailure(
            SkillResult.ErrorType type,
            String code,
            String field,
            boolean retryable,
            String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("errorCode", code);
        payload.put("errorType", type.name());
        payload.put("retryable", retryable);
        payload.put("failedField", field);
        payload.put("suggestedAction", action);
        return payload;
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"errorCode\":\"ARTIFACT_RESULT_SERIALIZATION_FAILED\","
                    + "\"errorType\":\"EXECUTION\",\"retryable\":false,"
                    + "\"failedField\":\"result\",\"suggestedAction\":\"Report the blocked publish operation.\"}";
        }
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
