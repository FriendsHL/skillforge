package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EVAL-V2 Q2: locks {@link ScenarioLoader} dual-path semantics — classpath
 * seeds + home dir scenarios merge, classpath wins on id collision, and
 * {@link EvalScenario#getSource()} is set so the UI can label entries.
 *
 * <p>The test uses {@code @TempDir} as the home dir (via the
 * {@code skillforge.eval.base-scenarios-dir} override on
 * {@link BaseScenarioService}) and an empty {@link EvalScenarioDraftRepository}
 * mock to focus on the file-system half of the loader.
 */
class ScenarioLoaderHomeDirTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EvalScenarioDraftRepository draftRepository;

    @BeforeEach
    void setUp() {
        draftRepository = mock(EvalScenarioDraftRepository.class);
        when(draftRepository.findByStatus("active")).thenReturn(Collections.emptyList());
    }

    private static void writeHomeScenario(Path homeDir, String id, String name, String task) throws IOException {
        Map<String, Object> body = Map.of(
                "id", id,
                "name", name,
                "task", task);
        Files.createDirectories(homeDir);
        Files.write(homeDir.resolve(id + ".json"),
                new ObjectMapper().writeValueAsBytes(body));
    }

    @Test
    @DisplayName("loadAll: home-dir scenarios are loaded with source=home")
    void loadAll_homeDir_includedWithSourceTag(@TempDir Path tmp) throws IOException {
        writeHomeScenario(tmp, "sc-home-1", "Home one", "task one");

        BaseScenarioService baseScenarioService = new BaseScenarioService(objectMapper, tmp.toString());
        ScenarioLoader loader = new ScenarioLoader(objectMapper, draftRepository, baseScenarioService);

        List<EvalScenario> scenarios = loader.loadAll();

        EvalScenario homeScn = scenarios.stream()
                .filter(s -> "sc-home-1".equals(s.getId()))
                .findFirst()
                .orElse(null);
        assertThat(homeScn).isNotNull();
        assertThat(homeScn.getSource()).isEqualTo(EvalScenario.SOURCE_HOME);
        assertThat(homeScn.getName()).isEqualTo("Home one");
    }

    @Test
    @DisplayName("loadAll: classpath wins on id collision; home shadow ignored")
    void loadAll_classpathWinsOnCollision(@TempDir Path tmp) throws IOException {
        // sc-bs-01 is a real classpath seed (see seed_sc-bs-01.json). Write a
        // home-dir scenario with the same id but a different name; the loader
        // should keep the classpath copy and discard the home shadow.
        writeHomeScenario(tmp, "sc-bs-01", "shadowed", "shadowed task");

        BaseScenarioService baseScenarioService = new BaseScenarioService(objectMapper, tmp.toString());
        ScenarioLoader loader = new ScenarioLoader(objectMapper, draftRepository, baseScenarioService);

        List<EvalScenario> scenarios = loader.loadAll();

        EvalScenario sc = scenarios.stream()
                .filter(s -> "sc-bs-01".equals(s.getId()))
                .findFirst()
                .orElse(null);
        assertThat(sc).isNotNull();
        // Classpath name from seed_sc-bs-01.json is "Simple file read"
        assertThat(sc.getName()).isEqualTo("Simple file read");
        assertThat(sc.getSource()).isEqualTo(EvalScenario.SOURCE_CLASSPATH);
    }

    @Test
    @DisplayName("loadBaseScenarios: only classpath + home (no DB query)")
    void loadBaseScenarios_excludesDb(@TempDir Path tmp) throws IOException {
        writeHomeScenario(tmp, "sc-home-base", "Base scn", "task base");

        BaseScenarioService baseScenarioService = new BaseScenarioService(objectMapper, tmp.toString());
        ScenarioLoader loader = new ScenarioLoader(objectMapper, draftRepository, baseScenarioService);

        List<EvalScenario> scenarios = loader.loadBaseScenarios();

        // Should include our home-dir scenario plus all classpath seeds, none of which are 'db'-sourced.
        assertThat(scenarios).anySatisfy(s -> {
            assertThat(s.getId()).isEqualTo("sc-home-base");
            assertThat(s.getSource()).isEqualTo(EvalScenario.SOURCE_HOME);
        });
        assertThat(scenarios)
                .extracting(EvalScenario::getSource)
                .doesNotContain(EvalScenario.SOURCE_DB);
        // DB query path must not be touched.
        org.mockito.Mockito.verifyNoInteractions(draftRepository);
    }
}
