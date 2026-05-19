/**
 * SYSTEM-AGENT-TYPING Phase 2.2 — inline monitor card embedded under each
 * system-agent AgentCard. Surfaces cron schedule / last_run timestamp + status
 * / 7d trigger count / 7d output count + 3 action buttons (Run Manually /
 * View Sessions / View Trace).
 *
 * Run Manually requires the scheduled task id. The monitor response from the
 * BE does NOT include task id (only agentId / cronExpression), so the parent
 * passes `scheduledTaskId` resolved upstream from `listSchedules()`. If unset
 * (no scheduled task for this agent), Run Manually is disabled with a tooltip.
 */
import React from 'react';
import { Tooltip, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { triggerSchedule } from '../../api/schedules';
import { useAuth } from '../../contexts/AuthContext';
import type { SystemAgentMonitorRow } from '../../api/systemAgents';

interface SystemAgentMonitorCardProps {
  data: SystemAgentMonitorRow;
  /**
   * Resolved client-side from `listSchedules(userId)` filtered by `agentId`.
   * Null when this system agent has no scheduled task yet (rare; only on
   * fresh dev DB before V1-V5 bootstrap creates the schedule rows).
   */
  scheduledTaskId: number | null;
}

const STATUS_COLOR: Record<NonNullable<SystemAgentMonitorRow['lastRunStatus']>, string> = {
  running: 'processing',
  success: 'success',
  failure: 'error',
  skipped: 'default',
  timeout: 'warning',
  paused: 'default',
};

const OUTPUT_LABEL: Record<SystemAgentMonitorRow['outputEntityType'], string> = {
  annotations: 'annotations',
  proposals: 'proposals',
  metrics: 'metric snapshots',
  consolidations: 'consolidations',
  trials: 'trials',
  unknown: 'output items',
};

function formatLastRun(iso: string | null): string {
  if (!iso) return 'never';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  // Same compact format as RuntimeBanner — local time, drop seconds.
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

const SystemAgentMonitorCard: React.FC<SystemAgentMonitorCardProps> = ({
  data,
  scheduledTaskId,
}) => {
  const navigate = useNavigate();
  const { userId } = useAuth();

  const handleRunManually = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (scheduledTaskId == null) {
      message.warning(`No scheduled task wired for ${data.name}`);
      return;
    }
    try {
      await triggerSchedule(scheduledTaskId, userId);
      message.success(`Triggered ${data.name} (task #${scheduledTaskId})`);
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 403) {
        message.error('Not authorized to trigger this schedule');
      } else if (status === 404) {
        message.error(`Schedule ${scheduledTaskId} not found`);
      } else {
        message.error('Trigger failed');
      }
    }
  };

  const handleViewSessions = (e: React.MouseEvent) => {
    e.stopPropagation();
    // F4 spec: jump to /sessions?agentId={id}&origin=production. The session
    // list page accepts these as filters. We don't pin a specific `origin`
    // because system agents emit across multiple origins (production / canary
    // / user_sim) — operators can re-filter on arrival.
    navigate(`/sessions?agentId=${data.agentId}`);
  };

  const handleViewSchedule = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (scheduledTaskId == null) {
      message.warning(`No scheduled task wired for ${data.name}`);
      return;
    }
    navigate(`/schedules?taskId=${scheduledTaskId}`);
  };

  // Stop click propagation so clicking the monitor card doesn't trigger the
  // parent AgentCard's `onOpen` (which opens the AgentDrawer). All buttons
  // also call stopPropagation defensively above for the same reason.
  const stop = (e: React.MouseEvent) => e.stopPropagation();

  return (
    <div
      className="system-monitor-card"
      data-testid={`system-agent-monitor-${data.agentId}`}
      onClick={stop}
    >
      <div className="system-monitor-row">
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-3)' }}>
          cron
        </span>
        <code>{data.cronExpression ?? '(no schedule)'}</code>
        {data.lastRunStatus && (
          <span
            className={`sess-status s-${data.lastRunStatus === 'success' ? 'done' : data.lastRunStatus === 'failure' ? 'error' : data.lastRunStatus === 'running' ? 'running' : 'idle'}`}
            data-testid="last-run-status"
          >
            {data.lastRunStatus}
          </span>
        )}
      </div>
      <div className="system-monitor-stats">
        <span data-testid="last-run">last run · {formatLastRun(data.lastRunAt)}</span>
        <span data-testid="trigger-count">7d triggers · {data.sevenDayTriggerCount}</span>
        <span data-testid="output-count">
          7d {OUTPUT_LABEL[data.outputEntityType]} · {data.sevenDayOutputCount}
        </span>
      </div>
      <div className="system-monitor-actions">
        <Tooltip
          title={
            scheduledTaskId == null
              ? `No scheduled task wired for ${data.name}`
              : `Trigger schedule #${scheduledTaskId} now (bypasses enabled gate)`
          }
        >
          <button
            onClick={handleRunManually}
            disabled={scheduledTaskId == null}
            data-testid="run-manually-btn"
          >
            Run Manually
          </button>
        </Tooltip>
        <button onClick={handleViewSessions} data-testid="view-sessions-btn">
          View Sessions
        </button>
        <button
          onClick={handleViewSchedule}
          disabled={scheduledTaskId == null}
          data-testid="view-schedule-btn"
        >
          View Schedule
        </button>
      </div>
    </div>
  );
};

export default SystemAgentMonitorCard;
