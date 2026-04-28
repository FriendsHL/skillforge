import React, { useState } from 'react';
import type { SkillDraft } from '../../api';
import { CHEVRON_ICON } from './icons';

/**
 * P1-C-8 dedup thresholds (per plan §9):
 *   ≥ 0.85   → high-similarity (red badge, approve gated by confirm modal)
 *   0.60-0.85 → suggest-merge (amber badge, approve unblocked)
 *   < 0.60   → no badge
 */
const HIGH_SIMILARITY = 0.85;
const SUGGEST_MERGE_SIMILARITY = 0.60;

interface SimilarityBadgeStyle {
  background: string;
  color: string;
  label: string;
}

function similarityStyle(sim: number, mergeName: string | undefined): SimilarityBadgeStyle | null {
  if (sim >= HIGH_SIMILARITY) {
    return {
      background: 'rgba(240,97,109,0.14)',
      color: '#f0616d',
      label: `High similarity ${Math.round(sim * 100)}%${mergeName ? ` · ${mergeName}` : ''}`,
    };
  }
  if (sim >= SUGGEST_MERGE_SIMILARITY) {
    return {
      background: 'rgba(255,159,67,0.14)',
      color: '#ff9f43',
      label: `Suggest merge ${Math.round(sim * 100)}%${mergeName ? ` · ${mergeName}` : ''}`,
    };
  }
  return null;
}

interface SkillDraftsSectionProps {
  drafts: SkillDraft[];
  pendingCount: number;
  onClose: () => void;
  onApprove: (id: string) => void;
  onDiscard: (id: string) => void;
  approvingId: string | null;
  discardingId: string | null;
}

export const SkillDraftsSection: React.FC<SkillDraftsSectionProps> = ({
  drafts, pendingCount, onClose, onApprove, onDiscard, approvingId, discardingId,
}) => {
  const pending = drafts.filter(d => d.status === 'draft');
  return (
    <section
      style={{
        margin: '0 24px 16px',
        padding: '14px 16px',
        border: '1px solid var(--border-subtle, #2a2a31)',
        borderRadius: 8,
        background: 'var(--bg-secondary, #15151a)',
      }}
    >
      <header
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          marginBottom: 10,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
          <h3 style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing: 0.2 }}>
            Extracted Skill Drafts
          </h3>
          <span style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)' }}>
            {pendingCount} pending · {drafts.length} total
          </span>
        </div>
        <button
          className="sf-icon-btn"
          onClick={onClose}
          title="Hide drafts"
          style={{ transform: 'rotate(180deg)' }}
        >
          {CHEVRON_ICON}
        </button>
      </header>

      {pending.length === 0 ? (
        <div className="sf-empty-state" style={{ padding: '18px 8px', fontSize: 12 }}>
          No pending drafts. Run “Extract from Sessions” to generate new candidates.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {pending.map(d => (
            <SkillDraftCard
              key={d.id}
              draft={d}
              onApprove={onApprove}
              onDiscard={onDiscard}
              approving={approvingId === d.id}
              discarding={discardingId === d.id}
            />
          ))}
        </div>
      )}
    </section>
  );
};

interface SkillDraftCardProps {
  draft: SkillDraft;
  onApprove: (id: string) => void;
  onDiscard: (id: string) => void;
  approving: boolean;
  discarding: boolean;
}

const SkillDraftCard: React.FC<SkillDraftCardProps> = React.memo(
  ({ draft, onApprove, onDiscard, approving, discarding }) => {
    const [showHint, setShowHint] = useState(false);
    const [showRationale, setShowRationale] = useState(false);
    const triggers = (draft.triggers ?? '').split(',').map(t => t.trim()).filter(Boolean);

    const simBadge = draft.similarity != null
      ? similarityStyle(draft.similarity, draft.mergeCandidateName ?? draft.mergeCandidateId)
      : null;

    return (
      <div
        style={{
          display: 'flex',
          gap: 12,
          padding: '12px 14px',
          borderRadius: 6,
          border: '1px solid var(--border-subtle, #2a2a31)',
          background: 'var(--bg-primary, #0f0f10)',
        }}
      >
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span
              style={{
                fontFamily: 'var(--font-mono, monospace)',
                fontSize: 13, fontWeight: 600,
                color: 'var(--text-primary, #e7e7ea)',
              }}
            >
              {draft.name}
            </span>
            {simBadge && (
              <span
                data-testid="similarity-badge"
                style={{
                  fontSize: 10,
                  padding: '1px 7px',
                  borderRadius: 10,
                  background: simBadge.background,
                  color: simBadge.color,
                  fontFamily: 'var(--font-mono, monospace)',
                  fontWeight: 600,
                }}
              >
                {simBadge.label}
              </span>
            )}
            {triggers.slice(0, 3).map(t => (
              <span
                key={t}
                style={{
                  fontSize: 10,
                  padding: '1px 6px',
                  borderRadius: 3,
                  background: 'var(--bg-hover, #1d1d22)',
                  color: 'var(--fg-4, #8a8a93)',
                  fontFamily: 'var(--font-mono, monospace)',
                }}
              >
                {t}
              </span>
            ))}
          </div>

          {draft.description && (
            <div
              style={{
                marginTop: 4,
                fontSize: 12,
                lineHeight: 1.5,
                color: 'var(--fg-3, #a8a8b1)',
                display: '-webkit-box',
                WebkitLineClamp: 2,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
              }}
            >
              {draft.description}
            </div>
          )}

          {draft.requiredTools && (
            <div style={{ marginTop: 6, fontSize: 11, color: 'var(--fg-4, #8a8a93)' }}>
              Tools: <code style={{ fontFamily: 'var(--font-mono, monospace)' }}>{draft.requiredTools}</code>
            </div>
          )}

          {draft.promptHint && (
            <div style={{ marginTop: 6 }}>
              <button
                className="sf-mini-btn"
                onClick={() => setShowHint(v => !v)}
                style={{ fontSize: 11 }}
              >
                {showHint ? 'Hide' : 'Show'} prompt hint
              </button>
              {showHint && (
                <pre
                  className="sf-code-block"
                  style={{ marginTop: 6, fontSize: 11, maxHeight: 160, overflow: 'auto' }}
                >
                  {draft.promptHint}
                </pre>
              )}
            </div>
          )}

          {draft.extractionRationale && (
            <div style={{ marginTop: 6 }}>
              <button
                className="sf-mini-btn"
                onClick={() => setShowRationale(v => !v)}
                style={{ fontSize: 11 }}
              >
                {showRationale ? 'Hide' : 'Why extracted?'}
              </button>
              {showRationale && (
                <div
                  style={{
                    marginTop: 4, fontSize: 11, fontStyle: 'italic',
                    color: 'var(--fg-4, #8a8a93)', lineHeight: 1.5,
                  }}
                >
                  {draft.extractionRationale}
                </div>
              )}
            </div>
          )}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0 }}>
          <button
            className="btn-primary-sf"
            disabled={approving || discarding}
            onClick={() => onApprove(draft.id)}
            style={{ fontSize: 11, padding: '4px 10px' }}
          >
            {approving ? '…' : 'Approve'}
          </button>
          <button
            className="btn-ghost-sf"
            disabled={approving || discarding}
            onClick={() => onDiscard(draft.id)}
            style={{ fontSize: 11, padding: '4px 10px', color: 'var(--color-err, #f0616d)' }}
          >
            {discarding ? '…' : 'Discard'}
          </button>
        </div>
      </div>
    );
  },
);
SkillDraftCard.displayName = 'SkillDraftCard';
