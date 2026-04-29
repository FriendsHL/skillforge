import React from 'react';
import type { SpanSummary } from '../../../types/observability';
import LlmSpanDetailView from './LlmSpanDetailView';
import ToolSpanDetailView from './ToolSpanDetailView';

interface SpanDetailPanelProps {
  span: SpanSummary | null;
}

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
        <span className="mono-sm obs-span-detail-id">{span.spanId.slice(0, 12)}</span>
        <span className="obs-span-detail-when mono-sm">{span.startedAt}</span>
      </header>
      <div className="obs-span-detail-body">
        {/* FE-B1 fix: key={span.spanId} forces remount on span switch, resetting
            PayloadViewer's internal blob state so spanA blob text never bleeds into spanB. */}
        {span.kind === 'llm' ? (
          <LlmSpanDetailView key={span.spanId} span={span} />
        ) : (
          <ToolSpanDetailView key={span.spanId} span={span} />
        )}
      </div>
    </aside>
  );
};

export default SpanDetailPanel;
