import React, { useState, useEffect, useMemo } from 'react';
import {
  Card, Table, Tag, Button, Typography, Space, Progress, Drawer,
  Statistic, Row, Col, Empty, Spin, Select, message, Tooltip, Alert,
} from 'antd';
import {
  PlayCircleOutlined, CheckCircleOutlined, CloseCircleOutlined,
  ClockCircleOutlined, ExperimentOutlined, WarningOutlined,
  ReloadOutlined, RiseOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getEvalRuns, getEvalRun, triggerEvalRun, getAgents, extractList } from '../api';

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

function scoreColor(score: number): string {
  if (score >= 70) return '#52c41a';
  if (score >= 40) return '#faad14';
  return '#ff4d4f';
}

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
  const scenarios: any[] = run?.scenarioResults ?? [];
  const passed = scenarios.filter((s: any) => s.status === 'PASS').length;
  const failed = scenarios.filter((s: any) => s.status === 'FAIL').length;
  const timeout = scenarios.filter((s: any) => s.status === 'TIMEOUT').length;
  const veto = scenarios.filter((s: any) => s.status === 'VETO').length;
  const total = scenarios.length;
  const passRate = total > 0 ? Math.round((passed / total) * 100) : 0;
  const avgScore = total > 0
    ? Math.round(scenarios.reduce((sum: number, s: any) => sum + (s.oracleScore ?? 0), 0) / total)
    : 0;

  // Attribution histogram
  const attrCounts: Record<string, number> = {};
  scenarios.forEach((s: any) => {
    const attr = s.primaryAttribution ?? 'NONE';
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
      dataIndex: 'oracleScore',
      width: 100,
      render: (v: number) => (
        <Text strong style={{ color: scoreColor(v ?? 0) }}>{v ?? '-'}</Text>
      ),
    },
    {
      title: 'Attribution',
      dataIndex: 'primaryAttribution',
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
              <Statistic title="Passed" value={passed} valueStyle={{ color: '#52c41a' }} />
            </Col>
            <Col span={3}>
              <Statistic title="Failed" value={failed} valueStyle={{ color: '#ff4d4f' }} />
            </Col>
            <Col span={3}>
              <Statistic title="Timeout" value={timeout} valueStyle={{ color: '#faad14' }} />
            </Col>
            <Col span={2}>
              <Statistic title="Veto" value={veto} valueStyle={{ color: '#eb2f96' }} />
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
              expandedRowRender: (record: any) => (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 0' }}>
                  {record.agentFinalOutput && (
                    <div>
                      <Text type="secondary" style={{ fontSize: 11 }}>Agent Output:</Text>
                      <pre style={{
                        fontSize: 11, margin: '4px 0 0', whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word', maxHeight: 200, overflowY: 'auto',
                        background: 'var(--bg-surface, #fafafa)', padding: 8, borderRadius: 4,
                      }}>
                        {record.agentFinalOutput}
                      </pre>
                    </div>
                  )}
                  {record.improvementSuggestions && (
                    <div>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        <RiseOutlined /> Improvement Suggestions:
                      </Text>
                      <pre style={{
                        fontSize: 11, margin: '4px 0 0', whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word', maxHeight: 200, overflowY: 'auto',
                        background: 'var(--bg-surface, #fafafa)', padding: 8, borderRadius: 4,
                      }}>
                        {record.improvementSuggestions}
                      </pre>
                    </div>
                  )}
                  {!record.agentFinalOutput && !record.improvementSuggestions && (
                    <Text type="secondary" style={{ fontSize: 11 }}>No additional details</Text>
                  )}
                </div>
              ),
            }}
          />
        </div>
      )}
    </Drawer>
  );
};

// ── Main page ────────────────────────────────────────────────────────────────

const Eval: React.FC = () => {
  const queryClient = useQueryClient();
  const [drawerRunId, setDrawerRunId] = useState<string | null>(null);
  const [triggerAgent, setTriggerAgent] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);

  // Fetch eval runs
  const { data: runs = [], isLoading, isError } = useQuery({
    queryKey: ['eval-runs'],
    queryFn: () => getEvalRuns().then(res => extractList<any>(res)),
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

  // Auto-refresh when runs are in progress
  useQuery({
    queryKey: ['eval-runs-poll'],
    queryFn: () => getEvalRuns().then(res => {
      const list = extractList<any>(res);
      queryClient.setQueryData(['eval-runs'], list);
      return list;
    }),
    refetchInterval: hasRunningRuns ? 3000 : false,
    enabled: hasRunningRuns,
  });

  // WebSocket for real-time updates
  useEffect(() => {
    const token = localStorage.getItem('sf_token') ?? '';
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${window.location.host}/ws/users/1?token=${encodeURIComponent(token)}`;
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
  }, [queryClient]);

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
    } catch {
      message.error('Failed to trigger eval run');
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
      dataIndex: 'agentId',
      width: 140,
      render: (v: string) => {
        const agent = agents.find((a: any) => String(a.id) === String(v));
        return <Text style={{ fontSize: 12 }}>{agent?.name ?? v}</Text>;
      },
    },
    {
      title: 'Pass Rate',
      dataIndex: 'passRate',
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
        const passed = r.passedCount ?? 0;
        const total = r.totalCount ?? 0;
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

      {/* Runs table */}
      <Card
        styles={{ body: { padding: 0 } }}
        style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}
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
