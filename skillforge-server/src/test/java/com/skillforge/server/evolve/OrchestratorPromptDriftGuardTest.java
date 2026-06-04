package com.skillforge.server.evolve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-M2b drift guard (design W3): V144 re-sets the evolve-orchestrator
 * {@code system_prompt} (supersedes V140). To keep "zero accidental drift in the
 * copied body" a CI invariant rather than a one-time eyeball, this test asserts
 * that the V144 prompt body equals the V140 prompt body PLUS exactly the intended
 * additions:
 * <ul>
 *   <li>the new {@code 第零步} discovery section (call ListActiveHarvestedScenarios
 *       → targetScenarioIds), and</li>
 *   <li>the two TriggerAbEval lines (b1 / dN) each gaining the same
 *       {@code evalScenarioIds=targetScenarioIds} clause.</li>
 * </ul>
 * Strip those known additions from V144 and the remainder must be byte-identical
 * to V140 — so any future supersede that silently changes an unrelated line turns
 * this test red.
 */
class OrchestratorPromptDriftGuardTest {

    /** The exact clause appended to the b1 + dN TriggerAbEval lines (appears twice). */
    private static final String TRIGGER_CLAUSE =
            "**, 且 targetScenarioIds 非空时额外带 evalScenarioIds=targetScenarioIds**";

    private static String migration(String file) throws Exception {
        try (InputStream in = OrchestratorPromptDriftGuardTest.class.getResourceAsStream(
                "/db/migration/" + file)) {
            assertThat(in).as("%s must be on the classpath", file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /** Extract the prompt body between the first and last {@code $SEED$} delimiters. */
    private static String seedBody(String sql) {
        int first = sql.indexOf("$SEED$");
        int last = sql.lastIndexOf("$SEED$");
        assertThat(first).as("opening $SEED$").isGreaterThanOrEqualTo(0);
        assertThat(last).as("closing $SEED$").isGreaterThan(first);
        return sql.substring(first + "$SEED$".length(), last);
    }

    @Test
    @DisplayName("V144 prompt body = V140 body + (第零步 section + two evalScenarioIds clauses), nothing else")
    void v144_isV140PlusOnlyTheIntendedAdditions() throws Exception {
        String v140Body = seedBody(migration("V140__evolve_orchestrator_agent_bundle_skill.sql"));
        String v144Body = seedBody(migration("V144__evolve_orchestrator_harvested_target.sql"));

        // 1) remove the inserted 第零步 section (from its header up to 第一步).
        int zeroStart = v144Body.indexOf("== 第零步");
        int firstStepStart = v144Body.indexOf("== 第一步");
        assertThat(zeroStart).as("V144 must contain the 第零步 section").isGreaterThanOrEqualTo(0);
        assertThat(firstStepStart).as("V144 must keep the 第一步 section").isGreaterThan(zeroStart);
        String stripped = v144Body.substring(0, zeroStart) + v144Body.substring(firstStepStart);

        // 2) remove the two identical TriggerAbEval clauses (b1 + dN).
        assertThat(countOccurrences(stripped, TRIGGER_CLAUSE))
                .as("the evalScenarioIds clause must appear exactly twice (b1 + dN)")
                .isEqualTo(2);
        stripped = stripped.replace(TRIGGER_CLAUSE, "");

        // 3) what remains must be byte-identical to V140's body — zero other drift.
        assertThat(stripped)
                .as("V144 prompt body drifted from V140 beyond the intended additions")
                .isEqualTo(v140Body);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
