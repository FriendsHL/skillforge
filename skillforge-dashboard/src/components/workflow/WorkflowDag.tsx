import React, { useCallback, useMemo } from 'react';
import ReactFlow, {
  Background,
  Controls,
  type Edge,
  type Node,
  type NodeTypes,
} from 'reactflow';
import 'reactflow/dist/style.css';
import dagre from 'dagre';
import WorkflowNode, {
  type WorkflowNodeData,
  type WorkflowNodeStatus,
} from './WorkflowNode';
import type { WorkflowStep } from '../../api/workflow';
import {
  groupStepsByPhase,
  deriveAgentStatus,
  STEP_KIND_HUMAN_APPROVE,
  type PhaseGroup,
} from './workflowDagUtils';

const NODE_TYPES: NodeTypes = { workflowStep: WorkflowNode };

const PHASE_W = 200;
const PHASE_H = 48;
const AGENT_H = 64;
const COL_GAP = 90; // dagre ranksep between phase columns
const ROW_GAP = 18; // vertical gap between stacked agents
const HEADER_GAP = 22; // gap below a phase header before its agents

/** Aggregate a phase's child statuses into a single header status. */
function derivePhaseStatus(children: WorkflowNodeStatus[]): WorkflowNodeStatus {
  if (children.length === 0) return 'pending';
  if (children.some((c) => c === 'error')) return 'error';
  if (children.some((c) => c === 'paused')) return 'paused';
  if (children.some((c) => c === 'running')) return 'running';
  if (children.every((c) => c === 'completed')) return 'completed';
  return 'pending';
}

/**
 * Lay out phase-header X positions with dagre (LR backbone) — reuses the
 * FlywheelFlowchart dagre idiom. Agent nodes are then stacked vertically under
 * their header at the header's X so each phase reads as a clean column.
 */
function computePhaseColumns(groups: PhaseGroup[]): Map<string, number> {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'LR', nodesep: 24, ranksep: COL_GAP, marginx: 24, marginy: 24 });
  for (const grp of groups) {
    g.setNode(grp.title, { width: PHASE_W, height: PHASE_H });
  }
  for (let i = 0; i < groups.length - 1; i++) {
    g.setEdge(groups[i].title, groups[i + 1].title);
  }
  dagre.layout(g);
  const xByTitle = new Map<string, number>();
  groups.forEach((grp, i) => {
    const n = g.node(grp.title);
    xByTitle.set(grp.title, n ? n.x - PHASE_W / 2 : i * (PHASE_W + COL_GAP));
  });
  return xByTitle;
}

export interface WorkflowDagProps {
  steps: WorkflowStep[];
  /** Run-level status (running / paused / completed / error / pending). */
  runStatus: string;
  /** Ordered phase titles from the workflow definition (skeleton + ordering). */
  phaseOrder?: string[];
  /** Fired when an agent / gate node is clicked (phase headers don't fire). */
  onStepClick?: (step: WorkflowStep) => void;
}

/**
 * Renders a workflow run's steps as a phase-grouped DAG. Read-only React Flow
 * (no drag / connect / select). Phase nodes form the horizontal backbone; agent
 * nodes hang under their phase header.
 */
const WorkflowDag: React.FC<WorkflowDagProps> = ({
  steps,
  runStatus,
  phaseOrder,
  onStepClick,
}) => {
  const { nodes, edges, isEmpty } = useMemo(() => {
    const { groups, linear } = groupStepsByPhase(steps, phaseOrder);
    if (groups.length === 0) {
      return { nodes: [] as Node<WorkflowNodeData>[], edges: [] as Edge[], isEmpty: true };
    }

    const builtNodes: Node<WorkflowNodeData>[] = [];
    const builtEdges: Edge[] = [];

    if (linear) {
      // One step per column; chain agent → agent. No phase headers.
      const xByTitle = computePhaseColumns(groups);
      groups.forEach((grp, i) => {
        const step = grp.steps[0];
        const x = xByTitle.get(grp.title) ?? i * (PHASE_W + COL_GAP);
        const nodeId = `agent-${i}`;
        builtNodes.push({
          id: nodeId,
          type: 'workflowStep',
          position: { x, y: 0 },
          data: {
            kind: 'agent',
            label: step.agentSlug ?? grp.title,
            status: deriveAgentStatus(step, runStatus),
            sublabel: step.stepKind,
            isApprovalGate: step.stepKind === STEP_KIND_HUMAN_APPROVE,
            isRoot: i === 0,
            step,
          },
          draggable: false,
          connectable: false,
          selectable: false,
        });
        if (i > 0) {
          builtEdges.push({
            id: `e-${i - 1}-${i}`,
            source: `agent-${i - 1}`,
            target: nodeId,
            type: 'smoothstep',
            className: 'wf-edge',
          });
        }
      });
      return { nodes: builtNodes, edges: builtEdges, isEmpty: false };
    }

    // Phase-grouped layout.
    const xByTitle = computePhaseColumns(groups);
    groups.forEach((grp, gi) => {
      const phaseId = `phase-${gi}`;
      const x = xByTitle.get(grp.title) ?? gi * (PHASE_W + COL_GAP);
      const childStatuses = grp.steps.map((s) => deriveAgentStatus(s, runStatus));

      builtNodes.push({
        id: phaseId,
        type: 'workflowStep',
        position: { x, y: 0 },
        data: {
          kind: 'phase',
          label: grp.title,
          status: derivePhaseStatus(childStatuses),
          sublabel:
            grp.steps.length === 0
              ? 'pending'
              : `${grp.steps.length} ${grp.steps.length === 1 ? 'agent' : 'agents'}`,
          isRoot: gi === 0,
        },
        draggable: false,
        connectable: false,
        selectable: false,
      });

      // Backbone edge phase[i] → phase[i+1].
      if (gi > 0) {
        builtEdges.push({
          id: `e-phase-${gi - 1}-${gi}`,
          source: `phase-${gi - 1}`,
          target: phaseId,
          type: 'smoothstep',
          className: 'wf-edge wf-edge--backbone',
        });
      }

      // Agent nodes stacked under the header.
      grp.steps.forEach((step, si) => {
        const agentId = `${phaseId}-agent-${si}`;
        const y = PHASE_H + HEADER_GAP + si * (AGENT_H + ROW_GAP);
        builtNodes.push({
          id: agentId,
          type: 'workflowStep',
          position: { x, y },
          data: {
            kind: 'agent',
            label: step.agentSlug ?? `step ${step.stepIndex ?? si}`,
            status: childStatuses[si],
            sublabel: step.stepKind,
            isApprovalGate: step.stepKind === STEP_KIND_HUMAN_APPROVE,
            isRoot: true, // fed by its phase header via a vertical edge, no left handle needed
            step,
          },
          draggable: false,
          connectable: false,
          selectable: false,
        });
        builtEdges.push({
          id: `e-${phaseId}-${si}`,
          source: phaseId,
          target: agentId,
          type: 'smoothstep',
          className: 'wf-edge wf-edge--child',
        });
      });
    });

    return { nodes: builtNodes, edges: builtEdges, isEmpty: false };
  }, [steps, runStatus, phaseOrder]);

  // Open the drawer when an agent / gate node is clicked. Phase headers carry
  // no `step` in their data, so they're inert. onNodeClick fires even with
  // elementsSelectable=false (the read-only DAG stays non-selectable).
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
