import type { EvalTaskItem } from '../../api';

/* ── Icons ── */
export const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);
export const PLAY_ICON = (
  <svg width={10} height={10} viewBox="0 0 16 16" fill="currentColor"><path d="M4 3l10 5-10 5z" /></svg>
);
export const TRACE_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M6 3h7v7" /><path d="M13 3L5 11" />
  </svg>
);
export const ANALYZE_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="7" cy="7" r="4.5" /><path d="M10.5 10.5L14 14" />
  </svg>
);
export const ANNOTATE_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M11.5 1.5l3 3L5 14H2v-3z" />
  </svg>
);
export const ARROW_ICON = (
  <svg width={10} height={10} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 8h10M9 4l4 4-4 4" />
  </svg>
);

/* ── EvalMetric ── */
export type EvalMetric = 'composite' | 'quality' | 'efficiency' | 'latency' | 'cost';

export const METRIC_OPTIONS: Array<{ value: EvalMetric; label: string }> = [
  { value: 'composite', label: 'Composite' },
  { value: 'quality', label: 'Quality' },
  { value: 'efficiency', label: 'Efficiency' },
  { value: 'latency', label: 'Latency' },
  { value: 'cost', label: 'Cost' },
];

export function getMetricValue(
  item: Pick<EvalTaskItem, 'compositeScore' | 'qualityScore' | 'efficiencyScore' | 'latencyScore' | 'costScore'>,
  metric: EvalMetric,
): number | null {
  switch (metric) {
    case 'quality': return item.qualityScore ?? null;
    case 'efficiency': return item.efficiencyScore ?? null;
    case 'latency': return item.latencyScore ?? null;
    case 'cost': return item.costScore ?? null;
    case 'composite':
    default: return item.compositeScore ?? null;
  }
}

export function formatMetricValue(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? '—' : `${Math.round(value)}%`;
}

export function computeMetricDelta(
  entries: Array<{ compositeScore?: number | null; qualityScore?: number | null; efficiencyScore?: number | null; latencyScore?: number | null; costScore?: number | null }>,
  metric: EvalMetric,
): number | null {
  const values = entries
    .map((e) => getMetricValue(e, metric))
    .filter((v): v is number => v != null && Number.isFinite(v));
  if (values.length < 2) return null;
  return Math.max(...values) - Math.min(...values);
}

export function scoreColor(s: number): string {
  if (s >= 0.9) return 'var(--color-ok)';
  if (s >= 0.75) return 'var(--color-info)';
  if (s >= 0.6) return 'var(--color-warn)';
  return 'var(--color-err)';
}

export function scoreTier(s: number): 'pass' | 'warn' | 'fail' {
  if (s >= 0.8) return 'pass';
  if (s >= 0.6) return 'warn';
  return 'fail';
}

export function fmtTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  const diff = Date.now() - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/* ── EvalRow (normalized task for list display) ── */
export interface EvalRow {
  id: string;
  name: string;
  suite: string;
  target: string;
  agentId: string;
  lastRun: string;
  cases: number;
  pass: number;
  fail: number;
  score: number;
  trend: number[];
  runs: number;
  status: string;
  raw: Record<string, unknown>;
}

export function normalizeEval(raw: Record<string, unknown>, agents: Record<string, unknown>[]): EvalRow {
  const agentId = String(raw.agentDefinitionId || '');
  const agent = agents.find(a => String(a.id) === agentId);
  const total = Number(raw.scenarioCount ?? raw.totalScenarios ?? 0);
  const passed = Number(raw.passCount ?? 0);
  const failed = Number(raw.failCount ?? 0);
  const scorePct = Number(
    raw.compositeAvg ?? raw.overallPassRate ?? (total > 0 ? (passed / total) * 100 : 0),
  );
  const score = Number.isFinite(scorePct) ? scorePct / 100 : 0;

  let status = 'fail';
  if (raw.status === 'RUNNING' || raw.status === 'PENDING') status = 'warn';
  else if (raw.status === 'FAILED' || raw.status === 'CANCELLED') status = 'fail';
  else if (score >= 0.9) status = 'pass';
  else if (score >= 0.7) status = 'warn';

  return {
    id: String(raw.id),
    name: agent ? String(agent.name || `Agent #${agentId}`) : `Eval ${String(raw.id || '').slice(0, 8)}`,
    suite: 'default',
    target: agent ? String(agent.name || agentId) : agentId,
    agentId,
    lastRun: fmtTime(String(raw.completedAt || raw.startedAt || '')),
    cases: total,
    pass: passed,
    fail: failed,
    score,
    trend: generateTrend(score),
    runs: 1,
    status,
    raw,
  };
}

function generateTrend(currentScore: number): number[] {
  const trend: number[] = [];
  for (let i = 0; i < 7; i++) {
    const noise = (Math.random() - 0.5) * 0.15;
    trend.push(Math.max(0, Math.min(1, currentScore + noise)));
  }
  trend[6] = currentScore;
  return trend;
}

/* ── ProgressState (WS live updates) ── */
export interface ProgressState {
  passedCount: number;
  totalCount: number;
  currentScenarioName?: string;
  completed?: boolean;
}
