package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InteractiveArtifactImportTest {

    @Mock ChatAttachmentRepository repository;
    @TempDir Path tempDir;

    private Path storageRoot;
    private Path stagingRoot;
    private ChatAttachmentService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Files.createDirectories(tempDir.resolve("managed"));
        stagingRoot = Files.createDirectories(tempDir.resolve("staging/7/session-1/trace-1"));
        objectMapper = new ObjectMapper();
        service = new ChatAttachmentService(
                repository, storageRoot.toString(), ignored -> { }, objectMapper);
    }

    @Test
    void importsValidatedHtmlWithManifestAndManagedIdentity() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body><input type=range></body></html>");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-1"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatAttachmentEntity result = service.importInteractiveArtifact(
                "session-1", 7L, "tool-1", source, "Budget", stagingRoot, manifest());

        assertThat(result.getKind()).isEqualTo("interactive");
        assertThat(result.getMimeType()).isEqualTo("text/html");
        assertThat(result.getInteractiveManifestJson()).contains("\"schemaVersion\":1");
        assertThat(result.getProcessingMode()).isEqualTo("INTERACTIVE_ARTIFACT_CUSTOM");
        assertThat(Path.of(result.getStoragePath())).hasContent(Files.readString(source));
    }

    @Test
    void rejectsNetworkHtmlBeforeDatabaseWrite() throws Exception {
        Path source = stagingRoot.resolve("bad.html");
        Files.writeString(source, "<script>fetch('https://example.invalid')</script>");

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, "tool-2", source, null, stagingRoot, manifest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsHistoricalHtmlFromTheSameSessionManagedDirectory() throws Exception {
        Path historical = Files.createDirectories(storageRoot.resolve("session-1"))
                .resolve("historical.html");
        Files.writeString(historical, "<!doctype html><html><body>Historical</body></html>");

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, "tool-history", historical, null, stagingRoot, manifest()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("current run workspace");

        assertThat(historical).hasContent("<!doctype html><html><body>Historical</body></html>");
        verify(repository, never()).findBySessionIdAndSourceToolUseId(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void importsTrustedTemplateBytesWithoutReadingAWorkspaceSource() {
        byte[] html = "<!doctype html><html><body><main>Daily</main></body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-template"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatAttachmentEntity result = service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", "Daily",
                html, manifest());

        assertThat(result.getFilename()).isEqualTo("ai-daily-brief.html");
        assertThat(result.getProcessingMode()).isEqualTo("INTERACTIVE_ARTIFACT_TEMPLATE");
        assertThat(Path.of(result.getStoragePath())).hasBinaryContent(html);
        assertThat(stagingRoot).isEmptyDirectory();
    }

    @Test
    void customReplayMismatchDoesNotReserveOrMutateExistingState() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body>Budget</body></html>");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-custom-replay"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChatAttachmentEntity existing = service.importInteractiveArtifact(
                "session-1", 7L, "tool-custom-replay", source, "Budget", stagingRoot, manifest());
        Instant originalBoundAt = Instant.parse("2026-07-17T00:00:00Z");
        existing.setStatus("uploaded");
        existing.setBoundAt(originalBoundAt);
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-custom-replay"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, "tool-custom-replay", source, "Budget", stagingRoot,
                manifestWithInitialData(Map.of("food", 999))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(existing.getStatus()).isEqualTo("uploaded");
        assertThat(existing.getBoundAt()).isEqualTo(originalBoundAt);
        verify(repository, never()).reserveForPublishing(any(), any());
    }

    @Test
    void customReplayRejectsFilenameCaptionUserAndTemplateSourceMode() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body>Budget</body></html>");

        assertCustomReplayRejected(source, "tool-filename", 7L, "different.html", "Budget",
                "INTERACTIVE_ARTIFACT_CUSTOM");
        assertCustomReplayRejected(source, "tool-caption", 7L, "budget.html", "Different",
                "INTERACTIVE_ARTIFACT_CUSTOM");
        assertCustomReplayRejected(source, "tool-user", 8L, "budget.html", "Budget",
                "INTERACTIVE_ARTIFACT_CUSTOM");
        assertCustomReplayRejected(source, "tool-mode", 7L, "budget.html", "Budget",
                "INTERACTIVE_ARTIFACT_TEMPLATE");
    }

    @Test
    void customConcurrentInsertAcceptsOnlyExactReplayAndCleansLosingFile() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body>Budget</body></html>");
        InteractiveArtifactManifest manifest = manifest();
        ChatAttachmentEntity winner = interactiveRow(
                "tool-custom-race", source, 7L, "budget.html", "Budget",
                "INTERACTIVE_ARTIFACT_CUSTOM", manifest);
        winner.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-custom-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("simulated concurrent insert"));

        ChatAttachmentEntity result = service.importInteractiveArtifact(
                "session-1", 7L, "tool-custom-race", source, "Budget", stagingRoot, manifest);

        assertThat(result).isSameAs(winner);
        assertThat(storageRoot.resolve("session-1")).isEmptyDirectory();
    }

    @Test
    void customConcurrentInsertRejectsMismatchedWinnerAndCleansLosingFile() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body>Budget</body></html>");
        InteractiveArtifactManifest manifest = manifest();
        ChatAttachmentEntity winner = interactiveRow(
                "tool-custom-race-mismatch", source, 7L, "budget.html", "Different",
                "INTERACTIVE_ARTIFACT_CUSTOM", manifest);
        winner.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-custom-race-mismatch"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("simulated concurrent insert"));

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, "tool-custom-race-mismatch", source, "Budget",
                stagingRoot, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different interactive artifact payload");

        assertThat(storageRoot.resolve("session-1")).isEmptyDirectory();
        verify(repository, never()).reserveForPublishing(any(), any());
    }

    @Test
    void templateConcurrentInsertAcceptsExactReplayAndCleansLosingFile() throws Exception {
        byte[] html = "<!doctype html><html><body>Daily</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        Path source = stagingRoot.resolve("template-source.html");
        Files.write(source, html);
        ChatAttachmentEntity winner = interactiveRow(
                "tool-template-race", source, 7L, "ai-daily-brief.html", "Daily",
                "INTERACTIVE_ARTIFACT_TEMPLATE", manifest());
        winner.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-template-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("simulated concurrent insert"));

        ChatAttachmentEntity result = service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template-race", "ai-daily-brief.html", "Daily",
                html, manifest());

        assertThat(result).isSameAs(winner);
        assertThat(storageRoot.resolve("session-1")).isEmptyDirectory();
    }

    @Test
    void templateConcurrentInsertRejectsMismatchedWinnerAndCleansLosingFile() throws Exception {
        byte[] html = "<!doctype html><html><body>Daily</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        Path source = stagingRoot.resolve("template-source.html");
        Files.write(source, html);
        ChatAttachmentEntity winner = interactiveRow(
                "tool-template-race-mismatch", source, 7L, "ai-daily-brief.html", "Daily",
                "INTERACTIVE_ARTIFACT_TEMPLATE", manifest());
        winner.setId("00000000-0000-0000-0000-000000000000");
        winner.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-template-race-mismatch"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("simulated concurrent insert"));

        assertThatThrownBy(() -> service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template-race-mismatch", "ai-daily-brief.html", "Daily",
                html, manifest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different interactive artifact payload");

        assertThat(storageRoot.resolve("session-1")).isEmptyDirectory();
        verify(repository, never()).reserveForPublishing(any(), any());
    }

    @Test
    void customReplayRechecksFullPayloadAfterFailedReservationReload() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body>Budget</body></html>");
        ChatAttachmentEntity existing = interactiveRow(
                "tool-reserve-race", source, 7L, "budget.html", "Budget",
                "INTERACTIVE_ARTIFACT_CUSTOM", manifest());
        existing.setStatus("publishing");
        ChatAttachmentEntity replaced = interactiveRow(
                "tool-reserve-race", source, 7L, "budget.html", "Different",
                "INTERACTIVE_ARTIFACT_CUSTOM", manifest());
        replaced.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-reserve-race"))
                .thenReturn(Optional.of(existing));
        when(repository.reserveForPublishing(any(), any())).thenReturn(0);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(replaced));

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, "tool-reserve-race", source, "Budget", stagingRoot, manifest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different interactive artifact payload");
    }

    @Test
    void customReplayRejectsMismatchedDeterministicIdBeforeReservation() throws Exception {
        Path source = stagingRoot.resolve("budget.html");
        Files.writeString(source, "<!doctype html><html><body>Budget</body></html>");
        ChatAttachmentEntity existing = interactiveRow(
                "tool-wrong-id", source, 7L, "budget.html", "Budget",
                "INTERACTIVE_ARTIFACT_CUSTOM", manifest());
        existing.setId("00000000-0000-0000-0000-000000000000");
        Instant originalBoundAt = Instant.parse("2026-07-17T00:00:00Z");
        existing.setStatus("uploaded");
        existing.setBoundAt(originalBoundAt);
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-wrong-id"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, "tool-wrong-id", source, "Budget", stagingRoot, manifest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different interactive artifact payload");

        assertThat(existing.getStatus()).isEqualTo("uploaded");
        assertThat(existing.getBoundAt()).isEqualTo(originalBoundAt);
        verify(repository, never()).reserveForPublishing(any(), any());
    }

    @Test
    void trustedTemplateReplayIsIdempotentAndRejectsPayloadChanges() {
        byte[] html = "<!doctype html><html><body><main>Daily</main></body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-template"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChatAttachmentEntity first = service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", "Daily",
                html, manifest());
        first.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-template"))
                .thenReturn(Optional.of(first));

        assertThat(service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", "Daily",
                html, manifest())).isSameAs(first);
        assertThatThrownBy(() -> service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "budget-planner.html", "Daily",
                html, manifest()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", "Daily",
                "<!doctype html><html><body>Changed</body></html>".getBytes(), manifest()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", "Daily",
                html, manifestWithInitialData(Map.of("food", 999))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", "Changed caption",
                html, manifest()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidTrustedTemplateBytesFailBeforeStorageOrDatabaseSideEffects() {
        byte[] html = "<script>fetch('https://example.invalid')</script>".getBytes();

        assertThatThrownBy(() -> service.importInteractiveArtifactBytes(
                "session-1", 7L, "tool-template", "ai-daily-brief.html", null,
                html, manifest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThat(storageRoot.resolve("session-1")).doesNotExist();
        verify(repository, never()).findBySessionIdAndSourceToolUseId(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    private static InteractiveArtifactManifest manifest() {
        return manifestWithInitialData(Map.of("food", 2600));
    }

    private static InteractiveArtifactManifest manifestWithInitialData(Map<String, Object> initialData) {
        return new InteractiveArtifactManifest(1, "Budget", "Offline budget planner",
                List.of(), List.of(), initialData,
                Map.of("type", "object", "additionalProperties", false,
                        "properties", Map.of()));
    }

    private void assertCustomReplayRejected(
            Path source,
            String toolUseId,
            Long storedUserId,
            String storedFilename,
            String storedCaption,
            String storedMode) throws Exception {
        ChatAttachmentEntity existing = interactiveRow(
                toolUseId, source, storedUserId, storedFilename, storedCaption, storedMode, manifest());
        existing.setStatus("published");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", toolUseId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.importInteractiveArtifact(
                "session-1", 7L, toolUseId, source, "Budget", stagingRoot, manifest()))
                .as(toolUseId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different interactive artifact payload");
    }

    private ChatAttachmentEntity interactiveRow(
            String toolUseId,
            Path source,
            Long userId,
            String filename,
            String caption,
            String processingMode,
            InteractiveArtifactManifest manifest) throws Exception {
        ChatAttachmentEntity row = new ChatAttachmentEntity();
        row.setId(deterministicAttachmentId("session-1", toolUseId));
        row.setSessionId("session-1");
        row.setUserId(userId);
        row.setKind("interactive");
        row.setMimeType("text/html");
        row.setFilename(filename);
        row.setSizeBytes(Files.size(source));
        row.setStoragePath(storageRoot.resolve("session-1/winner.html").toString());
        row.setOrigin("agent_generated");
        row.setSourceToolUseId(toolUseId);
        row.setSha256(ChatAttachmentService.sha256(source));
        row.setCaption(caption);
        row.setInteractiveManifestJson(objectMapper.writeValueAsString(manifest));
        row.setProcessingMode(processingMode);
        return row;
    }

    private static String deterministicAttachmentId(String sessionId, String toolUseId) {
        return UUID.nameUUIDFromBytes((sessionId + "\n" + toolUseId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
