package com.skillforge.server.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;

/**
 * Creates and cleans up per-invocation work directories for hook scripts.
 *
 * <p>Addresses the macOS {@code /tmp → /private/tmp} symlink pitfall:
 * <ul>
 *   <li>Resolve the tmp root via {@link Path#toRealPath()} at construction so the prefix
 *       used for the "is this path under our sandbox?" check matches what the kernel sees.</li>
 *   <li>Never compare against the hard-coded string {@code "/tmp/"} — on macOS that always
 *       fails, silently breaking the sandbox check.</li>
 * </ul>
 *
 * <p>Each call to {@link #create()} yields a fresh UUID-named directory under
 * {@code <tmpRoot>/sf-hook/}. Callers are responsible for invoking {@link #cleanup(Path)}
 * in a {@code finally} block.
 */
public class ScriptWorkdirManager {

    private static final Logger log = LoggerFactory.getLogger(ScriptWorkdirManager.class);

    /** Subdirectory name under the JVM tmp root that we carve out for hook scripts. */
    static final String SANDBOX_DIR_NAME = "sf-hook";

    private final Path sandboxRoot;

    public ScriptWorkdirManager() {
        this(defaultTmpRoot());
    }

    /** Test seam — accept a specific tmp root (caller guarantees it exists & is real-pathable). */
    public ScriptWorkdirManager(Path tmpRoot) {
        try {
            Path realTmp = tmpRoot.toRealPath();
            this.sandboxRoot = realTmp.resolve(SANDBOX_DIR_NAME);
            Files.createDirectories(this.sandboxRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize hook sandbox under " + tmpRoot, e);
        }
    }

    /** The sandbox root path (e.g. {@code /private/tmp/sf-hook}). Exposed for logging and tests. */
    public Path sandboxRoot() {
        return sandboxRoot;
    }

    /**
     * Create a fresh workdir with UUID name. The directory is validated via
     * {@link Path#toRealPath()} to ensure it truly lives under {@link #sandboxRoot}.
     */
    public Path create() {
        try {
            Path workdir = sandboxRoot.resolve(UUID.randomUUID().toString());
            Files.createDirectories(workdir);
            Path real = workdir.toRealPath();
            if (!real.startsWith(sandboxRoot)) {
                cleanup(workdir);
                throw new SecurityException(
                        "Work dir escaped sandbox: real=" + real + " sandbox=" + sandboxRoot);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create script workdir", e);
        }
    }

    /**
     * Recursive, best-effort cleanup. Swallows errors (per-hook cleanup must never surface
     * to callers). Safe to call with {@code null}.
     */
    public void cleanup(Path workdir) {
        if (workdir == null) return;
        try {
            if (!Files.exists(workdir)) return;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(workdir)) {
                for (Path entry : entries) {
                    deleteRecursive(entry);
                }
            }
            try {
                Files.deleteIfExists(workdir);
            } catch (IOException ignored) {
                // tolerate transient cleanup failures
            }
        } catch (IOException e) {
            log.debug("workdir cleanup failed for {}: {}", workdir, e.toString());
        }
    }

    private static void deleteRecursive(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (NoSuchFileException ignored) {
                    // raced with concurrent cleanup; harmless
                } catch (IOException e) {
                    log.debug("deleteRecursive failed on {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.debug("walk failed on {}: {}", root, e.toString());
        }
    }

    private static Path defaultTmpRoot() {
        return Paths.get(System.getProperty("java.io.tmpdir", "/tmp"));
    }
}
