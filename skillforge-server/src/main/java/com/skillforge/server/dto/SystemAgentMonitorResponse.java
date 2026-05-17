package com.skillforge.server.dto;

import java.time.Instant;

/**
 * SYSTEM-AGENT-TYPING Phase 2 (PRD F4): wire shape for the
 * {@code GET /api/system-agents/monitor} endpoint that backs the
 * {@code /insights/system-agents} observability panel (and the inline
 * "Show system agents" monitor cards in {@code AgentList}).
 *
 * <p>One row per {@code agent_type='system'} agent. The aggregation joins three
 * sources:
 * <ul>
 *   <li>{@code t_agent} — identity (id / name / description)</li>
 *   <li>{@code t_scheduled_task} (looked up by {@code agent_id}) — cron schedule
 *       + last fire timestamp; null when the agent has no scheduled task or its
 *       row was removed</li>
 *   <li>{@code t_scheduled_task_run} — latest run status + 7d trigger count</li>
 *   <li>per-agent output table — 7d output count, table chosen by agent name
 *       (see {@link #outputEntityType}). Tables natively scoped to a single
 *       system agent (only that cron writes them) so no agent_id join is needed.</li>
 * </ul>
 *
 * <p>Field types match {@code .claude/rules/java.md} footgun #6 Java → TS map
 * (Long → number, Instant → ISO string, String → string, long → number) so the
 * FE TS interface can mirror this record verbatim in camelCase.
 *
 * @param agentId               {@code t_agent.id}
 * @param name                  {@code t_agent.name} (deterministic — one of
 *                              {@code memory-curator} / {@code session-annotator} /
 *                              {@code metrics-collector} / {@code attribution-curator} /
 *                              {@code user-simulator})
 * @param description           {@code t_agent.description}; null-able
 * @param cronExpression        {@code t_scheduled_task.cron_expr}; null when no
 *                              scheduled task or it was disabled at DB level
 * @param lastRunAt             {@code t_scheduled_task.last_fire_at}; null when
 *                              never fired
 * @param lastRunStatus         latest {@code t_scheduled_task_run.status}
 *                              ({@code running} / {@code success} / {@code failure} /
 *                              {@code skipped} / {@code timeout} / {@code paused})
 *                              or null when no run history
 * @param sevenDayTriggerCount  count of {@code t_scheduled_task_run} rows with
 *                              {@code triggered_at} ≥ now − 7d (always ≥ 0)
 * @param sevenDayOutputCount   count of rows in the agent-specific output table
 *                              with {@code created_at} ≥ now − 7d
 * @param outputEntityType      semantic label for the output table the FE card
 *                              renders as the "produced N <X> in 7d" line:
 *                              <ul>
 *                                <li>{@code annotations} (session-annotator → t_session_annotation)</li>
 *                                <li>{@code proposals} (attribution-curator → t_optimization_event)</li>
 *                                <li>{@code metrics} (metrics-collector → t_canary_metric_snapshot)</li>
 *                                <li>{@code consolidations} (memory-curator → t_memory_proposal)</li>
 *                                <li>{@code trials} (user-simulator → t_simulator_trial)</li>
 *                                <li>{@code unknown} (defensive — system agent without a known mapping)</li>
 *                              </ul>
 */
public record SystemAgentMonitorResponse(
        Long agentId,
        String name,
        String description,
        String cronExpression,
        Instant lastRunAt,
        String lastRunStatus,
        long sevenDayTriggerCount,
        long sevenDayOutputCount,
        String outputEntityType
) {
}
