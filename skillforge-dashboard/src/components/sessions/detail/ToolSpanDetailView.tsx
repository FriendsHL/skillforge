import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { ToolSpanSummary, ToolSpanDetail } from '../../../types/observability';
import { getToolSpanDetail } from '../../../api';
import { useAuth } from '../../../contexts/AuthContext';
import SubagentJumpLink from './SubagentJumpLink';
import SpanDetailTabs from './SpanDetailTabs';

interface ToolSpanDetailViewProps {
  span: ToolSpanSummary;
}

/**
 * Tool span detail panel with tab-based navigation.
 *
 * - Meta tab: toolName, toolUseId, latency, status
 * - Input tab: full input payload
 * - Output tab: full output payload
 * - Error tab (if failed): error message
 * - Subagent link rendered above tabs when toolName === 'SubAgent'
 */
const ToolSpanDetailView: React.FC<ToolSpanDetailViewProps> = ({ span }) => {
  const { userId } = useAuth();
  const [activeTab, setActiveTab] = useState('meta');

  const { data, isLoading, isError, refetch } = useQuery<ToolSpanDetail>({
    queryKey: ['obs-tool-span-detail', span.spanId, userId],
    queryFn: async () => {
      const res = await getToolSpanDetail(span.spanId, userId);
      return res.data;
    },
    staleTime: 60_000,
  });

  if (isLoading) {
    return <div className="obs-empty-state">Loading tool span detail…</div>;
  }
  if (isError || !data) {
    return (
      <div className="obs-empty-state">
        Failed to load tool span detail.{' '}
        <button type="button" className="btn-ghost-sf" onClick={() => refetch()}>
          Retry
        </button>
      </div>
    );
  }

  const showSubagentLink = data.toolName === 'SubAgent' && Boolean(data.subagentSessionId);

  // Build tabs list - show error tab only if there's an error
  const tabs = [
    { key: 'meta', label: 'Meta' },
    { key: 'input', label: 'Input' },
    { key: 'output', label: 'Output', badge: data.success ? undefined : 'err' },
    ...(data.error ? [{ key: 'error', label: 'Error', badge: '!' }] : []),
  ];

  return (
    <div className="obs-tool-span-detail">
      {/* Subagent jump link */}
      {showSubagentLink && data.subagentSessionId && (
        <div className="obs-subagent-jump-row">
          <SubagentJumpLink targetSessionId={data.subagentSessionId} />
        </div>
      )}

      {/* Tab header */}
      <SpanDetailTabs tabs={tabs} activeKey={activeTab} onSelect={setActiveTab} />

      {/* Tab content */}
      <div className="obs-span-tab-content">
        {activeTab === 'meta' && (
          <div className="obs-span-meta-grid">
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">tool</span>
              <span className="obs-span-meta-v mono-sm">{data.toolName}</span>
            </div>
            {data.toolUseId && (
              <div className="obs-span-meta-row">
                <span className="obs-span-meta-k">tool_use_id</span>
                <span className="obs-span-meta-v mono-sm">{data.toolUseId}</span>
              </div>
            )}
            <div className="obs-span-meta-row">
              <span className="obs-span-meta-k">latency</span>
              <span className="obs-span-meta-v mono-sm">{data.latencyMs} ms</span>
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

export default ToolSpanDetailView;