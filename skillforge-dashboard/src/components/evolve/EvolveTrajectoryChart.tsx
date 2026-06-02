/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — EvolveTrajectoryChart
 *
 * ECharts line chart showing candidateScore over iteration number.
 * - One series per evolve run (multi-run overlay for AC-D4).
 * - Kept iterations: solid filled marker circle.
 * - Not-kept iterations: hollow marker circle (borderColor set, color transparent).
 * - Tooltip shows surface / changeDesc / delta / kept for each point.
 * - Respects CSS variables for dark/light theming.
 *
 * Uses echarts-for-react ReactECharts which disposes the underlying echarts
 * instance on unmount automatically (no manual dispose needed per the lib).
 */
import React, { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';

import type { EvolveRunDetail, EvolveIteration } from '../../api/evolve';

interface EvolveTrajectoryChartProps {
  /** One or more evolve run detail objects to overlay on the same chart. */
  runs: EvolveRunDetail[];
  /** Chart height in px (default 340). */
  height?: number;
}

/** Colour palette for up to 8 overlaid runs. Cycles if more. */
const RUN_COLORS = [
  '#6366f1', // indigo
  '#10b981', // emerald
  '#f59e0b', // amber
  '#ef4444', // red
  '#8b5cf6', // violet
  '#06b6d4', // cyan
  '#f97316', // orange
  '#ec4899', // pink
];

function runColor(idx: number): string {
  return RUN_COLORS[idx % RUN_COLORS.length];
}

function formatDelta(delta: number): string {
  if (delta > 0) return `+${delta.toFixed(2)}pp`;
  if (delta < 0) return `${delta.toFixed(2)}pp`;
  return '±0pp';
}

/** Colour expressing a candidate's position relative to baseline: up / down / flat. */
function deltaColor(delta: number): string {
  if (delta > 0) return 'var(--color-success,#5c8a4a)';
  if (delta < 0) return 'var(--color-error,#b8412f)';
  return 'var(--text-tertiary,#7a7770)';
}

/**
 * Baseline is measured once per run (hill-climb), so every iteration carries the
 * same baselineScore. Return the first non-null one as the run's baseline; null
 * when no iteration recorded a baseline (baseline unavailable).
 */
function runBaseline(run: EvolveRunDetail): number | null {
  for (const it of run.iterations) {
    if (it.baselineScore != null) return it.baselineScore;
  }
  return null;
}

function buildSeries(run: EvolveRunDetail, colorIdx: number): object {
  const color = runColor(colorIdx);
  const label =
    run.agentName
      ? `${run.agentName} · ${run.evolveRunId.slice(0, 6)}`
      : run.evolveRunId.slice(0, 8);

  const data = run.iterations.map((iter: EvolveIteration) => {
    // Marker colour encodes rise/fall vs baseline (delta = candidate − baseline);
    // fill encodes whether the candidate was kept (became the new carry-forward
    // best). So a hollow green point = above baseline but not a new best.
    const sign = deltaColor(iter.delta);
    return {
      value: [iter.iteration, iter.candidateScore],
      // Store full iteration metadata for tooltip access.
      iterMeta: iter,
      itemStyle: {
        color: iter.kept ? sign : 'transparent',
        borderColor: sign,
        borderWidth: 2,
      },
    };
  });

  const baseline = runBaseline(run);

  const series: Record<string, unknown> = {
    name: label,
    type: 'line',
    data,
    smooth: false,
    connectNulls: false,
    lineStyle: { color, width: 2 },
    itemStyle: { color },
    symbol: 'circle',
    symbolSize: (
      _val: unknown,
      params: { data?: { iterMeta?: EvolveIteration } },
    ): number => {
      const kept = params?.data?.iterMeta?.kept;
      return kept ? 9 : 7;
    },
  };

  // Constant horizontal reference line at the run's baseline. Every iteration's
  // point sits above (rise) or below (fall) this line — that's the comparison
  // the trajectory is meant to convey.
  if (baseline != null) {
    series.markLine = {
      silent: true,
      symbol: 'none',
      lineStyle: { color, type: 'dashed', width: 1.5, opacity: 0.85 },
      label: {
        formatter: `baseline ${baseline.toFixed(2)}`,
        position: 'insideStartTop',
        color: 'var(--text-tertiary, #7a7770)',
        fontSize: 11,
      },
      data: [{ yAxis: baseline }],
    };
  }

  return series;
}

const EvolveTrajectoryChart: React.FC<EvolveTrajectoryChartProps> = ({
  runs,
  height = 340,
}) => {
  const option = useMemo((): EChartsOption => {
    // Only plot runs that recorded at least one iteration. Empty runs (errored
    // early / still running before any RecordIteration) would otherwise add a
    // legend entry with no line — and since they share the agent name, several
    // empty runs of the same agent look like "several Research Agents".
    const plotted = runs.filter((r) => (r.iterations?.length ?? 0) > 0);
    if (plotted.length === 0) {
      return {};
    }

    const series = plotted.map((run, idx) => buildSeries(run, idx));

    // Pad the x-axis to [0, maxIter+1] so points never sit on the chart edge.
    // With a single iteration (point at x=1) the axis is 0..2, centering it;
    // multi-iteration runs get symmetric breathing room on both sides.
    const maxIter = Math.max(
      1,
      ...plotted.flatMap((r) => r.iterations.map((it) => it.iteration)),
    );

    return {
      backgroundColor: 'transparent',
      animation: true,
      animationDuration: 400,
      legend: {
        show: plotted.length > 1,
        top: 4,
        right: 8,
        textStyle: { color: 'var(--text-secondary, #5d5952)', fontSize: 12 },
        icon: 'circle',
        itemWidth: 10,
        itemHeight: 10,
      },
      grid: {
        top: plotted.length > 1 ? 48 : 24,
        right: 24,
        bottom: 48,
        left: 56,
      },
      xAxis: {
        type: 'value',
        name: 'Iteration',
        nameLocation: 'middle',
        nameGap: 28,
        nameTextStyle: { color: 'var(--text-tertiary, #7a7770)', fontSize: 12 },
        min: 0,
        max: maxIter + 1,
        minInterval: 1,
        axisLine: { lineStyle: { color: 'var(--border-subtle, #e2ded3)' } },
        axisTick: { lineStyle: { color: 'var(--border-subtle, #e2ded3)' } },
        axisLabel: {
          color: 'var(--text-tertiary, #7a7770)',
          fontSize: 11,
          formatter: (v: number) => String(Math.round(v)),
        },
        splitLine: { lineStyle: { color: 'var(--border-subtle, #e2ded3)', type: 'dashed' } },
      },
      yAxis: {
        type: 'value',
        name: 'Score',
        nameLocation: 'middle',
        nameGap: 40,
        nameTextStyle: { color: 'var(--text-tertiary, #7a7770)', fontSize: 12 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: 'var(--text-tertiary, #7a7770)',
          fontSize: 11,
          formatter: (v: number) => v.toFixed(2),
        },
        splitLine: { lineStyle: { color: 'var(--border-subtle, #e2ded3)', type: 'dashed' } },
      },
      tooltip: {
        trigger: 'item',
        backgroundColor: 'var(--bg-surface, #ffffff)',
        borderColor: 'var(--border-medium, #cfcbc0)',
        borderWidth: 1,
        padding: [8, 12],
        textStyle: { color: 'var(--text-primary, #1a1815)', fontSize: 12 },
        formatter: (rawParams: unknown): string => {
          const params = Array.isArray(rawParams) ? rawParams[0] : (rawParams as Record<string, unknown> | undefined);
          const pointData = (params as { data?: { iterMeta?: EvolveIteration } } | undefined)?.data;
          const meta: EvolveIteration | undefined = pointData?.iterMeta;
          if (!meta) return '';

          const kept = meta.kept
            ? '<span style="color:var(--color-success,#5c8a4a)">✓ kept (new best)</span>'
            : '<span style="color:var(--text-tertiary,#7a7770)">✗ not kept</span>';
          const delta = formatDelta(meta.delta);
          const deltaCol = deltaColor(meta.delta);
          const score = meta.candidateScore != null
            ? meta.candidateScore.toFixed(3)
            : '—';
          const baseline = meta.baselineScore != null
            ? meta.baselineScore.toFixed(3)
            : '—';

          return `
            <div style="font-size:12px;line-height:1.6;max-width:280px">
              <div style="font-weight:600;margin-bottom:4px">
                Iter ${meta.iteration} · <span style="font-family:var(--font-mono,monospace);font-size:11px">${meta.surface}</span>
              </div>
              <div style="margin-bottom:4px;word-break:break-word">${meta.changeDesc}</div>
              <div>Baseline: <strong>${baseline}</strong></div>
              <div>Score: <strong>${score}</strong></div>
              <div>vs baseline: <strong style="color:${deltaCol}">${delta}</strong></div>
              <div>${kept}</div>
            </div>
          `.trim();
        },
      },
      series,
    };
  }, [runs]);

  if (runs.length === 0) {
    return (
      <div className="etc-empty" data-testid="evolve-trajectory-chart-empty">
        Select an evolve run to view its trajectory.
      </div>
    );
  }

  // A run is selected but none of the selected runs recorded any iteration — show a
  // clear message instead of a blank chart. A run only records points after it
  // completes an A/B-evaluated iteration; runs that errored early (max_loops /
  // duration / rate-limit before RecordIteration) have an empty trajectory.
  const totalPoints = runs.reduce(
    (n, r) => n + (r.iterations?.length ?? 0),
    0,
  );
  if (totalPoints === 0) {
    return (
      <div className="etc-empty" data-testid="evolve-trajectory-chart-noiter">
        No iterations recorded for the selected run{runs.length > 1 ? 's' : ''} yet.
        A run plots points only after it completes an A/B-evaluated iteration —
        runs that errored early (or are still running) have none.
      </div>
    );
  }

  return (
    <ReactECharts
      option={option}
      style={{ height, width: '100%' }}
      notMerge
      lazyUpdate={false}
      data-testid="evolve-trajectory-chart"
    />
  );
};

export default EvolveTrajectoryChart;
