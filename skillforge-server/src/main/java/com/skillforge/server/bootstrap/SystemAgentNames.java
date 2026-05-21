package com.skillforge.server.bootstrap;

/**
 * Names of SkillForge system agents (KILL-BOOTSTRAP-PROMPT-TO-DB 2026-05-22).
 *
 * <p>Before V95 these constants lived on the per-agent {@code *Bootstrap}
 * classes (e.g. {@code MemoryCuratorBootstrap.AGENT_NAME}). After V95
 * inline-seeds {@code system_prompt} directly into {@code t_agent} the
 * {@code Bootstrap} swap classes are gone — but the agent-name constants
 * remain useful for cross-package {@code agentRepository.findFirstByName}
 * lookups and to keep grep / refactor cheap.
 *
 * <p>The matching Flyway seed migrations (still in place — schema
 * unchanged) are:
 * <ul>
 *   <li>V69 {@code memory-curator}</li>
 *   <li>V75 {@code session-annotator}</li>
 *   <li>V79 {@code metrics-collector}</li>
 *   <li>V81 {@code attribution-curator}</li>
 *   <li>V85 {@code user-simulator}</li>
 *   <li>V93 {@code attribution-dispatcher}</li>
 * </ul>
 *
 * <p>{@link com.skillforge.server.eval.usersim.UserSimAgentConstants#AGENT_NAME}
 * is the domain-scoped duplicate of {@link #USER_SIMULATOR} kept for
 * backward compatibility with V5 EVAL-DYNAMIC-USER-SIM call sites; both
 * resolve to {@code "user-simulator"}.
 */
public final class SystemAgentNames {

    public static final String MEMORY_CURATOR = "memory-curator";
    public static final String SESSION_ANNOTATOR = "session-annotator";
    public static final String METRICS_COLLECTOR = "metrics-collector";
    public static final String ATTRIBUTION_CURATOR = "attribution-curator";
    public static final String ATTRIBUTION_DISPATCHER = "attribution-dispatcher";
    public static final String USER_SIMULATOR = "user-simulator";

    private SystemAgentNames() { }
}
