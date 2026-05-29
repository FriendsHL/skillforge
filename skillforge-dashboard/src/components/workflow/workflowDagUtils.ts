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
