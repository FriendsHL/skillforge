import React, { useEffect, useMemo, useState, useCallback } from 'react';
import { Modal, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkillDrafts, reviewSkillDraft, mergeDraftIntoSkill,
  type SkillDraft,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import { extractNameConflict, openNameConflictModal } from '../components/skills/draftApproveHelpers';
import '../components/agents/agents.css';
import '../components/skills/skills.css';

/** P1-C-8 dedup: above this similarity score the BE flags the candidate as a near-duplicate. */
const HIGH_SIMILARITY_THRESHOLD = 0.85;
const SUGGEST_MERGE_THRESHOLD = 0.60;

type DraftStatusFilter = 'all' | 'draft' | 'approved' | 'discarded';

/**
 * SKILL-DRAFTS-REDESIGN — dual-pane layout with list + detail preview.
 */
const SkillDraftsPage: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId: currentUserId } = useAuth();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<DraftStatusFilter>('draft');

  const { data: draftsData, isLoading } = useQuery({
    queryKey: ['skill-drafts', currentUserId],
    queryFn: () => getSkillDrafts(currentUserId).then(r => r.data),
    enabled: !!currentUserId,
  });

  const drafts: SkillDraft[] = useMemo(() => draftsData ?? [], [draftsData]);
  const pendingDrafts = useMemo(() => drafts.filter(d => d.status === 'draft'), [drafts]);
  const approvedDrafts = useMemo(() => drafts.filter(d => d.status === 'approved'), [drafts]);
  const discardedDrafts = useMemo(() => drafts.filter(d => d.status === 'discarded'), [drafts]);

  /** Drafts filtered by status */
  const statusFilteredDrafts = useMemo(() => {
    if (statusFilter === 'all') return drafts;
    return drafts.filter(d => d.status === statusFilter);
  }, [drafts, statusFilter]);

  /** Further filter by search query */
  const filteredDrafts = useMemo(() => {
    if (!searchQuery.trim()) return statusFilteredDrafts;
    const q = searchQuery.toLowerCase();
    return statusFilteredDrafts.filter(d =>
      d.name.toLowerCase().includes(q) ||
      d.description?.toLowerCase().includes(q) ||
      d.triggers?.toLowerCase().includes(q)
    );
  }, [statusFilteredDrafts, searchQuery]);

  /** Auto-select first draft in current filter */
  useEffect(() => {
    if (!selectedId && filteredDrafts.length > 0) {
      setSelectedId(filteredDrafts[0].id);
    } else if (selectedId && !filteredDrafts.find(d => d.id === selectedId)) {
      // Selected draft no longer in filter, select first available
      setSelectedId(filteredDrafts[0]?.id ?? null);
    }
  }, [filteredDrafts, selectedId]);

  /** Keyboard navigation */
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement) return;

      const currentIndex = filteredDrafts.findIndex(d => d.id === selectedId);
      if (e.key === 'ArrowUp' && currentIndex > 0) {
        setSelectedId(filteredDrafts[currentIndex - 1].id);
        e.preventDefault();
      } else if (e.key === 'ArrowDown' && currentIndex < filteredDrafts.length - 1) {
        setSelectedId(filteredDrafts[currentIndex + 1].id);
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [filteredDrafts, selectedId]);

  const invalidateAfterDraftMutation = () => {
    queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
    queryClient.invalidateQueries({ queryKey: ['skills'] });
    queryClient.invalidateQueries({ queryKey: ['dashboard-skill-summary'] });
  };

  const approveDraftCore = async (id: string, opts?: { forceCreate?: boolean }) => {
    const draft = drafts.find((d) => d.id === id);
    try {
      await reviewSkillDraft(id, 'approve', currentUserId, opts);
      invalidateAfterDraftMutation();
      message.success('Skill approved');
      // Auto-select next draft
      const nextDraft = pendingDrafts.find(d => d.id !== id);
      setSelectedId(nextDraft?.id ?? null);
    } catch (err: unknown) {
      const conflict = draft ? extractNameConflict(err) : null;
      if (conflict && draft) {
        openNameConflictModal({
          draft,
          conflict,
          onMerge: async (targetSkillId) => {
            await mergeDraftIntoSkill(id, targetSkillId, currentUserId);
            invalidateAfterDraftMutation();
            message.success(`Merged into skill #${targetSkillId}`);
          },
          onRename: async (newName) => {
            await reviewSkillDraft(id, 'approve', currentUserId, { newName });
            invalidateAfterDraftMutation();
            message.success(`Approved as "${newName}"`);
          },
          onReject: async () => {
            await reviewSkillDraft(id, 'discard', currentUserId);
            invalidateAfterDraftMutation();
            message.success('Draft rejected');
          },
        });
        return;
      }
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Failed to approve draft');
    }
  };

  const approveMutation = useMutation({
    mutationFn: (vars: { id: string; forceCreate?: boolean }) =>
      approveDraftCore(vars.id, { forceCreate: vars.forceCreate }),
  });

  const discardMutation = useMutation({
    mutationFn: (id: string) => reviewSkillDraft(id, 'discard', currentUserId),
    onSuccess: () => {
      invalidateAfterDraftMutation();
      message.success('Draft discarded');
      const nextDraft = pendingDrafts.find(d => d.id !== discardMutation.variables);
      setSelectedId(nextDraft?.id ?? null);
    },
    onError: () => message.error('Failed to discard draft'),
  });

  const handleApproveDraft = useCallback((id: string) => {
    const draft = drafts.find(d => d.id === id);
    const similarity = draft?.similarity ?? 0;
    if (draft && similarity >= HIGH_SIMILARITY_THRESHOLD) {
      Modal.confirm({
        title: 'Possible duplicate skill',
        content: (
          <div>
            <p>
              Skill <strong>{draft.name}</strong> looks similar to existing skill{' '}
              <strong>{draft.mergeCandidateName ?? draft.mergeCandidateId ?? 'unknown'}</strong>{' '}
              (similarity {Math.round(similarity * 100)}%).
            </p>
            <p>Create anyway?</p>
          </div>
        ),
        okText: 'Create anyway',
        cancelText: 'Cancel',
        okButtonProps: { danger: true },
        onOk: () => approveMutation.mutate({ id, forceCreate: true }),
      });
      return;
    }
    approveMutation.mutate({ id });
  }, [drafts, approveMutation]);

  const handleDiscardDraft = useCallback((id: string) => {
    discardMutation.mutate(id);
  }, [discardMutation]);

  /** Approve all low-risk drafts (similarity < 60%) */
  const handleApproveAllSafe = useCallback(() => {
    const safeDrafts = pendingDrafts.filter(d => (d.similarity ?? 0) < SUGGEST_MERGE_THRESHOLD);
    if (safeDrafts.length === 0) {
      message.info('No safe drafts to approve');
      return;
    }
    Modal.confirm({
      title: `Approve ${safeDrafts.length} safe drafts?`,
      content: `All drafts with similarity < 60% will be approved automatically.`,
      okText: 'Approve all',
      cancelText: 'Cancel',
      onOk: async () => {
        for (const draft of safeDrafts) {
          try {
            await reviewSkillDraft(draft.id, 'approve', currentUserId);
          } catch {
            // Skip failed ones
          }
        }
        invalidateAfterDraftMutation();
        message.success(`Approved ${safeDrafts.length} drafts`);
      },
    });
  }, [pendingDrafts, currentUserId]);

  /** WS subscription */
  useEffect(() => {
    if (!currentUserId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${currentUserId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as { type?: string };
        if (msg.type === 'skill_draft_extracted') {
          queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
        }
      } catch { /* ignore non-JSON */ }
    };
    return () => { try { ws.close(); } catch { /* ignore */ } };
  }, [currentUserId, queryClient]);

  const selectedDraft = useMemo(
    () => drafts.find(d => d.id === selectedId),
    [drafts, selectedId]
  );

  return (
    <div className="drafts-page">
      {/* Header */}
      <header className="drafts-head">
        <div className="drafts-head-left">
          <h1 className="drafts-title">Skill Drafts</h1>
          <p className="drafts-sub">Review and approve candidates extracted from recent sessions</p>
          <div className="drafts-stats">
            <button
              className={`draft-stat pending ${statusFilter === 'draft' ? 'active' : ''}`}
              onClick={() => setStatusFilter('draft')}
            >
              <span className="dot" />
              {pendingDrafts.length} pending
            </button>
            <button
              className={`draft-stat approved ${statusFilter === 'approved' ? 'active' : ''}`}
              onClick={() => setStatusFilter('approved')}
            >
              <span className="dot" />
              {approvedDrafts.length} approved
            </button>
            <button
              className={`draft-stat discarded ${statusFilter === 'discarded' ? 'active' : ''}`}
              onClick={() => setStatusFilter('discarded')}
            >
              <span className="dot" />
              {discardedDrafts.length} discarded
            </button>
            <button
              className={`draft-stat ${statusFilter === 'all' ? 'active' : ''}`}
              onClick={() => setStatusFilter('all')}
            >
              {drafts.length} total
            </button>
          </div>
        </div>
        <div className="drafts-head-actions">
          {statusFilter === 'draft' && pendingDrafts.length > 0 && (
            <button className="btn-ghost-sf" onClick={handleApproveAllSafe}>
              Approve all safe
            </button>
          )}
        </div>
      </header>

      {/* Dual pane */}
      <div className="drafts-dual">
        {/* List pane */}
        <aside className="drafts-list-pane">
          <div className="drafts-search">
            <input
              className="drafts-search-input"
              placeholder="Search drafts..."
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
            />
          </div>

          {isLoading ? (
            <div className="drafts-empty">Loading drafts...</div>
          ) : filteredDrafts.length === 0 ? (
            <div className="drafts-empty">
              {searchQuery ? 'No matching drafts' : `No ${statusFilter === 'all' ? '' : statusFilter} drafts`}
            </div>
          ) : (
            <div className="drafts-list-body">
              {filteredDrafts.map(draft => (
                <DraftListItem
                  key={draft.id}
                  draft={draft}
                  isActive={draft.id === selectedId}
                  onClick={() => setSelectedId(draft.id)}
                />
              ))}
            </div>
          )}

          <div className="drafts-list-footer">
            {filteredDrafts.length} {statusFilter === 'all' ? 'drafts' : statusFilter}
          </div>
        </aside>

        {/* Detail pane */}
        <main className="drafts-detail-pane">
          {selectedDraft ? (
            <DraftDetailPanel
              draft={selectedDraft}
              onApprove={handleApproveDraft}
              onDiscard={handleDiscardDraft}
              approving={approveMutation.isPending && approveMutation.variables?.id === selectedDraft.id}
              discarding={discardMutation.isPending && discardMutation.variables === selectedDraft.id}
            />
          ) : (
            <div className="drafts-detail-empty">
              <div className="empty-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <rect x="3" y="3" width="18" height="18" rx="2" />
                  <line x1="9" y1="9" x2="15" y2="9" />
                  <line x1="9" y1="12" x2="15" y2="12" />
                  <line x1="9" y1="15" x2="12" y2="15" />
                </svg>
              </div>
              <p>Select a draft to review</p>
            </div>
          )}
        </main>
      </div>
    </div>
  );
};

/** Status indicator style based on similarity */
function getDraftStatus(draft: SkillDraft): 'new' | 'warn' | 'err' | 'approved' | 'discarded' {
  if (draft.status === 'approved') return 'approved';
  if (draft.status === 'discarded') return 'discarded';
  const sim = draft.similarity ?? 0;
  if (sim >= HIGH_SIMILARITY_THRESHOLD) return 'err';
  if (sim >= SUGGEST_MERGE_THRESHOLD) return 'warn';
  return 'new';
}

/** Compact list item */
const DraftListItem: React.FC<{
  draft: SkillDraft;
  isActive: boolean;
  onClick: () => void;
}> = ({ draft, isActive, onClick }) => {
  const status = getDraftStatus(draft);
  const triggers = (draft.triggers ?? '').split(',').map(t => t.trim()).filter(Boolean);

  return (
    <div
      className={`draft-list-item ${isActive ? 'active' : ''}`}
      data-status={status}
      onClick={onClick}
    >
      <div className="item-main">
        <div className="item-head">
          <span className="item-name">{draft.name}</span>
          <StatusBadge status={status} similarity={draft.similarity} />
        </div>
        <div className="item-meta">
          {status !== 'new' && status !== 'approved' && status !== 'discarded' && draft.mergeCandidateName && (
            <span className="sim">→ {draft.mergeCandidateName}</span>
          )}
          {status === 'approved' && draft.reviewedAt && (
            <span className="approved-time">Approved {new Date(draft.reviewedAt).toLocaleDateString()}</span>
          )}
          {status === 'discarded' && draft.reviewedAt && (
            <span className="discarded-time">Discarded {new Date(draft.reviewedAt).toLocaleDateString()}</span>
          )}
          {triggers.length > 0 && <span>{triggers.length} triggers</span>}
        </div>
        {triggers.length > 0 && (
          <div className="item-triggers">
            {triggers.slice(0, 4).map(t => (
              <span key={t} className="trigger-chip">{t}</span>
            ))}
          </div>
        )}
        {draft.description && (
          <div className="item-desc">{draft.description}</div>
        )}
      </div>
    </div>
  );
};

/** Status badge */
const StatusBadge: React.FC<{
  status: 'new' | 'warn' | 'err' | 'approved' | 'discarded';
  similarity?: number;
}> = ({ status, similarity }) => {
  const label = useMemo(() => {
    if (status === 'approved') return 'Approved';
    if (status === 'discarded') return 'Discarded';
    if (status === 'err') return `High ${Math.round((similarity ?? 0) * 100)}%`;
    if (status === 'warn') return `Merge ${Math.round((similarity ?? 0) * 100)}%`;
    return 'New';
  }, [status, similarity]);

  return <span className={`item-badge badge-${status}`}>{label}</span>;
};

/** Detail panel */
const DraftDetailPanel: React.FC<{
  draft: SkillDraft;
  onApprove: (id: string) => void;
  onDiscard: (id: string) => void;
  approving: boolean;
  discarding: boolean;
}> = ({ draft, onApprove, onDiscard, approving, discarding }) => {
  const [showPromptHint, setShowPromptHint] = useState(false);
  const [showRationale, setShowRationale] = useState(false);
  const triggers = (draft.triggers ?? '').split(',').map(t => t.trim()).filter(Boolean);
  const tools = (draft.requiredTools ?? '').split(',').map(t => t.trim()).filter(Boolean);
  const status = getDraftStatus(draft);
  const isProcessed = draft.status === 'approved' || draft.status === 'discarded';

  return (
    <div className="draft-detail-content">
      {/* Header */}
      <div className="detail-head">
        <h2 className="detail-title">
          {draft.name}
          <StatusBadge status={status} similarity={draft.similarity} />
        </h2>
        {draft.description && (
          <p className="detail-desc">{draft.description}</p>
        )}
      </div>

      {/* Processed info */}
      {isProcessed && draft.reviewedAt && (
        <div className="detail-section">
          <div className="processed-info">
            {draft.status === 'approved' ? (
              <div className="processed-approved">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                Approved on {new Date(draft.reviewedAt).toLocaleString()}
                {draft.skillId && ` → Skill #${draft.skillId}`}
              </div>
            ) : (
              <div className="processed-discarded">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
                Discarded on {new Date(draft.reviewedAt).toLocaleString()}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Similarity warning */}
      {status !== 'new' && status !== 'approved' && status !== 'discarded' && (
        <div className="detail-section">
          <SimilarityCard draft={draft} />
        </div>
      )}

      {/* Triggers */}
      {triggers.length > 0 && (
        <div className="detail-section">
          <div className="section-label">Triggers</div>
          <div className="trigger-row">
            {triggers.map(t => (
              <span key={t} className="trigger-pill">{t}</span>
            ))}
          </div>
        </div>
      )}

      {/* Tools */}
      {tools.length > 0 && (
        <div className="detail-section">
          <div className="section-label">Required Tools</div>
          <div className="tools-block">
            {tools.map(t => (
              <span key={t} className="tool-tag">{t}</span>
            ))}
          </div>
        </div>
      )}

      {/* Prompt Hint */}
      {draft.promptHint && (
        <div className="detail-section">
          <div className="section-label">Prompt Hint</div>
          <button
            className="sf-mini-btn"
            onClick={() => setShowPromptHint(v => !v)}
          >
            {showPromptHint ? 'Hide' : 'Show'} prompt hint
          </button>
          {showPromptHint && (
            <div className="prompt-block">
              <div className="prompt-bar">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
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

      {/* Extraction Rationale */}
      {draft.extractionRationale && (
        <div className="detail-section">
          <div className="section-label">Why Extracted?</div>
          <button
            className="sf-mini-btn"
            onClick={() => setShowRationale(v => !v)}
          >
            {showRationale ? 'Hide' : 'Show'} rationale
          </button>
          {showRationale && (
            <div className="rationale-block">{draft.extractionRationale}</div>
          )}
        </div>
      )}

      {/* Source */}
      {draft.sourceSessionId && (
        <div className="detail-section">
          <div className="section-label">Source</div>
          <div className="source-link">
            Session #{draft.sourceSessionId} · {new Date(draft.createdAt).toLocaleDateString()}
          </div>
        </div>
      )}

      {/* Footer - only show for pending drafts */}
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
    </div>
  );
};

/** Similarity warning card */
const SimilarityCard: React.FC<{ draft: SkillDraft }> = ({ draft }) => {
  const sim = draft.similarity ?? 0;
  const level = sim >= HIGH_SIMILARITY_THRESHOLD ? 'high' : 'medium';

  return (
    <div className="similarity-card">
      <div className={`sim-indicator ${level}`}>
        {Math.round(sim * 100)}%
      </div>
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
};

export default SkillDraftsPage;