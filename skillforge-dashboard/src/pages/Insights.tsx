import React, { Suspense, useState, useMemo, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  listPatterns,
  type ListPatternsParams,
  type PatternListItem,
  type SessionOutcome,
  type SuspectSurface,
} from '../api/insights';
import PatternList from '../components/insights/PatternList';
import PatternDetailDrawer from '../components/insights/PatternDetailDrawer';
import OptimizationEventsPage from './OptimizationEvents';
import BehaviorRuleEvolutionPage from './BehaviorRuleEvolution';
import DynamicSimPage from './DynamicSim';
import OptReportsPage from './OptReports';
// F5 (code BUNDLE-3) — code-split: reactflow + dagre (~220 KB minified, ~72 KB
// gzipped) only load when the operator actually clicks the Flywheel tab.
// Static `import` was pulling those deps into the main Insights chunk even
// for users who never visit the tab.
const FlywheelObservability = React.lazy(() => import('./FlywheelObservability'));
import TabBar from '../components/TabBar';
import Dropdown from '../components/ui/Dropdown';
import '../components/insights/insights.css';

/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — known tab keys. Anything
 * outside this allowlist in the `?tab=` URL param falls back to the default
 * `patterns` tab (no silent typo confusion).
 */
const TAB_KEYS = ['patterns', 'optimization', 'behavior-rules', 'dynamic-sim', 'flywheel', 'reports'] as const;
type TabKey = (typeof TAB_KEYS)[number];

function normalizeTab(raw: string | null): TabKey {
  return (TAB_KEYS as readonly string[]).includes(raw ?? '')
    ? (raw as TabKey)
    : 'patterns';
}

const OUTCOME_OPTIONS: { value: SessionOutcome; label: string }[] = [
  { value: 'success', label: 'success' },
  { value: 'partial_success', label: 'partial_success' },
  { value: 'failure', label: 'failure' },
  { value: 'cancelled', label: 'cancelled' },
];

const SURFACE_OPTIONS: { value: SuspectSurface; label: string }[] = [
  { value: 'skill', label: 'skill' },
  { value: 'prompt', label: 'prompt' },
  { value: 'behavior_rule', label: 'behavior_rule' },
  { value: 'other', label: 'other' },
  { value: 'unclear', label: 'unclear' },
];

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

const INSIGHTS_TABS = [
  { key: 'patterns', label: 'Patterns' },
  { key: 'optimization', label: 'Events' },
  { key: 'behavior-rules', label: 'Behavior Rules' },
  { key: 'dynamic-sim', label: 'Dynamic Sim' },
  { key: 'flywheel', label: 'Optimization Loop' },
  { key: 'reports', label: 'Reports' },
];

const Insights: React.FC = () => {
  // FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — URL ?tab= drives the
  // active tab; flipping tabs in-page writes back via setSearchParams so
  // the URL stays canonical for deep linking / browser back-forward.
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab: TabKey = normalizeTab(searchParams.get('tab'));
  const setActiveTab = (next: TabKey) => {
    setSearchParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if (next === 'patterns') {
          out.delete('tab');
        } else {
          out.set('tab', next);
        }
        return out;
      },
      { replace: true },
    );
  };
  const [filters, setFilters] = useState<ListPatternsParams>({ limit: DEFAULT_LIMIT });
  const [selectedPattern, setSelectedPattern] = useState<PatternListItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Filter form state (plain React state, no Ant Design Form)
  const [fOutcome, setFOutcome] = useState<SessionOutcome | undefined>(undefined);
  const [fSurface, setFSurface] = useState<SuspectSurface | undefined>(undefined);
  const [fAgent, setFAgent] = useState<string>('');
  const [fLimit, setFLimit] = useState(DEFAULT_LIMIT);

  // FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — hydrate the pattern
  // filter form from `?outcome=&surface=&agent=&limit=` so deep links land
  // with the filter pre-applied. One-way URL → state; in-page Apply still
  // owns the data fetch.
  useEffect(() => {
    if (activeTab !== 'patterns') return;
    const o = searchParams.get('outcome');
    const su = searchParams.get('surface');
    const ag = searchParams.get('agent');
    const lim = searchParams.get('limit');
    const valid = (
      v: string | null,
      whitelist: readonly string[],
    ): string | undefined =>
      v != null && whitelist.includes(v) ? v : undefined;
    const nextOutcome = valid(o, ['success', 'partial_success', 'failure', 'cancelled']) as
      | SessionOutcome
      | undefined;
    const nextSurface = valid(su, ['skill', 'prompt', 'behavior_rule', 'other', 'unclear']) as
      | SuspectSurface
      | undefined;
    const nextLimit = lim ? Math.max(1, Math.min(MAX_LIMIT, Number(lim) || DEFAULT_LIMIT)) : DEFAULT_LIMIT;
    if (nextOutcome) setFOutcome(nextOutcome);
    if (nextSurface) setFSurface(nextSurface);
    if (ag) setFAgent(ag);
    setFLimit(nextLimit);
    setFilters({
      ...(nextOutcome ? { outcome: nextOutcome } : {}),
      ...(nextSurface ? { surface: nextSurface } : {}),
      ...(ag ? { agent: Number(ag) } : {}),
      limit: nextLimit,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    activeTab,
    searchParams.get('outcome'),
    searchParams.get('surface'),
    searchParams.get('agent'),
    searchParams.get('limit'),
  ]);

  const { data: patterns = [], isLoading, isError, error } = useQuery({
    queryKey: ['insights-patterns', filters],
    queryFn: () => listPatterns(filters).then((r) => r.data ?? []),
    staleTime: 30_000,
  });

  const errMsg = useMemo(() => {
    if (!isError) return null;
    if (error instanceof Error) return error.message;
    return 'Failed to load patterns.';
  }, [isError, error]);

  const onApply = () => {
    const next: ListPatternsParams = {};
    if (fOutcome) next.outcome = fOutcome;
    if (fSurface) next.surface = fSurface;
    if (fAgent) next.agent = Number(fAgent);
    next.limit = fLimit || DEFAULT_LIMIT;
    setFilters(next);
  };

  const onReset = () => {
    setFOutcome(undefined);
    setFSurface(undefined);
    setFAgent('');
    setFLimit(DEFAULT_LIMIT);
    setFilters({ limit: DEFAULT_LIMIT });
  };

  const onRowClick = (pattern: PatternListItem) => {
    setSelectedPattern(pattern);
    setDrawerOpen(true);
  };

  const onDrawerClose = () => setDrawerOpen(false);

  const onTabSwitch = (key: string) => setActiveTab(normalizeTab(key));

  if (activeTab === 'optimization') {
    // ts-B3 fix — feed `?stage=` / `?agentId=` from the URL into the
    // OptimizationEvents tab so flywheel G1/G3 drill-downs land pre-
    // filtered (PRD R3 真消费 contract). Stage allowlist matches the BE
    // `OptimizationEventEntity.STAGE_*` constants.
    const stageParam = searchParams.get('stage');
    const agentIdParam = searchParams.get('agentId');
    const validStages = new Set([
      'dispatch_initiated', 'proposal_pending', 'proposal_approved',
      'proposal_rejected', 'candidate_generating', 'candidate_ready',
      'candidate_failed', 'candidate_created', 'ab_running', 'ab_passed',
      'ab_failed', 'canary_started', 'promoted', 'rolled_back', 'verified',
    ]);
    const initialStageFilter =
      stageParam && validStages.has(stageParam)
        ? (stageParam as Parameters<typeof OptimizationEventsPage>[0] extends { initialStageFilter?: infer S } ? S : never)
        : undefined;
    const initialAgentIdFilter = agentIdParam ? Number(agentIdParam) : undefined;
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={onTabSwitch} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <OptimizationEventsPage
            initialStageFilter={initialStageFilter}
            initialAgentIdFilter={
              initialAgentIdFilter !== undefined && Number.isFinite(initialAgentIdFilter)
                ? initialAgentIdFilter
                : undefined
            }
          />
        </div>
      </div>
    );
  }

  if (activeTab === 'behavior-rules') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={onTabSwitch} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <BehaviorRuleEvolutionPage />
        </div>
      </div>
    );
  }

  if (activeTab === 'dynamic-sim') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={onTabSwitch} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <DynamicSimPage />
        </div>
      </div>
    );
  }

  if (activeTab === 'flywheel') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={onTabSwitch} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          {/* F5 — Suspense fallback while the lazy FlywheelObservability
              chunk (reactflow + dagre) downloads on first visit. */}
          <Suspense
            fallback={
              <div style={{ padding: 24, color: 'var(--fg-3)', fontSize: 13 }}>
                Loading Optimization Loop…
              </div>
            }
          >
            <FlywheelObservability />
          </Suspense>
        </div>
      </div>
    );
  }

  if (activeTab === 'reports') {
    // OPT-REPORT-V1 Sub-batch 2 — Reports tab. URL state (`?agentId=` /
    // `?reportId=`) is owned by OptReportsPage so the WS-driven "View
    // report →" toast in Layout.tsx deep-links straight to the selected row.
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={onTabSwitch} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <OptReportsPage />
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
      <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={onTabSwitch} />
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable', padding: 'var(--sp-6, 24px) var(--sp-8, 32px)', maxWidth: 1600, margin: '0 auto' }}>
        {/* Header */}
        <div style={{ marginBottom: 24 }}>
          <h1 style={{ fontFamily: 'var(--font-serif)', fontSize: 28, fontWeight: 500, letterSpacing: '-0.02em', color: 'var(--fg-1)', margin: '0 0 4px', lineHeight: 1.2 }}>
            Insights — Failure Patterns
          </h1>
          <p style={{ color: 'var(--fg-3)', fontSize: 'var(--font-size-sm)', margin: 0 }}>
            Hourly session-annotator agent labels production sessions and groups them by
            (outcome × suspect_surface × top_failing_tool × agent). Clusters with ≥ 3 members surface here.
          </p>
        </div>

        {/* Filter form */}
        <div className="ins-filter-form">
          <div className="ins-field">
            <label className="ins-field-label">Outcome</label>
            <Dropdown
              options={OUTCOME_OPTIONS}
              value={fOutcome}
              placeholder="any"
              allowClear
              onChange={(v) => setFOutcome(v as SessionOutcome | undefined)}
            />
          </div>
          <div className="ins-field">
            <label className="ins-field-label">Surface</label>
            <Dropdown
              options={SURFACE_OPTIONS}
              value={fSurface}
              placeholder="any"
              allowClear
              onChange={(v) => setFSurface(v as SuspectSurface | undefined)}
            />
          </div>
          <div className="ins-field">
            <label className="ins-field-label">Agent id</label>
            <input
              className="ins-input-num"
              type="number"
              min={1}
              placeholder="any"
              value={fAgent}
              onChange={(e) => setFAgent(e.target.value)}
            />
          </div>
          <div className="ins-field">
            <label className="ins-field-label">Limit</label>
            <input
              className="ins-input-num ins-input-num-sm"
              type="number"
              min={1}
              max={MAX_LIMIT}
              value={fLimit}
              onChange={(e) => setFLimit(Math.max(1, Math.min(MAX_LIMIT, Number(e.target.value) || DEFAULT_LIMIT)))}
            />
          </div>
          <div className="ins-field" style={{ flexDirection: 'row', gap: 8, alignItems: 'flex-end' }}>
            <button className="ins-btn ins-btn-primary" onClick={onApply}>Apply</button>
            <button className="ins-btn" onClick={onReset}>Reset</button>
          </div>
        </div>

        {/* Error */}
        {errMsg && (
          <div className="ins-alert ins-alert-error">
            <div className="ins-alert-title">Failed to load patterns</div>
            <div>{errMsg}</div>
          </div>
        )}

        {/* Pattern list */}
        <PatternList patterns={patterns} loading={isLoading} onRowClick={onRowClick} />

        {/* Detail drawer */}
        <PatternDetailDrawer
          pattern={selectedPattern}
          open={drawerOpen}
          onClose={onDrawerClose}
        />
      </div>
    </div>
  );
};

export default Insights;
