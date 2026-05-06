package com.skillforge.server.service;

import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalScenarioVersionService")
class EvalScenarioVersionServiceTest {

    @Mock
    private EvalScenarioDraftRepository repository;

    private EvalScenarioVersionService service;

    @BeforeEach
    void setUp() {
        service = new EvalScenarioVersionService(repository);
    }

    @Test
    @DisplayName("listLatestScenarios returns newest version per scenario family")
    void listLatestScenarios_returnsNewestVersionPerFamily() {
        EvalScenarioEntity root = scenario("scn-v1", "7", 1, null, "Alpha v1", Instant.parse("2026-05-06T01:00:00Z"));
        EvalScenarioEntity latest = scenario("scn-v2", "7", 2, "scn-v1", "Alpha v2", Instant.parse("2026-05-06T02:00:00Z"));
        EvalScenarioEntity sibling = scenario("scn-b1", "7", 1, null, "Beta v1", Instant.parse("2026-05-06T03:00:00Z"));
        when(repository.findByAgentIdOrderByCreatedAtDesc("7"))
                .thenReturn(List.of(sibling, latest, root));

        List<EvalScenarioEntity> rows = service.listLatestScenarios("7");

        assertThat(rows).extracting(EvalScenarioEntity::getId)
                .containsExactly("scn-b1", "scn-v2");
    }

    @Test
    @DisplayName("listVersions returns the full family ordered by version desc")
    void listVersions_returnsFamilyOrderedByVersionDesc() {
        EvalScenarioEntity root = scenario("scn-v1", "7", 1, null, "Alpha v1", Instant.parse("2026-05-06T01:00:00Z"));
        EvalScenarioEntity mid = scenario("scn-v2", "7", 2, "scn-v1", "Alpha v2", Instant.parse("2026-05-06T02:00:00Z"));
        EvalScenarioEntity latest = scenario("scn-v3", "7", 3, "scn-v2", "Alpha v3", Instant.parse("2026-05-06T03:00:00Z"));
        EvalScenarioEntity other = scenario("other-v1", "7", 1, null, "Beta v1", Instant.parse("2026-05-06T04:00:00Z"));
        when(repository.findById("scn-v2")).thenReturn(Optional.of(mid));
        when(repository.findByAgentIdOrderByCreatedAtDesc("7"))
                .thenReturn(List.of(other, latest, mid, root));

        List<EvalScenarioEntity> rows = service.listVersions("scn-v2");

        assertThat(rows).extracting(EvalScenarioEntity::getId)
                .containsExactly("scn-v3", "scn-v2", "scn-v1");
    }

    @Test
    @DisplayName("createVersion clones scenario fields and increments version")
    void createVersion_clonesScenarioAndIncrementsVersion() {
        EvalScenarioEntity source = scenario("scn-v2", "7", 2, "scn-v1", "Alpha v2", Instant.parse("2026-05-06T02:00:00Z"));
        source.setDescription("old desc");
        source.setTask("old task");
        source.setOracleExpected("old expected");
        source.setConversationTurns("[{\"role\":\"user\",\"content\":\"hello\"}]");

        EvalScenarioEntity root = scenario("scn-v1", "7", 1, null, "Alpha v1", Instant.parse("2026-05-06T01:00:00Z"));
        when(repository.findById("scn-v2")).thenReturn(Optional.of(source));
        when(repository.findByAgentIdOrderByCreatedAtDesc("7"))
                .thenReturn(List.of(source, root));
        when(repository.save(any(EvalScenarioEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvalScenarioEntity created = service.createVersion("scn-v2", Map.of(
                "name", "Alpha v3",
                "oracleExpected", "new expected"
        ));

        assertThat(created.getId()).isNotBlank().isNotEqualTo("scn-v2");
        assertThat(created.getParentScenarioId()).isEqualTo("scn-v2");
        assertThat(created.getVersion()).isEqualTo(3);
        assertThat(created.getName()).isEqualTo("Alpha v3");
        assertThat(created.getTask()).isEqualTo("old task");
        assertThat(created.getOracleExpected()).isEqualTo("new expected");
        assertThat(created.getStatus()).isEqualTo("active");
        assertThat(created.getReviewedAt()).isNotNull();

        ArgumentCaptor<EvalScenarioEntity> captor = ArgumentCaptor.forClass(EvalScenarioEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getParentScenarioId()).isEqualTo("scn-v2");
    }

    private static EvalScenarioEntity scenario(String id,
                                               String agentId,
                                               int version,
                                               String parentScenarioId,
                                               String name,
                                               Instant createdAt) {
        EvalScenarioEntity entity = new EvalScenarioEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setVersion(version);
        entity.setParentScenarioId(parentScenarioId);
        entity.setName(name);
        entity.setTask("task");
        entity.setOracleType("llm_judge");
        entity.setStatus("active");
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
