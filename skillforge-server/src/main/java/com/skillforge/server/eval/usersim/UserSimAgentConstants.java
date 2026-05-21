package com.skillforge.server.eval.usersim;

/**
 * V5 EVAL-DYNAMIC-USER-SIM constants shared between orchestrator + tools.
 * Single source of truth for the system agent name + tool names so grep /
 * refactor stays cheap.
 *
 * <p>KILL-BOOTSTRAP-PROMPT-TO-DB (2026-05-22): the per-agent
 * {@code *Bootstrap} swap classes are gone — system_prompt now seeded
 * inline into {@code t_agent} by V95. {@link #AGENT_NAME} is the
 * domain-scoped duplicate of
 * {@link com.skillforge.server.bootstrap.SystemAgentNames#USER_SIMULATOR};
 * both resolve to {@code "user-simulator"}.
 */
public final class UserSimAgentConstants {

    /**
     * Seeded by V85 migration; prompt inline-seeded by V95.
     *
     * <p>r2 W2 fix (java-reviewer): delegate to {@link
     * com.skillforge.server.bootstrap.SystemAgentNames#USER_SIMULATOR} so the
     * two constants stay in sync at compile time. Renames + value changes only
     * have to touch one source.
     */
    public static final String AGENT_NAME =
            com.skillforge.server.bootstrap.SystemAgentNames.USER_SIMULATOR;

    public static final String TOOL_RUN_SIMULATOR_TRIAL = "RunSimulatorTrial";
    public static final String TOOL_RECORD_SIMULATION_RESULT = "RecordSimulationResult";

    public static final String SURFACE_SKILL = "skill";
    public static final String SURFACE_PROMPT = "prompt";
    public static final String SURFACE_BEHAVIOR_RULE = "behavior_rule";

    private UserSimAgentConstants() { }
}
