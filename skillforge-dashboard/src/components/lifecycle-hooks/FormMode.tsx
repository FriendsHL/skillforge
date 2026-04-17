import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Card,
  Radio,
  Select,
  InputNumber,
  Switch,
  Input,
  Tooltip,
  Tag,
  Space,
  Empty,
  Typography,
} from 'antd';
import {
  InfoCircleOutlined,
  ApiOutlined,
  CodeOutlined,
  FunctionOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import {
  HOOK_HANDLER_TYPE_META,
  LIFECYCLE_HOOK_EVENT_IDS,
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
} from '../../constants/lifecycleHooks';
import { useDebouncedCallback } from '../../hooks/useDebouncedCallback';

const FIELD_COMMIT_DEBOUNCE_MS = 200;

interface SkillOption {
  name: string;
  description?: string;
}

interface FormModeProps {
  parsed: LifecycleHooksConfig | null;
  errors: string[];
  events: LifecycleHookEventMeta[];
  skills: SkillOption[];
  /** Called when any field changes — caller re-serializes to rawJson. */
  onConfigChange: (next: LifecycleHooksConfig) => void;
}

const HANDLER_TYPE_ICONS: Record<HookHandlerType, React.ReactNode> = {
  skill: <ApiOutlined />,
  script: <CodeOutlined />,
  method: <FunctionOutlined />,
};

/**
 * Form mode: one Card per event. P0 constraint — list length ≤ 1 per event.
 * Handler type selector shows all 3 options with Script / Method disabled.
 */
const FormMode: React.FC<FormModeProps> = ({
  parsed,
  errors,
  events,
  skills,
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
          entry={parsed.hooks[event.id]?.[0] ?? null}
          skills={skills}
          onEntryChange={(next) =>
            onConfigChange(updateEntry(parsed, event.id, next))
          }
        />
      ))}
    </div>
  );
};

// ─── EventCard ──────────────────────────────────────────────────────────────

interface EventCardProps {
  event: LifecycleHookEventMeta;
  entry: HookEntry | null;
  skills: SkillOption[];
  onEntryChange: (next: HookEntry | null) => void;
}

const EventCard: React.FC<EventCardProps> = ({ event, entry, skills, onEntryChange }) => {
  const isEnabled = entry !== null;
  const handlerType = entry?.handler?.type ?? 'skill';

  const handleToggle = (enabled: boolean) => {
    if (enabled) {
      onEntryChange(buildDefaultEntry());
    } else {
      onEntryChange(null);
    }
  };

  const handleHandlerTypeChange = (nextType: HookHandlerType) => {
    if (!entry) return;
    // Preserve common fields (timeoutSeconds / failurePolicy / async / displayName).
    // Reset handler-specific fields only.
    onEntryChange({
      ...entry,
      handler: buildDefaultHandler(nextType),
    });
  };

  const updateHandler = (patch: Partial<HookHandler>) => {
    if (!entry) return;
    onEntryChange({
      ...entry,
      handler: { ...entry.handler, ...patch } as HookHandler,
    });
  };

  const updateEntryField = <K extends keyof HookEntry>(key: K, value: HookEntry[K]) => {
    if (!entry) return;
    onEntryChange({ ...entry, [key]: value });
  };

  return (
    <Card
      className="sf-hooks-event-card"
      bordered
      title={
        <div className="sf-hooks-event-header">
          <div className="sf-hooks-event-title">
            <span className="sf-hooks-event-name">{event.id}</span>
            <span className="sf-hooks-event-display">{event.displayName}</span>
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

      {isEnabled && entry && (
        <div className="sf-hooks-event-body">
          <HandlerTypeSelector value={handlerType} onChange={handleHandlerTypeChange} />

          {handlerType === 'skill' && entry.handler.type === 'skill' && (
            <SkillHandlerFields
              handler={entry.handler}
              skills={skills}
              onChange={(patch) => updateHandler(patch)}
            />
          )}
          {handlerType === 'script' && <DisabledHandlerPlaceholder type="script" />}
          {handlerType === 'method' && <DisabledHandlerPlaceholder type="method" />}

          <CommonFields
            entry={entry}
            eventId={event.id}
            onUpdate={updateEntryField}
          />
        </div>
      )}
    </Card>
  );
};

// ─── Handler Type Selector ──────────────────────────────────────────────────

const HandlerTypeSelector: React.FC<{
  value: HookHandlerType;
  onChange: (next: HookHandlerType) => void;
}> = ({ value, onChange }) => (
  <div className="sf-hooks-field">
    <label className="sf-hooks-label">Handler Type</label>
    <Radio.Group
      value={value}
      onChange={(e) => onChange(e.target.value as HookHandlerType)}
      optionType="button"
      buttonStyle="solid"
      size="small"
    >
      {HOOK_HANDLER_TYPE_META.map((meta) => {
        const disabled = meta.availability !== 'available';
        return (
          <Tooltip key={meta.value} title={meta.description}>
            <Radio.Button value={meta.value} disabled={disabled}>
              <Space size={4}>
                {HANDLER_TYPE_ICONS[meta.value]}
                <span>{meta.label}</span>
                {meta.availability === 'p1' && (
                  <Tag className="sf-hooks-coming-tag" color="default">
                    coming in P1
                  </Tag>
                )}
                {meta.availability === 'p2' && (
                  <Tag className="sf-hooks-coming-tag" color="default">
                    coming in P2
                  </Tag>
                )}
              </Space>
            </Radio.Button>
          </Tooltip>
        );
      })}
    </Radio.Group>
  </div>
);

// ─── Skill handler fields ───────────────────────────────────────────────────

const SkillHandlerFields: React.FC<{
  handler: Extract<HookHandler, { type: 'skill' }>;
  skills: SkillOption[];
  onChange: (patch: Partial<HookHandler>) => void;
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

// ─── Disabled handler placeholders ──────────────────────────────────────────

const DisabledHandlerPlaceholder: React.FC<{ type: 'script' | 'method' }> = ({ type }) => (
  <div className="sf-hooks-disabled-placeholder">
    <CodeOutlined />
    <span>
      {type === 'script'
        ? 'Script handler is coming in P1 (bash / node / python sub-process with sandbox).'
        : 'Method handler is coming in P2 (platform built-in methods: log, http, feishu, etc).'}
    </span>
  </div>
);

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

// ─── Buffered inputs (character-level debounce) ─────────────────────────────
// Parent commits hit the rawJson source of truth + Zod reparse, so debouncing
// keystrokes keeps the editor responsive on busy configs. Select / Switch /
// Radio stay synchronous so the Save button always sees the latest choice.

interface BufferedInputProps {
  value: string;
  onCommit: (next: string) => void;
  placeholder?: string;
  maxLength?: number;
}

const BufferedInput: React.FC<BufferedInputProps> = ({
  value,
  onCommit,
  placeholder,
  maxLength,
}) => {
  const [local, setLocal] = useState(value);
  const lastExternalRef = useRef(value);
  const [debouncedCommit, flush] = useDebouncedCallback(onCommit, FIELD_COMMIT_DEBOUNCE_MS);

  useEffect(() => {
    if (value !== lastExternalRef.current) {
      lastExternalRef.current = value;
      setLocal(value);
    }
  }, [value]);

  return (
    <Input
      value={local}
      onChange={(e) => {
        const next = e.target.value;
        lastExternalRef.current = next;
        setLocal(next);
        debouncedCommit(next);
      }}
      onBlur={flush}
      placeholder={placeholder}
      maxLength={maxLength}
    />
  );
};

interface BufferedInputNumberProps {
  value: number;
  min?: number;
  max?: number;
  onCommit: (next: number | null) => void;
}

const BufferedInputNumber: React.FC<BufferedInputNumberProps> = ({
  value,
  min,
  max,
  onCommit,
}) => {
  const [local, setLocal] = useState<number | null>(value);
  const lastExternalRef = useRef<number | null>(value);
  const [debouncedCommit, flush] = useDebouncedCallback(onCommit, FIELD_COMMIT_DEBOUNCE_MS);

  useEffect(() => {
    if (value !== lastExternalRef.current) {
      lastExternalRef.current = value;
      setLocal(value);
    }
  }, [value]);

  return (
    <InputNumber
      min={min}
      max={max}
      value={local ?? undefined}
      onChange={(next) => {
        const nextValue = (next ?? null) as number | null;
        lastExternalRef.current = nextValue;
        setLocal(nextValue);
        debouncedCommit(nextValue);
      }}
      onBlur={flush}
      style={{ width: '100%' }}
    />
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
    timeoutSeconds: 30,
    failurePolicy: 'CONTINUE',
    async: false,
  };
}

function buildDefaultHandler(type: HookHandlerType): HookHandler {
  switch (type) {
    case 'skill':
      return { type: 'skill', skillName: '' };
    case 'script':
      return { type: 'script', scriptLang: 'bash', scriptBody: '' };
    case 'method':
      return { type: 'method', methodRef: '' };
  }
}

/**
 * Produce a new LifecycleHooksConfig with `entry` written at `eventId` slot 0.
 * null entry → empty list for that event. Other event slots are preserved as-is.
 */
function updateEntry(
  cfg: LifecycleHooksConfig,
  eventId: LifecycleHookEventId,
  entry: HookEntry | null,
): LifecycleHooksConfig {
  const hooks = { ...cfg.hooks };
  for (const id of LIFECYCLE_HOOK_EVENT_IDS) {
    if (!hooks[id]) hooks[id] = [];
  }
  hooks[eventId] = entry ? [entry] : [];
  return { version: 1, hooks };
}

export default FormMode;
