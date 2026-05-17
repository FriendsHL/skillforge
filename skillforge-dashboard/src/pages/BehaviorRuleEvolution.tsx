import React, { useMemo, useState } from 'react';
import { Typography, Select, Alert, Empty } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { getAgents, extractList } from '../api';
import { AgentSchema, safeParseList, type AgentDto } from '../api/schemas';
import BehaviorRuleEvolutionPanel from '../components/behaviorRules/BehaviorRuleEvolutionPanel';

const { Title, Paragraph } = Typography;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.4 — `/insights` Behavior Rules tab.
 *
 * <p>Composes the agent picker + {@link BehaviorRuleEvolutionPanel}. State
 * lives in this component (selected agent id); the panel is dumb-presentational.
 *
 * <p>Rendered inside {@code Insights.tsx} as a tab — no standalone route is
 * added to {@code App.tsx} because Phase 1.4 inherits the
 * {@code /insights/patterns} URL and the {@code OptimizationEvents} precedent
 * is the same in-page tab pattern.
 *
 * <p>If the agents list is empty (single-tenant dogfood with no agents
 * created), the panel falls back to an Empty state rather than blank canvas.
 */
const BehaviorRuleEvolution: React.FC = () => {
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

  // Auto-select the first agent when the list lands and nothing's selected
  // yet — the operator's first action is always "pick an agent", so doing
  // it for them on initial render saves a click and shows a useful surface
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
          Insights — Behavior Rule Evolution
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          Inspect the active {`behavior_rule`} version of each agent, review pending candidates
          generated via attribution / auto_improve, and drive canary rollouts (start / step-up /
          publish / rollback) for the third optimizable surface introduced in V4 multi-surface
          flywheel.
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

      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
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
        <BehaviorRuleEvolutionPanel
          // BE column is VARCHAR(36) (UUID-style) but FE AgentDto.id is a
          // number. We stringify for the API param (BehaviorRuleVersionEntity
          // matches on the string form). FLYWHEEL-LOOP-CLOSURE Phase 1.5
          // (2026-05-16) — `agentNumericId` no longer needed since the
          // embedded CanaryPanel was removed.
          agentId={String(selectedAgent.id)}
        />
      ) : (
        !isLoading && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>
                {agents.length === 0
                  ? 'No agents available — create one from the Agents page first.'
                  : 'Pick an agent to view its behavior_rule evolution and canary state.'}
              </span>
            }
            style={{ marginTop: 32 }}
          />
        )
      )}
    </div>
  );
};

export default BehaviorRuleEvolution;
