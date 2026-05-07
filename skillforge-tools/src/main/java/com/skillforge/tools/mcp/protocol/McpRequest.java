package com.skillforge.tools.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 request envelope used over MCP transports.
 *
 * <p>{@code id} is omitted (notifications) when {@code null} — Jackson respects
 * {@link JsonInclude.Include#NON_NULL} on the field. {@code params} may be any
 * shape (object, array, null) — kept as {@link Object} to defer serialization
 * to the caller-provided params record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpRequest(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") Object id,
        @JsonProperty("method") String method,
        @JsonProperty("params") Object params
) {

    public static final String JSONRPC_VERSION = "2.0";

    /** Standard request constructor — fills {@code jsonrpc} = "2.0". */
    public static McpRequest of(Object id, String method, Object params) {
        return new McpRequest(JSONRPC_VERSION, id, method, params);
    }
}
