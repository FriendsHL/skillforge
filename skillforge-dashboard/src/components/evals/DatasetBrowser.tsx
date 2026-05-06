import { useMemo, useState } from 'react';
import { Select } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getEvalDatasetScenarios,
  getBaseScenarios,
  type EvalDatasetScenario,
  type BaseScenario,
} from '../../api';
import ScenarioDetailDrawer from './ScenarioDetailDrawer';
import AnalyzeCaseModal from './AnalyzeCaseModal';
import AddBaseScenarioModal from './AddBaseScenarioModal';

interface DatasetBrowserProps {
  agents: Record<string, unknown>[];
  userId: number;
}

const STATUS_OPTIONS = [
  { value: '', label: 'All statuses' },
  { value: 'active', label: 'Active' },
  { value: 'draft', label: 'Draft' },
  { value: 'discarded', label: 'Discarded' },
];

type DatasetTab = 'base' | 'agent';

/**
 * EVAL-V2 Q2/Q3: lift a {@link BaseScenario} (no agentId, no status concept
 * — backed by classpath/home dir) into the {@link EvalDatasetScenario}
 * shape so the existing card / drawer / Analyze modal renderers can reuse
 * the same code without branching everywhere on dataset type. The
 * synthesized {@code agentId} is empty string, {@code status} is "active"
 * (so the card border picks the green accent), and {@code createdAt} is
 * blank (the drawer's fmtTime renders this gracefully as "—").
 */
function baseToDataset(s: BaseScenario): EvalDatasetScenario {
  return {
    id: s.id,
    agentId: '',
    version: s.version ?? 1,
    parentScenarioId: s.parentScenarioId ?? null,
    name: s.name,
    description: s.description ?? undefined,
    category: s.category ?? 'base',
    split: s.split ?? 'held_out',
    task: s.task,
    oracleType: s.oracleType ?? 'llm_judge',
    oracleExpected: s.oracleExpected ?? undefined,
    status: 'active',
    createdAt: '',
    // Surface source on the projection so filter/render don't need to
    // cross-index back into baseRaw (which breaks once the array is filtered).
    source: s.source,
    // EVAL-V2 M3b: pass through rich detail fields so the drawer can render
    // toolsHint / tags / setup files / loop & perf metadata, and the card
    // can show a description preview + a few tag chips.
    conversationTurns: s.conversationTurns,
    toolsHint: s.toolsHint,
    tags: s.tags,
    setupFiles: s.setupFiles,
    maxLoops: s.maxLoops,
    performanceThresholdMs: s.performanceThresholdMs,
  };
}

function DatasetBrowser({ agents, userId }: DatasetBrowserProps) {
  const [tab, setTab] = useState<DatasetTab>('base');
  const [agentId, setAgentId] = useState<string>(() => {
    const first = agents[0];
    return first ? String(first.id) : '';
  });
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('');
  const [sourceFilter, setSourceFilter] = useState<string>('');
  const [opened, setOpened] = useState<EvalDatasetScenario | null>(null);
  const [analyzing, setAnalyzing] = useState<EvalDatasetScenario | null>(null);
  const [adding, setAdding] = useState(false);
  const queryClient = useQueryClient();

  // EVAL-V2 Q2: Base tab fetches classpath + home-dir scenarios via the new
  // GET /eval/scenarios/base endpoint. Disabled when not on the Base tab so
  // we don't burn a request on every render of the Agent tab.
  const { data: baseRaw = [], isLoading: baseLoading, isError: baseError } = useQuery({
    queryKey: ['eval-base-scenarios'],
    queryFn: () => getBaseScenarios().then(r => r.data ?? []),
    enabled: tab === 'base',
  });

  const { data: agentScenarios = [], isLoading: agentLoading, isError: agentError } = useQuery({
    queryKey: ['eval-dataset-scenarios', agentId],
    queryFn: () => getEvalDatasetScenarios(agentId).then(r => r.data ?? []),
    enabled: tab === 'agent' && !!agentId,
  });

  // For rendering, we project both into EvalDatasetScenario shape; the
  // tab decides which set is "live".
  const scenarios: EvalDatasetScenario[] = useMemo(() => {
    if (tab === 'base') return baseRaw.map(baseToDataset);
    return agentScenarios;
  }, [tab, baseRaw, agentScenarios]);

  const isLoading = tab === 'base' ? baseLoading : agentLoading;
  const isError = tab === 'base' ? baseError : agentError;

  // Distinct categories — derive from data so we don't hardcode
  const categories = useMemo(() => {
    const set = new Set<string>();
    scenarios.forEach(s => { if (s.category) set.add(s.category); });
    return Array.from(set).sort();
  }, [scenarios]);

  // Distinct sources (only relevant on Base tab — classpath / home).
  const sources = useMemo(() => {
    if (tab !== 'base') return [] as string[];
    const set = new Set<string>();
    scenarios.forEach(s => { if (s.source) set.add(s.source); });
    return Array.from(set).sort();
  }, [tab, scenarios]);

  const filtered = useMemo(() => {
    const ql = q.trim().toLowerCase();
    return scenarios.filter(s => {
      if (ql && !`${s.name} ${s.task} ${s.category ?? ''}`.toLowerCase().includes(ql)) return false;
      // Status filter only meaningful on Agent tab (Base scenarios are always "active").
      if (tab === 'agent' && statusFilter && s.status !== statusFilter) return false;
      if (categoryFilter && s.category !== categoryFilter) return false;
      // Source filter only meaningful on Base tab — uses the projected
      // EvalDatasetScenario.source field (set by baseToDataset). Avoiding
      // baseRaw[idx] cross-indexing because the post-filter mapping breaks
      // that invariant.
      if (tab === 'base' && sourceFilter && s.source !== sourceFilter) return false;
      return true;
    });
  }, [scenarios, q, statusFilter, categoryFilter, tab, sourceFilter]);

  return (
    <div className="dataset-browser">
      <div className="dataset-toolbar">
        {/* Segmented Control for Base/Agent */}
        <div className="dataset-segmented-control">
          {(['base', 'agent'] as DatasetTab[]).map(t => (
            <button
              key={t}
              className={`dataset-segment-btn ${tab === t ? 'on' : ''}`}
              onClick={() => setTab(t)}
            >
              {t === 'base' ? 'Base' : 'Agent'}
              <span className="dataset-segment-count">
                {t === 'base' ? baseRaw.length : agentScenarios.length}
              </span>
            </button>
          ))}
        </div>

        {tab === 'agent' && (
          <Select
            value={agentId || undefined}
            onChange={(v) => setAgentId(v ?? '')}
            placeholder="Select agent…"
            style={{ minWidth: 220 }}
            options={agents.map((a) => ({
              value: String(a.id),
              label: String(a.name || `Agent #${a.id}`),
            }))}
          />
        )}
        <input
          className="agents-search"
          placeholder="search name / task / category…"
          value={q}
          onChange={e => setQ(e.target.value)}
        />
        {tab === 'agent' && (
          <Select
            value={statusFilter || ''}
            onChange={(v) => setStatusFilter(v ?? '')}
            options={STATUS_OPTIONS}
            style={{ minWidth: 140 }}
          />
        )}
        {tab === 'base' && sources.length > 0 && (
          <Select
            value={sourceFilter || ''}
            onChange={(v) => setSourceFilter(v ?? '')}
            options={[
              { value: '', label: 'All sources' },
              ...sources.map(src => ({
                value: src,
                label: src === 'classpath' ? 'System (classpath)' : src === 'home' ? 'User (home dir)' : src,
              })),
            ]}
            style={{ minWidth: 180 }}
          />
        )}
        {categories.length > 0 && (
          <Select
            value={categoryFilter || ''}
            onChange={(v) => setCategoryFilter(v ?? '')}
            options={[
              { value: '', label: 'All categories' },
              ...categories.map(c => ({ value: c, label: c })),
            ]}
            style={{ minWidth: 180 }}
          />
        )}
        <span style={{ marginLeft: 'auto', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>
          {filtered.length} of {scenarios.length} cases
        </span>
        {tab === 'base' && (
          <button
            type="button"
            className="btn-ghost-sf"
            onClick={() => setAdding(true)}
            title="新增 Base 评测场景"
            aria-label="新增 Base 评测场景"
          >
            <span aria-hidden="true" style={{ marginRight: 4 }}>+</span>
            新增
          </button>
        )}
      </div>

      {tab === 'agent' && !agentId ? (
        <div className="sf-empty-state">Select an agent to browse its dataset.</div>
      ) : isError ? (
        <div className="sf-empty-state">Failed to load dataset.</div>
      ) : isLoading ? (
        <div className="sf-empty-state">Loading…</div>
      ) : filtered.length === 0 ? (
        <div className="sf-empty-state">No scenarios match the current filters.</div>
      ) : (
        <div className="dataset-grid">
          {filtered.map(s => {
            // EVAL-V2 M3b: cap at 3 tag chips on the card so a tag-heavy
            // scenario (some seeds carry 6-8 tags) doesn't blow up vertical
            // rhythm; the drawer renders the full list. Bound to a
            // non-empty slice so we don't render an empty `<div>` strip.
            const tagPreview = (s.tags ?? []).slice(0, 3);
            const hasMoreTags = (s.tags?.length ?? 0) > tagPreview.length;
            return (
              <button
                key={s.id}
                className={`dataset-card s-${s.status}`}
                onClick={() => setOpened(s)}
              >
                <div className="dataset-card-h">
                  <h4>{s.name}</h4>
                  {tab === 'agent' ? (
                    <span className={`sess-status s-${s.status === 'active' ? 'idle' : s.status === 'draft' ? 'waiting' : 'error'}`}>
                      {s.status}
                    </span>
                  ) : s.source ? (
                    <span className="kv-chip-sf" title={s.source}>
                      {s.source === 'classpath' ? '系统内置' : '用户新增'}
                    </span>
                  ) : null}
                </div>
                <div className="dataset-card-meta">
                  {s.category && <span className="kv-chip-sf">{s.category}</span>}
                  {s.split && <span className="kv-chip-sf">{s.split}</span>}
                  {tab === 'agent' && <span className="kv-chip-sf">v{s.version ?? 1}</span>}
                  <span className="kv-chip-sf">{s.oracleType}</span>
                  {/* EVAL-V2 M3b: tag preview chips (max 3) inline with the
                      meta row so card height stays consistent whether tags
                      exist or not — no separate row that would shift layout. */}
                  {tagPreview.map(t => (
                    <span
                      key={`tag-${t}`}
                      className="kv-chip-sf"
                      style={{ opacity: 0.75 }}
                      title={t}
                    >
                      #{t}
                    </span>
                  ))}
                  {hasMoreTags && (
                    <span
                      className="kv-chip-sf"
                      style={{ opacity: 0.55 }}
                      // EVAL-V2 M3b r2 W1: surface the actually-hidden tag
                      // names on hover so the +N chip is informative, not
                      // just a count badge.
                      title={(s.tags ?? []).slice(tagPreview.length).join(', ')}
                    >
                      +{s.tags!.length - tagPreview.length}
                    </span>
                  )}
                </div>
                {/* EVAL-V2 M3b: description preview above the task line gives
                    cards more information density (痛点 4: "只能看到地址").
                    Falls back to nothing when description is blank — task
                    line carries the load on minimal scenarios so card
                    height stays roughly consistent. The CSS line-clamp on
                    .dataset-card-task already handles long content. */}
                {s.description && s.description.trim().length > 0 && (
                  <div className="dataset-card-desc">{s.description}</div>
                )}
                <div className="dataset-card-task">{s.task}</div>
              </button>
            );
          })}
        </div>
      )}

      {opened && (
        <ScenarioDetailDrawer
          scenario={opened}
          userId={userId}
          onClose={() => setOpened(null)}
          onAnalyze={(scn) => setAnalyzing(scn)}
          onSelectScenarioVersion={(scn) => setOpened(scn)}
        />
      )}

      {analyzing && (
        <AnalyzeCaseModal
          target={{ kind: 'scenario', scenario: analyzing }}
          agents={agents}
          userId={userId}
          onClose={() => setAnalyzing(null)}
        />
      )}

      {adding && (
        <AddBaseScenarioModal
          onClose={() => setAdding(false)}
          onAdded={() => {
            // EVAL-V2 Q2: invalidate the base scenarios query so the new
            // card shows up after save without a manual page refresh.
            queryClient.invalidateQueries({ queryKey: ['eval-base-scenarios'] });
          }}
        />
      )}
    </div>
  );
}

export default DatasetBrowser;
