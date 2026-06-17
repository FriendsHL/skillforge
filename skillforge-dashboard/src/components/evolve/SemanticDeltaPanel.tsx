/**
 * AUTOEVOLVE Phase 2a — SemanticDeltaPanel
 *
 * Renders "what the candidate changed" for each iteration of an evolve run:
 * per changed surface (prompt / behavior_rule / skill), a unified diff with
 * +/- line coloring plus that surface's change description. Reads the
 * `semanticDelta` sidecar (an ARRAY in P2a multi-surface, a single object in
 * legacy P1, or null) off each iteration and normalizes it via Array.isArray.
 *
 * Read-only history view — no WebSocket, no side effects.
 */
import React from 'react';
import { Tag } from 'antd';
import type { EvolveRunDetail, SemanticDelta } from '../../api/evolve';
import './evolve.css';

interface SemanticDeltaPanelProps {
  run: EvolveRunDetail;
}

const SURFACE_COLOR: Record<string, string> = {
  prompt: 'geekblue',
  behavior_rule: 'purple',
  skill: 'green',
};

/** Normalize the sidecar (array | single object | null) into a clean array. */
function toDeltaList(
  sd: SemanticDelta | SemanticDelta[] | null | undefined,
): SemanticDelta[] {
  if (sd == null) return [];
  return (Array.isArray(sd) ? sd : [sd]).filter((d) => d != null);
}

type DiffLine = { kind: 'add' | 'del' | 'ctx'; text: string };

/**
 * DOM cap per diff block: the BE block-diff fallback can emit before+after in
 * full (2×2000+ lines); rendering each as a span would blow up the DOM across
 * multiple runs × iterations. Lines beyond the cap collapse into a count row.
 */
const MAX_DIFF_LINES = 400;

/** Split a unified diff into lines tagged add / del / context for coloring. */
function diffLines(diff: string): DiffLine[] {
  const raw = diff.split('\n');
  // A trailing newline (standard in unified diffs) yields a phantom empty last
  // element — drop it so it doesn't render a blank line or skew the truncation count.
  if (raw.length > 0 && raw[raw.length - 1] === '') {
    raw.pop();
  }
  return raw.map((line) => {
    if (line.startsWith('+')) return { kind: 'add', text: line };
    if (line.startsWith('-')) return { kind: 'del', text: line };
    return { kind: 'ctx', text: line };
  });
}

const DiffBlock: React.FC<{ surface: string; diff: string }> = React.memo(
  ({ surface, diff }) => {
    const lines = diffLines(diff);
    return (
      <pre className="esd-diff" aria-label={`${surface} diff`}>
        {lines.slice(0, MAX_DIFF_LINES).map((ln, j) => (
          <span key={j} className={`esd-line esd-line--${ln.kind}`}>
            {ln.text + '\n'}
          </span>
        ))}
        {lines.length > MAX_DIFF_LINES && (
          <span className="esd-line esd-line--ctx">
            （已截断，剩余 {lines.length - MAX_DIFF_LINES} 行未显示）
          </span>
        )}
      </pre>
    );
  },
);

DiffBlock.displayName = 'DiffBlock';

const SemanticDeltaPanel: React.FC<SemanticDeltaPanelProps> = React.memo(({ run }) => {
  // Only iterations that actually carry a delta (skip legacy/empty rows).
  const rows = run.iterations
    .map((it) => ({ iteration: it.iteration, deltas: toDeltaList(it.semanticDelta) }))
    .filter((r) => r.deltas.length > 0);

  if (rows.length === 0) return null;

  return (
    <section
      className="esd-section"
      aria-label="Candidate changes"
      data-testid="semantic-delta-panel"
    >
      <h4 className="esd-title">候选改动 · {run.agentName}</h4>
      {rows.map((row) => (
        <div className="esd-iter" key={`sd-${run.evolveRunId}-${row.iteration}`}>
          <div className="esd-iter-head">
            <span className="esd-iter-no">第 {row.iteration} 轮</span>
            {row.deltas.map((d, i) => (
              <Tag key={`${d.surface}-${i}`} color={SURFACE_COLOR[d.surface] ?? 'default'}>
                {d.surface}
              </Tag>
            ))}
          </div>
          {row.deltas.map((d, i) => (
            <div className="esd-surface" key={`${d.surface}-${i}`}>
              {d.changeDesc && <p className="esd-change-desc">{d.changeDesc}</p>}
              {d.diff ? (
                <DiffBlock surface={d.surface} diff={d.diff} />
              ) : (
                <p className="esd-empty">（该面无 diff）</p>
              )}
            </div>
          ))}
        </div>
      ))}
    </section>
  );
});

SemanticDeltaPanel.displayName = 'SemanticDeltaPanel';

export default SemanticDeltaPanel;
