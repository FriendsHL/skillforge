import React, { useState, useEffect } from 'react';
import { Card, Table, Tag, Typography, Space, Tabs, Empty, Spin, Tooltip, Progress, Collapse, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  RobotOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { getCollabRuns, getCollabRunMembers, getCollabRunTraces, getCollabRunSummary, extractList } from '../api';

const { Text } = Typography;

const USER_ID = 1; // MVP: single-user, matches SessionList pattern

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

function formatTokens(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'blue',
  COMPLETED: 'green',
  CANCELLED: 'default',
  FAILED: 'red',
};

const SPAN_TYPE_COLOR: Record<string, string> = {
  AGENT_LOOP: 'blue',
  LLM_CALL: 'purple',
  TOOL_CALL: 'green',
  ASK_USER: 'orange',
};

// 10 distinct colors for agent handles in the waterfall
const AGENT_COLORS = [
  '#1677ff', '#52c41a', '#fa8c16', '#722ed1', '#13c2c2',
  '#eb2f96', '#faad14', '#a0d911', '#2f54eb', '#f5222d',
];

// ── Member panel ──────────────────────────────────────────────────────────────

const MembersPanel: React.FC<{ collabRunId: string }> = ({ collabRunId }) => {
  const { data, isLoading: loading } = useQuery({
    queryKey: ['collab-run', collabRunId, 'members'],
    queryFn: () => getCollabRunMembers(collabRunId).then((res) => res.data).catch(() => null),
  });

  if (loading) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (!data) return <Empty description="Failed to load" />;

  const members: any[] = data.members ?? [];

  const statusDot = (status: string) => {
    const colorMap: Record<string, string> = {
      running: '#1890ff', idle: '#52c41a', error: '#ff4d4f', waiting_user: '#faad14',
    };
    return (
      <span
        style={{
          display: 'inline-block', width: 8, height: 8, borderRadius: '50%',
          background: colorMap[status] ?? '#bfbfbf', marginRight: 6,
        }}
      />
    );
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {members.map((m: any, idx: number) => (
        <Card
          key={m.sessionId}
          size="small"
          style={{ borderLeft: `3px solid ${AGENT_COLORS[idx % AGENT_COLORS.length]}` }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <Tag color="blue" icon={<TeamOutlined />} style={{ margin: 0 }}>
              @{m.handle ?? '?'}
            </Tag>
            <Text strong style={{ fontSize: 13 }}>{m.title || m.agentId}</Text>
            <span>{statusDot(m.runtimeStatus)}<Text type="secondary" style={{ fontSize: 11 }}>{m.runtimeStatus}</Text></span>
            <Tag style={{ margin: 0, fontSize: 10 }}>depth {m.depth ?? 0}</Tag>
            <Text type="secondary" style={{ fontSize: 10, marginLeft: 'auto' }}>
              {String(m.sessionId).slice(0, 8)}
            </Text>
          </div>
        </Card>
      ))}
      {members.length === 0 && <Empty description="No members" />}
    </div>
  );
};

// ── Summary panel ─────────────────────────────────────────────────────────────

const SummaryPanel: React.FC<{ collabRunId: string }> = ({ collabRunId }) => {
  const { data, isLoading: loading } = useQuery({
    queryKey: ['collab-run', collabRunId, 'summary'],
    queryFn: () => getCollabRunSummary(collabRunId).then((res) => res.data).catch(() => null),
  });

  if (loading) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (!data) return <Empty description="Failed to load" />;

  const members: any[] = data.members ?? [];
  const totalTokens = (data.totalInputTokens ?? 0) + (data.totalOutputTokens ?? 0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Overall stats */}
      <Card size="small" title="Overall">
        <Space size={24} wrap>
          <div><Text type="secondary">Members</Text><br /><Text strong>{data.memberCount}</Text></div>
          <div><Text type="secondary">Duration</Text><br /><Text strong>{formatDuration(data.durationMs ?? 0)}</Text></div>
          <div><Text type="secondary">Total Tokens</Text><br /><Text strong>{formatTokens(totalTokens)}</Text></div>
          <div><Text type="secondary">LLM Calls</Text><br /><Text strong>{data.totalLlmCalls ?? 0}</Text></div>
          <div><Text type="secondary">Tool Calls</Text><br /><Text strong>{data.totalToolCalls ?? 0}</Text></div>
          <div><Text type="secondary">Peer Msgs</Text><br /><Text strong>{data.totalPeerMessages ?? 0}</Text></div>
        </Space>
      </Card>

      {/* Per-member breakdown */}
      {members.map((m: any, idx: number) => {
        const memberTokens = (m.inputTokens ?? 0) + (m.outputTokens ?? 0);
        const pct = totalTokens > 0 ? Math.round((memberTokens / totalTokens) * 100) : 0;
        return (
          <Card
            key={m.sessionId}
            size="small"
            style={{ borderLeft: `3px solid ${AGENT_COLORS[idx % AGENT_COLORS.length]}` }}
            title={
              <Space>
                <Tag color="blue">@{m.handle ?? '?'}</Tag>
                <Text style={{ fontSize: 12 }}>{m.agentName ?? m.sessionId}</Text>
              </Space>
            }
          >
            <Progress percent={pct} size="small" style={{ marginBottom: 8 }} />
            <Space size={16} wrap>
              <Text style={{ fontSize: 11 }}><Text type="secondary">tok: </Text>{formatTokens(memberTokens)}</Text>
              <Text style={{ fontSize: 11 }}><Text type="secondary">llm: </Text>{m.llmCalls ?? 0}</Text>
              <Text style={{ fontSize: 11 }}><Text type="secondary">tools: </Text>{m.toolCalls ?? 0}</Text>
              <Text style={{ fontSize: 11 }}><Text type="secondary">msgs: </Text>{m.peerMessages ?? 0}</Text>
              <Text style={{ fontSize: 11 }}><Text type="secondary">dur: </Text>{formatDuration(m.durationMs ?? 0)}</Text>
            </Space>
          </Card>
        );
      })}
    </div>
  );
};

// ── Traces panel (multi-agent waterfall) ──────────────────────────────────────

const TracesPanel: React.FC<{ collabRunId: string }> = ({ collabRunId }) => {
  const { data, isLoading: loading } = useQuery({
    queryKey: ['collab-run', collabRunId, 'traces'],
    queryFn: () => getCollabRunTraces(collabRunId).then((res) => res.data).catch(() => null),
  });

  if (loading) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (!data) return <Empty description="Failed to load" />;

  const spans: any[] = data.spans ?? [];
  if (spans.length === 0) return <Empty description="No spans yet" />;

  // Build handle→color mapping
  const handles: string[] = [];
  spans.forEach((s) => {
    if (s.handle && !handles.includes(s.handle)) handles.push(s.handle);
  });
  const handleColor = (handle: string) => AGENT_COLORS[handles.indexOf(handle) % AGENT_COLORS.length];

  const rootStart = Math.min(...spans.map((s) => new Date(s.startTime).getTime()));
  const rootEnd = Math.max(...spans.map((s) => new Date(s.endTime || s.startTime).getTime() + (s.durationMs ?? 0)));
  const totalDuration = rootEnd - rootStart || 1;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {/* Legend */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
        {handles.map((h) => (
          <Tag key={h} color="blue" icon={<TeamOutlined />} style={{ borderLeft: `3px solid ${handleColor(h)}` }}>
            @{h}
          </Tag>
        ))}
      </div>

      {spans.map((span: any) => {
        const spanStart = new Date(span.startTime).getTime();
        const offsetPct = ((spanStart - rootStart) / totalDuration) * 100;
        const widthPct = Math.max((span.durationMs / totalDuration) * 100, 0.3);
        const color = span.handle ? handleColor(span.handle) : '#bfbfbf';

        const collapseItems = [];
        if (span.input) {
          collapseItems.push({
            key: 'input',
            label: <Text type="secondary" style={{ fontSize: 11 }}>Input</Text>,
            children: (
              <div style={{ maxHeight: 200, overflowY: 'auto' }}>
                <pre style={{ fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {span.input}
                </pre>
              </div>
            ),
          });
        }
        if (span.output) {
          collapseItems.push({
            key: 'output',
            label: <Text type="secondary" style={{ fontSize: 11 }}>Output</Text>,
            children: (
              <div style={{ maxHeight: 200, overflowY: 'auto' }}>
                <pre style={{ fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {span.output}
                </pre>
              </div>
            ),
          });
        }

        return (
          <div key={span.id} style={{ borderBottom: '1px solid #f5f5f5', padding: '4px 0' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 3 }}>
              {span.handle && (
                <Tag style={{ margin: 0, fontSize: 10, borderColor: color, color }} icon={<TeamOutlined />}>
                  @{span.handle}
                </Tag>
              )}
              <Tag color={SPAN_TYPE_COLOR[span.spanType] ?? 'default'} style={{ margin: 0, fontSize: 10 }}>
                {span.spanType}
              </Tag>
              <Text style={{ fontSize: 11 }}>{span.name}</Text>
              {span.success
                ? <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 10 }} />
                : <CloseCircleOutlined style={{ color: '#ff4d4f', fontSize: 10 }} />}
              {(span.inputTokens > 0 || span.outputTokens > 0) && (
                <Tag style={{ margin: 0, fontSize: 10 }}>
                  {formatTokens((span.inputTokens ?? 0) + (span.outputTokens ?? 0))} tok
                </Tag>
              )}
              <Text type="secondary" style={{ fontSize: 10, marginLeft: 'auto' }}>
                {formatDuration(span.durationMs ?? 0)}
              </Text>
            </div>
            {/* Waterfall bar */}
            <div style={{ height: 6, background: '#f5f5f5', borderRadius: 3, position: 'relative', marginBottom: 3 }}>
              <Tooltip title={`@${span.handle ?? '?'} · ${span.spanType} · ${formatDuration(span.durationMs ?? 0)}`}>
                <div
                  style={{
                    position: 'absolute',
                    left: `${Math.min(offsetPct, 99)}%`,
                    width: `${Math.max(widthPct, 0.3)}%`,
                    height: '100%',
                    borderRadius: 3,
                    background: span.success ? color : '#ff7875',
                    opacity: 0.75,
                  }}
                />
              </Tooltip>
            </div>
            {/* Error */}
            {span.error && (
              <Text type="danger" style={{ fontSize: 11 }}>Error: {span.error}</Text>
            )}
            {/* Collapsible I/O */}
            {collapseItems.length > 0 && (
              <Collapse size="small" ghost items={collapseItems} />
            )}
          </div>
        );
      })}
    </div>
  );
};

// ── Main page ─────────────────────────────────────────────────────────────────

const Teams: React.FC = () => {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const queryClient = useQueryClient();

  useEffect(() => {
    const token = localStorage.getItem('sf_token') ?? '';
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${window.location.host}/ws/users/${USER_ID}?token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(url);

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        const type: string = msg.type ?? '';
        const runId: string | undefined = msg.collabRunId;

        if (type === 'collab_run_status' || type === 'collab_member_spawned' || type === 'collab_member_finished') {
          queryClient.invalidateQueries({ queryKey: ['collab-runs'] });
          if (runId) {
            queryClient.invalidateQueries({ queryKey: ['collab-run', runId, 'members'] });
            queryClient.invalidateQueries({ queryKey: ['collab-run', runId, 'summary'] });
          }
        } else if (type === 'collab_message_routed') {
          if (runId) {
            queryClient.invalidateQueries({ queryKey: ['collab-run', runId, 'traces'] });
            queryClient.invalidateQueries({ queryKey: ['collab-run', runId, 'summary'] });
          }
        }
      } catch {
        // ignore unparseable messages
      }
    };

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    ws.onerror = (_e) => { /* silent — Teams page is not chat-critical */ };

    return () => { ws.close(); };
  }, [queryClient]);

  const { data: runs = [], isLoading: loading, isError: runsError } = useQuery({
    queryKey: ['collab-runs'],
    queryFn: () =>
      getCollabRuns().then((res) => extractList<any>(res)),
  });
  useEffect(() => {
    if (runsError) message.error('Failed to load collab runs');
  }, [runsError]);

  const columns = [
    {
      title: 'ID',
      dataIndex: 'collabRunId',
      width: 160,
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v}>
          <Text style={{ fontSize: 11, fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{v.slice(0, 16)}…</Text>
        </Tooltip>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => <Tag color={STATUS_COLOR[v] ?? 'default'} style={{ margin: 0 }}>{v}</Tag>,
    },
    {
      title: 'Members',
      dataIndex: 'memberCount',
      width: 90,
      align: 'center' as const,
      render: (v: number) => <Tag icon={<TeamOutlined />} style={{ margin: 0 }}>{v}</Tag>,
    },
    {
      title: 'Duration',
      dataIndex: 'durationMs',
      width: 80,
      render: (v: number) => <Tag icon={<ClockCircleOutlined />} style={{ margin: 0 }}>{formatDuration(v)}</Tag>,
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      width: 130,
      render: (v: string) => <Text style={{ fontSize: 11 }}>{formatDateTime(v)}</Text>,
    },
  ];

  const selectedRun = runs.find((r) => r.collabRunId === selectedId);

  return (
    <div style={{ display: 'flex', height: '100%', gap: 16, overflow: 'hidden' }}>
      {/* Left: run list */}
      <Card
        title={<Space><TeamOutlined /><span>Agent Teams</span></Space>}
        style={{ width: 400, flexShrink: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, padding: 0, overflow: 'auto' } }}
      >
        <Table
          size="small"
          rowKey="collabRunId"
          dataSource={runs}
          columns={columns}
          loading={loading}
          virtual
          scroll={{ y: 500 }}
          pagination={false}
          onRow={(record) => ({
            onClick: () => setSelectedId(record.collabRunId),
            style: {
              cursor: 'pointer',
              background: selectedId === record.collabRunId ? '#e6f4ff' : undefined,
            },
          })}
          locale={{ emptyText: <Empty description="No collab runs yet. Ask an agent to use TeamCreate." /> }}
        />
      </Card>

      {/* Right: detail */}
      <Card
        title={
          selectedRun ? (
            <Space>
              <ThunderboltOutlined />
              <Text style={{ fontSize: 13 }}>{selectedRun.collabRunId.slice(0, 16)}…</Text>
              <Tag color={STATUS_COLOR[selectedRun.status] ?? 'default'}>{selectedRun.status}</Tag>
              <Tag icon={<TeamOutlined />}>{selectedRun.memberCount} agents</Tag>
              <Tag icon={<ClockCircleOutlined />}>{formatDuration(selectedRun.durationMs)}</Tag>
            </Space>
          ) : (
            'Team Detail'
          )
        }
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, padding: '12px', overflow: 'auto' } }}
      >
        {!selectedId ? (
          <Empty description="Click a team run to view details" style={{ marginTop: 60 }} />
        ) : (
          <Tabs
            items={[
              {
                key: 'members',
                label: <Space><RobotOutlined />Members</Space>,
                children: <MembersPanel collabRunId={selectedId} />,
              },
              {
                key: 'traces',
                label: <Space><ThunderboltOutlined />Traces</Space>,
                children: <TracesPanel collabRunId={selectedId} />,
              },
              {
                key: 'summary',
                label: <Space><ToolOutlined />Summary</Space>,
                children: <SummaryPanel collabRunId={selectedId} />,
              },
            ]}
          />
        )}
      </Card>
    </div>
  );
};

export default Teams;
