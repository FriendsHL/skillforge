package com.skillforge.server.history;

import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHistoryToolSchemasTest {

    @Test
    void searchSchemaIsClosedAndContainsNoIdentityField() {
        ToolSchema schema = SessionHistoryToolSchemas.search();

        assertThat(schema.getName()).isEqualTo("SessionHistorySearch");
        assertThat(schema.getInputSchema()).containsEntry("additionalProperties", false);
        assertThat(schema.getInputSchema()).doesNotContainKeys("sessionId", "userId");
        assertThat(recursiveKeys(schema.getInputSchema()))
                .doesNotContain("sessionId", "session_id", "userId", "user_id");

        Map<String, Object> properties = properties(schema);
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "query", "seqFrom", "seqTo", "roles", "kinds", "toolName",
                "toolUseId", "compacted", "summaryState", "limit", "cursor");
        assertThat(properties.get("roles").toString()).contains("USER", "ASSISTANT");
        assertThat(properties.get("kinds").toString())
                .contains("TEXT", "TOOL_USE", "TOOL_RESULT", "SUMMARY");
        assertThat((Collection<?>) schema.getInputSchema().get("anyOf")).hasSize(9);
    }

    @Test
    void readSchemaIsClosedAndDeclaresFiveSelectorArms() {
        ToolSchema schema = SessionHistoryToolSchemas.read();

        assertThat(schema.getName()).isEqualTo("SessionHistoryRead");
        assertThat(schema.getInputSchema()).containsEntry("additionalProperties", false);
        assertThat(recursiveKeys(schema.getInputSchema()))
                .doesNotContain("sessionId", "session_id", "userId", "user_id");

        Map<String, Object> properties = properties(schema);
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "refs", "seqFrom", "seqTo", "aroundSeq", "before", "after",
                "tail", "archiveRef", "offset", "maxChars", "cursor");
        Collection<?> selectorArms = (Collection<?>) schema.getInputSchema().get("oneOf");
        assertThat(selectorArms).hasSize(5);
        assertThat(selectorArms)
                .allSatisfy(arm -> assertThat(((Map<?, ?>) arm).containsKey("not")).isTrue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(ToolSchema schema) {
        return (Map<String, Object>) schema.getInputSchema().get("properties");
    }

    private static java.util.Set<String> recursiveKeys(Object value) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                keys.add(String.valueOf(entry.getKey()));
                keys.addAll(recursiveKeys(entry.getValue()));
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                keys.addAll(recursiveKeys(item));
            }
        }
        return keys;
    }
}
