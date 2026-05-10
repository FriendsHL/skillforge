import React, { useState, useCallback, useMemo } from 'react';
import { Modal, Switch, Tooltip, message, Spin } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
dayjs.extend(relativeTime);
import {
  listSchedules,
  deleteSchedule,
  triggerSchedule,
  updateSchedule,
} from '../api/schedules';
import { getAgents, extractList } from '../api';
import { useAuth } from '../contexts/AuthContext';
import type { ScheduledTask, ScheduledTaskStatus } from '../types/schedule';
import ScheduleEditDrawer from '../components/schedules/ScheduleEditDrawer';
import ScheduleRunHistoryDrawer from '../components/schedules/ScheduleRunHistoryDrawer';
import './Schedules.css';



const PLUS_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);

const CLOCK_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
    <circle cx="12" cy="12" r="10"/>
    <path d="M12 6v6l4 2"/>
  </svg>
);

const ONESHOT_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
    <circle cx="12" cy="12" r="4"/>
    <path d="M12 2v2M12 20v2M2 12h2M20 12h2"/>
  </svg>
);

const AGENT_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
    <rect x="3" y="4" width="18" height="6" rx="2"/>
    <rect x="3" y="14" width="18" height="6" rx="2"/>
    <circle cx="7" cy="7" r="1" fill="currentColor"/>
    <circle cx="7" cy="17" r="1" fill="currentColor"/>
  </svg>
);

const RUN_ICON = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M4 3l8 5-8 5V3z" fill="currentColor"/>
  </svg>
);

const EDIT_ICON = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M11.5 2.5l2 2M3 13l8-8 2 2-8 8H3z"/>
  </svg>
);

const HISTORY_ICON = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M2 8a6 6 0 1012 0M2 8h3M14 8h-3"/>
    <circle cx="8" cy="8" r="2"/>
  </svg>
);

const DELETE_ICON = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M3 4h10M5 4V3h6v1M6 7v5M10 7v5M4 4l1 10h6l1-10"/>
  </svg>
);

const SPINNER_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ animation: 'sched-spin 0.8s linear infinite' }}>
    <circle cx="8" cy="8" r="6" strokeDasharray="30" strokeDashoffset="10"/>
  </svg>
);

interface AgentLite {
  id: number;
  name: string;
}

function formatRelative(iso: string | null): string {
  if (!iso) return '';
  return dayjs(iso).fromNow();
}

function isSoon(iso: string | null): boolean {
  if (!iso) return false;
  return dayjs(iso).diff(dayjs(), 'hour') < 2;
}

function StatusPill({ status }: { status: ScheduledTaskStatus }) {
  const labelMap: Record<ScheduledTaskStatus, string> = {
    idle: 'pending',
    running: 'running',
    completed: 'completed',
    error: 'error',
  };
  const classMap: Record<ScheduledTaskStatus, string> = {
    idle: 'idle',
    running: 'running',
    completed: 'success',
    error: 'failed',
  };
  return (
    <span className={`sched-status-pill ${classMap[status]}`}>
      <span className="sched-status-dot"/>
      {labelMap[status]}
    </span>
  );
}

function TriggerCell({ task }: { task: ScheduledTask }) {
  if (task.cronExpr) {
    return (
      <div className="sched-trigger-cell">
        <span className="sched-trigger-type cron">cron</span>
        <span className="sched-trigger-expr">{task.cronExpr}</span>
        <span className="sched-trigger-hint">
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
            <circle cx="8" cy="8" r="6"/>
            <path d="M8 4v4l2.5 1.5"/>
          </svg>
          {task.timezone}
        </span>
      </div>
    );
  }
  if (task.oneShotAt) {
    return (
      <div className="sched-trigger-cell">
        <span className="sched-trigger-type oneshot">one-shot</span>
        <span className="sched-trigger-expr">{dayjs(task.oneShotAt).format('YYYY-MM-DD HH:mm')}</span>
        <span className="sched-trigger-hint">
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
            <circle cx="8" cy="8" r="6"/>
            <path d="M8 4v4l2.5 1.5"/>
          </svg>
          Single execution
        </span>
      </div>
    );
  }
  return <span className="sched-time-empty">—</span>;
}

function TimeCell({ iso, status }: { iso: string | null; status?: ScheduledTaskStatus }) {
  if (!iso) return <span className="sched-time-empty">— never</span>;
  const rel = formatRelative(iso);
  const soon = isSoon(iso);
  const isError = status === 'error';
  return (
    <div className="sched-time-cell">
      <span className="sched-time-main">{dayjs(iso).format('YYYY-MM-DD HH:mm')}</span>
      <span className={`sched-time-relative ${soon ? 'soon' : ''} ${isError ? 'failed' : ''}`}>
        {isError ? 'error' : rel}
      </span>
    </div>
  );
}

const Schedules: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [editOpen, setEditOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ScheduledTask | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyTarget, setHistoryTarget] = useState<ScheduledTask | null>(null);
  const [triggeringId, setTriggeringId] = useState<number | null>(null);

  const { data: tasks = [], isLoading } = useQuery({
    queryKey: ['schedules', userId],
    queryFn: () => listSchedules(userId).then((r) => r.data ?? []),
    staleTime: 15_000,
  });

  const { data: agentsRaw = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then((r) => extractList<Record<string, unknown>>(r)),
    staleTime: 60_000,
  });

  const agentMap = useMemo<Record<number, AgentLite>>(() => {
    const m: Record<number, AgentLite> = {};
    agentsRaw.forEach((a) => {
      const id = Number((a as Record<string, unknown>).id);
      const name = String((a as Record<string, unknown>).name || '');
      if (id) m[id] = { id, name };
    });
    return m;
  }, [agentsRaw]);

  // Stats
  const stats = useMemo(() => {
    const active = tasks.filter(t => t.enabled).length;
    const cron = tasks.filter(t => t.cronExpr).length;
    const oneshot = tasks.filter(t => t.oneShotAt).length;
    return { active, cron, oneshot, total: tasks.length };
  }, [tasks]);

  const { mutate: removeTask } = useMutation({
    mutationFn: (id: number) => deleteSchedule(id, userId),
    onSuccess: () => {
      message.success('Schedule removed');
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
    },
    onError: () => message.error('Failed to remove schedule'),
  });

  const { mutate: fireNow } = useMutation({
    mutationFn: (id: number) => triggerSchedule(id, userId),
    onMutate: (id: number) => setTriggeringId(id),
    onSuccess: () => {
      message.success('Triggered — see run history for status');
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
    },
    onError: (e: unknown) => {
      const detail = e instanceof Error ? e.message : 'unknown';
      message.error(`Trigger failed: ${detail}`);
    },
    onSettled: () => setTriggeringId(null),
  });

  const { mutate: toggleEnabled } = useMutation({
    mutationFn: (vars: { id: number; enabled: boolean }) =>
      updateSchedule(vars.id, { enabled: vars.enabled }, userId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['schedules'] }),
    onError: () => message.error('Failed to toggle enabled'),
  });

  const handleOpenCreate = useCallback(() => {
    setEditTarget(null);
    setEditOpen(true);
  }, []);

  const handleOpenEdit = useCallback((task: ScheduledTask) => {
    setEditTarget(task);
    setEditOpen(true);
  }, []);

  const handleOpenHistory = useCallback((task: ScheduledTask) => {
    setHistoryTarget(task);
    setHistoryOpen(true);
  }, []);

  const handleDelete = useCallback(
    (task: ScheduledTask) => {
      Modal.confirm({
        title: `Remove "${task.name}"?`,
        content: 'This deletes the schedule and all its run history. This cannot be undone.',
        okText: 'Remove',
        okType: 'danger',
        onOk: () => removeTask(task.id),
      });
    },
    [removeTask],
  );

  return (
    <div className="sched-page">
      <header className="sched-head">
        <div>
          <h1 className="sched-head-title">Scheduled Tasks</h1>
          <p className="sched-head-sub">Cron-based or one-shot agent triggers. Auto-restored on app restart.</p>
        </div>
        <button className="sched-btn-add" onClick={handleOpenCreate}>
          {PLUS_ICON}
          New schedule
        </button>
      </header>

      {/* Stats bar */}
      {tasks.length > 0 && (
        <div className="sched-stats-bar">
          <div className="sched-stat-item">
            <svg className="sched-stat-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M12 6v6l4 2"/>
            </svg>
            <span className="sched-stat-count">{stats.active}</span>
            <span className="sched-stat-label">active</span>
          </div>
          <div className="sched-stat-item">
            <svg className="sched-stat-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <rect x="3" y="4" width="18" height="18" rx="2"/>
              <path d="M16 2v4M8 2v4M3 10h18"/>
            </svg>
            <span className="sched-stat-count">{stats.cron}</span>
            <span className="sched-stat-label">cron</span>
          </div>
          <div className="sched-stat-item">
            <svg className="sched-stat-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="12" cy="12" r="4"/>
              <path d="M12 2v2M12 20v2M2 12h2M20 12h2"/>
            </svg>
            <span className="sched-stat-count">{stats.oneshot}</span>
            <span className="sched-stat-label">one-shot</span>
          </div>
        </div>
      )}

      <div className="sched-table-wrap">
        {isLoading ? (
          <div className="sched-loading"><Spin /></div>
        ) : tasks.length === 0 ? (
          <div className="sched-empty">
            <div className="sched-empty-icon">{CLOCK_ICON}</div>
            <h3 className="sched-empty-title">No schedules configured</h3>
            <p className="sched-empty-desc">Create a schedule to trigger an agent on a cron or at a specific time.</p>
            <button className="sched-btn-add" onClick={handleOpenCreate}>
              {PLUS_ICON}
              Add first schedule
            </button>
          </div>
        ) : (
          <table className="sched-table">
            <thead>
              <tr>
                <th style={{ paddingLeft: 20 }}>Task</th>
                <th>Agent</th>
                <th>Trigger</th>
                <th>Next fire</th>
                <th>Last fire</th>
                <th>Status</th>
                <th>Enabled</th>
                <th style={{ textAlign: 'right', paddingRight: 20 }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((task) => {
                const agent = agentMap[task.agentId];
                const iconType = task.cronExpr ? CLOCK_ICON : ONESHOT_ICON;
                const iconFailed = task.status === 'error';
                return (
                  <tr
                    key={task.id}
                    className={task.enabled ? '' : 'disabled'}
                    onClick={() => handleOpenEdit(task)}
                  >
                    <td style={{ paddingLeft: 20 }}>
                      <div className="sched-name-cell">
                        <div className={`sched-name-icon ${iconFailed ? 'failed' : ''}`}>
                          {iconType}
                        </div>
                        <div className="sched-name-text">
                          <span className="sched-name-main">{task.name}</span>
                          <span className="sched-name-id">#{task.id}</span>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span className={`sched-agent-badge ${agent ? '' : 'missing'}`}>
                        {AGENT_ICON}
                        {agent ? agent.name : `#${task.agentId} (missing)`}
                      </span>
                    </td>
                    <td><TriggerCell task={task} /></td>
                    <td>
                      {task.enabled ? (
                        <TimeCell iso={task.nextFireAt} />
                      ) : (
                        <span className="sched-time-empty">— disabled</span>
                      )}
                    </td>
                    <td><TimeCell iso={task.lastFireAt} status={task.status} /></td>
                    <td><StatusPill status={task.status} /></td>
                    <td>
                      <div className="sched-toggle-wrap">
                        <Switch
                          checked={task.enabled}
                          size="small"
                          onChange={(checked) => toggleEnabled({ id: task.id, enabled: checked })}
                          onClick={(_, e) => e.stopPropagation()}
                        />
                      </div>
                    </td>
                    <td style={{ paddingRight: 20 }}>
                      <div className="sched-actions" onClick={(e) => e.stopPropagation()}>
                        <Tooltip title="Edit configuration">
                          <button className="sched-btn-action edit" onClick={() => handleOpenEdit(task)}>
                            {EDIT_ICON}
                            Edit
                          </button>
                        </Tooltip>
                        <Tooltip title="Manually trigger now">
                          <button
                            className={`sched-btn-action run ${triggeringId === task.id ? 'loading' : ''}`}
                            onClick={() => fireNow(task.id)}
                            disabled={triggeringId === task.id}
                          >
                            {triggeringId === task.id ? SPINNER_ICON : RUN_ICON}
                            Run
                          </button>
                        </Tooltip>
                        <Tooltip title="View run history">
                          <button className="sched-btn-action history" onClick={() => handleOpenHistory(task)}>
                            {HISTORY_ICON}
                            History
                          </button>
                        </Tooltip>
                        <Tooltip title="Delete">
                          <button className="sched-btn-action delete" onClick={() => handleDelete(task)}>
                            {DELETE_ICON}
                          </button>
                        </Tooltip>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      <ScheduleEditDrawer open={editOpen} task={editTarget} onClose={() => setEditOpen(false)} />
      <ScheduleRunHistoryDrawer open={historyOpen} task={historyTarget} onClose={() => setHistoryOpen(false)} />
    </div>
  );
};

export default Schedules;