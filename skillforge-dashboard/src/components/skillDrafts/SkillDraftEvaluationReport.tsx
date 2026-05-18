/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 (FE F5) — evaluation report panel for a
 * single skill draft.
 *
 * Renders the BE `EvaluationResult` blob (see `api/skillDrafts.ts`) as:
 *   - per-metric benchmark table (with_skill / without_skill / delta)
 *   - LLM summary alert (success vs warning depending on `delta.passRate`)
 *   - drill-down button that deep-links the source sessions (`?sessionIds=...`)
 *   - status badge ('evaluated_passed' | 'rejected' | 'pending').
 *
 * Designed as a presentation component — receives the parsed result via props
 * and is data-fetching agnostic. Caller (`SkillDraftDetailDrawer`) owns
 * mounting and gates render when `result` is absent.
 */
import { useMemo } from 'react';
import { Alert, Button, Statistic, Table, Tag, Empty } from 'antd';
import { useNavigate } from 'react-router-dom';
import {
  PASS_RATE_DELTA_THRESHOLD,
  isPassingResult,
  type EvaluationResult,
} from '../../api/skillDrafts';

interface SkillDraftEvaluationReportProps {
  result: EvaluationResult | null | undefined;
  /**
   * Optional explicit status from `SkillDraft.status`. When provided, drives
   * the status badge directly (the BE is the source of truth — the FE may
   * lag if the operator stamped a manual override). When omitted, derived
   * from `result.delta.passRate` via {@link isPassingResult}.
   */
  draftStatus?: 'evaluated_passed' | 'rejected' | 'draft' | 'approved' | 'discarded' | string;
}

interface BenchmarkRow {
  key: string;
  metric: string;
  withSkill: string;
  withoutSkill: string;
  delta: string;
  /** Set when delta is meaningfully positive/negative for that metric. */
  deltaTone: 'pos' | 'neg' | 'neutral';
}

function formatPct(n: number): string {
  return `${(n * 100).toFixed(1)}%`;
}

function formatScore(n: number): string {
  return n.toFixed(3);
}

function formatLatency(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatCost(usd: number): string {
  if (usd < 0.01) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(3)}`;
}

function formatDeltaSigned(value: number, formatter: (n: number) => string): string {
  if (value === 0) return formatter(0);
  const sign = value > 0 ? '+' : '−';
  return `${sign}${formatter(Math.abs(value))}`;
}

/**
 * Status badge — keeps the visual language consistent with the Rejected tab
 * in `SkillDrafts.tsx`. Uses Ant Design `Tag` colors that line up with the
 * `.draft-stat.rejected` / `.draft-stat.evaluated` chip palette.
 */
function StatusBadge({
  status,
}: {
  status: 'evaluated_passed' | 'rejected' | 'pending';
}) {
  if (status === 'evaluated_passed') {
    return (
      <Tag color="success" data-testid="eval-status-badge">
        evaluated · passed
      </Tag>
    );
  }
  if (status === 'rejected') {
    return (
      <Tag color="error" data-testid="eval-status-badge">
        rejected
      </Tag>
    );
  }
  return (
    <Tag color="default" data-testid="eval-status-badge">
      pending
    </Tag>
  );
}

/**
 * SKILL-CREATOR-WITH-EVAL F5 — evaluation report content. Empty state when
 * `result` is undefined so the caller can mount the tab unconditionally
 * without branching on data presence.
 */
export function SkillDraftEvaluationReport({
  result,
  draftStatus,
}: SkillDraftEvaluationReportProps) {
  const navigate = useNavigate();

  const verdictStatus: 'evaluated_passed' | 'rejected' | 'pending' = useMemo(() => {
    if (draftStatus === 'evaluated_passed') return 'evaluated_passed';
    if (draftStatus === 'rejected') return 'rejected';
    if (!result) return 'pending';
    return isPassingResult(result) ? 'evaluated_passed' : 'rejected';
  }, [draftStatus, result]);

  const rows: BenchmarkRow[] = useMemo(() => {
    if (!result) return [];
    const { withSkill, withoutSkill, delta } = result;
    return [
      {
        key: 'passRate',
        metric: 'Pass rate',
        withSkill: formatPct(withSkill.passRate),
        withoutSkill: formatPct(withoutSkill.passRate),
        delta: formatDeltaSigned(delta.passRate, formatPct),
        deltaTone: delta.passRate > 0 ? 'pos' : delta.passRate < 0 ? 'neg' : 'neutral',
      },
      {
        key: 'compositeScore',
        metric: 'Composite score',
        withSkill: formatScore(withSkill.compositeScore),
        withoutSkill: formatScore(withoutSkill.compositeScore),
        delta: formatDeltaSigned(delta.compositeScore, formatScore),
        deltaTone:
          delta.compositeScore > 0 ? 'pos' : delta.compositeScore < 0 ? 'neg' : 'neutral',
      },
      {
        key: 'overallScore',
        metric: 'Overall score',
        withSkill: formatScore(withSkill.overallScore),
        withoutSkill: formatScore(withoutSkill.overallScore),
        delta: formatDeltaSigned(delta.overallScore, formatScore),
        deltaTone:
          delta.overallScore > 0 ? 'pos' : delta.overallScore < 0 ? 'neg' : 'neutral',
      },
      {
        key: 'avgLatencyMs',
        metric: 'Avg latency',
        withSkill: formatLatency(withSkill.avgLatencyMs),
        withoutSkill: formatLatency(withoutSkill.avgLatencyMs),
        delta: formatDeltaSigned(delta.avgLatencyMs, formatLatency),
        // Lower latency is better, so positive delta = worse (neg tone).
        deltaTone: delta.avgLatencyMs > 0 ? 'neg' : delta.avgLatencyMs < 0 ? 'pos' : 'neutral',
      },
      {
        key: 'totalCostUsd',
        metric: 'Total cost',
        withSkill: formatCost(withSkill.totalCostUsd),
        withoutSkill: formatCost(withoutSkill.totalCostUsd),
        delta: formatDeltaSigned(delta.totalCostUsd, formatCost),
        // Lower cost is better.
        deltaTone:
          delta.totalCostUsd > 0 ? 'neg' : delta.totalCostUsd < 0 ? 'pos' : 'neutral',
      },
    ];
  }, [result]);

  if (!result) {
    return (
      <div data-testid="eval-report-empty" style={{ padding: 24 }}>
        <Empty
          description={
            <span style={{ color: 'var(--fg-3, #8a8a93)', fontSize: 13 }}>
              No evaluation run yet for this draft.
              <br />
              Evaluation runs automatically when the draft is created via
              upload / marketplace / extract-from-sessions / skill-creator.
            </span>
          }
        />
      </div>
    );
  }

  const passing = isPassingResult(result);
  const alertType = passing ? 'success' : 'warning';

  const handleDrillToSessions = () => {
    if (!result.sourceSessionIds.length) return;
    const ids = result.sourceSessionIds.join(',');
    navigate(`/sessions?sessionIds=${encodeURIComponent(ids)}`);
  };

  return (
    <div className="skill-draft-eval-report" data-testid="eval-report-root">
      {/* Header: status badge + headline deltas */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 16,
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 600 }}>Evaluation Report</h3>
          <StatusBadge status={verdictStatus} />
        </div>
        <div style={{ display: 'flex', gap: 24 }}>
          <Statistic
            title="Pass rate Δ"
            value={formatDeltaSigned(result.delta.passRate, formatPct)}
            valueStyle={{
              fontSize: 18,
              color:
                result.delta.passRate > 0
                  ? 'var(--success, #10b981)'
                  : result.delta.passRate < 0
                  ? 'var(--danger, #ef4444)'
                  : 'var(--fg-2, #c0c0c8)',
            }}
          />
          <Statistic
            title="Composite Δ"
            value={formatDeltaSigned(result.delta.compositeScore, formatScore)}
            valueStyle={{
              fontSize: 18,
              color:
                result.delta.compositeScore > 0
                  ? 'var(--success, #10b981)'
                  : result.delta.compositeScore < 0
                  ? 'var(--danger, #ef4444)'
                  : 'var(--fg-2, #c0c0c8)',
            }}
          />
        </div>
      </div>

      {/* LLM summary alert — success when passing threshold, warning otherwise */}
      <Alert
        type={alertType}
        showIcon
        message={passing ? 'Skill adds measurable value' : 'Skill did not clear the threshold'}
        description={result.llmSummary}
        style={{ marginBottom: 20 }}
        data-testid="eval-llm-summary"
      />

      {/* Benchmark table */}
      <Table<BenchmarkRow>
        size="small"
        pagination={false}
        dataSource={rows}
        rowKey="key"
        data-testid="eval-benchmark-table"
        columns={[
          {
            title: 'Metric',
            dataIndex: 'metric',
            key: 'metric',
            width: 180,
          },
          {
            title: 'With skill',
            dataIndex: 'withSkill',
            key: 'withSkill',
            align: 'right',
          },
          {
            title: 'Without skill',
            dataIndex: 'withoutSkill',
            key: 'withoutSkill',
            align: 'right',
          },
          {
            title: 'Δ',
            dataIndex: 'delta',
            key: 'delta',
            align: 'right',
            render: (text: string, row: BenchmarkRow) => (
              <span
                style={{
                  fontFamily: 'var(--font-mono, monospace)',
                  fontWeight: 600,
                  color:
                    row.deltaTone === 'pos'
                      ? 'var(--success, #10b981)'
                      : row.deltaTone === 'neg'
                      ? 'var(--danger, #ef4444)'
                      : 'var(--fg-2, #c0c0c8)',
                }}
              >
                {text}
              </span>
            ),
          },
        ]}
      />

      {/* Source sessions drill-down + metadata footer */}
      <div
        style={{
          marginTop: 20,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ fontSize: 12, color: 'var(--fg-3, #8a8a93)' }}>
          {result.scenarioCount} scenario{result.scenarioCount === 1 ? '' : 's'} ·{' '}
          evaluator {result.evaluatorVersion} · evaluated{' '}
          {new Date(result.evaluatedAt).toLocaleString()} ·{' '}
          threshold +{Math.round(PASS_RATE_DELTA_THRESHOLD * 100)}pp pass rate
        </div>
        <Button
          type="default"
          size="small"
          disabled={result.sourceSessionIds.length === 0}
          onClick={handleDrillToSessions}
          data-testid="eval-drilldown-btn"
        >
          View source sessions ({result.sourceSessionIds.length})
        </Button>
      </div>
    </div>
  );
}

export default SkillDraftEvaluationReport;
