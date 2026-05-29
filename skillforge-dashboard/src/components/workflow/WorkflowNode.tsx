import React from 'react';
import { Handle, Position, type NodeProps } from 'reactflow';
import type { WorkflowStep } from '../../api/workflow';

/**
 * AUTOEVOLVING V1 Sprint 3 — node status for the workflow run DAG.
 *
 * Derived (not 1:1 with BE step.status) — see WorkflowDag.deriveAgentStatus:
 *   - `paused`  : human_approve gate, step pending, run-level status=paused
 *                 (BE has no step-level "paused"; the run parks instead).
 *   - `running` : subagent_dispatch step pending while the run is running
 *                 (the invoker appends the row pending THEN runs engine.run
 *                 synchronously, so a pending dispatch row genuinely means
 *                 "in-flight").
 *   - `completed` / `error` : map directly from step.status.
 *   - `pending` : queued / not-yet-started (skeleton phases, stalled steps).
 */
export type WorkflowNodeStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'error'
  | 'paused';

/**
 * Q2-redesign: nodes are now the actual orchestration steps (`agent`) or a
 * dimmed skeleton placeholder for a not-yet-reached phase (`ghost`). The old
 * `phase` header-node kind was removed when the DAG became a single step chain.
 */
export type WorkflowNodeKind = 'agent' | 'ghost';

/**
 * Per-node payload threaded through React Flow `data`. Keep small + serializable;
 * React Flow re-renders a node only when this object identity changes (the parent
 * builds nodes via useMemo).
 */
export interface WorkflowNodeData {
  kind: WorkflowNodeKind;
  /** Agent slug / step label (agent node) or phase title (ghost placeholder). */
  label: string;
  status: WorkflowNodeStatus;
  /** Secondary line — step kind for agents. */
  sublabel?: string | null;
  /** Small phase caption shown above the title (the step's `phase`). */
  phaseLabel?: string | null;
  /** True for human_approve gate nodes (drives the "awaiting approval" badge). */
  isApprovalGate?: boolean;
  /** True when this node has no inbound edge (suppresses the target handle). */
  isRoot?: boolean;
  /** True for a dimmed skeleton placeholder of a not-yet-reached phase. */
  isGhost?: boolean;
  /**
   * The backing step for `agent`-kind nodes — threaded so a click can open the
   * step drawer without a separate index lookup. Absent on `phase` header nodes
   * (they aggregate multiple steps).
   */
  step?: WorkflowStep;
  /**
   * Click handler threaded into the node so the click is handled by the node's
   * own DOM `onClick` rather than React Flow's `onNodeClick` — the latter gets
   * swallowed by RF's pan/drag gesture detection when the pointer moves even a
   * pixel between down/up (known RF footgun → "intermittently won't open").
   * Absent / no-op on phase headers.
   */
  onStepClick?: (step: WorkflowStep) => void;
}

const STATUS_LABEL: Record<WorkflowNodeStatus, string> = {
  pending: 'pending',
  running: 'running',
  completed: 'done',
  error: 'error',
  paused: 'awaiting approval',
};

/**
 * Custom React Flow node for the workflow run DAG. Registered as
 * `nodeTypes={{ workflowStep: WorkflowNode }}` on the top-level <ReactFlow>.
 * Purely presentational — the parent WorkflowDag owns nodes + edges.
 */
const WorkflowNode: React.FC<NodeProps<WorkflowNodeData>> = ({ data }) => {
  const { kind, label, status, sublabel, phaseLabel, isApprovalGate, isRoot, isGhost, step, onStepClick } = data;

  const statusText = isGhost
    ? 'upcoming'
    : isApprovalGate && status === 'paused'
      ? STATUS_LABEL.paused
      : STATUS_LABEL[status];

  // The node body + status. Rendered inside a real <button> when the node is
  // clickable, or a plain inert <div> for ghost / step-less placeholders.
  const inner = (
    <>
      <div className="wf-node-body">
        {phaseLabel != null && phaseLabel !== '' && (
          <div className="wf-node-phase">{phaseLabel}</div>
        )}
        <div className="wf-node-title" title={label}>
          {label}
        </div>
        {sublabel != null && sublabel !== '' && (
          <div className="wf-node-sub">{sublabel}</div>
        )}
      </div>

      <div className="wf-node-status">
        <span className={`wf-dot wf-dot--${status}`} aria-hidden="true" />
        <span className="wf-status-text">{statusText}</span>
      </div>
    </>
  );

  // CRITICAL (matches the working FlywheelNode/StepCard pattern): the click
  // target MUST be a real <button>, not a <div onClick>. React Flow's pane
  // swallows a real mouse click on a plain <div> node (gesture/pan handling),
  // so a div onClick "intermittently won't open" — a native <button> is not
  // swallowed. `nodrag` additionally tells RF not to treat it as a pan target.
  const clickable = step != null && !isGhost;

  return (
    <div
      className={`wf-node wf-node--${kind} wf-node--${status}`}
      data-status={status}
      data-testid={`wf-node-${kind}-${label}`}
    >
      {!isRoot && (
        <Handle
          type="target"
          position={Position.Left}
          className="wf-handle"
          isConnectable={false}
        />
      )}

      {clickable ? (
        <button
          type="button"
          className="wf-node-click nodrag"
          aria-label={`${label} — 查看详情`}
          onClick={(e) => {
            // Stop the click bubbling to RF's pane onNodeClick (kept as a
            // no-op fallback) so onStepClick fires exactly once.
            e.stopPropagation();
            onStepClick?.(step);
          }}
        >
          {inner}
        </button>
      ) : (
        <div className="wf-node-click wf-node-click--inert">{inner}</div>
      )}

      <Handle
        type="source"
        position={Position.Right}
        className="wf-handle"
        isConnectable={false}
      />
    </div>
  );
};

export default React.memo(WorkflowNode);
