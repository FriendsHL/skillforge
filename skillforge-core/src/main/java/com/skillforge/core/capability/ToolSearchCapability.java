package com.skillforge.core.capability;

import com.skillforge.core.model.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-special capability that discovers deferred schemas. It is not a
 * registry Tool because execution needs the current authorized ToolCatalog.
 */
public final class ToolSearchCapability {

    public static final String NAME = ToolCatalog.TOOL_SEARCH_NAME;

    private ToolSearchCapability() {}

    public static ToolSchema schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Keywords describing the capability needed."));
        properties.put("max_results", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 20,
                "description", "Maximum descriptors to discover. Default 5."));
        return new ToolSchema(
                NAME,
                "Search capabilities already authorized for this session. "
                        + "Matching deferred tools become available with their authoritative "
                        + "JSON schema on the next model call.",
                Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("query"),
                        "additionalProperties", false));
    }
}
