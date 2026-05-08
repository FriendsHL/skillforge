import React, { useEffect, useMemo, useState } from 'react';
import { Button, Modal, Select, Tooltip, message, notification } from 'antd';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkills, uploadSkill, deleteSkill,
  toggleSkill, extractList,
  getSkillDrafts, triggerSkillExtraction, reviewSkillDraft,
  mergeDraftIntoSkill,
  getAgents,
  rescanSkills,
  getSkillEvalHistory,
  type SkillDraft,
  type RescanReport,
  type EvalHistoryEntry,
} from '../api';
import { extractNameConflict, openNameConflictModal } from '../components/skills/draftApproveHelpers';
import { useAuth } from '../contexts/AuthContext';
import '../components/agents/agents.css';
import '../components/skills/skills.css';
import type { SkillRow } from '../components/skills/types';
import { normalizeSkill } from '../components/skills/utils';
import { BOLT_ICON, PLUS_ICON } from '../components/skills/icons';
import { FilterItem } from '../components/skills/FilterItem';
import { SkillCard } from '../components/skills/SkillCard';
import { SkillTable } from '../components/skills/SkillTable';
import { SkillDrawer } from '../components/skills/SkillDrawer';
import { NewSkillModal } from '../components/skills/NewSkillModal';
import { SkillDraftsSection } from '../components/skills/SkillDraftPanel';

interface AgentRow {
  id: number;
  name: string;
}

/** P1-C-8 dedup: above this similarity score the BE flags the candidate as a near-duplicate. */
const HIGH_SIMILARITY_THRESHOLD = 0.85;

const SkillList: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId: currentUserId } = useAuth();
  // P1-C-7: page-level state replaces DEFAULT_SOURCE_AGENT_ID. Until the user
  // picks one, extract / A-B / evolution actions are disabled with a tooltip.
  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [filterSource, setFilterSource] = useState<string | null>(null);
  const [open, setOpen] = useState<SkillRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('readme');
  const [creating, setCreating] = useState(false);
  const [draftsOpen, setDraftsOpen] = useState(false);
  const [extracting, setExtracting] = useState(false);
  const [rescanning, setRescanning] = useState(false);

  const { data: rawSkills = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () => getSkills().then(res => extractList<Record<string, unknown>>(res)),
  });

  // Agent catalog for the source-agent selector. We don't yet have a shared
  // useAgents hook (CLAUDE.md frontend rule §"文件组织" — extract when reused).
  const { data: agentRows = [] } = useQuery<AgentRow[]>({
    queryKey: ['agents-min'],
    queryFn: () =>
      getAgents().then((res) => {
        const list = extractList<Record<string, unknown>>(res);
        return list
          .map((a): AgentRow | null => {
            if (a.id == null || a.name == null) return null;
            return { id: Number(a.id), name: String(a.name) };
          })
          .filter((a): a is AgentRow => a !== null);
      }),
    staleTime: 60_000,
  });

  const { data: draftsData } = useQuery({
    queryKey: ['skill-drafts', currentUserId],
    queryFn: () => getSkillDrafts(currentUserId).then(r => r.data),
    enabled: !!currentUserId,
  });
  const drafts: SkillDraft[] = draftsData ?? [];
  const pendingDrafts = useMemo(() => drafts.filter(d => d.status === 'draft'), [drafts]);

  /**
   * Invalidate the standard set of caches after any draft mutation. Pulled
   * out so the merge-UX path (which doesn't go through the mutation
   * onSuccess callback) stays consistent.
   */
  const invalidateAfterDraftMutation = () => {
    queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
    queryClient.invalidateQueries({ queryKey: ['skills'] });
    queryClient.invalidateQueries({ queryKey: ['dashboard-skill-summary'] });
  };

  /**
   * Approve flow has two error branches that need different UX:
   *   - 409 `name_conflict` → open merge UX modal (V2 §H)
   *   - anything else → red toast
   * So we hand-roll the catch instead of using the mutation's `onError`.
   */
  const approveDraftCore = async (id: string, opts?: { forceCreate?: boolean }) => {
    const draft = drafts.find((d) => d.id === id);
    try {
      await reviewSkillDraft(id, 'approve', currentUserId, opts);
      invalidateAfterDraftMutation();
      message.success('Skill approved');
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
    },
    onError: () => message.error('Failed to discard draft'),
  });

  /**
   * Approve handler: when the BE flags a near-duplicate (similarity ≥ 0.85),
   * confirm with the operator and only then send `forceCreate=true` (P1-C-8).
   * After force-create the BE may still 409 on exact-name match; that's
   * handled inside `approveDraftCore` (merge UX).
   */
  const handleApproveDraft = (id: string) => {
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
  };

  /**
   * P1-D rescan: trigger a synchronous filesystem reconciliation and surface
   * the report. We use Modal.info (not message.success) so the multi-line
   * summary stays on screen long enough for the operator to read each count
   * — auto-dismissing toasts hide too quickly for diagnostic data.
   */
  const handleRescan = async () => {
    setRescanning(true);
    try {
      const res = await rescanSkills();
      const report: RescanReport = res.data;
      Modal.info({
        title: 'Skills rescanned',
        content: (
          <div data-testid="rescan-report">
            <p style={{ marginTop: 0 }}>Filesystem reconciliation complete:</p>
            <ul style={{ paddingLeft: 18, marginBottom: 0 }}>
              <li><strong>{report.created}</strong> created</li>
              <li><strong>{report.updated}</strong> updated</li>
              <li><strong>{report.missing}</strong> missing</li>
              <li><strong>{report.invalid}</strong> invalid</li>
              <li><strong>{report.shadowed}</strong> shadowed</li>
              <li><strong>{report.disabledDuplicates}</strong> duplicates auto-disabled</li>
            </ul>
          </div>
        ),
        okText: 'Close',
      });
      queryClient.invalidateQueries({ queryKey: ['skills'] });
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Failed to rescan skills');
    } finally {
      setRescanning(false);
    }
  };

  const handleExtract = async () => {
    if (!selectedAgentId) {
      message.warning('Select a source agent first');
      return;
    }
    setExtracting(true);
    try {
      const res = await triggerSkillExtraction(selectedAgentId, currentUserId);
      if (res.data.status === 'already_has_drafts') {
        message.info(`${res.data.count ?? 0} pending draft(s) already waiting for review`);
      } else {
        message.success('Extraction started — check back in a moment');
      }
      setDraftsOpen(true);
    } catch {
      message.error('Failed to start skill extraction');
    } finally {
      setExtracting(false);
    }
  };

  // WS: auto-refresh drafts when backend finishes extraction +
  //     surface SKILL-EVOLVE-LOOP `skill_auto_upgraded` event (Phase 5
  //     self-improve cron). The same socket carries multiple event
  //     types — we discriminate on `msg.type` and only react to the
  //     two we care about. Cleanup must close the socket (frontend.md
  //     footgun #2: an unclosed WS keeps setState'ing after unmount).
  useEffect(() => {
    if (!currentUserId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${currentUserId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as {
          type?: string;
          skillId?: number;
          oldVersion?: string | number;
          newVersion?: string | number;
          baselineScore?: number;
          candidateScore?: number;
          skillName?: string;
        };
        if (msg.type === 'skill_draft_extracted') {
          queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
          setDraftsOpen(true);
          return;
        }
        if (msg.type === 'skill_auto_upgraded') {
          const baseline = typeof msg.baselineScore === 'number' ? Math.round(msg.baselineScore) : '—';
          const candidate = typeof msg.candidateScore === 'number' ? Math.round(msg.candidateScore) : '—';
          const versionPart =
            msg.oldVersion != null && msg.newVersion != null
              ? ` ${msg.oldVersion} → ${msg.newVersion}`
              : '';
          const skillLabel = msg.skillName ?? (msg.skillId != null ? `#${msg.skillId}` : 'unknown');
          notification.success({
            message: 'Skill auto-upgraded',
            description: `Skill ${skillLabel}${versionPart} promoted via A/B (baseline ${baseline} → candidate ${candidate}).`,
            duration: 6,
          });
          // Refresh both the list (latest score column) and per-skill
          // history / evolution panels.
          queryClient.invalidateQueries({ queryKey: ['skills'] });
          queryClient.invalidateQueries({ queryKey: ['skill-eval-history-list'] });
          if (msg.skillId != null) {
            queryClient.invalidateQueries({ queryKey: ['skill-eval-history', msg.skillId] });
            queryClient.invalidateQueries({ queryKey: ['skill-evolution-runs', msg.skillId] });
          }
          return;
        }
      } catch { /* ignore non-JSON */ }
    };
    return () => { try { ws.close(); } catch { /* ignore */ } };
  }, [currentUserId, queryClient]);

  const all = useMemo<SkillRow[]>(() => rawSkills.map(normalizeSkill), [rawSkills]);

  // SKILL-EVOLVE-LOOP Phase 6 — batch-fetch eval history for every skill row
  // so the table can render Latest Score + Trend without N round-trips on
  // open. We use one `useQueries` call instead of one query per `<tr>` so
  // tanstack does the parallelism (one HTTP per skill, dedup'd cache).
  //
  // Performance note: at ~100 skills this fires 100 GET /eval-history
  // requests on mount. Acceptable for V1 (system has fewer than that
  // today); when skill count grows we'll add a batch endpoint or
  // server-side aggregate cached in `t_skill`. Marked V2 in tech-design.
  const numericSkillIds = useMemo<number[]>(
    () =>
      all
        .map((s) => (typeof s.id === 'number' ? s.id : null))
        .filter((id): id is number => id !== null),
    [all],
  );

  const historyQueries = useQueries({
    queries: numericSkillIds.map((id) => ({
      queryKey: ['skill-eval-history-list', id, 10] as const,
      queryFn: () =>
        getSkillEvalHistory(id, currentUserId, 10).then((r) => r.data),
      enabled: !!currentUserId,
      staleTime: 60_000,
      // Don't retry hard — a missing eval-history is not an error path.
      retry: 1,
    })),
  });

  const skillHistories = useMemo<Map<number, EvalHistoryEntry[]>>(() => {
    const m = new Map<number, EvalHistoryEntry[]>();
    numericSkillIds.forEach((id, idx) => {
      const data = historyQueries[idx]?.data;
      if (Array.isArray(data) && data.length > 0) {
        m.set(id, data);
      }
    });
    return m;
  }, [historyQueries, numericSkillIds]);

  const rows = useMemo(() => {
    return all.filter(s => {
      if (q) {
        const ql = q.toLowerCase();
        const hay = `${s.name} ${s.description || ''} ${s.tags.join(' ')}`.toLowerCase();
        if (!hay.includes(ql)) return false;
      }
      if (filterStatus) {
        if (filterStatus === 'active' && !s.enabled) return false;
        if (filterStatus === 'disabled' && s.enabled) return false;
      }
      if (filterSource && s.source !== filterSource) return false;
      return true;
    });
  }, [all, q, filterStatus, filterSource]);

  const toggle = (key: 'status' | 'source', value: string) => {
    if (key === 'status') setFilterStatus(v => v === value ? null : value);
    else setFilterSource(v => v === value ? null : value);
  };

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteSkill(id, currentUserId),
    onSuccess: () => { message.success('Skill deleted'); queryClient.invalidateQueries({ queryKey: ['skills'] }); },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string }; status?: number } };
      // BE returns 403 when attempting to delete a system skill (P1-C-8 BE收口).
      if (e.response?.status === 403) {
        message.error(e.response?.data?.error || 'System skills cannot be deleted');
      } else {
        message.error(e.response?.data?.error || 'Failed to delete');
      }
    },
  });
  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
      toggleSkill(id, enabled, currentUserId),
    onSuccess: (_, v) => { message.success(v.enabled ? 'Enabled' : 'Disabled'); queryClient.invalidateQueries({ queryKey: ['skills'] }); },
    onError: () => message.error('Toggle failed'),
  });
  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadSkill(file, currentUserId),
    onSuccess: () => { message.success('Skill uploaded'); queryClient.invalidateQueries({ queryKey: ['skills'] }); setCreating(false); },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Upload failed');
    },
  });

  const openDetail = (s: SkillRow) => { setOpen(s); setDrawerTab('readme'); };

  /**
   * SKILL-DASHBOARD-POLISH-V2 §I — Version Tree "Open" handler. Looks up
   * the target skill in the already-loaded list (no extra fetch) and swaps
   * the drawer's currentSkill. Falls through silently when the id isn't
   * in `all` (e.g. cross-owner version that the operator can't see).
   */
  const openSkillById = (id: number) => {
    const target = all.find((s) => s.id === id);
    if (!target) {
      message.warning(`Skill #${id} not in your list (different owner?)`);
      return;
    }
    setOpen(target);
    setDrawerTab('version-tree');
  };

  const noAgentTooltip = 'Pick a source agent first';

  return (
    <div className="agents-view">
      {/* Filter sidebar */}
      <aside className="agents-filters">
        <div className="agents-filters-h">Search</div>
        <input className="agents-search" placeholder="name, tag, description…" value={q} onChange={e => setQ(e.target.value)} />

        <div className="agents-filters-h">Status</div>
        <FilterItem label="active" count={all.filter(s => s.enabled).length} active={filterStatus === 'active'} onClick={() => toggle('status', 'active')} />
        <FilterItem label="disabled" count={all.filter(s => !s.enabled).length} active={filterStatus === 'disabled'} onClick={() => toggle('status', 'disabled')} />

        <div className="agents-filters-h">Source</div>
        <FilterItem label="system" count={all.filter(s => s.source === 'system').length} active={filterSource === 'system'} onClick={() => toggle('source', 'system')} />
        <FilterItem label="custom" count={all.filter(s => s.source === 'custom').length} active={filterSource === 'custom'} onClick={() => toggle('source', 'custom')} />
      </aside>

      {/* Main */}
      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Skills</h1>
            <p className="agents-head-sub">{rows.length} of {all.length} · reusable building blocks for agents</p>
          </div>
          <div className="agents-head-actions">
            {/* P1-C-7: source-agent selector replaces hardcoded agentId=1.
                Disables extract / A-B / evolution until set. */}
            <Select<number>
              size="small"
              style={{ minWidth: 180 }}
              placeholder="Source agent…"
              value={selectedAgentId ?? undefined}
              onChange={(v) => setSelectedAgentId(v ?? null)}
              options={agentRows.map(a => ({ label: a.name, value: a.id }))}
              showSearch
              optionFilterProp="label"
              allowClear
              data-testid="source-agent-select"
            />
            <div className="view-seg">
              <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')}>Grid</button>
              <button className={view === 'table' ? 'on' : ''} onClick={() => setView('table')}>Table</button>
            </div>
            {pendingDrafts.length > 0 && (
              <button
                className="btn-ghost-sf"
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6,
                  borderStyle: 'dashed', color: 'var(--accent-primary, #6366f1)',
                }}
                onClick={() => setDraftsOpen(o => !o)}
                title="Review extracted skill drafts"
              >
                {pendingDrafts.length} pending draft{pendingDrafts.length > 1 ? 's' : ''}
              </button>
            )}
            <Tooltip title={!selectedAgentId ? noAgentTooltip : 'Extract new skill drafts from recent sessions'}>
              <button
                className="btn-ghost-sf"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
                onClick={handleExtract}
                disabled={extracting || !selectedAgentId}
                data-testid="extract-btn"
              >
                {BOLT_ICON} {extracting ? 'Extracting…' : 'Extract from Sessions'}
              </button>
            </Tooltip>
            <Tooltip title="Reconcile skills against the on-disk skills directory and report missing / shadowed / invalid entries">
              <Button
                size="small"
                onClick={handleRescan}
                loading={rescanning}
                data-testid="rescan-btn"
              >
                Rescan
              </Button>
            </Tooltip>
            <button className="btn-primary-sf" onClick={() => setCreating(true)}>{PLUS_ICON} New skill</button>
          </div>
        </header>

        {draftsOpen && (
          <SkillDraftsSection
            drafts={drafts}
            pendingCount={pendingDrafts.length}
            onClose={() => setDraftsOpen(false)}
            onApprove={handleApproveDraft}
            onDiscard={(id) => discardMutation.mutate(id)}
            approvingId={
              approveMutation.isPending ? approveMutation.variables?.id ?? null : null
            }
            discardingId={discardMutation.isPending ? discardMutation.variables ?? null : null}
          />
        )}

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No skills match your filters.</div>
          ) : view === 'grid' ? (
            <div className="skills-grid-sf">
              {/* SKILL-DASHBOARD-POLISH: Aggregate by name to avoid duplicates */}
              {(() => {
                const map = new Map<string, SkillRow[]>();
                rows.forEach(s => {
                  const key = s.name.trim();
                  if (!map.has(key)) map.set(key, []);
                  map.get(key)!.push(s);
                });
                
                return Array.from(map.entries()).map(([name, versions]) => {
                  const primary = versions.find(v => v.enabled) || versions[0];
                  const candidateCount = versions.filter(v => !v.enabled).length;
                  const totalVersions = versions.length;
                  const score = primary.latestEvalScore || 0;
                  const scoreColor = score >= 80 ? '#52c41a' : score >= 60 ? '#faad14' : '#ff4d4f';
                  
                  return (
                    <div 
                      key={name}
                      className="skill-card-aggregate"
                      onClick={() => openDetail(primary)}
                      style={{
                        // Use the project's standard surface variable for theme consistency
                        background: 'var(--bg-surface)', 
                        border: '1px solid var(--border-subtle, #e5e7eb)',
                        color: 'var(--fg-1)',
                        boxShadow: 'var(--shadow-sm, 0 1px 3px rgba(0,0,0,0.1))',
                        borderRadius: 16,
                        padding: 24,
                        cursor: 'pointer',
                        transition: 'all 0.3s ease',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 16,
                        position: 'relative',
                        overflow: 'hidden'
                      }}
                      onMouseEnter={(e) => {
                        e.currentTarget.style.transform = 'translateY(-4px)';
                        e.currentTarget.style.borderColor = 'var(--accent-primary, #6366f1)';
                        e.currentTarget.style.boxShadow = '0 12px 32px rgba(99, 102, 241, 0.15)';
                      }}
                      onMouseLeave={(e) => {
                        e.currentTarget.style.transform = 'none';
                        e.currentTarget.style.borderColor = 'var(--border-subtle, #e5e7eb)';
                        e.currentTarget.style.boxShadow = 'var(--shadow-sm, 0 1px 3px rgba(0,0,0,0.1))';
                      }}
                    >
                      {/* Header: Name & Status */}
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                          <h3 style={{ margin: 0, fontSize: 18, fontWeight: 700, color: 'var(--fg-1)', lineHeight: 1.3, wordBreak: 'break-word' }}>
                            {name}
                          </h3>
                          {primary.enabled ? (
                            <span style={{ flexShrink: 0, fontSize: 10, padding: '2px 8px', borderRadius: 12, background: 'rgba(82, 196, 26, 0.15)', color: '#52c41a', fontWeight: 600, whiteSpace: 'nowrap' }}>
                              LIVE
                            </span>
                          ) : (
                            <span style={{ flexShrink: 0, fontSize: 10, padding: '2px 8px', borderRadius: 12, background: 'rgba(138, 138, 147, 0.15)', color: '#8a8a93', whiteSpace: 'nowrap' }}>
                              DISABLED
                            </span>
                          )}
                        </div>
                        
                        {/* Tool Badges Row */}
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                          {(() => {
                            const textContent = (primary.skillMd || primary.description || '').toLowerCase();
                            const tools = [];
                            
                            // 1. Check for explicit tool schema
                            if (primary.toolSchema && typeof primary.toolSchema === 'object') {
                              tools.push('Custom Tool');
                            }
                            
                            // 2. Keyword detection in description or markdown
                            if (textContent.includes('bash') || textContent.includes('shell') || textContent.includes('command')) tools.push('Bash');
                            if (textContent.includes('python') || textContent.includes('script')) tools.push('Python');
                            if (textContent.includes('web') || textContent.includes('browser') || textContent.includes('search')) tools.push('Web');
                            if (textContent.includes('file') || textContent.includes('read') || textContent.includes('write')) tools.push('File Ops');
                            
                            // Fallback if no specific tool found but it's a complex skill
                            if (tools.length === 0 && primary.toolSchema) tools.push('Advanced');

                            return tools.slice(0, 3).map((t, i) => (
                              <span key={i} style={{ 
                                fontSize: 10, padding: '2px 8px', borderRadius: 4, 
                                background: 'var(--bg-hover)', 
                                color: 'var(--fg-3)', border: '1px solid var(--border-subtle)',
                                fontWeight: 500
                              }}>
                                {t}
                              </span>
                            ));
                          })()}
                        </div>
                      </div>

                      {/* Description */}
                      <p style={{ margin: 0, fontSize: 13, color: 'var(--fg-2)', lineHeight: 1.6, flex: 1, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                        {primary.description || 'No description provided.'}
                      </p>

                      {/* Footer: Version Info & Status */}
                      <div style={{ 
                        display: 'flex', 
                        justifyContent: 'space-between', 
                        alignItems: 'center', 
                        paddingTop: 16,
                        borderTop: '1px solid var(--border-subtle, #e5e7eb)',
                        fontSize: 12, 
                        color: 'var(--fg-3, #8a8a93)' 
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ 
                            width: 6, 
                            height: 6, 
                            borderRadius: '50%', 
                            background: totalVersions > 1 ? 'var(--accent-primary, #6366f1)' : 'var(--fg-4, #d1d5db)' 
                          }}></span>
                          <span>{totalVersions} version{totalVersions > 1 ? 's' : ''}</span>
                        </div>
                        {candidateCount > 0 && (
                          <span style={{ 
                            color: 'var(--accent-primary, #6366f1)', 
                            fontWeight: 600,
                            background: 'rgba(99, 102, 241, 0.1)',
                            padding: '2px 8px',
                            borderRadius: 12
                          }}>
                            {candidateCount} update{candidateCount > 1 ? 's' : ''} ready
                          </span>
                        )}
                      </div>
                    </div>
                  );
                });
              })()}
            </div>
          ) : (
            <SkillTable rows={rows} onOpenDetail={openDetail} histories={skillHistories} />
          )}
        </div>
      </section>

      {/* Detail drawer */}
      {open && (
        <SkillDrawer
          skill={open}
          tab={drawerTab}
          setTab={setDrawerTab}
          onClose={() => setOpen(null)}
          onToggle={(id, en) => toggleMutation.mutate({ id: id as number, enabled: en })}
          onDelete={(id) => { deleteMutation.mutate(id as number); setOpen(null); }}
          currentUserId={currentUserId}
          sourceAgentId={selectedAgentId}
          onOpenSkill={openSkillById}
        />
      )}

      {/* New Skill modal */}
      {creating && (
        <NewSkillModal
          onClose={() => setCreating(false)}
          onUpload={(file) => uploadMutation.mutate(file)}
          uploading={uploadMutation.isPending}
        />
      )}
    </div>
  );
};

export default SkillList;
