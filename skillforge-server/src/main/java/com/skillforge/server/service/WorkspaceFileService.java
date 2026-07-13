package com.skillforge.server.service;

import com.skillforge.server.config.WorkspaceProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WorkspaceFileService {

    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_PREVIEW_BYTES = 1_048_576;
    private static final int MAX_ENTRIES = 5_000;
    private static final int MAX_SCANNED_ENTRIES = 20_000;
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(^|[._-])(credential|credentials|secret|secrets|token|tokens|password|passwd)([._-]|$)");
    private static final Set<String> DENIED_NAMES = Set.of(
            ".git", "node_modules", "target", "dist", "build", "logs", "data", ".codeforge",
            ".ssh", ".aws", ".gnupg", ".kube", ".npmrc", ".netrc", ".pypirc");
    private static final Set<String> PRIVATE_KEY_NAMES = Set.of(
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");
    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");
    private static final Set<String> PREVIEWABLE_SUFFIXES = Set.of(
            ".txt", ".md", ".markdown", ".java", ".kt", ".kts", ".swift", ".m", ".mm",
            ".h", ".c", ".cc", ".cpp", ".cs", ".go", ".rs", ".py", ".rb", ".php",
            ".js", ".jsx", ".ts", ".tsx", ".css", ".scss", ".less", ".html", ".xml",
            ".json", ".jsonl", ".yaml", ".yml", ".toml", ".properties", ".gradle", ".sql",
            ".sh", ".zsh", ".fish", ".dockerfile", ".gitignore", ".gitattributes");

    private final RootState rootState;
    private final int maxPreviewBytes;
    private final int maxEntriesPerDirectory;

    public WorkspaceFileService(WorkspaceProperties properties) {
        this.maxPreviewBytes = clamp(properties.getMaxPreviewBytes(), 1, MAX_PREVIEW_BYTES);
        this.maxEntriesPerDirectory = clamp(properties.getMaxEntriesPerDirectory(), 1, MAX_ENTRIES);
        this.rootState = initializeRoot(properties.getRoot());
    }

    public DirectoryListing list(String rawPath) {
        RelativePath relativePath = parsePath(rawPath, true);
        RootState root = requireRoot();
        ensureNotGitIgnored(root, relativePath.value());

        if (!root.secureDirectoryStreams()) {
            return listWithIdentityVerification(root, relativePath);
        }

        try (SecurePathContext context = SecurePathContext.open(root)) {
            SecureDirectoryStream<Path> directory = context.openDirectory(relativePath.components());
            List<RawEntry> candidates = new ArrayList<>();
            boolean scanTruncated = false;
            int scanned = 0;
            for (Path child : directory) {
                if (++scanned > MAX_SCANNED_ENTRIES) {
                    scanTruncated = true;
                    break;
                }
                String name = child.getFileName().toString();
                if (isDeniedName(name)) {
                    continue;
                }
                BasicFileAttributes attributes = readAttributes(directory, child.getFileName());
                if (attributes == null || attributes.isSymbolicLink()
                        || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                    continue;
                }
                String childPath = relativePath.child(name);
                candidates.add(new RawEntry(name, childPath, attributes));
            }
            return buildListing(root, relativePath, candidates, scanTruncated);
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkspaceFileException(ErrorKind.IO_ERROR, "Workspace listing failed", exception);
        }
    }

    public FileContent content(String rawPath) {
        RelativePath relativePath = parsePath(rawPath, false);
        RootState root = requireRoot();
        List<String> parentComponents = relativePath.components()
                .subList(0, relativePath.components().size() - 1);
        String fileName = relativePath.components().get(relativePath.components().size() - 1);
        if (!isPreviewable(fileName)) {
            throw notFound();
        }
        ensureNotGitIgnored(root, relativePath.value());

        if (!root.secureDirectoryStreams()) {
            return contentWithIdentityVerification(root, relativePath);
        }

        try (SecurePathContext context = SecurePathContext.open(root)) {
            SecureDirectoryStream<Path> parent = context.openDirectory(parentComponents);
            Path child = Path.of(fileName);
            BasicFileAttributes before = requireRegularFile(parent, child);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = parent.newByteChannel(child, options)) {
                BasicFileAttributes after = requireRegularFile(parent, child);
                if (!sameIdentity(before, after)) {
                    throw notFound();
                }
                Preview preview = readPreview(channel, before.size());
                return new FileContent(fileName, relativePath.value(), before.size(),
                        before.lastModifiedTime().toInstant(), preview.content(), preview.truncated(),
                        preview.binary());
            } catch (NoSuchFileException | AccessDeniedException exception) {
                throw notFound();
            }
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkspaceFileException(ErrorKind.IO_ERROR, "Workspace preview failed", exception);
        }
    }

    private RootState initializeRoot(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return null;
        }
        try {
            Path configured = Path.of(configuredRoot.trim());
            Path canonical = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attributes = Files.readAttributes(
                    canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null
                    || canonical.getParent() == null || canonical.getFileName() == null) {
                return null;
            }
            WorkspaceGitIgnorePolicy gitIgnorePolicy = WorkspaceGitIgnorePolicy.initialize(canonical);
            boolean secureDirectoryStreams = supportsSecureDirectoryStreams(canonical);
            String label = canonical.getFileName().toString();
            return new RootState(canonical, attributes.fileKey(), label, gitIgnorePolicy,
                    secureDirectoryStreams);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private RootState requireRoot() {
        if (rootState == null) {
            throw new WorkspaceFileException(ErrorKind.UNAVAILABLE, "Workspace root is unavailable");
        }
        return rootState;
    }

    private RelativePath parsePath(String rawPath, boolean allowRoot) {
        String value = rawPath == null ? "" : rawPath;
        if (value.isEmpty() && allowRoot) {
            return new RelativePath("", List.of());
        }
        if (value.isBlank() || value.length() > MAX_PATH_LENGTH || value.indexOf('\0') >= 0
                || value.startsWith("/") || value.endsWith("/") || value.contains("\\")
                || WINDOWS_ABSOLUTE.matcher(value).matches()) {
            throw invalidPath();
        }
        List<String> components = Arrays.asList(value.split("/", -1));
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw invalidPath();
            }
            if (isDeniedName(component)) {
                throw notFound();
            }
        }
        return new RelativePath(String.join("/", components), List.copyOf(components));
    }

    private Entry toEntry(RawEntry candidate) {
        BasicFileAttributes attributes = candidate.attributes();
        EntryType type = attributes.isDirectory() ? EntryType.DIRECTORY : EntryType.FILE;
        Long size = type == EntryType.FILE ? attributes.size() : null;
        return new Entry(candidate.name(), candidate.path(), type, size,
                attributes.lastModifiedTime().toInstant(),
                type == EntryType.FILE && isPreviewable(candidate.name()));
    }

    private DirectoryListing listWithIdentityVerification(RootState root, RelativePath relativePath) {
        // Use descriptor-relative traversal when the provider supports SecureDirectoryStream.
        // macOS JDK 17 falls back to identity-before/after verification and rejects every observed
        // replacement. A malicious local ABA writer is outside the authenticated remote-client
        // threat boundary; this fallback is deliberately not described as descriptor-safe.
        Path target = resolveRelative(root.path(), relativePath.components());
        try {
            List<PathIdentity> before = capturePathIdentities(root, relativePath.components(), true);
            List<RawEntry> candidates = new ArrayList<>();
            boolean scanTruncated = false;
            int scanned = 0;
            try (DirectoryStream<Path> directory = Files.newDirectoryStream(target)) {
                requireSameIdentities(before,
                        capturePathIdentities(root, relativePath.components(), true));
                for (Path child : directory) {
                    if (++scanned > MAX_SCANNED_ENTRIES) {
                        scanTruncated = true;
                        break;
                    }
                    String name = child.getFileName().toString();
                    if (isDeniedName(name)) {
                        continue;
                    }
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(
                                child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException | AccessDeniedException exception) {
                        continue;
                    }
                    if (attributes.isSymbolicLink()
                            || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                        continue;
                    }
                    candidates.add(new RawEntry(name, relativePath.child(name), attributes));
                }
            }
            requireSameIdentities(before, capturePathIdentities(root, relativePath.components(), true));
            DirectoryListing listing = buildListing(root, relativePath, candidates, scanTruncated);
            requireSameIdentities(before, capturePathIdentities(root, relativePath.components(), true));
            return listing;
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkspaceFileException(ErrorKind.IO_ERROR, "Workspace listing failed", exception);
        }
    }

    private FileContent contentWithIdentityVerification(RootState root, RelativePath relativePath) {
        Path target = resolveRelative(root.path(), relativePath.components());
        try {
            List<PathIdentity> before = capturePathIdentities(root, relativePath.components(), false);
            PathIdentity fileIdentity = before.get(before.size() - 1);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(target, options)) {
                requireSameIdentities(before,
                        capturePathIdentities(root, relativePath.components(), false));
                Preview preview = readPreview(channel, fileIdentity.size());
                requireSameIdentities(before,
                        capturePathIdentities(root, relativePath.components(), false));
                String fileName = relativePath.components().get(relativePath.components().size() - 1);
                return new FileContent(fileName, relativePath.value(), fileIdentity.size(),
                        fileIdentity.modifiedAt(), preview.content(), preview.truncated(), preview.binary());
            } catch (NoSuchFileException | AccessDeniedException exception) {
                throw notFound();
            }
        } catch (WorkspaceFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorkspaceFileException(ErrorKind.IO_ERROR, "Workspace preview failed", exception);
        }
    }

    private DirectoryListing buildListing(RootState root, RelativePath relativePath,
                                           List<RawEntry> candidates, boolean scanTruncated) {
        Set<String> ignored = gitIgnored(root, candidates.stream().map(RawEntry::path).toList());
        List<Entry> visible = candidates.stream()
                .filter(candidate -> !ignored.contains(candidate.path()))
                .map(this::toEntry)
                .sorted(Comparator.comparing((Entry entry) -> entry.type() != EntryType.DIRECTORY)
                        .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Entry::name))
                .toList();
        boolean truncated = scanTruncated || visible.size() > maxEntriesPerDirectory;
        List<Entry> bounded = visible.size() > maxEntriesPerDirectory
                ? visible.subList(0, maxEntriesPerDirectory)
                : visible;
        return new DirectoryListing(root.label(), relativePath.value(), relativePath.parent(),
                List.copyOf(bounded), truncated);
    }

    private static List<PathIdentity> capturePathIdentities(
            RootState root, List<String> components, boolean finalDirectory) throws IOException {
        List<PathIdentity> identities = new ArrayList<>();
        Path current = root.path();
        for (int index = -1; index < components.size(); index++) {
            if (index >= 0) {
                current = current.resolve(components.get(index));
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean finalComponent = index == components.size() - 1;
            boolean expectedDirectory = !finalComponent || finalDirectory;
            if (attributes.isSymbolicLink() || attributes.fileKey() == null
                    || (expectedDirectory && !attributes.isDirectory())
                    || (!expectedDirectory && !attributes.isRegularFile())) {
                throw notFound();
            }
            if (index == -1 && !Objects.equals(root.fileKey(), attributes.fileKey())) {
                throw new WorkspaceFileException(ErrorKind.UNAVAILABLE,
                        "Workspace root identity changed");
            }
            identities.add(new PathIdentity(attributes.fileKey(), attributes.isDirectory(),
                    attributes.size(), attributes.lastModifiedTime().toInstant()));
        }
        return identities;
    }

    private static void requireSameIdentities(List<PathIdentity> expected, List<PathIdentity> actual) {
        if (!expected.equals(actual)) {
            throw new WorkspaceFileException(ErrorKind.UNAVAILABLE,
                    "Workspace path identity changed during access");
        }
    }

    private static Path resolveRelative(Path root, List<String> components) {
        Path current = root;
        for (String component : components) {
            current = current.resolve(component);
        }
        return current;
    }

    private static boolean supportsSecureDirectoryStreams(Path root) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            return stream instanceof SecureDirectoryStream<?>;
        }
    }

    private Preview readPreview(SeekableByteChannel channel, long reportedSize) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(maxPreviewBytes + 1);
        boolean eof = false;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                eof = true;
                break;
            }
            if (count == 0) {
                break;
            }
        }
        byte[] read = Arrays.copyOf(buffer.array(), buffer.position());
        int length = Math.min(read.length, maxPreviewBytes);
        boolean truncated = read.length > maxPreviewBytes || (!eof && reportedSize > maxPreviewBytes)
                || reportedSize > maxPreviewBytes;
        byte[] bounded = Arrays.copyOf(read, length);
        for (byte value : bounded) {
            if (value == 0) {
                return new Preview(null, truncated, true);
            }
        }

        String decoded = decodeUtf8(bounded, truncated);
        if (decoded == null) {
            return new Preview(null, truncated, true);
        }
        if (truncated) {
            decoded = removePartialTrailingWord(decoded);
        }
        return new Preview(decoded, truncated, false);
    }

    private String decodeUtf8(byte[] bytes, boolean truncated) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(Math.max(1, bytes.length));
        CoderResult result = decoder.decode(input, output, !truncated);
        if (!result.isUnderflow()) {
            return null;
        }
        if (truncated) {
            if (input.hasRemaining() && !isIncompleteUtf8Prefix(input)) {
                return null;
            }
        } else {
            if (input.hasRemaining() || !decoder.flush(output).isUnderflow()) {
                return null;
            }
        }
        output.flip();
        return output.toString();
    }

    private boolean isIncompleteUtf8Prefix(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining < 1 || remaining > 3) {
            return false;
        }
        int position = input.position();
        int first = input.get(position) & 0xFF;
        int expectedLength;
        if (first >= 0xC2 && first <= 0xDF) {
            expectedLength = 2;
        } else if (first >= 0xE0 && first <= 0xEF) {
            expectedLength = 3;
        } else if (first >= 0xF0 && first <= 0xF4) {
            expectedLength = 4;
        } else {
            return false;
        }
        if (remaining >= expectedLength) {
            return false;
        }
        for (int index = 1; index < remaining; index++) {
            int continuation = input.get(position + index) & 0xFF;
            if (continuation < 0x80 || continuation > 0xBF) {
                return false;
            }
        }
        if (remaining >= 2) {
            int second = input.get(position + 1) & 0xFF;
            if ((first == 0xE0 && second < 0xA0)
                    || (first == 0xED && second > 0x9F)
                    || (first == 0xF0 && second < 0x90)
                    || (first == 0xF4 && second > 0x8F)) {
                return false;
            }
        }
        return true;
    }

    private String removePartialTrailingWord(String value) {
        if (value.isEmpty() || Character.isWhitespace(value.charAt(value.length() - 1))) {
            return value.stripTrailing();
        }
        for (int index = value.length() - 1; index >= 0; index--) {
            if (Character.isWhitespace(value.charAt(index))) {
                return value.substring(0, index).stripTrailing();
            }
        }
        return value;
    }

    private void ensureNotGitIgnored(RootState root, String relativePath) {
        if (!relativePath.isEmpty() && gitIgnored(root, List.of(relativePath)).contains(relativePath)) {
            throw notFound();
        }
    }

    private Set<String> gitIgnored(RootState root, List<String> relativePaths) {
        try {
            return root.gitIgnorePolicy().ignored(relativePaths);
        } catch (WorkspaceGitIgnorePolicy.PolicyException exception) {
            if (exception.failure() == WorkspaceGitIgnorePolicy.Failure.NOT_FOUND) {
                throw notFound();
            }
            if (exception.failure() == WorkspaceGitIgnorePolicy.Failure.MALFORMED_OUTPUT) {
                throw new WorkspaceFileException(ErrorKind.UNAVAILABLE,
                        "Workspace ignore policy returned malformed output", exception);
            }
            throw new WorkspaceFileException(ErrorKind.UNAVAILABLE,
                    "Workspace ignore policy is unavailable", exception);
        }
    }

    private static BasicFileAttributes readAttributes(SecureDirectoryStream<Path> directory, Path child)
            throws IOException {
        try {
            BasicFileAttributeView view = directory.getFileAttributeView(
                    child, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view == null ? null : view.readAttributes();
        } catch (NoSuchFileException | AccessDeniedException exception) {
            return null;
        }
    }

    private static BasicFileAttributes requireRegularFile(SecureDirectoryStream<Path> directory, Path child)
            throws IOException {
        BasicFileAttributes attributes = readAttributes(directory, child);
        if (attributes == null || !attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.fileKey() == null) {
            throw notFound();
        }
        return attributes;
    }

    private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return first.fileKey() != null && Objects.equals(first.fileKey(), second.fileKey())
                && first.isDirectory() == second.isDirectory()
                && first.isRegularFile() == second.isRegularFile();
    }

    private static boolean isDeniedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (DENIED_NAMES.contains(lower) || PRIVATE_KEY_NAMES.contains(lower)
                || SENSITIVE_NAME.matcher(lower).find()) {
            return true;
        }
        if (lower.equals(".env") || (lower.startsWith(".env.") && !lower.equals(".env.example"))) {
            return true;
        }
        return SENSITIVE_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static boolean isPreviewable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("dockerfile") || lower.equals("makefile") || lower.equals("license")
                || lower.equals("readme")) {
            return true;
        }
        return PREVIEWABLE_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static WorkspaceFileException invalidPath() {
        return new WorkspaceFileException(ErrorKind.INVALID_PATH, "Invalid workspace path");
    }

    private static WorkspaceFileException notFound() {
        return new WorkspaceFileException(ErrorKind.NOT_FOUND, "Workspace entry not found");
    }

    public enum EntryType {
        DIRECTORY,
        FILE
    }

    public enum ErrorKind {
        INVALID_PATH,
        NOT_FOUND,
        UNAVAILABLE,
        IO_ERROR
    }

    public record Entry(String name, String path, EntryType type, Long sizeBytes, Instant modifiedAt,
                        boolean previewable) {
    }

    public record DirectoryListing(String rootLabel, String path, String parentPath, List<Entry> entries,
                                   boolean truncated) {
    }

    public record FileContent(String name, String path, long sizeBytes, Instant modifiedAt, String content,
                              boolean truncated, boolean binary) {
    }

    public static class WorkspaceFileException extends RuntimeException {
        private final ErrorKind kind;

        public WorkspaceFileException(ErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public WorkspaceFileException(ErrorKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public ErrorKind kind() {
            return kind;
        }
    }

    private record RootState(Path path, Object fileKey, String label,
                             WorkspaceGitIgnorePolicy gitIgnorePolicy,
                             boolean secureDirectoryStreams) {
    }

    private record RelativePath(String value, List<String> components) {
        String child(String name) {
            return value.isEmpty() ? name : value + "/" + name;
        }

        String parent() {
            if (value.isEmpty()) {
                return null;
            }
            int separator = value.lastIndexOf('/');
            return separator < 0 ? "" : value.substring(0, separator);
        }
    }

    private record RawEntry(String name, String path, BasicFileAttributes attributes) {
    }

    private record Preview(String content, boolean truncated, boolean binary) {
    }

    private record PathIdentity(Object fileKey, boolean directory, long size, Instant modifiedAt) {
    }

    private static final class SecurePathContext implements AutoCloseable {
        private final List<SecureDirectoryStream<Path>> streams = new ArrayList<>();
        private final SecureDirectoryStream<Path> root;

        private SecurePathContext(SecureDirectoryStream<Path> root) {
            this.root = root;
            streams.add(root);
        }

        static SecurePathContext open(RootState rootState) throws IOException {
            revalidateRoot(rootState);
            Path parentPath = rootState.path().getParent();
            Path rootName = rootState.path().getFileName();
            DirectoryStream<Path> rawParent = Files.newDirectoryStream(parentPath);
            SecureDirectoryStream<Path> parent = asSecure(rawParent);
            SecureDirectoryStream<Path> openedRoot = null;
            try {
                BasicFileAttributes before = requireDirectory(parent, rootName);
                if (!Objects.equals(rootState.fileKey(), before.fileKey())) {
                    throw unavailable();
                }
                openedRoot = parent.newDirectoryStream(
                        rootName, LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes after = requireDirectory(parent, rootName);
                if (!sameIdentity(before, after) || !Objects.equals(rootState.fileKey(), after.fileKey())) {
                    throw unavailable();
                }
                revalidateRoot(rootState);
                parent.close();
                SecurePathContext context = new SecurePathContext(openedRoot);
                openedRoot = null;
                return context;
            } finally {
                if (openedRoot != null) {
                    openedRoot.close();
                }
                try {
                    parent.close();
                } catch (IOException ignored) {
                    // The successful path already closed the parent before returning the root handle.
                }
            }
        }

        SecureDirectoryStream<Path> openDirectory(List<String> components) throws IOException {
            SecureDirectoryStream<Path> current = root;
            for (String component : components) {
                Path child = Path.of(component);
                BasicFileAttributes before = requireDirectory(current, child);
                SecureDirectoryStream<Path> next;
                try {
                    next = current.newDirectoryStream(child, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException | AccessDeniedException exception) {
                    throw notFound();
                }
                BasicFileAttributes after = requireDirectory(current, child);
                if (!sameIdentity(before, after)) {
                    next.close();
                    throw notFound();
                }
                streams.add(next);
                current = next;
            }
            return current;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = streams.size() - 1; index >= 0; index--) {
                try {
                    streams.get(index).close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static void revalidateRoot(RootState rootState) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        rootState.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink()
                        || !Objects.equals(rootState.fileKey(), attributes.fileKey())) {
                    throw unavailable();
                }
            } catch (IOException exception) {
                throw unavailable();
            }
        }

        private static BasicFileAttributes requireDirectory(
                SecureDirectoryStream<Path> directory, Path child) throws IOException {
            BasicFileAttributes attributes = readAttributes(directory, child);
            if (attributes == null || !attributes.isDirectory() || attributes.isSymbolicLink()
                    || attributes.fileKey() == null) {
                throw notFound();
            }
            return attributes;
        }

        @SuppressWarnings("unchecked")
        private static SecureDirectoryStream<Path> asSecure(DirectoryStream<Path> stream)
                throws IOException {
            if (!(stream instanceof SecureDirectoryStream<?>)) {
                stream.close();
                throw unavailable();
            }
            return (SecureDirectoryStream<Path>) stream;
        }

        private static WorkspaceFileException unavailable() {
            return new WorkspaceFileException(ErrorKind.UNAVAILABLE,
                    "Secure workspace access is unavailable");
        }
    }
}
