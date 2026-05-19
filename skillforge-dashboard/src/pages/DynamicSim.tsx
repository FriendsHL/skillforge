import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { extractList, getAgents } from '../api';
import { AgentSchema, safeParseList, type AgentDto } from '../api/schemas';
import DynamicSimPanel from '../components/dynamicSim/DynamicSimPanel';
import '../components/dynamicSim/dynamic-sim.css';

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.4 — `/insights` Dynamic Sim tab.
 *
 * <p>Composes the agent picker + {@link DynamicSimPanel}. State lives here
 * (selected agent id + persona list); the panel is dumb-presentational.
 *
 * <p><strong>Personas</strong>: hardcoded mirror of
 * {@code application.yml::skillforge.eval.user-simulator.personas} (5 fixed
 * strings). If a future {@code /api/eval/personas} endpoint lands, swap
 * for a {@code useQuery} call.
 */

const HARDCODED_PERSONAS: string[] = [
  '销售经理急性子 — 短句催进度、商业语言、不耐烦细节',
  '数据分析师细心 — 多确认、问细节、追究边界条件',
  'CEO 高高在上 — 命令式、不解释、只要结果',
  '实习生小白 — 问基础概念、需要铺垫、抓不准重点',
  'DBA 老手 — 跳过初级解释、直接问深问题、对效率敏感',
];

const DynamicSim: React.FC = () => {
  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);

  const {
    data: agents = [],
    isLoading,
    isError,
  } = useQuery({
    queryKey: ['agents'],
    queryFn: () =>
      getAgents().then((res) =>
        safeParseList(AgentSchema, extractList<Record<string, unknown>>(res)),
      ),
    staleTime: 60_000,
  });

  React.useEffect(() => {
    if (selectedAgentId == null && agents.length > 0) {
      const firstId = agents[0].id;
      if (typeof firstId === 'number') setSelectedAgentId(firstId);
    }
  }, [agents, selectedAgentId]);

  const agentOptions = useMemo(
    () =>
      agents
        .filter((a: AgentDto) => typeof a.id === 'number')
        .map((a: AgentDto) => ({
          value: a.id as number,
          label: `${a.name} (#${a.id})`,
        })),
    [agents],
  );

  const selectedAgent = useMemo(
    () => agents.find((a: AgentDto) => a.id === selectedAgentId) ?? null,
    [agents, selectedAgentId],
  );

  return (
    <div className="ds-page">
      <div className="ds-page-head">
        <h1 className="ds-page-title">Insights — Dynamic Sim Trials</h1>
        <p className="ds-page-desc">
          Drive the user-simulator harness: pick a candidate (skill / prompt
          version) + scenario + persona(s), kick off a trial, and inspect the
          resulting transcript through the existing SessionDetail viewer.
          Trials persist as origin='user_sim' sessions, isolated from V1
          patterns / V2 canary metrics / V3 attribution.
        </p>
      </div>

      {isError && (
        <div className="ds-alert ds-alert-error">
          <div className="ds-alert-title">Failed to load agents.</div>
          <div>Cannot pick an agent without the list. Retry navigating, or check /api/agents.</div>
        </div>
      )}

      <div className="ds-agent-row">
        <span className="ds-agent-label">Agent:</span>
        <select
          className="ds-select"
          style={{ minWidth: 280 }}
          value={selectedAgentId ?? ''}
          onChange={(e) => setSelectedAgentId(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="" disabled>{isLoading ? 'Loading…' : 'Pick an agent'}</option>
          {agentOptions.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        {selectedAgent && (
          <span className="ds-agent-mode">
            {selectedAgent.executionMode ?? 'ask'} mode
          </span>
        )}
      </div>

      {selectedAgent && typeof selectedAgent.id === 'number' ? (
        <DynamicSimPanel
          agentNumericId={selectedAgent.id}
          personas={HARDCODED_PERSONAS}
        />
      ) : (
        !isLoading && (
          <div className="ds-empty" style={{ marginTop: 32 }}>
            <p className="ds-empty-title">
              {agents.length === 0
                ? 'No agents available'
                : 'Pick an agent to launch a dynamic-sim trial.'}
            </p>
            {agents.length === 0 && (
              <p className="ds-empty-desc">Create one from the Agents page first.</p>
            )}
          </div>
        )
      )}
    </div>
  );
};

export default DynamicSim;
