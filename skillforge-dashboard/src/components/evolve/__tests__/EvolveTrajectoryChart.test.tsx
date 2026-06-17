/**
 * EvolveTrajectoryChart — rendering tests.
 *
 * echarts-for-react renders a canvas/div; we mock it to avoid
 * canvas-in-jsdom issues while still testing option construction logic.
 */
import React from 'react';
import { describe, it, expect, vi, beforeAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { EvolveRunDetail, EvolveIteration } from '../../../api/evolve';

// ── Mock echarts-for-react so tests don't need a real canvas ─────────────────
vi.mock('echarts-for-react', () => {
  const ReactECharts = (props: {
    option: object;
    style?: React.CSSProperties;
    'data-testid'?: string;
  }) => (
    <div
      data-testid="echarts-mock"
      data-option={JSON.stringify(props.option)}
      style={props.style}
    />
  );
  ReactECharts.displayName = 'ReactECharts';
  return { default: ReactECharts };
});

import EvolveTrajectoryChart from '../EvolveTrajectoryChart';

// ── Fixtures ─────────────────────────────────────────────────────────────────

function makeIteration(overrides: Partial<EvolveIteration> = {}): EvolveIteration {
  return {
    iteration: 1,
    surface: 'prompt',
    changeDesc: 'Improved instruction clarity',
    candidateId: 'cand-001',
    baselineScore: 0.70,
    candidateScore: 0.76,
    delta: 0.06,
    kept: true,
    abRunId: 'ab-001',
    createdAt: '2026-05-31T10:00:00Z',
    ...overrides,
  };
}

function makeDetail(overrides: Partial<EvolveRunDetail> = {}): EvolveRunDetail {
  return {
    evolveRunId: 'run-001',
    agentId: 42,
    agentName: 'main-assistant',
    status: 'completed',
    createdAt: '2026-05-31T09:00:00Z',
    updatedAt: '2026-05-31T09:45:00Z',
    iterations: [
      makeIteration({ iteration: 1, candidateScore: 0.72, kept: true }),
      makeIteration({ iteration: 2, candidateScore: 0.78, kept: false }),
      makeIteration({ iteration: 3, candidateScore: 0.83, kept: true }),
    ],
    ...overrides,
  };
}

// ── Tests ─────────────────────────────────────────────────────────────────────

beforeAll(() => {
  // CSS variables are not resolved in jsdom — suppress the expected warnings
});

describe('EvolveTrajectoryChart', () => {
  it('renders empty state when runs array is empty', () => {
    render(<EvolveTrajectoryChart runs={[]} />);
    expect(screen.getByTestId('evolve-trajectory-chart-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('echarts-mock')).not.toBeInTheDocument();
  });

  it('renders echarts when runs are provided', () => {
    render(<EvolveTrajectoryChart runs={[makeDetail()]} />);
    const chart = screen.getByTestId('echarts-mock');
    expect(chart).toBeInTheDocument();
  });

  it('produces exactly one series per run', () => {
    const runs = [
      makeDetail({ evolveRunId: 'run-001' }),
      makeDetail({ evolveRunId: 'run-002', agentName: 'other-agent' }),
    ];
    render(<EvolveTrajectoryChart runs={runs} />);
    const chart = screen.getByTestId('echarts-mock');
    const option = JSON.parse(chart.getAttribute('data-option') ?? '{}') as {
      series: unknown[];
    };
    expect(option.series).toHaveLength(2);
  });

  it('series data points map iteration → candidateScore', () => {
    const detail = makeDetail({
      iterations: [
        makeIteration({ iteration: 1, candidateScore: 0.72 }),
        makeIteration({ iteration: 2, candidateScore: 0.80 }),
      ],
    });
    render(<EvolveTrajectoryChart runs={[detail]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as { series: Array<{ data: Array<{ value: [number, number] }> }> };
    const pts = option.series[0].data;
    expect(pts[0].value).toEqual([1, 0.72]);
    expect(pts[1].value).toEqual([2, 0.80]);
  });

  it('stores iterMeta on each data point (for tooltip)', () => {
    const iter = makeIteration({
      iteration: 1,
      surface: 'skill',
      changeDesc: 'Rewrote tool description',
      kept: false,
    });
    render(<EvolveTrajectoryChart runs={[makeDetail({ iterations: [iter] })]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as {
      series: Array<{
        data: Array<{ iterMeta: EvolveIteration }>;
      }>;
    };
    const meta = option.series[0].data[0].iterMeta;
    expect(meta.surface).toBe('skill');
    expect(meta.changeDesc).toBe('Rewrote tool description');
    expect(meta.kept).toBe(false);
  });

  it('draws a horizontal baseline markLine at the run baselineScore', () => {
    const detail = makeDetail({
      iterations: [
        makeIteration({ iteration: 1, baselineScore: 25, candidateScore: 16.67, delta: -8.33 }),
        makeIteration({ iteration: 2, baselineScore: 25, candidateScore: 33.33, delta: 8.33 }),
      ],
    });
    render(<EvolveTrajectoryChart runs={[detail]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as {
      series: Array<{ markLine?: { data: Array<{ yAxis: number }> } }>;
    };
    const markLine = option.series[0].markLine;
    expect(markLine).toBeDefined();
    // baseline is measured once → constant; line sits at that y value.
    expect(markLine?.data[0].yAxis).toBe(25);
  });

  it('omits the baseline markLine when no iteration has a baselineScore', () => {
    const detail = makeDetail({
      iterations: [makeIteration({ iteration: 1, baselineScore: null })],
    });
    render(<EvolveTrajectoryChart runs={[detail]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as { series: Array<{ markLine?: unknown }> };
    expect(option.series[0].markLine).toBeUndefined();
  });

  it('colors each marker by its position vs the baseline line (rise green / fall red)', () => {
    const detail = makeDetail({
      iterations: [
        // below the 25 baseline line, not kept → hollow red
        makeIteration({ iteration: 1, baselineScore: 25, candidateScore: 16.67, delta: -8.33, kept: false }),
        // above the 25 baseline line, kept → solid green
        makeIteration({ iteration: 2, baselineScore: 25, candidateScore: 33.33, delta: 8.33, kept: true }),
      ],
    });
    render(<EvolveTrajectoryChart runs={[detail]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as {
      series: Array<{
        data: Array<{ itemStyle: { color: string; borderColor: string } }>;
      }>;
    };
    const [down, up] = option.series[0].data;
    expect(down.itemStyle.borderColor).toContain('--color-error');
    expect(down.itemStyle.color).toBe('transparent'); // hollow (not kept)
    expect(up.itemStyle.borderColor).toContain('--color-success');
    expect(up.itemStyle.color).toContain('--color-success'); // solid (kept)
  });

  it('keeps the baseline line fixed at the original after a win, and colors vs that line — not the moved best', () => {
    // Winner carry-forward: iter1 wins (best 41.67→58.33). iter2's per-iteration
    // baselineScore is the MOVED best (58.33), but the chart line must stay at the
    // ORIGINAL baseline (41.67), and iter2 (candidate 41.67) sits ON that line —
    // so it must be neutral, NOT red (red is what iter.delta=-16.66 vs best implies).
    const detail = makeDetail({
      iterations: [
        makeIteration({ iteration: 1, baselineScore: 41.67, candidateScore: 58.33, delta: 16.66, kept: true }),
        makeIteration({ iteration: 2, baselineScore: 58.33, candidateScore: 41.67, delta: -16.66, kept: false }),
      ],
    });
    render(<EvolveTrajectoryChart runs={[detail]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as {
      series: Array<{
        markLine?: { data: Array<{ yAxis: number }> };
        data: Array<{ itemStyle: { borderColor: string } }>;
      }>;
    };
    // Line fixed at the original baseline, not the moved best.
    expect(option.series[0].markLine?.data[0].yAxis).toBe(41.67);
    const [first, second] = option.series[0].data;
    expect(first.itemStyle.borderColor).toContain('--color-success'); // above line → green
    // On the line (41.67 − 41.67 = 0) → neutral/tertiary, NOT red.
    expect(second.itemStyle.borderColor).toContain('--text-tertiary');
    expect(second.itemStyle.borderColor).not.toContain('--color-error');
  });

  it('shows legend when multiple runs are provided', () => {
    const runs = [makeDetail({ evolveRunId: 'run-001' }), makeDetail({ evolveRunId: 'run-002' })];
    render(<EvolveTrajectoryChart runs={runs} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as { legend: { show: boolean } };
    expect(option.legend.show).toBe(true);
  });

  it('hides legend for a single run', () => {
    render(<EvolveTrajectoryChart runs={[makeDetail()]} />);
    const option = JSON.parse(
      screen.getByTestId('echarts-mock').getAttribute('data-option') ?? '{}',
    ) as { legend: { show: boolean } };
    expect(option.legend.show).toBe(false);
  });

  it('respects custom height prop', () => {
    render(<EvolveTrajectoryChart runs={[makeDetail()]} height={500} />);
    const chart = screen.getByTestId('echarts-mock');
    expect(chart).toHaveStyle({ height: '500px' });
  });
});
