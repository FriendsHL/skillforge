/**
 * BC-M2b 子轮3 (G3) — EvolvePredictionPanel
 *
 * Renders, per evolve run, the falsifiable prediction vs. deterministic
 * reconciliation for each iteration that recorded one:
 *
 *   - Prediction: which scenarios the candidate was bet to flip to pass
 *     (and which it risked breaking), placed BEFORE the A/B ran.
 *   - Reconciliation: which predicted flips actually happened (hits), which
 *     didn't (misses), which at-risk scenarios regressed (riskHits), and which
 *     scenarios changed outcome without being predicted (surprises) — settled
 *     deterministically AFTER the A/B.
 *
 * Each row shows a compact summary badge (预测 N 翻 → 实际 X hit / Y miss · conf
 * Z%) and expands to the full prediction/reconciliation detail.
 *
 * Backward compatible: iterations without a prediction are skipped; a run with
 * no predicted iterations renders nothing.
 *
 * Copy is strictly mechanism-descriptive (predict / flip / reconcile /
 * confidence). `targetProblem`/`rationale` are BE-supplied runtime strings —
 * rendered verbatim, never augmented with remedy hints.
 */
import React, { useState, useCallback } from 'react';
import type {
  EvolveRunDetail,
  EvolveIteration,
  IterationPrediction,
  IterationReconciliation,
} from '../../api/evolve';
import './evolve.css';

interface EvolvePredictionPanelProps {
  run: EvolveRunDetail;
}

/** Confidence → semantic class. High = green, mid = neutral, low = red. */
function confidenceClass(confidence: number | null): string {
  if (confidence == null) return 'epred-conf--none';
  if (confidence >= 0.7) return 'epred-conf--high';
  if (confidence >= 0.4) return 'epred-conf--mid';
  return 'epred-conf--low';
}

function formatConfidence(confidence: number | null): string {
  if (confidence == null) return '—';
  return `${Math.round(confidence * 100)}%`;
}

// ─────────────────────────── id chip list ──────────────────────────────────

interface IdChipsProps {
  label: string;
  ids: string[];
  tone: 'flip' | 'risk' | 'hit' | 'miss' | 'risk-hit' | 'surprise';
}

const IdChips: React.FC<IdChipsProps> = React.memo(({ label, ids, tone }) => {
  if (ids.length === 0) return null;
  return (
    <div className="epred-chipgroup">
      <span className="epred-chipgroup-label">{label}</span>
      <div className="epred-chips">
        {ids.map((id) => (
          <span key={id} className={`epred-chip epred-chip--${tone}`} title={id}>
            {id}
          </span>
        ))}
      </div>
    </div>
  );
});
IdChips.displayName = 'IdChips';

// ─────────────────────────── one iteration row ─────────────────────────────

interface PredictionRowProps {
  /** Owning run id — namespaces the detail element id so multiple selected
   *  runs (each with their own Iter #1) never collide on a single HTML id. */
  evolveRunId: string;
  iteration: EvolveIteration;
  prediction: IterationPrediction;
  reconciliation: IterationReconciliation | null;
}

const PredictionRow: React.FC<PredictionRowProps> = React.memo(({
  evolveRunId,
  iteration,
  prediction,
  reconciliation,
}) => {
  const [expanded, setExpanded] = useState(false);
  const toggle = useCallback(() => setExpanded((v) => !v), []);

  const detailId = `epred-detail-${evolveRunId}-${iteration.iteration}`;
  const predictedFlips = prediction.flipToPass.length;

  return (
    <li className="epred-row" data-testid="epred-row">
      <button
        type="button"
        className="epred-summary"
        onClick={toggle}
        aria-expanded={expanded}
        aria-controls={detailId}
        data-testid="epred-summary"
      >
        <span className="epred-caret" aria-hidden="true">
          {expanded ? '▾' : '▸'}
        </span>
        <span className="epred-iter">Iter #{iteration.iteration}</span>
        <span className="epred-surface">{iteration.surface}</span>
        <span className="epred-target" title={prediction.targetProblem}>
          {prediction.targetProblem}
        </span>
        <span className="epred-badges">
          <span className="epred-badge epred-badge--flip">
            预测 {predictedFlips} 翻
          </span>
          {reconciliation ? (
            <>
              <span className="epred-badge epred-badge--hit">
                {reconciliation.hits.length} hit
              </span>
              <span className="epred-badge epred-badge--miss">
                {reconciliation.misses.length} miss
              </span>
              {reconciliation.riskHits.length > 0 && (
                <span className="epred-badge epred-badge--risk-hit">
                  {reconciliation.riskHits.length} risk-hit
                </span>
              )}
              {reconciliation.surprises.length > 0 && (
                <span className="epred-badge epred-badge--surprise">
                  {reconciliation.surprises.length} surprise
                </span>
              )}
              <span
                className={`epred-conf ${confidenceClass(reconciliation.confidence)}`}
                title="预测置信度 / 命中率"
              >
                conf {formatConfidence(reconciliation.confidence)}
              </span>
            </>
          ) : (
            <span className="epred-badge epred-badge--pending">对账待完成</span>
          )}
        </span>
      </button>

      {expanded && (
        <div className="epred-detail" id={detailId} data-testid="epred-detail">
          <section className="epred-block">
            <h5 className="epred-block-title">预测</h5>
            {prediction.issueId && (
              <p className="epred-meta">
                <span className="epred-meta-label">Issue</span>
                <span className="epred-meta-val">{prediction.issueId}</span>
              </p>
            )}
            <p className="epred-target-full">{prediction.targetProblem}</p>
            <IdChips label="预测翻 pass" ids={prediction.flipToPass} tone="flip" />
            <IdChips label="风险场景" ids={prediction.riskToFail} tone="risk" />
            {prediction.rationale && (
              <p className="epred-rationale">{prediction.rationale}</p>
            )}
          </section>

          <section className="epred-block">
            <h5 className="epred-block-title">实际对账</h5>
            {reconciliation ? (
              <>
                <IdChips label="命中（翻盘）" ids={reconciliation.hits} tone="hit" />
                <IdChips label="未中" ids={reconciliation.misses} tone="miss" />
                <IdChips
                  label="风险兑现（回退）"
                  ids={reconciliation.riskHits}
                  tone="risk-hit"
                />
                <IdChips
                  label="意外（未预测却翻盘）"
                  ids={reconciliation.surprises}
                  tone="surprise"
                />
                <p className="epred-meta">
                  <span className="epred-meta-label">Confidence</span>
                  <span
                    className={`epred-meta-val ${confidenceClass(reconciliation.confidence)}`}
                  >
                    {formatConfidence(reconciliation.confidence)}
                  </span>
                </p>
              </>
            ) : (
              <p className="epred-pending-hint">
                A/B 尚未完成，对账数据待生成。
              </p>
            )}
          </section>
        </div>
      )}
    </li>
  );
});
PredictionRow.displayName = 'PredictionRow';

// ─────────────────────────── panel ─────────────────────────────────────────

/** Type-narrowing predicate: an iteration whose prediction is present. Lets the
 *  filter result carry a non-null `prediction` so no `as` assertion is needed. */
type PredictedIteration = EvolveIteration & { prediction: IterationPrediction };
const hasPrediction = (it: EvolveIteration): it is PredictedIteration =>
  it.prediction != null;

const EvolvePredictionPanel: React.FC<EvolvePredictionPanelProps> = React.memo(({ run }) => {
  // Only iterations that recorded a prediction (G3). Legacy iterations skipped.
  const predicted = run.iterations.filter(hasPrediction);
  if (predicted.length === 0) return null;

  return (
    <section
      className="epred-section"
      aria-label="Prediction reconciliation"
      data-testid="evolve-prediction-panel"
    >
      <header className="epred-head">
        <h4 className="epred-title">预测对账</h4>
        <span className="epred-subtitle" title={run.evolveRunId}>
          {run.agentName} · {predicted.length} 条预测
        </span>
      </header>
      <ul className="epred-list">
        {predicted.map((it) => (
          <PredictionRow
            key={it.iteration}
            evolveRunId={run.evolveRunId}
            iteration={it}
            prediction={it.prediction}
            reconciliation={it.reconciliation ?? null}
          />
        ))}
      </ul>
    </section>
  );
});
EvolvePredictionPanel.displayName = 'EvolvePredictionPanel';

export default EvolvePredictionPanel;
