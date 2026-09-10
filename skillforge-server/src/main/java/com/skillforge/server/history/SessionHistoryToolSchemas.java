package com.skillforge.server.history;

import com.skillforge.core.model.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-visible closed schemas for the system-resident current-Session History tool pair. */
public final class SessionHistoryToolSchemas {

    public static final String SEARCH_NAME = "SessionHistorySearch";
    public static final String READ_NAME = "SessionHistoryRead";

    private SessionHistoryToolSchemas() {
    }

    public static ToolSchema search() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> query = stringProperty(
                "Case-insensitive literal substring, not semantic search; spaces are literal. "
                        + "Use one distinctive fragment likely present in the original text.", 1, 512);
        query.put("pattern", "\\S");
        properties.put("query", query);
        properties.put("seqFrom", integerProperty("Optional inclusive lower logical sequence.", 0, null));
        properties.put("seqTo", integerProperty("Optional inclusive upper logical sequence.", 0, null));
        properties.put("roles", enumArrayProperty("Optional message roles.", List.of("USER", "ASSISTANT"), 2));
        properties.put("kinds", enumArrayProperty("Optional evidence kinds.",
                List.of("TEXT", "TOOL_USE", "TOOL_RESULT", "SUMMARY"), 4));
        properties.put("toolName", stringProperty("Exact Tool name filter.", 1, 256));
        properties.put("toolUseId", stringProperty("Exact Tool use ID filter.", 1, 256));
        properties.put("compacted", enumProperty("Compaction-state filter.",
                List.of("ANY", "COMPACTED", "UNCOMPACTED")));
        properties.put("summaryState", enumProperty("Summary-state filter.",
                List.of("ANY", "ACTIVE", "SUPERSEDED")));
        properties.put("limit", integerProperty("Locator count; default 8, hard cap 50.", 1, 50));
        properties.put("cursor", stringProperty("Opaque exact-return continuation token.", 1, 4096));

        Map<String, Object> schema = closedObject(properties);
        schema.put("anyOf", List.of(
                required("query"),
                required("seqFrom"),
                required("seqTo"),
                required("roles"),
                required("kinds"),
                required("toolName"),
                required("toolUseId"),
                requiredEnum("compacted", List.of("COMPACTED", "UNCOMPACTED")),
                requiredEnum("summaryState", List.of("ACTIVE", "SUPERSEDED"))
        ));
        return new ToolSchema(SEARCH_NAME,
                "Locate missing facts in the persisted history of the current Session. "
                        + "Issue one Search call first and inspect its result before searching again; "
                        + "avoid parallel overlapping queries. Filters combine with AND. "
                        + "Once useful locators appear, read their refs together with SessionHistoryRead "
                        + "instead of issuing overlapping searches. If empty, shorten the fragment or relax filters. "
                        + "Locators are not evidence.", schema);
    }

    public static ToolSchema read() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("refs", Map.of(
                "type", "array", "minItems", 1, "maxItems", 50,
                "items", stringProperty("Stable History ref.", 1, 4096)));
        properties.put("seqFrom", integerProperty("Inclusive lower sequence.", 0, null));
        properties.put("seqTo", integerProperty("Inclusive upper sequence.", 0, null));
        properties.put("aroundSeq", integerProperty("Center sequence.", 0, null));
        properties.put("before", integerProperty("Events before center.", 0, 49));
        properties.put("after", integerProperty("Events after center.", 0, 49));
        properties.put("tail", integerProperty("Last eligible events, returned ascending.", 1, 50));
        properties.put("archiveRef", stringProperty("Authorized archive/block ref.", 1, 4096));
        properties.put("offset", integerProperty("Unicode code-point offset.", 0, null));
        properties.put("maxChars", integerProperty("Projected code-point budget; hard cap 20000.", 1, 20_000));
        properties.put("cursor", stringProperty("Opaque exact-return continuation token.", 1, 4096));

        Map<String, Object> schema = closedObject(properties);
        schema.put("oneOf", List.of(
                exclusiveSelector(List.of("refs"),
                        "seqFrom", "seqTo", "aroundSeq", "before", "after", "tail",
                        "archiveRef", "offset", "maxChars"),
                exclusiveSelector(List.of("seqFrom", "seqTo"),
                        "refs", "aroundSeq", "before", "after", "tail",
                        "archiveRef", "offset", "maxChars"),
                exclusiveSelector(List.of("aroundSeq", "before", "after"),
                        "refs", "seqFrom", "seqTo", "tail", "archiveRef", "offset", "maxChars"),
                exclusiveSelector(List.of("tail"),
                        "refs", "seqFrom", "seqTo", "aroundSeq", "before", "after",
                        "archiveRef", "offset", "maxChars"),
                exclusiveSelector(List.of("archiveRef", "offset", "maxChars"),
                        "refs", "seqFrom", "seqTo", "aroundSeq", "before", "after", "tail")
        ));
        return new ToolSchema(READ_NAME,
                "Read exact authorized evidence from the persisted history of the current Session. "
                        + "Choose exactly one selector arm and return any cursor unchanged.", schema);
    }

    private static Map<String, Object> closedObject(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(
            String description, Integer minLength, Integer maxLength) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        if (minLength != null) property.put("minLength", minLength);
        if (maxLength != null) property.put("maxLength", maxLength);
        return property;
    }

    private static Map<String, Object> integerProperty(
            String description, Integer minimum, Integer maximum) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        if (minimum != null) property.put("minimum", minimum);
        if (maximum != null) property.put("maximum", maximum);
        return property;
    }

    private static Map<String, Object> enumProperty(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private static Map<String, Object> enumArrayProperty(
            String description, List<String> values, int maxItems) {
        return Map.of(
                "type", "array", "description", description,
                "minItems", 1, "maxItems", maxItems, "uniqueItems", true,
                "items", Map.of("type", "string", "enum", values));
    }

    private static Map<String, Object> required(String... fields) {
        return Map.of("required", List.of(fields));
    }

    private static Map<String, Object> requiredEnum(String field, List<String> values) {
        return Map.of(
                "required", List.of(field),
                "properties", Map.of(field, Map.of("enum", values)));
    }

    private static Map<String, Object> exclusiveSelector(
            List<String> requiredFields, String... forbiddenFields) {
        return Map.of(
                "required", requiredFields,
                "not", Map.of(
                        "anyOf", java.util.Arrays.stream(forbiddenFields)
                                .map(SessionHistoryToolSchemas::required)
                                .toList()));
    }
}
