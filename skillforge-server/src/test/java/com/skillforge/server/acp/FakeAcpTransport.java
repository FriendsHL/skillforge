package com.skillforge.server.acp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-memory {@link AcpTransport} for unit tests — drives {@link AcpClient}
 * WITHOUT spawning a subprocess or touching the network.
 *
 * <p>{@link #sent} records every JSON line the client wrote;
 * {@link #emit(String)} feeds a canned inbound line to the client's listener
 * (mirroring lines the spike captured from the real adapter).
 */
class FakeAcpTransport implements AcpTransport {

    final List<String> sent = new ArrayList<>();
    private Consumer<String> listener;
    boolean started;
    boolean closed;

    @Override
    public void setLineListener(Consumer<String> lineListener) {
        this.listener = lineListener;
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void send(String jsonLine) {
        if (closed) {
            throw new AcpException("closed");
        }
        sent.add(jsonLine);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Simulate one inbound JSON-RPC line from the agent. */
    void emit(String line) {
        listener.accept(line);
    }

    /** The most recently sent JSON line. */
    String lastSent() {
        return sent.get(sent.size() - 1);
    }
}
