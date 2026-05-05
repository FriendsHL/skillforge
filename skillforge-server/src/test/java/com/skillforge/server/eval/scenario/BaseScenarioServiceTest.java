package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ─── EVAL-V2 M2 multi-turn tests ────────────────────────────────────────

    @Test
    @DisplayName("addBaseScenario M2: multi-turn turns parsed and round-tripped through JSON file")
    @SuppressWarnings("unchecked")
    void addBaseScenario_multiTurn_writesAndReadsBack(@TempDir Path tmp) throws IOException {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-01");
        body.put("conversation_turns", List.of(
                Map.of("role", "user", "content", "Help debug NPE on line 42"),
                Map.of("role", "assistant", "content", "<placeholder>"),
                Map.of("role", "user", "content", "Tried that, still NPE.")
        ));

        String savedId = svc.addBaseScenario(body);

        assertThat(savedId).isEqualTo("sc-mt-01");
        Path file = tmp.resolve("sc-mt-01.json");
        Map<String, Object> parsed = objectMapper.readValue(file.toFile(), Map.class);
        Object roundTripped = parsed.get("conversation_turns");
        assertThat(roundTripped).isInstanceOf(List.class);
        List<Map<String, Object>> turns = (List<Map<String, Object>>) roundTripped;
        assertThat(turns).hasSize(3);
        assertThat(turns.get(0)).containsEntry("role", "user")
                .containsEntry("content", "Help debug NPE on line 42");
        assertThat(turns.get(1)).containsEntry("role", "assistant")
                .containsEntry("content", "<placeholder>");
        assertThat(turns.get(2)).containsEntry("role", "user");
    }

    @Test
    @DisplayName("addBaseScenario M2: camelCase 'conversationTurns' alias is accepted")
    @SuppressWarnings("unchecked")
    void addBaseScenario_multiTurn_camelCaseAlias(@TempDir Path tmp) throws IOException {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-camel");
        body.put("conversationTurns", List.of(
                Map.of("role", "user", "content", "first"),
                Map.of("role", "assistant", "content", "<placeholder>")
        ));

        svc.addBaseScenario(body);

        Map<String, Object> parsed = objectMapper.readValue(tmp.resolve("sc-mt-camel.json").toFile(), Map.class);
        // canonical on-disk key is snake_case
        assertThat(parsed).containsKey("conversation_turns");
        assertThat(parsed).doesNotContainKey("conversationTurns");
    }

    @Test
    @DisplayName("addBaseScenario M2: empty turns array → IllegalArgumentException")
    void addBaseScenario_multiTurn_emptyArray_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-empty");
        body.put("conversation_turns", List.of());

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("addBaseScenario M2: turns with no user message → IllegalArgumentException")
    void addBaseScenario_multiTurn_noUser_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-nouser");
        body.put("conversation_turns", List.of(
                Map.of("role", "assistant", "content", "<placeholder>")
        ));

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one user turn");
    }

    @Test
    @DisplayName("addBaseScenario M2: assistant turn with non-placeholder content → IllegalArgumentException")
    void addBaseScenario_multiTurn_assistantNotPlaceholder_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-asst");
        body.put("conversation_turns", List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "I baked you a real reply")
        ));

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("addBaseScenario M2: turn with unknown role → IllegalArgumentException")
    void addBaseScenario_multiTurn_unknownRole_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-role");
        body.put("conversation_turns", List.of(
                Map.of("role", "user", "content", "hi"),
                Map.of("role", "ghost", "content", "<placeholder>")
        ));

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    @DisplayName("addBaseScenario M2: malformed turn shape (string instead of object) → IllegalArgumentException")
    void addBaseScenario_multiTurn_malformedShape_rejected(@TempDir Path tmp) {
        BaseScenarioService svc = newService(tmp);
        Map<String, Object> body = validBody("sc-mt-bad");
        body.put("conversation_turns", List.of("not an object"));

        assertThatThrownBy(() -> svc.addBaseScenario(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an object");
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
