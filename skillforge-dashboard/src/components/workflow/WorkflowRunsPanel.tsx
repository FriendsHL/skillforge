import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getWorkflowRun,
  listWorkflowRuns,
  listWorkflows,
  type WorkflowDto,
  type WorkflowRunDetail,
  type WorkflowRunSummary,
  type WorkflowStep,
} from '../../api/workflow';
import { useAuth } from '../../contexts/AuthContext';
import WorkflowDag from './WorkflowDag';
import WorkflowStepDrawer from './WorkflowStepDrawer';
import './workflow.css';

const RUNS_LIMIT = 30;
const LIVE_POLL_MS = 2000;

/** Run-status values that mean the run is still progressing (poll + skeleton). */
const LIVE_STATUSES = new Set(['pending', 'running', 'paused']);

function statusClass(status: string): string {
  switch (status) {
    case 'completed':
      return 'wf-chip--completed';
    case 'error':
      return 'wf-chip--error';
    case 'paused':
      return 'wf-chip--paused';
    case 'running':
      return 'wf-chip--running';
    default:
      return 'wf-chip--pending';
  }
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '—';
  const diff = Date.now() - t;
  if (diff < 0) return 'just now';
  const min = Math.floor(diff / 60000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}

/** Minimal WS frame shape we care about (workflow + flywheel run events). */
interface WorkflowWsFrame {
  type?: string;
  runId?: string;
  loopKind?: string;
}

const WorkflowRunsPanel: React.FC = () => {
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const selectedRunIdRef = useRef<string | null>(null);
  // Q2 — the step whose detail drawer is open (null = closed). Tagged with the
  // run it was selected under so switching runs auto-hides a stale selection at
  // render time (no setState-in-effect — mirrors the effectiveSelectedRunId
  // derive-at-render pattern below).
  const [selectedStep, setSelectedStep] = useState<{
    runId: string;
    step: WorkflowStep;
  } | null>(null);
  // Stable identity so the drawer's Esc-listener useEffect doesn't tear down /
  // re-add on every parent re-render.
  const closeStepDrawer = useCallback(() => setSelectedStep(null), []);

  // ── Workflow definitions (for phase skeleton + ordering) ──
  const { data: workflowsResp } = useQuery({
    queryKey: ['workflows', 'definitions'],
    queryFn: () => listWorkflows().then((r) => r.data),
    staleTime: 5 * 60_000,
  });
  const definitionsByName = useMemo(() => {
    const map = new Map<string, WorkflowDto>();
    for (const w of workflowsResp?.items ?? []) map.set(w.name, w);
    return map;
  }, [workflowsResp]);

  // ── Runs list ──
  const {
    data: runsResp,
    isLoading: runsLoading,
    isError: runsError,
    error: runsErrObj,
  } = useQuery({
    queryKey: ['workflow-runs', RUNS_LIMIT],
    queryFn: () => listWorkflowRuns({ limit: RUNS_LIMIT }).then((r) => r.data),
    staleTime: 10_000,
  });
  const runs: WorkflowRunSummary[] = useMemo(
    () => runsResp?.items ?? [],
    [runsResp],
  );

  // Derive at render time — no effect needed (avoids react-hooks/set-state-in-effect).
  const effectiveSelectedRunId = selectedRunId ?? (runs.length > 0 ? runs[0].runId : null);

  // Keep a ref in sync so the WS onmessage handler can read the current run
  // without forcing a reconnect every time the user switches runs (W1).
  useEffect(() => {
    selectedRunIdRef.current = effectiveSelectedRunId;
  }, [effectiveSelectedRunId]);

  // ── Selected run detail ──
  const {
    data: detail,
    isLoading: detailLoading,
    isError: detailError,
    error: detailErrObj,
  } = useQuery<WorkflowRunDetail>({
    queryKey: ['workflow-run', effectiveSelectedRunId],
    queryFn: () => getWorkflowRun(effectiveSelectedRunId as string).then((r) => r.data),
    enabled: effectiveSelectedRunId != null,
    // Poll while the run is still progressing; stop once terminal.
    refetchInterval: (query) => {
      const d = query.state.data as WorkflowRunDetail | undefined;
      return d && LIVE_STATUSES.has(d.status) ? LIVE_POLL_MS : false;
    },
  });

  const phaseOrder = useMemo<string[] | undefined>(() => {
    if (!detail?.name) return undefined;
    const def = definitionsByName.get(detail.name);
    if (!def) return undefined;
    return def.phases.map((p) => p.title);
  }, [detail, definitionsByName]);

  // ── WS live updates (frontend.md footgun #2: cleanup MUST close) ──
  useEffect(() => {
    if (!userId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );

    ws.onmessage = (ev) => {
      let frame: WorkflowWsFrame;
      try {
        frame = JSON.parse(ev.data) as WorkflowWsFrame;
      } catch {
        return; // ignore malformed frames
      }
      const type = frame.type;
      if (
        type !== 'flywheel_run_status_changed' &&
        type !== 'workflow_phase' &&
        type !== 'workflow_log' &&
        type !== 'workflow_human_approve_required'
      ) {
        return;
      }
      // flywheel_run_status_changed fires for every loop kind; only refresh the
      // runs list for workflow-kind runs (or when loopKind is absent — be safe).
      if (type === 'flywheel_run_status_changed') {
        if (frame.loopKind == null || frame.loopKind === 'workflow') {
          queryClient.invalidateQueries({ queryKey: ['workflow-runs', RUNS_LIMIT] });
        }
      }
      // Refresh the open run's detail when the event targets it.
      // Read from ref so this handler never stales without reconnecting (W1).
      if (frame.runId != null && frame.runId === selectedRunIdRef.current) {
        queryClient.invalidateQueries({ queryKey: ['workflow-run', frame.runId] });
      }
    };

    return () => {
      try {
        ws.close();
      } catch {
        /* ignore */
      }
    };
  }, [userId, queryClient]);

  const runsErrMsg =
    runsError && runsErrObj instanceof Error ? runsErrObj.message : null;
  const detailErrMsg =
    detailError && detailErrObj instanceof Error ? detailErrObj.message : null;

  return (
    <div className="wf-panel" data-testid="workflow-runs-panel">
      {/* Left — runs list */}
      <aside className="wf-runs-list" aria-label="Workflow runs">
        <div className="wf-runs-head">
          <span className="wf-runs-head-title">Runs</span>
          <span className="wf-runs-head-count">
            {runsResp?.total != null ? runsResp.total : runs.length}
          </span>
        </div>

        {runsLoading && <div className="wf-runs-hint">Loading runs…</div>}
        {runsErrMsg && (
          <div className="wf-runs-hint wf-runs-hint--error">{runsErrMsg}</div>
        )}
        {!runsLoading && !runsErrMsg && runs.length === 0 && (
          <div className="wf-runs-hint">No workflow runs yet.</div>
        )}

        <ul className="wf-runs-ul">
          {runs.map((r) => {
            const active = r.runId === effectiveSelectedRunId;
            return (
              <li key={r.runId}>
                <button
                  type="button"
                  className={`wf-run-row${active ? ' wf-run-row--active' : ''}`}
                  onClick={() => setSelectedRunId(r.runId)}
                  data-testid={`wf-run-row-${r.runId}`}
                >
                  <div className="wf-run-row-top">
                    <span className="wf-run-name">{r.name ?? '(unnamed)'}</span>
                    <span className={`wf-chip ${statusClass(r.status)}`}>
                      {r.status}
                    </span>
                  </div>
                  <div className="wf-run-row-bottom">
                    <span className="wf-run-id" title={r.runId}>
                      {r.runId.slice(0, 8)}
                    </span>
                    <span className="wf-run-time">{formatTime(r.createdAt)}</span>
                  </div>
                </button>
              </li>
            );
          })}
        </ul>
      </aside>

      {/* Right — selected run DAG */}
      <section className="wf-detail" aria-label="Workflow run detail">
        {effectiveSelectedRunId == null ? (
          <div className="wf-detail-hint">
            Select a run from the list to inspect its DAG.
          </div>
        ) : detailLoading && !detail ? (
          <div className="wf-detail-hint">Loading run…</div>
        ) : detailErrMsg ? (
          <div className="wf-detail-hint wf-detail-hint--error">{detailErrMsg}</div>
        ) : detail ? (
          <>
            <header className="wf-detail-head">
              <div className="wf-detail-head-text">
                <h2 className="wf-detail-title">{detail.name ?? '(unnamed workflow)'}</h2>
                <span className="wf-detail-runid" title={detail.runId}>
                  {detail.runId}
                </span>
              </div>
              <span className={`wf-chip ${statusClass(detail.status)}`}>
                {detail.status}
              </span>
            </header>

            {detail.status === 'error' && detail.errorReason && (
              <div className="wf-detail-error" role="alert">
                {detail.errorReason}
              </div>
            )}

            <WorkflowDag
              steps={detail.steps}
              runStatus={detail.status}
              phaseOrder={phaseOrder}
              onStepClick={(s) => setSelectedStep({ runId: detail.runId, step: s })}
            />

            <WorkflowStepDrawer
              step={
                selectedStep && selectedStep.runId === detail.runId
                  ? selectedStep.step
                  : null
              }
              runId={detail.runId}
              runStatus={detail.status}
              workflowName={detail.name}
              onClose={closeStepDrawer}
            />
          </>
        ) : null}
      </section>
    </div>
  );
};

export default WorkflowRunsPanel;
