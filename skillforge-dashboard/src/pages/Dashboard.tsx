import React, { useEffect, useMemo, useState } from 'react';
import { Card, Col, Row, Spin, message, List, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  RobotOutlined,
  MessageOutlined,
  CloudOutlined,
  CalendarOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getDashboardOverview, getDailyUsage, getSessions, getUsageByModel, getUsageByAgent } from '../api';
import '../components/usage/usage.css';

const { Text } = Typography;

interface Overview {
  totalAgents: number;
  activeAgents: number;
  totalSessions: number;
  todaySessions: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  todayInputTokens: number;
  todayOutputTokens: number;
}

interface DailyPoint {
  date: string;
  inputTokens: number;
  outputTokens: number;
}

interface SessionItem {
  id?: string;
  sessionId?: string;
  agentId?: number;
  title?: string;
  messageCount?: number;
  totalInputTokens?: number;
  totalOutputTokens?: number;
  updatedAt?: string;
}

interface ModelUsageItem {
  model: string;
  totalTokens: number;
}

interface AgentUsageItem {
  agentName: string;
  totalTokens: number;
}

type RangeKey = '24h' | '7d' | '30d' | '90d';
const RANGE_DAYS: Record<RangeKey, number> = { '24h': 1, '7d': 7, '30d': 30, '90d': 90 };

type MetricKey = 'cost' | 'tIn' | 'tOut';
interface MetricDef {
  key: string;
  label: string;
  fmt: (v: number) => string;
  color: string;
}
const metricMap: Record<MetricKey, MetricDef> = {
  cost: { key: 'cost', label: 'Cost', fmt: v => `$${v.toFixed(2)}`, color: 'var(--accent)' },
  tIn: { key: 'tIn', label: 'Tokens in', fmt: v => `${(v / 1000).toFixed(0)}K`, color: '#3a7d54' },
  tOut: { key: 'tOut', label: 'Tokens out', fmt: v => `${(v / 1000).toFixed(0)}K`, color: '#8a3a1a' },
};

const formatNumber = (n: number) => n.toLocaleString();

// SVG line chart — same as ModelUsage
function UsageLineChart({ days, values, metric }: { days: DailyPoint[]; values: number[]; metric: MetricDef }) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 780, H = 220;
  const PAD = { top: 16, right: 24, bottom: 32, left: 52 };
  const IW = W - PAD.left - PAD.right;
  const IH = H - PAD.top - PAD.bottom;

  const max = Math.max(...values) * 1.15 || 1;

  const xs = values.map((_, i) => PAD.left + (i / Math.max(values.length - 1, 1)) * IW);
  const ys = values.map(v => PAD.top + IH - (v / max) * IH);
  const pts = xs.map((x, i) => [x, ys[i]]);

  const line = pts.reduce((acc, p, i) => {
    if (i === 0) return `M ${p[0]},${p[1]}`;
    const prev = pts[i - 1];
    const cx = (prev[0] + p[0]) / 2;
    return `${acc} C ${cx},${prev[1]} ${cx},${p[1]} ${p[0]},${p[1]}`;
  }, '');

  const area = `${line} L ${xs[xs.length - 1]},${PAD.top + IH} L ${xs[0]},${PAD.top + IH} Z`;

  const yticks = [0, 0.25, 0.5, 0.75, 1].map(t => ({
    y: PAD.top + IH - t * IH,
    v: t * max,
  }));

  const color = metric.color;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto' }}>
      {yticks.map((t, i) => (
        <g key={i}>
          <line x1={PAD.left} x2={W - PAD.right} y1={t.y} y2={t.y}
            stroke="var(--border-1)" strokeWidth="0.5" />
          <text x={PAD.left - 8} y={t.y + 3} textAnchor="end" fontSize="10"
            fontFamily="var(--font-mono)" fill="var(--fg-3)">
            {metric.fmt(t.v)}
          </text>
        </g>
      ))}

      <path d={area} fill={color} opacity="0.12" />
      <path d={line} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" />

      {xs.map((x, i) => (
        <rect key={i} x={x - IW / values.length / 2} y={PAD.top}
          width={IW / values.length} height={IH}
          fill="transparent"
          onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
      ))}

      {hover !== null && (
        <>
          <line x1={xs[hover]} x2={xs[hover]} y1={PAD.top} y2={PAD.top + IH}
            stroke={color} strokeWidth="1" strokeDasharray="3 3" opacity="0.5" />
          <circle cx={xs[hover]} cy={ys[hover]} r="5" fill="var(--bg-surface)" stroke={color} strokeWidth="2" />
          {(() => {
            const isRight = xs[hover] > W * 0.7;
            const tx = xs[hover] + (isRight ? -14 : 14);
            const ty = ys[hover] - 16;
            return (
              <g>
                <rect x={isRight ? tx - 126 : tx} y={ty - 24} width={126} height={42}
                  rx={6} fill="var(--bg-base)" stroke="var(--border-1)" />
                <text x={isRight ? tx - 114 : tx + 12} y={ty - 8}
                  fontSize="10" fontFamily="var(--font-mono)" fill="var(--fg-4)"
                  style={{ letterSpacing: '0.08em', textTransform: 'uppercase' }}>
                  {days[hover].date}
                </text>
                <text x={isRight ? tx - 114 : tx + 12} y={ty + 10}
                  fontSize="14" fontWeight="500" fontFamily="var(--font-mono)" fill={color}>
                  {metric.fmt(values[hover])}
                </text>
              </g>
            );
          })()}
        </>
      )}

      {days.map((d, i) => (
        <text key={i} x={xs[i]} y={H - 10} textAnchor="middle" fontSize="10.5"
          fontFamily="var(--font-mono)" fill="var(--fg-3)">
          {d.date.slice(5)}
        </text>
      ))}
    </svg>
  );
}

const Dashboard: React.FC = () => {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [recent, setRecent] = useState<SessionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<RangeKey>('7d');
  const [metric, setMetric] = useState<MetricKey>('cost');
  const navigate = useNavigate();

  useEffect(() => {
    const load = async () => {
      try {
        const [ovRes, sessRes] = await Promise.all([
          getDashboardOverview(),
          getSessions(1),
        ]);
        setOverview(ovRes.data as Overview);
        const list: SessionItem[] = Array.isArray(sessRes.data)
          ? sessRes.data
          : (sessRes.data as any)?.data ?? [];
        setRecent(list.slice(0, 5));
      } catch {
        message.error('Failed to load dashboard data');
      } finally {
        setLoading(false);
      }
    };
    void load();
  }, []);

  const dailyQuery = useQuery({
    queryKey: ['usage', 'daily', range],
    queryFn: () => getDailyUsage(RANGE_DAYS[range]).then(res => res.data as DailyPoint[]),
  });
  const modelQuery = useQuery({
    queryKey: ['usage', 'by-model'],
    queryFn: () => getUsageByModel().then(res => res.data as ModelUsageItem[]),
    staleTime: 5 * 60_000,
  });
  const agentQuery = useQuery({
    queryKey: ['usage', 'by-agent'],
    queryFn: () => getUsageByAgent().then(res => res.data as AgentUsageItem[]),
    staleTime: 5 * 60_000,
  });

  const dailyData = dailyQuery.data ?? [];
  const modelData = modelQuery.data ?? [];
  const agentData = agentQuery.data ?? [];

  const usageTotals = useMemo(() => {
    const tokensIn = dailyData.reduce((s, d) => s + (d.inputTokens || 0), 0);
    const tokensOut = dailyData.reduce((s, d) => s + (d.outputTokens || 0), 0);
    const cost = (tokensIn * 3 + tokensOut * 15) / 1_000_000;
    return { cost, tokensIn, tokensOut };
  }, [dailyData]);

  const totalModelTokens = modelData.reduce((s, m) => s + m.totalTokens, 0) || 1;
  const totalAgentTokens = agentData.reduce((s, a) => s + a.totalTokens, 0) || 1;

  const chartValues = useMemo(() => {
    return dailyData.map(d => {
      const tIn = d.inputTokens || 0;
      const tOut = d.outputTokens || 0;
      if (metric === 'cost') return (tIn * 3 + tOut * 15) / 1_000_000;
      if (metric === 'tIn') return tIn;
      return tOut;
    });
  }, [dailyData, metric]);

  const m = metricMap[metric];

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 100, textAlign: 'center' }} />;
  }

  const ov = overview ?? ({} as Overview);
  const totalTokens = (ov.totalInputTokens ?? 0) + (ov.totalOutputTokens ?? 0);
  const todayTokens = (ov.todayInputTokens ?? 0) + (ov.todayOutputTokens ?? 0);

  const kpis = [
    { title: 'Total Agents', value: ov.totalAgents ?? 0, icon: <RobotOutlined />, sub: `${ov.activeAgents ?? 0} active` },
    { title: 'Total Sessions', value: ov.totalSessions ?? 0, icon: <MessageOutlined />, sub: `${ov.todaySessions ?? 0} today` },
    { title: "Today's Tokens", value: todayTokens, icon: <CalendarOutlined />, sub: `in ${formatNumber(ov.todayInputTokens ?? 0)} / out ${formatNumber(ov.todayOutputTokens ?? 0)}` },
    { title: 'Total Tokens', value: totalTokens, icon: <CloudOutlined />, sub: `in ${formatNumber(ov.totalInputTokens ?? 0)} / out ${formatNumber(ov.totalOutputTokens ?? 0)}` },
  ];

  const handleExport = () => {
    const rows = dailyData.map(d => ({
      date: d.date,
      inputTokens: d.inputTokens,
      outputTokens: d.outputTokens,
      cost: ((d.inputTokens || 0) * 3 + (d.outputTokens || 0) * 15) / 1_000_000,
    }));
    const csv = ['date,inputTokens,outputTokens,cost', ...rows.map(r => `${r.date},${r.inputTokens},${r.outputTokens},${r.cost.toFixed(4)}`)].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `usage-${range}.csv`;
    a.click();
  };

  return (
    <div style={{ padding: '16px 24px' }}>
      {/* KPI cards */}
      <Row gutter={[16, 16]}>
        {kpis.map((k) => (
          <Col xs={12} sm={6} key={k.title}>
            <Card className="sf-stat-card" style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="sf-stat-label">{k.title}</div>
                  <div className="sf-stat-metric">{formatNumber(Number(k.value))}</div>
                  <div className="sf-stat-sub">{k.sub}</div>
                </div>
                <div style={{ flexShrink: 0, opacity: 0.85, fontSize: 22, color: 'var(--accent-primary)' }}>{k.icon}</div>
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      {/* Usage chart */}
      <Card className="sf-surface-card" style={{ marginTop: 16, borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
        {/* Controls */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontWeight: 600, whiteSpace: 'nowrap', marginRight: 8 }}>{m.label} over time</span>
            {(Object.entries(metricMap) as [MetricKey, MetricDef][]).map(([k, v]) => (
              <button key={k} className={`filter-item ${metric === k ? 'on' : ''}`}
                onClick={() => setMetric(k)} style={{ padding: '4px 12px', fontSize: 12 }}>
                {v.label}
              </button>
            ))}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            {(['24h', '7d', '30d', '90d'] as RangeKey[]).map(r => (
              <button key={r} className={`filter-item ${range === r ? 'on' : ''}`}
                onClick={() => setRange(r)} style={{ padding: '2px 8px', fontSize: 11 }}>
                {r}
              </button>
            ))}
            <button className="filter-item" onClick={handleExport} style={{ padding: '2px 8px', fontSize: 11, marginLeft: 4 }} title="Export CSV">
              CSV
            </button>
          </div>
        </div>

        {/* Totals */}
        <div className="usage-totals" style={{ marginBottom: 16 }}>
          <div className="stat-card">
            <span className="stat-lbl">Total cost</span>
            <span className="stat-big">${usageTotals.cost.toFixed(2)}</span>
          </div>
          <div className="stat-card">
            <span className="stat-lbl">Tokens in</span>
            <span className="stat-big">{usageTotals.tokensIn >= 1e6 ? `${(usageTotals.tokensIn / 1e6).toFixed(2)}M` : `${(usageTotals.tokensIn / 1e3).toFixed(0)}K`}</span>
          </div>
          <div className="stat-card">
            <span className="stat-lbl">Tokens out</span>
            <span className="stat-big">{usageTotals.tokensOut >= 1e6 ? `${(usageTotals.tokensOut / 1e6).toFixed(2)}M` : `${(usageTotals.tokensOut / 1e3).toFixed(0)}K`}</span>
          </div>
        </div>

        {/* Chart */}
        {dailyData.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>No usage data</div>
        ) : (
          <UsageLineChart days={dailyData} values={chartValues} metric={m} />
        )}
      </Card>

      {/* By model + by agent + recent */}
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={10}>
          <Card title="By Model" className="sf-surface-card" bodyStyle={{ maxHeight: 320, overflow: 'auto' }} style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
            {modelData.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>No model data</div>
            ) : (
              <div className="bymodel-list">
                {modelData.map(mo => {
                  const cost = (mo.totalTokens * 9) / 1_000_000;
                  return (
                    <div key={mo.model} className="bymodel-row">
                      <div className="bymodel-top">
                        <b>{mo.model}</b>
                        <span className="mono-sm">${cost.toFixed(2)}</span>
                      </div>
                      <div className="bymodel-bar">
                        <span style={{ width: `${(mo.totalTokens / totalModelTokens) * 100}%` }} />
                      </div>
                      <div className="bymodel-meta mono-sm">
                        <span>{mo.totalTokens.toLocaleString()} tokens</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={7}>
          <Card title="By Agent" className="sf-surface-card" bodyStyle={{ maxHeight: 320, overflow: 'auto' }} style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
            {agentData.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>No agent data</div>
            ) : (
              <div className="byagent-list">
                {agentData.map(a => {
                  const share = a.totalTokens / totalAgentTokens;
                  const cost = (a.totalTokens * 9) / 1_000_000;
                  return (
                    <div key={a.agentName} className="byagent-row">
                      <span className="byagent-name">{a.agentName}</span>
                      <div className="byagent-bar">
                        <span style={{ width: `${share * 100}%` }} />
                      </div>
                      <span className="byagent-pct mono-sm">{(share * 100).toFixed(0)}%</span>
                      <span className="byagent-cost mono-sm">${cost.toFixed(2)}</span>
                      <span className="byagent-runs mono-sm">{a.totalTokens.toLocaleString()}</span>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={7}>
          <Card title="Recent Sessions" className="sf-surface-card" style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
            {recent.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>No sessions yet</div>
            ) : (
              <List
                size="small"
                dataSource={recent}
                renderItem={(s) => {
                  const sid = String(s.id ?? s.sessionId ?? '');
                  const tokens = (s.totalInputTokens ?? 0) + (s.totalOutputTokens ?? 0);
                  return (
                    <List.Item style={{ cursor: 'pointer' }} onClick={() => sid && navigate(`/chat/${sid}`)}>
                      <div style={{ width: '100%' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                          <Text ellipsis style={{ maxWidth: 180 }}>
                            {s.title && s.title !== 'New Session' ? s.title : `Session ${sid.slice(0, 8)}`}
                          </Text>
                          <Tag color="blue">{s.messageCount ?? 0} msg</Tag>
                        </div>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          {formatNumber(tokens)} tokens
                          {s.updatedAt ? ` · ${s.updatedAt.slice(0, 16).replace('T', ' ')}` : ''}
                        </Text>
                      </div>
                    </List.Item>
                  );
                }}
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
