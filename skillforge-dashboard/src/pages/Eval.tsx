import React, { useState, useEffect, useMemo } from 'react';
import {
  Card, Table, Tag, Button, Typography, Space, Progress, Drawer,
  Statistic, Row, Col, Empty, Spin, Select, message, Tooltip, Alert, Tabs,
} from 'antd';
import {
  PlayCircleOutlined, CheckCircleOutlined, CloseCircleOutlined,
  ClockCircleOutlined, ExperimentOutlined, WarningOutlined,
  ReloadOutlined, RiseOutlined, InfoCircleOutlined, DatabaseOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getEvalRuns, getEvalRun, triggerEvalRun, getAgents,
  getEvalScenarios, extractList,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import ImprovePromptButton from '../components/ImprovePromptButton';

const { Text } = Typography;

// ── helpers ──────────────────────────────────────────────────────────────────

function formatDuration(ms: number): string {
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)}m`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

function formatDateTime(iso: string): string {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function durationBetween(start: string, end: string): string {
  if (!start || !end) return '-';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  return ms > 0 ? formatDuration(ms) : '-';
}

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'blue',
  COMPLETED: 'green',
  FAILED: 'red',
};

const SCENARIO_STATUS_COLOR: Record<string, string> = {
  PASS: 'green',
  FAIL: 'red',
  TIMEOUT: 'orange',
  VETO: 'magenta',
  ERROR: 'volcano',
};

const ATTRIBUTION_COLOR: Record<string, string> = {
  NONE: 'green',
  SKILL_MISSING: 'gold',
  SKILL_EXECUTION_FAILURE: 'red',
  PROMPT_QUALITY: 'orange',
  CONTEXT_OVERFLOW: 'purple',
  PERFORMANCE: 'blue',
  VETO_EXCEPTION: 'magenta',
};

const ORACLE_TYPE_COLOR: Record<string, string> = {
  exact_match: 'blue',
  contains: 'cyan',
  regex: 'purple',
  llm_judge: 'gold',
};

function scoreColor(score: number): string {
  if (score >= 70) return 'var(--color-success)';
  if (score >= 40) return 'var(--color-warning)';
  return 'var(--color-error)';
}

// ── Scenario expandable row content ──────────────────────────────────────────

interface ScenarioResultRecord {
  scenarioId: string;
  status: string;
  compositeScore?: number;
  attribution?: string;
  loopCount?: number;
  executionTimeMs?: number;
  agentFinalOutput?: string;
  task?: string;
  oracleType?: string;
  oracleExpected?: string;
  oracleExpectedList?: string[];
  judgeRationale?: string;
  errorMessage?: string;
  inputTokens?: number;
  outputTokens?: number;
  improvementSuggestions?: string;
}

const ScenarioExpandedRow: React.FC<{ record: ScenarioResultRecord }> = ({ record }) => {
  const hasContent =
    record.task ||
    record.oracleExpected ||
    (record.oracleExpectedList && record.oracleExpectedList.length > 0) ||
    record.agentFinalOutput ||
    record.judgeRationale ||
    record.errorMessage ||
    record.inputTokens != null ||
    record.outputTokens != null ||
    record.improvementSuggestions;

  if (!hasContent) {
    return <Text type="secondary" style={{ fontSize: 11 }}>No additional details</Text>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '8px 0' }}>
      {/* Error alert — highest priority */}
      {record.errorMessage && (
        <Alert
          type="error"
          showIcon
          message="Run Error"
          description={<Text style={{ fontSize: 12, fontFamily: 'monospace' }}>{record.errorMessage}</Text>}
        />
      )}

      {/* Task */}
      {record.task && (
        <div>
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>Task:</Text>
          <pre style={{
            fontSize: 11, margin: 0, fontFamily: 'JetBrains Mono, Fira Code, monospace',
            whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            overflow: 'hidden',
            display: '-webkit-box',
            WebkitLineClamp: 3,
            WebkitBoxOrient: 'vertical' as const,
            background: 'var(--bg-surface)', padding: '6px 8px', borderRadius: 4,
            borderLeft: '2px solid var(--accent-primary)',
          }}>
            {record.task}
          </pre>
        </div>
      )}

      {/* Oracle expected */}
      {(record.oracleExpected || (record.oracleExpectedList && record.oracleExpectedList.length > 0)) && (
        <div>
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>
            Expected:
            {record.oracleType && (
              <Tag
                color={ORACLE_TYPE_COLOR[record.oracleType] ?? 'default'}
                style={{ fontSize: 10, marginLeft: 6, lineHeight: '16px', padding: '0 4px' }}
              >
                {record.oracleType}
              </Tag>
            )}
          </Text>
          {record.oracleExpected && (
            <Text code style={{ fontSize: 11, display: 'block' }}>{record.oracleExpected}</Text>
          )}
          {record.oracleExpectedList && record.oracleExpectedList.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {record.oracleExpectedList.map((item, i) => (
                <Text key={i} code style={{ fontSize: 11 }}>{item}</Text>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Agent final output */}
      {record.agentFinalOutput && (
        <div>
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>Agent Output:</Text>
          <pre style={{
            fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            maxHeight: 180, overflowY: 'auto',
            background: 'var(--bg-surface)', padding: '6px 8px', borderRadius: 4,
          }}>
            {record.agentFinalOutput}
          </pre>
        </div>
      )}

      {/* Judge rationale */}
      {record.judgeRationale && (
        <div>
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>
            <RiseOutlined style={{ marginRight: 4 }} />Judge Rationale:
          </Text>
          <pre style={{
            fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            maxHeight: 120, overflowY: 'auto',
            background: 'var(--bg-surface)', padding: '6px 8px', borderRadius: 4,
            borderLeft: '2px solid var(--color-warning)',
          }}>
            {record.judgeRationale}
          </pre>
        </div>
      )}

      {/* Improvement suggestions */}
      {record.improvementSuggestions && (
        <div>
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>
            <RiseOutlined style={{ marginRight: 4 }} />Improvement Suggestions:
          </Text>
          <pre style={{
            fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            maxHeight: 120, overflowY: 'auto',
            background: 'var(--bg-surface)', padding: '6px 8px', borderRadius: 4,
          }}>
            {record.improvementSuggestions}
          </pre>
        </div>
      )}

      {/* Token usage */}
      {(record.inputTokens != null || record.outputTokens != null) && (
        <div style={{ marginTop: 4 }}>
          <Text type="secondary" style={{ fontSize: 10 }}>
            Tokens:
            {record.inputTokens != null && (
              <> in <Text style={{ fontSize: 10 }}>{record.inputTokens.toLocaleString()}</Text></>
            )}
            {record.outputTokens != null && (
              <> · out <Text style={{ fontSize: 10 }}>{record.outputTokens.toLocaleString()}</Text></>
            )}
          </Text>
        </div>
      )}
    </div>
  );
};

// ── Detail Drawer ────────────────────────────────────────────────────────────

interface EvalDetailDrawerProps {
  evalRunId: string | null;
  open: boolean;
  onClose: () => void;
}

const EvalDetailDrawer: React.FC<EvalDetailDrawerProps> = ({ evalRunId, open, onClose }) => {
  const { data, isLoading } = useQuery({
    queryKey: ['eval-run', evalRunId],
    queryFn: () => evalRunId ? getEvalRun(evalRunId).then(res => res.data).catch(() => null) : null,
    enabled: !!evalRunId && open,
  });

  if (!evalRunId) return null;

  const run: any = data;
  const scenarios: ScenarioResultRecord[] = run?.scenarioResults ?? [];
  const passed = scenarios.filter((s) => s.status === 'PASS').length;
  const failed = scenarios.filter((s) => s.status === 'FAIL').length;
  const timeout = scenarios.filter((s) => s.status === 'TIMEOUT').length;
  const veto = scenarios.filter((s) => s.status === 'VETO').length;
  const total = scenarios.length;
  const passRate = total > 0 ? Math.round((passed / total) * 100) : 0;
  const avgScore = total > 0
    ? Math.round(scenarios.reduce((sum, s) => sum + (s.compositeScore ?? 0), 0) / total)
    : 0;

  // Attribution histogram
  const attrCounts: Record<string, number> = {};
  scenarios.forEach((s) => {
    const attr = s.attribution ?? 'NONE';
    attrCounts[attr] = (attrCounts[attr] ?? 0) + 1;
  });

  const scenarioColumns = [
    {
      title: 'Scenario',
      dataIndex: 'scenarioId',
      width: 160,
      ellipsis: true,
      render: (v: string) => <Text style={{ fontSize: 12, fontFamily: 'monospace' }}>{v}</Text>,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => <Tag color={SCENARIO_STATUS_COLOR[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: 'Oracle Score',
      dataIndex: 'compositeScore',
      width: 100,
      render: (v: number) => (
        <Text strong style={{ color: scoreColor(v ?? 0) }}>{v != null ? Math.round(v) : '-'}</Text>
      ),
    },
    {
      title: 'Attribution',
      dataIndex: 'attribution',
      width: 160,
      render: (v: string) => (
        <Tag color={ATTRIBUTION_COLOR[v] ?? 'default'} style={{ fontSize: 11 }}>
          {v ?? 'NONE'}
        </Tag>
      ),
    },
    {
      title: 'Loops',
      dataIndex: 'loopCount',
      width: 70,
      align: 'center' as const,
    },
    {
      title: 'Time',
      dataIndex: 'executionTimeMs',
      width: 80,
      render: (v: number) => v != null ? formatDuration(v) : '-',
    },
  ];

  return (
    <Drawer
      title={
        <Space>
          <ExperimentOutlined />
          <span>Eval Run Detail</span>
          {run && <Tag color={STATUS_COLOR[run.status] ?? 'default'}>{run.status}</Tag>}
        </Space>
      }
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {isLoading ? (
        <Spin style={{ display: 'block', margin: '80px auto' }} />
      ) : !run ? (
        <Empty description="Failed to load eval run" />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          {/* Run-level error — shown when the entire run failed before scenario execution */}
          {run.status === 'FAILED' && run.errorMessage && (
            <Alert
              type="error"
              showIcon
              message="Eval Run Failed"
              description={
                <Text style={{ fontSize: 12, fontFamily: 'monospace' }}>{run.errorMessage}</Text>
              }
            />
          )}

          {/* Summary stats */}
          <Row gutter={16}>
            <Col span={5}>
              <Statistic title="Pass Rate" value={passRate} suffix="%" valueStyle={{ color: scoreColor(passRate) }} />
            </Col>
            <Col span={5}>
              <Statistic title="Avg Score" value={avgScore} valueStyle={{ color: scoreColor(avgScore) }} />
            </Col>
            <Col span={3}>
              <Statistic title="Total" value={total} />
            </Col>
            <Col span={3}>
              <Statistic title="Passed" value={passed} valueStyle={{ color: 'var(--color-success)' }} />
            </Col>
            <Col span={3}>
              <Statistic title="Failed" value={failed} valueStyle={{ color: 'var(--color-error)' }} />
            </Col>
            <Col span={3}>
              <Statistic title="Timeout" value={timeout} valueStyle={{ color: 'var(--color-warning)' }} />
            </Col>
            <Col span={2}>
              <Statistic title="Veto" value={veto} valueStyle={{ color: 'var(--accent-primary)' }} />
            </Col>
          </Row>

          {/* Attribution histogram */}
          {Object.keys(attrCounts).length > 0 && (
            <Card size="small" title="Attribution Breakdown">
              <Space wrap>
                {Object.entries(attrCounts).map(([attr, count]) => (
                  <Tag key={attr} color={ATTRIBUTION_COLOR[attr] ?? 'default'} style={{ fontSize: 12 }}>
                    {attr}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          )}

          {/* Scenario results table */}
          <Table
            size="small"
            rowKey="scenarioId"
            dataSource={scenarios}
            columns={scenarioColumns}
            pagination={false}
            expandable={{
              expandedRowRender: (record: ScenarioResultRecord) => (
                <ScenarioExpandedRow record={record} />
              ),
            }}
          />

          {/* Prompt Improve action */}
          {run && (
            <ImprovePromptButton
              agentId={String(run.agentDefinitionId)}
              evalRun={{
                id: run.id ?? run.evalRunId,
                primaryAttribution: run.primaryAttribution,
                status: run.status,
              }}
            />
          )}
        </div>
      )}
    </Drawer>
  );
};

// ── Scenarios Panel ──────────────────────────────────────────────────────────

interface EvalScenario {
  id: string;
  name: string;
  category?: string;
  split?: string;
  task?: string;
  oracleType?: string;
  maxLoops?: number;
  tags?: string[];
  expected?: string;
  expectedList?: string[];
  setupFiles?: string[];
  toolsHint?: string;
  performanceThresholdMs?: number;
}

const ScenariosPanel: React.FC = () => {
  const { data: scenarios = [], isLoading, isError } = useQuery({
    queryKey: ['eval-scenarios'],
    queryFn: () => getEvalScenarios().then(res => extractList<EvalScenario>(res)),
  });

  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 160,
      ellipsis: true,
      render: (v: string) => (
        <Text style={{ fontSize: 11, fontFamily: 'JetBrains Mono, Fira Code, monospace' }}>{v}</Text>
      ),
    },
    {
      title: 'Name',
      dataIndex: 'name',
      width: 180,
      ellipsis: true,
      render: (v: string) => <Text style={{ fontSize: 12 }}>{v}</Text>,
    },
    {
      title: 'Category',
      dataIndex: 'category',
      width: 120,
      render: (v: string) => v ? <Tag style={{ fontSize: 11 }}>{v}</Tag> : <Text type="secondary">—</Text>,
    },
    {
      title: 'Split',
      dataIndex: 'split',
      width: 90,
      render: (v: string) => v ? (
        <Tag color={v === 'held_out' ? 'blue' : 'cyan'} style={{ fontSize: 11 }}>{v}</Tag>
      ) : <Text type="secondary">—</Text>,
    },
    {
      title: 'Task',
      dataIndex: 'task',
      ellipsis: true,
      render: (v: string) => (
        <Text style={{ fontSize: 11 }} type={v ? undefined : 'secondary'}>{v ?? '—'}</Text>
      ),
    },
    {
      title: 'Oracle Type',
      dataIndex: 'oracleType',
      width: 110,
      render: (v: string) => v ? (
        <Tag color={ORACLE_TYPE_COLOR[v] ?? 'default'} style={{ fontSize: 11 }}>{v}</Tag>
      ) : <Text type="secondary">—</Text>,
    },
    {
      title: 'Max Loops',
      dataIndex: 'maxLoops',
      width: 90,
      align: 'center' as const,
      render: (v: number) => <Text style={{ fontSize: 12 }}>{v ?? '—'}</Text>,
    },
    {
      title: 'Tags',
      dataIndex: 'tags',
      width: 160,
      render: (v: string[]) => v?.length ? (
        <Space size={2} wrap>
          {v.map(t => <Tag key={t} style={{ fontSize: 10, margin: 0 }}>{t}</Tag>)}
        </Space>
      ) : <Text type="secondary">—</Text>,
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        message={
          <Text style={{ fontSize: 12 }}>
            Scenario files are at{' '}
            <Text code style={{ fontSize: 11 }}>
              skillforge-server/src/main/resources/eval/scenarios/*.json
            </Text>
            {' '}— add new <Text code style={{ fontSize: 11 }}>.json</Text> files there and restart the server to include them in eval runs.
          </Text>
        }
      />

      {isError && (
        <Alert type="error" showIcon message="Failed to load scenarios. Is the server running?" />
      )}

      <Table
        size="small"
        rowKey="id"
        dataSource={scenarios}
        columns={columns}
        loading={isLoading}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        scroll={{ x: 1100 }}
        locale={{ emptyText: <Empty description="No scenarios loaded. Add .json files to the scenarios directory and restart." /> }}
        expandable={{
          expandedRowRender: (record: EvalScenario) => (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '8px 0' }}>
              {/* Full task text */}
              {record.task && (
                <div>
                  <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>Full Task:</Text>
                  <pre style={{
                    fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                    background: 'var(--bg-surface)', padding: '6px 8px', borderRadius: 4,
                    borderLeft: '2px solid var(--accent-primary)',
                    fontFamily: 'JetBrains Mono, Fira Code, monospace',
                  }}>
                    {record.task}
                  </pre>
                </div>
              )}

              {/* Oracle details */}
              {(record.expected || (record.expectedList && record.expectedList.length > 0)) && (
                <div>
                  <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>
                    Oracle Expected:
                    {record.oracleType && (
                      <Tag
                        color={ORACLE_TYPE_COLOR[record.oracleType] ?? 'default'}
                        style={{ fontSize: 10, marginLeft: 6, lineHeight: '16px', padding: '0 4px' }}
                      >
                        {record.oracleType}
                      </Tag>
                    )}
                  </Text>
                  {record.expected && (
                    <Text code style={{ fontSize: 11, display: 'block' }}>{record.expected}</Text>
                  )}
                  {record.expectedList && record.expectedList.length > 0 && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                      {record.expectedList.map((item, i) => (
                        <Text key={i} code style={{ fontSize: 11 }}>{item}</Text>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* Setup files */}
              {record.setupFiles && record.setupFiles.length > 0 && (
                <div>
                  <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>Setup Files:</Text>
                  <Space size={4} wrap>
                    {record.setupFiles.map((f, i) => (
                      <Tag key={i} style={{ fontSize: 11, fontFamily: 'monospace' }}>{f}</Tag>
                    ))}
                  </Space>
                </div>
              )}

              {/* Tools hint */}
              {record.toolsHint && (
                <div>
                  <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 3 }}>Tools Hint:</Text>
                  <Text code style={{ fontSize: 11 }}>{record.toolsHint}</Text>
                </div>
              )}

              {/* Performance threshold */}
              {record.performanceThresholdMs != null && (
                <div>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    Performance Threshold: <Text style={{ fontSize: 11 }}>{formatDuration(record.performanceThresholdMs)}</Text>
                  </Text>
                </div>
              )}
            </div>
          ),
        }}
      />
    </div>
  );
};

// ── Main page ────────────────────────────────────────────────────────────────

const Eval: React.FC = () => {
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [drawerRunId, setDrawerRunId] = useState<string | null>(null);
  const [triggerAgent, setTriggerAgent] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);

  // Fetch eval runs — auto-refresh every 3s while any run is RUNNING
  const { data: runs = [], isLoading, isError } = useQuery({
    queryKey: ['eval-runs'],
    queryFn: () => getEvalRuns().then(res => extractList<any>(res)),
    refetchInterval: (query) => {
      const data = query.state.data as any[] | undefined;
      return data?.some((r: any) => r.status === 'RUNNING') ? 3000 : false;
    },
  });

  // Fetch agents for the trigger select
  const { data: agents = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then(res => extractList<any>(res)),
  });

  const hasRunningRuns = useMemo(
    () => runs.some((r: any) => r.status === 'RUNNING'),
    [runs],
  );

  // WebSocket for real-time updates
  useEffect(() => {
    const token = localStorage.getItem('sf_token') ?? '';
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(url);

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'eval_scenario_finished') {
          queryClient.invalidateQueries({ queryKey: ['eval-runs'] });
          if (msg.evalRunId) {
            queryClient.invalidateQueries({ queryKey: ['eval-run', msg.evalRunId] });
          }
        }
      } catch {
        // ignore
      }
    };

    return () => { ws.close(); };
  }, [queryClient, userId]);

  useEffect(() => {
    if (isError) message.error('Failed to load eval runs');
  }, [isError]);

  const handleTrigger = async () => {
    if (!triggerAgent) {
      message.warning('Select an agent first');
      return;
    }
    setTriggering(true);
    try {
      const res = await triggerEvalRun(triggerAgent);
      const evalRunId = res.data?.id ?? res.data?.evalRunId ?? '(unknown)';
      message.success(`Eval started, ID: ${evalRunId}`);
      queryClient.invalidateQueries({ queryKey: ['eval-runs'] });
    } catch (err: unknown) {
      const detail =
        (err as any)?.response?.data?.error ??
        (err as any)?.response?.data?.message ??
        (err as any)?.message ??
        'Unknown error';
      message.error(`Failed to trigger eval run: ${detail}`);
    } finally {
      setTriggering(false);
    }
  };

  const columns = [
    {
      title: 'Status',
      dataIndex: 'status',
      width: 100,
      render: (v: string) => {
        const icon = v === 'RUNNING' ? <ClockCircleOutlined />
          : v === 'COMPLETED' ? <CheckCircleOutlined />
          : v === 'FAILED' ? <CloseCircleOutlined /> : undefined;
        return <Tag color={STATUS_COLOR[v] ?? 'default'} icon={icon}>{v}</Tag>;
      },
    },
    {
      title: 'Agent',
      dataIndex: 'agentDefinitionId',
      width: 140,
      render: (v: string) => {
        const agent = agents.find((a: any) => String(a.id) === String(v));
        return <Text style={{ fontSize: 12 }}>{agent?.name ?? v}</Text>;
      },
    },
    {
      title: 'Pass Rate',
      dataIndex: 'overallPassRate',
      width: 140,
      render: (v: number) => {
        const pct = typeof v === 'number' ? Math.round(v) : 0;
        return <Progress percent={pct} size="small" strokeColor={scoreColor(pct)} />;
      },
    },
    {
      title: 'Avg Score',
      dataIndex: 'avgOracleScore',
      width: 90,
      align: 'center' as const,
      render: (v: number) => (
        <Text strong style={{ color: scoreColor(v ?? 0) }}>{v != null ? Math.round(v) : '-'}</Text>
      ),
    },
    {
      title: 'Scenarios',
      width: 90,
      align: 'center' as const,
      render: (_: unknown, r: any) => {
        const passed = r.passedScenarios ?? 0;
        const total = r.totalScenarios ?? 0;
        return <Text style={{ fontSize: 12 }}>{passed}/{total}</Text>;
      },
    },
    {
      title: 'Attribution',
      dataIndex: 'primaryAttribution',
      width: 160,
      render: (v: string) => v ? (
        <Tag color={ATTRIBUTION_COLOR[v] ?? 'default'} style={{ fontSize: 11 }}>{v}</Tag>
      ) : <Text type="secondary">-</Text>,
    },
    {
      title: 'Started',
      dataIndex: 'startedAt',
      width: 130,
      render: (v: string) => <Text style={{ fontSize: 11 }}>{formatDateTime(v)}</Text>,
    },
    {
      title: 'Duration',
      width: 80,
      render: (_: unknown, r: any) => (
        <Tag icon={<ClockCircleOutlined />} style={{ margin: 0, fontSize: 11 }}>
          {r.completedAt ? durationBetween(r.startedAt, r.completedAt) : r.status === 'RUNNING' ? '...' : '-'}
        </Tag>
      ),
    },
    {
      title: 'Action',
      width: 90,
      render: (_: unknown, r: any) => (
        <Button
          size="small"
          type="link"
          icon={<ExperimentOutlined />}
          onClick={() => setDrawerRunId(r.id ?? r.evalRunId)}
        >
          Detail
        </Button>
      ),
    },
  ];

  const storageInfo = (
    <Tooltip
      title={
        <div style={{ fontSize: 11 }}>
          Results stored in PostgreSQL <Text code style={{ fontSize: 11, color: 'var(--text-muted)' }}>t_eval_run</Text>
          <br />
          Scenarios loaded from classpath <Text code style={{ fontSize: 11, color: 'var(--text-muted)' }}>eval/scenarios/</Text>
        </div>
      }
    >
      <Space style={{ cursor: 'default', opacity: 0.65 }} size={4}>
        <DatabaseOutlined style={{ fontSize: 12 }} />
        <Text style={{ fontSize: 11 }}>Storage info</Text>
        <InfoCircleOutlined style={{ fontSize: 11 }} />
      </Space>
    </Tooltip>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: '100%' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <ExperimentOutlined style={{ fontSize: 20 }} />
          <Text strong style={{ fontSize: 16 }}>Eval Pipeline</Text>
          {hasRunningRuns && (
            <Tag color="processing" icon={<ClockCircleOutlined />}>Running</Tag>
          )}
          {storageInfo}
        </Space>
        <Space>
          <Select
            placeholder="Select agent"
            style={{ width: 200 }}
            value={triggerAgent}
            onChange={setTriggerAgent}
            options={agents.map((a: any) => ({
              value: String(a.id),
              label: a.name ?? `Agent #${a.id}`,
            }))}
            allowClear
          />
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            loading={triggering}
            onClick={handleTrigger}
            disabled={!triggerAgent}
          >
            Run Eval
          </Button>
          <Tooltip title="Refresh">
            <Button
              icon={<ReloadOutlined />}
              onClick={() => queryClient.invalidateQueries({ queryKey: ['eval-runs'] })}
            />
          </Tooltip>
        </Space>
      </div>

      {/* Tabs: Runs + Scenarios */}
      <Tabs
        defaultActiveKey="runs"
        style={{ flex: 1 }}
        items={[
          {
            key: 'runs',
            label: (
              <Space size={6}>
                <ClockCircleOutlined />
                Runs
                {runs.length > 0 && (
                  <Tag style={{ margin: 0, fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>
                    {runs.length}
                  </Tag>
                )}
              </Space>
            ),
            children: (
              <Card
                styles={{ body: { padding: 0 } }}
                style={{ overflow: 'hidden' }}
              >
                <Table
                  size="small"
                  rowKey={(r: any) => r.id ?? r.evalRunId}
                  dataSource={runs}
                  columns={columns}
                  loading={isLoading}
                  pagination={{ pageSize: 20, showSizeChanger: false }}
                  scroll={{ x: 1000 }}
                  locale={{ emptyText: <Empty description="No eval runs yet. Select an agent and click Run Eval." /> }}
                />
              </Card>
            ),
          },
          {
            key: 'scenarios',
            label: (
              <Space size={6}>
                <WarningOutlined />
                Scenarios
              </Space>
            ),
            children: <ScenariosPanel />,
          },
        ]}
      />

      {/* Detail drawer */}
      <EvalDetailDrawer
        evalRunId={drawerRunId}
        open={!!drawerRunId}
        onClose={() => setDrawerRunId(null)}
      />
    </div>
  );
};

export default Eval;
