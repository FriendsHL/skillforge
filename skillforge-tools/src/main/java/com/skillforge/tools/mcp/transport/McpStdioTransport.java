package com.skillforge.tools.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stdio MCP transport. Spawns a subprocess via {@link ProcessBuilder} (array form, no
 * shell) and exchanges newline-delimited JSON-RPC messages over stdin/stdout.
 *
 * <p>MCP stdio framing: <strong>one JSON object per line</strong> (LF-terminated).
 * The MCP spec for stdio transport uses NDJSON, NOT LSP-style {@code Content-Length}
 * headers. Each request and response is a single complete JSON object on its own line.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>One {@link ProcessBuilder} → one process. No re-spawn on crash; that's the
 *       lifecycle layer's job (P11 INV-10: lazy reconnect).</li>
 *   <li>One dedicated <em>reader</em> daemon thread parses stdout line-by-line and
 *       hands each {@link McpResponse} to the matching pending caller via a
 *       per-id {@link SynchronousQueue}. Server-initiated notifications (no id)
 *       are logged + dropped (MVP; V2 may surface them).</li>
 *   <li>One <em>stderr drain</em> daemon thread reads stderr to prevent the OS pipe
 *       from filling and blocking the child. Lines are logged at WARN.</li>
 *   <li>{@link #sendRequest} synchronizes on {@code writeLock} so only one writer
 *       puts bytes on stdin at a time — concurrent requests interleave on the wire.</li>
 * </ul>
 *
 * <p>Crash handling: if the reader observes EOF on stdout (process died) it sets
 * {@link #closed} and unblocks every pending caller with an error response.
 *
 * <p>This class is not aware of MCP semantics (initialize / tools/list); it speaks
 * raw JSON-RPC. {@code McpServerSession} owns the protocol state machine.
 */
public class McpStdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);

    private final String serverName;
    private final List<String> command;
    private final Map<String, String> env;
    private final ObjectMapper objectMapper;
    private final AtomicLong nextRequestId = new AtomicLong(1);

    // Per-id "mailbox" — sender installs an entry, reader thread fills it.
    private final ConcurrentHashMap<Object, SynchronousQueue<McpResponse>> pending =
            new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Single mutex for stdin writes — we write the JSON line + LF as one atomic op.
    private final Object writeLock = new Object();

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread stderrThread;

    public McpStdioTransport(String serverName,
                             List<String> command,
                             Map<String, String> env,
                             ObjectMapper objectMapper) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        this.serverName = serverName;
        this.command = List.copyOf(command);
        this.env = env != null ? Map.copyOf(env) : Map.of();
        this.objectMapper = objectMapper;
    }

    /** Test/seam constructor: skip the ProcessBuilder and use pre-attached streams. */
    McpStdioTransport(String serverName,
                      InputStream stdout,
                      OutputStream stdin,
                      InputStream stderr,
                      ObjectMapper objectMapper) {
        this.serverName = serverName;
        this.command = List.of();
        this.env = Map.of();
        this.objectMapper = objectMapper;
        this.process = null;
        attachStreams(stdout, stdin, stderr);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return; // idempotent
        }
        if (process == null && !command.isEmpty()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                // Inherit + override env (env vars already resolved by the lifecycle layer).
                pb.environment().putAll(env);
                pb.redirectErrorStream(false);
                this.process = pb.start();
                attachStreams(process.getInputStream(), process.getOutputStream(),
                        process.getErrorStream());
                log.info("MCP stdio server '{}' started: pid={} command={}",
                        serverName, process.pid(), command);
            } catch (IOException e) {
                started.set(false);
                throw new McpClientException("Failed to spawn MCP server '" + serverName
                        + "' command=" + command + ": " + e.getMessage(), e);
            }
        } else if (process == null) {
            // Pre-attached streams (test seam path); reader/stderr threads already started.
            return;
        }
    }

    private void attachStreams(InputStream stdout, OutputStream stdin, InputStream stderr) {
        this.writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));

        BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        this.readerThread = new Thread(() -> readLoop(stdoutReader),
                "mcp-stdio-reader-" + serverName);
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        if (stderr != null) {
            BufferedReader stderrReader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));
            this.stderrThread = new Thread(() -> drainStderr(stderrReader),
                    "mcp-stdio-stderr-" + serverName);
            this.stderrThread.setDaemon(true);
            this.stderrThread.start();
        }
    }

    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    McpResponse resp = objectMapper.readValue(line, McpResponse.class);
                    if (resp.id() == null) {
                        // Server-initiated notification — MVP: log + drop.
                        log.debug("MCP server '{}' sent notification (dropped): {}", serverName, line);
                        continue;
                    }
                    SynchronousQueue<McpResponse> mailbox = pending.remove(canonicalId(resp.id()));
                    if (mailbox == null) {
                        log.warn("MCP server '{}' sent response for unknown id={} (line={})",
                                serverName, resp.id(), line);
                        continue;
                    }
                    // offer with timeout: caller may have given up (timed out) → no harm.
                    mailbox.offer(resp, 1, TimeUnit.SECONDS);
                } catch (JsonProcessingException jpe) {
                    log.warn("MCP server '{}' emitted non-JSON line, dropping: {}",
                            serverName, line);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.warn("MCP server '{}' stdout reader IOException: {}", serverName, e.getMessage());
            }
        } finally {
            // EOF or IOException → unblock everyone waiting with a synthetic error so they
            // don't hang indefinitely. The caller will translate to a SkillResult.error.
            closed.set(true);
            failAllPending("MCP server '" + serverName + "' stdout closed");
        }
    }

    private void drainStderr(BufferedReader reader) {
        // r1 W2 (security): MCP server stderr can leak credentials, tool arguments, or other
        // sensitive request bodies. tech-design.md §"错误处理 / 安全" mandates "MCP server
        // stdio output 不直接 log". We drain the pipe (otherwise the OS buffer fills and
        // blocks the child) but emit at DEBUG so operators must explicitly raise the log
        // level to see contents. INFO/WARN paths must never carry the raw line.
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (!line.isBlank() && log.isDebugEnabled()) {
                    log.debug("[mcp:{}:stderr] {}", serverName, line);
                }
            }
        } catch (IOException ignored) {
            // expected on close
        }
    }

    private void failAllPending(String reason) {
        for (Map.Entry<Object, SynchronousQueue<McpResponse>> e : pending.entrySet()) {
            McpResponse synthetic = new McpResponse(McpRequest.JSONRPC_VERSION, e.getKey(), null,
                    new com.skillforge.tools.mcp.protocol.McpError(-32000, reason, null));
            e.getValue().offer(synthetic);
        }
        pending.clear();
    }

    @Override
    public McpResponse sendRequest(McpRequest request, long timeoutMillis) {
        if (closed.get()) {
            throw new McpClientException("MCP transport '" + serverName + "' is closed");
        }
        if (!started.get()) {
            throw new McpClientException("MCP transport '" + serverName + "' is not started");
        }
        if (request.id() == null) {
            throw new IllegalArgumentException(
                    "sendRequest requires a non-null id; use sendNotification() for fire-and-forget");
        }
        Object key = canonicalId(request.id());
        SynchronousQueue<McpResponse> mailbox = new SynchronousQueue<>();
        if (pending.putIfAbsent(key, mailbox) != null) {
            throw new McpClientException("Duplicate in-flight request id=" + request.id());
        }
        try {
            writeJsonLine(request);
            McpResponse resp = mailbox.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (resp == null) {
                throw new McpClientException("MCP request timeout id=" + request.id()
                        + " method=" + request.method() + " server=" + serverName
                        + " (" + timeoutMillis + "ms)");
            }
            if (resp.isError() && resp.error().code() == -32000
                    && resp.error().message() != null
                    && resp.error().message().contains("stdout closed")) {
                throw new McpClientException(resp.error().message());
            }
            return resp;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP request interrupted id=" + request.id(), ie);
        } finally {
            pending.remove(key);
        }
    }

    @Override
    public void sendNotification(McpRequest notification) {
        if (closed.get()) {
            log.debug("Dropping notification on closed transport '{}': {}", serverName, notification.method());
            return;
        }
        if (notification.id() != null) {
            throw new IllegalArgumentException("sendNotification requires id == null");
        }
        try {
            writeJsonLine(notification);
        } catch (McpClientException ce) {
            log.warn("Notification write failed for '{}': {}", serverName, ce.getMessage());
        }
    }

    private void writeJsonLine(McpRequest msg) {
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException jpe) {
            throw new McpClientException("Failed to encode JSON-RPC request: " + jpe.getMessage(), jpe);
        }
        synchronized (writeLock) {
            if (closed.get() || writer == null) {
                throw new McpClientException("MCP transport '" + serverName + "' is closed");
            }
            try {
                writer.write(json);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                closed.set(true);
                throw new McpClientException("Failed to write JSON-RPC line to '"
                        + serverName + "': " + e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean isAlive() {
        if (closed.get()) return false;
        if (process != null && !process.isAlive()) return false;
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // idempotent
        }
        // 1. close stdin so the child sees EOF and shuts down cooperatively.
        synchronized (writeLock) {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
                writer = null;
            }
        }
        // 2. give the child up to 2s to exit on its own.
        if (process != null) {
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    log.info("MCP server '{}' did not exit within 2s — destroyForcibly", serverName);
                    process.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // 3. interrupt reader/stderr threads (they may already be exiting on EOF).
        if (readerThread != null) readerThread.interrupt();
        if (stderrThread != null) stderrThread.interrupt();
        // 4. unblock any caller still waiting.
        failAllPending("MCP transport '" + serverName + "' closed");
        log.info("MCP stdio server '{}' closed", serverName);
    }

    /** Generate a fresh request id (caller-side; reader matches by id). */
    public long nextId() {
        return nextRequestId.getAndIncrement();
    }

    /**
     * Jackson sometimes deserializes integer ids as {@code Integer} and sometimes as
     * {@code Long} depending on magnitude. Compare via canonical string form so the
     * pending-map lookup is robust regardless of numeric width.
     */
    private static Object canonicalId(Object id) {
        if (id instanceof Number n) {
            return "n:" + n.longValue();
        }
        return "s:" + id;
    }
}
