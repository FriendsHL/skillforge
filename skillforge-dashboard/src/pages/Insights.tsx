import React, { useState, useMemo } from 'react';
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
import TabBar from '../components/TabBar';
import '../components/insights/insights.css';

interface FilterFormValues {
  outcome?: SessionOutcome;
  surface?: SuspectSurface;
  agent?: number;
  limit?: number;
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
  { key: 'optimization', label: 'Optimization' },
  { key: 'behavior-rules', label: 'Behavior Rules' },
  { key: 'dynamic-sim', label: 'Dynamic Sim' },
];

const Insights: React.FC = () => {
  const [activeTab, setActiveTab] = useState('patterns');
  const [filters, setFilters] = useState<ListPatternsParams>({ limit: DEFAULT_LIMIT });
  const [selectedPattern, setSelectedPattern] = useState<PatternListItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Filter form state (plain React state, no Ant Design Form)
  const [fOutcome, setFOutcome] = useState<SessionOutcome | undefined>(undefined);
  const [fSurface, setFSurface] = useState<SuspectSurface | undefined>(undefined);
  const [fAgent, setFAgent] = useState<string>('');
  const [fLimit, setFLimit] = useState(DEFAULT_LIMIT);

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

  if (activeTab === 'optimization') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={setActiveTab} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <OptimizationEventsPage />
        </div>
      </div>
    );
  }

  if (activeTab === 'behavior-rules') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={setActiveTab} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <BehaviorRuleEvolutionPage />
        </div>
      </div>
    );
  }

  if (activeTab === 'dynamic-sim') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={setActiveTab} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <DynamicSimPage />
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
      <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={setActiveTab} />
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
            <select
              className="ins-select"
              value={fOutcome ?? ''}
              onChange={(e) => setFOutcome((e.target.value || undefined) as SessionOutcome | undefined)}
            >
              <option value="">any</option>
              {OUTCOME_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>
          <div className="ins-field">
            <label className="ins-field-label">Surface</label>
            <select
              className="ins-select"
              value={fSurface ?? ''}
              onChange={(e) => setFSurface((e.target.value || undefined) as SuspectSurface | undefined)}
            >
              <option value="">any</option>
              {SURFACE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
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
