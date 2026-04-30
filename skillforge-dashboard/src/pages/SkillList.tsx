import React, { useEffect, useMemo, useState } from 'react';
import { Button, Modal, Select, Tooltip, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkills, uploadSkill, deleteSkill,
  toggleSkill, extractList,
  getSkillDrafts, triggerSkillExtraction, reviewSkillDraft,
  getAgents,
  rescanSkills,
  type SkillDraft,
  type RescanReport,
} from '../api';
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

  const approveMutation = useMutation({
    mutationFn: (vars: { id: string; forceCreate?: boolean }) =>
      reviewSkillDraft(vars.id, 'approve', currentUserId, { forceCreate: vars.forceCreate }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
      queryClient.invalidateQueries({ queryKey: ['skills'] });
      message.success('Skill approved');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Failed to approve draft');
    },
  });

  const discardMutation = useMutation({
    mutationFn: (id: string) => reviewSkillDraft(id, 'discard', currentUserId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
      message.success('Draft discarded');
    },
    onError: () => message.error('Failed to discard draft'),
  });

  /**
   * Approve handler: when the BE flags a near-duplicate (similarity ≥ 0.85),
   * confirm with the operator and only then send `forceCreate=true` (P1-C-8).
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

  // WS: auto-refresh drafts when backend finishes extraction
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
          setDraftsOpen(true);
        }
      } catch { /* ignore non-JSON */ }
    };
    return () => { try { ws.close(); } catch { /* ignore */ } };
  }, [currentUserId, queryClient]);

  const all = useMemo<SkillRow[]>(() => rawSkills.map(normalizeSkill), [rawSkills]);

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
              {rows.map(s => (
                <SkillCard key={s.id} skill={s} onClick={() => openDetail(s)} />
              ))}
            </div>
          ) : (
            <SkillTable rows={rows} onOpenDetail={openDetail} />
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
