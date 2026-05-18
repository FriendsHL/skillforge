import React, { useEffect, useMemo, useState, useCallback } from 'react';
import { Modal, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkillDrafts, reviewSkillDraft, mergeDraftIntoSkill,
  type SkillDraft,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import { extractNameConflict, openNameConflictModal } from '../components/skills/draftApproveHelpers';
import { SkillDraftDetailDrawer } from '../components/skillDrafts/SkillDraftDetailDrawer';
import '../components/agents/agents.css';
import '../components/skills/skills.css';

/** P1-C-8 dedup: above this similarity score the BE flags the candidate as a near-duplicate. */
const HIGH_SIMILARITY_THRESHOLD = 0.85;
const SUGGEST_MERGE_THRESHOLD = 0.60;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 (F6) — adds 'evaluated_passed' and
 * 'rejected' as first-class status filters alongside the legacy
 * draft/approved/discarded triple. BE Phase 1.1 writes these via V91
 * `t_skill_draft.status`.
 */
type DraftStatusFilter =
  | 'all'
  | 'draft'
  | 'approved'
  | 'discarded'
  | 'evaluated_passed'
  | 'rejected';

/**
 * SKILL-DRAFTS-REDESIGN — dual-pane layout with list + detail preview.
 */
const SkillDraftsPage: React.FC = () => {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
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
  const evaluatedDrafts = useMemo(
    () => drafts.filter(d => d.status === 'evaluated_passed'),
    [drafts],
  );
  const rejectedDrafts = useMemo(
    () => drafts.filter(d => d.status === 'rejected'),
    [drafts],
  );

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

  /**
   * SKILL-CREATOR-WITH-EVAL F6 — operator clicks "Iterate" on a rejected
   * draft. Deep-links to the skill-creator chat with a prefill payload
   * containing the reject reason so the next turn can rewrite SKILL.md and
   * re-trigger evaluation. The target chat surface owns the prefill parse
   * (`?prefill=` URL param) — out of scope for this phase, so the deep link
   * is laid down for the FE iterate-loop work item.
   */
  const handleIterateDraft = useCallback((id: string) => {
    const draft = drafts.find(d => d.id === id);
    const summary = draft?.evaluationResult?.llmSummary ?? 'See evaluation report';
    const prefill = `Iterate based on reject reason: ${summary}`;
    navigate(`/chat?prefill=${encodeURIComponent(prefill)}&draftId=${encodeURIComponent(id)}`);
  }, [drafts, navigate]);

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
              className={`draft-stat approved ${statusFilter === 'evaluated_passed' ? 'active' : ''}`}
              onClick={() => setStatusFilter('evaluated_passed')}
              data-testid="filter-evaluated-passed"
            >
              <span className="dot" />
              {evaluatedDrafts.length} evaluated
            </button>
            <button
              className={`draft-stat discarded ${statusFilter === 'rejected' ? 'active' : ''}`}
              onClick={() => setStatusFilter('rejected')}
              data-testid="filter-rejected"
            >
              <span className="dot" />
              {rejectedDrafts.length} rejected
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

        {/* Detail pane — SKILL-CREATOR-WITH-EVAL F5: now tab-organised
            (Overview / Evaluation Report / Source) via the extracted
            SkillDraftDetailDrawer component. */}
        <main className="drafts-detail-pane">
          {selectedDraft ? (
            <SkillDraftDetailDrawer
              draft={selectedDraft}
              onApprove={handleApproveDraft}
              onDiscard={handleDiscardDraft}
              onIterate={handleIterateDraft}
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

/** Status indicator style based on similarity / eval verdict. */
type DraftStatusKind =
  | 'new'
  | 'warn'
  | 'err'
  | 'approved'
  | 'discarded'
  | 'evaluated'
  | 'rejected';

function getDraftStatus(draft: SkillDraft): DraftStatusKind {
  if (draft.status === 'approved') return 'approved';
  if (draft.status === 'discarded') return 'discarded';
  if (draft.status === 'evaluated_passed') return 'evaluated';
  if (draft.status === 'rejected') return 'rejected';
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
          {status !== 'new' &&
            status !== 'approved' &&
            status !== 'discarded' &&
            status !== 'evaluated' &&
            status !== 'rejected' &&
            draft.mergeCandidateName && (
              <span className="sim">→ {draft.mergeCandidateName}</span>
            )}
          {status === 'approved' && draft.reviewedAt && (
            <span className="approved-time">Approved {new Date(draft.reviewedAt).toLocaleDateString()}</span>
          )}
          {status === 'discarded' && draft.reviewedAt && (
            <span className="discarded-time">Discarded {new Date(draft.reviewedAt).toLocaleDateString()}</span>
          )}
          {status === 'evaluated' && draft.evaluationResult && (
            <span className="approved-time">
              +{Math.round(draft.evaluationResult.delta.passRate * 100)}pp pass rate
            </span>
          )}
          {status === 'rejected' && draft.evaluationResult && (
            <span className="discarded-time">
              {Math.round(draft.evaluationResult.delta.passRate * 100)}pp pass rate
            </span>
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
  status: DraftStatusKind;
  similarity?: number;
}> = ({ status, similarity }) => {
  const label = useMemo(() => {
    if (status === 'approved') return 'Approved';
    if (status === 'discarded') return 'Discarded';
    if (status === 'evaluated') return 'Evaluated';
    if (status === 'rejected') return 'Rejected';
    if (status === 'err') return `High ${Math.round((similarity ?? 0) * 100)}%`;
    if (status === 'warn') return `Merge ${Math.round((similarity ?? 0) * 100)}%`;
    return 'New';
  }, [status, similarity]);

  return <span className={`item-badge badge-${status}`}>{label}</span>;
};

export default SkillDraftsPage;