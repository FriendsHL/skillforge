package com.skillforge.core.context.runtime;

import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.context.PromptObservationHashes;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRuntimeSnapshotResolverTest {

    @Test
    void resolve_keepsOnlyCurrentAuthorizedRegistryReferencesWithExactHashes() {
        ToolCatalog catalog = ToolCatalog.fromAuthorizedSchemas(List.of(
                schema("mcp_search", "current search"),
                schema("mcp_files", "current files")), null);
        SkillDefinition research = skill("research", "current research body");
        SessionSkillView skillView = new SessionSkillView(
                Map.of("research", research), Set.of(), Set.of("research"));
        ContextRuntimeSnapshot input = new ContextRuntimeSnapshot(
                ContextRuntimeSnapshot.CURRENT_VERSION,
                Map.of(
                        catalog.findByName("mcp_search").id(),
                        catalog.findByName("mcp_search").schemaHash(),
                        catalog.findByName("mcp_files").id(),
                        "stale-schema-hash",
                        "tool:not-authorized",
                        "unknown-hash"),
                List.of(
                        new SkillInvocationRef(
                                "research",
                                PromptObservationHashes.sha256("current research body"),
                                4L,
                                20),
                        new SkillInvocationRef("removed-skill", "unknown-hash", 5L, 10)));

        ContextRuntimeSnapshot resolved = ContextRuntimeSnapshotResolver.resolve(
                input, catalog, skillView);

        assertThat(resolved.discoveredToolSchemaHashes())
                .containsOnlyKeys(catalog.findByName("mcp_search").id());
        assertThat(resolved.invokedSkills())
                .extracting(SkillInvocationRef::skillId)
                .containsExactly("research");
    }

    @Test
    void resolve_missingAuthorityOrUnknownVersion_failsClosedToEmpty() {
        ContextRuntimeSnapshot snapshot = new ContextRuntimeSnapshot(
                99,
                Map.of("tool:mcp_search", "hash"),
                List.of(new SkillInvocationRef("research", "hash", 1L, 1)));

        assertThat(ContextRuntimeSnapshotResolver.resolve(snapshot, null, null))
                .isEqualTo(ContextRuntimeSnapshot.empty());
    }

    private static ToolSchema schema(String name, String description) {
        return new ToolSchema(name, description, Map.of("type", "object"));
    }

    private static SkillDefinition skill(String name, String content) {
        SkillDefinition skill = new SkillDefinition();
        skill.setName(name);
        skill.setPromptContent(content);
        return skill;
    }
}
