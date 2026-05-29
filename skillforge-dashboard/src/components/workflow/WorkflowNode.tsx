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

export type WorkflowNodeKind = 'phase' | 'agent';

/**
 * Per-node payload threaded through React Flow `data`. Keep small + serializable;
 * React Flow re-renders a node only when this object identity changes (the parent
 * builds nodes via useMemo).
 */
export interface WorkflowNodeData {
  kind: WorkflowNodeKind;
  /** Phase title (phase node) or agent slug / label (agent node). */
  label: string;
  status: WorkflowNodeStatus;
  /** Secondary line — step kind for agents, agent count for phases. */
  sublabel?: string | null;
  /** True for human_approve gate nodes (drives the "awaiting approval" badge). */
  isApprovalGate?: boolean;
  /** True when this node has no inbound edge (suppresses the target handle). */
  isRoot?: boolean;
  /**
   * The backing step for `agent`-kind nodes — threaded so the DAG's
   * `onNodeClick` can open the step drawer without a separate index lookup.
   * Absent on `phase` header nodes (they aggregate multiple steps).
   */
  step?: WorkflowStep;
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
  const { kind, label, status, sublabel, isApprovalGate, isRoot } = data;

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

      <div className="wf-node-body">
        <div className="wf-node-title" title={label}>
          {label}
        </div>
        {sublabel != null && sublabel !== '' && (
          <div className="wf-node-sub">{sublabel}</div>
        )}
      </div>

      <div className="wf-node-status">
        <span className={`wf-dot wf-dot--${status}`} aria-hidden="true" />
        <span className="wf-status-text">
          {isApprovalGate && status === 'paused'
            ? STATUS_LABEL.paused
            : STATUS_LABEL[status]}
        </span>
      </div>

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
