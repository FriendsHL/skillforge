package com.skillforge.server.acp;

import java.util.function.Consumer;

/**
 * Injectable transport boundary for the ACP client.
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. The transport carries newline-delimited JSON-RPC
 * 2.0 messages. {@link AcpClient} writes a single JSON object (NO trailing
 * newline — the transport owns framing) via {@link #send(String)} and receives
 * each inbound line via the listener registered through
 * {@link #setLineListener(Consumer)}.
 *
 * <p>Existence of this interface is what makes {@link AcpClient} unit-testable
 * without spawning a real subprocess: tests inject a fake in-memory transport
 * and feed canned JSON-RPC lines. The production impl is
 * {@link ProcessAcpTransport}.
 */
public interface AcpTransport {

    /**
     * Register the callback invoked once per inbound JSON-RPC line (already
     * trimmed; blank lines are not delivered). Must be set before {@link #start()}.
     * The callback may be invoked from a transport-owned reader thread.
     */
    void setLineListener(Consumer<String> lineListener);

    /**
     * Begin delivering inbound lines (e.g. spawn the subprocess / start the
     * reader thread). Idempotent implementations are encouraged.
     */
    void start();

    /**
     * Send one JSON-RPC message. The implementation appends the line delimiter
     * ({@code '\n'}); callers pass the raw JSON object string.
     *
     * @throws AcpException if the transport is closed or the write fails
     */
    void send(String jsonLine);

    /** Stop the transport and release resources (kill subprocess, join threads). Idempotent. */
    void close();
}
