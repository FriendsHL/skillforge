/**
 * AUTOEVOLVING V1 Sprint 3 — unit tests for the WorkflowDag pure transforms.
 *
 * Covers the non-trivial logic that drives the DAG:
 *  - groupStepsByPhase: definition-order priority, step-order append, null-phase
 *    bucketing, and the linear fallback when no phase info exists at all.
 *  - deriveAgentStatus: the paused / running derivations that don't map 1:1 to
 *    BE step.status.
 */
import { describe, expect, it } from 'vitest';
import {
  groupStepsByPhase,
  deriveAgentStatus,
  buildChainEdgePairs,
} from '../workflowDagUtils';
import type { WorkflowStep } from '../../../api/workflow';

function step(p: Partial<WorkflowStep>): WorkflowStep {
  return {
    stepIndex: 0,
    stepKind: 'subagent_dispatch',
    status: 'pending',
    agentSlug: 'a',
    phase: null,
    createdAt: null,
    updatedAt: null,
    ...p,
  };
}

describe('groupStepsByPhase', () => {
  it('orders phases by the definition, appending unseen step phases', () => {
    const steps = [
      step({ stepIndex: 0, phase: 'Annotate', agentSlug: 'ann' }),
      step({ stepIndex: 1, phase: 'Load', agentSlug: 'loader' }),
      step({ stepIndex: 2, phase: 'Extra', agentSlug: 'x' }),
    ];
    const { groups, linear } = groupStepsByPhase(steps, ['Load', 'Annotate', 'Aggregate']);
    expect(linear).toBe(false);
    // Definition order first (Load, Annotate, Aggregate), then unseen 'Extra'.
    expect(groups.map((g) => g.title)).toEqual(['Load', 'Annotate', 'Aggregate', 'Extra']);
    // Aggregate exists in the skeleton with zero steps.
    expect(groups.find((g) => g.title === 'Aggregate')?.steps).toHaveLength(0);
    expect(groups.find((g) => g.title === 'Load')?.steps).toHaveLength(1);
  });

  it('buckets null-phase steps under (unphased) appended last', () => {
    const steps = [
      step({ stepIndex: 0, phase: 'Load' }),
      step({ stepIndex: 1, phase: null }),
    ];
    const { groups } = groupStepsByPhase(steps, ['Load']);
    expect(groups.map((g) => g.title)).toEqual(['Load', '(unphased)']);
    expect(groups[1].steps).toHaveLength(1);
  });

  it('groups multiple agents under the same phase (parallel fanout)', () => {
    const steps = [
      step({ stepIndex: 0, phase: 'Annotate', agentSlug: 'b0' }),
      step({ stepIndex: 1, phase: 'Annotate', agentSlug: 'b1' }),
    ];
    const { groups } = groupStepsByPhase(steps);
    expect(groups).toHaveLength(1);
    expect(groups[0].steps.map((s) => s.agentSlug)).toEqual(['b0', 'b1']);
  });

  it('falls back to linear (one column per step) when no phase info exists', () => {
    const steps = [
      step({ stepIndex: 1, phase: null, agentSlug: 'second' }),
      step({ stepIndex: 0, phase: null, agentSlug: 'first' }),
    ];
    const { groups, linear } = groupStepsByPhase(steps);
    expect(linear).toBe(true);
    // Sorted by stepIndex regardless of input order.
    expect(groups.map((g) => g.title)).toEqual(['first', 'second']);
  });

  it('returns empty groups for no steps and no definition', () => {
    expect(groupStepsByPhase([]).groups).toHaveLength(0);
  });
});

describe('deriveAgentStatus', () => {
  it('maps completed/error directly regardless of run status', () => {
    expect(deriveAgentStatus(step({ status: 'completed' }), 'running')).toBe('completed');
    expect(deriveAgentStatus(step({ status: 'error' }), 'paused')).toBe('error');
  });

  it('marks a pending human_approve gate as paused only when the run is paused', () => {
    const gate = step({ stepKind: 'human_approve', status: 'pending' });
    expect(deriveAgentStatus(gate, 'paused')).toBe('paused');
    expect(deriveAgentStatus(gate, 'running')).toBe('pending');
  });

  it('marks a pending dispatch step running only when the run is running', () => {
    const dispatch = step({ stepKind: 'subagent_dispatch', status: 'pending' });
    expect(deriveAgentStatus(dispatch, 'running')).toBe('running');
    expect(deriveAgentStatus(dispatch, 'completed')).toBe('pending');
  });
});

describe('buildChainEdgePairs (single-flow chain fan-out / fan-in)', () => {
  it('connects 1 → 1 columns with a single edge', () => {
    expect(buildChainEdgePairs([['a'], ['b']])).toEqual([['a', 'b']]);
  });

  it('fans out 1 → N', () => {
    expect(buildChainEdgePairs([['a'], ['b1', 'b2']])).toEqual([
      ['a', 'b1'],
      ['a', 'b2'],
    ]);
  });

  it('fans in N → 1', () => {
    expect(buildChainEdgePairs([['a1', 'a2'], ['b']])).toEqual([
      ['a1', 'b'],
      ['a2', 'b'],
    ]);
  });

  it('chains a full single → parallel → single flow', () => {
    // [orchestrator] → [b1,b2] → [aggregator]
    const cols = [['o'], ['b1', 'b2'], ['agg']];
    expect(buildChainEdgePairs(cols)).toEqual([
      ['o', 'b1'],
      ['o', 'b2'],
      ['b1', 'agg'],
      ['b2', 'agg'],
    ]);
  });

  it('index-aligns N → M (clamped) as a documented fallback', () => {
    expect(buildChainEdgePairs([['a1', 'a2'], ['b1', 'b2', 'b3']])).toEqual([
      ['a1', 'b1'],
      ['a2', 'b2'],
      ['a2', 'b3'],
    ]);
  });

  it('skips empty columns and single-column inputs', () => {
    expect(buildChainEdgePairs([['a']])).toEqual([]);
    expect(buildChainEdgePairs([[], ['b']])).toEqual([]);
  });
});
