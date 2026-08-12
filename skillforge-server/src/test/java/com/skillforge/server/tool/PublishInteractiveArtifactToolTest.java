package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import com.skillforge.server.artifact.InteractiveArtifactValidator;
import com.skillforge.server.artifact.InteractiveArtifactViolationException;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.PersonalAppTemplateCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishInteractiveArtifactToolTest {

    @Mock ChatAttachmentService attachmentService;

    @TempDir Path tempDir;

    private PersonalAppTemplateCatalog catalog;
    private PublishInteractiveArtifactTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        catalog = new PersonalAppTemplateCatalog(objectMapper);
        tool = new PublishInteractiveArtifactTool(
                attachmentService, catalog, new InteractiveArtifactValidator(objectMapper), objectMapper);
    }

    @Test
    void customFileModePublishesTypedRefWithoutEmbeddingHtmlInProviderText() throws Exception {
        Path workspace = tempDir;
        Path html = workspace.resolve("budget.html");
        Files.writeString(html, "<!doctype html><title>Budget</title>");
        ChatAttachmentEntity attachment = attachment("artifact-1", "budget.html");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenReturn(attachment);
        SkillContext context = context(workspace);

        var result = tool.execute(Map.of(
                "entry_file", "budget.html",
                "title", "July budget",
                "fallback", "Offline budget planner",
                "initial_data", Map.of("food", 2600),
                "state_schema", Map.of("type", "object", "properties", Map.of())), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(json(result.getOutput()))
                .containsEntry("success", true)
                .containsEntry("artifactId", "artifact-1")
                .containsEntry("address", "/api/chat/attachments/artifact-1/data")
                .containsEntry("status", "published");
        assertThat(result.getOutput()).doesNotContain("<html");
        assertThat(result.getArtifacts()).singleElement().satisfies(ref -> {
            assertThat(ref.getBlockType()).isEqualTo("interactive_artifact_ref");
            assertThat(ref.getTitle()).isEqualTo("July budget");
            assertThat(ref.getArtifactSchemaVersion()).isEqualTo(1);
            assertThat(ref.getCaption()).isEqualTo("Offline budget planner");
        });
    }

    @Test
    void templateModeUsesTrustedBytesPlatformSchemaAndCallerInitialDataWithoutWorkspace() {
        var template = catalog.find("ai-daily-brief-v1").orElseThrow();
        ChatAttachmentEntity attachment = attachment("artifact-2", template.filename());
        when(attachmentService.importInteractiveArtifactBytes(
                eq("session-1"), eq(7L), eq("tool-1"), eq(template.filename()), eq("Daily"),
                any(byte[].class), any()))
                .thenReturn(attachment);
        SkillContext context = context(null);
        Map<String, Object> initialData = Map.of("items", List.of(), "dateLabel", "Today");

        var result = tool.execute(Map.of(
                "template_id", "ai-daily-brief-v1",
                "title", "AI daily",
                "fallback", "Daily AI updates",
                "caption", "Daily",
                "initial_data", initialData), context);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<InteractiveArtifactManifest> manifest =
                ArgumentCaptor.forClass(InteractiveArtifactManifest.class);
        verify(attachmentService).importInteractiveArtifactBytes(
                eq("session-1"), eq(7L), eq("tool-1"), eq(template.filename()), eq("Daily"),
                bytes.capture(), manifest.capture());
        assertThat(bytes.getValue()).isEqualTo(template.htmlBytes());
        assertThat(manifest.getValue().initialData()).isEqualTo(initialData);
        assertThat(manifest.getValue().stateSchema()).isEqualTo(template.manifest().stateSchema());
        assertThat(manifest.getValue().title()).isEqualTo("AI daily");
    }

    @Test
    void templateModeUsesPackagedDemoInitialDataWhenCallerOmitsOverride() {
        var template = catalog.find("budget-planner-v1").orElseThrow();
        when(attachmentService.importInteractiveArtifactBytes(
                eq("session-1"), eq(7L), eq("tool-1"), eq(template.filename()), eq(null),
                any(byte[].class), any()))
                .thenReturn(attachment("artifact-3", template.filename()));

        var result = tool.execute(Map.of(
                "template_id", "budget-planner-v1",
                "title", "Budget",
                "fallback", "Offline budget"), context(null));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<InteractiveArtifactManifest> manifest =
                ArgumentCaptor.forClass(InteractiveArtifactManifest.class);
        verify(attachmentService).importInteractiveArtifactBytes(
                eq("session-1"), eq(7L), eq("tool-1"), eq(template.filename()), eq(null),
                any(byte[].class), manifest.capture());
        assertThat(manifest.getValue().initialData()).isEqualTo(template.manifest().initialData());
    }

    @Test
    void templateModePreservesLegalNullInitialDataWithoutLeakingAnException() {
        var template = catalog.find("ai-daily-brief-v1").orElseThrow();
        when(attachmentService.importInteractiveArtifactBytes(
                eq("session-1"), eq(7L), eq("tool-1"), eq(template.filename()), eq(null),
                any(byte[].class), any()))
                .thenReturn(attachment("artifact-null-template", template.filename()));
        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put("subtitle", null);

        SkillResult result = tool.execute(Map.of(
                "template_id", "ai-daily-brief-v1",
                "title", "AI daily",
                "fallback", "Daily AI updates",
                "initial_data", initialData), context(null));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<InteractiveArtifactManifest> manifest =
                ArgumentCaptor.forClass(InteractiveArtifactManifest.class);
        verify(attachmentService).importInteractiveArtifactBytes(
                eq("session-1"), eq(7L), eq("tool-1"), eq(template.filename()), eq(null),
                any(byte[].class), manifest.capture());
        assertThat(manifest.getValue().initialData()).containsEntry("subtitle", null);
    }

    @Test
    void customModePreservesLegalNullInitialDataWithoutLeakingAnException() throws Exception {
        Path workspace = tempDir;
        Path html = workspace.resolve("null-data.html");
        Files.writeString(html, "<!doctype html><title>App</title>");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenReturn(attachment("artifact-null-custom", "null-data.html"));
        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put("subtitle", null);

        SkillResult result = tool.execute(Map.of(
                "entry_file", "null-data.html",
                "title", "App",
                "fallback", "Offline app",
                "initial_data", initialData,
                "state_schema", Map.of("type", "object")), context(workspace));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<InteractiveArtifactManifest> manifest =
                ArgumentCaptor.forClass(InteractiveArtifactManifest.class);
        verify(attachmentService).importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace),
                manifest.capture());
        assertThat(manifest.getValue().initialData()).containsEntry("subtitle", null);
    }

    @Test
    void malformedNullStateSchemaReturnsSkillResultBeforeCallingTheService() {
        Path workspace = Path.of("/tmp/artifacts");
        Map<String, Object> stateSchema = new LinkedHashMap<>();
        stateSchema.put("type", null);

        SkillResult result = tool.execute(Map.of(
                "file_path", workspace.resolve("bad.html").toString(),
                "title", "App",
                "fallback", "Offline app",
                "state_schema", stateSchema), context(workspace));

        assertThat(result.isSuccess()).isFalse();
        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_SCHEMA_INVALID")
                .containsEntry("failedField", "state_schema");
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
        verify(attachmentService, never()).importInteractiveArtifactBytes(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deeplyNestedSchemaAndInitialDataReturnSkillResultsInsteadOfLeakingErrors() {
        Map<String, Object> nestedSchema = Map.of("type", "string");
        Map<String, Object> nestedInitialData = Map.of("leaf", "value");
        for (int i = 0; i < 10_000; i++) {
            nestedSchema = Map.of("type", "array", "items", nestedSchema);
            nestedInitialData = Map.of("child", nestedInitialData);
        }
        Map<String, Object> finalNestedSchema = Map.of(
                "type", "object",
                "properties", Map.of("nested", nestedSchema));

        SkillResult schemaResult = tool.execute(Map.of(
                "file_path", "/tmp/artifacts/deep.html",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", finalNestedSchema), context(Path.of("/tmp/artifacts")));
        SkillResult initialDataResult = tool.execute(Map.of(
                "template_id", "ai-daily-brief-v1",
                "title", "Daily",
                "fallback", "Offline daily",
                "initial_data", nestedInitialData), context(null));

        assertThat(schemaResult.isSuccess()).isFalse();
        assertThat(json(schemaResult.getError()))
                .containsEntry("errorCode", "ARTIFACT_SCHEMA_INVALID")
                .containsEntry("failedField", "state_schema");
        assertThat(initialDataResult.isSuccess()).isFalse();
        assertThat(json(initialDataResult.getError()))
                .containsEntry("errorCode", "ARTIFACT_INITIAL_DATA_INVALID")
                .containsEntry("failedField", "initial_data");
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
        verify(attachmentService, never()).importInteractiveArtifactBytes(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMissingDualUnknownAndPathLikeSources() {
        SkillContext context = context(Path.of("/tmp/artifacts"));

        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("title", "A", "fallback", "B"),
                Map.of("file_path", "/tmp/a.html", "template_id", "ai-daily-brief-v1",
                        "title", "A", "fallback", "B", "state_schema", Map.of("type", "object")),
                Map.of("template_id", "missing-v1", "title", "A", "fallback", "B"),
                Map.of("template_id", "../ai-daily-brief-v1", "title", "A", "fallback", "B"))) {
            SkillResult result = tool.execute(input, context);
            assertThat(result.isSuccess()).as(input.toString()).isFalse();
            assertThat(result.getErrorType()).as(input.toString())
                    .isEqualTo(SkillResult.ErrorType.VALIDATION);
        }
        verify(attachmentService, never()).importInteractiveArtifactBytes(
                any(), any(), any(), any(), any(), any(), any());
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void customModeRequiresStateSchemaAndTemplateModeRejectsSchemaMismatch() {
        var missingCustomSchema = tool.execute(
                Map.of("file_path", "/tmp/a.html", "title", "A", "fallback", "B"),
                context(Path.of("/tmp")));
        var templateMismatch = tool.execute(Map.of(
                        "template_id", "budget-planner-v1",
                        "title", "A",
                        "fallback", "B",
                        "state_schema", Map.of("type", "object", "properties", Map.of())),
                context(null));

        assertThat(missingCustomSchema.isSuccess()).isFalse();
        assertThat(missingCustomSchema.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(json(missingCustomSchema.getError()))
                .containsEntry("errorCode", "ARTIFACT_SCHEMA_REQUIRED")
                .containsEntry("failedField", "state_schema");
        assertThat(templateMismatch.isSuccess()).isFalse();
        assertThat(templateMismatch.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(json(templateMismatch.getError()))
                .containsEntry("errorCode", "ARTIFACT_SCHEMA_INVALID")
                .containsEntry("failedField", "state_schema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaAdvertisesTemplateEnumWithoutUnconditionallyRequiringSourceOrStateSchema() {
        Map<String, Object> schema = tool.getToolSchema().getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> templateId = (Map<String, Object>) properties.get("template_id");

        assertThat((List<String>) templateId.get("enum"))
                .containsExactly("ai-daily-brief-v1", "budget-planner-v1");
        assertThat((List<String>) schema.get("required"))
                .containsExactly("title", "fallback")
                .doesNotContain("entry_file", "template_id", "state_schema");
        assertThat(tool.getDescription())
                .contains("template_id")
                .contains("custom self-contained offline HTML")
                .contains("current run")
                .contains("Historical run files are reference-only")
                .contains("data-sf-url")
                .contains("Ordinary href navigation is forbidden")
                .contains("rewrite")
                .contains("never publish a historical path directly");
        assertThat(properties)
                .containsKeys("entry_file", "replace_artifact_id")
                .doesNotContainKey("file_path");
        assertThat(properties.get("entry_file").toString())
                .contains("current run")
                .containsIgnoringCase("relative");
        assertThat(properties.get("state_schema").toString())
                .contains("string, number, integer, boolean, object, and array")
                .contains("additionalProperties")
                .contains("unsupported keywords are rejected")
                .contains("16 KiB, depth 8, and 1024 JSON value nodes");
        assertThat(properties.get("initial_data").toString())
                .contains("32 KiB")
                .contains("nesting limit of 64")
                .contains("JSON null values are supported");
    }

    @Test
    void entryFileRejectsAbsoluteAndEscapingPathsBeforeImport() {
        for (String entryFile : List.of("/tmp/app.html", "../app.html", "nested/../../app.html")) {
            SkillResult result = tool.execute(Map.of(
                    "entry_file", entryFile,
                    "title", "App",
                    "fallback", "Offline app",
                    "state_schema", Map.of("type", "object")), context(tempDir));

            assertThat(json(result.getError()))
                    .containsEntry("errorCode", "ARTIFACT_ENTRY_FILE_INVALID")
                    .containsEntry("failedField", "entry_file")
                    .containsEntry("retryable", false)
                    .containsEntry("recoveryAction", "WRITE_CURRENT_ENTRY")
                    .containsEntry("preserveUserGoal", true);
        }
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replacementPublishesANewTrackedRevision() throws Exception {
        Path html = tempDir.resolve("revised.html");
        Files.writeString(html, "<!doctype html><html><body>Revised</body></html>");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(tempDir), any(),
                eq("artifact-original")))
                .thenReturn(attachment("artifact-revision", "revised.html"));

        SkillResult result = tool.execute(Map.of(
                "entry_file", "revised.html",
                "replace_artifact_id", "artifact-original",
                "title", "Revised app",
                "fallback", "Revised offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(result.isSuccess()).isTrue();
        assertThat(json(result.getOutput()))
                .containsEntry("artifactId", "artifact-revision")
                .containsEntry("replacesArtifactId", "artifact-original")
                .containsEntry("revisionMode", "new_version");
    }

    @Test
    void invalidReplacementReturnsAnActionableNonRetryableResult() throws Exception {
        Path html = tempDir.resolve("revised.html");
        Files.writeString(html, "<!doctype html><html><body>Revised</body></html>");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(tempDir), any(),
                eq("artifact-foreign")))
                .thenThrow(new IllegalArgumentException(
                        "Replacement artifact must belong to the owned interactive session"));

        SkillResult result = tool.execute(Map.of(
                "entry_file", "revised.html",
                "replace_artifact_id", "artifact-foreign",
                "title", "Revised app",
                "fallback", "Revised offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_REPLACEMENT_INVALID")
                .containsEntry("failedField", "replace_artifact_id")
                .containsEntry("retryable", false)
                .containsEntry("recoveryAction", "SELECT_OWNED_ARTIFACT")
                .containsEntry("preserveUserGoal", true);
    }

    @Test
    void malformedReplacementDoesNotSilentlyPublishAnUntrackedVersion() {
        SkillResult result = tool.execute(Map.of(
                "entry_file", "revised.html",
                "replace_artifact_id", 42,
                "title", "Revised app",
                "fallback", "Revised offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_REPLACEMENT_INVALID")
                .containsEntry("recoveryAction", "SELECT_OWNED_ARTIFACT");
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void returnsCustomImportFailureAsSkillResultForTheLlm() throws Exception {
        Path workspace = tempDir;
        Path html = workspace.resolve("app.html");
        Files.writeString(html, "<!doctype html><title>App</title>");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenThrow(new SecurityException("source is outside the current run workspace"));

        SkillResult result = tool.execute(Map.of(
                "entry_file", "app.html",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object", "properties", Map.of())),
                context(workspace));

        assertThat(result.isSuccess()).isFalse();
        assertThat(json(result.getError()))
                .containsEntry("success", false)
                .containsEntry("errorCode", "ARTIFACT_WORKSPACE_MISMATCH")
                .containsEntry("errorType", "VALIDATION")
                .containsEntry("retryable", false)
                .containsEntry("failedField", "entry_file")
                .containsKey("suggestedAction");
        assertThat(result.getError()).doesNotContain("<html", "data:text/html;base64");
    }

    @Test
    void rejectsNonHtmlCustomFileBeforeImport() throws Exception {
        Path file = tempDir.resolve("app.txt");
        Files.writeString(file, "<!doctype html><title>App</title>");

        SkillResult result = tool.execute(Map.of(
                "entry_file", "app.txt",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(result.isSuccess()).isFalse();
        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_FILE_EXTENSION_INVALID")
                .containsEntry("errorType", "VALIDATION")
                .containsEntry("retryable", false);
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidHtmlIsValidationFailureAndDoesNotInviteIoRetry() throws Exception {
        Path html = tempDir.resolve("invalid.html");
        Files.writeString(html, "<!doctype html><title>App</title>");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(tempDir), any()))
                .thenThrow(new IllegalArgumentException("Interactive artifact HTML must be valid UTF-8"));

        SkillResult result = tool.execute(Map.of(
                "entry_file", "invalid.html",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(result.isSuccess()).isFalse();
        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_HTML_INVALID")
                .containsEntry("errorType", "VALIDATION")
                .containsEntry("retryable", false);
    }

    @Test
    void modeConflictReturnsStableStructuredValidationError() {
        SkillResult result = tool.execute(Map.of(
                "entry_file", "app.html",
                "template_id", "ai-daily-brief-v1",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object")), context(Path.of("/tmp/artifacts")));

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_MODE_CONFLICT")
                .containsEntry("failedField", "entry_file|template_id")
                .containsEntry("retryable", false);
    }

    @Test
    void missingCustomFileFailsPreflightWithStableCode() {
        Path missing = tempDir.resolve("missing.html");

        SkillResult result = tool.execute(Map.of(
                "entry_file", "missing.html",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_FILE_NOT_FOUND")
                .containsEntry("errorType", "VALIDATION")
                .containsEntry("retryable", false)
                .containsEntry("failedField", "entry_file");
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void relativeCustomFileFailsPreflightWithStableCode() {
        SkillResult result = tool.execute(Map.of(
                "file_path", "app.html",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object")), context(tempDir));

        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_FILE_PATH_INVALID")
                .containsEntry("errorType", "VALIDATION")
                .containsEntry("retryable", false)
                .containsEntry("failedField", "file_path");
        verify(attachmentService, never()).importInteractiveArtifact(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void forbiddenCapabilityReturnsStableNonRetryableError() throws Exception {
        Path workspace = tempDir;
        Path html = workspace.resolve("app.html");
        Files.writeString(html, "<!doctype html><title>App</title>");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenThrow(dynamicHtmlInjectionViolation());

        SkillResult result = tool.execute(Map.of(
                "entry_file", "app.html",
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object")), context(workspace));

        assertThat(json(result.getError()))
                .containsEntry("errorCode", "ARTIFACT_FORBIDDEN_CAPABILITY")
                .containsEntry("errorType", "VALIDATION")
                .containsEntry("retryable", false)
                .containsEntry("failedField", "entry_file")
                .containsEntry("violationCode", "DYNAMIC_HTML_INJECTION")
                .containsEntry("message", "Interactive artifact HTML contains forbidden executable script "
                        + "dynamic HTML injection via innerHTML, outerHTML, or insertAdjacentHTML")
                .containsEntry("suggestedAction",
                        "Build elements with createElement, textContent, and append instead.");
        assertThat(result.getError()).doesNotContain("<script", html.toString());
    }

    @Test
    void descriptionTreatsTemplatesAsOptionalFastPathsAndExplainsCustomQualityBar() {
        assertThat(tool.getDescription())
                .contains("optional fast path")
                .contains("first-class")
                .contains("responsive")
                .contains("source links")
                .contains("escape untrusted data")
                .contains("addEventListener")
                .contains("innerHTML")
                .contains("executable JavaScript");
    }

    private Map<String, Object> json(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception e) {
            throw new AssertionError("Expected JSON tool result: " + value, e);
        }
    }

    private InteractiveArtifactViolationException dynamicHtmlInjectionViolation() {
        byte[] html = "<script>list.innerHTML = '<article>Item</article>';</script>"
                .getBytes(StandardCharsets.UTF_8);
        try {
            new InteractiveArtifactValidator(objectMapper).validate(
                    new InteractiveArtifactManifest(
                            1, "App", "Offline app", List.of(), List.of(), Map.of(),
                            Map.of("type", "object")),
                    html);
            throw new AssertionError("Expected dynamic HTML injection to be rejected");
        } catch (InteractiveArtifactViolationException failure) {
            return failure;
        }
    }

    private static SkillContext context(Path workspace) {
        SkillContext context = new SkillContext("/repo", "session-1", 7L);
        context.setToolUseId("tool-1");
        if (workspace != null) context.setArtifactOutputDirectory(workspace.toString());
        return context;
    }

    private static ChatAttachmentEntity attachment(String id, String filename) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId(id);
        attachment.setKind("interactive");
        attachment.setFilename(filename);
        attachment.setMimeType("text/html");
        return attachment;
    }
}
