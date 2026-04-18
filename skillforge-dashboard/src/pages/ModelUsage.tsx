import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getDailyUsage, getUsageByModel, getUsageByAgent } from '../api';
import '../components/agents/agents.css';
import '../components/usage/usage.css';

interface DailyUsage {
  date: string;
  inputTokens: number;
  outputTokens: number;
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

type MetricKey = 'cost' | 'runs' | 'tIn' | 'tOut';
interface MetricDef {
  key: string;
  label: string;
  fmt: (v: number) => string;
  color: string;
}
const metricMap: Record<MetricKey, MetricDef> = {
  cost: { key: 'cost', label: 'Cost', fmt: v => `$${v.toFixed(2)}`, color: 'var(--accent)' },
  runs: { key: 'runs', label: 'Runs', fmt: v => v.toLocaleString(), color: '#3a527d' },
  tIn: { key: 'tIn', label: 'Tokens in', fmt: v => `${(v / 1000).toFixed(0)}K`, color: '#3a7d54' },
  tOut: { key: 'tOut', label: 'Tokens out', fmt: v => `${(v / 1000).toFixed(0)}K`, color: '#8a3a1a' },
};

function FilterItem({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
    </button>
  );
}

const ModelUsage: React.FC = () => {
  const [range, setRange] = useState<RangeKey>('7d');
  const [metric, setMetric] = useState<MetricKey>('cost');

  const dailyQuery = useQuery({
    queryKey: ['usage', 'daily', range],
    queryFn: () => getDailyUsage(RANGE_DAYS[range]).then(res => res.data as DailyUsage[]),
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

  const totals = useMemo(() => {
    const tokensIn = dailyData.reduce((s, d) => s + (d.inputTokens || 0), 0);
    const tokensOut = dailyData.reduce((s, d) => s + (d.outputTokens || 0), 0);
    const cost = (tokensIn * 3 + tokensOut * 15) / 1_000_000;
    const runs = dailyData.length;
    return { cost, runs, tokensIn, tokensOut };
  }, [dailyData]);

  const totalModelTokens = modelData.reduce((s, m) => s + m.totalTokens, 0) || 1;
  const totalAgentTokens = agentData.reduce((s, a) => s + a.totalTokens, 0) || 1;

  const m = metricMap[metric];

  const chartValues = useMemo(() => {
    return dailyData.map(d => {
      const tIn = d.inputTokens || 0;
      const tOut = d.outputTokens || 0;
      if (metric === 'cost') return (tIn * 3 + tOut * 15) / 1_000_000;
      if (metric === 'tIn') return tIn;
      if (metric === 'tOut') return tOut;
      return 1;
    });
  }, [dailyData, metric]);

  return (
    <div className="agents-view">
      <aside className="agents-filters">
        <div className="agents-filters-h">Range</div>
        {(['24h', '7d', '30d', '90d'] as RangeKey[]).map(r => (
          <FilterItem key={r} label={r} active={range === r} onClick={() => setRange(r)} />
        ))}

        <div className="agents-filters-h">Metric</div>
        {(Object.entries(metricMap) as [MetricKey, MetricDef][]).map(([k, v]) => (
          <FilterItem key={k} label={v.label} active={metric === k} onClick={() => setMetric(k)} />
        ))}

        <div className="agents-filters-h">Export</div>
        <button className="filter-item"><span>CSV</span></button>
        <button className="filter-item"><span>JSON</span></button>
      </aside>

      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Usage</h1>
            <p className="agents-head-sub">Last {range} · token consumption and cost</p>
          </div>
        </header>

        <div className="agents-body">
          {/* Totals */}
          <div className="usage-totals">
            <div className="stat-card">
              <span className="stat-lbl">Total cost</span>
              <span className="stat-big">${totals.cost.toFixed(2)}</span>
              <span className="stat-delta muted">—</span>
            </div>
            <div className="stat-card">
              <span className="stat-lbl">Days</span>
              <span className="stat-big">{dailyData.length}</span>
              <span className="stat-delta muted">—</span>
            </div>
            <div className="stat-card">
              <span className="stat-lbl">Tokens in</span>
              <span className="stat-big">{totals.tokensIn >= 1e6 ? `${(totals.tokensIn / 1e6).toFixed(2)}M` : `${(totals.tokensIn / 1e3).toFixed(0)}K`}</span>
              <span className="stat-delta muted">—</span>
            </div>
            <div className="stat-card">
              <span className="stat-lbl">Tokens out</span>
              <span className="stat-big">{totals.tokensOut >= 1e6 ? `${(totals.tokensOut / 1e6).toFixed(2)}M` : `${(totals.tokensOut / 1e3).toFixed(0)}K`}</span>
              <span className="stat-delta muted">—</span>
            </div>
          </div>

          {/* Chart */}
          <div className="usage-chart-block">
            <div className="chart-h">
              <h3>{m.label} over time</h3>
              {dailyData.length > 0 && (
                <span className="mono-sm">{dailyData[0].date} → {dailyData[dailyData.length - 1].date}</span>
              )}
            </div>
            {dailyData.length === 0 ? (
              <div className="sf-empty-state">No usage data for this range.</div>
            ) : (
              <UsageLineChart days={dailyData} values={chartValues} metric={m} />
            )}
          </div>

          {/* By model + by agent */}
          <div className="usage-split">
            <div className="usage-panel">
              <div className="chart-h">
                <h3>By model</h3>
                <span className="mono-sm">{modelData.length} models</span>
              </div>
              {modelData.length === 0 ? (
                <div className="sf-empty-state">No model data.</div>
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
            </div>

            <div className="usage-panel">
              <div className="chart-h">
                <h3>By agent</h3>
                <span className="mono-sm">share of tokens</span>
              </div>
              {agentData.length === 0 ? (
                <div className="sf-empty-state">No agent data.</div>
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
            </div>
          </div>
        </div>
      </section>
    </div>
  );
};

function UsageLineChart({ days, values, metric }: { days: DailyUsage[]; values: number[]; metric: MetricDef }) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 780, H = 240;
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
  const fillId = `usage-area-${metric.key}`;

  return (
    <div className="usage-line-wrap">
      <svg className="usage-line-svg" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none"
        onMouseLeave={() => setHover(null)}>
        <defs>
          <linearGradient id={fillId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.22} />
            <stop offset="100%" stopColor={color} stopOpacity={0} />
          </linearGradient>
        </defs>

        {yticks.map((t, i) => (
          <g key={i}>
            <line x1={PAD.left} x2={W - PAD.right} y1={t.y} y2={t.y} stroke="var(--border-1)" strokeDasharray="2 4" />
            <text x={PAD.left - 10} y={t.y + 4} textAnchor="end" fontSize="10" fontFamily="var(--font-mono)" fill="var(--fg-4)">
              {metric.fmt(t.v)}
            </text>
          </g>
        ))}

        <path d={area} fill={`url(#${fillId})`} />
        <path d={line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />

        {days.map((d, i) => (
          <text key={i} x={xs[i]} y={H - 10} textAnchor="middle" fontSize="10.5" fontFamily="var(--font-mono)" fill="var(--fg-3)">
            {d.date.slice(5)}
          </text>
        ))}

        {hover !== null && (
          <line x1={xs[hover]} x2={xs[hover]} y1={PAD.top} y2={PAD.top + IH}
            stroke={color} strokeWidth="1" strokeDasharray="3 3" opacity="0.5" />
        )}

        {pts.map((p, i) => (
          <g key={i}>
            <circle cx={p[0]} cy={p[1]} r={hover === i ? 6 : 3.5}
              fill="var(--bg-surface)" stroke={color} strokeWidth="2" />
            <rect x={xs[i] - IW / values.length / 2} y={PAD.top}
              width={IW / values.length} height={IH}
              fill="transparent"
              onMouseEnter={() => setHover(i)} />
          </g>
        ))}

        {hover !== null && (() => {
          const isRight = xs[hover] > W * 0.7;
          const tx = xs[hover] + (isRight ? -14 : 14);
          const ty = ys[hover] - 16;
          return (
            <g>
              <rect x={isRight ? tx - 126 : tx} y={ty - 24} width={126} height={42}
                rx={6} fill="var(--bg-base)" stroke="var(--border-1)" />
              <text x={isRight ? tx - 114 : tx + 12} y={ty - 8}
                fontSize="10" fontFamily="var(--font-mono)" fill="var(--fg-4)"
                style={{ letterSpacing: '0.08em', textTransform: 'uppercase' } as React.CSSProperties}>
                {days[hover].date}
              </text>
              <text x={isRight ? tx - 114 : tx + 12} y={ty + 10}
                fontSize="14" fontWeight="500" fontFamily="var(--font-mono)" fill={color}>
                {metric.fmt(values[hover])}
              </text>
            </g>
          );
        })()}
      </svg>
    </div>
  );
}

export default ModelUsage;
