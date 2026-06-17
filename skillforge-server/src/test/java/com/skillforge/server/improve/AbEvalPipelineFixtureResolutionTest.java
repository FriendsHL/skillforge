package com.skillforge.server.improve;

import com.skillforge.server.eval.scenario.EvalScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-M1: DB-persisted {@code fixtureFiles} (session_derived harvested scenarios)
 * take priority over disk {@code setup.files} (benchmark scenarios); benchmark
 * scenarios still fall back to setup.files.
 */
class AbEvalPipelineFixtureResolutionTest {

    private EvalScenario withSetupFiles(Map<String, String> setupFiles) {
        EvalScenario s = new EvalScenario();
        EvalScenario.ScenarioSetup setup = new EvalScenario.ScenarioSetup();
        setup.setFiles(setupFiles);
        s.setSetup(setup);
        return s;
    }

    @Test
    @DisplayName("DB fixtureFiles take priority over setup.files")
    void dbFixturesWinOverSetupFiles() {
        EvalScenario s = withSetupFiles(Map.of("disk.txt", "disk"));
        s.setFixtureFiles(Map.of("db.txt", "db"));

        Map<String, String> resolved = AbEvalPipeline.resolveFixtureFiles(s);

        assertThat(resolved).containsExactlyEntriesOf(Map.of("db.txt", "db"));
    }

    @Test
    @DisplayName("falls back to setup.files when DB fixtureFiles is null/empty")
    void fallsBackToSetupFiles() {
        EvalScenario s = withSetupFiles(Map.of("disk.txt", "disk"));
        s.setFixtureFiles(null);

        Map<String, String> resolved = AbEvalPipeline.resolveFixtureFiles(s);

        assertThat(resolved).containsExactlyEntriesOf(Map.of("disk.txt", "disk"));

        s.setFixtureFiles(Map.of());
        assertThat(AbEvalPipeline.resolveFixtureFiles(s))
                .containsExactlyEntriesOf(Map.of("disk.txt", "disk"));
    }

    @Test
    @DisplayName("returns null when neither source has files")
    void returnsNullWhenNoFixtures() {
        EvalScenario s = new EvalScenario();
        assertThat(AbEvalPipeline.resolveFixtureFiles(s)).isNull();
    }
}
