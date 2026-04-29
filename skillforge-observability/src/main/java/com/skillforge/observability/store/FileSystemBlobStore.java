package com.skillforge.observability.store;

import com.skillforge.observability.api.BlobRef;
import com.skillforge.observability.api.BlobStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Plan §5.2 / §5.4 — local filesystem 实现。
 *
 * <p>路径校验：每次 read/write 都把 {@code blobRef} resolve 到 {@code root} 下并 normalize，
 * 确保 {@code target.startsWith(root)} 防 traversal。
 */
@Service
public class FileSystemBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBlobStore.class);
    private static final long OVERSIZE_WARN_BYTES = 10L * 1024L * 1024L;

    private final Path root;

    public FileSystemBlobStore(@Value("${skillforge.observability.blob.root:./data/llm-payloads}") String rootPath) {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
        log.info("FileSystemBlobStore root: {}", root);
    }

    @Override
    public String write(BlobRef ref, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        String rel = ref.toRelativePath();
        Path target = resolveSafe(rel);
        Files.createDirectories(target.getParent());
        Files.write(target, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        if (payload.length > OVERSIZE_WARN_BYTES) {
            log.warn("obs.blob.oversize: blob_ref={} bytes={}", rel, payload.length);
        }
        return rel;
    }

    @Override
    public Optional<byte[]> read(String blobRef) throws IOException {
        Path target = resolveSafe(blobRef);
        if (!Files.exists(target)) return Optional.empty();
        return Optional.of(Files.readAllBytes(target));
    }

    @Override
    public Optional<InputStream> openStream(String blobRef) throws IOException {
        Path target = resolveSafe(blobRef);
        if (!Files.exists(target)) return Optional.empty();
        return Optional.of(Files.newInputStream(target, StandardOpenOption.READ));
    }

    @Override
    public List<String> listOlderThan(Instant cutoff, int limit) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    try { return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff); }
                    catch (IOException ioe) { return false; }
                })
                .limit(limit)
                .forEach(p -> out.add(root.relativize(p).toString()));
        } catch (IOException e) {
            log.warn("listOlderThan walk failed", e);
        }
        return out;
    }

    @Override
    public void delete(String blobRef) {
        try {
            Path target = resolveSafe(blobRef);
            Files.deleteIfExists(target);
        } catch (Exception e) {
            log.warn("blob delete failed: ref={}", blobRef, e);
        }
    }

    /** Resolve + normalize + must startsWith root. */
    Path resolveSafe(String blobRef) {
        if (blobRef == null || blobRef.isBlank()) {
            throw new IllegalArgumentException("blobRef is required");
        }
        Path target = root.resolve(blobRef).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("blobRef escapes root: " + blobRef);
        }
        return target;
    }

    Path getRoot() { return root; }
}
