package com.skillforge.server.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ArtifactWorkspaceService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final Path root;
    private final Object rootFileKey;
    private final Clock clock;

    @Autowired
    public ArtifactWorkspaceService(
            @Value("${skillforge.chat.artifacts.staging-root:./data/artifact-staging}") String root) {
        this(root, Clock.systemUTC());
    }

    ArtifactWorkspaceService(String root, Clock clock) {
        Path configured = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
            this.rootFileKey = Files.readAttributes(
                    this.root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize artifact staging root", e);
        }
        this.clock = clock;
    }

    public Path create(Long userId, String sessionId, String traceId) {
        if (userId == null || userId < 0 || !safe(sessionId) || !safe(traceId)) {
            throw new IllegalArgumentException("Invalid artifact workspace identity");
        }
        Path workspace = root.resolve(userId.toString()).resolve(sessionId).resolve(traceId).normalize();
        requireContained(workspace);
        try {
            Files.createDirectories(workspace);
            return requireCanonicalWorkspace(workspace);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create artifact workspace", e);
        }
    }

    public String promptInstruction(Path workspace) {
        Path canonical = requireCanonicalWorkspace(workspace);
        return "Generated deliverables must be written under " + canonical
                + ". Publish each final file with PublishChatArtifact. Do not publish files from other directories.";
    }

    public void deleteWorkspace(Path workspace) {
        Path canonical = requireCanonicalWorkspace(workspace);
        deleteTreeSecurely(canonical);
    }

    public int cleanupExpired(int ttlHours) {
        if (!Files.isDirectory(root)) return 0;
        Instant cutoff = clock.instant().minus(Duration.ofHours(Math.max(1, ttlHours)));
        int deleted = 0;
        try (Stream<Path> users = Files.list(root)) {
            for (Path user : users.toList()) {
                if (!Files.isDirectory(user, LinkOption.NOFOLLOW_LINKS)) continue;
                try (Stream<Path> sessions = Files.list(user)) {
                    for (Path session : sessions.toList()) {
                        if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) continue;
                        try (Stream<Path> traces = Files.list(session)) {
                            for (Path trace : traces.toList()) {
                                if (Files.isDirectory(trace, LinkOption.NOFOLLOW_LINKS)
                                        && Files.getLastModifiedTime(trace, LinkOption.NOFOLLOW_LINKS)
                                        .toInstant().isBefore(cutoff)) {
                                    deleteWorkspace(trace);
                                    deleted++;
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clean artifact workspaces", e);
        }
        return deleted;
    }

    private Path requireContained(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new SecurityException("Artifact workspace escapes staging root");
        }
        return normalized;
    }

    private Path requireCanonicalWorkspace(Path workspace) {
        Path normalized = requireContained(workspace);
        Path relative = root.relativize(normalized);
        if (relative.getNameCount() != 3) {
            throw new SecurityException("Artifact workspace must identify one run");
        }
        try {
            verifyRootIdentity();
            Path current = root;
            for (Path component : relative) {
                current = current.resolve(component);
                BasicFileAttributes attrs = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                    throw new SecurityException("Artifact workspace contains a replaced component");
                }
            }
            Path real = normalized.toRealPath();
            if (!real.equals(normalized) || !real.startsWith(root)) {
                throw new SecurityException("Artifact workspace is not canonical");
            }
            return real;
        } catch (IOException e) {
            throw new SecurityException("Artifact workspace is not safely accessible", e);
        }
    }

    private void deleteTreeSecurely(Path workspace) {
        Path relative = root.relativize(workspace);
        if (relative.getNameCount() != 3) {
            throw new SecurityException("Artifact workspace must identify one run");
        }
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            verifyRootIdentity();
            DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
            opened.add(rootStream);
            if (!(rootStream instanceof SecureDirectoryStream<?>)) {
                throw new SecurityException("Secure directory traversal is unavailable");
            }
            SecureDirectoryStream<Path> current = requireSecure(rootStream);
            for (int i = 0; i < relative.getNameCount(); i++) {
                Path name = relative.getName(i);
                BasicFileAttributes attrs = attributes(current, name);
                if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                    throw new SecurityException("Artifact workspace contains a replaced component");
                }
                SecureDirectoryStream<Path> parent = current;
                DirectoryStream<Path> child = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                opened.add(child);
                current = requireSecure(child);
                if (i == relative.getNameCount() - 1) {
                    deleteContents(current);
                    child.close();
                    opened.remove(child);
                    parent.deleteDirectory(name);
                }
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            return;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete artifact workspace", e);
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) {
                try {
                    opened.get(i).close();
                } catch (IOException ignored) {
                    // Best effort after the primary operation has completed or failed.
                }
            }
        }
    }

    private void deleteContents(SecureDirectoryStream<Path> directory) throws IOException {
        for (Path entry : directory) {
            Path name = entry.getFileName();
            BasicFileAttributes attrs = attributes(directory, name);
            if (attrs.isDirectory() && !attrs.isSymbolicLink()) {
                try (DirectoryStream<Path> child = directory.newDirectoryStream(
                        name, LinkOption.NOFOLLOW_LINKS)) {
                    SecureDirectoryStream<Path> secureChild = requireSecure(child);
                    deleteContents(secureChild);
                }
                directory.deleteDirectory(name);
            } else {
                directory.deleteFile(name);
            }
        }
    }

    private void verifyRootIdentity() throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || !attrs.isDirectory()
                || !Objects.equals(rootFileKey, attrs.fileKey())) {
            throw new SecurityException("Artifact staging root was replaced");
        }
    }

    private static BasicFileAttributes attributes(
            SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("Basic file attributes are unavailable");
        return view.readAttributes();
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> requireSecure(DirectoryStream<Path> stream) {
        if (!(stream instanceof SecureDirectoryStream<?> secure)) {
            throw new SecurityException("Secure directory traversal is unavailable");
        }
        return (SecureDirectoryStream<Path>) secure;
    }

    private static boolean safe(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }
}
