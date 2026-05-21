package com.skillforge.server.migration;

import com.skillforge.server.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KILL-BOOTSTRAP-PROMPT-TO-DB (2026-05-22) post-condition check for V95:
 * 6 system agents have inline-seeded {@code system_prompt} content in
 * {@code t_agent} (no more {@code 'SEE_FILE:...md'} sentinel).
 *
 * <p>Before V95 the V69/V75/V79/V81/V85/V93 seed migrations stored a
 * {@code 'SEE_FILE:<resource>.md'} sentinel in {@code system_prompt} and
 * a per-agent {@code *Bootstrap} @EventListener swapped the prompt from
 * classpath on startup. V95 (this migration) inlines the actual prompt
 * content via Postgres dollar-quoted strings, dropping the bootstrap
 * indirection — promote writes are now permanent (no swap-on-boot
 * overwrite) and operator edits propagate without server restart.
 *
 * <p>Assertions (per agent):
 * <ol>
 *   <li>{@code system_prompt} is non-null + non-empty</li>
 *   <li>{@code system_prompt} does NOT start with {@code 'SEE_FILE:'}</li>
 *   <li>{@code system_prompt} contains an agent-specific marker fragment
 *       so we know V95 wrote the right content (catches accidental
 *       cross-agent UPDATE shuffling)</li>
 * </ol>
 *
 * <p>Gated by {@code -Dskillforge.runMigrationIT=true} per the existing
 * migration IT convention.
 */
@DisplayName("V95 — system agent prompts inline-seeded (KILL-BOOTSTRAP-PROMPT-TO-DB)")
@EnabledIf(expression = "#{systemProperties['skillforge.runMigrationIT'] == 'true'}",
        reason = "Run migration ITs only when explicitly requested")
class V95SeedSystemAgentPromptsIT extends AbstractPostgresIT {

    @PersistenceContext
    private EntityManager entityManager;

    private record AgentExpectation(String agentName, String requiredMarker) { }

    /**
     * One required content-marker per agent. Markers were picked to be
     * specific enough that a cross-agent shuffle (e.g. attribution-curator
     * prompt accidentally landing on session-annotator) would fail. We don't
     * assert exact character counts because the prompt content is the
     * Postgres-side source of truth and may be tweaked via PromptPromotion
     * after V95 — the migration's job is only to seed initial content
     * cleanly, not to freeze it.
     */
    private static final List<AgentExpectation> EXPECTATIONS = List.of(
            // r2 N1 fix (java-reviewer): use a marker that only appears in this
            // agent's prompt. "attribution-curator" literal occurs in the
            // dispatcher prompt too (as agentName="attribution-curator" arg to
            // SubAgent), so the curator row weakly false-PASSes a shuffle.
            // "ProposeOptimization" is the curator's STEP 4 tool, exclusive.
            new AgentExpectation("attribution-curator", "ProposeOptimization"),
            new AgentExpectation("attribution-dispatcher", "ListAttributionCandidates"),
            new AgentExpectation("memory-curator", "memory-curator"),
            new AgentExpectation("session-annotator", "DetectSignalAnnotations"),
            new AgentExpectation("metrics-collector", "RecomputeMetrics"),
            new AgentExpectation("user-simulator", "RecordSimulationResult")
    );

    @Test
    @DisplayName("all 6 system agents have non-SEE_FILE system_prompt with > 200 chars")
    void allSystemAgentPromptsInlined() {
        for (AgentExpectation exp : EXPECTATIONS) {
            String prompt = loadSystemPrompt(exp.agentName());
            assertThat(prompt)
                    .as("%s.system_prompt must be non-null after V95", exp.agentName())
                    .isNotNull();
            assertThat(prompt.trim())
                    .as("%s.system_prompt must be non-empty after V95", exp.agentName())
                    .isNotEmpty();
            assertThat(prompt)
                    .as("%s.system_prompt must NOT start with SEE_FILE: sentinel after V95 "
                            + "(KILL-BOOTSTRAP-PROMPT-TO-DB)", exp.agentName())
                    .doesNotStartWith("SEE_FILE:");
            // 200-char lower bound is much smaller than the smallest prompt
            // (metrics-collector ~1.6KB). Catches an empty-body $prompt$$prompt$
            // accidental misquoting.
            assertThat(prompt.length())
                    .as("%s.system_prompt must contain real content (got %d chars)",
                            exp.agentName(), prompt.length())
                    .isGreaterThan(200);
            assertThat(prompt)
                    .as("%s.system_prompt must contain expected marker '%s' — "
                            + "guards against cross-agent UPDATE shuffling in V95",
                            exp.agentName(), exp.requiredMarker())
                    .contains(exp.requiredMarker());
        }
    }

    @Test
    @DisplayName("r2 W1: every SystemAgentNames row really has agent_type='system' (Bootstrap self-heal gone — runtime guard 已删除，本测 enforce 'new system agent seed 必须显式 agent_type=system' 协议)")
    void allSystemAgentNamesRowsTypedSystem() {
        // KILL-BOOTSTRAP-PROMPT-TO-DB r2 W1 fix (java-reviewer):
        // Bootstrap.swapSystemPromptOnBoot 之前会 self-heal agent_type='system'
        // 作为 defense-in-depth。删 Bootstrap 后这道 runtime 保护没了 — 未来
        // 加新 system agent 时 dev 必须在 INSERT/UPDATE 显式 set
        // agent_type='system'，否则 row 默认 'user'，导致 system-agent
        // 路径 (e.g. SessionAnnotationSignalService 3-tier filter) 错分类。
        // 本测 query 整 t_agent 表 vs SystemAgentNames 期望 set，任一漏列
        // agent_type='system' 立即 FAIL。
        List<String> expectedSystemAgents = List.of(
                "memory-curator", "session-annotator", "metrics-collector",
                "attribution-curator", "user-simulator", "attribution-dispatcher");
        @SuppressWarnings("unchecked")
        List<Object[]> mistyped = entityManager.createNativeQuery("""
                SELECT name, agent_type
                  FROM t_agent
                 WHERE name IN (:names)
                   AND agent_type != 'system'
                """)
                .setParameter("names", expectedSystemAgents)
                .getResultList();
        assertThat(mistyped)
                .as("Every name in SystemAgentNames must have agent_type='system'. "
                        + "Mistyped rows (likely a future V96+ seeded system agent that forgot "
                        + "to set agent_type='system' explicitly — Bootstrap self-heal 没了): %s",
                        mistyped)
                .isEmpty();
    }

    @Test
    @DisplayName("no system_prompt across t_agent still carries 'SEE_FILE:' sentinel")
    void noSeeFileSentinelLeftInTable() {
        @SuppressWarnings("unchecked")
        List<Object[]> stragglers = entityManager.createNativeQuery("""
                SELECT name, LEFT(system_prompt, 80) AS preview
                  FROM t_agent
                 WHERE system_prompt LIKE 'SEE_FILE:%'
                """).getResultList();

        assertThat(stragglers)
                .as("V95 should have replaced every 'SEE_FILE:%%' sentinel; remaining "
                        + "rows indicate either a missed agent name or a SEE_FILE-shaped "
                        + "value seeded by a later migration")
                .isEmpty();
    }

    private String loadSystemPrompt(String agentName) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT name, system_prompt
                  FROM t_agent
                 WHERE name = :name
                """)
                .setParameter("name", agentName)
                .getResultList();
        assertThat(rows)
                .as("Expected exactly 1 row in t_agent for system agent %s", agentName)
                .hasSize(1);
        Object[] row = rows.get(0);
        return (String) row[1];
    }
}
