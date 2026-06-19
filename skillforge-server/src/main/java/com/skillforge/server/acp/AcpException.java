package com.skillforge.server.acp;

/**
 * Domain exception for the ACP (Agent Client Protocol) client layer.
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. Pure protocol-layer error — raised on transport
 * failures, JSON-RPC error responses, and adapter spawn problems. Carries an
 * optional JSON-RPC error code (see {@link AcpRpcError}); {@code null} for
 * transport/spawn errors that never reached the JSON-RPC layer.
 */
public class AcpException extends RuntimeException {

    private final Integer rpcCode;

    public AcpException(String message) {
        super(message);
        this.rpcCode = null;
    }

    public AcpException(String message, Throwable cause) {
        super(message, cause);
        this.rpcCode = null;
    }

    public AcpException(String message, int rpcCode) {
        super(message);
        this.rpcCode = rpcCode;
    }

    /** JSON-RPC error code if this error came from a peer error response, else {@code null}. */
    public Integer getRpcCode() {
        return rpcCode;
    }
}
