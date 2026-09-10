package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.context.runtime.SkillInvocationRef;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaContextRuntimeStoreTest {

    private final SessionRepository repository = mock(SessionRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JpaContextRuntimeStore store =
            new JpaContextRuntimeStore(repository, mapper);

    @Test
    void snapshotRoundTripsAsContentFreeJson() throws Exception {
        ContextRuntimeSnapshot snapshot = new ContextRuntimeSnapshot(
                ContextRuntimeSnapshot.CURRENT_VERSION,
                Map.of("tool:mcp_web_search", "schema-hash"),
                List.of(new SkillInvocationRef("research", "skill-hash", 3L, 42)));
        when(repository.updateContextRuntimeJson("s1", mapper.writeValueAsString(snapshot)))
                .thenReturn(1);
        when(repository.findContextRuntimeJsonById("s1"))
                .thenReturn(Optional.of(mapper.writeValueAsString(snapshot)));

        store.save("s1", snapshot);

        assertThat(store.load("s1")).contains(snapshot);
        verify(repository).updateContextRuntimeJson(
                "s1", mapper.writeValueAsString(snapshot));
    }

    @Test
    void missingSessionFailsClosedInsteadOfPretendingCheckpointWasSaved() {
        when(repository.updateContextRuntimeJson(anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> store.save("missing", ContextRuntimeSnapshot.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Session not found");
    }

    @Test
    void corruptOrUnknownRuntimeLoadsAsEmptyInsteadOfRetainingFutureState() {
        when(repository.findContextRuntimeJsonById("corrupt"))
                .thenReturn(Optional.of("{not-json"));
        when(repository.findContextRuntimeJsonById("future"))
                .thenReturn(Optional.of("""
                        {"version":99,"discoveredToolSchemaHashes":{},"invokedSkills":[]}
                        """));

        assertThat(store.load("corrupt")).contains(ContextRuntimeSnapshot.empty());
        assertThat(store.load("future")).contains(ContextRuntimeSnapshot.empty());
    }
}
