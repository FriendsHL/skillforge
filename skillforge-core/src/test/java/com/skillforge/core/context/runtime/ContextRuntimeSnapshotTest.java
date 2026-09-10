package com.skillforge.core.context.runtime;

import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRuntimeSnapshotTest {

    @Test
    void roundTripKeepsStableReferencesAndLatestSkillInvocationOnly() {
        ToolCatalog catalog = ToolCatalog.fromAuthorizedSchemas(List.of(
                schema("mcp_web_search", "Search the web")), null);
        LoopContext original = new LoopContext();
        original.getToolDiscoveryState().discover(catalog.findByName("mcp_web_search"));
        original.recordSkillInvocation("research", "hash-v1", "old body");
        original.recordSkillInvocation("research", "hash-v2", "current body");

        ContextRuntimeSnapshot snapshot = original.runtimeSnapshot();
        LoopContext restored = new LoopContext();
        restored.restoreRuntimeSnapshot(snapshot);

        assertThat(restored.runtimeSnapshot().discoveredToolSchemaHashes())
                .containsEntry(
                        "tool:mcp_web_search",
                        catalog.findByName("mcp_web_search").schemaHash());
        assertThat(restored.runtimeSnapshot().invokedSkills())
                .singleElement()
                .satisfies(ref -> {
                    assertThat(ref.skillId()).isEqualTo("research");
                    assertThat(ref.versionHash()).isEqualTo("hash-v2");
                    assertThat(ref.invocationSequence()).isEqualTo(2L);
                });
    }

    @Test
    void restoredToolReferenceDoesNotBypassChangedSchemaHash() {
        ToolCatalog originalCatalog = ToolCatalog.fromAuthorizedSchemas(List.of(
                schema("mcp_web_search", "Search the web")), null);
        LoopContext original = new LoopContext();
        original.getToolDiscoveryState().discover(
                originalCatalog.findByName("mcp_web_search"));

        LoopContext restored = new LoopContext();
        restored.restoreRuntimeSnapshot(original.runtimeSnapshot());
        ToolCatalog changedCatalog = ToolCatalog.fromAuthorizedSchemas(List.of(
                schema("mcp_web_search", "Search the web with a changed contract")), null);

        assertThat(restored.getToolDiscoveryState().isDiscovered(
                changedCatalog.findByName("mcp_web_search"))).isFalse();
    }

    @Test
    void invalidSnapshotClearsExistingRuntimeInsteadOfRetainingFutureState() {
        ToolCatalog catalog = ToolCatalog.fromAuthorizedSchemas(List.of(
                schema("mcp_web_search", "Search the web")), null);
        LoopContext context = new LoopContext();
        context.getToolDiscoveryState().discover(catalog.findByName("mcp_web_search"));
        context.recordSkillInvocation("research", "hash-v1", "body");

        context.restoreRuntimeSnapshot(new ContextRuntimeSnapshot(
                99, Map.of("tool:future", "future-hash"), List.of()));

        assertThat(context.runtimeSnapshot()).isEqualTo(ContextRuntimeSnapshot.empty());
    }

    private static ToolSchema schema(String name, String description) {
        return new ToolSchema(name, description, Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")));
    }
}
