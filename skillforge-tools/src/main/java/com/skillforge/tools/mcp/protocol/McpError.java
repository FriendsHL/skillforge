package com.skillforge.tools.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 error object embedded inside {@link McpResponse#error()}.
 *
 * <p>{@code data} is opaque (server-defined). We surface it as text in
 * {@code McpToolAdapter} error output for debuggability.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpError(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") Object data
) {
}
