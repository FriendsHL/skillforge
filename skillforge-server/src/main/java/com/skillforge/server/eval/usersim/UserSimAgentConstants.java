package com.skillforge.server.eval.usersim;

/**
 * V5 EVAL-DYNAMIC-USER-SIM constants shared between orchestrator + tools +
 * bootstrap. Single source of truth for the system agent name + tool names so
 * grep / refactor stays cheap.
 */
public final class UserSimAgentConstants {

    /** Seeded by V85 migration; swapped by UserSimulatorBootstrap at boot. */
    public static final String AGENT_NAME = "user-simulator";

    public static final String TOOL_RUN_SIMULATOR_TRIAL = "RunSimulatorTrial";
    public static final String TOOL_RECORD_SIMULATION_RESULT = "RecordSimulationResult";

    public static final String SURFACE_SKILL = "skill";
    public static final String SURFACE_PROMPT = "prompt";
    public static final String SURFACE_BEHAVIOR_RULE = "behavior_rule";

    private UserSimAgentConstants() { }
}
