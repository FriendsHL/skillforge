package com.skillforge.server.hook.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends a JSON line to a local file. Useful for audit trails and debug logging.
 *
 * <p>Security: the resolved path must be under the configured base directory (default:
 * {@code ${java.io.tmpdir}/skillforge-hooks/}). Path traversal via {@code ..} is rejected.
 */
@Component
public class LogToFileMethod implements BuiltInMethod {

    private static final Logger log = LoggerFactory.getLogger(LogToFileMethod.class);

    private static final ConcurrentHashMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final Path baseDir;

    public LogToFileMethod(ObjectMapper objectMapper,
                           @Value("${lifecycle.hooks.method.log-base-dir:}") String configBaseDir) {
        this.objectMapper = objectMapper;
        if (configBaseDir != null && !configBaseDir.isBlank()) {
            this.baseDir = Path.of(configBaseDir).toAbsolutePath().normalize();
        } else {
            this.baseDir = Path.of(System.getProperty("java.io.tmpdir"), "skillforge-hooks")
                    .toAbsolutePath().normalize();
        }
    }

    @Override
    public String ref() {
        return "builtin.log.file";
    }

    @Override
    public String displayName() {
        return "Log to File";
    }

    @Override
    public String description() {
        return "Appends a JSON line to a local file. Useful for audit trails and hook debugging.";
    }

    @Override
    public Map<String, String> argsSchema() {
        return Map.of(
                "path", "String (required) — relative file path under the hook log directory",
                "format", "String (optional, default: json) — output format"
        );
    }

    @Override
    public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();

        Object pathArg = args.get("path");
        if (pathArg == null || pathArg.toString().isBlank()) {
            return HookRunResult.failure("path is required", elapsed(t0));
        }
        String relativePath = pathArg.toString().strip();

        // Reject path traversal
        if (relativePath.contains("..")) {
            return HookRunResult.failure("path traversal rejected: " + relativePath, elapsed(t0));
        }

        Path resolved = baseDir.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(baseDir)) {
            return HookRunResult.failure("path escapes base directory: " + relativePath, elapsed(t0));
        }

        try {
            Files.createDirectories(resolved.getParent());
        } catch (IOException e) {
            log.warn("[LogToFile] failed to create parent dirs for {}: {}", resolved, e.toString());
            return HookRunResult.failure("cannot create directory: " + e.getMessage(), elapsed(t0));
        }

        // Build log line
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("timestamp", Instant.now().toString());
        logEntry.put("event", ctx.event() != null ? ctx.event().wireName() : null);
        logEntry.put("sessionId", ctx.sessionId());
        logEntry.put("args", args);

        ReentrantLock lock = FILE_LOCKS.computeIfAbsent(resolved, k -> new ReentrantLock());
        lock.lock();
        try {
            String line = objectMapper.writeValueAsString(logEntry) + "\n";
            Files.writeString(resolved, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[LogToFile] write failed for {}: {}", resolved, e.toString());
            return HookRunResult.failure("write failed", elapsed(t0));
        } finally {
            lock.unlock();
        }

        return HookRunResult.ok("logged to " + resolved, elapsed(t0));
    }

    private static long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
