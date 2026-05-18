/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 (FE F5) — tabbed detail view for a
 * single skill draft. Refactor of the inline `DraftDetailPanel` that used to
 * live in `pages/SkillDrafts.tsx`; extracted so the eval report tab can be
 * added cleanly and so the page stays focused on list/state orchestration.
 *
 * Tabs (matching `tech-design.md` line ~399):
 *   - Overview       — original detail content (description / triggers / tools / hint / rationale)
 *   - Evaluation Report — new SKILL-CREATOR-WITH-EVAL panel
 *   - Source         — source session + reviewed metadata
 *
 * "Drawer" in the file name preserves the brief's reference and matches the
 * naming convention used elsewhere (`AgentDrawer`, `SkillDrawer`,
 * `ScheduleEditDrawer`); behaviorally this component is still the right
 * pane of the existing dual-pane page, not an Ant Design `Drawer` overlay
 * (the dual-pane UX was preserved intentionally — see SKILL-DRAFTS-REDESIGN
 * comment in `pages/SkillDrafts.tsx`).
 */
import { useMemo, useState } from 'react';
import { Tabs } from 'antd';
import type { SkillDraft } from '../../api';
import { SkillDraftEvaluationReport } from './SkillDraftEvaluationReport';

const HIGH_SIMILARITY_THRESHOLD = 0.85;
const SUGGEST_MERGE_THRESHOLD = 0.6;

type DraftStatusKind =
  | 'new'
  | 'warn'
  | 'err'
  | 'approved'
  | 'discarded'
  | 'evaluating'
  | 'evaluated'
  | 'rejected';

function deriveStatusKind(draft: SkillDraft): DraftStatusKind {
  if (draft.status === 'approved') return 'approved';
  if (draft.status === 'discarded') return 'discarded';
  if (draft.status === 'evaluating') return 'evaluating';
  if (draft.status === 'evaluated_passed') return 'evaluated';
  if (draft.status === 'rejected') return 'rejected';
  const sim = draft.similarity ?? 0;
  if (sim >= HIGH_SIMILARITY_THRESHOLD) return 'err';
  if (sim >= SUGGEST_MERGE_THRESHOLD) return 'warn';
  return 'new';
}

function StatusBadge({ status, similarity }: { status: DraftStatusKind; similarity?: number }) {
  const label = useMemo(() => {
    if (status === 'approved') return 'Approved';
    if (status === 'discarded') return 'Discarded';
    if (status === 'evaluating') return 'Evaluating…';
    if (status === 'evaluated') return 'Evaluated · Passed';
    if (status === 'rejected') return 'Rejected';
    if (status === 'err') return `High ${Math.round((similarity ?? 0) * 100)}%`;
    if (status === 'warn') return `Merge ${Math.round((similarity ?? 0) * 100)}%`;
    return 'New';
  }, [status, similarity]);
  return <span className={`item-badge badge-${status}`}>{label}</span>;
}

interface SkillDraftDetailDrawerProps {
  draft: SkillDraft;
  onApprove: (id: string) => void;
  onDiscard: (id: string) => void;
  onIterate?: (id: string) => void;
  approving: boolean;
  discarding: boolean;
}

export function SkillDraftDetailDrawer({
  draft,
  onApprove,
  onDiscard,
  onIterate,
  approving,
  discarding,
}: SkillDraftDetailDrawerProps) {
  const [activeTab, setActiveTab] = useState<string>('overview');
  const [showPromptHint, setShowPromptHint] = useState(false);
  const [showRationale, setShowRationale] = useState(false);

  const triggers = (draft.triggers ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);
  const tools = (draft.requiredTools ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);
  const statusKind = deriveStatusKind(draft);
  const isProcessed =
    draft.status === 'approved' ||
    draft.status === 'discarded' ||
    draft.status === 'evaluated_passed' ||
    draft.status === 'rejected';
  const isRejected = draft.status === 'rejected';

  const overviewPane = (
    <div className="draft-detail-content">
      <div className="detail-head">
        <h2 className="detail-title">
          {draft.name}
          <StatusBadge status={statusKind} similarity={draft.similarity} />
        </h2>
        {draft.description && <p className="detail-desc">{draft.description}</p>}
      </div>

      {isProcessed && draft.reviewedAt && (
        <div className="detail-section">
          <div className="processed-info">
            {draft.status === 'approved' || draft.status === 'evaluated_passed' ? (
              <div className="processed-approved">
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                >
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                {draft.status === 'approved' ? 'Approved' : 'Evaluated · Passed'} on{' '}
                {new Date(draft.reviewedAt).toLocaleString()}
                {draft.status === 'approved' &&
                  draft.skillId &&
                  ` → Skill #${draft.skillId}`}
              </div>
            ) : (
              <div className="processed-discarded">
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                >
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
                {draft.status === 'rejected' ? 'Rejected' : 'Discarded'} on{' '}
                {new Date(draft.reviewedAt).toLocaleString()}
              </div>
            )}
          </div>
        </div>
      )}

      {statusKind !== 'new' &&
        statusKind !== 'approved' &&
        statusKind !== 'discarded' &&
        statusKind !== 'evaluating' &&
        statusKind !== 'evaluated' &&
        statusKind !== 'rejected' && (
          <div className="detail-section">
            <SimilarityCard draft={draft} />
          </div>
        )}

      {triggers.length > 0 && (
        <div className="detail-section">
          <div className="section-label">Triggers</div>
          <div className="trigger-row">
            {triggers.map((t) => (
              <span key={t} className="trigger-pill">
                {t}
              </span>
            ))}
          </div>
        </div>
      )}

      {tools.length > 0 && (
        <div className="detail-section">
          <div className="section-label">Required Tools</div>
          <div className="tools-block">
            {tools.map((t) => (
              <span key={t} className="tool-tag">
                {t}
              </span>
            ))}
          </div>
        </div>
      )}

      {draft.promptHint && (
        <div className="detail-section">
          <div className="section-label">Prompt Hint</div>
          <button className="sf-mini-btn" onClick={() => setShowPromptHint((v) => !v)}>
            {showPromptHint ? 'Hide' : 'Show'} prompt hint
          </button>
          {showPromptHint && (
            <div className="prompt-block">
              <div className="prompt-bar">
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                >
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                </svg>
                SKILL.md
              </div>
              <pre className="prompt-body">{draft.promptHint}</pre>
            </div>
          )}
        </div>
      )}

      {draft.extractionRationale && (
        <div className="detail-section">
          <div className="section-label">Why Extracted?</div>
          <button className="sf-mini-btn" onClick={() => setShowRationale((v) => !v)}>
            {showRationale ? 'Hide' : 'Show'} rationale
          </button>
          {showRationale && <div className="rationale-block">{draft.extractionRationale}</div>}
        </div>
      )}
    </div>
  );

  const sourcePane = (
    <div className="draft-detail-content">
      <div className="detail-section">
        <div className="section-label">Origin</div>
        <div className="source-link">
          {draft.source && (
            <div style={{ marginBottom: 4 }}>
              <span style={{ color: 'var(--fg-3, #8a8a93)', marginRight: 6 }}>Created via</span>
              <span className="trigger-pill" data-testid="source-origin">
                {draft.source}
              </span>
            </div>
          )}
          {draft.sourceSessionId ? (
            <div>
              Source session{' '}
              <a href={`/sessions/${draft.sourceSessionId}`} data-testid="source-session-link">
                #{draft.sourceSessionId}
              </a>{' '}
              · {new Date(draft.createdAt).toLocaleString()}
            </div>
          ) : (
            <div style={{ color: 'var(--fg-3, #8a8a93)' }}>
              No source session linked · created {new Date(draft.createdAt).toLocaleString()}
            </div>
          )}
          {draft.targetAgentId != null && (
            <div style={{ marginTop: 6 }}>
              Target agent <span className="tool-tag">#{draft.targetAgentId}</span>
            </div>
          )}
          {draft.candidateSkillId != null && (
            <div style={{ marginTop: 6 }}>
              Transient candidate skill{' '}
              <span className="tool-tag">#{draft.candidateSkillId}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );

  return (
    <div className="draft-detail-content" data-testid="skill-draft-detail-drawer">
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'overview', label: 'Overview', children: overviewPane },
          {
            key: 'evaluation',
            label: 'Evaluation Report',
            children: (
              <SkillDraftEvaluationReport
                result={draft.evaluationResult}
                draftStatus={draft.status}
              />
            ),
          },
          { key: 'source', label: 'Source', children: sourcePane },
        ]}
      />

      {/* Footer actions — pending drafts get approve/discard; rejected drafts
          get an iterate button (skill-creator chat with prefill). Already-
          processed drafts (approved/discarded/evaluated_passed) show nothing. */}
      {!isProcessed && (
        <div className="draft-detail-footer">
          <button
            className="btn-discard"
            disabled={approving || discarding}
            onClick={() => onDiscard(draft.id)}
          >
            {discarding ? 'Discarding...' : 'Discard'}
          </button>
          <button
            className="btn-approve"
            disabled={approving || discarding}
            onClick={() => onApprove(draft.id)}
          >
            {approving ? 'Approving...' : 'Approve'}
          </button>
        </div>
      )}
      {isRejected && onIterate && (
        <div className="draft-detail-footer">
          <button
            className="btn-approve"
            onClick={() => onIterate(draft.id)}
            data-testid="iterate-btn"
          >
            Iterate on reject reason
          </button>
        </div>
      )}
    </div>
  );
}

function SimilarityCard({ draft }: { draft: SkillDraft }) {
  const sim = draft.similarity ?? 0;
  const level = sim >= HIGH_SIMILARITY_THRESHOLD ? 'high' : 'medium';
  return (
    <div className="similarity-card">
      <div className={`sim-indicator ${level}`}>{Math.round(sim * 100)}%</div>
      <div className="sim-text">
        <div className="sim-label">
          {level === 'high' ? 'High similarity detected' : 'Suggest merge'}
        </div>
        <div className="sim-desc">
          Similar to <strong>{draft.mergeCandidateName ?? draft.mergeCandidateId}</strong>
        </div>
      </div>
    </div>
  );
}

export default SkillDraftDetailDrawer;
