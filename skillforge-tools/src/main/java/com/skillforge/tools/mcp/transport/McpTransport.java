package com.skillforge.tools.mcp.transport;

import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;

import java.io.Closeable;

/**
 * Bidirectional MCP transport abstraction. Implementations: {@link McpStdioTransport}
 * (stdin/stdout newline-delimited JSON-RPC). Future: HTTP/SSE (V2).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start()} — open the underlying channel (spawn process, connect socket, ...)</li>
 *   <li>{@link #sendRequest(McpRequest)} — send a JSON-RPC request and block until matching
 *       response (correlated via {@code id}) or timeout.</li>
 *   <li>{@link #isAlive()} — check if the underlying channel / process is still up.</li>
 *   <li>{@link #close()} — release resources, terminate the process if any.</li>
 * </ol>
 *
 * <p>Implementations MUST be safe to call {@code sendRequest} concurrently from multiple
 * threads (the agent loop may dispatch parallel tool_use calls within one turn). The
 * stdio impl achieves this via a single writer + dispatcher thread keyed by request id.
 */
public interface McpTransport extends Closeable {

    /**
     * Open the channel. For stdio: spawn the subprocess and start the reader thread.
     * Idempotent — second call is a no-op.
     */
    void start();

    /**
     * Send a JSON-RPC request and wait for the response (matching id). Blocks the
     * calling thread up to {@code timeoutMillis}.
     *
     * @throws com.skillforge.tools.mcp.exception.McpClientException on transport error,
     *         timeout, process death mid-request, or non-JSON garbage from the server.
     */
    McpResponse sendRequest(McpRequest request, long timeoutMillis);

    /**
     * Send a JSON-RPC notification (no id, no response expected). Best-effort write;
     * silent if the channel is closed.
     */
    void sendNotification(McpRequest notification);

    /** True iff the underlying channel/process is still alive. */
    boolean isAlive();

    /**
     * Close the channel. For stdio: write side closed first, give the process a
     * short grace, then {@code destroyForcibly}. Reader thread is interrupted.
     * Idempotent — calling close() on an already-closed transport is a no-op.
     */
    @Override
    void close();
}
