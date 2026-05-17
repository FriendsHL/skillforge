/**
 * SYSTEM-AGENT-TYPING Phase 2.2 — wrapper for `GET /api/system-agents/monitor`.
 *
 * The BE returns a flat array of monitor rows (one per system agent), joining
 * `t_agent` with `t_scheduled_task` and aggregating per-surface 7d output
 * counts. The FE consumes this from {@link SystemAgentMonitorCard}, embedded
 * inline in the AgentList grid card for system-typed agents.
 *
 * Wire shape mirrors the BE projection 1:1 — no client-side enum widening or
 * field renaming (keeps the contract trivially auditable).
 */
import api from './index';

/**
 * One monitor row, one system agent. `cronExpression` / `lastRunAt` / etc. are
 * nullable when the system agent has no scheduled task yet (rare; only happens
 * before V1-V5 bootstrap completes on first server start).
 */
export interface SystemAgentMonitorRow {
  agentId: number;
  name: string;
  description: string | null;
  /** Quartz / cron4j expression (e.g. `"0 30 * * * *"`) or null. */
  cronExpression: string | null;
  /** ISO-8601 instant of the latest run, or null if never run. */
  lastRunAt: string | null;
  lastRunStatus:
    | 'running'
    | 'success'
    | 'failure'
    | 'skipped'
    | 'timeout'
    | 'paused'
    | null;
  /** Last 7 days: # of cron triggers attempted (success + failure + skipped). */
  sevenDayTriggerCount: number;
  /** Last 7 days: # of entities this agent produced (per `outputEntityType`). */
  sevenDayOutputCount: number;
  /**
   * What kind of entity this system agent emits. Drives the label in the
   * monitor card ("7d annotations: 24" vs "7d trials: 5" etc.). `'unknown'`
   * means the BE couldn't infer (likely a new V*-bootstrap agent the monitor
   * resolver hasn't been taught about yet — surfaces as "7d output: N").
   */
  outputEntityType:
    | 'annotations'
    | 'proposals'
    | 'metrics'
    | 'consolidations'
    | 'trials'
    | 'unknown';
}

export type SystemAgentMonitorResponse = SystemAgentMonitorRow[];

/**
 * Fetch monitor rows for all 5 system agents. Returns 200 + empty array if
 * no system agents exist (e.g. fresh dev DB with no bootstrap yet).
 */
export const getSystemAgentMonitor = () =>
  api.get<SystemAgentMonitorResponse>('/system-agents/monitor');
