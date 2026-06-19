package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A server→client JSON-RPC REQUEST received from the ACP agent (it carries an
 * {@code id} and expects a response). The canonical example is a permission
 * request (the agent asking the client to approve a tool call).
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. For P1a-1 these are merely surfaced to a callback
 * (see {@link AcpClient#setServerRequestHandler}); the permission bridge that
 * maps them to SkillForge ask/confirmation is P1b. Until then a default handler
 * denies them so a stray request can never hang the agent waiting on a reply.
 *
 * @param id     the JSON-RPC request id (must be echoed in the response)
 * @param method the JSON-RPC method (e.g. {@code session/request_permission})
 * @param params the raw {@code params} node (may be {@code null})
 */
public record AcpServerRequest(JsonNode id, String method, JsonNode params) {
}
