package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import com.skillforge.server.artifact.InteractiveArtifactValidator;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.PersonalAppTemplateCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
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

    private PersonalAppTemplateCatalog catalog;
    private PublishInteractiveArtifactTool tool;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        catalog = new PersonalAppTemplateCatalog(objectMapper);
        tool = new PublishInteractiveArtifactTool(
                attachmentService, catalog, new InteractiveArtifactValidator(objectMapper));
    }

    @Test
    void customFileModePublishesTypedRefWithoutEmbeddingHtmlInProviderText() {
        Path workspace = Path.of("/tmp/artifacts");
        Path html = workspace.resolve("budget.html");
        ChatAttachmentEntity attachment = attachment("artifact-1", "budget.html");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenReturn(attachment);
        SkillContext context = context(workspace);

        var result = tool.execute(Map.of(
                "file_path", html.toString(),
                "title", "July budget",
                "fallback", "Offline budget planner",
                "initial_data", Map.of("food", 2600),
                "state_schema", Map.of("type", "object", "properties", Map.of())), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Personal App published: July budget");
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
    void customModePreservesLegalNullInitialDataWithoutLeakingAnException() {
        Path workspace = Path.of("/tmp/artifacts");
        Path html = workspace.resolve("null-data.html");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenReturn(attachment("artifact-null-custom", "null-data.html"));
        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put("subtitle", null);

        SkillResult result = tool.execute(Map.of(
                "file_path", html.toString(),
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
        assertThat(result.getError()).contains("PublishInteractiveArtifact", "stateSchema");
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
        assertThat(schemaResult.getError()).contains("PublishInteractiveArtifact", "stateSchema");
        assertThat(initialDataResult.isSuccess()).isFalse();
        assertThat(initialDataResult.getError()).contains("PublishInteractiveArtifact", "initialData");
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
        assertThat(missingCustomSchema.getError()).contains("state_schema");
        assertThat(templateMismatch.isSuccess()).isFalse();
        assertThat(templateMismatch.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(templateMismatch.getError()).contains("platform state_schema");
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
                .doesNotContain("file_path", "template_id", "state_schema");
        assertThat(tool.getDescription())
                .contains("template_id")
                .contains("custom self-contained offline HTML")
                .contains("current run")
                .contains("Historical run files are reference-only")
                .contains("rewrite")
                .contains("never publish a historical path directly");
        assertThat(properties.get("file_path").toString())
                .contains("current run")
                .containsIgnoringCase("historical run");
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
    void returnsCustomImportFailureAsSkillResultForTheLlm() {
        Path workspace = Path.of("/tmp/artifacts");
        Path html = workspace.resolve("app.html");
        when(attachmentService.importInteractiveArtifact(
                eq("session-1"), eq(7L), eq("tool-1"), eq(html), eq(null), eq(workspace), any()))
                .thenThrow(new SecurityException("source is outside the current run workspace"));

        SkillResult result = tool.execute(Map.of(
                "file_path", html.toString(),
                "title", "App",
                "fallback", "Offline app",
                "state_schema", Map.of("type", "object", "properties", Map.of())),
                context(workspace));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains(
                "PublishInteractiveArtifact", "outside the current run workspace");
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
