import React from 'react';
import { fmtMs } from './session-detail-utils';

interface TraceInfo {
  id: string;
  index: number;
  name: string;
  title: string; // 用户输入（截断）
  input: string; // 完整用户输入
  output: string; // assistant 输出
  status: 'ok' | 'error';
  totalMs: number;
  tokensIn: number;
  tokensOut: number;
  llmCalls: number;
  toolCalls: number;
  model: string;
  startTime: string;
  /**
   * OBS-4 M2 — root_trace_id for the investigation this trace belongs to.
   * For pre-OBS-4 traces (V45 backfill set root_trace_id = trace_id) and
   * for the first trace of a fresh user message, this equals `id`. Used by
   * SessionDetail to fetch the full tree via GET /api/traces/{rootId}/tree.
   */
  rootTraceId: string;
}

interface TraceSidebarProps {
  traces: TraceInfo[];
  selectedTraceId: string | null;
  onSelectTrace: (id: string) => void;
}

function fmtTime(iso: string): string {
  if (!iso) return '—';
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
      <aside className="tr-runs">
        <div className="tr-runs-h">
          <div className="tr-filter-chips">
            <span className="tr-chip">traces · 0</span>
          </div>
        </div>
        <div className="tr-empty">No traces in this session.</div>
      </aside>
    );
  }

  return (
    <aside className="tr-runs">
      <div className="tr-runs-h">
        <div className="tr-filter-chips">
          <span className="tr-chip on">traces · {traces.length}</span>
        </div>
      </div>
      <div className="tr-runs-list">
        {traces.map((trace) => {
          const totalTokens = trace.tokensIn + trace.tokensOut;
          const isSelected = trace.id === selectedTraceId;

          return (
            <button
              key={trace.id}
              type="button"
              className={`tr-run ${isSelected ? 'sel' : ''} ${trace.status !== 'ok' ? 'err' : ''}`}
              onClick={() => onSelectTrace(trace.id)}
            >
              <div className="tr-run-top">
                <span className={`tr-dot ${trace.status === 'ok' ? 'ok' : 'err'}`} />
                <span className="tr-run-name">{trace.title}</span>
              </div>
              <div className="tr-run-meta">
                <span className="mono-sm">{trace.name}</span>
              </div>
              <div className="tr-run-stats">
                <span className="mono-sm">{fmtMs(trace.totalMs)}</span>
                <span className="tr-run-sep">·</span>
                <span className="mono-sm">{trace.llmCalls} LLM</span>
                <span className="tr-run-sep">·</span>
                <span className="mono-sm">{trace.toolCalls} Tool</span>
                {totalTokens > 0 && (
                  <>
                    <span className="tr-run-sep">·</span>
                    <span className="mono-sm">{totalTokens.toLocaleString()} tok</span>
                  </>
                )}
                <span className="tr-run-when">{fmtTime(trace.startTime)}</span>
              </div>
            </button>
          );
        })}
      </div>
    </aside>
  );
};

export default TraceSidebar;
export type { TraceInfo };
