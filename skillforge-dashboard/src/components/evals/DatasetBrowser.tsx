import React, { useMemo, useState } from 'react';
import { Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  getEvalDatasetScenarios,
  type EvalDatasetScenario,
} from '../../api';
import ScenarioDetailDrawer from './ScenarioDetailDrawer';
import AnalyzeCaseModal from './AnalyzeCaseModal';

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

function DatasetBrowser({ agents, userId }: DatasetBrowserProps) {
  const [agentId, setAgentId] = useState<string>(() => {
    const first = agents[0];
    return first ? String(first.id) : '';
  });
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('');
  const [opened, setOpened] = useState<EvalDatasetScenario | null>(null);
  const [analyzing, setAnalyzing] = useState<EvalDatasetScenario | null>(null);

  const { data: scenarios = [], isLoading, isError } = useQuery({
    queryKey: ['eval-dataset-scenarios', agentId],
    queryFn: () => getEvalDatasetScenarios(agentId).then(r => r.data ?? []),
    enabled: !!agentId,
  });

  // Distinct categories — derive from data so we don't hardcode
  const categories = useMemo(() => {
    const set = new Set<string>();
    scenarios.forEach(s => { if (s.category) set.add(s.category); });
    return Array.from(set).sort();
  }, [scenarios]);

  const filtered = useMemo(() => {
    const ql = q.trim().toLowerCase();
    return scenarios.filter(s => {
      if (ql && !`${s.name} ${s.task} ${s.category}`.toLowerCase().includes(ql)) return false;
      if (statusFilter && s.status !== statusFilter) return false;
      if (categoryFilter && s.category !== categoryFilter) return false;
      return true;
    });
  }, [scenarios, q, statusFilter, categoryFilter]);

  return (
    <div className="dataset-browser">
      <div className="dataset-toolbar">
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
        <input
          className="agents-search"
          placeholder="search name / task / category…"
          value={q}
          onChange={e => setQ(e.target.value)}
        />
        <Select
          value={statusFilter || ''}
          onChange={(v) => setStatusFilter(v ?? '')}
          options={STATUS_OPTIONS}
          style={{ minWidth: 140 }}
        />
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

      {!agentId ? (
        <div className="sf-empty-state">Select an agent to browse its dataset.</div>
      ) : isError ? (
        <div className="sf-empty-state">Failed to load dataset.</div>
      ) : isLoading ? (
        <div className="sf-empty-state">Loading…</div>
      ) : filtered.length === 0 ? (
        <div className="sf-empty-state">No scenarios match the current filters.</div>
      ) : (
        <div className="dataset-grid">
          {filtered.map(s => (
            <button
              key={s.id}
              className={`dataset-card s-${s.status}`}
              onClick={() => setOpened(s)}
            >
              <div className="dataset-card-h">
                <h4>{s.name}</h4>
                <span className={`sess-status s-${s.status === 'active' ? 'idle' : s.status === 'draft' ? 'waiting' : 'error'}`}>
                  {s.status}
                </span>
              </div>
              <div className="dataset-card-meta">
                <span className="kv-chip-sf">{s.category}</span>
                <span className="kv-chip-sf">{s.split}</span>
                <span className="kv-chip-sf">{s.oracleType}</span>
              </div>
              <div className="dataset-card-task">{s.task}</div>
            </button>
          ))}
        </div>
      )}

      {opened && (
        <ScenarioDetailDrawer
          scenario={opened}
          onClose={() => setOpened(null)}
          onAnalyze={(scn) => setAnalyzing(scn)}
        />
      )}

      {analyzing && (
        <AnalyzeCaseModal
          scenario={analyzing}
          agents={agents}
          userId={userId}
          onClose={() => setAnalyzing(null)}
        />
      )}
    </div>
  );
}

export default DatasetBrowser;
