import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveAgentAuthoredHook,
  getAgentHooks,
  rejectAgentAuthoredHook,
  retireAgentAuthoredHook,
  setAgentAuthoredHookEnabled,
  updateAgentUserHooks,
  type AgentAuthoredHookViewDto,
  type HookViewDto,
} from '../api';
import { countHookEntries, migrateLegacyFlat } from '../constants/lifecycleHooks';
import LifecycleHooksEditor, { type LifecycleHooksEditorHandle } from './LifecycleHooksEditor';

interface SkillOption {
  name: string;
  description?: string;
}

interface LifecycleHooksPanelProps {
  agentId: number;
  fallbackRawJson: string | null | undefined;
  skills: SkillOption[];
}

export default function LifecycleHooksPanel({ agentId, fallbackRawJson, skills }: LifecycleHooksPanelProps) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ['agent-hooks', agentId],
    queryFn: () => getAgentHooks(agentId).then((r) => r.data),
  });

  const remoteUserRawJson = query.data?.user.rawJson ?? fallbackRawJson ?? null;
  const migratedInitial = useMemo(
    () => migrateLegacyFlat(remoteUserRawJson),
    [remoteUserRawJson],
  );

  const editorRef = useRef<LifecycleHooksEditorHandle>(null);
  const [liveRawJson, setLiveRawJson] = useState('');
  const [baselineJson, setBaselineJson] = useState<string | null>(null);
  const errorsRef = useRef<string[]>([]);
  const [hasErrors, setHasErrors] = useState(false);
  const migrationToastFiredRef = useRef(false);

  useEffect(() => {
    setLiveRawJson('');
    setBaselineJson(null);
    setHasErrors(false);
    errorsRef.current = [];
    migrationToastFiredRef.current = false;
  }, [agentId, remoteUserRawJson]);

  const handleRawChange = useCallback((raw: string) => {
    setLiveRawJson(raw);
    setBaselineJson((prev) => (prev === null ? raw : prev));
  }, []);

  const handleErrorsChange = useCallback((errors: string[]) => {
    errorsRef.current = errors;
    setHasErrors(errors.length > 0);
  }, []);

  useEffect(() => {
    if (migrationToastFiredRef.current || baselineJson === null) return;
    migrationToastFiredRef.current = true;
    const { migratedCount, droppedCount, reasons } = migratedInitial;
    if (migratedCount + droppedCount > 0) {
      message.warning(
        `Migrated ${migratedCount} legacy hook ${migratedCount === 1 ? 'entry' : 'entries'}; dropped ${droppedCount}.`,
      );
      if (reasons.length > 0) {
        message.info(`Hook migration details: ${reasons.join('; ')}`);
      }
    }
  }, [baselineJson, migratedInitial]);

  const saveMutation = useMutation({
    mutationFn: (rawJson: string) => updateAgentUserHooks(agentId, rawJson),
    onSuccess: (_, snapshot) => {
      setBaselineJson(snapshot);
      queryClient.invalidateQueries({ queryKey: ['agent-hooks', agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      message.success('Hooks updated');
    },
    onError: () => message.error('Failed to update hooks'),
  });

  const approveMutation = useMutation({
    mutationFn: (id: number) => approveAgentAuthoredHook(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-hooks', agentId] });
      message.success('Hook approved');
    },
    onError: () => message.error('Failed to approve hook'),
  });

  const rejectMutation = useMutation({
    mutationFn: (id: number) => rejectAgentAuthoredHook(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-hooks', agentId] });
      message.success('Hook rejected');
    },
    onError: () => message.error('Failed to reject hook'),
  });

  const retireMutation = useMutation({
    mutationFn: (id: number) => retireAgentAuthoredHook(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-hooks', agentId] });
      message.success('Hook retired');
    },
    onError: () => message.error('Failed to retire hook'),
  });

  const enableMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
      setAgentAuthoredHookEnabled(id, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-hooks', agentId] });
    },
    onError: () => message.error('Failed to update hook state'),
  });

  const hooksDirty = baselineJson !== null && liveRawJson !== baselineJson;
  const userCount = useMemo(() => countHookEntries(liveRawJson), [liveRawJson]);
  const dispatchableCount = query.data?.counts.dispatchable ?? userCount;
  const pendingCount = query.data?.counts.agentAuthored.PENDING ?? 0;

  const handleSave = () => {
    if (errorsRef.current.length > 0) return;
    saveMutation.mutate(liveRawJson);
  };

  const handleRevert = () => {
    if (baselineJson === null) return;
    editorRef.current?.setRawJson(baselineJson);
    setLiveRawJson(baselineJson);
  };

  return (
    <>
      <div className="spec-h">
        <h3>
          Lifecycle hooks <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-4)' }}>— {dispatchableCount}</span>
        </h3>
        {pendingCount > 0 && (
          <span className="chip-pill-sf warning">{pendingCount} pending</span>
        )}
      </div>

      {query.isError && (
        <div className="spec-block" style={{ color: 'var(--color-error)' }}>
          Failed to load effective hooks. User hook editing is still available.
        </div>
      )}

      <ReadOnlyHookSection title="System hooks" entries={query.data?.system.entries ?? []} />

      <div className="spec-block">
        <div className="spec-h">
          <h3>User hooks <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-4)' }}>— {userCount}</span></h3>
        </div>
        <LifecycleHooksEditor
          key={`${agentId}:${remoteUserRawJson ?? ''}`}
          ref={editorRef}
          initialJson={migratedInitial.json}
          skills={skills}
          agentId={String(agentId)}
          onRawJsonChange={handleRawChange}
          onErrorsChange={handleErrorsChange}
        />
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <button
            className="btn-ghost-sf"
            onClick={handleRevert}
            disabled={!hooksDirty || saveMutation.isPending}
          >
            Revert
          </button>
          <button
            className="btn-primary-sf"
            onClick={handleSave}
            disabled={!hooksDirty || hasErrors || saveMutation.isPending}
            title={hasErrors ? 'Fix JSON validation errors before saving.' : undefined}
          >
            {hasErrors ? 'Fix JSON first' : hooksDirty ? 'Save' : 'Saved'}
          </button>
        </div>
      </div>

      <AgentAuthoredSection
        entries={query.data?.agentAuthored.entries ?? []}
        onApprove={(id) => approveMutation.mutate(id)}
        onReject={(id) => rejectMutation.mutate(id)}
        onRetire={(id) => retireMutation.mutate(id)}
        onToggle={(id, enabled) => enableMutation.mutate({ id, enabled })}
      />
    </>
  );
}

function ReadOnlyHookSection({ title, entries }: { title: string; entries: HookViewDto[] }) {
  return (
    <div className="spec-block">
      <div className="spec-h">
        <h3>{title} <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-4)' }}>— {entries.length}</span></h3>
      </div>
      {entries.length === 0 ? (
        <div style={{ color: 'var(--fg-4)', fontSize: 13 }}>None</div>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {entries.map((entry) => (
            <HookRow key={entry.sourceId} entry={entry} badge={entry.source} />
          ))}
        </div>
      )}
    </div>
  );
}

function AgentAuthoredSection({
  entries,
  onApprove,
  onReject,
  onRetire,
  onToggle,
}: {
  entries: AgentAuthoredHookViewDto[];
  onApprove: (id: number) => void;
  onReject: (id: number) => void;
  onRetire: (id: number) => void;
  onToggle: (id: number, enabled: boolean) => void;
}) {
  return (
    <div className="spec-block">
      <div className="spec-h">
        <h3>Agent-authored hooks <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-4)' }}>— {entries.length}</span></h3>
      </div>
      {entries.length === 0 ? (
        <div style={{ color: 'var(--fg-4)', fontSize: 13 }}>None</div>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {entries.map((entry) => (
            <div key={entry.id} className="spec-card">
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                <HookRow entry={entryToHookView(entry)} badge={entry.reviewState.toLowerCase()} />
                <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                  {entry.reviewState === 'PENDING' && (
                    <>
                      <button className="btn-primary-sf" onClick={() => onApprove(entry.id)}>Approve</button>
                      <button className="btn-ghost-sf" onClick={() => onReject(entry.id)}>Reject</button>
                    </>
                  )}
                  {entry.reviewState === 'APPROVED' && (
                    <>
                      <button className="btn-ghost-sf" onClick={() => onToggle(entry.id, !entry.enabled)}>
                        {entry.enabled ? 'Disable' : 'Enable'}
                      </button>
                      <button className="btn-ghost-sf" onClick={() => onRetire(entry.id)}>Retire</button>
                    </>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function HookRow({ entry, badge }: { entry: HookViewDto; badge: string }) {
  const name = entry.displayName || entry.handlerSummary?.name || entry.sourceId;
  const type = entry.handlerSummary?.type || 'hook';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
      <span className="chip-pill-sf">{badge}</span>
      <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{name}</span>
      <span style={{ color: 'var(--fg-4)', fontSize: 12 }}>{entry.event}</span>
      <span style={{ color: 'var(--fg-4)', fontSize: 12 }}>{type}</span>
      {entry.async && <span style={{ color: 'var(--fg-4)', fontSize: 12 }}>async</span>}
    </div>
  );
}

function entryToHookView(entry: AgentAuthoredHookViewDto): HookViewDto {
  return {
    event: entry.event,
    source: 'agent',
    sourceId: entry.sourceId,
    displayName: entry.displayName,
    timeoutSeconds: entry.timeoutSeconds,
    failurePolicy: entry.failurePolicy,
    async: entry.async,
    handlerSummary: { type: 'method', name: entry.methodRef },
    agentAuthoredHookId: entry.id,
    authorAgentId: entry.authorAgentId,
    reviewState: entry.reviewState,
    readOnly: true,
    dispatchEnabled: entry.dispatchEnabled,
  };
}
