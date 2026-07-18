package com.skillforge.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCanonicalPerRunWorkspaceAndPromptInstruction() throws Exception {
        ArtifactWorkspaceService service = new ArtifactWorkspaceService(
                tempDir.toString(), Clock.systemUTC());

        Path workspace = service.create(7L, "session-1", "trace-1");

        assertThat(workspace).isDirectory();
        assertThat(workspace).isEqualTo(tempDir.toRealPath().resolve("7/session-1/trace-1"));
        assertThat(workspace.resolve(".skillforge")).doesNotExist();
        assertThat(service.promptInstruction(workspace))
                .contains(workspace.toString())
                .contains("absolute path")
                .contains("template_id")
                .contains("ai-daily-brief-v1")
                .contains("budget-planner-v1")
                .contains("amounts:{[categoryKey]:number}")
                .contains("file_path")
                .contains("PublishChatArtifact")
                .contains("PublishInteractiveArtifact")
                .contains("image, PDF, Word, Excel, or CSV")
                .contains("HTML and HTM files are rejected by PublishChatArtifact")
                .contains("Historical run files are reference-only")
                .contains("rewrite the final file inside the current run workspace")
                .contains("never publish a historical path directly")
                .contains("state_schema supports only")
                .contains("string, number, integer, boolean, object, and array")
                .contains("16 KiB, depth 8, and 1024 JSON value nodes");
    }

    @Test
    void rejectsUnsafeIdentifiersAndCleanupOutsideRoot() throws Exception {
        ArtifactWorkspaceService service = new ArtifactWorkspaceService(
                tempDir.toString(), Clock.systemUTC());

        assertThatThrownBy(() -> service.create(7L, "../escape", "trace-1"))
                .isInstanceOf(IllegalArgumentException.class);

        Path outside = Files.createTempDirectory("artifact-outside");
        assertThatThrownBy(() -> service.deleteWorkspace(outside))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void ttlCleanupDoesNotFollowSymlinkOutsideRoot() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC);
        ArtifactWorkspaceService service = new ArtifactWorkspaceService(tempDir.toString(), clock);
        Path workspace = service.create(7L, "session-1", "trace-1");
        Path outside = Files.createTempDirectory("artifact-preserved");
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(workspace.resolve("outside-link"), outside);
        Files.setLastModifiedTime(workspace, java.nio.file.attribute.FileTime.from(
                Instant.parse("2026-07-01T00:00:00Z")));

        if (supportsSecureDirectoryStream(tempDir)) {
            assertThat(service.cleanupExpired(24)).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> service.cleanupExpired(24))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Secure directory traversal");
            assertThat(workspace).exists();
        }
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void rejectsWorkspaceReplacedBySymlinkBeforeDeletion() throws Exception {
        ArtifactWorkspaceService service = new ArtifactWorkspaceService(
                tempDir.toString(), Clock.systemUTC());
        Path workspace = service.create(7L, "session-1", "trace-1");
        Path outside = Files.createTempDirectory("artifact-replacement-preserved");
        Files.writeString(outside.resolve("keep.txt"), "keep");
        deleteFixtureTree(workspace);
        Files.createSymbolicLink(workspace, outside);

        assertThatThrownBy(() -> service.deleteWorkspace(workspace))
                .isInstanceOf(SecurityException.class);
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void ttlCleanupRejectsReplacedSessionComponentWithoutFollowingIt() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC);
        ArtifactWorkspaceService service = new ArtifactWorkspaceService(tempDir.toString(), clock);
        Path workspace = service.create(7L, "session-1", "trace-1");
        Path outside = Files.createTempDirectory("artifact-component-preserved");
        Path outsideTrace = Files.createDirectories(outside.resolve("trace-outside"));
        Files.writeString(outsideTrace.resolve("keep.txt"), "keep");
        deleteFixtureTree(workspace);
        Files.delete(workspace.getParent());
        Files.createSymbolicLink(workspace.getParent(), outside);

        assertThat(service.cleanupExpired(24)).isZero();
        assertThat(outsideTrace.resolve("keep.txt")).exists();
    }

    private static boolean supportsSecureDirectoryStream(Path root) throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            return stream instanceof SecureDirectoryStream<?>;
        }
    }

    private static void deleteFixtureTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
