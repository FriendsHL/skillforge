package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.context.PromptObservationHashes;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.context.runtime.SkillInvocationRef;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRuntimeCheckpointServiceTest {

    private final ContextRuntimeSnapshotCodec codec =
            new ContextRuntimeSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    private final ContextRuntimeCheckpointService service =
            new ContextRuntimeCheckpointService(codec);

    @Test
    void resume_usesCurrentSessionRuntimeWhileBranchAndRestoreUseCheckpointRuntime() {
        ToolCatalog catalog = ToolCatalog.fromAuthorizedSchemas(
                List.of(schema("mcp_checkpoint", "checkpoint tool")), null);
        SkillDefinition checkpointSkill = skill("checkpoint-skill", "checkpoint body");
        SessionSkillView view = new SessionSkillView(
                Map.of("checkpoint-skill", checkpointSkill), Set.of(), Set.of("checkpoint-skill"));
        String current = codec.encode(new ContextRuntimeSnapshot(
                1,
                Map.of("tool:future", "future-hash"),
                List.of(new SkillInvocationRef("future-skill", "future-hash", 9L, 9))));
        String checkpoint = codec.encode(new ContextRuntimeSnapshot(
                1,
                Map.of(
                        catalog.findByName("mcp_checkpoint").id(),
                        catalog.findByName("mcp_checkpoint").schemaHash()),
                List.of(new SkillInvocationRef(
                        "checkpoint-skill",
                        PromptObservationHashes.sha256("checkpoint body"),
                        2L,
                        5))));

        assertThat(codec.decodeOrEmpty(service.runtimeForResume(current))
                .discoveredToolSchemaHashes()).containsKey("tool:future");
        assertThat(codec.decodeOrEmpty(service.runtimeForBranch(checkpoint, catalog, view)))
                .isEqualTo(codec.decodeOrEmpty(checkpoint));
        assertThat(codec.decodeOrEmpty(service.runtimeForRestore(checkpoint, catalog, view)))
                .isEqualTo(codec.decodeOrEmpty(checkpoint));
    }

    @Test
    void branchAndRestore_filterUnknownRefsAndLegacyCorruptionToEmpty() {
        ToolCatalog emptyCatalog = ToolCatalog.fromAuthorizedSchemas(List.of(), null);

        assertThat(codec.decodeOrEmpty(service.runtimeForBranch(
                "{corrupt", emptyCatalog, SessionSkillView.EMPTY)))
                .isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(codec.decodeOrEmpty(service.runtimeForRestore(
                """
                {"version":99,"discoveredToolSchemaHashes":{},"invokedSkills":[]}
                """, emptyCatalog, SessionSkillView.EMPTY)))
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
