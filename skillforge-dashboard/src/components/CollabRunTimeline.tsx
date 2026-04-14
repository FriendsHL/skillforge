import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Tooltip, Spin } from 'antd';
import { getCollabRunTraces } from '../api';

interface TraceSpan {
  id: string;
  sessionId: string;
  spanType: string;
  name: string;
  startTimeMs: number;
  endTimeMs: number;
  durationMs: number;
  inputTokens: number;
  outputTokens: number;
  success: boolean;
}

interface Props {
  collabRunId?: string | null;
}

const spanColors: Record<string, string> = {
  LLM_CALL: '#4096ff',
  TOOL_CALL: '#52c41a',
  PEER_MESSAGE: '#722ed1',
  ASK_USER: '#fa8c16',
  COMPACT: '#8c8c8c',
};

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const secs = Math.floor(ms / 1000);
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  const remSecs = secs % 60;
  return `${mins}m ${remSecs}s`;
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

/** Build time axis tick marks. Returns an array of { pct, label }. */
function buildTimeTicks(totalDurationMs: number): { pct: number; label: string }[] {
  if (totalDurationMs <= 0) return [];
  const ticks: { pct: number; label: string }[] = [];
  // Aim for ~5 ticks
  const stepMs = Math.max(1000, Math.ceil(totalDurationMs / 5 / 1000) * 1000);
  for (let t = 0; t <= totalDurationMs; t += stepMs) {
    ticks.push({ pct: (t / totalDurationMs) * 100, label: formatDuration(t) });
  }
  return ticks;
}

const CollabRunTimeline: React.FC<Props> = ({ collabRunId }) => {
  const [spans, setSpans] = useState<TraceSpan[]>([]);
  const [loading, setLoading] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchTraces = useCallback(async () => {
    if (!collabRunId) return;
    try {
      const res = await getCollabRunTraces(collabRunId);
      setSpans(Array.isArray(res.data) ? res.data : []);
    } catch {
      // non-critical
    }
  }, [collabRunId]);

  useEffect(() => {
    setSpans([]);
    if (collabRunId) {
      setLoading(true);
      fetchTraces().finally(() => setLoading(false));
    }
  }, [collabRunId, fetchTraces]);

  // Light poll every 8s (traces are less latency-sensitive)
  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    if (collabRunId && spans.length > 0) {
      // Stop polling once all spans appear finished (heuristic: last span endTimeMs > 0)
      const anyOngoing = spans.some((s) => s.endTimeMs <= 0 || s.durationMs <= 0);
      if (anyOngoing) {
        pollRef.current = setInterval(fetchTraces, 8000);
      }
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [collabRunId, spans, fetchTraces]);

  if (!collabRunId) return null;
  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
        <Spin tip="Loading traces..." />
      </div>
    );
  }
  if (spans.length === 0) return null;

  // Group spans by sessionId
  const sessionMap = new Map<string, TraceSpan[]>();
  for (const span of spans) {
    const list = sessionMap.get(span.sessionId) ?? [];
    list.push(span);
    sessionMap.set(span.sessionId, list);
  }

  // Global time range
  const allStarts = spans.map((s) => s.startTimeMs).filter((t) => t > 0);
  const allEnds = spans.map((s) => s.endTimeMs).filter((t) => t > 0);
  const collabStartMs = Math.min(...allStarts);
  const collabEndMs = Math.max(...allEnds);
  const totalDurationMs = Math.max(1, collabEndMs - collabStartMs);

  const ticks = buildTimeTicks(totalDurationMs);

  const LANE_HEIGHT = 32;
  const LABEL_WIDTH = 120;

  return (
    <div style={{ maxHeight: 400, overflowY: 'auto' }}>
      {/* Time axis */}
      <div
        style={{
          display: 'flex',
          marginLeft: LABEL_WIDTH,
          position: 'relative',
          height: 20,
          borderBottom: '1px solid #f0f0f0',
          marginBottom: 4,
        }}
      >
        {ticks.map((tick, i) => (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: `${tick.pct}%`,
              transform: 'translateX(-50%)',
              fontSize: 10,
              color: '#999',
              whiteSpace: 'nowrap',
            }}
          >
            {tick.label}
          </div>
        ))}
      </div>

      {/* Swimlanes */}
      {Array.from(sessionMap.entries()).map(([sessionId, sessionSpans]) => {
        // Sort spans by start time
        const sorted = [...sessionSpans].sort((a, b) => a.startTimeMs - b.startTimeMs);
        // Use first span's name or sessionId as label
        const label = sessionId.length > 12 ? sessionId.slice(0, 12) + '...' : sessionId;

        return (
          <div
            key={sessionId}
            style={{
              display: 'flex',
              alignItems: 'center',
              height: LANE_HEIGHT,
              marginBottom: 2,
            }}
          >
            {/* Label */}
            <div
              style={{
                width: LABEL_WIDTH,
                flexShrink: 0,
                fontSize: 11,
                color: '#666',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                paddingRight: 8,
              }}
              title={sessionId}
            >
              {label}
            </div>

            {/* Lane track */}
            <div
              style={{
                flex: 1,
                position: 'relative',
                height: '100%',
                background: '#fafafa',
                borderRadius: 4,
              }}
            >
              {sorted.map((span) => {
                const leftPct = ((span.startTimeMs - collabStartMs) / totalDurationMs) * 100;
                const widthPct = (span.durationMs / totalDurationMs) * 100;
                const color = spanColors[span.spanType] ?? '#d9d9d9';

                return (
                  <Tooltip
                    key={span.id}
                    title={
                      <div style={{ fontSize: 11 }}>
                        <div><strong>{span.spanType}</strong>: {span.name}</div>
                        <div>Duration: {formatDuration(span.durationMs)}</div>
                        {(span.inputTokens > 0 || span.outputTokens > 0) && (
                          <div>Tokens: {formatTokens(span.inputTokens)} in / {formatTokens(span.outputTokens)} out</div>
                        )}
                        <div>Status: {span.success ? 'success' : 'failed'}</div>
                      </div>
                    }
                  >
                    <div
                      style={{
                        position: 'absolute',
                        left: `${Math.min(leftPct, 99.5)}%`,
                        width: `${Math.max(widthPct, 0.3)}%`,
                        height: '60%',
                        top: '20%',
                        borderRadius: 3,
                        background: color,
                        opacity: span.success ? 0.85 : 0.5,
                        border: span.success ? 'none' : '1px solid #ff4d4f',
                        cursor: 'pointer',
                        minWidth: 2,
                      }}
                    />
                  </Tooltip>
                );
              })}
            </div>
          </div>
        );
      })}

      {/* Legend */}
      <div
        style={{
          display: 'flex',
          gap: 12,
          marginTop: 8,
          paddingTop: 6,
          borderTop: '1px solid #f0f0f0',
          fontSize: 11,
          color: '#888',
          flexWrap: 'wrap',
        }}
      >
        {Object.entries(spanColors).map(([type, color]) => (
          <span key={type} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <span
              style={{
                display: 'inline-block',
                width: 10,
                height: 10,
                borderRadius: 2,
                background: color,
              }}
            />
            {type}
          </span>
        ))}
      </div>
    </div>
  );
};

export default CollabRunTimeline;
