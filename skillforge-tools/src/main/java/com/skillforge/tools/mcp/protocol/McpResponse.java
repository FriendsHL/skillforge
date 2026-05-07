package com.skillforge.tools.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 response envelope. Either {@link #result()} or {@link #error()} is
 * non-null, never both. {@code id} is the same primitive shape (number/string/null)
 * the request sent.
 *
 * <p>{@link JsonIgnoreProperties} is set to {@code ignoreUnknown} so MCP server
 * extensions (custom fields outside the JSON-RPC envelope) don't break parsing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpResponse(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") Object id,
        @JsonProperty("result") Object result,
        @JsonProperty("error") McpError error
) {

    public boolean isError() {
        return error != null;
    }
}
