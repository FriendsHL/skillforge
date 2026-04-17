import React, { useCallback, useMemo, useState } from 'react';
import {
  Card,
  Radio,
  Select,
  Switch,
  Tooltip,
  Space,
  Empty,
  Typography,
  Button,
  Modal,
  message,
} from 'antd';
import {
  InfoCircleOutlined,
  ApiOutlined,
  CodeOutlined,
  FunctionOutlined,
  ExclamationCircleOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  DeleteOutlined,
  PlusOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { dryRunHook, type BuiltInMethodDto, type DryRunResponse } from '../../api';
import MethodHandlerFields from './MethodHandlerFields';
import DryRunResultModal from './DryRunResultModal';
import {
  HOOK_HANDLER_TYPE_META,
  LIFECYCLE_HOOK_EVENT_IDS,
  MAX_ENTRIES_PER_EVENT,
  MAX_SCRIPT_BODY_BYTES,
  SCRIPT_CONFIRM_STORAGE_KEY,
  SCRIPT_LANG_OPTIONS,
  TIMEOUT_DEFAULT_SECONDS,
  TIMEOUT_MAX_SECONDS,
  TIMEOUT_MIN_SECONDS,
  allowedFailurePolicies,
  type HookEntry,
  type HookHandler,
  type HookHandlerType,
  type LifecycleHookEventId,
  type LifecycleHookEventMeta,
  type LifecycleHooksConfig,
  type FailurePolicy,
  type ScriptLang,
} from '../../constants/lifecycleHooks';
import {
  BufferedInput,
  BufferedInputNumber,
  BufferedTextArea,
} from './BufferedInputs';

interface SkillOption {
  name: string;
  description?: string;
}

interface FormModeProps {
  parsed: LifecycleHooksConfig | null;
  errors: string[];
  events: LifecycleHookEventMeta[];
  skills: SkillOption[];
  methods: BuiltInMethodDto[];
  isMethodsLoading?: boolean;
  agentId: string | null;
  /** Called when any field changes — caller re-serializes to rawJson. */
  onConfigChange: (next: LifecycleHooksConfig) => void;
}

const HANDLER_TYPE_ICONS: Record<HookHandlerType, React.ReactNode> = {
  skill: <ApiOutlined />,
  script: <CodeOutlined />,
  method: <FunctionOutlined />,
};

/**
 * Form mode: one Card per event, each holding an ordered list of up to
 * {@link MAX_ENTRIES_PER_EVENT} entries (P1). Entries can be added, moved,
 * edited in place and deleted — the rawJson source of truth in the parent
 * editor is updated on every commit.
 */
const FormMode: React.FC<FormModeProps> = ({
  parsed,
  errors,
  events,
  skills,
  methods,
  isMethodsLoading,
  agentId,
  onConfigChange,
}) => {
  if (!parsed) {
    return (
      <div className="sf-hooks-form-error">
        <ExclamationCircleOutlined />
        <div>
          <div className="sf-hooks-form-error-title">
            Current JSON is invalid — switch to JSON mode to fix it.
          </div>
          <ul className="sf-hooks-form-error-list">
            {errors.slice(0, 5).map((err, i) => (
              <li key={i}>{err}</li>
            ))}
          </ul>
        </div>
      </div>
    );
  }

  return (
    <div className="sf-hooks-form-grid">
      {events.map((event) => (
        <EventCard
          key={event.id}
          event={event}
          entries={parsed.hooks[event.id] ?? []}
          skills={skills}
          methods={methods}
          isMethodsLoading={isMethodsLoading}
          agentId={agentId}
          onEntriesChange={(nextEntries) =>
            onConfigChange(replaceEntries(parsed, event.id, nextEntries))
          }
        />
      ))}
    </div>
  );
};

// ─── EventCard ──────────────────────────────────────────────────────────────

interface EventCardProps {
  event: LifecycleHookEventMeta;
  entries: HookEntry[];
  skills: SkillOption[];
  methods: BuiltInMethodDto[];
  isMethodsLoading?: boolean;
  agentId: string | null;
  onEntriesChange: (next: HookEntry[]) => void;
}

const EventCard: React.FC<EventCardProps> = ({
  event,
  entries,
  skills,
  methods,
  isMethodsLoading,
  agentId,
  onEntriesChange,
}) => {
  const isEnabled = entries.length > 0;
  const atCap = entries.length >= MAX_ENTRIES_PER_EVENT;

  const handleToggle = (enabled: boolean) => {
    if (enabled) {
      onEntriesChange([buildDefaultEntry()]);
    } else {
      onEntriesChange([]);
    }
  };

  const handleAddEntry = () => {
    if (atCap) return;
    onEntriesChange([...entries, buildDefaultEntry()]);
  };

  const handleUpdateEntry = (idx: number, next: HookEntry) => {
    const copy = entries.map((e, i) => (i === idx ? next : e));
    onEntriesChange(copy);
  };

  const handleMoveEntry = (idx: number, direction: -1 | 1) => {
    const target = idx + direction;
    if (target < 0 || target >= entries.length) return;
    const copy = [...entries];
    [copy[idx], copy[target]] = [copy[target], copy[idx]];
    onEntriesChange(copy);
  };

  const handleDeleteEntry = (idx: number) => {
    onEntriesChange(entries.filter((_, i) => i !== idx));
  };

  return (
    <Card
      className="sf-hooks-event-card"
      variant="outlined"
      title={
        <div className="sf-hooks-event-header">
          <div className="sf-hooks-event-title">
            <span className="sf-hooks-event-name">{event.id}</span>
            <span className="sf-hooks-event-display">{event.displayName}</span>
            {isEnabled && entries.length > 1 && (
              <span className="sf-hooks-entry-count">{entries.length} entries</span>
            )}
          </div>
          <Tooltip
            title={
              <div className="sf-hooks-input-schema-tip">
                <div className="sf-hooks-input-schema-label">Input schema:</div>
                {Object.entries(event.inputSchema).map(([k, v]) => (
                  <div key={k} className="sf-hooks-input-schema-row">
                    <code>{k}</code>
                    <span>{v}</span>
                  </div>
                ))}
              </div>
            }
          >
            <InfoCircleOutlined className="sf-hooks-event-info" />
          </Tooltip>
        </div>
      }
      extra={
        <Switch
          size="small"
          checked={isEnabled}
          onChange={handleToggle}
          checkedChildren="ON"
          unCheckedChildren="OFF"
        />
      }
    >
      <div className="sf-hooks-event-desc">{event.description}</div>

      {!isEnabled && (
        <div className="sf-hooks-event-empty">Toggle ON to configure this hook.</div>
      )}

      {isEnabled && (
        <div className="sf-hooks-entry-list">
          {entries.map((entry, idx) => (
            <EntryRow
              key={entry._id ?? String(idx)}
              entry={entry}
              index={idx}
              total={entries.length}
              eventId={event.id}
              skills={skills}
              methods={methods}
              isMethodsLoading={isMethodsLoading}
              agentId={agentId}
              onUpdate={(next) => handleUpdateEntry(idx, next)}
              onMoveUp={() => handleMoveEntry(idx, -1)}
              onMoveDown={() => handleMoveEntry(idx, 1)}
              onDelete={() => handleDeleteEntry(idx)}
            />
          ))}

          <Tooltip
            title={
              atCap
                ? `Maximum ${MAX_ENTRIES_PER_EVENT} entries per event — delete one to add more.`
                : ''
            }
          >
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              disabled={atCap}
              onClick={handleAddEntry}
              className="sf-hooks-entry-add"
              block
            >
              Add entry
            </Button>
          </Tooltip>
        </div>
      )}
    </Card>
  );
};

// ─── EntryRow ───────────────────────────────────────────────────────────────

interface EntryRowProps {
  entry: HookEntry;
  index: number;
  total: number;
  eventId: LifecycleHookEventId;
  skills: SkillOption[];
  methods: BuiltInMethodDto[];
  isMethodsLoading?: boolean;
  agentId: string | null;
  onUpdate: (next: HookEntry) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onDelete: () => void;
}

const EntryRow: React.FC<EntryRowProps> = ({
  entry,
  index,
  total,
  eventId,
  skills,
  methods,
  isMethodsLoading,
  agentId,
  onUpdate,
  onMoveUp,
  onMoveDown,
  onDelete,
}) => {
  const handlerType = entry.handler.type;
  const canMoveUp = index > 0;
  const canMoveDown = index < total - 1;
  const [dryRunResult, setDryRunResult] = useState<DryRunResponse | null>(null);
  const [dryRunModalOpen, setDryRunModalOpen] = useState(false);

  const dryRunMutation = useMutation({
    mutationFn: () => {
      if (!agentId) throw new Error('Agent must be saved first');
      return dryRunHook(agentId, { event: eventId, entryIndex: index });
    },
    onSuccess: (res) => {
      setDryRunResult(res.data);
      setDryRunModalOpen(true);
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Dry-run failed';
      message.error(msg);
    },
  });

  const handleHandlerTypeChange = useCallback(
    (nextType: HookHandlerType) => {
      if (nextType === handlerType) return;
      onUpdate({ ...entry, handler: buildDefaultHandler(nextType) });
    },
    [entry, handlerType, onUpdate],
  );

  const handleUpdateSkillHandler = useCallback(
    (patch: Partial<Extract<HookHandler, { type: 'skill' }>>) => {
      if (entry.handler.type !== 'skill') return;
      onUpdate({ ...entry, handler: { ...entry.handler, ...patch } });
    },
    [entry, onUpdate],
  );

  const handleUpdateScriptHandler = useCallback(
    (patch: Partial<Extract<HookHandler, { type: 'script' }>>) => {
      if (entry.handler.type !== 'script') return;
      onUpdate({ ...entry, handler: { ...entry.handler, ...patch } });
    },
    [entry, onUpdate],
  );

  const handleUpdateMethodHandler = useCallback(
    (patch: Partial<Extract<HookHandler, { type: 'method' }>>) => {
      if (entry.handler.type !== 'method') return;
      onUpdate({ ...entry, handler: { ...entry.handler, ...patch } });
    },
    [entry, onUpdate],
  );

  const handleUpdateField = useCallback(
    <K extends keyof HookEntry>(key: K, value: HookEntry[K]) => {
      if (key === 'async' && value === true && entry.failurePolicy === 'SKIP_CHAIN') {
        message.info(
          'async entries cannot use SKIP_CHAIN — switching policy to CONTINUE.',
        );
        onUpdate({ ...entry, async: true, failurePolicy: 'CONTINUE' });
        return;
      }
      onUpdate({ ...entry, [key]: value });
    },
    [entry, onUpdate],
  );

  return (
    <div className="sf-hooks-entry-row">
      <div className="sf-hooks-entry-row-header">
        <div className="sf-hooks-entry-row-title">
          <span className="sf-hooks-entry-badge">
            {total > 1 ? `entry ${index + 1}/${total}` : 'entry'}
          </span>
          <span className="sf-hooks-entry-summary">
            {HANDLER_TYPE_ICONS[handlerType]}
            <EntrySummary entry={entry} />
          </span>
        </div>
        <div className="sf-hooks-entry-actions">
          <Tooltip title={agentId ? 'Dry-run this entry' : 'Save the agent first to test'}>
            <Button
              size="small"
              type="text"
              icon={<PlayCircleOutlined />}
              onClick={() => dryRunMutation.mutate()}
              loading={dryRunMutation.isPending}
              disabled={!agentId}
              aria-label="Test entry"
              style={{ color: agentId ? 'var(--accent-primary, #6366f1)' : undefined }}
            />
          </Tooltip>
          <Tooltip title="Move up">
            <Button
              size="small"
              type="text"
              disabled={!canMoveUp}
              icon={<ArrowUpOutlined />}
              onClick={onMoveUp}
              aria-label="Move entry up"
            />
          </Tooltip>
          <Tooltip title="Move down">
            <Button
              size="small"
              type="text"
              disabled={!canMoveDown}
              icon={<ArrowDownOutlined />}
              onClick={onMoveDown}
              aria-label="Move entry down"
            />
          </Tooltip>
          <Tooltip title="Delete entry">
            <Button
              size="small"
              type="text"
              danger
              icon={<DeleteOutlined />}
              onClick={onDelete}
              aria-label="Delete entry"
            />
          </Tooltip>
        </div>
      </div>

      <div className="sf-hooks-entry-row-body">
        <HandlerTypeSelector value={handlerType} onChange={handleHandlerTypeChange} />

        {entry.handler.type === 'skill' && (
          <SkillHandlerFields
            handler={entry.handler}
            skills={skills}
            onChange={handleUpdateSkillHandler}
          />
        )}
        {entry.handler.type === 'script' && (
          <ScriptHandlerFields
            handler={entry.handler}
            onChange={handleUpdateScriptHandler}
          />
        )}
        {entry.handler.type === 'method' && (
          <MethodHandlerFields
            handler={entry.handler}
            methods={methods}
            isLoading={isMethodsLoading}
            onChange={handleUpdateMethodHandler}
          />
        )}

        <CommonFields entry={entry} eventId={eventId} onUpdate={handleUpdateField} />
      </div>

      <DryRunResultModal
        open={dryRunModalOpen}
        result={dryRunResult}
        onClose={() => { setDryRunModalOpen(false); setDryRunResult(null); }}
      />
    </div>
  );
};

// ─── Entry summary (shown in the row header) ────────────────────────────────

const EntrySummary: React.FC<{ entry: HookEntry }> = ({ entry }) => {
  const { handler, timeoutSeconds, failurePolicy, async, displayName } = entry;
  let label: string;
  if (handler.type === 'skill') {
    label = handler.skillName || '(no skill selected)';
  } else if (handler.type === 'script') {
    const preview = handler.scriptBody
      ? handler.scriptBody.trim().replace(/\s+/g, ' ').slice(0, 40)
      : '(empty script)';
    label = `${handler.scriptLang}: ${preview}${handler.scriptBody && handler.scriptBody.length > 40 ? '…' : ''}`;
  } else {
    label = handler.methodRef || '(no method)';
  }

  const badges: string[] = [`${timeoutSeconds}s`, failurePolicy];
  if (async) badges.push('async');
  if (displayName) badges.unshift(displayName);

  return (
    <span className="sf-hooks-entry-summary-text">
      <code className="sf-hooks-entry-summary-label">{label}</code>
      <span className="sf-hooks-entry-summary-meta">{badges.join(' · ')}</span>
    </span>
  );
};

// ─── Handler Type Selector ──────────────────────────────────────────────────

const HandlerTypeSelector: React.FC<{
  value: HookHandlerType;
  onChange: (next: HookHandlerType) => void;
}> = ({ value, onChange }) => {
  const handleChange = (nextType: HookHandlerType) => {
    if (nextType === value) return;
    if (nextType === 'script') {
      if (hasConfirmedScript()) {
        onChange(nextType);
        return;
      }
      Modal.confirm({
        title: 'Enable Script handler?',
        icon: <ExclamationCircleOutlined />,
        content: (
          <div className="sf-hooks-script-confirm-body">
            <p>
              Script handlers run inline bash / node code on the SkillForge
              server. Make sure the script body is reviewed and trusted before
              saving.
            </p>
            <p>
              In production deployments consider disabling Script handlers
              entirely via <code>lifecycle.hooks.script.allowed-langs: []</code>.
            </p>
          </div>
        ),
        okText: 'I understand, enable Script',
        cancelText: 'Cancel',
        onOk: () => {
          markScriptConfirmed();
          onChange(nextType);
        },
      });
      return;
    }
    onChange(nextType);
  };

  return (
    <div className="sf-hooks-field">
      <label className="sf-hooks-label">Handler Type</label>
      <Radio.Group
        value={value}
        onChange={(e) => handleChange(e.target.value as HookHandlerType)}
        optionType="button"
        buttonStyle="solid"
        size="small"
      >
        {HOOK_HANDLER_TYPE_META.map((meta) => {
          const disabled = meta.availability === 'p2';
          return (
            <Tooltip key={meta.value} title={meta.description}>
              <Radio.Button value={meta.value} disabled={disabled}>
                <Space size={4}>
                  {HANDLER_TYPE_ICONS[meta.value]}
                  <span>{meta.label}</span>
                  {meta.availability === 'p2' && (
                    <span className="sf-hooks-coming-tag">P2</span>
                  )}
                </Space>
              </Radio.Button>
            </Tooltip>
          );
        })}
      </Radio.Group>
    </div>
  );
};

// ─── Skill handler fields ───────────────────────────────────────────────────

const SkillHandlerFields: React.FC<{
  handler: Extract<HookHandler, { type: 'skill' }>;
  skills: SkillOption[];
  onChange: (patch: Partial<Extract<HookHandler, { type: 'skill' }>>) => void;
}> = ({ handler, skills, onChange }) => {
  const options = useMemo(
    () =>
      skills.map((s) => ({
        label: s.description ? `${s.name} — ${s.description}` : s.name,
        value: s.name,
      })),
    [skills],
  );

  const isEmpty = handler.skillName.trim() === '';

  return (
    <div className="sf-hooks-field">
      <label className="sf-hooks-label">Skill</label>
      {skills.length === 0 ? (
        <Empty
          description="No skills available. Upload one from the Skills page."
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      ) : (
        <>
          <Select
            value={handler.skillName || undefined}
            onChange={(next) => onChange({ skillName: next })}
            options={options}
            placeholder="Select a skill to run on this event"
            showSearch
            optionFilterProp="label"
            status={isEmpty ? 'error' : undefined}
            style={{ width: '100%' }}
          />
          {isEmpty && (
            <Typography.Text type="danger" className="sf-hooks-field-error">
              Skill is required — pick one from the list above.
            </Typography.Text>
          )}
        </>
      )}
    </div>
  );
};

// ─── Script handler fields ──────────────────────────────────────────────────

const ScriptHandlerFields: React.FC<{
  handler: Extract<HookHandler, { type: 'script' }>;
  onChange: (patch: Partial<Extract<HookHandler, { type: 'script' }>>) => void;
}> = ({ handler, onChange }) => {
  const bodyLength = handler.scriptBody.length;
  const isEmpty = handler.scriptBody.trim() === '';
  const overLimit = bodyLength > MAX_SCRIPT_BODY_BYTES;

  return (
    <div className="sf-hooks-script-fields">
      <div className="sf-hooks-field">
        <label className="sf-hooks-label">Language</label>
        <Select<ScriptLang>
          value={handler.scriptLang}
          onChange={(next) => onChange({ scriptLang: next })}
          options={SCRIPT_LANG_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
          style={{ width: 180 }}
        />
      </div>

      <div className="sf-hooks-field">
        <div className="sf-hooks-script-body-header">
          <label className="sf-hooks-label">Script Body</label>
          <span
            className={
              overLimit
                ? 'sf-hooks-script-counter sf-hooks-script-counter--over'
                : 'sf-hooks-script-counter'
            }
          >
            {bodyLength} / {MAX_SCRIPT_BODY_BYTES}
          </span>
        </div>
        <BufferedTextArea
          value={handler.scriptBody}
          rows={8}
          maxLength={MAX_SCRIPT_BODY_BYTES}
          placeholder={
            handler.scriptLang === 'bash'
              ? '#!/usr/bin/env bash\necho "hello"'
              : '// node script\nconsole.log("hello");'
          }
          onCommit={(next) => onChange({ scriptBody: next })}
          className="sf-hooks-script-body"
          status={isEmpty || overLimit ? 'error' : undefined}
        />
        {isEmpty && (
          <Typography.Text type="danger" className="sf-hooks-field-error">
            Script body is required.
          </Typography.Text>
        )}
        {overLimit && (
          <Typography.Text type="danger" className="sf-hooks-field-error">
            Script body exceeds {MAX_SCRIPT_BODY_BYTES} characters.
          </Typography.Text>
        )}
      </div>
    </div>
  );
};

// MethodHandlerFields and DryRunResultModal extracted to separate files.

// ─── Common fields (timeout / policy / async / displayName) ────────────────

const CommonFields: React.FC<{
  entry: HookEntry;
  eventId: LifecycleHookEventId;
  onUpdate: <K extends keyof HookEntry>(key: K, value: HookEntry[K]) => void;
}> = ({ entry, eventId, onUpdate }) => {
  const policies = allowedFailurePolicies(eventId);
  const policyOptions = policies.map((p) => ({
    label: formatPolicyLabel(p),
    value: p,
    // async entries cannot use SKIP_CHAIN — disable the option in that case.
    disabled: entry.async && p === 'SKIP_CHAIN',
  }));

  return (
    <>
      <div className="sf-hooks-field-row">
        <div className="sf-hooks-field">
          <label className="sf-hooks-label">Timeout (s)</label>
          <BufferedInputNumber
            min={TIMEOUT_MIN_SECONDS}
            max={TIMEOUT_MAX_SECONDS}
            value={entry.timeoutSeconds}
            onCommit={(next) =>
              onUpdate('timeoutSeconds', (next ?? TIMEOUT_DEFAULT_SECONDS) as number)
            }
          />
        </div>
        <div className="sf-hooks-field">
          <label className="sf-hooks-label">Failure Policy</label>
          <Select
            value={entry.failurePolicy}
            onChange={(next) => onUpdate('failurePolicy', next as FailurePolicy)}
            options={policyOptions}
            style={{ width: '100%' }}
          />
        </div>
      </div>
      <div className="sf-hooks-field-row">
        <div className="sf-hooks-field sf-hooks-field--inline">
          <label className="sf-hooks-label">Async</label>
          <Switch
            size="small"
            checked={entry.async}
            onChange={(next) => onUpdate('async', next)}
          />
          <span className="sf-hooks-hint">
            {entry.async ? 'Fire-and-forget' : 'Blocks main loop until complete'}
          </span>
        </div>
      </div>
      <div className="sf-hooks-field">
        <label className="sf-hooks-label">Display Name (optional)</label>
        <BufferedInput
          value={entry.displayName ?? ''}
          onCommit={(next) => onUpdate('displayName', next || undefined)}
          placeholder="e.g. Prompt Content Filter"
          maxLength={80}
        />
      </div>
    </>
  );
};

function formatPolicyLabel(policy: FailurePolicy): string {
  switch (policy) {
    case 'CONTINUE':
      return 'CONTINUE — log warn, keep going';
    case 'ABORT':
      return 'ABORT — stop main loop';
    case 'SKIP_CHAIN':
      return 'SKIP_CHAIN — skip later hooks';
  }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

function buildDefaultEntry(): HookEntry {
  return {
    handler: { type: 'skill', skillName: '' },
    timeoutSeconds: TIMEOUT_DEFAULT_SECONDS,
    failurePolicy: 'CONTINUE',
    async: false,
    _id: crypto.randomUUID(),
  };
}

function buildDefaultHandler(type: HookHandlerType): HookHandler {
  switch (type) {
    case 'skill':
      return { type: 'skill', skillName: '' };
    case 'script':
      return { type: 'script', scriptLang: 'bash', scriptBody: '' };
    case 'method':
      return { type: 'method', methodRef: '', args: {} };
  }
}

/**
 * Produce a new LifecycleHooksConfig with the given entry list written at
 * `eventId`. Other event slots are preserved as-is; missing slots are
 * backfilled with empty arrays so the shape stays stable.
 */
function replaceEntries(
  cfg: LifecycleHooksConfig,
  eventId: LifecycleHookEventId,
  entries: HookEntry[],
): LifecycleHooksConfig {
  const hooks = { ...cfg.hooks };
  for (const id of LIFECYCLE_HOOK_EVENT_IDS) {
    if (!hooks[id]) hooks[id] = [];
  }
  hooks[eventId] = entries;
  return { version: 1, hooks };
}

// ─── Script-confirm localStorage gate ──────────────────────────────────────

function hasConfirmedScript(): boolean {
  try {
    return window.localStorage.getItem(SCRIPT_CONFIRM_STORAGE_KEY) === '1';
  } catch {
    // Private-mode Safari or storage blocked — fall back to prompting each time.
    return false;
  }
}

function markScriptConfirmed(): void {
  try {
    window.localStorage.setItem(SCRIPT_CONFIRM_STORAGE_KEY, '1');
  } catch {
    // Swallow — acceptable to prompt again if storage is unavailable.
  }
}

export default FormMode;
