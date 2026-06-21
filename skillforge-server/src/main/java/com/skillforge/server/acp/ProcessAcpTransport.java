package com.skillforge.server.acp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link AcpTransport} implementation that spawns an ACP agent adapter as a child
 * process and speaks newline-delimited JSON-RPC 2.0 over its stdio.
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. Spike-verified framing/launch (2026-06-19):
 * <ul>
 *   <li>one JSON object per line on stdout (NOT LSP {@code Content-Length});</li>
 *   <li>the cc adapter REFUSES to launch if {@code CLAUDECODE} (etc.) is set — it
 *       thinks it is nested — so those vars are STRIPPED from the child env;</li>
 *   <li>default command is {@code npx --yes <adapter-package>} where the package
 *       defaults to the renamed {@code @agentclientprotocol/claude-agent-acp}
 *       (configurable).</li>
 * </ul>
 *
 * <p>A dedicated reader thread parses stdout line by line and feeds the line
 * listener; a second thread drains stderr to the log. Writes to stdin are
 * synchronized and flushed per message.
 */
public class ProcessAcpTransport implements AcpTransport {

    private static final Logger log = LoggerFactory.getLogger(ProcessAcpTransport.class);

    /** Default adapter package — the RENAMED cc adapter (was @zed-industries/claude-code-acp). */
    public static final String DEFAULT_ADAPTER_PACKAGE = "@agentclientprotocol/claude-agent-acp";

    /**
     * Env vars that make the cc adapter believe it is nested and refuse to start.
     * Always stripped from the child environment (defensive — a production JVM
     * normally has none of these).
     */
    private static final List<String> NESTING_ENV_VARS = List.of(
            "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT");

    private final List<String> command;
    private final String cwd;
    private final Map<String, String> extraEnv;

    private Consumer<String> lineListener;
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private Thread readerThread;
    private Thread stderrThread;
    private volatile boolean closed;

    /**
     * Construct with the default {@code npx --yes <DEFAULT_ADAPTER_PACKAGE>}
     * command.
     *
     * @param cwd      working directory for the child process
     * @param extraEnv extra env vars to set on the child (may be empty/null)
     */
    public ProcessAcpTransport(String cwd, Map<String, String> extraEnv) {
        this(defaultCommand(DEFAULT_ADAPTER_PACKAGE), cwd, extraEnv);
    }

    /**
     * Construct with an explicit adapter package (still launched via {@code npx
     * --yes}).
     */
    public static ProcessAcpTransport forAdapterPackage(
            String adapterPackage, String cwd, Map<String, String> extraEnv) {
        return new ProcessAcpTransport(defaultCommand(adapterPackage), cwd, extraEnv);
    }

    /**
     * Construct with a fully explicit command (e.g. a locally-installed binary).
     *
     * @param command  the process command + args; must be non-empty
     * @param cwd      working directory
     * @param extraEnv extra env vars (may be empty/null)
     */
    public ProcessAcpTransport(List<String> command, String cwd, Map<String, String> extraEnv) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        this.command = List.copyOf(command);
        this.cwd = cwd;
        this.extraEnv = (extraEnv == null) ? Map.of() : Map.copyOf(extraEnv);
    }

    private static List<String> defaultCommand(String adapterPackage) {
        return List.of("npx", "--yes", adapterPackage);
    }

    /**
     * Strip the nesting-signal env vars (so the cc adapter does not refuse to
     * launch) and apply extra env vars. Mutates {@code env} in place. Package-
     * private + static so the launch-critical sanitization is unit-testable
     * without spawning a real process.
     */
    static void sanitizeEnv(Map<String, String> env, Map<String, String> extraEnv) {
        for (String var : NESTING_ENV_VARS) {
            env.remove(var);
        }
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
    }

    @Override
    public void setLineListener(Consumer<String> lineListener) {
        this.lineListener = lineListener;
    }

    @Override
    public synchronized void start() {
        if (process != null) {
            return; // already started
        }
        if (lineListener == null) {
            throw new AcpException("lineListener must be set before start()");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(new java.io.File(cwd));
        }
        sanitizeEnv(pb.environment(), extraEnv);

        try {
            this.process = pb.start();
        } catch (IOException e) {
            throw new AcpException("Failed to spawn ACP adapter: " + command, e);
        }
        this.stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));

        this.readerThread = new Thread(this::readStdout, "acp-stdout-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        this.stderrThread = new Thread(this::drainStderr, "acp-stderr-drain");
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();

        log.info("ACP adapter started: {} (cwd={})", command, cwd);
    }

    @Override
    public void send(String jsonLine) {
        BufferedWriter w = this.stdin;
        if (closed || w == null) {
            throw new AcpException("ACP transport not open");
        }
        synchronized (writeLock) {
            try {
                w.write(jsonLine);
                w.write('\n');
                w.flush();
            } catch (IOException e) {
                throw new AcpException("Failed to write to ACP adapter stdin", e);
            }
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    lineListener.accept(trimmed);
                } catch (RuntimeException e) {
                    // Never let a listener failure kill the reader thread.
                    log.warn("ACP line listener threw (continuing)", e);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("ACP stdout reader ended with error", e);
            }
        } finally {
            log.debug("ACP stdout reader thread exiting");
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("ACP adapter stderr: {}", line);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("ACP stderr drain ended", e);
            }
        }
    }

    @Override
    public synchronized void close() {
        // J-W2: the volatile `closed`/`stdin` reads in send()/readStdout() are not fully
        // synchronized against start()'s writes, so a concurrent send() racing start() could
        // briefly see stale state. Accepted as-is: close() destroys the process and both
        // worker threads are daemons, so the worst case is a benign dropped write / an
        // IOException already handled — never a hang or a leaked thread/process.
        if (closed) {
            return;
        }
        closed = true;
        BufferedWriter w = this.stdin;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // closing stdin best-effort
            }
        }
        Process p = this.process;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        joinQuietly(readerThread);
        joinQuietly(stderrThread);
        log.info("ACP adapter closed");
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exposed for diagnostics/tests: the resolved command line. */
    public List<String> getCommand() {
        return new ArrayList<>(command);
    }
}
