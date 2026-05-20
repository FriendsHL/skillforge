import React, { useCallback, useEffect, useMemo, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  type Edge,
  type Node,
  type NodeTypes,
} from 'reactflow';
import 'reactflow/dist/style.css';
import dagre from 'dagre';
import FlywheelNode, { type FlywheelNodeData } from './FlywheelNode';
import FlywheelStepDrawer from './FlywheelStepDrawer';
import FlywheelRunsSidebar from './FlywheelRunsSidebar';
import ActivityFeed from './ActivityFeed';
import { useFlywheelObservability } from '../../hooks/useFlywheelObservability';
import { useFlywheelRuns } from '../../hooks/useFlywheelRuns';
import { useLocalStorageString } from '../../hooks/useLocalStorageString';
import { useAuth } from '../../contexts/AuthContext';
import type {
  AgentTypeTab,
  FlywheelMode,
  FlywheelRunDto,
  FlywheelSurface,
  StepDescriptor,
  StepMetrics,
} from './types';
import {
  ERROR_STAGES,
  PRE_OPTEVENT_CONTEXT_STEPS,
  RUN_STAGE_ORDER,
  STAGE_TO_STEP,
  STEP_CATALOGUE,
} from './types';
import './flywheel.css';

const AGENT_TYPE_KEYS = ['user', 'system'] as const;
const SURFACE_KEYS = ['skill', 'prompt', 'behavior_rule'] as const;
const MODE_KEYS = ['aggregate', 'perRun'] as const;

/**
 * FLYWHEEL-FLOWCHART — full topology of the flywheel pipeline, listed as
 * `[source.id, target.id]` pairs.
 *
 * Edges are filtered at render time to only those whose **both** endpoints
 * are visible for the active (agentType × surface) pair (e.g. for
 * `behavior_rule` the chain truncates at G1 because step4…step9 surfaces
 * exclude behavior_rule per STEP_CATALOGUE — see types.ts).
 *
 * Topology source: prd "flywheel observability" v2 / FLYWHEEL-FLOWCHART
 * Implementation Notes.
 */
const EDGE_PAIRS: ReadonlyArray<readonly [string, string]> = [
  // Entry → first pipeline stage
  ['E1-user-chat', 'step1-annotate'],
  ['E3-extract-skill', 'step1-annotate'],
  ['E2-upload-skill', 'step4-candidate'],
  ['E4-write-prompt', 'step4-candidate'],
  // Pipeline mainline
  ['step1-annotate', 'step2-cluster'],
  ['step2-cluster', 'step3-attribute'],
  ['step3-attribute', 'G1-approve-event'],
  ['G1-approve-event', 'step4-candidate'],
  ['step4-candidate', 'G2-review-draft'],
  ['G2-review-draft', 'step5-abtest'],
  ['step5-abtest', 'step6-gate'],
  ['step6-gate', 'G3-promote-decision'],
  // Rollout (dormant)
  ['G3-promote-decision', 'step7-canary'],
  ['step7-canary', 'step8-metrics'],
  ['step8-metrics', 'step9-decide'],
];

// dagre node dimensions — match the CSS .fw-node max-width / target height so
// the layout solver places nodes without overlap. Adjusting these here also
// requires updating flywheel.css `.fw-node` width/min-height.
const NODE_WIDTH = 240;
const NODE_HEIGHT = 130;

const NODE_TYPES: NodeTypes = { flywheelStep: FlywheelNode };

interface DagreLayout {
  positions: Map<string, { x: number; y: number }>;
}

function computeLayout(
  visibleSteps: ReadonlyArray<StepDescriptor>,
  visibleEdges: ReadonlyArray<readonly [string, string]>,
): DagreLayout {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: 'LR',
    nodesep: 32,
    ranksep: 90,
    marginx: 24,
    marginy: 24,
  });
  for (const step of visibleSteps) {
    g.setNode(step.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }
  for (const [src, dst] of visibleEdges) {
    g.setEdge(src, dst);
  }
  dagre.layout(g);
  const positions = new Map<string, { x: number; y: number }>();
  for (const step of visibleSteps) {
    const n = g.node(step.id);
    if (!n) continue;
    // dagre returns center coordinates; React Flow expects top-left.
    positions.set(step.id, {
      x: n.x - NODE_WIDTH / 2,
      y: n.y - NODE_HEIGHT / 2,
    });
  }
  return { positions };
}

function emptyMetrics(): StepMetrics {
  return {
    inFlight: 0,
    todayCount: 0,
    lastActivityAt: null,
    recentErrorCount: 0,
    loaded: false,
  };
}

/**
 * FLYWHEEL-FLOWCHART — top-level observability panel rendered as the 5th
 * Insights tab. Workflow-style DAG view (n8n / Dagster / Airflow visual idiom)
 * with two modes:
 *  - **Aggregate** (default): node metric = in-flight/lag aggregates;
 *    AUTO+HYBRID nodes pulse green when running, USER gates static PEND chip.
 *  - **Per-Run** (FLYWHEEL-PER-RUN): left sidebar lists recent OptimizationEvent
 *    runs; selecting one overlays that run's current journey position on
 *    the DAG (pre-OptEvent steps gray as context; current step highlighted;
 *    completed steps marked with ✓; surface tab disabled, run dictates).
 *
 * Architecture:
 *   useFlywheelObservability  →  metricsByStep + runningByStep + events
 *   useFlywheelRuns (perRun)  →  runs[] for sidebar
 *                                     │
 *                                     ▼
 *                       useMemo(computeLayout via dagre)
 *                                     │
 *                                     ▼
 *                              <ReactFlow nodes/edges/>
 *                                     │
 *                                     ▼
 *                       FlywheelNode  (wraps StepCard + Handles)
 *
 * Mode + activeRunId stored locally:
 *   - mode in localStorage (`flywheel.mode`) so reload preserves user choice
 *   - activeRunId in useState ONLY (no persistence — runs are ephemeral and
 *     a persisted id can refer to a run that no longer exists)
 */
const FlywheelFlowchart: React.FC = () => {
  const { userId } = useAuth();
  const [agentType, setAgentType] = useLocalStorageString<AgentTypeTab>(
    'flywheel.agent_type_tab',
    'user',
    AGENT_TYPE_KEYS,
  );
  const surfaceLsKey = `flywheel.surface_tab.${agentType}`;
  const [surface, setSurface] = useLocalStorageString<FlywheelSurface>(
    surfaceLsKey,
    'skill',
    SURFACE_KEYS,
  );
  // FLYWHEEL-PER-RUN — mode toggle (aggregate default).
  const [mode, setMode] = useLocalStorageString<FlywheelMode>(
    'flywheel.mode',
    'aggregate',
    MODE_KEYS,
  );

  // FLYWHEEL-PER-RUN — local state (no localStorage persistence; a persisted
  // id can refer to a run no longer in BE response and re-selecting it from
  // stored state would silently render an empty "missing run" overlay).
  const [activeRunId, setActiveRunId] = useState<number | null>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(false);
  const [hideTerminal, setHideTerminal] = useState<boolean>(true);

  const { runs, isLoading: runsLoading } = useFlywheelRuns({
    agentType,
    surface: undefined, // per-run mode: surface is dictated by selected run, not pre-filtered
    hideTerminal,
    limit: 20,
    enabled: mode === 'perRun',
  });

  // Resolve activeRun from the runs array. If the previously-selected id is
  // no longer in the response (run advanced beyond hideTerminal filter,
  // BE-side filter removed it, etc), activeRun becomes null and the DAG
  // re-renders to the "no run selected" hint state.
  const activeRun = useMemo<FlywheelRunDto | null>(() => {
    if (mode !== 'perRun' || activeRunId == null) return null;
    return runs.find((r) => r.optEventId === activeRunId) ?? null;
  }, [mode, activeRunId, runs]);

  // FLYWHEEL-PER-RUN — when a run is selected, the surface is dictated by
  // the run (we DON'T write to setSurface so the user's aggregate-mode
  // surface choice stays preserved). displaySurface is what we actually
  // render with.
  const displaySurface: FlywheelSurface = useMemo(() => {
    if (mode === 'perRun' && activeRun) {
      const s = activeRun.surface;
      if (s === 'skill' || s === 'prompt' || s === 'behavior_rule') return s;
    }
    return surface;
  }, [mode, activeRun, surface]);

  // FLYWHEEL-PER-RUN — auto-deselect run when mode flips back to aggregate.
  // Don't clear activeRunId entirely so switching back to per-run preserves
  // the last selection if the run still exists.
  useEffect(() => {
    // (intentionally no-op for now — keep last activeRunId across mode flips
    // so switching aggregate→perRun→aggregate→perRun retains selection)
  }, [mode]);

  const {
    metricsByStep,
    runningByStep,
    events,
    isLoading,
    isError,
    errorMsg,
  } = useFlywheelObservability({ agentType, surface: displaySurface, userId });

  // Detail Drawer — null = closed, descriptor object = open + content target.
  // Stable callback to avoid invalidating node data identity unnecessarily
  // (otherwise every render would force React Flow to re-mount nodes).
  const [activeStep, setActiveStep] = useState<StepDescriptor | null>(null);
  const handleSelectStep = useCallback((step: StepDescriptor) => {
    if (step.nodeType === 'dormant') return;
    setActiveStep(step);
  }, []);
  const handleCloseDrawer = useCallback(() => {
    setActiveStep(null);
  }, []);

  /**
   * React Flow node-click handler — wired in addition to the inner
   * `<button onClick>` on StepCard because React Flow's NodeWrapper can
   * swallow real-browser pointer events (jsdom path in tests doesn't hit
   * the same code path, which is why the inner-button click test passes
   * even though the live UI was unresponsive). Wiring both paths to the
   * same callback makes click work reliably for mouse + keyboard:
   *   - mouse: RF NodeWrapper.onNodeClick fires → handleSelectStep(step)
   *   - keyboard Tab + Enter/Space: inner button onClick → handleSelectStep(step)
   * setActiveStep on the same step is idempotent so double-fire is safe.
   */
  const handleNodeClickRF = useCallback(
    (_: React.MouseEvent, node: Node<FlywheelNodeData>) => {
      handleSelectStep(node.data.step);
    },
    [handleSelectStep],
  );

  // FLYWHEEL-PER-RUN — derive per-step run-position flags. currentStepId is
  // the DAG step the active run is "at" per STAGE_TO_STEP; currentIdx is its
  // index in RUN_STAGE_ORDER so we can determine which steps are already
  // past (completedForRun).
  const currentStepId = activeRun
    ? STAGE_TO_STEP[activeRun.currentStage] ?? null
    : null;
  const currentStageIdx = currentStepId
    ? RUN_STAGE_ORDER.indexOf(currentStepId)
    : -1;
  const isRunErrorStage = activeRun
    ? ERROR_STAGES.has(activeRun.currentStage)
    : false;

  const { nodes, edges } = useMemo(() => {
    const visible = STEP_CATALOGUE.filter((s) => {
      if (!s.surfaces.includes(displaySurface)) return false;
      if (s.agentTypes && !s.agentTypes.includes(agentType)) return false;
      return true;
    });
    const visibleIds = new Set(visible.map((s) => s.id));
    const visibleEdges = EDGE_PAIRS.filter(
      ([src, dst]) => visibleIds.has(src) && visibleIds.has(dst),
    );

    const { positions } = computeLayout(visible, visibleEdges);

    const builtNodes: Node<FlywheelNodeData>[] = visible.map((step) => {
      const m = metricsByStep[step.id] ?? emptyMetrics();
      const isContextForRun =
        mode === 'perRun' && PRE_OPTEVENT_CONTEXT_STEPS.has(step.id);
      const isCurrentForRun =
        mode === 'perRun' && currentStepId === step.id && !isContextForRun;
      const stepIdxInRun = RUN_STAGE_ORDER.indexOf(step.id);
      const isCompletedForRun =
        mode === 'perRun' &&
        activeRun != null &&
        !isContextForRun &&
        currentStageIdx >= 0 &&
        stepIdxInRun >= 0 &&
        stepIdxInRun < currentStageIdx;
      const isErrorForRun =
        mode === 'perRun' && isCurrentForRun && isRunErrorStage;

      return {
        id: step.id,
        type: 'flywheelStep',
        position: positions.get(step.id) ?? { x: 0, y: 0 },
        data: {
          step,
          metrics: m,
          isRunning: runningByStep[step.id] ?? false,
          mode,
          isCurrentForRun,
          isContextForRun,
          isCompletedForRun,
          isErrorForRun,
          activeRun,
          // Stable per-render via useCallback above — node data identity only
          // flips when step/metrics/isRunning genuinely change.
          onSelect: handleSelectStep,
        },
        // Read-only DAG — disable interactions React Flow defaults on.
        draggable: false,
        connectable: false,
        selectable: false,
      };
    });

    // Edge gets animated dashed flow when **both** endpoints have in-flight
    // > 0 (PRD: data is actively flowing between these stages). In per-run
    // mode we suppress edge animation entirely — the run isn't an aggregate
    // flow, it's a point, and pulsing edges would be misleading.
    const inFlightIds = new Set(
      visible
        .filter((s) => (metricsByStep[s.id]?.inFlight ?? 0) > 0)
        .map((s) => s.id),
    );

    const builtEdges: Edge[] = visibleEdges.map(([src, dst]) => {
      const isLive =
        mode === 'aggregate' && inFlightIds.has(src) && inFlightIds.has(dst);
      return {
        id: `${src}->${dst}`,
        source: src,
        target: dst,
        type: 'smoothstep',
        animated: isLive,
        className: isLive ? 'fw-edge-animated' : 'fw-edge-static',
        style: { strokeWidth: isLive ? 2 : 1.5 },
      };
    });

    return { nodes: builtNodes, edges: builtEdges };
  }, [
    agentType,
    displaySurface,
    metricsByStep,
    runningByStep,
    handleSelectStep,
    mode,
    activeRun,
    currentStepId,
    currentStageIdx,
    isRunErrorStage,
  ]);

  // Drawer feeds — derive only when a step is selected to avoid running the
  // filter on every render.
  const drawerMetrics = activeStep
    ? metricsByStep[activeStep.id]
    : undefined;
  const drawerEvents = useMemo(() => {
    if (!activeStep) return undefined;
    return events.filter((e) => e.stepId === activeStep.id);
  }, [activeStep, events]);

  // Per-run drawer flags derived from the step the drawer is targeting.
  const drawerStepIsContextForRun = !!(
    activeStep &&
    mode === 'perRun' &&
    PRE_OPTEVENT_CONTEXT_STEPS.has(activeStep.id)
  );
  const drawerStepIsCurrentForRun = !!(
    activeStep &&
    mode === 'perRun' &&
    currentStepId === activeStep.id &&
    !drawerStepIsContextForRun
  );
  const drawerStepIdxInRun = activeStep
    ? RUN_STAGE_ORDER.indexOf(activeStep.id)
    : -1;
  const drawerStepIsCompletedForRun = !!(
    activeStep &&
    mode === 'perRun' &&
    activeRun &&
    !drawerStepIsContextForRun &&
    currentStageIdx >= 0 &&
    drawerStepIdxInRun >= 0 &&
    drawerStepIdxInRun < currentStageIdx
  );

  // PRD N3 — "this surface never lit up" hint on the tab label.
  const surfaceIsEmpty = useMemo<Record<FlywheelSurface, boolean>>(() => {
    const empty: Record<FlywheelSurface, boolean> = {
      skill: false,
      prompt: false,
      behavior_rule: false,
    };
    if (isLoading) return empty;
    const any = Object.values(metricsByStep).some(
      (m) => m.inFlight > 0 || m.todayCount > 0 || m.lastActivityAt != null,
    );
    empty[displaySurface] = !any;
    return empty;
  }, [metricsByStep, isLoading, displaySurface]);

  const surfaceTabsDisabled = mode === 'perRun' && activeRun != null;

  return (
    <div className="fw-page" data-testid="flywheel-flowchart-panel">
      <header className="fw-head">
        <div className="fw-head-row">
          <div className="fw-head-text">
            <h1 className="fw-head-title">Insight Loop</h1>
            <p className="fw-head-sub">
              Workflow DAG of the full prod-signal → annotate → cluster → attribute →
              candidate → A/B → gate → promote cycle.
              {mode === 'aggregate'
                ? ' Aggregate mode: nodes pulse green when actively running, edges animate when data flows.'
                : ' Per-Run mode: select a run from the sidebar to overlay its journey on the DAG.'}
            </p>
          </div>
          <ModeToggle mode={mode} setMode={setMode} />
        </div>
      </header>

      {/* Tier 1 — agent type (user / system). */}
      <div
        className="underline-tabs"
        data-testid="agent-type-tabs"
        role="tablist"
        aria-label="Agent type"
      >
        {AGENT_TYPE_KEYS.map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            id={`fw-tab-agent-${k}`}
            aria-selected={agentType === k}
            aria-controls="fw-flowchart-panel"
            tabIndex={agentType === k ? 0 : -1}
            className={agentType === k ? 'on' : ''}
            onClick={() => setAgentType(k)}
          >
            {k === 'user' ? 'User agents' : 'System agents'}
          </button>
        ))}
      </div>

      {/* Tier 2 — surface (skill / prompt / behavior_rule). In per-run mode
          with a selected run, the surface is dictated by the run and the
          tabs become read-only display (each shows whether it matches the
          run's surface). */}
      <div
        className="fw-surface-tabs"
        data-testid="surface-tabs"
        role="tablist"
        aria-label="Surface"
        data-surface-locked={surfaceTabsDisabled ? 'true' : undefined}
      >
        {SURFACE_KEYS.map((k) => {
          const isSelected = displaySurface === k;
          return (
            <button
              key={k}
              type="button"
              role="tab"
              id={`fw-tab-surface-${k}`}
              aria-selected={isSelected}
              aria-controls="fw-flowchart-panel"
              tabIndex={isSelected ? 0 : -1}
              disabled={surfaceTabsDisabled}
              className={`fw-surface-tab${isSelected ? ' on' : ''}${
                surfaceTabsDisabled ? ' fw-surface-tab--locked' : ''
              }`}
              data-empty={
                isSelected && !surfaceTabsDisabled
                  ? surfaceIsEmpty[k]
                  : undefined
              }
              onClick={() => {
                if (!surfaceTabsDisabled) setSurface(k);
              }}
              title={
                surfaceTabsDisabled
                  ? 'Surface is dictated by the selected run'
                  : undefined
              }
            >
              {surfaceLabel(k)}
              {surfaceTabsDisabled && isSelected && (
                <span className="fw-surface-tab-locklabel"> · by run</span>
              )}
            </button>
          );
        })}
      </div>

      {isError && errorMsg && (
        <div className="fw-error" role="alert">
          Failed to load some flywheel metrics: {errorMsg}
        </div>
      )}

      <div
        className={`fw-flowchart-row${mode === 'perRun' ? ' fw-flowchart-row--perrun' : ''}`}
        data-testid="flywheel-flowchart-row"
      >
        {mode === 'perRun' && (
          <FlywheelRunsSidebar
            runs={runs}
            isLoading={runsLoading}
            activeRunId={activeRunId}
            onSelectRun={setActiveRunId}
            isCollapsed={sidebarCollapsed}
            onToggleCollapse={() => setSidebarCollapsed((v) => !v)}
            hideTerminal={hideTerminal}
            onToggleHideTerminal={() => setHideTerminal((v) => !v)}
            mode={mode}
          />
        )}

        <div
          id="fw-flowchart-panel"
          role="tabpanel"
          aria-labelledby={`fw-tab-surface-${displaySurface}`}
          className={`fw-flowchart-shell${
            mode === 'perRun' && !activeRun ? ' fw-flowchart-shell--idle' : ''
          }`}
          data-testid="flywheel-flowchart"
          data-mode={mode}
        >
          {mode === 'perRun' && !activeRun && (
            <div className="fw-perrun-hint" data-testid="fw-perrun-hint">
              <span className="fw-perrun-hint-icon" aria-hidden="true">⟶</span>
              <span className="fw-perrun-hint-text">
                Select a run from the sidebar to inspect its journey on the DAG.
              </span>
            </div>
          )}
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onNodeClick={handleNodeClickRF}
            fitView
            fitViewOptions={{ padding: 0.15, maxZoom: 1 }}
            minZoom={0.4}
            maxZoom={1.5}
            nodesDraggable={false}
            nodesConnectable={false}
            // F4 (code A11Y-1) — must be false: when true, React Flow
            // intercepts Enter/Space on its node wrapper for its own
            // selection logic, which prevents the inner StepCard <a>
            // (react-router-dom <Link>) from receiving the keypress. With
            // false, keyboard users Tab straight to the <a> element and
            // Enter/Space follows the drill-down link as expected.
            nodesFocusable={false}
            edgesFocusable={false}
            elementsSelectable={false}
            panOnDrag
            panOnScroll={false}
            zoomOnScroll
            zoomOnPinch
            proOptions={{ hideAttribution: false }}
          >
            <Background gap={20} size={1} />
            <Controls
              showInteractive={false}
              position="bottom-right"
            />
          </ReactFlow>
        </div>
      </div>

      <ActivityFeed events={events} loading={isLoading} />

      {/* Detail Drawer — rendered as last child so it stacks above the
          flowchart shell + activity feed when a step is selected. */}
      <FlywheelStepDrawer
        step={activeStep}
        metrics={drawerMetrics}
        recentEvents={drawerEvents}
        onClose={handleCloseDrawer}
        mode={mode}
        activeRun={activeRun}
        isCurrentForRun={drawerStepIsCurrentForRun}
        isContextForRun={drawerStepIsContextForRun}
        isCompletedForRun={drawerStepIsCompletedForRun}
      />
    </div>
  );
};

interface ModeToggleProps {
  mode: FlywheelMode;
  setMode: (m: FlywheelMode) => void;
}

const ModeToggle: React.FC<ModeToggleProps> = ({ mode, setMode }) => (
  <div
    className="fw-mode-toggle"
    role="tablist"
    aria-label="View mode"
    data-testid="flywheel-mode-toggle"
  >
    {MODE_KEYS.map((k) => (
      <button
        key={k}
        type="button"
        role="tab"
        aria-selected={mode === k}
        className={`fw-mode-toggle-btn${mode === k ? ' on' : ''}`}
        data-testid={`fw-mode-${k}`}
        onClick={() => setMode(k)}
      >
        {k === 'aggregate' ? 'Aggregate' : 'Per-Run'}
      </button>
    ))}
  </div>
);

function surfaceLabel(s: FlywheelSurface): string {
  switch (s) {
    case 'skill':         return 'skill';
    case 'prompt':        return 'prompt';
    case 'behavior_rule': return 'behavior_rule';
  }
}

export default FlywheelFlowchart;
