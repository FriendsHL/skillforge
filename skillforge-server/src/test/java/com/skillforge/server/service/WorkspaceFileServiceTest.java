package com.skillforge.server.service;

import com.skillforge.server.config.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceFileServiceTest {

    @TempDir
    Path root;

    @Test
    void list_root_returnsSortedRelativeEntriesAndOmitsDeniedContent() throws Exception {
        Files.createDirectories(root.resolve("zeta"));
        Files.createDirectories(root.resolve("alpha"));
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("README.md"), "hello");
        Files.writeString(root.resolve(".env"), "SECRET=value");
        Files.writeString(root.resolve(".env.example"), "SAFE=value");
        WorkspaceFileService service = service(root, 1024, 20);

        WorkspaceFileService.DirectoryListing listing = service.list("");

        assertThat(listing.path()).isEmpty();
        assertThat(listing.parentPath()).isNull();
        assertThat(listing.rootLabel()).doesNotContain(root.toAbsolutePath().toString());
        assertThat(listing.entries()).extracting(WorkspaceFileService.Entry::name)
                .containsExactly("alpha", "zeta", ".env.example", "README.md");
        assertThat(listing.entries()).allSatisfy(entry -> {
            assertThat(entry.path()).doesNotStartWith("/");
            assertThat(entry.path()).doesNotContain(root.toAbsolutePath().toString());
        });
    }

    @Test
    void list_limit_isBoundedAndReportsTruncation() throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        Files.writeString(root.resolve("c.txt"), "c");
        WorkspaceFileService service = service(root, 1024, 2);

        WorkspaceFileService.DirectoryListing listing = service.list("");

        assertThat(listing.entries()).hasSize(2);
        assertThat(listing.truncated()).isTrue();
    }

    @Test
    void pathValidation_rejectsTraversalAbsoluteAndWindowsAbsolutePaths() {
        WorkspaceFileService service = service(root, 1024, 20);

        assertInvalid(() -> service.list("../outside"));
        assertInvalid(() -> service.list("nested/../../outside"));
        assertInvalid(() -> service.list(root.toAbsolutePath().toString()));
        assertInvalid(() -> service.list("C:\\secrets"));
        assertInvalid(() -> service.list("nested//child"));
    }

    @Test
    void symlinks_areOmittedFromListingsAndCannotBePreviewed() throws Exception {
        Path outside = Files.createTempFile("workspace-outside", ".txt");
        Path outsideDirectory = Files.createTempDirectory("workspace-outside-dir");
        Files.writeString(outside, "outside-secret");
        Files.createSymbolicLink(root.resolve("linked.txt"), outside);
        Files.createSymbolicLink(root.resolve("linked-directory"), outsideDirectory);
        WorkspaceFileService service = service(root, 1024, 20);

        assertThat(service.list("").entries()).isEmpty();
        assertThatThrownBy(() -> service.list("linked-directory"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
        assertThatThrownBy(() -> service.content("linked.txt"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
    }

    @Test
    void content_returnsBoundedUtf8AndNeverReturnsBinaryBytes() throws Exception {
        Files.writeString(root.resolve("notes.txt"), "你好 workspace", StandardCharsets.UTF_8);
        Files.write(root.resolve("binary.txt"), new byte[]{1, 0, 2, 3});
        WorkspaceFileService service = service(root, 8, 20);

        WorkspaceFileService.FileContent text = service.content("notes.txt");
        WorkspaceFileService.FileContent binary = service.content("binary.txt");

        assertThat(text.content()).isEqualTo("你好");
        assertThat(text.truncated()).isTrue();
        assertThat(text.binary()).isFalse();
        assertThat(binary.content()).isNull();
        assertThat(binary.binary()).isTrue();
    }

    @Test
    void content_rejectsUnsupportedExtensionsEvenWhenBytesAreValidUtf8() throws Exception {
        Files.writeString(root.resolve("private.png"), "valid utf-8 must not be returned");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThatThrownBy(() -> service.content("private.png"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
    }

    @Test
    void content_completeInvalidUtf8Tail_isBinaryWithoutDroppingInvalidBytes() throws Exception {
        Files.write(root.resolve("invalid.txt"), new byte[]{'o', 'k', ' ', (byte) 0xFF});
        WorkspaceFileService service = service(root, 1024, 20);

        WorkspaceFileService.FileContent content = service.content("invalid.txt");

        assertThat(content.truncated()).isFalse();
        assertThat(content.binary()).isTrue();
        assertThat(content.content()).isNull();
    }

    @Test
    void content_truncatedUtf8Boundary_dropsOnlyIncompleteMultibyteTail() throws Exception {
        Files.writeString(root.resolve("boundary.txt"), "你好", StandardCharsets.UTF_8);
        WorkspaceFileService service = service(root, 4, 20);

        WorkspaceFileService.FileContent content = service.content("boundary.txt");

        assertThat(content.truncated()).isTrue();
        assertThat(content.binary()).isFalse();
        assertThat(content.content()).isEqualTo("你");
    }

    @Test
    void content_truncatedMalformedUtf8Tail_isBinaryInsteadOfDeletingInvalidByte() throws Exception {
        Files.write(root.resolve("truncated-invalid.txt"),
                new byte[]{'o', 'k', ' ', (byte) 0xFF, 'x'});
        WorkspaceFileService service = service(root, 4, 20);

        WorkspaceFileService.FileContent content = service.content("truncated-invalid.txt");

        assertThat(content.truncated()).isTrue();
        assertThat(content.binary()).isTrue();
        assertThat(content.content()).isNull();
    }

    @Test
    void blankRoot_isUnavailableAndDoesNotFallBackToWorkingDirectory() {
        WorkspaceProperties properties = new WorkspaceProperties();
        WorkspaceFileService service = new WorkspaceFileService(properties);

        assertThatThrownBy(() -> service.list(""))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.UNAVAILABLE));
    }

    @Test
    void deniedSensitiveFiles_areNotAddressableEvenWhenTheyExist() throws Exception {
        Files.writeString(root.resolve("credentials.json"), "secret");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThatThrownBy(() -> service.content("credentials.json"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
    }

    @Test
    void gitIgnoredFiles_areHiddenWhileTrackedClaudeAndCodexFilesRemainVisible() throws Exception {
        runGit("init", "--quiet");
        Files.createDirectories(root.resolve(".claude"));
        Files.createDirectories(root.resolve(".codex"));
        Files.writeString(root.resolve(".gitignore"),
                ".claude/settings.local.json\n.codex/*\n");
        Files.writeString(root.resolve(".claude/README.md"), "tracked claude rules");
        Files.writeString(root.resolve(".claude/settings.local.json"), "local secret");
        Files.writeString(root.resolve(".codex/rules.md"), "tracked codex rules");
        runGit("add", ".gitignore", ".claude/README.md");
        runGit("add", "--force", ".codex/rules.md");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThat(service.list(".claude").entries())
                .extracting(WorkspaceFileService.Entry::name)
                .containsExactly("README.md");
        assertThat(service.list(".codex").entries())
                .extracting(WorkspaceFileService.Entry::name)
                .containsExactly("rules.md");
        assertThat(service.content(".codex/rules.md").content()).isEqualTo("tracked codex rules");
        assertThatThrownBy(() -> service.content(".claude/settings.local.json"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
    }

    @Test
    void parentWorkspace_appliesIgnoreRulesFromEachNestedRepository() throws Exception {
        Path project = Files.createDirectories(root.resolve("project-a"));
        Files.createDirectories(root.resolve("plain-folder"));
        runGit(project, "init", "--quiet");
        Files.writeString(project.resolve(".gitignore"), "local-only.txt\nignored-dir/\n");
        Files.writeString(project.resolve("README.md"), "tracked");
        Files.writeString(project.resolve("local-only.txt"), "ignored");
        Files.createDirectories(project.resolve("ignored-dir"));
        Files.writeString(project.resolve("ignored-dir/secret.txt"), "ignored");
        runGit(project, "add", ".gitignore", "README.md");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThat(service.list("").entries())
                .extracting(WorkspaceFileService.Entry::name)
                .containsExactly("plain-folder", "project-a");
        assertThat(service.list("project-a").entries())
                .extracting(WorkspaceFileService.Entry::name)
                .containsExactly(".gitignore", "README.md");
        assertThatThrownBy(() -> service.content("project-a/local-only.txt"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
    }

    @Test
    void parentWorkspace_doesNotInheritIgnoreRulesFromRepositoryOutsideAuthorizedRoot()
            throws Exception {
        runGit("init", "--quiet");
        Files.writeString(root.resolve(".gitignore"), "authorized/visible.txt\n");
        Path authorizedRoot = Files.createDirectories(root.resolve("authorized"));
        Files.writeString(authorizedRoot.resolve("visible.txt"), "visible inside authorized root");
        WorkspaceFileService service = service(authorizedRoot, 1024, 20);

        assertThat(service.list("").entries())
                .extracting(WorkspaceFileService.Entry::name)
                .containsExactly("visible.txt");
        assertThat(service.content("visible.txt").content())
                .isEqualTo("visible inside authorized root");
    }

    @Test
    void missingNestedPath_isNotFoundInsteadOfWorkspaceUnavailable() {
        WorkspaceFileService service = service(root, 1024, 20);

        assertThatThrownBy(() -> service.list("missing/path"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.NOT_FOUND));
    }

    @Test
    void nestedRepositoryWithExternalGitDirectory_failsClosed() throws Exception {
        Path externalRepository = Files.createTempDirectory("workspace-external-git");
        runGit(externalRepository, "init", "--quiet");
        Path project = Files.createDirectories(root.resolve("linked-worktree"));
        Files.writeString(project.resolve(".git"),
                "gitdir: " + externalRepository.resolve(".git") + "\n");
        Files.writeString(project.resolve("README.md"), "must not be exposed");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThatThrownBy(() -> service.list("linked-worktree"))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.UNAVAILABLE));
    }

    @Test
    void nestedRepository_usesNearestRepositoryIgnoreRules() throws Exception {
        Path outer = Files.createDirectories(root.resolve("outer"));
        runGit(outer, "init", "--quiet");
        Files.writeString(outer.resolve(".gitignore"), "nested/outer-only.txt\n");
        Path nested = Files.createDirectories(outer.resolve("nested"));
        runGit(nested, "init", "--quiet");
        Files.writeString(nested.resolve(".gitignore"), "inner-only.txt\n");
        Files.writeString(nested.resolve("outer-only.txt"), "visible in nearest repository");
        Files.writeString(nested.resolve("inner-only.txt"), "ignored by nearest repository");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThat(service.list("outer/nested").entries())
                .extracting(WorkspaceFileService.Entry::name)
                .containsExactly(".gitignore", "outer-only.txt");
    }

    @Test
    void rootIdentityChange_failsClosed() throws Exception {
        Files.writeString(root.resolve("before.txt"), "before");
        WorkspaceFileService service = service(root, 1024, 20);
        Path oldRoot = root.resolveSibling(root.getFileName() + "-old");
        Files.move(root, oldRoot);
        Files.createDirectory(root);
        Files.writeString(root.resolve("replacement.txt"), "replacement");

        assertThatThrownBy(() -> service.list(""))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.UNAVAILABLE));
    }

    @Test
    void brokenGitPolicy_failsClosedInsteadOfTreatingRootAsNonGit() throws Exception {
        Files.writeString(root.resolve(".git"), "gitdir: missing-directory\n");
        Files.writeString(root.resolve("visible.txt"), "must not be returned without policy");
        WorkspaceFileService service = service(root, 1024, 20);

        assertThatThrownBy(() -> service.list(""))
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.UNAVAILABLE));
    }

    private static WorkspaceFileService service(Path root, int maxPreviewBytes, int maxEntries) {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setRoot(root.toString());
        properties.setMaxPreviewBytes(maxPreviewBytes);
        properties.setMaxEntriesPerDirectory(maxEntries);
        return new WorkspaceFileService(properties);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(WorkspaceFileService.WorkspaceFileException.class,
                        error -> assertThat(error.kind()).isEqualTo(
                                WorkspaceFileService.ErrorKind.INVALID_PATH));
    }

    private void runGit(String... arguments) throws Exception {
        runGit(root, arguments);
    }

    private void runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git output: %s", output).isZero();
    }
}
