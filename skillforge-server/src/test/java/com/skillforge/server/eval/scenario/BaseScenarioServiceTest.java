package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EVAL-V2 Q2: contract tests for {@link BaseScenarioService}. Locks happy
 * path, validation failures (path traversal / missing required), and the
 * 409-conflict semantics required by the controller mapping.
 *
 * <p>Uses {@code @TempDir} as the home dir so tests don't touch the real
 * {@code ~/.skillforge/eval-scenarios/}.
 */
class BaseScenarioServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BaseScenarioService newService(Path tmpHome) {
        return new BaseScenarioService(objectMapper, tmpHome.toString());
    }

    private static Map<String, Object> validBody(String id) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (id != null) b.put("id", id);
        b.put("name", "demo case");
        b.put("task", "do the thing");
        return b;
    }

    @Test
    @DisplayName("addBaseScenario: writes JSON file at homeDir/<id>.json")
    void addBaseScenario_happyPath_writesFile(@TempDir Path tmp) throws IOException {
        BaseScenarioService svc = newService(tmp);

        String savedId = svc.addBaseScenario(validBody("sc-q2-01"));

        assertThat(savedId).isEqualTo("sc-q2-01");
        Path expected = tmp.resolve("sc-q2-01.json");
        assertThat(Files.exists(expected)).isTrue();
        // Round-trip: BE should be able to read what it wrote.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(expected.toFile(), Map.class);
        assertThat(parsed).containsEntry("id", "sc-q2-01")
                .containsEntry("name", "demo case")
                .containsEntry("task", "do the thing");
    }

    @Test
    @DisplayName("addBaseScenario: missing id auto-generates a UUID")
    void addBaseScenario_noId_autoGenerates(@TempDir Path tmp) throws IOException {
        BaseScenarioService svc = newService(tmp);

        String savedId = svc.addBaseScenario(validBody(null));

        assertThat(savedId).isNotBlank().hasSize(36); // UUID
        assertThat(Files.exists(tmp.resolve(savedId + ".json"))).isTrue();
    }

    @Test
    @DisplayName("addBaseScenario: rejects path-traversal id with IllegalArgumentException")
    void addBaseScenario_pathTraversal_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);

        assertThatThrownBy(() -> svc.addBaseScenario(validBody("../escape")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must match");
        assertThatThrownBy(() -> svc.addBaseScenario(validBody("/abs/path")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.addBaseScenario(validBody("..")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("addBaseScenario: missing name → IllegalArgumentException")
    void addBaseScenario_missingName_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-x");
        body.remove("name");

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("addBaseScenario: missing task → IllegalArgumentException")
    void addBaseScenario_missingTask_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-y");
        body.remove("task");

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
    }

    @Test
    @DisplayName("addBaseScenario: unknown oracle.type → IllegalArgumentException")
    void addBaseScenario_invalidOracle_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-z");
        body.put("oracle", Map.of("type", "made_up_oracle"));

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oracle.type");
    }

    @Test
    @DisplayName("addBaseScenario: existing file + force=false → ScenarioAlreadyExistsException")
    void addBaseScenario_conflict_withoutForce(@TempDir Path tmp) throws IOException {
        BaseScenarioService svc = newService(tmp);
        svc.addBaseScenario(validBody("sc-dup"));

        assertThatThrownBy(() -> svc.addBaseScenario(validBody("sc-dup")))
                .isInstanceOf(BaseScenarioService.ScenarioAlreadyExistsException.class)
                .hasMessageContaining("sc-dup");
    }

    @Test
    @DisplayName("addBaseScenario: force=true overwrites existing file")
    void addBaseScenario_force_overwrites(@TempDir Path tmp) throws IOException {
        BaseScenarioService svc = newService(tmp);
        svc.addBaseScenario(validBody("sc-force"));
        Path file = tmp.resolve("sc-force.json");
        assertThat(Files.exists(file)).isTrue();

        Map<String, Object> updated = validBody("sc-force");
        updated.put("task", "updated task text");
        String savedId = svc.addBaseScenario(updated, true);

        assertThat(savedId).isEqualTo("sc-force");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(file.toFile(), Map.class);
        assertThat(parsed).containsEntry("task", "updated task text");
    }
}
