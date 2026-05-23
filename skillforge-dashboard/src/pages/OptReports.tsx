/**
 * OPT-REPORT-V1 Sub-batch 2 (FE) — Reports tab page.
 *
 * Layout: left = filterable list of reports for a chosen agent, right =
 * detail panel rendering the selected report's markdown + structured
 * summary JSON. `?agentId=N&reportId=R` deep-links select-state into the
 * URL so the WS-driven "View report →" toast lands on the right row.
 *
 * Polling: `useQuery` polls the list every 5s while at least one row is in
 * a non-terminal state (`pending` / `running`). Detail panel polls the
 * single selected report on the same cadence when its status is non-terminal.
 *
 * Markdown rendering reuses the shared `MarkdownRenderer` (same one
 * `Chat.tsx` uses) so code fences / GFM tables / links behave consistently
 * across the dashboard.
 */
import React, { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Spin, Collapse, Tag, Alert, Empty } from 'antd';
import { getAgents, extractList } from '../api';
import {
  getOptReport,
  listOptReports,
  type OptReport,
  type OptReportStatus,
  type OptReportSummary,
} from '../api/optReport';
import MarkdownRenderer from '../components/MarkdownRenderer';
import Dropdown from '../components/ui/Dropdown';

const POLL_INTERVAL_MS = 5_000;
const LIST_LIMIT = 50;

interface AgentLite {
  id: number;
  name: string;
}

function statusColor(status: OptReportStatus): string {
  switch (status) {
    case 'completed':
      return 'green';
    case 'running':
      return 'blue';
    case 'pending':
      return 'gold';
    case 'error':
      return 'red';
    default:
      return 'default';
  }
}

function formatDateRange(start: string, end: string): string {
  try {
    const s = new Date(start);
    const e = new Date(end);
    const fmt = (d: Date) =>
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return `${fmt(s)} → ${fmt(e)}`;
  } catch {
    return `${start} → ${end}`;
  }
}

function formatRelative(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * Try to parse + pretty-print the raw `summary_json` string. Returns null
 * when missing / malformed so the caller can hide the panel entirely
 * instead of rendering "null" garbage. Per `summaryJson` contract: column
 * is `text`, may be null, may be invalid JSON if the agent hallucinated.
 */
function prettyJson(raw: string | null): string | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return null;
  }
}

interface ReportListPanelProps {
  agentId: number | null;
  selectedReportId: string | null;
  onSelect: (reportId: string) => void;
}

const ReportListPanel: React.FC<ReportListPanelProps> = ({
  agentId,
  selectedReportId,
  onSelect,
}) => {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['opt-reports', 'list', agentId],
    queryFn: () =>
      listOptReports(agentId as number, LIST_LIMIT).then((r) => r.data?.items ?? []),
    enabled: agentId !== null,
    staleTime: 0,
    refetchInterval: (q) => {
      const items = (q.state.data ?? []) as OptReportSummary[];
      const hasActive = items.some(
        (r) => r.status === 'pending' || r.status === 'running',
      );
      return hasActive ? POLL_INTERVAL_MS : false;
    },
  });

  const items = data ?? [];

  if (agentId === null) {
    return (
      <div style={{ padding: 24, color: 'var(--fg-3)', fontSize: 13 }}>
        Pick an agent above to see its reports.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div style={{ padding: 24, textAlign: 'center' }}>
        <Spin size="small" />
      </div>
    );
  }

  if (isError) {
    return (
      <Alert
        type="error"
        showIcon
        message="Failed to load reports"
        description={error instanceof Error ? error.message : 'Unknown error'}
        style={{ margin: 12 }}
      />
    );
  }

  if (items.length === 0) {
    return (
      <Empty
        description="No reports yet — trigger Generate Report from the agent drawer."
        style={{ padding: 24 }}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: 8 }}>
      {items.map((row) => {
        const active = row.reportId === selectedReportId;
        return (
          <button
            key={row.reportId}
            type="button"
            onClick={() => onSelect(row.reportId)}
            data-testid={`opt-report-row-${row.reportId.slice(0, 8)}`}
            style={{
              textAlign: 'left',
              padding: '10px 12px',
              borderRadius: 6,
              border: `1px solid ${active ? 'var(--accent, #6366f1)' : 'var(--border-subtle, #2a2a31)'}`,
              background: active ? 'var(--accent-soft, rgba(99,102,241,0.08))' : 'var(--bg-primary, #0f0f10)',
              color: 'var(--fg-1)',
              cursor: 'pointer',
              display: 'flex',
              flexDirection: 'column',
              gap: 4,
              fontFamily: 'inherit',
              transition: 'background 80ms ease',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between' }}>
              <span
                style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11,
                  color: 'var(--fg-3)',
                }}
              >
                {row.reportId.slice(0, 8)}…
              </span>
              <Tag color={statusColor(row.status)} style={{ marginInlineEnd: 0 }}>
                {row.status}
              </Tag>
            </div>
            <div style={{ fontSize: 12, color: 'var(--fg-2)' }}>
              {formatDateRange(row.windowStart, row.windowEnd)}
            </div>
            <div style={{ fontSize: 11, color: 'var(--fg-4)' }}>
              created {formatRelative(row.createdAt)}
            </div>
          </button>
        );
      })}
    </div>
  );
};

interface ReportDetailPanelProps {
  reportId: string | null;
  agentName: string | null;
}

const ReportDetailPanel: React.FC<ReportDetailPanelProps> = ({ reportId, agentName }) => {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['opt-reports', 'detail', reportId],
    queryFn: () => getOptReport(reportId as string).then((r) => r.data),
    enabled: reportId !== null,
    staleTime: 0,
    refetchInterval: (q) => {
      const r = q.state.data as OptReport | undefined;
      if (!r) return false;
      return r.status === 'pending' || r.status === 'running' ? POLL_INTERVAL_MS : false;
    },
  });

  if (reportId === null) {
    return (
      <div style={{ padding: 32, color: 'var(--fg-3)', fontSize: 13, textAlign: 'center' }}>
        Select a report from the list to view details.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div style={{ padding: 32, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <Alert
        type="error"
        showIcon
        message="Failed to load report"
        description={error instanceof Error ? error.message : 'Unknown error'}
        style={{ margin: 16 }}
      />
    );
  }

  const r = data;
  const prettySummary = prettyJson(r.summaryJson);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: 20 }}>
      {/* Header */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          borderBottom: '1px solid var(--border-subtle, #2a2a31)',
          paddingBottom: 12,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h2
            style={{
              fontFamily: 'var(--font-serif)',
              fontSize: 20,
              fontWeight: 500,
              margin: 0,
              color: 'var(--fg-1)',
            }}
          >
            Report — {agentName ?? `agent ${r.agentId}`}
          </h2>
          <Tag color={statusColor(r.status)}>{r.status}</Tag>
        </div>
        <div style={{ fontSize: 12, color: 'var(--fg-3)' }}>
          Window: <strong>{formatDateRange(r.windowStart, r.windowEnd)}</strong>
          &nbsp;·&nbsp; Created: {formatRelative(r.createdAt)}
          {r.generatorSessionId && (
            <>
              &nbsp;·&nbsp; Generator session:&nbsp;
              <a
                href={`/sessions/${r.generatorSessionId}`}
                target="_blank"
                rel="noopener noreferrer"
                style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}
              >
                {r.generatorSessionId.slice(0, 8)}…
              </a>
            </>
          )}
        </div>
        <div
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            color: 'var(--fg-4)',
            wordBreak: 'break-all',
          }}
        >
          {r.reportId}
        </div>
      </div>

      {/* Running spinner */}
      {(r.status === 'pending' || r.status === 'running') && (
        <Alert
          type="info"
          showIcon
          icon={<Spin size="small" />}
          message="Report is being generated…"
          description="The report-generator agent is fanning out SubAgents to label sessions. This typically takes 1-2 minutes."
        />
      )}

      {/* Error reason */}
      {r.status === 'error' && r.errorReason && (
        <Alert
          type="error"
          showIcon
          message="Report generation failed"
          description={<pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{r.errorReason}</pre>}
        />
      )}

      {/* Main markdown body */}
      {r.contentMd ? (
        <div data-testid="opt-report-md">
          <MarkdownRenderer content={r.contentMd} />
        </div>
      ) : (
        r.status === 'completed' && (
          <Empty description="Report completed but contentMd is empty." />
        )
      )}

      {/* Summary JSON (collapsed by default) */}
      {prettySummary && (
        <Collapse
          ghost
          size="small"
          items={[
            {
              key: 'summary',
              label: 'Structured summary (summary_json)',
              children: (
                <pre
                  style={{
                    margin: 0,
                    padding: 12,
                    background: 'var(--bg-code, #1a1a1e)',
                    color: 'var(--text-on-accent, #e7e7ea)',
                    fontFamily: 'var(--font-mono, ui-monospace, Menlo, monospace)',
                    fontSize: 12,
                    borderRadius: 6,
                    overflow: 'auto',
                    maxHeight: 360,
                  }}
                  data-testid="opt-report-summary-json"
                >
                  {prettySummary}
                </pre>
              ),
            },
          ]}
        />
      )}
      {r.summaryJson && !prettySummary && (
        <Alert
          type="warning"
          showIcon
          message="summary_json present but not valid JSON — agent output may be malformed."
          description={
            <pre style={{ margin: 0, fontSize: 11, whiteSpace: 'pre-wrap' }}>
              {r.summaryJson.slice(0, 500)}
              {r.summaryJson.length > 500 && '…'}
            </pre>
          }
        />
      )}
    </div>
  );
};

const OptReportsPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();

  // Agent dropdown — user agents only (Generate Report is a user-agent
  // action; system agents are cron-managed and not the target of reports).
  const { data: agents = [] } = useQuery({
    queryKey: ['agents', 'user', 'opt-reports'],
    queryFn: () =>
      getAgents('user').then((res) => {
        const rows = extractList<Record<string, unknown>>(res);
        return rows
          .map((r) => ({
            id: Number(r.id),
            name: String(r.name ?? `agent ${r.id}`),
          }))
          .filter((a) => Number.isFinite(a.id) && a.id > 0) as AgentLite[];
      }),
    staleTime: 60_000,
  });

  const urlAgentId = searchParams.get('agentId');
  const urlReportId = searchParams.get('reportId');

  const [agentId, setAgentId] = useState<number | null>(
    urlAgentId ? Number(urlAgentId) : null,
  );
  const [selectedReportId, setSelectedReportId] = useState<string | null>(urlReportId);

  // Sync URL → state on back/forward navigation
  useEffect(() => {
    const nextAgentId = urlAgentId ? Number(urlAgentId) : null;
    const nextReportId = urlReportId;
    if (nextAgentId !== agentId) setAgentId(nextAgentId);
    if (nextReportId !== selectedReportId) setSelectedReportId(nextReportId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlAgentId, urlReportId]);

  // Helper: rewrite the relevant search params, preserving siblings (e.g.
  // `?tab=reports` set by Insights.tsx).
  const writeUrl = (next: { agentId?: number | null; reportId?: string | null }) => {
    setSearchParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if ('agentId' in next) {
          if (next.agentId == null) out.delete('agentId');
          else out.set('agentId', String(next.agentId));
        }
        if ('reportId' in next) {
          if (next.reportId == null) out.delete('reportId');
          else out.set('reportId', next.reportId);
        }
        return out;
      },
      { replace: true },
    );
  };

  const handleAgentChange = (v: string | undefined) => {
    const next = v ? Number(v) : null;
    setAgentId(next);
    setSelectedReportId(null);
    writeUrl({ agentId: next, reportId: null });
  };

  const handleReportSelect = (id: string) => {
    setSelectedReportId(id);
    writeUrl({ reportId: id });
  };

  const agentOptions = useMemo(
    () => agents.map((a) => ({ value: String(a.id), label: a.name })),
    [agents],
  );

  const selectedAgentName = useMemo(() => {
    if (agentId == null) return null;
    return agents.find((a) => a.id === agentId)?.name ?? null;
  }, [agents, agentId]);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        padding: 'var(--sp-6, 24px) var(--sp-8, 32px)',
        maxWidth: 1600,
        width: '100%',
        margin: '0 auto',
        boxSizing: 'border-box',
      }}
    >
      {/* Header */}
      <div style={{ marginBottom: 20 }}>
        <h1
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: 28,
            fontWeight: 500,
            letterSpacing: '-0.02em',
            margin: '0 0 4px',
            lineHeight: 1.2,
            color: 'var(--fg-1)',
          }}
        >
          Optimization Reports
        </h1>
        <p style={{ color: 'var(--fg-3)', fontSize: 'var(--font-size-sm)', margin: 0 }}>
          Human-readable reports produced by the <code>report-generator</code> agent. Pick an
          agent to see its history; trigger new reports from the Agent drawer&apos;s &ldquo;Generate
          Report&rdquo; button.
        </p>
      </div>

      {/* Agent picker */}
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ fontSize: 13, color: 'var(--fg-2)' }}>Agent</span>
        <div style={{ minWidth: 280 }}>
          <Dropdown
            options={agentOptions}
            value={agentId == null ? undefined : String(agentId)}
            placeholder="Choose an agent…"
            allowClear
            onChange={handleAgentChange}
          />
        </div>
      </div>

      {/* Two-pane layout */}
      <div
        style={{
          flex: 1,
          minHeight: 0,
          display: 'grid',
          gridTemplateColumns: 'minmax(280px, 360px) 1fr',
          gap: 16,
          border: '1px solid var(--border-subtle, #2a2a31)',
          borderRadius: 8,
          overflow: 'hidden',
          background: 'var(--bg-primary, #0f0f10)',
        }}
      >
        <div
          style={{
            overflow: 'auto',
            borderRight: '1px solid var(--border-subtle, #2a2a31)',
            minHeight: 0,
          }}
        >
          <ReportListPanel
            agentId={agentId}
            selectedReportId={selectedReportId}
            onSelect={handleReportSelect}
          />
        </div>
        <div style={{ overflow: 'auto', minHeight: 0 }}>
          <ReportDetailPanel reportId={selectedReportId} agentName={selectedAgentName} />
        </div>
      </div>
    </div>
  );
};

export default OptReportsPage;
