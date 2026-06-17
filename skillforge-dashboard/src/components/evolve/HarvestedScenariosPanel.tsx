/**
 * BC-M2b 子轮2 — HarvestedScenariosPanel
 *
 * Surfaces an agent's harvested bad-case scenarios (real captured failures
 * turned into eval targets) and the human gate that activates them into the
 * agent's eval dataset:
 *
 *   - "Harvest bad cases" — server-side clusters recent real failures into
 *     fresh draft scenarios.
 *   - Draft column — each row carries an Activate action (Modal.confirm →
 *     activate → published into the agent's eval dataset as a measurement
 *     target). Human-gated: the activation is irreversible.
 *   - Active column — read-only; scenarios already in the eval dataset.
 *
 * Copy is strictly mechanism-descriptive (harvest / activate / measure) — it
 * never describes or implies how any failure should be fixed.
 *
 * Data loading via react-query (two parallel lists, keyed by agent). Mutations
 * follow the EvolveAdoptCard idiom (plain async + local loading state + cache
 * invalidation) rather than useMutation, for consistency with this module.
 */
import React, { useState, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Select, Button, Modal, Tag, message } from 'antd';
import { useAuth } from '../../contexts/AuthContext';
import {
  listHarvestedScenarios,
  activateHarvestedScenario,
  harvestBadCases,
  type HarvestedScenario,
} from '../../api/evolve';
import { getAgents } from '../../api/index';
import './evolve.css';

interface AgentLite {
  id: number;
  name: string;
}

const STALE = 20_000;

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

function errMsg(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback;
}

// ─────────────────────────── scenario row ──────────────────────────────────

interface ScenarioRowProps {
  scenario: HarvestedScenario;
  /** When set, renders the Activate action (draft column only). */
  onActivate?: (scenario: HarvestedScenario) => void;
  /** True while this row's activation is in flight. */
  activating?: boolean;
}

const ScenarioRow: React.FC<ScenarioRowProps> = React.memo(
  ({ scenario, onActivate, activating = false }) => {
    const isDraft = scenario.status === 'draft';
    return (
      <li className="hsp-row" data-testid="hsp-row">
        <div className="hsp-row-main">
          <div className="hsp-row-top">
            <span
              className={
                'hsp-status-dot ' +
                (isDraft ? 'hsp-status-dot--draft' : 'hsp-status-dot--active')
              }
              aria-hidden="true"
            />
            <span className="hsp-row-name" title={scenario.name}>
              {scenario.name}
            </span>
          </div>
          {scenario.description && (
            <p className="hsp-row-desc" title={scenario.description}>
              {scenario.description}
            </p>
          )}
          <div className="hsp-row-meta">
            {scenario.sourceRef && (
              <span className="hsp-row-source" title={scenario.sourceRef}>
                ⤷ {scenario.sourceRef}
              </span>
            )}
            <span className="hsp-row-time">
              {isDraft
                ? `收割于 ${formatTimestamp(scenario.createdAt)}`
                : `激活于 ${formatTimestamp(scenario.reviewedAt)}`}
            </span>
          </div>
        </div>
        {onActivate && (
          <Button
            size="small"
            type="primary"
            className="hsp-activate-btn"
            loading={activating}
            disabled={activating}
            onClick={() => onActivate(scenario)}
            data-testid="hsp-activate-btn"
          >
            Activate
          </Button>
        )}
      </li>
    );
  },
);
ScenarioRow.displayName = 'ScenarioRow';

// ─────────────────────────── scenario column ───────────────────────────────

interface ScenarioColumnProps {
  title: string;
  status: 'draft' | 'active';
  scenarios: HarvestedScenario[];
  loading: boolean;
  error: string | null;
  emptyHint: string;
  onActivate?: (scenario: HarvestedScenario) => void;
  activatingIds?: ReadonlySet<string>;
}

const ScenarioColumn: React.FC<ScenarioColumnProps> = ({
  title,
  status,
  scenarios,
  loading,
  error,
  emptyHint,
  onActivate,
  activatingIds,
}) => (
  <div className="hsp-col" data-testid={`hsp-col-${status}`}>
    <div className="hsp-col-head">
      <Tag
        className="hsp-col-tag"
        color={status === 'draft' ? 'gold' : 'green'}
      >
        {title}
      </Tag>
      <span className="hsp-col-count">{scenarios.length}</span>
    </div>
    {error ? (
      <p className="hsp-col-status hsp-col-status--error" role="alert">
        {error}
      </p>
    ) : loading ? (
      <p className="hsp-col-status">Loading…</p>
    ) : scenarios.length === 0 ? (
      <p className="hsp-col-status">{emptyHint}</p>
    ) : (
      <ul className="hsp-list">
        {scenarios.map((s) => (
          <ScenarioRow
            key={s.id}
            scenario={s}
            onActivate={onActivate}
            activating={activatingIds?.has(s.id) ?? false}
          />
        ))}
      </ul>
    )}
  </div>
);

// ─────────────────────────── panel ─────────────────────────────────────────

const HarvestedScenariosPanel: React.FC = () => {
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [committedAgentId, setCommittedAgentId] = useState<number | null>(null);
  const [harvesting, setHarvesting] = useState(false);
  // Track in-flight activations so each row can show its own loading state and
  // a row can't double-submit.
  const [activatingIds, setActivatingIds] = useState<ReadonlySet<string>>(
    () => new Set(),
  );

  // ── agents for the picker ──
  const { data: agents, isLoading: agentsLoading } = useQuery({
    queryKey: ['agents', 'harvested-scenarios'],
    queryFn: () => getAgents().then((r) => (r.data as AgentLite[]) ?? []),
    staleTime: 60_000,
  });

  // ── draft + active lists (parallel, keyed by agent) ──
  const draftQuery = useQuery({
    queryKey: ['harvested-scenarios', committedAgentId, 'draft'],
    queryFn: () =>
      committedAgentId != null
        ? listHarvestedScenarios(committedAgentId, 'draft').then(
            (r) => r.data.items,
          )
        : Promise.resolve([] as HarvestedScenario[]),
    enabled: committedAgentId != null,
    staleTime: STALE,
  });

  const activeQuery = useQuery({
    queryKey: ['harvested-scenarios', committedAgentId, 'active'],
    queryFn: () =>
      committedAgentId != null
        ? listHarvestedScenarios(committedAgentId, 'active').then(
            (r) => r.data.items,
          )
        : Promise.resolve([] as HarvestedScenario[]),
    enabled: committedAgentId != null,
    staleTime: STALE,
  });

  const refreshLists = useCallback(() => {
    if (committedAgentId == null) return;
    queryClient.invalidateQueries({
      queryKey: ['harvested-scenarios', committedAgentId],
    });
  }, [queryClient, committedAgentId]);

  const handleSelectAgent = useCallback((agentId: number) => {
    setCommittedAgentId(agentId);
  }, []);

  // ── harvest fresh drafts ──
  const handleHarvest = useCallback(async () => {
    if (committedAgentId == null || harvesting) return;
    setHarvesting(true);
    try {
      const res = await harvestBadCases(committedAgentId);
      const { count } = res.data;
      if (count > 0) {
        message.success(`已收割 ${count} 条错题，进入草稿待激活。`);
      } else {
        message.info('近期窗口内未发现可收割的失败错题。');
      }
      refreshLists();
    } catch (e) {
      message.error(`收割失败：${errMsg(e, '收割请求失败')}`);
    } finally {
      setHarvesting(false);
    }
  }, [committedAgentId, harvesting, refreshLists]);

  // ── activate one draft into the eval dataset ──
  const runActivate = useCallback(
    async (scenario: HarvestedScenario) => {
      setActivatingIds((prev) => {
        const next = new Set(prev);
        next.add(scenario.id);
        return next;
      });
      try {
        const res = await activateHarvestedScenario(scenario.id, userId);
        const { datasetVersionNumber, datasetScenarioCount } = res.data;
        message.success(
          `已激活进评测靶子（数据集版本 v${datasetVersionNumber}，共 ${datasetScenarioCount} 条）。`,
        );
        refreshLists();
      } catch (e) {
        message.error(`激活失败：${errMsg(e, '激活请求失败')}`);
      } finally {
        setActivatingIds((prev) => {
          const next = new Set(prev);
          next.delete(scenario.id);
          return next;
        });
      }
    },
    [userId, refreshLists],
  );

  const handleActivateClick = useCallback(
    (scenario: HarvestedScenario) => {
      if (activatingIds.has(scenario.id)) return;
      Modal.confirm({
        title: '激活这条错题进评测靶子？',
        content: (
          <span>
            会把「{scenario.name}」发布进该 agent 的评测数据集，作为后续评测的
            衡量靶子。激活不可撤销。
          </span>
        ),
        okText: 'Activate',
        cancelText: '取消',
        onOk: () => runActivate(scenario),
      });
    },
    [activatingIds, runActivate],
  );

  const draftErr = draftQuery.isError
    ? errMsg(draftQuery.error, 'Failed to load draft scenarios.')
    : null;
  const activeErr = activeQuery.isError
    ? errMsg(activeQuery.error, 'Failed to load active scenarios.')
    : null;

  return (
    <section
      className="hsp-section"
      aria-label="Harvested bad-case scenarios"
      data-testid="harvested-scenarios-panel"
    >
      <div className="hsp-head">
        <div className="hsp-head-text">
          <h3 className="hsp-title">错题本</h3>
          <p className="hsp-subtitle">
            收割真实失败错题，人工激活进评测靶子，衡量后续是否复现。
          </p>
        </div>
        <Button
          className="hsp-harvest-btn"
          loading={harvesting}
          disabled={committedAgentId == null || harvesting}
          onClick={handleHarvest}
          data-testid="hsp-harvest-btn"
        >
          Harvest bad cases
        </Button>
      </div>

      <div className="hsp-agent-select">
        <label className="hsp-agent-label" htmlFor="hsp-agent-select">
          Agent
        </label>
        <Select
          id="hsp-agent-select"
          style={{ minWidth: 240 }}
          showSearch
          optionFilterProp="label"
          placeholder={agentsLoading ? 'Loading agents…' : 'Select an agent'}
          loading={agentsLoading}
          value={committedAgentId ?? undefined}
          onChange={handleSelectAgent}
          options={(agents ?? []).map((a) => ({
            label: `${a.name} (#${a.id})`,
            value: a.id,
          }))}
          data-testid="hsp-agent-select"
        />
      </div>

      {committedAgentId == null ? (
        <p className="hsp-empty">选择一个 agent 查看其错题本。</p>
      ) : (
        <div className="hsp-body">
          <ScenarioColumn
            title="Draft"
            status="draft"
            scenarios={draftQuery.data ?? []}
            loading={draftQuery.isLoading}
            error={draftErr}
            emptyHint="暂无待激活错题。点上方按钮收割近期失败。"
            onActivate={handleActivateClick}
            activatingIds={activatingIds}
          />
          <ScenarioColumn
            title="Active"
            status="active"
            scenarios={activeQuery.data ?? []}
            loading={activeQuery.isLoading}
            error={activeErr}
            emptyHint="暂无已激活错题。"
          />
        </div>
      )}
    </section>
  );
};

export default HarvestedScenariosPanel;
