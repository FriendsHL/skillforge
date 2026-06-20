import React, { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, Modal, Select, Table, Tooltip, message, notification } from 'antd';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkills, uploadSkill, deleteSkill,
  toggleSkill, extractList,
  getSkillDrafts, triggerSkillExtraction,
  getAgents,
  rescanSkills,
  getSkillEvalHistory,
  getCuratorCandidates, applyCurator, restoreSkill,
  type SkillDraft,
  type RescanReport,
  type EvalHistoryEntry,
  type SkillCuratorCandidate,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import { useTaskTracker, newTaskId } from '../contexts/TaskTrackerContext';
import SkillDraftsPage from './SkillDrafts';
import '../components/agents/agents.css';
import '../components/skills/skills.css';
import type { SkillRow } from '../components/skills/types';
import { normalizeSkill, timeAgo } from '../components/skills/utils';
import { BOLT_ICON, PLUS_ICON } from '../components/skills/icons';
import { FilterItem } from '../components/skills/FilterItem';
import { SkillTable } from '../components/skills/SkillTable';
import { SkillDrawer } from '../components/skills/SkillDrawer';
import { NewSkillModal } from '../components/skills/NewSkillModal';

interface AgentRow {
  id: number;
  name: string;
}

/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — translate the URL
 * `?panel=` param to a SkillDrawer tab id. Unknown / missing values fall
 * back to the default 'readme' tab.
 *
 *   evolution → 'version-tree' (drawer shows the version evolution graph)
 *   abtest    → 'ab-test'
 *   canary    → 'ab-test' (V87 dormant — drawer has no canary tab today)
 */
function mapPanelParamToDrawerTab(panel: string | null): string {
  switch (panel) {
    case 'evolution': return 'version-tree';
    case 'abtest':    return 'ab-test';
    case 'canary':    return 'ab-test';
    default:          return 'readme';
  }
}

const SkillList: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId: currentUserId } = useAuth();
  const { addTask, updateTask } = useTaskTracker();
  // FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — `?panel=drafts`
  // opens the Drafts tab; `?skillId=N&panel=evolution|abtest|canary` deep
  // links into the SkillDrawer pre-opened on the chosen sub-tab.
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab: 'skills' | 'drafts' =
    searchParams.get('panel') === 'drafts' ? 'drafts' : 'skills';
  const [activeTab, setActiveTab] = useState<'skills' | 'drafts'>(initialTab);
  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [filterSource, setFilterSource] = useState<string | null>(null);
  const [open, setOpen] = useState<SkillRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('readme');
  const [creating, setCreating] = useState(false);
  const [extracting, setExtracting] = useState(false);
  const [rescanning, setRescanning] = useState(false);
  // SKILL-CURATOR human-in-loop
  const [curatorOpen, setCuratorOpen] = useState(false);
  const [curatorLoading, setCuratorLoading] = useState(false);
  const [curatorApplying, setCuratorApplying] = useState(false);
  const [candidates, setCandidates] = useState<SkillCuratorCandidate[]>([]);
  const [showArchived, setShowArchived] = useState(false);

  const { data: rawSkills = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () => getSkills().then(res => extractList<Record<string, unknown>>(res)),
  });

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

  // SKILL-CURATOR — open the modal and load the archival candidates (preview, no mutation).
  const openCurator = async () => {
    setCuratorOpen(true);
    setCuratorLoading(true);
    try {
      const res = await getCuratorCandidates();
      setCandidates(extractList<SkillCuratorCandidate>(res));
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Failed to load curator candidates');
      setCandidates([]);
    } finally {
      setCuratorLoading(false);
    }
  };

  // SKILL-CURATOR — confirm + apply real archival, then refresh the skills list.
  const handleApplyCurator = () => {
    if (candidates.length === 0) return;
    Modal.confirm({
      title: `归档 ${candidates.length} 个技能？`,
      content: '这些低使用率的旧技能将被归档（禁用）。归档后可在“显示已归档”里恢复。',
      okText: '归档这些',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setCuratorApplying(true);
        try {
          const res = await applyCurator();
          message.success(`已归档 ${res.data.archived} 个技能`);
          setCuratorOpen(false);
          setCandidates([]);
          queryClient.invalidateQueries({ queryKey: ['skills'] });
        } catch (err: unknown) {
          const e = err as { response?: { data?: { error?: string } } };
          message.error(e.response?.data?.error || 'Failed to archive skills');
        } finally {
          setCuratorApplying(false);
        }
      },
    });
  };

  const restoreMutation = useMutation({
    mutationFn: (id: number) => restoreSkill(id, currentUserId),
    onSuccess: () => {
      message.success('技能已恢复');
      queryClient.invalidateQueries({ queryKey: ['skills'] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Failed to restore');
    },
  });

  const handleExtract = async () => {
    if (!selectedAgentId) {
      message.warning('Select a source agent first');
      return;
    }
    setExtracting(true);
    // Pre-register a running task keyed by agentId so the panel survives
    // the setActiveTab('drafts') below; the WS event handler in Layout
    // resolves it by `relatedId === String(agentId)`.
    const taskId = newTaskId();
    const agentName =
      agentRows.find((a) => a.id === selectedAgentId)?.name ??
      `agent ${selectedAgentId}`;
    addTask({
      id: taskId,
      type: 'skill-extract',
      label: `Skill 抽取 (${agentName})`,
      state: 'running',
      detail: '正在分析最近会话…',
      relatedId: String(selectedAgentId),
    });
    try {
      const res = await triggerSkillExtraction(selectedAgentId, currentUserId);
      if (res.data.status === 'already_has_drafts') {
        // No async work — resolve the task immediately so the panel doesn't
        // sit running forever waiting for a WS event that won't come.
        updateTask(taskId, {
          state: 'info',
          detail: `${res.data.count ?? 0} 条 draft 已在 review 队列`,
        });
      }
      setActiveTab('drafts');
    } catch {
      updateTask(taskId, { state: 'failed', detail: '请求失败' });
    } finally {
      setExtracting(false);
    }
  };

  // WS: auto-refresh drafts when backend finishes extraction +
  //     surface SKILL-EVOLVE-LOOP `skill_auto_upgraded` event
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
      // SKILL-CURATOR — hide archived skills unless the toggle is on.
      if (!showArchived && s.archived) return false;
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
  }, [all, q, filterStatus, filterSource, showArchived]);

  const archivedCount = useMemo(() => all.filter(s => s.archived).length, [all]);

  const toggle = (key: 'status' | 'source', value: string) => {
    if (key === 'status') setFilterStatus(v => v === value ? null : value);
    else setFilterSource(v => v === value ? null : value);
  };

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteSkill(id, currentUserId),
    onSuccess: () => { message.success('Skill deleted'); queryClient.invalidateQueries({ queryKey: ['skills'] }); },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string }; status?: number } };
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

  // FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — consume
  // `?skillId=N&panel=evolution|abtest|canary` deep links. The skillId
  // resolves from the loaded `all` list; the panel param maps to the
  // matching drawer tab. We drop the URL params on success so the auto-
  // open doesn't fire again on every render.
  //
  // `canary` maps to `ab-test` (drawer has no dedicated canary tab; V87
  // dormant) so we don't 404 a legit-looking deep link.
  useEffect(() => {
    const skillIdParam = searchParams.get('skillId');
    const panelParam = searchParams.get('panel');
    if (!skillIdParam) return;
    if (all.length === 0) return; // wait for the list to load
    const target = all.find((s) => String(s.id) === skillIdParam);
    if (target) {
      setOpen(target);
      const tab = mapPanelParamToDrawerTab(panelParam);
      setDrawerTab(tab);
    } else {
      message.warning(`Skill #${skillIdParam} not in your list (different owner?)`);
    }
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('skillId');
        next.delete('panel');
        return next;
      },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams.get('skillId'), searchParams.get('panel'), all]);

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

  if (activeTab === 'drafts') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 0, padding: '0 0 0 16px', borderBottom: '1px solid var(--border, #e5e7eb)', flexShrink: 0 }}>
          <button
            onClick={() => setActiveTab('skills')}
            style={{
              padding: '10px 16px',
              background: 'none',
              border: 'none',
              borderBottom: '2px solid transparent',
              color: 'var(--fg-3, #8a8a93)',
              cursor: 'pointer',
              fontSize: 13,
              fontWeight: 500,
            }}
          >
            Skills
          </button>
          <button
            style={{
              padding: '10px 16px',
              background: 'none',
              border: 'none',
              borderBottom: '2px solid var(--accent-primary, #6366f1)',
              color: 'var(--fg-1, #111827)',
              cursor: 'default',
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            Drafts
            {pendingDrafts.length > 0 && (
              <span style={{
                marginLeft: 6,
                background: 'var(--color-warning, #f59e0b)',
                color: '#fff',
                borderRadius: 10,
                padding: '1px 7px',
                fontSize: 11,
                fontWeight: 600,
              }}>
                {pendingDrafts.length}
              </span>
            )}
          </button>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <SkillDraftsPage />
        </div>
      </div>
    );
  }

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
        <div style={{ display: 'flex', alignItems: 'center', gap: 0, borderBottom: '1px solid var(--border, #e5e7eb)' }}>
          <button
            style={{
              padding: '10px 16px',
              background: 'none',
              border: 'none',
              borderBottom: '2px solid var(--accent-primary, #6366f1)',
              color: 'var(--fg-1, #111827)',
              cursor: 'default',
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            Skills
          </button>
          <button
            onClick={() => setActiveTab('drafts')}
            style={{
              padding: '10px 16px',
              background: 'none',
              border: 'none',
              borderBottom: '2px solid transparent',
              color: 'var(--fg-3, #8a8a93)',
              cursor: 'pointer',
              fontSize: 13,
              fontWeight: 500,
            }}
          >
            Drafts
            {pendingDrafts.length > 0 && (
              <span style={{
                marginLeft: 6,
                background: 'var(--accent-primary, #6366f1)',
                color: '#fff',
                borderRadius: 10,
                padding: '1px 7px',
                fontSize: 11,
                fontWeight: 600,
              }}>
                {pendingDrafts.length}
              </span>
            )}
          </button>
        </div>
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Skills</h1>
            <p className="agents-head-sub">{rows.length} of {all.length} · reusable building blocks for agents</p>
          </div>
          <div className="agents-head-actions">
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
            <span aria-hidden className="sf-toolbar-div" />
            {pendingDrafts.length > 0 && (
              <button
                className="btn-ghost-sf"
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6,
                  borderStyle: 'dashed', color: 'var(--accent-primary, #6366f1)',
                }}
                onClick={() => setActiveTab('drafts')}
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
            <span aria-hidden className="sf-toolbar-div" />
            <Tooltip title="Reconcile skills against the on-disk skills directory and report missing / shadowed / invalid entries">
              <button
                className="btn-ghost-sf"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
                onClick={handleRescan}
                disabled={rescanning}
                data-testid="rescan-btn"
              >
                {rescanning ? 'Rescanning…' : 'Rescan'}
              </button>
            </Tooltip>
            <Tooltip title="预览并归档低使用率的旧技能（人工确认后才执行）">
              <button
                className="btn-ghost-sf"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
                onClick={openCurator}
                data-testid="curator-btn"
              >
                技能整理
              </button>
            </Tooltip>
            <Tooltip title={showArchived ? '隐藏已归档技能' : `显示已归档技能${archivedCount > 0 ? ` (${archivedCount})` : ''}`}>
              <button
                className="btn-ghost-sf"
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6,
                  ...(showArchived ? { borderColor: 'var(--accent-primary, #6366f1)', color: 'var(--accent-primary, #6366f1)' } : {}),
                }}
                onClick={() => setShowArchived(v => !v)}
                data-testid="toggle-archived-btn"
              >
                显示已归档{archivedCount > 0 ? ` (${archivedCount})` : ''}
              </button>
            </Tooltip>
            <span aria-hidden className="sf-toolbar-div" />
            <button className="btn-primary-sf" onClick={() => setCreating(true)}>{PLUS_ICON} New skill</button>
          </div>
        </header>

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No skills match your filters.</div>
          ) : view === 'grid' ? (
            <div className="skills-grid-sf">
              {(() => {
                const map = new Map<string, SkillRow[]>();
                rows.forEach(s => {
                  const key = s.name.trim();
                  if (!map.has(key)) map.set(key, []);
                  map.get(key)!.push(s);
                });
                
                return Array.from(map.entries()).map(([name, versions]) => {
                  const primary = versions.find(v => v.enabled) || versions[0];
                  const totalVersions = versions.length;
                  
                  return (
                    <div 
                      key={name}
                      className="skill-card-aggregate"
                      onClick={() => openDetail(primary)}
                      style={{
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
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                          <h3 style={{ margin: 0, fontSize: 18, fontWeight: 700, color: 'var(--fg-1)', lineHeight: 1.3, wordBreak: 'break-word' }}>
                            {name}
                          </h3>
                          {primary.archived ? (
                            <Tooltip title={primary.archiveReason ? `已归档：${primary.archiveReason}` : '已归档'}>
                              <span style={{ flexShrink: 0, fontSize: 10, padding: '2px 8px', borderRadius: 12, background: 'rgba(250, 173, 20, 0.15)', color: '#faad14', fontWeight: 600, whiteSpace: 'nowrap' }}>
                                已归档
                              </span>
                            </Tooltip>
                          ) : primary.enabled ? (
                            <span style={{ flexShrink: 0, fontSize: 10, padding: '2px 8px', borderRadius: 12, background: 'rgba(82, 196, 26, 0.15)', color: '#52c41a', fontWeight: 600, whiteSpace: 'nowrap' }}>
                              LIVE
                            </span>
                          ) : (
                            <span style={{ flexShrink: 0, fontSize: 10, padding: '2px 8px', borderRadius: 12, background: 'rgba(138, 138, 147, 0.15)', color: '#8a8a93', whiteSpace: 'nowrap' }}>
                              DISABLED
                            </span>
                          )}
                        </div>
                        
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                          {(() => {
                            const textContent = (primary.description || '').toLowerCase();
                            const tools = [];
                            
                            if (primary.toolSchema && typeof primary.toolSchema === 'object') {
                              tools.push('Custom Tool');
                            }
                            
                            if (textContent.includes('bash') || textContent.includes('shell') || textContent.includes('command')) tools.push('Bash');
                            if (textContent.includes('python') || textContent.includes('script')) tools.push('Python');
                            if (textContent.includes('web') || textContent.includes('browser') || textContent.includes('search')) tools.push('Web');
                            if (textContent.includes('file') || textContent.includes('read') || textContent.includes('write')) tools.push('File Ops');
                            
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

                      <p style={{ margin: 0, fontSize: 13, color: 'var(--fg-2)', lineHeight: 1.6, flex: 1, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                        {primary.description || 'No description provided.'}
                      </p>

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
                        {primary.archived && typeof primary.id === 'number' && (
                          <Button
                            size="small"
                            loading={restoreMutation.isPending && restoreMutation.variables === primary.id}
                            onClick={(e) => {
                              e.stopPropagation();
                              restoreMutation.mutate(primary.id as number);
                            }}
                            data-testid="restore-btn"
                          >
                            恢复
                          </Button>
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
          siblingVersions={
            open.system
              ? undefined
              : rows.filter(r => r.name === open.name)
          }
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

      {/* SKILL-CURATOR — preview + apply modal */}
      <Modal
        open={curatorOpen}
        title="技能整理 — 归档候选"
        onCancel={() => setCuratorOpen(false)}
        width={720}
        footer={[
          <Button key="cancel" onClick={() => setCuratorOpen(false)}>
            取消
          </Button>,
          <Button
            key="apply"
            type="primary"
            danger
            disabled={candidates.length === 0 || curatorLoading}
            loading={curatorApplying}
            onClick={handleApplyCurator}
            data-testid="curator-apply-btn"
          >
            归档这些 ({candidates.length})
          </Button>,
        ]}
      >
        <p style={{ marginTop: 0, color: 'var(--fg-3, #8a8a93)', fontSize: 13 }}>
          以下低使用率的旧技能符合归档条件。归档不会删除技能，可随时在“显示已归档”里恢复；
          恢复后整理器不会再次归档它。
        </p>
        <Table<SkillCuratorCandidate>
          size="small"
          rowKey="id"
          loading={curatorLoading}
          dataSource={candidates}
          pagination={false}
          scroll={{ y: 360 }}
          locale={{ emptyText: curatorLoading ? '加载中…' : '没有符合归档条件的技能' }}
          columns={[
            { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
            { title: '使用次数', dataIndex: 'usageCount', key: 'usageCount', width: 90 },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              key: 'createdAt',
              width: 120,
              render: (v: string | null) => (v ? timeAgo(v) : '—'),
            },
            {
              title: '描述',
              dataIndex: 'description',
              key: 'description',
              ellipsis: true,
              render: (v: string | null) => v || <span style={{ color: 'var(--fg-4, #d1d5db)' }}>—</span>,
            },
          ]}
        />
      </Modal>
    </div>
  );
};

export default SkillList;