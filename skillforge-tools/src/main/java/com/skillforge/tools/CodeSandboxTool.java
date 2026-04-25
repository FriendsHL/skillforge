package com.skillforge.tools;

import com.skillforge.core.engine.DangerousCommandChecker;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CodeSandbox — executes untrusted bash / node / java code in an isolated workdir.
 *
 * <p>Used by Code Agent to test scripts before registering them as hook methods.
 *
 * <p>Safety stack:
 * <ul>
 *   <li>Language allow-list (bash / node / java)</li>
 *   <li>{@link DangerousCommandChecker} pre-scan</li>
 *   <li>Isolated workdir under {@code /tmp/sf-code/<UUID>/}</li>
 *   <li>Env whitelist: {@code PATH, LANG, TERM, JAVA_HOME}; {@code HOME} set to sandbox workdir</li>
 *   <li>Output capped at 32KB</li>
 *   <li>Per-lang default timeouts; caller may override up to 300s</li>
 *   <li>Process tree kill on timeout (descendants + root)</li>
 * </ul>
 *
 * <p>Return payload: structured text {@code exitCode=N\ndurationMs=N\n---STDOUT---\n...\n---STDERR---\n...}.
 */
public class CodeSandboxTool implements Tool {

    private static final int MAX_OUTPUT_BYTES = 32_768;
    private static final int MAX_CODE_CHARS = 32_768;
    private static final int DEFAULT_TIMEOUT_NODE_SEC = 30;
    private static final int DEFAULT_TIMEOUT_BASH_SEC = 120;
    private static final int DEFAULT_TIMEOUT_JAVA_SEC = 120;
    private static final int MAX_TIMEOUT_SEC = 300;
    private static final long DESTROY_GRACE_SEC = 2L;
    private static final long READER_JOIN_MS = 2_000L;

    private static final List<String> INHERITED_ENV_KEYS = List.of("PATH", "LANG", "TERM", "JAVA_HOME");
    private static final Set<String> ALLOWED_LANGS = Set.of("bash", "node", "java");

    private static final String SANDBOX_ROOT_NAME = "sf-code";

    @Override
    public String getName() {
        return "CodeSandbox";
    }

    @Override
    public String getDescription() {
        return "Execute untrusted code (bash, node, or java) in an isolated sandbox and return its output. "
                + "Use this to test-run a script BEFORE registering it as a hook method.\n\n"
                + "- bash / node scripts run directly.\n"
                + "- java code must be a single public class; it is compiled and executed via `java SourceFile.java`.\n"
                + "- Environment is scrubbed: only PATH, LANG, TERM, JAVA_HOME are visible; HOME is set to the sandbox workdir.\n"
                + "- Output is capped at 32KB. Default timeout: node=30s, bash/java=120s. Pass timeoutSec to override (max 300).\n"
                + "- Dangerous patterns (rm -rf /, fork bombs, :(){ :|:& };:) are rejected before launch.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("lang", Map.of(
                "type", "string",
                "enum", List.of("bash", "node", "java"),
                "description", "Language of the code to execute."
        ));
        properties.put("code", Map.of(
                "type", "string",
                "description", "Source code to execute. For java, must be a single public class whose name matches the file SourceFile."
        ));
        properties.put("timeoutSec", Map.of(
                "type", "integer",
                "description", "Per-invocation timeout in seconds (capped at 300). Optional; per-lang defaults apply."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("lang", "code"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        long t0 = System.currentTimeMillis();
        String lang = asString(input.get("lang"));
        String code = asString(input.get("code"));
        Integer timeoutOverride = asInt(input.get("timeoutSec"));

        if (lang == null || lang.isBlank()) {
            return SkillResult.error("lang is required (bash | node | java)");
        }
        lang = lang.toLowerCase(Locale.ROOT);
        if (!ALLOWED_LANGS.contains(lang)) {
            return SkillResult.error("lang must be one of bash, node, java");
        }
        if (code == null || code.isBlank()) {
            return SkillResult.error("code is required");
        }
        if (code.length() > MAX_CODE_CHARS) {
            return SkillResult.error("code too long (max " + MAX_CODE_CHARS + " chars)");
        }

        String dangerous = DangerousCommandChecker.firstDangerousMatch(code);
        if (dangerous != null) {
            return SkillResult.error("dangerous_command:" + dangerous);
        }

        int timeoutSec = resolveTimeout(lang, timeoutOverride);
        Path workdir = null;
        try {
            workdir = createWorkdir();
            return runProcess(workdir, lang, code, timeoutSec, t0);
        } catch (SecurityException se) {
            return SkillResult.error("sandbox_violation: " + se.getMessage());
        } catch (IOException e) {
            return SkillResult.error("launch_failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SkillResult.error("interrupted");
        } finally {
            cleanup(workdir);
        }
    }

    private SkillResult runProcess(Path workdir, String lang, String code, int timeoutSec, long t0)
            throws IOException, InterruptedException {
        List<String> cmd;
        switch (lang) {
            case "bash" -> {
                Path script = workdir.resolve("run.sh");
                Files.writeString(script, code, StandardCharsets.UTF_8);
                cmd = List.of("bash", script.toString());
            }
            case "node" -> {
                Path script = workdir.resolve("run.js");
                Files.writeString(script, code, StandardCharsets.UTF_8);
                cmd = List.of("node", script.toString());
            }
            case "java" -> {
                Path source = workdir.resolve("SourceFile.java");
                Files.writeString(source, code, StandardCharsets.UTF_8);
                cmd = List.of("java", source.toString());
            }
            default -> throw new IllegalStateException("unreachable — lang guard above");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workdir.toFile())
                .redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.clear();
        for (String key : INHERITED_ENV_KEYS) {
            String v = System.getenv(key);
            if (v != null) env.put(key, v);
        }
        env.put("HOME", workdir.toString());

        Process process = pb.start();
        CappedReader stdoutReader = drain(process.getInputStream(), "sf-code-stdout");
        CappedReader stderrReader = drain(process.getErrorStream(), "sf-code-stderr");

        boolean exitedInTime = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!exitedInTime) {
            List<ProcessHandle> descendants = new ArrayList<>();
            process.toHandle().descendants().forEach(descendants::add);
            for (ProcessHandle ph : descendants) {
                ph.destroyForcibly();
            }
            process.destroyForcibly();
            process.waitFor(DESTROY_GRACE_SEC, TimeUnit.SECONDS);
            stdoutReader.thread.join(READER_JOIN_MS);
            stderrReader.thread.join(READER_JOIN_MS);
            long dur = System.currentTimeMillis() - t0;
            return SkillResult.error("timeout after " + timeoutSec + "s (durationMs=" + dur + ")");
        }

        stdoutReader.thread.join(READER_JOIN_MS);
        stderrReader.thread.join(READER_JOIN_MS);
        int exit = process.exitValue();
        long dur = System.currentTimeMillis() - t0;
        String body = formatOutput(exit, dur, stdoutReader.buf.toString(), stderrReader.buf.toString());
        return exit == 0 ? SkillResult.success(body) : SkillResult.error(body);
    }

    private CappedReader drain(InputStream in, String threadName) {
        CappedReader reader = new CappedReader();
        Thread t = new Thread(() -> reader.drain(in), threadName);
        t.setDaemon(true);
        reader.thread = t;
        t.start();
        return reader;
    }

    private Path createWorkdir() throws IOException {
        Path tmpRoot = Paths.get(System.getProperty("java.io.tmpdir", "/tmp")).toRealPath();
        Path sandboxRoot = tmpRoot.resolve(SANDBOX_ROOT_NAME);
        Files.createDirectories(sandboxRoot);
        Path workdir = sandboxRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(workdir);
        Path real = workdir.toRealPath();
        if (!real.startsWith(sandboxRoot)) {
            throw new SecurityException("workdir escaped sandbox root: " + real);
        }
        return real;
    }

    private void cleanup(Path workdir) {
        if (workdir == null) return;
        try {
            if (!Files.exists(workdir)) return;
            try (var stream = Files.walk(workdir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private int resolveTimeout(String lang, Integer override) {
        if (override != null && override > 0) {
            return Math.min(override, MAX_TIMEOUT_SEC);
        }
        return switch (lang) {
            case "node" -> DEFAULT_TIMEOUT_NODE_SEC;
            case "java" -> DEFAULT_TIMEOUT_JAVA_SEC;
            default -> DEFAULT_TIMEOUT_BASH_SEC;
        };
    }

    private static String formatOutput(int exitCode, long durationMs, String stdout, String stderr) {
        StringBuilder sb = new StringBuilder(Math.min(stdout.length() + stderr.length() + 128, 2 * MAX_OUTPUT_BYTES));
        sb.append("exitCode=").append(exitCode).append('\n');
        sb.append("durationMs=").append(durationMs).append('\n');
        sb.append("---STDOUT---\n").append(stdout);
        if (!stdout.isEmpty() && !stdout.endsWith("\n")) sb.append('\n');
        sb.append("---STDERR---\n").append(stderr);
        return sb.toString();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class CappedReader {
        final StringBuilder buf = new StringBuilder();
        Thread thread;
        int charsWritten = 0;

        void drain(InputStream in) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] chunk = new char[1024];
                int n;
                while ((n = br.read(chunk)) != -1) {
                    if (charsWritten < MAX_OUTPUT_BYTES) {
                        int toWrite = Math.min(n, MAX_OUTPUT_BYTES - charsWritten);
                        buf.append(chunk, 0, toWrite);
                        charsWritten += toWrite;
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }
}
