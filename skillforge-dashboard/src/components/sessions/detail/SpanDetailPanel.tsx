import React from 'react';
import type { SpanSummary } from '../../../types/observability';
import LlmSpanDetailView from './LlmSpanDetailView';
import ToolSpanDetailView from './ToolSpanDetailView';
import EventSpanDetailView from './EventSpanDetailView';

interface SpanDetailPanelProps {
  span: SpanSummary | null;
}

function copyText(text: string) {
  if (typeof navigator !== 'undefined' && navigator.clipboard) {
    navigator.clipboard.writeText(text).catch(() => {});
  }
}

const CopyBtn: React.FC<{ text: string; title?: string }> = ({ text, title = 'Copy' }) => (
  <button
    type="button"
    className="mini-btn"
    onClick={() => copyText(text)}
    title={title}
    style={{ padding: '2px 6px', fontSize: 11, marginLeft: 4 }}
  >
    📋
  </button>
);

/**
 * Right-pane container that switches between LLM and Tool detail views based
 * on the discriminator field `kind`. See plan §8.2 / §8.3.
 */
const SpanDetailPanel: React.FC<SpanDetailPanelProps> = ({ span }) => {
  if (!span) {
    return (
      <aside className="obs-span-detail-panel obs-span-detail-panel--empty">
        <div className="obs-empty-state">点击左侧 span 查看详情</div>
      </aside>
    );
  }

  return (
    <aside className="obs-span-detail-panel">
      <header className="obs-span-detail-head">
        <span className={`obs-kind-tag obs-kind-tag--${span.kind}`}>{span.kind}</span>
        <span className="mono-sm obs-span-detail-id">{span.spanId.slice(0, 12)}…</span>
        <CopyBtn text={span.spanId} title="Copy span ID" />
        <span className="obs-span-detail-when mono-sm">{span.startedAt}</span>
      </header>
      <div className="obs-span-detail-body">
        {/* FE-B1 fix: key={span.spanId} forces remount on span switch, resetting
            PayloadViewer's internal blob state so spanA blob text never bleeds into spanB. */}
        {span.kind === 'llm' && (
          <LlmSpanDetailView key={span.spanId} span={span} />
        )}
        {span.kind === 'tool' && (
          <ToolSpanDetailView key={span.spanId} span={span} />
        )}
        {span.kind === 'event' && (
          <EventSpanDetailView key={span.spanId} span={span} />
        )}
      </div>
    </aside>
  );
};

export default SpanDetailPanel;
