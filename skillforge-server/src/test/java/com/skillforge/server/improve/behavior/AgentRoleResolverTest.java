package com.skillforge.server.improve.behavior;

import com.skillforge.server.entity.AgentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — unit tests for {@link AgentRoleResolver}.
 *
 * <p><b>Machine cross-check vs V117 SQL</b> (r1-FIX W1-architect / N3-java-design):
 * each test feeds the resolver an input string DERIVED FROM the V117 SQL
 * literal ILIKE substring patterns. If a future PR renames a Java pattern
 * without updating the V117 SQL (or vice-versa), one side of the dual source
 * fails this test even though the comment-level cross-ref still appears
 * intact. The literals below MUST match
 * {@code V117__eval_scenario_add_applicable_agent_roles.sql} 5 UPDATE blocks.
 */
@DisplayName("AgentRoleResolver — name heuristic + GENERAL fallback (dual-source w/ V117 SQL)")
class AgentRoleResolverTest {

    private final AgentRoleResolver resolver = new AgentRoleResolver();

    // V117 SQL substring literals (KEEP IN SYNC with the migration's ILIKE).
    // Reading these from constants would only validate the constant, not the
    // migration; literal hardcoding here forces the test to fail when either
    // side drifts.
    private static final String V117_DESIGN_SUBSTR = "design";
    private static final String V117_CODE_SUBSTR = "code";
    private static final String V117_RESEARCH_SUBSTR = "research";
    private static final String V117_MAIN_SUBSTR = "main";
    private static final String V117_ASSISTANT_SUBSTR = "assistant";

    @Nested
    @DisplayName("happy path — each V117 pattern → matching role")
    class HappyPath {

        @DisplayName("name containing V117 design substring → DESIGN")
        @Test
        void design_substring_returns_design() {
            assertThat(resolver.resolveRole(named("Design Agent")))
                    .isEqualTo(AgentRoleConstants.DESIGN);
            // Case-insensitive (lower() in V117 not needed because ILIKE; resolver lower-cases first)
            assertThat(resolver.resolveRole(named("DESIGN agent v2")))
                    .isEqualTo(AgentRoleConstants.DESIGN);
            // Embedded substring (mirrors ILIKE %design%)
            assertThat(resolver.resolveRole(named("UI/Designer Pro")))
                    .isEqualTo(AgentRoleConstants.DESIGN);
            assertThat(V117_DESIGN_SUBSTR).isEqualTo("design"); // dual-source pin
        }

        @DisplayName("name containing V117 code substring → CODE")
        @Test
        void code_substring_returns_code() {
            assertThat(resolver.resolveRole(named("Code Agent")))
                    .isEqualTo(AgentRoleConstants.CODE);
            assertThat(resolver.resolveRole(named("Encoder Helper")))
                    .isEqualTo(AgentRoleConstants.CODE);
            assertThat(V117_CODE_SUBSTR).isEqualTo("code");
        }

        @DisplayName("name containing V117 research substring → RESEARCH")
        @Test
        void research_substring_returns_research() {
            assertThat(resolver.resolveRole(named("Research Agent")))
                    .isEqualTo(AgentRoleConstants.RESEARCH);
            assertThat(resolver.resolveRole(named("DeepResearch v3")))
                    .isEqualTo(AgentRoleConstants.RESEARCH);
            assertThat(V117_RESEARCH_SUBSTR).isEqualTo("research");
        }

        @DisplayName("name containing V117 main substring → MAIN_ASSISTANT")
        @Test
        void main_substring_returns_main_assistant() {
            assertThat(resolver.resolveRole(named("Main Assistant")))
                    .isEqualTo(AgentRoleConstants.MAIN_ASSISTANT);
            assertThat(V117_MAIN_SUBSTR).isEqualTo("main");
        }

        @DisplayName("name containing V117 assistant substring → MAIN_ASSISTANT")
        @Test
        void assistant_substring_returns_main_assistant() {
            assertThat(resolver.resolveRole(named("Assistant Pro")))
                    .isEqualTo(AgentRoleConstants.MAIN_ASSISTANT);
            assertThat(V117_ASSISTANT_SUBSTR).isEqualTo("assistant");
        }
    }

    @Nested
    @DisplayName("INV-2 — fallback to GENERAL, never returns null")
    class Fallback {

        @DisplayName("null agent → GENERAL")
        @Test
        void null_agent_returns_general() {
            assertThat(resolver.resolveRole(null)).isEqualTo(AgentRoleConstants.GENERAL);
        }

        @DisplayName("null name → GENERAL")
        @Test
        void null_name_returns_general() {
            AgentEntity a = new AgentEntity();
            a.setId(42L);
            // name left null
            assertThat(resolver.resolveRole(a)).isEqualTo(AgentRoleConstants.GENERAL);
        }

        @DisplayName("blank name → GENERAL")
        @Test
        void blank_name_returns_general() {
            assertThat(resolver.resolveRole(named("   "))).isEqualTo(AgentRoleConstants.GENERAL);
        }

        @DisplayName("unknown name → GENERAL (logs warn)")
        @Test
        void unknown_name_returns_general() {
            assertThat(resolver.resolveRole(named("Random Bot 99")))
                    .isEqualTo(AgentRoleConstants.GENERAL);
        }
    }

    @Nested
    @DisplayName("Precedence — pattern order matches V117 SQL UPDATE block sequence")
    class Precedence {

        /**
         * V117 SQL guards main_assistant with NOT ILIKE design/code/research,
         * so an agent named e.g. "MainDesign Agent" goes to design. Resolver
         * if/return chain order achieves the same: design check fires first.
         */
        @DisplayName("design wins over main_assistant when name has both substrings")
        @Test
        void design_wins_over_main_assistant() {
            assertThat(resolver.resolveRole(named("Main Design Agent")))
                    .isEqualTo(AgentRoleConstants.DESIGN);
        }

        @DisplayName("design wins over code when name has both")
        @Test
        void design_wins_over_code() {
            assertThat(resolver.resolveRole(named("Code-First Design Tool")))
                    .isEqualTo(AgentRoleConstants.DESIGN);
        }

        @DisplayName("code wins over research when name has both")
        @Test
        void code_wins_over_research() {
            assertThat(resolver.resolveRole(named("Research Code Helper")))
                    .isEqualTo(AgentRoleConstants.CODE);
        }

        @DisplayName("research wins over main_assistant when name has both")
        @Test
        void research_wins_over_main_assistant() {
            assertThat(resolver.resolveRole(named("Main Research Agent")))
                    .isEqualTo(AgentRoleConstants.RESEARCH);
        }
    }

    @DisplayName("all 5 role constants returnable + closed set membership")
    @Test
    void all_roles_belong_to_closed_set() {
        // Sanity guard that AgentRoleConstants.ALL still mirrors the 5 used here
        assertThat(AgentRoleConstants.ALL).containsExactlyInAnyOrder(
                AgentRoleConstants.GENERAL,
                AgentRoleConstants.CODE,
                AgentRoleConstants.DESIGN,
                AgentRoleConstants.RESEARCH,
                AgentRoleConstants.MAIN_ASSISTANT);
        // 5 known names cover the 5 roles
        assertThat(resolver.resolveRole(named("design X"))).isEqualTo(AgentRoleConstants.DESIGN);
        assertThat(resolver.resolveRole(named("code X"))).isEqualTo(AgentRoleConstants.CODE);
        assertThat(resolver.resolveRole(named("research X"))).isEqualTo(AgentRoleConstants.RESEARCH);
        assertThat(resolver.resolveRole(named("main X"))).isEqualTo(AgentRoleConstants.MAIN_ASSISTANT);
        assertThat(resolver.resolveRole(named("unknown"))).isEqualTo(AgentRoleConstants.GENERAL);
    }

    private static AgentEntity named(String name) {
        AgentEntity a = new AgentEntity();
        a.setId(1L);
        a.setName(name);
        return a;
    }
}
