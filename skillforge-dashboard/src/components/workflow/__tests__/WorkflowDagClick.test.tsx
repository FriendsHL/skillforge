/**
 * AUTOEVOLVING V1 Q2 (bug fix) — WorkflowDag node-click path.
 *
 * Proves the click is handled by the node's own DOM `onClick` (not React Flow's
 * `onNodeClick`, which RF's pan/drag detection intermittently swallows). Firing
 * a DOM click on the rendered agent-node element must invoke `onStepClick` with
 * the backing step.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// React Flow needs ResizeObserver / matchMedia / DOMMatrix in jsdom.
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}
if (!(globalThis as unknown as { DOMMatrixReadOnly?: unknown }).DOMMatrixReadOnly) {
  class DOMMatrixReadOnly {
    m22 = 1;
    constructor() {}
  }
  (globalThis as unknown as { DOMMatrixReadOnly: typeof DOMMatrixReadOnly }).DOMMatrixReadOnly =
    DOMMatrixReadOnly;
}

import WorkflowDag from '../WorkflowDag';
import type { WorkflowStep } from '../../../api/workflow';

function mkStep(p: Partial<WorkflowStep>): WorkflowStep {
  return {
    stepIndex: 0,
    stepKind: 'subagent_dispatch',
    status: 'completed',
    agentSlug: 'annotator',
    phase: null,
    payload: null,
    createdAt: null,
    updatedAt: null,
    ...p,
  };
}

describe('WorkflowDag node click (DOM onClick path)', () => {
  it('fires onStepClick with the backing step when an agent node is clicked', async () => {
    const onStepClick = vi.fn();
    render(
      <div style={{ width: 600, height: 400 }}>
        <WorkflowDag
          steps={[mkStep({ agentSlug: 'annotator', stepIndex: 0 })]}
          runStatus="completed"
          onStepClick={onStepClick}
        />
      </div>,
    );

    // Single-flow chain → agent node label = agentSlug → wf-node-agent-annotator.
    // The click target is the inner <button> (a real button — RF swallows clicks
    // on a plain <div> node; a native button is not swallowed).
    const node = await waitFor(() => screen.getByTestId('wf-node-agent-annotator'));
    // querySelector (not getByRole): React Flow's node wrapper marks descendants
    // hidden in the a11y tree under jsdom, so getByRole('button') can't see it —
    // but the real <button> is present + clickable.
    const btn = node.querySelector('button');
    expect(btn).not.toBeNull();
    fireEvent.click(btn as HTMLButtonElement);

    expect(onStepClick).toHaveBeenCalledTimes(1);
    expect(onStepClick).toHaveBeenCalledWith(
      expect.objectContaining({ agentSlug: 'annotator', stepIndex: 0 }),
    );
  });
});

describe('WorkflowDag single-flow structure (Q2 redesign)', () => {
  it('renders agent step nodes and NO phase-header nodes', async () => {
    render(
      <div style={{ width: 800, height: 400 }}>
        <WorkflowDag
          steps={[
            mkStep({ agentSlug: 'orchestrator', phase: 'Load', stepIndex: 0 }),
            mkStep({ agentSlug: 'aggregator', phase: 'Aggregate', stepIndex: 1 }),
          ]}
          runStatus="running"
          phaseOrder={['Load', 'Aggregate']}
        />
      </div>,
    );
    expect(await waitFor(() => screen.getByTestId('wf-node-agent-orchestrator'))).toBeInTheDocument();
    expect(screen.getByTestId('wf-node-agent-aggregator')).toBeInTheDocument();
    // Old phase-header node kind is gone.
    expect(screen.queryByTestId('wf-node-phase-Load')).not.toBeInTheDocument();
  });

  it('renders a dimmed ghost node for a skeleton phase with no steps', async () => {
    render(
      <div style={{ width: 800, height: 400 }}>
        <WorkflowDag
          steps={[mkStep({ agentSlug: 'orchestrator', phase: 'Load', stepIndex: 0 })]}
          runStatus="running"
          // Approve phase is in the skeleton but has produced no step yet → ghost.
          phaseOrder={['Load', 'Approve']}
        />
      </div>,
    );
    expect(await waitFor(() => screen.getByTestId('wf-node-agent-orchestrator'))).toBeInTheDocument();
    expect(screen.getByTestId('wf-node-ghost-Approve')).toBeInTheDocument();
  });

  it('ghost nodes are inert (no onStepClick) when clicked', async () => {
    const onStepClick = vi.fn();
    render(
      <div style={{ width: 800, height: 400 }}>
        <WorkflowDag
          steps={[mkStep({ agentSlug: 'orchestrator', phase: 'Load', stepIndex: 0 })]}
          runStatus="running"
          phaseOrder={['Load', 'Approve']}
          onStepClick={onStepClick}
        />
      </div>,
    );
    const ghost = await waitFor(() => screen.getByTestId('wf-node-ghost-Approve'));
    fireEvent.click(ghost);
    expect(onStepClick).not.toHaveBeenCalled();
  });
});
