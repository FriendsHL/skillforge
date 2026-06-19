package com.skillforge.server.acp;

/**
 * A JSON-RPC 2.0 {@code error} object as returned by the ACP peer.
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. {@code data} is the raw error payload (may be
 * {@code null}); kept as a {@code String} (re-serialized JSON) to avoid leaking
 * Jackson node types out of the protocol layer.
 */
public record AcpRpcError(int code, String message, String data) {
}
