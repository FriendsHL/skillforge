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

    @Test
    @DisplayName("V145 prompt body = V144 body + (G3 第二步补充 section), nothing else")
    void v145_isV144PlusOnlyTheG3Section() throws Exception {
        String v144Body = seedBody(migration("V144__evolve_orchestrator_harvested_target.sql"));
        String v145Body = seedBody(migration("V145__evolve_orchestrator_prediction_reconcile.sql"));

        // Remove the inserted G3 section (from its header up to the 逐轮迭代 step).
        // NOTE: the G3 header also starts with "== 第二步" (第二步补充), so the boundary
        // must be the more specific "== 第二步：逐轮迭代" marker.
        int g3Start = v145Body.indexOf("== 第二步补充（G3");
        int stepStart = v145Body.indexOf("== 第二步：逐轮迭代");
        assertThat(g3Start).as("V145 must contain the G3 第二步补充 section").isGreaterThanOrEqualTo(0);
        assertThat(stepStart).as("V145 must keep the 逐轮迭代 step").isGreaterThan(g3Start);
        String stripped = v145Body.substring(0, g3Start) + v145Body.substring(stepStart);

        assertThat(stripped)
                .as("V145 prompt body drifted from V144 beyond the intended G3 addition")
                .isEqualTo(v144Body);
    }

    /** Extract the JSON array literal from a migration's {@code tool_ids = '...'} assignment. */
    private static String toolIdsLiteral(String sql) {
        int i = sql.indexOf("tool_ids = '");
        assertThat(i).as("migration must set tool_ids").isGreaterThanOrEqualTo(0);
        int start = i + "tool_ids = '".length();
        int end = sql.indexOf("'", start);
        return sql.substring(start, end);
    }

    @Test
    @DisplayName("regression guard (V144 dropped GetOptReport): every tool the prompt instructs the orchestrator to CALL is present in the latest tool_ids")
    void everyPromptInvokedToolIsInLatestToolIds() throws Exception {
        // V144 rewrote tool_ids to add ListActiveHarvestedScenarios but silently dropped
        // GetOptReport, so the orchestrator could never call the tool its prompt told it to
        // use to read the opt-report — it looped RunWorkflow and every run failed at step 1.
        // V146 restores it. This invariant would have caught the regression in CI.
        String toolIds = toolIdsLiteral(
                migration("V146__evolve_orchestrator_restore_getoptreport_toolid.sql"));
        String prompt = seedBody(migration("V145__evolve_orchestrator_prediction_reconcile.sql"));

        assertThat(toolIds)
                .as("GetOptReport must be in the orchestrator tool_ids (V144 dropped it)")
                .contains("\"GetOptReport\"");

        // General invariant: any tool the prompt tells the orchestrator to CALL ("Name(")
        // must be in its allowlist — otherwise the model physically cannot invoke it.
        for (String tool : new String[]{
                "RunWorkflow", "GetOptReport", "GenerateCandidate", "TriggerAbEval",
                "GetAbResult", "RecordIteration", "ListActiveHarvestedScenarios", "ReconcilePrediction"}) {
            if (prompt.contains(tool + "(")) {
                assertThat(toolIds)
                        .as("prompt instructs calling %s( but it is missing from tool_ids", tool)
                        .contains("\"" + tool + "\"");
            }
        }
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
