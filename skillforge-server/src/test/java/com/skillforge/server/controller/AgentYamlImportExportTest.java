package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.exception.AgentNotFoundException;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.AgentYamlMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the two new {@code /api/agents/import} + {@code /api/agents/{id}/export}
 * endpoints on {@link AgentController}. Pure Mockito; no Spring context.
 */
class AgentYamlImportExportTest {

    private static final String SAMPLE_YAML =
            "name: Round Trip\n" +
            "description: A test agent.\n" +
            "modelId: deepseek-chat\n" +
            "executionMode: auto\n" +
            "public: false\n" +
            "systemPrompt: |\n" +
            "  You are a helpful bot.\n" +
            "skills:\n" +
            "  - Bash\n" +
            "  - Read\n";

    @Test
    void importParsesYamlAndDelegatesToService() {
        AgentService svc = mock(AgentService.class);
        AtomicLong idSeq = new AtomicLong(100);
        when(svc.createAgent(any(AgentEntity.class))).thenAnswer(inv -> {
            AgentEntity a = inv.getArgument(0);
            a.setId(idSeq.incrementAndGet());
            return a;
        });

        AgentController controller = new AgentController(svc);
        ResponseEntity<?> resp = controller.importAgent(SAMPLE_YAML);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AgentEntity saved = (AgentEntity) resp.getBody();
        assertThat(saved).isNotNull();
        assertThat(saved.getName()).isEqualTo("Round Trip");
        assertThat(saved.getModelId()).isEqualTo("deepseek-chat");
        assertThat(saved.getExecutionMode()).isEqualTo("auto");
        assertThat(saved.isPublic()).isFalse();
        assertThat(saved.getSkillIds()).contains("Bash").contains("Read");
        assertThat(saved.getSystemPrompt()).contains("helpful bot");
    }

    @Test
    void importReturns400OnInvalidYaml() {
        AgentService svc = mock(AgentService.class);
        AgentController controller = new AgentController(svc);
        ResponseEntity<?> resp = controller.importAgent("::not valid yaml::\n  - [unbalanced");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void importReturns400WhenNameMissing() {
        AgentService svc = mock(AgentService.class);
        AgentController controller = new AgentController(svc);
        ResponseEntity<?> resp = controller.importAgent("modelId: x\nskills:\n  - Bash\n");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exportReturnsYamlMatchingSchema() {
        AgentEntity stored = new AgentEntity();
        stored.setId(5L);
        stored.setName("Round Trip");
        stored.setDescription("A test agent.");
        stored.setModelId("deepseek-chat");
        stored.setExecutionMode("auto");
        stored.setPublic(false);
        stored.setSystemPrompt("You are a helpful bot.\n");
        stored.setSkillIds("[\"Bash\",\"Read\"]");

        AgentService svc = mock(AgentService.class);
        when(svc.getAgent(5L)).thenReturn(stored);

        AgentController controller = new AgentController(svc);
        ResponseEntity<String> resp = controller.exportAgent(5L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String yaml = resp.getBody();
        assertThat(yaml).contains("name: Round Trip");
        assertThat(yaml).contains("modelId: deepseek-chat");
        assertThat(yaml).contains("- Bash");
        assertThat(yaml).contains("- Read");
        // must not expose the raw json-string skillIds field
        assertThat(yaml).doesNotContain("skillIds");
    }

    @Test
    void exportReturns404WhenAgentMissing() {
        AgentService svc = mock(AgentService.class);
        when(svc.getAgent(999L)).thenThrow(new AgentNotFoundException(999L));
        AgentController controller = new AgentController(svc);
        ResponseEntity<String> resp = controller.exportAgent(999L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void yamlMapperRoundTripsEntity() {
        AgentEntity a = new AgentEntity();
        a.setName("X");
        a.setModelId("m");
        a.setExecutionMode("ask");
        a.setPublic(true);
        a.setSystemPrompt("Hello\n");
        a.setSkillIds("[\"Bash\",\"Grep\"]");

        String yaml = AgentYamlMapper.toYaml(a);
        AgentEntity parsed = AgentYamlMapper.fromYaml(yaml);

        assertThat(parsed.getName()).isEqualTo("X");
        assertThat(parsed.getModelId()).isEqualTo("m");
        assertThat(parsed.getExecutionMode()).isEqualTo("ask");
        assertThat(parsed.isPublic()).isTrue();
        assertThat(parsed.getSkillIds()).contains("Bash").contains("Grep");
    }

    /**
     * Highest-value invariant test for the import/export feature: a YAML
     * payload imported, then exported, then re-imported must produce a
     * second entity that is field-for-field equal to the first. This catches
     * any asymmetric drift in either direction (e.g., a field that imports
     * but doesn't export, or one that the YAML emitter quotes differently
     * the second time around).
     */
    @Test
    void controllerLevelImportExportImportRoundTrip() {
        AgentService svc = mock(AgentService.class);
        // Stub: createAgent assigns an id and stashes the entity for later
        // getAgent retrieval. Simulates a real persistence round-trip.
        java.util.Map<Long, AgentEntity> store = new java.util.HashMap<>();
        AtomicLong idSeq = new AtomicLong(0);
        when(svc.createAgent(any(AgentEntity.class))).thenAnswer(inv -> {
            AgentEntity a = inv.getArgument(0);
            a.setId(idSeq.incrementAndGet());
            store.put(a.getId(), a);
            return a;
        });
        when(svc.getAgent(any(Long.class))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            AgentEntity a = store.get(id);
            if (a == null) throw new RuntimeException("not found: " + id);
            return a;
        });

        AgentController controller = new AgentController(svc);

        // 1. Import the original YAML
        ResponseEntity<?> firstResp = controller.importAgent(SAMPLE_YAML);
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AgentEntity first = (AgentEntity) firstResp.getBody();
        assertThat(first).isNotNull();
        assertThat(first.getId()).isNotNull();

        // 2. Export it back to YAML
        ResponseEntity<String> exportResp = controller.exportAgent(first.getId());
        assertThat(exportResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String roundTripYaml = exportResp.getBody();
        assertThat(roundTripYaml).isNotNull();
        // Sanity: exported yaml uses the friendly skills: list, never the raw json string
        assertThat(roundTripYaml).contains("- Bash");
        assertThat(roundTripYaml).contains("- Read");

        // 3. Re-import the exported YAML
        ResponseEntity<?> secondResp = controller.importAgent(roundTripYaml);
        assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AgentEntity second = (AgentEntity) secondResp.getBody();
        assertThat(second).isNotNull();

        // 4. Field-by-field equality on every user-visible field. id differs
        // (each createAgent assigns a new one), and ownerId/createdAt/updatedAt
        // are persistence-managed so we don't assert them.
        assertThat(second.getName()).isEqualTo(first.getName());
        assertThat(second.getDescription()).isEqualTo(first.getDescription());
        assertThat(second.getModelId()).isEqualTo(first.getModelId());
        assertThat(second.getExecutionMode()).isEqualTo(first.getExecutionMode());
        assertThat(second.isPublic()).isEqualTo(first.isPublic());
        assertThat(second.getSystemPrompt()).isEqualTo(first.getSystemPrompt());
        // skillIds is the JSON-encoded TEXT column. The exact byte form
        // (e.g., the order of escapes) must round-trip stably.
        assertThat(second.getSkillIds()).isEqualTo(first.getSkillIds());
    }
}
