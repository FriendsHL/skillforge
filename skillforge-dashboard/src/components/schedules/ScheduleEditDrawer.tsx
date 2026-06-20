import React, { useEffect, useMemo, useState } from 'react';
import { DatePicker, message, Spin } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs, { Dayjs } from 'dayjs';
import { getAgents, extractList } from '../../api';
import { useAuth } from '../../contexts/AuthContext';
import { createSchedule, updateSchedule } from '../../api/schedules';
import type {
  ScheduledTask,
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
  ScheduleTriggerKind,
  ScheduleSessionMode,
  ChannelTarget,
} from '../../types/schedule';
import './ScheduleEditDrawer.css';

const TIMEZONE_OPTIONS = [
  { label: 'Asia/Shanghai (UTC+8)', value: 'Asia/Shanghai' },
  { label: 'UTC', value: 'UTC' },
  { label: 'America/Los_Angeles', value: 'America/Los_Angeles' },
  { label: 'America/New_York', value: 'America/New_York' },
  { label: 'Europe/London', value: 'Europe/London' },
];

const CHANNEL_TYPE_OPTIONS = [
  { label: 'None', value: '' },
  { label: '飞书', value: 'feishu' },
  { label: 'Telegram', value: 'telegram' },
  { label: '微信', value: 'weixin' },
];

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

const PROMPT_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M4 4h16v16H4z"/>
    <path d="M8 8h8M8 12h8M8 16h4"/>
  </svg>
);

const CHANNEL_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
    <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
  </svg>
);

const CLOSE_ICON = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8"/>
  </svg>
);

const SPINNER_ICON = (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ animation: 'sched-spin 0.8s linear infinite' }}>
    <circle cx="8" cy="8" r="6" strokeDasharray="30" strokeDashoffset="10"/>
  </svg>
);

interface AgentOption {
  id: number;
  name: string;
}

function normalizeAgents(raw: unknown[]): AgentOption[] {
  return raw.map((r) => {
    const a = r as Record<string, unknown>;
    return { id: Number(a.id), name: String(a.name || '') };
  });
}



export interface ScheduleEditDrawerProps {
  open: boolean;
  task: ScheduledTask | null;
  onClose: () => void;
}

const ScheduleEditDrawer: React.FC<ScheduleEditDrawerProps> = ({
  open,
  task,
  onClose,
}) => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const isEdit = task !== null;

  const [name, setName] = useState('');
  const [agentId, setAgentId] = useState<number | null>(null);
  const [triggerKind, setTriggerKind] = useState<ScheduleTriggerKind>('cron');
  const [cronExpr, setCronExpr] = useState('');
  const [oneShotAt, setOneShotAt] = useState<Dayjs | null>(null);
  const [timezone, setTimezone] = useState('Asia/Shanghai');
  const [promptTemplate, setPromptTemplate] = useState('');
  const [sessionMode, setSessionMode] = useState<ScheduleSessionMode>('new');
  const [channelType, setChannelType] = useState('');
  const [channelId, setChannelId] = useState('');
  const [enabled, setEnabled] = useState(true);

  const { data: agentsRaw = [], isLoading: agentsLoading } = useQuery({
    // SYSTEM-AGENT-TYPING Phase 2.2: editing a schedule for a system agent
    // requires that agent to be in the picker. BE default 'user' would hide
    // them; pass 'all' explicitly with a matching queryKey to keep cache
    // buckets separate from AgentList's toggle state.
    queryKey: ['agents', 'all'],
    queryFn: () => getAgents('all').then((r) => extractList<Record<string, unknown>>(r)),
  });
  const agents = useMemo(() => normalizeAgents(agentsRaw), [agentsRaw]);

  useEffect(() => {
    if (!open) return;
    if (task) {
      const kind: ScheduleTriggerKind = task.cronExpr ? 'cron' : 'one-shot';
      const ch = task.channelTarget;
      setName(task.name);
      setAgentId(task.agentId);
      setTriggerKind(kind);
      setCronExpr(task.cronExpr ?? '');
      setOneShotAt(task.oneShotAt ? dayjs(task.oneShotAt) : null);
      setTimezone(task.timezone);
      setPromptTemplate(task.promptTemplate);
      setSessionMode(task.sessionMode);
      setChannelType(ch?.channelType ?? '');
      setChannelId(ch?.channelId ?? '');
      setEnabled(task.enabled);
    } else {
      setName('');
      setAgentId(null);
      setTriggerKind('cron');
      setCronExpr('');
      setOneShotAt(null);
      setTimezone('Asia/Shanghai');
      setPromptTemplate('');
      setSessionMode('new');
      setChannelType('');
      setChannelId('');
      setEnabled(true);
    }
  }, [open, task]);

  const { mutate: save, isPending } = useMutation({
    mutationFn: async () => {
      const cronExprVal = triggerKind === 'cron' ? cronExpr.trim() : null;
      const oneShotAtVal = triggerKind === 'one-shot' && oneShotAt ? oneShotAt.toISOString() : null;

      const channelTarget: ChannelTarget | null =
        channelType && channelId ? { channelType, channelId: channelId.trim() } : null;

      if (isEdit && task) {
        const body: UpdateScheduledTaskRequest = {
          name,
          agentId: agentId!,
          cronExpr: cronExprVal,
          oneShotAt: oneShotAtVal,
          timezone,
          promptTemplate,
          sessionMode,
          channelTarget,
          enabled,
        };
        return updateSchedule(task.id, body, userId);
      } else {
        const body: CreateScheduledTaskRequest = {
          name,
          agentId: agentId!,
          cronExpr: cronExprVal,
          oneShotAt: oneShotAtVal,
          timezone,
          promptTemplate,
          sessionMode,
          channelTarget,
          enabled,
        };
        return createSchedule(body, userId);
      }
    },
    onSuccess: () => {
      message.success(isEdit ? 'Schedule updated' : 'Schedule created');
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
      onClose();
    },
    onError: (e: unknown) => {
      const detail = e instanceof Error ? e.message : 'unknown error';
      message.error(`Save failed: ${detail}`);
    },
  });

  const handleSubmit = () => {
    if (!name.trim()) {
      message.error('Name is required');
      return;
    }
    if (!agentId) {
      message.error('Select an agent');
      return;
    }
    if (triggerKind === 'cron' && !cronExpr.trim()) {
      message.error('Cron expression is required');
      return;
    }
    if (triggerKind === 'one-shot' && !oneShotAt) {
      message.error('Pick a date and time');
      return;
    }
    if (!promptTemplate.trim()) {
      message.error('Prompt template is required');
      return;
    }
    if (channelType && !channelId.trim()) {
      message.error('Channel id is required');
      return;
    }
    save();
  };

  const nextFireDisplay = task?.nextFireAt
    ? dayjs(task.nextFireAt).format('YYYY-MM-DD HH:mm')
    : '—';

  if (!open) return null;

  return (
    <>
      <div className="sched-drawer-backdrop" onClick={onClose} />
      <div className="sched-drawer">
        <div className="sched-drawer-head">
          <div className="sched-drawer-head-row">
            <button className="sched-drawer-close" onClick={onClose}>
              {CLOSE_ICON}
            </button>
            <div>
              <h2 className="sched-drawer-title">
                {isEdit ? 'Edit Schedule' : 'New Schedule'}
              </h2>
              {isEdit && task && (
                <div className="sched-drawer-sub">#{task.id}</div>
              )}
            </div>
          </div>
        </div>

        <div className="sched-drawer-body">
          {/* Basic info */}
          <div className="sched-form-section">
            <div className="sched-form-section-h">
              {AGENT_ICON}
              Basic
            </div>
            <div className="sched-form-item">
              <label className="sched-form-label">
                Name<span className="required">*</span>
              </label>
              <input
                className="sched-input"
                placeholder="e.g. Daily standup digest"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>
            <div className="sched-form-item">
              <label className="sched-form-label">
                Agent<span className="required">*</span>
              </label>
              {agentsLoading ? (
                <Spin size="small" />
              ) : (
                <select
                  className="sched-select"
                  value={agentId ?? ''}
                  onChange={(e) => setAgentId(Number(e.target.value) || null)}
                >
                  <option value="">Select agent...</option>
                  {agents.map((a) => (
                    <option key={a.id} value={a.id}>{a.name}</option>
                  ))}
                </select>
              )}
            </div>
          </div>

          {/* Trigger */}
          <div className="sched-form-section">
            <div className="sched-form-section-h">
              {triggerKind === 'cron' ? CLOCK_ICON : ONESHOT_ICON}
              Trigger
            </div>
            <div className="sched-trigger-kind">
              <button
                className={`sched-trigger-kind-btn ${triggerKind === 'cron' ? 'active' : ''}`}
                onClick={() => setTriggerKind('cron')}
              >
                {CLOCK_ICON}
                <span className="label">Cron</span>
                <span className="hint">Recurring schedule</span>
              </button>
              <button
                className={`sched-trigger-kind-btn ${triggerKind === 'one-shot' ? 'active' : ''}`}
                onClick={() => setTriggerKind('one-shot')}
              >
                {ONESHOT_ICON}
                <span className="label">One-shot</span>
                <span className="hint">Single execution</span>
              </button>
            </div>

            {triggerKind === 'cron' && (
              <div style={{ marginTop: 14 }}>
                <div className="sched-form-item">
                  <label className="sched-form-label">
                    Cron expression<span className="required">*</span>
                  </label>
                  <input
                    className="sched-input mono"
                    placeholder="0 0 9 * * *"
                    value={cronExpr}
                    onChange={(e) => setCronExpr(e.target.value)}
                  />
                  <div className="sched-form-hint">
                    Spring 6-field: sec min hour day month weekday. e.g. 0 0 9 * * MON-FRI for 9am weekdays.
                  </div>
                </div>
                <div className="sched-form-item">
                  <label className="sched-form-label">Timezone</label>
                  <select
                    className="sched-select"
                    value={timezone}
                    onChange={(e) => setTimezone(e.target.value)}
                  >
                    {TIMEZONE_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>{opt.label}</option>
                    ))}
                  </select>
                </div>
              </div>
            )}

            {triggerKind === 'one-shot' && (
              <div style={{ marginTop: 14 }}>
                <div className="sched-form-item">
                  <label className="sched-form-label">
                    Trigger at<span className="required">*</span>
                  </label>
                  <DatePicker
                    showTime
                    style={{ width: '100%' }}
                    format="YYYY-MM-DD HH:mm"
                    value={oneShotAt}
                    onChange={(d) => setOneShotAt(d)}
                    disabledDate={(d) => d.isBefore(dayjs().startOf('day'))}
                  />
                </div>
                <div className="sched-form-item">
                  <label className="sched-form-label">Timezone</label>
                  <select
                    className="sched-select"
                    value={timezone}
                    onChange={(e) => setTimezone(e.target.value)}
                  >
                    {TIMEZONE_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>{opt.label}</option>
                    ))}
                  </select>
                </div>
              </div>
            )}

            {isEdit && (
              <div className="sched-next-fire-card">
                <div className="sched-next-fire-label">Next fire</div>
                <div className="sched-next-fire-value">{nextFireDisplay}</div>
              </div>
            )}
          </div>

          {/* Prompt */}
          <div className="sched-form-section">
            <div className="sched-form-section-h">
              {PROMPT_ICON}
              Prompt
            </div>
            <div className="sched-form-item">
              <label className="sched-form-label">
                Template<span className="required">*</span>
              </label>
              <textarea
                className="sched-textarea"
                placeholder="e.g. 总结今天的会议笔记并发送到飞书..."
                value={promptTemplate}
                onChange={(e) => setPromptTemplate(e.target.value)}
                rows={5}
              />
              <div className="sched-form-hint">
                Sent verbatim as the user message when the task fires.
              </div>
            </div>
            <div className="sched-form-item">
              <label className="sched-form-label">Session mode</label>
              <div className="sched-trigger-kind" style={{ marginTop: 6 }}>
                <button
                  className={`sched-trigger-kind-btn ${sessionMode === 'new' ? 'active' : ''}`}
                  onClick={() => setSessionMode('new')}
                  style={{ padding: '8px 12px' }}
                >
                  <span className="label">New session</span>
                  <span className="hint">Each fire creates a fresh session</span>
                </button>
                <button
                  className={`sched-trigger-kind-btn ${sessionMode === 'reuse' ? 'active' : ''}`}
                  onClick={() => setSessionMode('reuse')}
                  style={{ padding: '8px 12px' }}
                >
                  <span className="label">Reuse session</span>
                  <span className="hint">All fires share one session</span>
                </button>
              </div>
            </div>
          </div>

          {/* Channel */}
          <div className="sched-form-section">
            <div className="sched-form-section-h">
              {CHANNEL_ICON}
              Channel push
            </div>
            <div className="sched-channel-row">
              <select
                className="sched-select"
                value={channelType}
                onChange={(e) => setChannelType(e.target.value)}
              >
                {CHANNEL_TYPE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
              {channelType && (
                <input
                  className="sched-input mono"
                  placeholder={channelType === 'feishu' ? 'oc_xxxxxxxx' : 'recipient id'}
                  value={channelId}
                  onChange={(e) => setChannelId(e.target.value)}
                />
              )}
            </div>
            {channelType && (
              <div className="sched-form-hint" style={{ marginTop: 6 }}>
                {channelType === 'feishu' ? 'Feishu open_chat_id' : 'Platform recipient id'}
              </div>
            )}
          </div>

          {/* Enabled */}
          <div className="sched-toggle-row">
            <div>
              <div className="sched-toggle-label">Enabled</div>
              <div className="sched-toggle-hint">Task will fire when enabled</div>
            </div>
            <button
              className={`sched-switch ${enabled ? 'on' : ''}`}
              onClick={() => setEnabled(!enabled)}
            />
          </div>
        </div>

        <div className="sched-drawer-foot">
          <button className="sched-btn-cancel" onClick={onClose}>
            Cancel
          </button>
          <button
            className={`sched-btn-save ${isPending ? 'loading' : ''}`}
            onClick={handleSubmit}
            disabled={isPending}
          >
            {isPending ? SPINNER_ICON : null}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </div>
    </>
  );
};

export default ScheduleEditDrawer;