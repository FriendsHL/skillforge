import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  createEvalAnnotation,
  createEvalScenarioVersion,
  getEvalAnnotations,
  updateEvalAnnotation,
  type EvalAnnotation,
  type EvalTaskItem,
} from '../../api';
import { fmtTime, TRACE_ICON } from './evalUtils';

export default function AnnotationQueue({
  userId,
  seed,
  onSeedConsumed,
}: {
  userId: number;
  seed: EvalTaskItem | null;
  onSeedConsumed: () => void;
}) {
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState<'pending' | 'applied' | 'all'>('pending');
  const [draft, setDraft] = useState<{ taskItemId: number; correctedScore?: number | null; correctedExpected?: string | null } | null>(null);

  useEffect(() => {
    if (!seed) return;
    setDraft({
      taskItemId: seed.id,
      correctedScore: seed.compositeScore ?? undefined,
      correctedExpected: '',
    });
    onSeedConsumed();
  }, [onSeedConsumed, seed]);

  const { data: annotations = [], isLoading } = useQuery({
    queryKey: ['eval-annotations', statusFilter],
    queryFn: () => getEvalAnnotations(statusFilter === 'all' ? undefined : statusFilter).then(res => res.data ?? []),
  });

  const create = async () => {
    if (!draft) return;
    await createEvalAnnotation({
      taskItemId: draft.taskItemId,
      annotatorId: userId,
      correctedScore: draft.correctedScore ?? null,
      correctedExpected: draft.correctedExpected ?? null,
    });
    setDraft(null);
    queryClient.invalidateQueries({ queryKey: ['eval-annotations'] });
  };

  const markApplied = async (annotation: EvalAnnotation) => {
    await updateEvalAnnotation(annotation.id, {
      status: 'applied',
      correctedScore: annotation.correctedScore ?? null,
      correctedExpected: annotation.correctedExpected ?? null,
    });
    queryClient.invalidateQueries({ queryKey: ['eval-annotations'] });
  };

  const createScenarioVersionFromAnnotation = async (annotation: EvalAnnotation) => {
    if (!annotation.scenarioId || annotation.scenarioSource !== 'db') return;
    await createEvalScenarioVersion(annotation.scenarioId, {
      oracleExpected: annotation.correctedExpected ?? undefined,
      status: 'active',
    });
    await updateEvalAnnotation(annotation.id, {
      status: 'applied',
      correctedScore: annotation.correctedScore ?? null,
      correctedExpected: annotation.correctedExpected ?? null,
    });
    queryClient.invalidateQueries({ queryKey: ['eval-annotations'] });
    queryClient.invalidateQueries({ queryKey: ['eval-dataset-scenarios'] });
  };

  return (
    <div className="annotation-queue">
      <div className="annotation-queue-head">
        <div>
          <h3>Review Queue</h3>
          <p>Human score corrections and expected-output fixes. Create new dataset versions from corrections.</p>
        </div>
        <div className="annotation-filter-row">
          {(['pending', 'applied', 'all'] as const).map(status => (
            <button
              key={status}
              className={`sf-mini-btn ${statusFilter === status ? 'on' : ''}`}
              onClick={() => setStatusFilter(status)}
            >
              {status}
            </button>
          ))}
        </div>
      </div>

      {draft && (
        <div className="annotation-draft-card">
          <div className="annotation-draft-grid">
            <label>
              <span>Task item</span>
              <input value={String(draft.taskItemId)} disabled />
            </label>
            <label>
              <span>Corrected score</span>
              <input
                value={draft.correctedScore ?? ''}
                onChange={e => setDraft(prev => prev ? {
                  ...prev,
                  correctedScore: e.target.value === '' ? null : Number(e.target.value),
                } : prev)}
              />
            </label>
          </div>
          <label className="annotation-draft-textarea">
            <span>Corrected expected</span>
            <textarea
              rows={4}
              value={draft.correctedExpected ?? ''}
              onChange={e => setDraft(prev => prev ? { ...prev, correctedExpected: e.target.value } : prev)}
            />
          </label>
          <div className="annotation-draft-actions">
            <button className="btn-ghost-sf" onClick={() => setDraft(null)}>Cancel</button>
            <button className="btn-primary-sf" onClick={create}>Create annotation</button>
          </div>
        </div>
      )}

      {isLoading ? (
        <div className="sf-empty-state">Loading…</div>
      ) : annotations.length === 0 ? (
        <div className="sf-empty-state">No annotations in this queue.</div>
      ) : (
        <div className="annotation-list">
          {annotations.map(annotation => (
            <div key={annotation.id} className="annotation-card">
              <div className="annotation-card-h">
                <div>
                  <div className="annotation-title mono-sm">
                    {annotation.taskId ?? 'task'} / {annotation.scenarioId ?? `item-${annotation.taskItemId}`}
                  </div>
                  <div className="annotation-meta">
                    <span className="kv-chip-sf">{annotation.status}</span>
                    {annotation.itemStatus && <span className="kv-chip-sf">item · {annotation.itemStatus}</span>}
                    {annotation.attribution && <span className="kv-chip-sf">{annotation.attribution}</span>}
                  </div>
                </div>
                {annotation.status === 'pending' && (
                  <div style={{ display: 'flex', gap: 8 }}>
                    {annotation.scenarioSource === 'db' && annotation.correctedExpected && (
                      <button className="sf-mini-btn" onClick={() => createScenarioVersionFromAnnotation(annotation)}>
                        Create version
                      </button>
                    )}
                    <button className="sf-mini-btn" onClick={() => markApplied(annotation)}>Mark applied</button>
                  </div>
                )}
              </div>
              <div className="annotation-score-row">
                <span>Original {annotation.originalScore ?? '—'}</span>
                <span>→</span>
                <span>Corrected {annotation.correctedScore ?? '—'}</span>
              </div>
              {annotation.correctedExpected && <pre className="annotation-pre">{annotation.correctedExpected}</pre>}
              {annotation.judgeRationale && (
                <details>
                  <summary>Judge rationale</summary>
                  <pre className="annotation-pre">{annotation.judgeRationale}</pre>
                </details>
              )}
              {annotation.rootTraceId && (
                <Link className="sf-mini-btn trace-btn" to={`/traces?traceId=${encodeURIComponent(annotation.rootTraceId)}`}>
                  {TRACE_ICON} View trace
                </Link>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
