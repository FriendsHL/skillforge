import React, { useCallback, useMemo } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MarkerType,
  type Edge,
  type Node,
  type NodeTypes,
} from 'reactflow';
import 'reactflow/dist/style.css';
import WorkflowNode, { type WorkflowNodeData } from './WorkflowNode';
import type { WorkflowStep } from '../../api/workflow';
import {
  groupStepsByPhase,
  deriveAgentStatus,
  buildChainEdgePairs,
  STEP_KIND_HUMAN_APPROVE,
} from './workflowDagUtils';

const NODE_TYPES: NodeTypes = { workflowStep: WorkflowNode };

const NODE_W = 200; // node footprint width (matches .wf-node width)
const NODE_H = 64; // node footprint height (for vertical stacking pitch)
const COL_GAP = 96; // horizontal gap between phase columns
const ROW_GAP = 20; // vertical gap between stacked parallel nodes

const COL_PITCH = NODE_W + COL_GAP;
const ROW_PITCH = NODE_H + ROW_GAP;

export interface WorkflowDagProps {
  steps: WorkflowStep[];
  /** Run-level status (running / paused / completed / error / pending). */
  runStatus: string;
  /** Ordered phase titles from the workflow definition (skeleton + ordering). */
  phaseOrder?: string[];
  /** Fired when an agent / gate node is clicked (ghost placeholders don't fire). */
  onStepClick?: (step: WorkflowStep) => void;
}

/**
 * Renders a workflow run as a single horizontal step chain (Q2 redesign).
 *
 * Each phase is one column laid left → right; the column holds that phase's
 * actual step nodes — one node for a single step, a vertical stack for a
 * parallel batch, or one dimmed `ghost` placeholder for a skeleton phase that
 * hasn't produced any step yet. Arrowed edges connect consecutive columns
 * (fan-out on 1→N, fan-in on N→1). There is no separate phase-header node tier;
 * the phase name rides on each node as a small caption.
 *
 * Read-only React Flow (no drag / connect / select). Clicks are handled by each
 * node's own DOM onClick (see WorkflowNode) — RF's gesture-filtered onNodeClick
 * stays only as a no-op fallback.
 */
const WorkflowDag: React.FC<WorkflowDagProps> = ({
  steps,
  runStatus,
  phaseOrder,
  onStepClick,
}) => {
  const { nodes, edges, isEmpty } = useMemo(() => {
    // B-FE: drop ghost steps with a null stepIndex (defensive — the BE also
    // filters these). humanApprove gates carry a real stepIndex, so they survive.
    const realSteps = steps.filter((s) => s.stepIndex != null);
    const { groups } = groupStepsByPhase(realSteps, phaseOrder);
    if (groups.length === 0) {
      return { nodes: [] as Node<WorkflowNodeData>[], edges: [] as Edge[], isEmpty: true };
    }

    const builtNodes: Node<WorkflowNodeData>[] = [];
    const colNodeIds: string[][] = [];

    groups.forEach((grp, gi) => {
      const x = gi * COL_PITCH;
      const ids: string[] = [];

      if (grp.steps.length === 0) {
        // Skeleton phase not reached yet → single dimmed ghost placeholder.
        const id = `col${gi}-ghost`;
        builtNodes.push({
          id,
          type: 'workflowStep',
          position: { x, y: 0 },
          data: {
            kind: 'ghost',
            // Title already carries the phase name → no separate caption.
            label: grp.title,
            status: 'pending',
            isRoot: gi === 0,
            isGhost: true,
          },
          draggable: false,
          connectable: false,
          selectable: false,
        });
        ids.push(id);
      } else {
        // 1 step → single node; N steps → vertical parallel stack centred on y=0
        // so a neighbouring single node lines up with the group's midpoint.
        const n = grp.steps.length;
        const totalH = (n - 1) * ROW_PITCH;
        grp.steps.forEach((step, si) => {
          const id = `col${gi}-n${si}`;
          builtNodes.push({
            id,
            type: 'workflowStep',
            position: { x, y: si * ROW_PITCH - totalH / 2 },
            data: {
              kind: 'agent',
              label: step.agentSlug ?? `step ${step.stepIndex ?? si}`,
              status: deriveAgentStatus(step, runStatus),
              sublabel: step.stepKind,
              phaseLabel: grp.title,
              isApprovalGate: step.stepKind === STEP_KIND_HUMAN_APPROVE,
              isRoot: gi === 0,
              step,
              onStepClick,
            },
            draggable: false,
            connectable: false,
            selectable: false,
          });
          ids.push(id);
        });
      }
      colNodeIds.push(ids);
    });

    // Arrowed edges between consecutive columns (fan-out / fan-in / 1:1).
    const builtEdges: Edge[] = buildChainEdgePairs(colNodeIds).map(([a, b], idx) => ({
      id: `e-${idx}-${a}-${b}`,
      source: a,
      target: b,
      type: 'smoothstep',
      className: 'wf-edge',
      markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
    }));

    return { nodes: builtNodes, edges: builtEdges, isEmpty: false };
  }, [steps, runStatus, phaseOrder, onStepClick]);

  // RF onNodeClick is kept only as a no-op fallback — node DOM onClick is the
  // real path (RF's onNodeClick is swallowed by pan/drag detection).
  const handleNodeClick = useCallback(
    (_: React.MouseEvent, node: Node<WorkflowNodeData>) => {
      const s = node.data?.step;
      if (s) onStepClick?.(s);
    },
    [onStepClick],
  );

  if (isEmpty) {
    return (
      <div className="wf-dag-empty" data-testid="wf-dag-empty">
        No steps recorded yet for this run.
      </div>
    );
  }

  return (
    <div className="wf-dag" data-testid="wf-dag">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        onNodeClick={handleNodeClick}
        fitView
        fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
        minZoom={0.3}
        maxZoom={1.5}
        nodesDraggable={false}
        nodesConnectable={false}
        nodesFocusable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        panOnDrag
        panOnScroll={false}
        zoomOnScroll
        zoomOnPinch
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} position="bottom-right" />
      </ReactFlow>
    </div>
  );
};

export default WorkflowDag;
