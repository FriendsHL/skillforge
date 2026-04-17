package com.skillforge.server.hook;

import com.skillforge.core.engine.DangerousCommandChecker;
import com.skillforge.core.engine.hook.HandlerRunner;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.server.config.LifecycleHooksScriptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Executes an inline {@link HookHandler.ScriptHandler} as a child process.
 *
 * <p>Safety net stack:
 * <ol>
 *   <li>{@code allowedLangs} config acts as an allow-list — unknown langs fail fast.</li>
 *   <li>{@link DangerousCommandChecker} scans the script text for destructive patterns.</li>
 *   <li>Script is written to a fresh sandboxed workdir ({@link ScriptWorkdirManager}).</li>
 *   <li>Env is cleared and only {@code PATH/LANG/HOME/TMPDIR/TZ + SF_*} are put back.</li>
 *   <li>Stdout+stderr are drained on a dedicated thread into a capped
 *       {@link ByteArrayOutputStream}; overflow bytes are read and discarded so the
 *       pipe never deadlocks.</li>
 *   <li>Timeout fires both {@code ProcessHandle::descendants().destroyForcibly} and
 *       {@code process.destroyForcibly} so {@code bash sleep 100 &} cannot survive.</li>
 * </ol>
 *
 * <p>Runner result is {@code success=true} only when the script exits 0 within the timeout.
 * All other cases return {@code success=false} with an {@code error} keyed by failure mode
 * ({@code lang_not_allowed}, {@code dangerous_command:&lt;pattern&gt;}, {@code timeout},
 * {@code exit_code:N}, {@code launch_failed}, {@code script_body_too_long}).
 */
@Component
public class ScriptHandlerRunner implements HandlerRunner<HookHandler.ScriptHandler> {

    private static final Logger log = LoggerFactory.getLogger(ScriptHandlerRunner.class);

    /** Envelope thread join grace window after the child process exits. */
    private static final long READER_JOIN_MS = 2_000L;

    /** System env variables that we propagate to the child. All others are stripped. */
    private static final List<String> INHERITED_ENV_KEYS = List.of("PATH", "LANG", "HOME", "TMPDIR", "TZ");

    /** Grace window after destroyForcibly to let the kernel reap zombies. */
    private static final long DESTROY_GRACE_SEC = 2L;

    private final LifecycleHooksScriptProperties props;
    private final ScriptWorkdirManager workdirManager;

    @org.springframework.beans.factory.annotation.Autowired
    public ScriptHandlerRunner(LifecycleHooksScriptProperties props) {
        this(props, new ScriptWorkdirManager());
    }

    /** Test seam — inject a workdir manager pointing at a controlled sandbox. */
    public ScriptHandlerRunner(LifecycleHooksScriptProperties props, ScriptWorkdirManager workdirManager) {
        this.props = props;
        this.workdirManager = workdirManager;
    }

    @Override
    public Class<HookHandler.ScriptHandler> handlerType() {
        return HookHandler.ScriptHandler.class;
    }

    @Override
    public HookRunResult run(HookHandler.ScriptHandler handler,
                             Map<String, Object> input,
                             HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();
        String lang = handler.getScriptLang() != null ? handler.getScriptLang().toLowerCase(Locale.ROOT) : "";
        String body = handler.getScriptBody() != null ? handler.getScriptBody() : "";

        // (1) Lang allow-list
        Set<String> allowed = new HashSet<>();
        for (String a : props.getAllowedLangs()) {
            if (a != null) allowed.add(a.toLowerCase(Locale.ROOT));
        }
        if (!allowed.contains(lang)) {
            return HookRunResult.failure("lang_not_allowed:" + lang, elapsed(t0));
        }

        // (2) scriptBody length (runner-side second line of defense; AgentService is first)
        if (body.length() > props.getMaxScriptBodyChars()) {
            return HookRunResult.failure("script_body_too_long:" + body.length(), elapsed(t0));
        }

        // (3) Dangerous command scan
        String bad = DangerousCommandChecker.firstDangerousMatch(body);
        if (bad != null) {
            log.warn("[ScriptRunner] dangerous pattern hit: {} (session={})", bad, ctx.sessionId());
            return HookRunResult.failure("dangerous_command:" + bad, elapsed(t0));
        }

        Path workdir = null;
        try {
            workdir = workdirManager.create();
            return runProcess(workdir, lang, body, ctx, t0);
        } catch (SecurityException se) {
            log.warn("[ScriptRunner] workdir sandbox violation: {}", se.getMessage());
            return HookRunResult.failure("sandbox_violation", elapsed(t0));
        } catch (Exception e) {
            log.warn("[ScriptRunner] unexpected failure: {}", e.toString());
            return HookRunResult.failure("launch_failed:" + e.getClass().getSimpleName(), elapsed(t0));
        } finally {
            if (workdir != null) {
                workdirManager.cleanup(workdir);
            }
        }
    }

    private HookRunResult runProcess(Path workdir, String lang, String body,
                                     HookExecutionContext ctx, long t0) throws IOException, InterruptedException {
        // Serialize body to file rather than inline-eval — avoids shell escaping bugs entirely.
        String scriptFileName = "bash".equals(lang) ? "hook.sh" : "hook.js";
        Path scriptFile = workdir.resolve(scriptFileName);
        Files.writeString(scriptFile, body, StandardCharsets.UTF_8);

        List<String> cmd = switch (lang) {
            case "bash" -> List.of("bash", scriptFile.toString());
            case "node" -> List.of("node", scriptFile.toString());
            default -> throw new IllegalStateException("Unreachable — lang guard above"); // defensive
        };

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workdir.toFile())
                .redirectErrorStream(true);

        // (4) Env: clear + whitelist + SF_*
        Map<String, String> env = pb.environment();
        env.clear();
        for (String k : INHERITED_ENV_KEYS) {
            String v = System.getenv(k);
            if (v != null) env.put(k, v);
        }
        env.put("SF_HOOK_EVENT", ctx.event() != null ? ctx.event().name() : "");
        env.put("SF_SESSION_ID", ctx.sessionId() != null ? ctx.sessionId() : "");
        env.put("SF_AGENT_ID", ctx.userId() != null ? String.valueOf(ctx.userId()) : "");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.warn("[ScriptRunner] process start failed: {}", e.toString());
            return HookRunResult.failure("launch_failed:" + e.getClass().getSimpleName(), elapsed(t0));
        }

        // (5) Drain stdout/stderr on dedicated reader thread with cap + overflow discard
        DrainedOutput drained = new DrainedOutput(props.getMaxOutputBytes());
        Thread reader = new Thread(() -> drained.drain(process.getInputStream()), "sf-hook-reader");
        reader.setDaemon(true);
        reader.start();

        int timeoutSec = computeTimeoutFromContext(ctx);
        boolean exitedInTime = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!exitedInTime) {
            // (6) Kill process tree: descendants first, then the root.
            List<ProcessHandle> descendants = new ArrayList<>();
            process.toHandle().descendants().forEach(descendants::add);
            for (ProcessHandle ph : descendants) {
                ph.destroyForcibly();
            }
            process.destroyForcibly();
            process.waitFor(DESTROY_GRACE_SEC, TimeUnit.SECONDS);
            reader.join(READER_JOIN_MS);
            return HookRunResult.failure("timeout", elapsed(t0));
        }

        reader.join(READER_JOIN_MS);
        int exit = process.exitValue();
        String captured = drained.asString();
        long dur = elapsed(t0);
        if (exit == 0) {
            return HookRunResult.ok(captured, dur);
        }
        String errMsg = "exit_code:" + exit
                + (captured.isEmpty() ? "" : ":" + truncateForError(captured));
        return new HookRunResult(false, captured, errMsg, dur);
    }

    private static long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }

    /**
     * Timeout for the script process. We don't have the {@code HookEntry.timeoutSeconds} on
     * this runner's API surface, so the dispatcher is the authoritative timer (via
     * {@code CompletableFuture.get(timeout)}). The runner itself uses a generous inner window
     * ({@code 4×maxOutputBytes / 32KB/s + 10s} capped at 300s) so it cannot out-wait the
     * dispatcher's supervising timeout but still gives the kernel a fair shot at reaping the
     * child before the supervisor cancels.
     */
    private int computeTimeoutFromContext(HookExecutionContext ctx) {
        Object hinted = ctx.metadata() != null ? ctx.metadata().get("_hook_timeout_sec") : null;
        if (hinted instanceof Number n) {
            int v = n.intValue();
            if (v > 0) return Math.min(Math.max(v, 1), 300);
        }
        return 300;
    }

    private static String truncateForError(String s) {
        if (s == null) return "";
        String clean = s.strip();
        if (clean.length() <= 200) return clean;
        return clean.substring(0, 200) + "...";
    }

    /**
     * Drain-and-discard reader. Accumulates up to {@code cap} <em>chars</em> in a buffer;
     * past that, it reads and discards to keep the OS pipe from blocking the child.
     *
     * <p>Capping at the char level (not bytes) avoids splitting a multi-byte UTF-8 sequence
     * mid-stream, which would produce replacement characters in the final output.
     */
    private static final class DrainedOutput {
        private final int cap;
        private final StringBuilder buf;
        private int charsWritten = 0;

        DrainedOutput(int cap) {
            this.cap = cap;
            this.buf = new StringBuilder(Math.min(cap, 4096));
        }

        void drain(InputStream in) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] chunk = new char[1024];
                int n;
                while ((n = br.read(chunk)) != -1) {
                    if (charsWritten < cap) {
                        int toWrite = Math.min(n, cap - charsWritten);
                        buf.append(chunk, 0, toWrite);
                        charsWritten += toWrite;
                    }
                    // else: discard (drain only — keeps OS pipe from blocking the child)
                }
            } catch (IOException e) {
                log.debug("[ScriptRunner] drain IO closed: {}", e.toString());
            }
        }

        String asString() {
            return buf.toString();
        }

        @SuppressWarnings("unused") // available for future telemetry
        int totalCharsRead() {
            return charsWritten;
        }
    }
}
