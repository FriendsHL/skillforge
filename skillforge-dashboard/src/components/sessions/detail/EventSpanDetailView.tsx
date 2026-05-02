import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { EventSpanSummary, EventSpanDetail } from '../../../types/observability';
import { getEventSpanDetail } from '../../../api';
import { useAuth } from '../../../contexts/AuthContext';
import SpanDetailTabs from './SpanDetailTabs';

interface EventSpanDetailViewProps {
  span: EventSpanSummary;
}

/**
 * OBS-2 M3 — event span detail panel.
 *
 * Lifecycle event spans (`kind='event'`) cover 4 types: ASK_USER,
 * INSTALL_CONFIRM, COMPACT, AGENT_CONFIRM. Layout mirrors
 * {@link ToolSpanDetailView} (Meta / Input / Output / Error tabs) so users get
 * a consistent reading model across span kinds.
 */
const EventSpanDetailView: React.FC<EventSpanDetailViewProps> = ({ span }) => {
  const { userId } = useAuth();
  const [activeTab, setActiveTab] = useState('meta');

  const { data, isLoading, isError, refetch } = useQuery<EventSpanDetail>({
    queryKey: ['obs-event-span-detail', span.spanId, userId],
    queryFn: async () => {
      const res = await getEventSpanDetail(span.spanId, userId);
      return res.data;
    },
    staleTime: 60_000,
  });

  if (isLoading) {
    return <div className="obs-empty-state">Loading event span detail…</div>;
  }
  if (isError || !data) {
    return (
      <div className="obs-empty-state">
        Failed to load event span detail.{' '}
        <button type="button" className="btn-ghost-sf" onClick={() => refetch()}>
          Retry
        </button>
      </div>
    );
  }

  const tabs = [
    { key: 'meta', label: 'Meta' },
    { key: 'input', label: 'Input' },
    { key: 'output', label: 'Output', badge: data.success ? undefined : 'err' },
    ...(data.error ? [{ key: 'error', label: 'Error', badge: '!' }] : []),
  ];

  const fmtIso = (iso: string | null | undefined): string => {
    if (!iso) return '—';
    try {
      const d = new Date(iso);
      return d.toLocaleString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        fractionalSecondDigits: 3,
        hour12: false,
      });
    } catch {
      return iso;
    }
  };

  return (
    <div className="obs-event-span-detail">
      {/* Tab header */}
      <SpanDetailTabs tabs={tabs} activeKey={activeTab} onSelect={setActiveTab} />

      {/* Tab content */}
      <div className="obs-span-tab-content">
        {activeTab === 'meta' && (
          <div className="obs-span-meta-grid">
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">span.id</span>
              <span className="obs-span-meta-v mono-sm">{data.spanId}</span>
            </div>
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">event.type</span>
              <span className="obs-span-meta-v mono-sm">{data.eventType}</span>
            </div>
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">name</span>
              <span className="obs-span-meta-v mono-sm">{data.name || '—'}</span>
            </div>
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">latency</span>
              <span className="obs-span-meta-v mono-sm">{data.latencyMs} ms</span>
            </div>
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">started</span>
              <span className="obs-span-meta-v mono-sm">{fmtIso(data.startedAt)}</span>
            </div>
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">status</span>
              <span className={`obs-span-meta-v mono-sm ${data.success ? 'obs-tag-ok' : 'obs-tag-err'}`}>
                {data.success ? 'ok' : 'error'}
              </span>
            </div>
          </div>
        )}

        {activeTab === 'input' && (
          <section className="obs-payload-viewer">
            <header className="obs-payload-head">
              <h4 className="obs-payload-title">Input</h4>
            </header>
            <pre className="obs-payload-pre">{data.input ?? '—'}</pre>
          </section>
        )}

        {activeTab === 'output' && (
          <section className="obs-payload-viewer">
            <header className="obs-payload-head">
              <h4 className="obs-payload-title">Output</h4>
            </header>
            <pre className={`obs-payload-pre ${data.success ? '' : 'obs-payload-pre--err'}`}>
              {data.success ? (data.output ?? '—') : (data.error || data.output || 'error')}
            </pre>
          </section>
        )}

        {activeTab === 'error' && data.error && (
          <section className="obs-payload-viewer">
            <header className="obs-payload-head">
              <h4 className="obs-payload-title">Error</h4>
            </header>
            <pre className="obs-payload-pre obs-payload-pre--err">{data.error}</pre>
          </section>
        )}
      </div>
    </div>
  );
};

export default EventSpanDetailView;
