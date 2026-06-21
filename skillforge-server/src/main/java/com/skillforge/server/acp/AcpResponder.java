package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Idempotent reply handle for ONE server→client request (ACP-EXTERNAL-AGENT P1b).
 *
 * <p>A server-request handler ({@link AcpClient#setServerRequestHandler(java.util.function.BiConsumer)})
 * receives one of these and completes it — synchronously on the reader thread, or
 * (the permission bridge case) later from a worker thread once the human answers.
 * The FIRST terminal call wins; every later call is a no-op, so a race between a
 * human answer and a timeout can never send two JSON-RPC responses for one id.
 *
 * <p>The permission helpers ({@link #selectPermissionOption}, {@link #cancelPermission})
 * encapsulate the ACP {@code session/request_permission} outcome shape:
 * <ul>
 *   <li>approve: {@code {outcome:{outcome:"selected", optionId:"<id>"}}}</li>
 *   <li>reject/cancel: {@code {outcome:{outcome:"cancelled"}}}</li>
 * </ul>
 */
public interface AcpResponder {

    /** Send a raw JSON-RPC {@code result}. First-call-wins. */
    void respondResult(JsonNode result);

    /** Send a JSON-RPC {@code error}. First-call-wins. */
    void respondError(int code, String message);

    /** Approve a permission request by selecting an ACP {@code optionId}. */
    void selectPermissionOption(String optionId);

    /** Reject/cancel a permission request ({@code outcome:"cancelled"}). */
    void cancelPermission();

    /**
     * Safe default deny: a permission request → {@link #cancelPermission()}; any
     * other server request → a JSON-RPC "method not found" error.
     */
    void deny();

    /** True when the bound request is a {@code session/request_permission}. */
    boolean isPermissionRequest();
}
