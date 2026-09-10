package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.context.runtime.SkillInvocationRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRuntimeSnapshotCodecTest {

    private final ContextRuntimeSnapshotCodec codec =
            new ContextRuntimeSnapshotCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTrip_emitsOnlyTheClosedVersionedControlReferenceShape() {
        ContextRuntimeSnapshot snapshot = new ContextRuntimeSnapshot(
                ContextRuntimeSnapshot.CURRENT_VERSION,
                Map.of("tool:mcp_search", "schema-hash"),
                List.of(new SkillInvocationRef("research", "skill-hash", 3L, 42)));

        String json = codec.encode(snapshot);

        assertThat(codec.decodeOrEmpty(json)).isEqualTo(snapshot);
        assertThat(json)
                .containsOnlyOnce("\"version\"")
                .doesNotContain("promptContent", "messages", "contentJson", "apiKey", "secret");
    }

    @Test
    void nullCorruptUnknownVersionAndUnknownFields_failClosedToEmpty() {
        assertThat(codec.decodeOrEmpty(null)).isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(codec.decodeOrEmpty("{not-json"))
                .isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(codec.decodeOrEmpty("""
                {"version":99,"discoveredToolSchemaHashes":{},"invokedSkills":[]}
                """))
                .isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(codec.decodeOrEmpty("""
                {"version":1,"discoveredToolSchemaHashes":{},"invokedSkills":[],
                 "messages":[{"content":"must not survive"}]}
                """))
                .isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(codec.decodeOrEmpty("""
                {"version":1,"discoveredToolSchemaHashes":{},"invokedSkills":[
                  {"skillId":"research","versionHash":"hash","invocationSequence":1,
                   "estimatedTokens":1,"promptContent":"must not survive"}]}
                """))
                .isEqualTo(ContextRuntimeSnapshot.empty());
    }
}
