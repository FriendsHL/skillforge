import React, { useMemo, useState } from 'react';
import { Alert, Empty, Select, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { extractList, getAgents } from '../api';
import { AgentSchema, safeParseList, type AgentDto } from '../api/schemas';
import DynamicSimPanel from '../components/dynamicSim/DynamicSimPanel';

const { Title, Paragraph } = Typography;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.4 — `/insights` Dynamic Sim tab.
 *
 * <p>Composes the agent picker + {@link DynamicSimPanel}. State lives here
 * (selected agent id + persona list); the panel is dumb-presentational.
 *
 * <p>Rendered inside {@code Insights.tsx} as the 4th tab — no standalone
 * route is added to {@code App.tsx} because Phase 1.4 inherits the
 * {@code /insights/patterns} URL and follows the V4 BehaviorRuleEvolution /
 * V3 OptimizationEvents in-page-tab precedent.
 *
 * <p><strong>Personas</strong>: hardcoded mirror of
 * {@code application.yml::skillforge.eval.user-simulator.personas} (5 fixed
 * strings, ratify #4). Dogfood may adjust copy; if a future
 * {@code /api/eval/personas} endpoint lands, swap the constant for a
 * {@code useQuery} call without touching the panel.
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

  // Auto-select the first agent on first land — same UX precedent as V4
  // BehaviorRuleEvolution: operator's first action is always "pick agent",
  // so doing it for them shaves a click and shows a useful surface
  // immediately.
  React.useEffect(() => {
    if (selectedAgentId == null && agents.length > 0) {
      const firstId = agents[0].id;
      if (typeof firstId === 'number') {
        setSelectedAgentId(firstId);
      }
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
    <div
      style={{
        padding: 'var(--sp-6, 24px) var(--sp-8, 32px)',
        maxWidth: 1400,
        margin: '0 auto',
      }}
    >
      <div style={{ marginBottom: 20 }}>
        <Title level={3} style={{ marginBottom: 4 }}>
          Insights — Dynamic Sim Trials
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          Drive the user-simulator harness: pick a candidate (skill / prompt
          version) + scenario + persona(s), kick off a trial, and inspect the
          resulting transcript through the existing SessionDetail viewer.
          Trials persist as {`origin='user_sim'`} sessions, isolated from V1
          patterns / V2 canary metrics / V3 attribution. {' '}
          <strong>behavior_rule</strong> surface is unavailable in V5 (V5.1
          backlog — V4 AgentLoopEngine 7+1 红灯不可改).
        </Paragraph>
      </div>

      {isError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Failed to load agents."
          description="Cannot pick an agent without the list. Retry navigating, or check /api/agents."
        />
      )}

      <div
        style={{
          marginBottom: 16,
          display: 'flex',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>Agent:</span>
        <Select
          loading={isLoading}
          showSearch
          optionFilterProp="label"
          placeholder="Pick an agent"
          value={selectedAgentId ?? undefined}
          onChange={(v) => setSelectedAgentId(v ?? null)}
          options={agentOptions}
          style={{ minWidth: 280 }}
          allowClear
        />
        {selectedAgent && (
          <span style={{ fontSize: 11, color: 'var(--fg-4)' }}>
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
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>
                {agents.length === 0
                  ? 'No agents available — create one from the Agents page first.'
                  : 'Pick an agent to launch a dynamic-sim trial.'}
              </span>
            }
            style={{ marginTop: 32 }}
          />
        )
      )}
    </div>
  );
};

export default DynamicSim;
