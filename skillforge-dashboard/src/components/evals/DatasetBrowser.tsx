import { useMemo, useState } from 'react';
import { Select, Button, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getEvalDatasetScenarios,
  getBaseScenarios,
  type EvalDatasetScenario,
  type BaseScenario,
} from '../../api';
import {
  listDatasets,
  listVersions,
  getDatasetVersion,
  type EvalScenarioSourceType,
} from '../../api/evalDataset';
import type { AgentRole } from '../../api/behaviorRule';
import ScenarioDetailDrawer from './ScenarioDetailDrawer';
import AnalyzeCaseModal from './AnalyzeCaseModal';
import AddBaseScenarioModal from './AddBaseScenarioModal';
import TraceImportSuggester from './TraceImportSuggester';

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

/**
 * EVAL-DATASET-LAYER V1 §5.1 — source_type tab on the scenario browser.
 * 'all' is the existing behaviour; the other three filter by the new V109
 * `t_eval_scenario.source_type` enum.
 *
 * Note: existing scenarios that were retroactively backfilled to
 * `session_derived` (V109 migration) will show up under that tab; benchmark
 * + manual tabs are populated by V112 seed + AddBaseScenarioModal additions.
 */
type SourceTypeTab = 'all' | EvalScenarioSourceType;

const SOURCE_TYPE_TABS: Array<{ id: SourceTypeTab; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'benchmark', label: 'Benchmark' },
  { id: 'session_derived', label: 'Session-derived' },
  { id: 'manual', label: 'Manual' },
];

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (tech-design §5.3) — role filter tab on
 * the scenario browser. 'all' is the existing behaviour; the other five filter
 * by the new V117 {@code t_eval_scenario.applicable_agent_roles} JSONB tag list.
 *
 * <p>Sits parallel to the source_type segmented control (cross-cutting filter
 * applied on top of source_type / dataset selection).
 *
 * <p>Tab id matches the BE wire literal exactly (snake_case for
 * {@code main_assistant}) — the human-readable label is separate so the UI
 * reads "Main Assistant" while the BE filter sees {@code 'main_assistant'}.
 */
type RoleTab = 'all' | AgentRole;

const ROLE_TABS: Array<{ id: RoleTab; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'general', label: 'General' },
  { id: 'design', label: 'Design' },
  { id: 'code', label: 'Code' },
  { id: 'research', label: 'Research' },
  { id: 'main_assistant', label: 'Main Assistant' },
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
    // EVAL-DATASET-LAYER V1 (V109): propagate the new source_type / source_ref
    // / purpose fields so the source_type tab filter + card chips work for
    // base scenarios too. Null-tolerant — legacy on-disk specs without
    // these keys round-trip as undefined.
    sourceType: s.sourceType ?? null,
    sourceRef: s.sourceRef ?? null,
    purpose: s.purpose ?? null,
    // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117): propagate role tags so the
    // role tab filter applies to base scenarios too. Legacy on-disk specs
    // without this key round-trip as null and degrade to "no match" under
    // any non-'all' filter (deliberate — operator should explicitly opt-in
    // by adding the field to the spec).
    applicableAgentRoles: s.applicableAgentRoles ?? null,
  };
}

function DatasetBrowser({ agents, userId }: DatasetBrowserProps) {
  const [tab, setTab] = useState<DatasetTab>('base');
  const [sourceTypeTab, setSourceTypeTab] = useState<SourceTypeTab>('all');
  // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (tech-design §5.3) — role filter tab
  // sits parallel to sourceTypeTab; applied client-side via
  // s.applicableAgentRoles?.includes(roleTab) (optional-chain defends
  // against BE returning the field undefined on legacy / pre-V117 deploys —
  // missing data → no match instead of TypeError).
  const [roleTab, setRoleTab] = useState<RoleTab>('all');
  const [datasetIdFilter, setDatasetIdFilter] = useState<string>('');
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
  const [showTraceImporter, setShowTraceImporter] = useState(false);
  const queryClient = useQueryClient();

  // ── EVAL-DATASET-LAYER V1 §5.1: dataset selector ─────────────────────
  // Lists EvalDataset rows for the current user; when one is picked,
  // scenarios are filtered to "belongs to any version of this dataset".
  // V1 keeps the filter client-side via scenario id intersect once the
  // version detail is loaded — for the V1 lite UX, we just surface a
  // shortcut to /eval/datasets/<id> for deeper management.
  const datasetsQ = useQuery({
    queryKey: ['eval-datasets', userId],
    queryFn: () => listDatasets({ ownerId: userId }).then((r) => r.data ?? []),
  });

  // When a dataset is picked, look up its latest version → resolve the
  // scenario-id set so we can intersect against the currently-displayed
  // scenarios. Two-step (versions list → version detail) because list
  // endpoints don't surface scenario ids inline.
  const selectedDatasetVersionsQ = useQuery({
    queryKey: ['eval-dataset-versions', datasetIdFilter],
    queryFn: () => listVersions(datasetIdFilter).then((r) => r.data ?? []),
    enabled: !!datasetIdFilter,
  });
  const latestVersionId = selectedDatasetVersionsQ.data?.[0]?.id ?? null;
  const selectedVersionDetailQ = useQuery({
    queryKey: ['eval-dataset-version', latestVersionId],
    queryFn: () => getDatasetVersion(latestVersionId!).then((r) => r.data),
    enabled: !!latestVersionId,
  });
  const datasetScenarioIds = useMemo(() => {
    if (!datasetIdFilter) return null;
    // BE `/dataset-versions/{id}` envelope provides `scenarioIds: string[]`
    // alongside the full scenarios projection — use that directly so we
    // don't walk the scenarios array for ids we already have.
    const ids = selectedVersionDetailQ.data?.scenarioIds;
    if (!ids) return new Set<string>();
    return new Set(ids);
  }, [datasetIdFilter, selectedVersionDetailQ.data]);

  // EVAL-V2 Q2: Base tab fetches classpath + home-dir scenarios via
  // GET /eval/scenarios/base. EVAL-DATASET-LAYER V1 r2 fix: removed
  // `enabled: tab === 'base'` lazy gate — segment count needs both tabs'
  // data simultaneously for sourceTypeTab filter to reflect accurately.
  // Cost: 1 extra fetch on agent-tab entry (small classpath read, cached).
  const { data: baseRaw = [], isLoading: baseLoading, isError: baseError } = useQuery({
    queryKey: ['eval-base-scenarios'],
    queryFn: () => getBaseScenarios().then(r => r.data ?? []),
  });

  // EVAL-DATASET-LAYER V1 §5.1: pass sourceType to the BE for server-side
  // filter. The BE accepts the param; falsy / 'all' → no filter.
  // r2 fix: removed `enabled: tab === 'agent'` lazy gate — same reason as above.
  const beSourceType = sourceTypeTab === 'all' ? undefined : sourceTypeTab;
  const { data: agentScenarios = [], isLoading: agentLoading, isError: agentError } = useQuery({
    queryKey: ['eval-dataset-scenarios', agentId, beSourceType],
    queryFn: () =>
      getEvalDatasetScenarios(agentId, beSourceType ? { sourceType: beSourceType } : {}).then(r => r.data ?? []),
    enabled: !!agentId,
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
      if (ql && !`${s.name} ${s.task} ${s.category ?? ''} ${s.sourceRef ?? ''}`.toLowerCase().includes(ql)) return false;
      // Status filter only meaningful on Agent tab (Base scenarios are always "active").
      if (tab === 'agent' && statusFilter && s.status !== statusFilter) return false;
      if (categoryFilter && s.category !== categoryFilter) return false;
      // Source filter only meaningful on Base tab — uses the projected
      // EvalDatasetScenario.source field (set by baseToDataset). Avoiding
      // baseRaw[idx] cross-indexing because the post-filter mapping breaks
      // that invariant.
      if (tab === 'base' && sourceFilter && s.source !== sourceFilter) return false;
      // EVAL-DATASET-LAYER V1 §5.1: client-side source_type filter. The
      // agent tab pre-filters server-side via the `sourceType` query param
      // (so this is a no-op there for confirmed-good data), but the base
      // tab loads everything once and filters here.
      if (sourceTypeTab !== 'all' && s.sourceType !== sourceTypeTab) return false;
      // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 §5.3: client-side role filter.
      // Uses optional-chain `?.includes(...)` so undefined / null /
      // missing-field rows (BE pre-V117 deploys, or base scenarios with
      // legacy on-disk JSON spec) safely return false instead of throwing
      // "Cannot read properties of undefined". V1 keeps filter client-side
      // — small dataset scale (≤49 scenarios) makes server-side filtering
      // unnecessary; V2 can promote to BE query if dataset grows.
      if (roleTab !== 'all' && !s.applicableAgentRoles?.includes(roleTab)) return false;
      // EVAL-DATASET-LAYER V1 §5.1: dataset selector intersect. When a
      // dataset is picked, restrict to scenarios that belong to its latest
      // version. `null` = no dataset filter active.
      if (datasetScenarioIds && !datasetScenarioIds.has(s.id)) return false;
      return true;
    });
  }, [scenarios, q, statusFilter, categoryFilter, tab, sourceFilter, sourceTypeTab, roleTab, datasetScenarioIds]);

  return (
    <div className="dataset-browser">
      {/* EVAL-DATASET-LAYER V1 §5.1 — source_type tab strip.
          Sits above the existing base/agent control because source_type is a
          cross-cutting filter (applies to either tab). Hand-rolled segmented
          control instead of antd Tabs to match the surrounding visual idiom. */}
      <div className="dataset-toolbar" style={{ paddingBottom: 0, borderBottom: 'none' }}>
        <div className="dataset-segmented-control" aria-label="Filter by source type">
          {SOURCE_TYPE_TABS.map(({ id, label }) => (
            <button
              key={id}
              className={`dataset-segment-btn ${sourceTypeTab === id ? 'on' : ''}`}
              onClick={() => setSourceTypeTab(id)}
            >
              {label}
            </button>
          ))}
        </div>
        {/* FLYWHEEL-AB-AGENT-AWARE-DATASET V1 §5.3 — role filter segmented
            control, mirrors the source_type visual idiom (custom buttons
            instead of antd Segmented for consistency with the surrounding
            toolbar; 6 tabs: All / General / Design / Code / Research /
            Main Assistant). Cross-cutting filter — applies on top of both
            base/agent tab and source_type filter. */}
        <div className="dataset-segmented-control" aria-label="Filter by agent role">
          {ROLE_TABS.map(({ id, label }) => (
            <button
              key={id}
              className={`dataset-segment-btn ${roleTab === id ? 'on' : ''}`}
              onClick={() => setRoleTab(id)}
            >
              {label}
            </button>
          ))}
        </div>
        <Select
          value={datasetIdFilter || undefined}
          onChange={(v) => setDatasetIdFilter(v ?? '')}
          placeholder="(any dataset)"
          allowClear
          style={{ minWidth: 240 }}
          loading={datasetsQ.isLoading}
          options={(datasetsQ.data ?? []).map((d) => ({
            value: d.id,
            label: `${d.name}${d.latestVersionNumber ? ` @v${d.latestVersionNumber}` : ''}`,
          }))}
        />
        {datasetIdFilter && (
          <Tag closable onClose={() => setDatasetIdFilter('')}>
            dataset:{(datasetsQ.data ?? []).find((d) => d.id === datasetIdFilter)?.name ?? datasetIdFilter.slice(0, 8)}
          </Tag>
        )}
      </div>

      <div className="dataset-toolbar">
        {/* Segmented Control for Base/Agent — r2 fix: count 走 sourceTypeTab filter 后的真实可见数。
            base scenarios 走 classpath/home-dir (无 sourceType 字段) → 当 sourceTypeTab≠'all' 时
            base 全 filter 掉显示 0 (用户期望); agent scenarios BE 已按 sourceType filter 直接 .length 即可 */}
        <div className="dataset-segmented-control">
          {(['base', 'agent'] as DatasetTab[]).map(t => (
            <button
              key={t}
              className={`dataset-segment-btn ${tab === t ? 'on' : ''}`}
              onClick={() => setTab(t)}
            >
              {t === 'base' ? 'Base' : 'Agent'}
              <span className="dataset-segment-count">
                {t === 'base'
                  ? (sourceTypeTab === 'all' ? baseRaw.length : 0)
                  : agentScenarios.length}
              </span>
            </button>
          ))}
        </div>

        {/* Action Buttons Group */}
        <div style={{ display: 'flex', gap: 8, marginLeft: 'auto' }}>
          {tab === 'base' && (
            <Button 
              onClick={() => setAdding(true)}
            >
              + 新增
            </Button>
          )}
          <Button 
            type="primary"
            onClick={() => setShowTraceImporter(true)}
            icon={<span>✨</span>}
          >
            Smart Import
          </Button>
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
                  {/* EVAL-DATASET-LAYER V1 §5.1: source_type / purpose / source_ref
                      chips. Placed first so the closed-enum classification reads
                      before the open-ended category tag — visual hierarchy
                      reflects the dataset composition decision in tech-design §1.1. */}
                  {s.sourceType && (
                    <span
                      className="kv-chip-sf"
                      style={{
                        color:
                          s.sourceType === 'benchmark' ? '#5b8def'
                          : s.sourceType === 'session_derived' ? '#b58dee'
                          : '#5fb3a1',
                        fontWeight: 600,
                      }}
                      title={`source_type=${s.sourceType}`}
                    >
                      {s.sourceType}
                    </span>
                  )}
                  {s.purpose && s.purpose !== 'regression' && (
                    <span
                      className="kv-chip-sf"
                      style={{ opacity: 0.85 }}
                      title={`purpose=${s.purpose}`}
                    >
                      {s.purpose}
                    </span>
                  )}
                  {s.sourceRef && (
                    <span
                      className="kv-chip-sf"
                      style={{ opacity: 0.7, fontFamily: 'var(--font-mono, monospace)', fontSize: 10 }}
                      title={`source_ref=${s.sourceRef}`}
                    >
                      {s.sourceRef.length > 24 ? `${s.sourceRef.slice(0, 22)}…` : s.sourceRef}
                    </span>
                  )}
                  {s.category && <span className="kv-chip-sf">{s.category}</span>}
                  {s.split && <span className="kv-chip-sf">{s.split}</span>}
                  {/* Multi-turn indicator */}
                  {s.conversationTurns && s.conversationTurns.length > 1 && (
                    <span className="kv-chip-sf" style={{ color: 'var(--accent-primary, #d9633a)', fontWeight: 600 }}>
                      💬 {Math.ceil(s.conversationTurns.length / 2)} turns
                    </span>
                  )}
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

      {/* Smart Import Modal */}
      <TraceImportSuggester 
        open={showTraceImporter} 
        onClose={() => setShowTraceImporter(false)} 
      />
    </div>
  );
}

export default DatasetBrowser;
