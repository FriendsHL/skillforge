import React, { useState } from 'react';
import { useDebounce } from '../hooks/useDebounce';
import { Card, Table, Tag, Typography, Space, Collapse, Tooltip, Empty, Spin, Input, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  RobotOutlined,
  ToolOutlined,
  ApiOutlined,
  QuestionCircleOutlined,
  CompressOutlined,
  ThunderboltOutlined,
  SearchOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import { getTraces, getTraceSpans, extractList } from '../api';

const { Text } = Typography;

interface TraceItem {
  traceId: string;
  sessionId: string;
  name: string;
  input: string;
  output: string;
  startTime: string;
  endTime: string;
  durationMs: number;
  inputTokens: number;
  outputTokens: number;
  modelId: string;
  success: boolean;
  error: string | null;
  llmCallCount: number;
  toolCallCount: number;
}

interface SpanItem {
  id: string;
  sessionId: string;
  parentSpanId: string | null;
  spanType: string;
  name: string;
  input: string;
  output: string;
  startTime: string;
  endTime: string;
  durationMs: number;
  iterationIndex: number;
  inputTokens: number;
  outputTokens: number;
  modelId: string;
  success: boolean;
  error: string | null;
}

function formatDuration(ms: number): string {
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)}m`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

function formatTokens(input: number, output: number): string {
  const total = input + output;
  if (total >= 1000) return `${(total / 1000).toFixed(1)}k`;
  return `${total}`;
}

function formatTime(iso: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatDateTime(iso: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

const spanTypeConfig: Record<string, { color: string; icon: React.ReactNode; label: string }> = {
  AGENT_LOOP: { color: 'blue', icon: <ThunderboltOutlined />, label: 'Agent Loop' },
  LLM_CALL: { color: 'purple', icon: <RobotOutlined />, label: 'LLM Call' },
  TOOL_CALL: { color: 'green', icon: <ToolOutlined />, label: 'Tool Call' },
  ASK_USER: { color: 'orange', icon: <QuestionCircleOutlined />, label: 'Ask User' },
  COMPACT: { color: 'cyan', icon: <CompressOutlined />, label: 'Compact' },
  LIFECYCLE_HOOK: { color: 'magenta', icon: <ApiOutlined />, label: 'Lifecycle Hook' },
};

const SPAN_TYPE_FILTER_OPTIONS = Object.entries(spanTypeConfig).map(([key, cfg]) => ({
  label: cfg.label,
  value: key,
}));

const SpanWaterfall: React.FC<{
  spans: SpanItem[];
  rootDurationMs: number;
  rootStartMs: number;
  spanTypeFilter: string[];
}> = ({
  spans,
  rootDurationMs,
  rootStartMs,
  spanTypeFilter,
}) => {
  const filtered = spanTypeFilter.length > 0
    ? spans.filter((s) => spanTypeFilter.includes(s.spanType))
    : spans;
  if (filtered.length === 0) return <Empty description="No spans" />;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {filtered.map((span) => {
        const cfg = spanTypeConfig[span.spanType] ?? { color: 'default', icon: null, label: span.spanType };
        const spanStartMs = new Date(span.startTime).getTime();
        const offsetPct = rootDurationMs > 0 ? ((spanStartMs - rootStartMs) / rootDurationMs) * 100 : 0;
        const widthPct = rootDurationMs > 0 ? (span.durationMs / rootDurationMs) * 100 : 100;

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
          <div key={span.id} style={{ borderBottom: '1px solid var(--border-subtle)', padding: '6px 0' }}>
            {/* Header row */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
              <Tag color={cfg.color} icon={cfg.icon} style={{ margin: 0, fontSize: 11 }}>
                {cfg.label}
              </Tag>
              <Text strong style={{ fontSize: 12 }}>{span.name}</Text>
              {span.success ? (
                <CheckCircleOutlined style={{ color: 'var(--color-success)', fontSize: 11 }} />
              ) : (
                <CloseCircleOutlined style={{ color: 'var(--color-error)', fontSize: 11 }} />
              )}
              <Tooltip title={`${span.durationMs}ms`}>
                <Tag icon={<ClockCircleOutlined />} style={{ margin: 0, fontSize: 10 }}>
                  {formatDuration(span.durationMs)}
                </Tag>
              </Tooltip>
              {(span.inputTokens > 0 || span.outputTokens > 0) && (
                <Tag style={{ margin: 0, fontSize: 10 }}>
                  {formatTokens(span.inputTokens, span.outputTokens)} tok
                </Tag>
              )}
              <Text type="secondary" style={{ fontSize: 10, marginLeft: 'auto' }}>
                iter {span.iterationIndex} &middot; {formatTime(span.startTime)}
              </Text>
            </div>

            {/* Waterfall bar */}
            <div style={{ height: 8, background: 'var(--bg-hover)', borderRadius: 4, position: 'relative', marginBottom: 4 }}>
              <Tooltip title={`${formatDuration(span.durationMs)} (${widthPct.toFixed(1)}%)`}>
                <div
                  style={{
                    position: 'absolute',
                    left: `${Math.min(offsetPct, 99)}%`,
                    width: `${Math.max(widthPct, 0.5)}%`,
                    height: '100%',
                    borderRadius: 4,
                    background: span.success
                      ? (span.spanType === 'LLM_CALL' ? 'var(--accent-primary)' : 'var(--color-success)')
                      : 'var(--color-error)',
                    opacity: 0.8,
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

const Traces: React.FC = () => {
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const [sessionFilter, setSessionFilter] = useState<string>('');
  const [spanTypeFilter, setSpanTypeFilter] = useState<string[]>([]);
  // Debounce filter input — prevents a new API call on every keystroke.
  const debouncedFilter = useDebounce(sessionFilter.trim(), 300);

  const { data: traces = [], isLoading: loading, isError: tracesError } = useQuery({
    queryKey: ['traces', debouncedFilter || null],
    queryFn: () =>
      getTraces(debouncedFilter || undefined)
        .then((res) => extractList<TraceItem>(res)),
  });

  const spansQuery = useQuery({
    queryKey: ['trace-spans', selectedTraceId],
    queryFn: () =>
      getTraceSpans(selectedTraceId as string).then((res) => ({
        root: (res.data.root ?? null) as SpanItem | null,
        spans: (Array.isArray(res.data.spans) ? res.data.spans : []) as SpanItem[],
      })),
    enabled: !!selectedTraceId,
  });
  const spans = spansQuery.data?.spans ?? [];
  const rootSpan = spansQuery.data?.root ?? null;
  const spansLoading = spansQuery.isLoading && !!selectedTraceId;

  const handleSelectTrace = (traceId: string) => setSelectedTraceId(traceId);

  const columns = [
    {
      title: 'Time',
      dataIndex: 'startTime',
      width: 100,
      render: (v: string) => <Text style={{ fontSize: 11 }}>{formatDateTime(v)}</Text>,
    },
    {
      title: 'Agent',
      dataIndex: 'name',
      width: 130,
      ellipsis: true,
    },
    {
      title: 'Input',
      dataIndex: 'input',
      width: 200,
      ellipsis: true,
      render: (v: string) => <Text style={{ fontSize: 12 }}>{v}</Text>,
    },
    {
      title: 'LLM',
      dataIndex: 'llmCallCount',
      width: 55,
      align: 'center' as const,
      render: (v: number) => <Tag color="purple" style={{ margin: 0 }}>{v}</Tag>,
    },
    {
      title: 'Tools',
      dataIndex: 'toolCallCount',
      width: 55,
      align: 'center' as const,
      render: (v: number) => <Tag color="green" style={{ margin: 0 }}>{v}</Tag>,
    },
    {
      title: 'Duration',
      dataIndex: 'durationMs',
      width: 80,
      render: (v: number) => (
        <Tag icon={<ClockCircleOutlined />} color={v > 30000 ? 'orange' : 'default'} style={{ margin: 0 }}>
          {formatDuration(v)}
        </Tag>
      ),
    },
    {
      title: 'Tokens',
      width: 80,
      render: (_: any, r: TraceItem) => (
        <Text style={{ fontSize: 11 }}>{formatTokens(r.inputTokens, r.outputTokens)}</Text>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'success',
      width: 60,
      align: 'center' as const,
      render: (v: boolean) =>
        v ? <CheckCircleOutlined style={{ color: 'var(--color-success)' }} /> : <CloseCircleOutlined style={{ color: 'var(--color-error)' }} />,
    },
  ];

  return (
    <div style={{ display: 'flex', height: '100%', gap: 16, overflow: 'hidden' }}>
      {/* Left: trace list */}
      <Card
        title="Traces"
        extra={
          <Space>
            <Select
              mode="multiple"
              placeholder={<><FilterOutlined /> Span types</>}
              options={SPAN_TYPE_FILTER_OPTIONS}
              value={spanTypeFilter}
              onChange={setSpanTypeFilter}
              size="small"
              style={{ minWidth: 180 }}
              maxTagCount={2}
              allowClear
            />
            <Input
              placeholder="Filter by Session ID"
              prefix={<SearchOutlined />}
              size="small"
              style={{ width: 200 }}
              allowClear
              onChange={(e) => setSessionFilter(e.target.value)}
            />
          </Space>
        }
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, padding: 0, overflow: 'auto' } }}
      >
        <Table
          size="small"
          rowKey="traceId"
          dataSource={traces}
          columns={columns}
          loading={loading}
          virtual
          scroll={{ y: 500 }}
          pagination={false}
          onRow={(record) => ({
            onClick: () => handleSelectTrace(record.traceId),
            style: {
              cursor: 'pointer',
              background: selectedTraceId === record.traceId ? 'var(--accent-muted)' : undefined,
            },
          })}
          locale={{ emptyText: tracesError
            ? <Empty description="Failed to load traces" />
            : <Empty description="No traces yet. Send a message to generate traces." /> }}
        />
      </Card>

      {/* Right: span detail */}
      <Card
        title={
          rootSpan ? (
            <Space>
              <ThunderboltOutlined />
              <span>{rootSpan.name}</span>
              {rootSpan.success ? (
                <Tag color="success">success</Tag>
              ) : (
                <Tag color="error">{rootSpan.error ?? 'failed'}</Tag>
              )}
              <Tag icon={<ClockCircleOutlined />}>{formatDuration(rootSpan.durationMs)}</Tag>
              <Tag>{formatTokens(rootSpan.inputTokens, rootSpan.outputTokens)} tok</Tag>
            </Space>
          ) : (
            'Span Detail'
          )
        }
        style={{ width: 520, flexShrink: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, padding: '12px', overflow: 'auto' } }}
      >
        {!selectedTraceId ? (
          <Empty description="Click a trace to view spans" style={{ marginTop: 60 }} />
        ) : spansLoading ? (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 200 }}>
            <Spin tip="Loading spans..." />
          </div>
        ) : (
          <SpanWaterfall
            spans={spans}
            rootDurationMs={rootSpan?.durationMs ?? 1}
            rootStartMs={rootSpan?.startTime ? new Date(rootSpan.startTime).getTime() : 0}
            spanTypeFilter={spanTypeFilter}
          />
        )}
      </Card>
    </div>
  );
};

export default Traces;
