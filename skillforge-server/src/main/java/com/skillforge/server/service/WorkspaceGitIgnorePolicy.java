package com.skillforge.server.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WorkspaceGitIgnorePolicy {

    private static final long GIT_TIMEOUT_SECONDS = 3;

    private final Path root;

    private WorkspaceGitIgnorePolicy(Path root) {
        this.root = root;
    }

    static WorkspaceGitIgnorePolicy initialize(Path root) throws IOException {
        WorkspaceGitIgnorePolicy policy = new WorkspaceGitIgnorePolicy(root);
        if (policy.hasGitMarker(root)) {
            policy.validateRepository(root);
        }
        return policy;
    }

    Set<String> ignored(List<String> relativePaths) throws PolicyException {
        if (relativePaths.isEmpty()) {
            return Set.of();
        }
        Map<Path, List<RepositoryPath>> pathsByRepository = new LinkedHashMap<>();
        try {
            for (String relativePath : relativePaths) {
                Path absolutePath = root.resolve(relativePath).normalize();
                if (!absolutePath.startsWith(root)) {
                    throw new PolicyException(Failure.MALFORMED_OUTPUT,
                            "Workspace path escaped its configured root");
                }
                Path repositoryRoot = findRepositoryRootWithinWorkspace(absolutePath);
                if (repositoryRoot == null) {
                    continue;
                }
                String repositoryPath = toGitPath(repositoryRoot.relativize(absolutePath));
                if (repositoryPath.isEmpty()) {
                    continue;
                }
                pathsByRepository.computeIfAbsent(repositoryRoot, ignored -> new ArrayList<>())
                        .add(new RepositoryPath(relativePath, repositoryPath));
            }
        } catch (PolicyException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PolicyException(Failure.UNAVAILABLE, "Git ignore check failed", exception);
        }

        Set<String> ignoredWorkspacePaths = new HashSet<>();
        for (Map.Entry<Path, List<RepositoryPath>> entry : pathsByRepository.entrySet()) {
            Path repositoryRoot = entry.getKey();
            List<RepositoryPath> repositoryPaths = entry.getValue();
            try {
                validateRepository(repositoryRoot);
                Set<String> ignoredRepositoryPaths = ignoredInRepository(
                        repositoryRoot,
                        repositoryPaths.stream().map(RepositoryPath::repositoryPath).toList());
                repositoryPaths.stream()
                        .filter(path -> ignoredRepositoryPaths.contains(path.repositoryPath()))
                        .map(RepositoryPath::workspacePath)
                        .forEach(ignoredWorkspacePaths::add);
            } catch (PolicyException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new PolicyException(Failure.UNAVAILABLE, "Git ignore check failed", exception);
            }
        }
        return Set.copyOf(ignoredWorkspacePaths);
    }

    private Set<String> ignoredInRepository(Path repositoryRoot, List<String> relativePaths)
            throws IOException, PolicyException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (String relativePath : relativePaths) {
            input.write(relativePath.getBytes(StandardCharsets.UTF_8));
            input.write(0);
        }
        ProcessResult result = runGit(
                repositoryRoot, List.of("check-ignore", "--stdin", "-z"), input.toByteArray());
        if (result.exitCode() == 1 && result.output().length == 0) {
            return Set.of();
        }
        if (result.exitCode() != 0) {
            throw new PolicyException(Failure.UNAVAILABLE, "Git ignore check failed");
        }
        Set<String> submitted = Set.copyOf(relativePaths);
        Set<String> ignored = new HashSet<>();
        int start = 0;
        for (int index = 0; index < result.output().length; index++) {
            if (result.output()[index] == 0) {
                String ignoredPath = decodeUtf8(result.output(), start, index - start);
                if (ignoredPath.isEmpty() || !submitted.contains(ignoredPath)) {
                    throw new PolicyException(Failure.MALFORMED_OUTPUT,
                            "Git ignore check returned an unknown path");
                }
                ignored.add(ignoredPath);
                start = index + 1;
            }
        }
        if (start != result.output().length || ignored.isEmpty()) {
            throw new PolicyException(Failure.MALFORMED_OUTPUT,
                    "Git ignore check returned malformed output");
        }
        return ignored;
    }

    private Path findRepositoryRootWithinWorkspace(Path path) throws IOException {
        Path current = root;
        Path repositoryRoot = hasGitMarker(root) ? root : null;
        Path relativePath = root.relativize(path);
        int componentIndex = 0;
        int componentCount = relativePath.getNameCount();
        for (Path component : relativePath) {
            componentIndex++;
            Path next = current.resolve(component);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        next, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                throw new PolicyException(Failure.NOT_FOUND, "Workspace path was not found", exception);
            }
            boolean finalComponent = componentIndex == componentCount;
            if (attributes.isSymbolicLink()
                    || (!attributes.isDirectory() && !attributes.isRegularFile())
                    || (!finalComponent && !attributes.isDirectory())) {
                throw new PolicyException(Failure.NOT_FOUND, "Workspace path was not found");
            }
            current = next;
            if (attributes.isDirectory() && hasGitMarker(current)) {
                repositoryRoot = current;
            }
        }
        return repositoryRoot;
    }

    private boolean hasGitMarker(Path directory) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory.resolve(".git"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()
                    || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                throw new PolicyException(Failure.UNAVAILABLE, "Untrusted Git repository marker");
            }
            return true;
        } catch (NoSuchFileException ignored) {
            return false;
        }
    }

    private void validateRepository(Path repositoryRoot) throws IOException {
        ProcessResult result = runGit(
                repositoryRoot,
                List.of("rev-parse", "--path-format=absolute", "--show-toplevel", "--git-dir"),
                new byte[0]);
        if (result.exitCode() != 0) {
            throw new IOException("Git workspace policy could not be initialized");
        }
        String output = decodeUtf8(result.output(), 0, result.output().length);
        String[] lines = output.split("\n", -1);
        if (lines.length != 3 || !lines[2].isEmpty()) {
            throw new IOException("Git workspace policy returned malformed repository paths");
        }
        Path canonicalWorkspaceRoot = root.toRealPath();
        Path canonicalRepositoryRoot = repositoryRoot.toRealPath();
        Path topLevel = Path.of(lines[0]).toRealPath();
        Path gitDirectory = Path.of(lines[1]).toRealPath();
        if (!topLevel.equals(canonicalRepositoryRoot)
                || !gitDirectory.startsWith(canonicalWorkspaceRoot)) {
            throw new IOException("Git workspace policy escaped the configured root");
        }
    }

    private static String toGitPath(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private String decodeUtf8(byte[] bytes, int offset, int length) throws PolicyException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new PolicyException(Failure.MALFORMED_OUTPUT,
                    "Git ignore check returned invalid UTF-8", exception);
        }
    }

    private static ProcessResult runGit(Path root, List<String> arguments, byte[] input)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        processBuilder.environment().remove("GIT_DIR");
        processBuilder.environment().remove("GIT_WORK_TREE");
        processBuilder.environment().remove("GIT_INDEX_FILE");
        processBuilder.environment().remove("GIT_CEILING_DIRECTORIES");
        Process process = processBuilder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException exception) {
                throw new ProcessOutputException(exception);
            }
        });
        CompletableFuture<Void> inputWriter = CompletableFuture.runAsync(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input);
            } catch (IOException exception) {
                throw new ProcessOutputException(exception);
            }
        });
        try {
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Git policy check timed out");
            }
            inputWriter.get(1, TimeUnit.SECONDS);
            return new ProcessResult(process.exitValue(), output.get(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Git policy check interrupted", exception);
        } catch (ExecutionException | TimeoutException | ProcessOutputException exception) {
            process.destroyForcibly();
            throw new IOException("Git policy output failed", exception);
        }
    }

    enum Failure {
        UNAVAILABLE,
        MALFORMED_OUTPUT,
        NOT_FOUND
    }

    static final class PolicyException extends IOException {
        private final Failure failure;

        private PolicyException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        private PolicyException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    private record ProcessResult(int exitCode, byte[] output) {
    }

    private record RepositoryPath(String workspacePath, String repositoryPath) {
    }

    private static final class ProcessOutputException extends RuntimeException {
        private ProcessOutputException(IOException cause) {
            super(cause);
        }
    }
}
