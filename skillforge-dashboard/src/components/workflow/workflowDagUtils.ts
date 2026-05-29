import type { WorkflowStep } from '../../api/workflow';
import type { WorkflowNodeStatus } from './WorkflowNode';

export const STEP_KIND_HUMAN_APPROVE = 'human_approve';
const UNPHASED_TITLE = '(unphased)';

export interface PhaseGroup {
  title: string;
  steps: WorkflowStep[];
}

/**
 * Group steps into ordered phase columns.
 *
 * Ordering priority:
 *   1. `phaseOrder` (the workflow definition's meta.phases titles) — keeps the
 *      full skeleton visible even before a phase has produced any step.
 *   2. phases first-seen among the steps (sorted by stepIndex), appended after.
 *   3. steps with a null phase bucket under `(unphased)` (appended last).
 *
 * Linear fallback: when NO phase information exists at all (phase field not yet
 * deployed by be-dev, or all-legacy rows), each step becomes its own single-step
 * group keyed by its agent slug so the DAG still renders a readable chain.
 */
export function groupStepsByPhase(
  steps: WorkflowStep[],
  phaseOrder?: string[],
): { groups: PhaseGroup[]; linear: boolean } {
  const ordered = [...steps].sort(
    (a, b) => (a.stepIndex ?? 0) - (b.stepIndex ?? 0),
  );
  const hasAnyPhase =
    ordered.some((s) => s.phase != null && s.phase !== '') ||
    (phaseOrder?.length ?? 0) > 0;

  if (!hasAnyPhase) {
    // Linear fallback — one column per step.
    const groups: PhaseGroup[] = ordered.map((s, i) => ({
      title: s.agentSlug ?? `step ${s.stepIndex ?? i}`,
      steps: [s],
    }));
    return { groups, linear: true };
  }

  const titles: string[] = [];
  const seen = new Set<string>();
  for (const t of phaseOrder ?? []) {
    if (!seen.has(t)) {
      seen.add(t);
      titles.push(t);
    }
  }
  for (const s of ordered) {
    const t = s.phase != null && s.phase !== '' ? s.phase : UNPHASED_TITLE;
    if (!seen.has(t)) {
      seen.add(t);
      titles.push(t);
    }
  }

  const byTitle = new Map<string, WorkflowStep[]>();
  for (const t of titles) byTitle.set(t, []);
  for (const s of ordered) {
    const t = s.phase != null && s.phase !== '' ? s.phase : UNPHASED_TITLE;
    byTitle.get(t)?.push(s);
  }

  const groups: PhaseGroup[] = titles.map((title) => ({
    title,
    steps: byTitle.get(title) ?? [],
  }));
  return { groups, linear: false };
}

/**
 * Map a run/step/node status string to its `.wf-chip--X` CSS class. Shared by
 * WorkflowRunsPanel (run + run-detail chips) and WorkflowStepDrawer (step chip)
 * so the status→colour mapping lives in one place.
 */
export function statusChipClass(status: string): string {
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

/**
 * Build directed edge pairs for the single-flow step chain: connect every
 * consecutive pair of phase columns. `columns[i]` is the node-id list of phase
 * column i (1 node, a parallel group of N, or a single ghost node).
 *
 * Fan rules (C):
 *   - 1 → N  (fan-out):     A→B1, A→B2, … An
 *   - N → 1  (fan-in):      A1→B, A2→B, … An→B
 *   - 1 → 1:                A→B
 *   - N → M  (both parallel): index-aligned Ai→Bi with the shorter side clamped
 *     to its last node. opt-report alternates single/parallel phases so this
 *     branch isn't normally hit; it's a best-effort fallback (documented
 *     limitation — no true cross-product for arbitrary N×M).
 *
 * Empty columns are skipped (a ghost column always has exactly 1 node, so it
 * participates as a single node).
 */
export function buildChainEdgePairs(columns: string[][]): Array<[string, string]> {
  const pairs: Array<[string, string]> = [];
  for (let i = 0; i < columns.length - 1; i++) {
    const prev = columns[i];
    const curr = columns[i + 1];
    if (prev.length === 0 || curr.length === 0) continue;
    if (prev.length === 1 || curr.length === 1) {
      for (const a of prev) for (const b of curr) pairs.push([a, b]);
    } else {
      const m = Math.max(prev.length, curr.length);
      for (let k = 0; k < m; k++) {
        pairs.push([
          prev[Math.min(k, prev.length - 1)],
          curr[Math.min(k, curr.length - 1)],
        ]);
      }
    }
  }
  return pairs;
}

/** Map a single step to a node status (see WorkflowNodeStatus doc). */
export function deriveAgentStatus(
  step: WorkflowStep,
  runStatus: string,
): WorkflowNodeStatus {
  if (step.status === 'completed') return 'completed';
  if (step.status === 'error') return 'error';
  // step.status === 'pending' (or unknown) below
  if (step.stepKind === STEP_KIND_HUMAN_APPROVE) {
    return runStatus === 'paused' ? 'paused' : 'pending';
  }
  return runStatus === 'running' ? 'running' : 'pending';
}
