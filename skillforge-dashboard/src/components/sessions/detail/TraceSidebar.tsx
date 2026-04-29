import React from 'react';

interface TraceInfo {
  id: string;
  index: number;
  name: string;
  title: string;       // 用户输入（截断）
  input: string;       // 完整用户输入
  output: string;      // assistant 输出
  status: 'ok' | 'error';
  totalMs: number;
  tokensIn: number;
  tokensOut: number;
  llmCalls: number;
  toolCalls: number;
  model: string;
  startTime: string;
}

interface TraceSidebarProps {
  traces: TraceInfo[];
  selectedTraceId: string | null;
  onSelectTrace: (id: string) => void;
}

function fmtMs(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  return (ms / 60000).toFixed(1) + 'm';
}

function fmtTime(iso: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
}

const TraceSidebar: React.FC<TraceSidebarProps> = ({
  traces,
  selectedTraceId,
  onSelectTrace,
}) => {
  if (traces.length === 0) {
    return (
      <div className="obs-trace-sidebar obs-trace-sidebar--empty">
        <div className="obs-empty-state">No traces in this session.</div>
      </div>
    );
  }

  return (
    <div className="obs-trace-sidebar">
      {/* Header */}
      <div className="obs-trace-sidebar-header">
        <span className="obs-trace-sidebar-title mono-sm">Traces</span>
        <span className="obs-trace-sidebar-count mono-sm">{traces.length}</span>
      </div>

      {/* Trace list - similar to Traces page style */}
      <div className="obs-trace-sidebar-list">
        {traces.map((trace) => {
          const isSelected = trace.id === selectedTraceId;
          const totalTokens = trace.tokensIn + trace.tokensOut;
          
          return (
            <button
              key={trace.id}
              type="button"
              className={`obs-trace-item ${isSelected ? 'is-selected' : ''} ${trace.status === 'error' ? 'is-error' : ''}`}
              onClick={() => onSelectTrace(trace.id)}
            >
              {/* Top row: status dot + title */}
              <div className="obs-trace-item-top">
                <span className={`obs-trace-item-dot ${trace.status === 'ok' ? 'is-ok' : 'is-error'}`} />
                <span className="obs-trace-item-title">{trace.title}</span>
              </div>

              {/* Meta row: agent name */}
              <div className="obs-trace-item-meta">
                <span className="mono-sm">{trace.name}</span>
              </div>

              {/* Stats row */}
              <div className="obs-trace-item-stats mono-sm">
                <span>{fmtMs(trace.totalMs)}</span>
                <span className="obs-trace-item-sep">·</span>
                <span>{trace.llmCalls} LLM</span>
                <span className="obs-trace-item-sep">·</span>
                <span>{trace.toolCalls} Tool</span>
                {totalTokens > 0 && (
                  <>
                    <span className="obs-trace-item-sep">·</span>
                    <span>{totalTokens.toLocaleString()} tok</span>
                  </>
                )}
                <span className="obs-trace-item-when">{fmtTime(trace.startTime)}</span>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default TraceSidebar;
export type { TraceInfo };