import React, { useMemo, useState } from 'react';
import { Tooltip } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTrials,
  listTrials,
  type CandidateSurfaceType,
  type DynamicSimErrorResponse,
  type SimulatorTrialResponse,
} from '../../api/dynamicSim';
import {
  getEvalDatasetScenarios,
  type EvalDatasetScenario,
} from '../../api';
import Dropdown from '../ui/Dropdown';
import './dynamic-sim.css';

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.4 — operator panel for kicking off
 * dynamic-sim trials and inspecting the resulting transcripts.
 *
 * <p>Ant Design Form replaced with plain React state. All other Ant Design
 * components replaced with custom design-system components.
 */

export interface DynamicSimPanelProps {
  agentNumericId: number;
  personas: string[];
}

const PAGE_SIZE_DEFAULT = 20;
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

function terminationTagClass(reason: string | null): string {
  switch (reason) {
    case 'task_completed': return 'ds-tag-green';
    case 'failure_signal': return 'ds-tag-red';
    case 'max_turns': return 'ds-tag-gold';
    case 'error': return 'ds-tag-volcano';
    default: return 'ds-tag-default';
  }
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); }
  catch { return iso; }
}

const DynamicSimPanel: React.FC<DynamicSimPanelProps> = ({
  agentNumericId,
  personas,
}) => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  // --- Form state (replaces Ant Design Form) ---
  const [formScenarioId, setFormScenarioId] = useState<string | undefined>(undefined);
  const [formSurface, setFormSurface] = useState<CandidateSurfaceType | undefined>(undefined);
  const [formVersionId, setFormVersionId] = useState('');
  const [formPersonas, setFormPersonas] = useState<string[]>([]);
  const [formMaxTurns, setFormMaxTurns] = useState(10);
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});

  // --- Filter state for trial-history table ---
  const [filterScenarioId, setFilterScenarioId] = useState<string | undefined>(undefined);
  const [filterVersionId, setFilterVersionId] = useState<string | undefined>(undefined);
  const [filterSurface, setFilterSurface] = useState<CandidateSurfaceType | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(PAGE_SIZE_DEFAULT);

  // --- Queries ---
  const scenariosQuery = useQuery({
    queryKey: ['eval-scenarios', agentNumericId],
    queryFn: () =>
      getEvalDatasetScenarios(String(agentNumericId)).then((r) => r.data ?? []),
    staleTime: 60_000,
  });

  const scenarioOptions = useMemo(
    () =>
      (scenariosQuery.data ?? []).map((s: EvalDatasetScenario) => ({
        value: s.id,
        label: `${s.name} (${s.id.slice(0, 8)}…)`,
      })),
    [scenariosQuery.data],
  );

  const trialsQuery = useQuery({
    queryKey: [
      'dynamic-sim-trials',
      filterScenarioId ?? null,
      filterVersionId ?? null,
      filterSurface ?? null,
      page,
      pageSize,
    ],
    queryFn: () =>
      listTrials({
        scenarioId: filterScenarioId,
        candidateAgentVersionId: filterVersionId,
        candidateSurfaceType: filterSurface,
        page,
        size: pageSize,
      }).then((r) => r.data),
    staleTime: 30_000,
  });

  // --- Mutation ---
  const launchMutation = useMutation({
    mutationFn: createTrials,
    onSuccess: (resp) => {
      const data = resp.data;
      alert(
        `Trial launched: ${data.personaCount} persona(s) running for scenario ${data.scenarioId.slice(0, 8)}…`,
      );
      if (formScenarioId) {
        setFilterScenarioId(formScenarioId);
        setPage(0);
      }
      queryClient.invalidateQueries({ queryKey: ['dynamic-sim-trials'] });
    },
    onError: (err: unknown) => {
      const maybeAxios = err as {
        response?: { data?: DynamicSimErrorResponse };
        message?: string;
      };
      const beData = maybeAxios.response?.data;
      if (beData && typeof beData.error === 'string') {
        const supported = beData.supportedSurfaces?.length
          ? ` (supported surfaces: ${beData.supportedSurfaces.join(', ')})`
          : '';
        alert(`Trial launch rejected: ${beData.error}${supported}`);
      } else {
        alert(`Trial launch failed: ${maybeAxios.message ?? 'unknown error'}`);
      }
    },
  });

  // --- Form actions ---
  const onLaunch = () => {
    const errors: Record<string, string> = {};
    if (!formScenarioId) errors.scenario = 'Pick a scenario';
    if (formPersonas.length === 0) errors.personas = 'Pick at least one persona';
    if (formSurface === 'behavior_rule') {
      alert('behavior_rule dynamic sim 暂不支持 — V5.1 backlog.');
      return;
    }
    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      return;
    }
    setFormErrors({});
    launchMutation.mutate({
      scenarioId: formScenarioId!,
      candidateAgentVersionId: formVersionId.trim() || undefined,
      candidateSurfaceType: formSurface,
      personas: formPersonas,
      maxTurns: formMaxTurns,
    });
  };

  const onResetForm = () => {
    setFormScenarioId(undefined);
    setFormSurface(undefined);
    setFormVersionId('');
    setFormPersonas([]);
    setFormMaxTurns(10);
    setFormErrors({});
  };

  const onTogglePersona = (p: string) => {
    setFormPersonas((prev) =>
      prev.includes(p) ? prev.filter((x) => x !== p) : [...prev, p],
    );
  };

  const onClearFilters = () => {
    setFilterScenarioId(undefined);
    setFilterVersionId(undefined);
    setFilterSurface(undefined);
    setPage(0);
  };

  // --- Derived ---
  const trials = trialsQuery.data?.content ?? [];
  const totalElements = trialsQuery.data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalElements / pageSize));

  return (
    <div className="ds-panel">
      {/* Header */}
      <div className="ds-panel-header">
        <span className="ds-panel-title">Dynamic Sim Trial Harness</span>
        <span className="ds-tag ds-tag-purple">user-simulator</span>
        <span className="ds-panel-agent">agent #{agentNumericId}</span>
      </div>

      {/* Info alert */}
      <div className="ds-alert ds-alert-info">
        <div className="ds-alert-title">V5 Phase 1 — manual trigger only</div>
        <div>
          Pick a scenario + candidate version (skill / prompt) + persona(s) and Run Trial.
          The user-simulator agent plays the persona and ping-pongs with your candidate agent
          until task_completed / failure_signal / max_turns.
        </div>
      </div>

      {/* Form */}
      <div className="ds-form-grid">
        {/* Scenario */}
        <div className="ds-field">
          <label className="ds-field-label">Scenario *</label>
          <Dropdown
            options={scenarioOptions}
            value={formScenarioId}
            placeholder={scenariosQuery.isLoading ? 'Loading…' : 'Pick a scenario for this agent'}
            onChange={(v) => {
              setFormScenarioId(v || undefined);
              setFormErrors((prev) => ({ ...prev, scenario: '' }));
            }}
          />
          {formErrors.scenario && <span className="ds-field-error">{formErrors.scenario}</span>}
        </div>

        {/* Surface */}
        <div className="ds-field">
          <label className="ds-field-label">
            Surface{' '}
            <Tooltip title="behavior_rule disabled — V5.1 backlog. V4 AgentLoopEngine 7+1 红灯文件不可改.">
              <span className="ds-field-hint">(?)</span>
            </Tooltip>
          </label>
          <Dropdown
            options={[
              { value: 'prompt', label: 'prompt' },
              { value: 'skill', label: 'skill' },
              { value: 'behavior_rule', label: 'behavior_rule (disabled)', disabled: true },
            ]}
            value={formSurface}
            placeholder="(blank = baseline)"
            allowClear
            onChange={(v) => setFormSurface((v || undefined) as CandidateSurfaceType | undefined)}
          />
        </div>

        {/* Candidate version id */}
        <div className="ds-field">
          <label className="ds-field-label">
            Candidate version id{' '}
            <Tooltip title="VARCHAR(64): skill version id / prompt version id. Leave blank to run against the agent's current baseline.">
              <span className="ds-field-hint">(?)</span>
            </Tooltip>
          </label>
          <input
            className="ds-input"
            placeholder="(blank = baseline)"
            maxLength={64}
            value={formVersionId}
            onChange={(e) => setFormVersionId(e.target.value)}
          />
        </div>

        {/* Max turns */}
        <div className="ds-field">
          <label className="ds-field-label">Max turns</label>
          <input
            className="ds-input"
            type="number"
            min={1}
            max={50}
            value={formMaxTurns}
            onChange={(e) => setFormMaxTurns(Math.max(1, Math.min(50, Number(e.target.value) || 10)))}
          />
        </div>
      </div>

      {/* Personas */}
      <div className="ds-field" style={{ marginTop: 8, marginBottom: 12 }}>
        <label className="ds-field-label">
          Personas{' '}
          <span className="ds-dim">(5 fixed — V5.x backlog to expand)</span>
        </label>
        <div className="ds-personas-grid">
          {personas.map((p) => (
            <label
              key={p}
              className={`ds-persona-check ${formPersonas.includes(p) ? 'checked' : ''}`}
            >
              <input
                type="checkbox"
                checked={formPersonas.includes(p)}
                onChange={() => onTogglePersona(p)}
              />
              <span className="ds-persona-text">{p}</span>
            </label>
          ))}
        </div>
        {formErrors.personas && <span className="ds-field-error">{formErrors.personas}</span>}
      </div>

      {/* Actions */}
      <div className="ds-btn-group">
        <button
          className="ds-btn ds-btn-primary"
          onClick={onLaunch}
          disabled={launchMutation.isPending}
        >
          {launchMutation.isPending ? 'Running…' : 'Run Trial'}
        </button>
        <button
          className="ds-btn"
          onClick={() => setFormPersonas(personas)}
        >
          Select all {personas.length} personas
        </button>
        <button className="ds-btn" onClick={onResetForm}>Reset</button>
      </div>

      {/* Trial history */}
      <div className="ds-section">
        <div className="ds-section-header">
          <span className="ds-section-title">Trial history</span>
          <span className="ds-section-meta">
            {totalElements} total · sorted createdAt DESC
          </span>
          <button
            className="ds-btn ds-btn-sm"
            style={{ marginLeft: 'auto' }}
            onClick={() => queryClient.invalidateQueries({ queryKey: ['dynamic-sim-trials'] })}
            disabled={trialsQuery.isFetching}
          >
            {trialsQuery.isFetching ? 'Loading…' : '↻ Refresh'}
          </button>
        </div>

        {/* Filters */}
        <div className="ds-filter-bar">
          <Dropdown
            options={scenarioOptions}
            value={filterScenarioId}
            placeholder="Filter by scenario"
            allowClear
            onChange={(v) => {
              setFilterScenarioId(v || undefined);
              setPage(0);
            }}
            style={{ minWidth: 220 }}
          />
          <input
            className="ds-input ds-input-sm"
            style={{ width: 220 }}
            placeholder="Filter by candidate version id"
            value={filterVersionId ?? ''}
            onChange={(e) => {
              setFilterVersionId(e.target.value || undefined);
              setPage(0);
            }}
          />
          <Dropdown
            options={[
              { value: 'prompt', label: 'prompt' },
              { value: 'skill', label: 'skill' },
            ]}
            value={filterSurface}
            placeholder="Filter by surface"
            allowClear
            onChange={(v) => {
              setFilterSurface((v || undefined) as CandidateSurfaceType | undefined);
              setPage(0);
            }}
            style={{ width: 160 }}
          />
          <button className="ds-btn ds-btn-sm" onClick={onClearFilters}>Clear filters</button>
        </div>

        {/* Error */}
        {trialsQuery.isError && (
          <div className="ds-alert ds-alert-error">
            <div className="ds-alert-title">Failed to load trials.</div>
            <div>GET /api/dynamic-sim/trials returned an error. Try Refresh, or check the server log.</div>
          </div>
        )}

        {/* Empty */}
        {!trialsQuery.isError && trials.length === 0 && !trialsQuery.isLoading && (
          <div className="ds-empty" style={{ marginTop: 24, marginBottom: 24 }}>
            <p className="ds-empty-title">No trials yet</p>
            <p className="ds-empty-desc">Run one above to see results here.</p>
          </div>
        )}

        {/* Table */}
        {(trials.length > 0 || trialsQuery.isLoading) && (
          <>
            <div className="ds-table-wrap">
              <table className="ds-table">
                <thead>
                  <tr>
                    <th className="col-trial">Trial id</th>
                    <th className="col-persona">Persona</th>
                    <th className="col-surface">Surface</th>
                    <th className="col-turns">Turns</th>
                    <th className="col-term">Termination</th>
                    <th className="col-created">Created</th>
                    <th className="col-transcript">Transcript</th>
                  </tr>
                </thead>
                <tbody>
                  {trialsQuery.isLoading ? (
                    <tr>
                      <td colSpan={7} style={{ textAlign: 'center', padding: 24, color: 'var(--fg-3)' }}>
                        Loading…
                      </td>
                    </tr>
                  ) : (
                    trials.map((row) => (
                      <tr key={row.trialId}>
                        <td className="ds-col-mono">{row.trialId.slice(0, 8)}…</td>
                        <td>
                          <Tooltip title={row.persona}>
                            <span className="ds-sm">{row.persona}</span>
                          </Tooltip>
                        </td>
                        <td>
                          {row.candidateSurfaceType ? (
                            <span className="ds-tag ds-tag-purple">{row.candidateSurfaceType}</span>
                          ) : (
                            <span className="ds-tag ds-tag-default">baseline</span>
                          )}
                        </td>
                        <td className="ds-col-right">{row.turnsUsed}</td>
                        <td>
                          <span className={`ds-tag ${terminationTagClass(row.terminationReason)}`}>
                            {row.terminationReason ?? 'pending'}
                          </span>
                        </td>
                        <td className="ds-col-dim">{formatTimestamp(row.createdAt)}</td>
                        <td className="ds-col-action">
                          <button
                            className="ds-btn-link"
                            onClick={() => navigate(`/chat/${row.sessionId}`)}
                          >
                            View transcript →
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            {totalElements > 0 && (
              <div className="ds-pagination">
                <button
                  disabled={page <= 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  ← Prev
                </button>
                <span className="ds-pagination-info">
                  Page {page + 1} / {totalPages}
                </span>
                <button
                  disabled={page >= totalPages - 1}
                  onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                >
                  Next →
                </button>
                <Dropdown
                  options={PAGE_SIZE_OPTIONS.map((n) => ({ value: String(n), label: `${n} / page` }))}
                  value={String(pageSize)}
                  onChange={(v) => {
                    if (v) {
                      setPageSize(Number(v));
                      setPage(0);
                    }
                  }}
                  style={{ width: 100 }}
                />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default DynamicSimPanel;
