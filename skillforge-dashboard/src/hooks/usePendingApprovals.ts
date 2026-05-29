import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  getWorkflowRun,
  listWorkflowRuns,
  type WorkflowRunDetail,
} from '../api/workflow';

/**
 * AUTOEVOLVING V1 Sprint 4 — pending humanApprove gates for the /autoevolving
 * page (sprint4-plan-draft.md §2 难题1).
 *
 * Sources are dual (plan §2):
 *   1. On mount — `listWorkflowRuns({status:'paused'})` + per-run detail to
 *      recover the payload for runs that parked before the WS connected.
 *   2. Live — `workflow_human_approve_required` WS frames carry the payload
 *      inline, appended as they arrive.
 *
 * Cards are keyed by `runId + stepIndex` (stepIndex is monotonic, so the same
 * run hitting a later gate produces a distinct card — no串台). `removeApproval`
 * drops a card after the operator resolves it; the next WS frame / paused
 * re-fetch re-populates if another gate opens.
 *
 * W1 (resolved): be-dev exposes the humanApprove payload as
 * `WorkflowStepDto.payload` on the pending `human_approve` step (parsed from
 * `step_input_json.payload`) — same shape as the WS frame's `payload`.
 */
export interface PendingApproval {
  runId: string;
  stepIndex: number | null;
  payload: unknown;
  workflowName: string | null;
}

function approvalKey(runId: string, stepIndex: number | null): string {
  return `${runId}::${stepIndex ?? 'x'}`;
}

/** Extract the pending human_approve gate (payload + index) from a run detail. */
function extractPending(detail: WorkflowRunDetail): PendingApproval | null {
  const steps = detail.steps ?? [];
  // Last pending human_approve step wins (a run parks at one gate at a time).
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i];
    if (s.stepKind === 'human_approve' && s.status === 'pending') {
      return {
        runId: detail.runId,
        stepIndex: s.stepIndex,
        payload: s.payload ?? null,
        workflowName: detail.name,
      };
    }
  }
  return null;
}

/** WS frame for `workflow_human_approve_required` (plan §1.1). */
interface ApproveWsFrame {
  type?: string;
  runId?: string;
  stepIndex?: number | null;
  payload?: unknown;
}

export function usePendingApprovals(userId: number): {
  approvals: PendingApproval[];
  removeApproval: (runId: string, stepIndex: number | null) => void;
} {
  const [byKey, setByKey] = useState<Record<string, PendingApproval>>({});
  // Keys the operator has resolved — never re-add them from a stale re-fetch.
  const resolvedRef = useRef<Set<string>>(new Set());

  const upsert = useCallback((a: PendingApproval) => {
    const key = approvalKey(a.runId, a.stepIndex);
    if (resolvedRef.current.has(key)) return;
    setByKey((prev) => {
      const existing = prev[key];
      // Prefer a non-null payload if one source had it and the other didn't.
      if (existing && existing.payload != null && a.payload == null) return prev;
      return { ...prev, [key]: a };
    });
  }, []);

  const removeApproval = useCallback(
    (runId: string, stepIndex: number | null) => {
      const key = approvalKey(runId, stepIndex);
      resolvedRef.current.add(key);
      setByKey((prev) => {
        if (!(key in prev)) return prev;
        const next = { ...prev };
        delete next[key];
        return next;
      });
    },
    [],
  );

  // ── Source 1: paused runs on mount / refresh ──
  const { data: pausedResp } = useQuery({
    queryKey: ['workflow-runs', 'paused'],
    queryFn: () => listWorkflowRuns({ status: 'paused' }).then((r) => r.data),
    staleTime: 10_000,
  });

  const pausedRunIds = useMemo(
    () => (pausedResp?.items ?? []).map((r) => r.runId),
    [pausedResp],
  );
  // Stable string key so the effect doesn't refire on array identity changes.
  const pausedKey = pausedRunIds.join(',');

  useEffect(() => {
    if (pausedRunIds.length === 0) return;
    let cancelled = false;
    Promise.all(
      pausedRunIds.map((id) =>
        getWorkflowRun(id)
          .then((r) => r.data)
          .catch(() => null),
      ),
    ).then((details) => {
      if (cancelled) return;
      for (const d of details) {
        if (!d) continue;
        const pending = extractPending(d);
        if (pending) upsert(pending);
      }
    });
    return () => {
      cancelled = true;
    };
    // pausedKey captures the run-id set; upsert is stable.
  }, [pausedKey, pausedRunIds, upsert]);

  // ── Source 2: live WS frames (frontend.md footgun #2 — cleanup closes) ──
  useEffect(() => {
    if (!userId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      let frame: ApproveWsFrame;
      try {
        frame = JSON.parse(ev.data) as ApproveWsFrame;
      } catch {
        return;
      }
      if (frame.type !== 'workflow_human_approve_required') return;
      if (!frame.runId) return;
      upsert({
        runId: frame.runId,
        stepIndex: frame.stepIndex ?? null,
        payload: frame.payload ?? null,
        workflowName: null,
      });
    };
    return () => {
      try {
        ws.close();
      } catch {
        /* ignore */
      }
    };
  }, [userId, upsert]);

  const approvals = useMemo(() => Object.values(byKey), [byKey]);
  return { approvals, removeApproval };
}
