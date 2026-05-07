package com.skillforge.tools.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * One MCP tool as advertised by the server's {@code tools/list} response.
 *
 * <p>{@code inputSchema} is JSON Schema (object). We pass it through verbatim to
 * the LLM via {@code McpToolAdapter.getToolSchema()} (INV-11).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpToolDescriptor(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("inputSchema") Map<String, Object> inputSchema
) {
}
