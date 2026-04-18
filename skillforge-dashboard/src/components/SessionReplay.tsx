import React, { useEffect, useMemo, useState } from 'react';
import { getSessionReplay } from '../api';
import { useAuth } from '../contexts/AuthContext';
import { IconPause, IconPlay } from './chat/ChatIcons';

interface ReplayToolCall {
  id?: string;
  name: string;
  input?: Record<string, any>;
  output?: string;
  success: boolean;
  durationMs?: number;
  timestamp?: number;
}

interface Iteration {
  iterationIndex: number;
  assistantText?: string;
  toolCalls: ReplayToolCall[];
}

interface Turn {
  turnIndex: number;
  userMessage: string;
  finalResponse?: string;
  iterationCount: number;
  inputTokens: number;
  outputTokens: number;
  modelId?: string;
  durationMs?: number;
  iterations: Iteration[];
}

interface ReplayData {
  sessionId: string;
  status: string;
  runtimeStatus: string;
  turns: Turn[];
}

const formatMs = (ms: number): string => {
  const s = Math.floor(ms / 1000);
  const mm = Math.floor(s / 60);
  const ss = (s % 60).toString().padStart(2, '0');
  return `${mm}:${ss}`;
};

const formatDuration = (ms: number): string => {
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)}m`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
};

export interface SessionReplayProps {
  sessionId: string | undefined;
}

const SessionReplay: React.FC<SessionReplayProps> = ({ sessionId }) => {
  const { userId } = useAuth();
  const [data, setData] = useState<ReplayData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [expanded, setExpanded] = useState<number>(-1);

  useEffect(() => {
    if (!sessionId) {
      setData(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    getSessionReplay(sessionId, userId)
      .then((res) => {
        if (!cancelled) setData(res.data);
      })
      .catch((e) => {
        if (!cancelled)
          setError(e?.response?.data?.error ?? 'Failed to load replay');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, userId]);

  const turns = data?.turns ?? [];
  const totalMs = useMemo(
    () => turns.reduce((s, t) => s + (t.durationMs ?? 0), 0),
    [turns],
  );

  useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => {
      setProgress((p) => {
        const n = p + 0.01;
        if (n >= 1) {
          setPlaying(false);
          return 1;
        }
        return n;
      });
    }, 120);
    return () => clearInterval(id);
  }, [playing]);

  if (!sessionId) {
    return (
      <div className="rail-empty-rd" style={{ marginTop: 60 }}>
        Select a session to view replay
      </div>
    );
  }
  if (loading) {
    return (
      <div className="rail-empty-rd" style={{ marginTop: 60 }}>
        Loading replay…
      </div>
    );
  }
  if (error) {
    return (
      <div className="rail-empty-rd" style={{ marginTop: 60 }}>
        {error}
      </div>
    );
  }
  if (!data || turns.length === 0) {
    return (
      <div className="rail-empty-rd" style={{ marginTop: 60 }}>
        No replay data
      </div>
    );
  }

  const currentMs = Math.floor(totalMs * progress);
  let acc = 0;
  let currentTurn = 0;
  for (let i = 0; i < turns.length; i++) {
    acc += turns[i].durationMs ?? 0;
    if (currentMs <= acc) {
      currentTurn = i;
      break;
    }
  }

  const totIters = turns.reduce((s, t) => s + t.iterationCount, 0);
  const totTools = turns.reduce(
    (s, t) => s + t.iterations.reduce((a, it) => a + it.toolCalls.length, 0),
    0,
  );
  const totTokens = turns.reduce(
    (s, t) => s + t.inputTokens + t.outputTokens,
    0,
  );

  return (
    <div className="replay-rd">
      <div className="replay-summary">
        <div className="replay-stat">
          <span className="val">{turns.length}</span>
          <span className="lbl">Turns</span>
        </div>
        <div className="replay-stat">
          <span className="val">{totIters}</span>
          <span className="lbl">Iterations</span>
        </div>
        <div className="replay-stat">
          <span className="val">{totTools}</span>
          <span className="lbl">Tool calls</span>
        </div>
        <div className="replay-stat">
          <span className="val">{formatMs(totalMs)}</span>
          <span className="lbl">Duration</span>
        </div>
        <div className="replay-stat">
          <span className="val">{(totTokens / 1000).toFixed(1)}k</span>
          <span className="lbl">Tokens</span>
        </div>
      </div>

      <div className="replay-scrubber">
        <div className="scrubber-head">
          <button
            type="button"
            className="scrubber-play"
            onClick={() => setPlaying((p) => !p)}
            aria-label={playing ? 'Pause' : 'Play'}
          >
            {playing ? <IconPause s={14} /> : <IconPlay s={14} />}
          </button>
          <span className="scrubber-time">
            {formatMs(currentMs)}{' '}
            <span style={{ color: 'var(--fg-4)' }}>/ {formatMs(totalMs)}</span>
          </span>
          <span style={{ marginLeft: 'auto' }}>Turn {currentTurn + 1}</span>
        </div>
        <div
          className="scrubber-track-wrap"
          onClick={(e) => {
            const rect = e.currentTarget.getBoundingClientRect();
            const pct = (e.clientX - rect.left) / rect.width;
            setProgress(Math.max(0, Math.min(1, pct)));
          }}
        >
          <div className="scrubber-track">
            <div
              className="scrubber-fill"
              style={{ width: `${progress * 100}%` }}
            />
            <div className="scrubber-ticks">
              {turns.map((_, i) => {
                const pos =
                  totalMs > 0
                    ? turns.slice(0, i).reduce((s, x) => s + (x.durationMs ?? 0), 0) /
                      totalMs
                    : 0;
                return (
                  <div
                    key={i}
                    className={`scrubber-turn-marker ${pos <= progress ? 'done' : ''}`}
                    style={{ left: `${pos * 100}%` }}
                    title={`Turn ${i + 1}`}
                  />
                );
              })}
            </div>
          </div>
        </div>
      </div>

      <div style={{ overflowY: 'auto', flex: 1, minHeight: 0, paddingBottom: 40 }}>
        {turns.map((turn, i) => {
          const allOk = turn.iterations.every((it) =>
            it.toolCalls.every((tc) => tc.success),
          );
          const isOpen = expanded === i;
          return (
            <div key={turn.turnIndex} className="turn-card">
              <div className="turn-card-inner">
                <button
                  type="button"
                  className="turn-head"
                  onClick={() => setExpanded(isOpen ? -1 : i)}
                  aria-expanded={isOpen}
                >
                  <span className="turn-num">
                    {String(i + 1).padStart(2, '0')}
                  </span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="turn-preview">
                      {turn.finalResponse || turn.userMessage}
                    </div>
                    <div
                      style={{
                        fontFamily: 'var(--font-mono)',
                        fontSize: 11,
                        color: 'var(--fg-4)',
                        marginTop: 2,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      &ldquo;{turn.userMessage}&rdquo;
                    </div>
                  </div>
                  <div className="turn-stats">
                    <span>{turn.iterationCount} iter</span>
                    {turn.durationMs != null && (
                      <span>{formatDuration(turn.durationMs)}</span>
                    )}
                    <span>
                      {((turn.inputTokens + turn.outputTokens) / 1000).toFixed(1)}k
                    </span>
                    {!allOk && <span style={{ color: 'var(--err)' }}>errors</span>}
                  </div>
                </button>
                {isOpen && (
                  <div className="turn-body">
                    {turn.iterations.map((it, j) => {
                      const iterOk = it.toolCalls.every((tc) => tc.success);
                      const totalDur = it.toolCalls.reduce(
                        (s, tc) => s + (tc.durationMs ?? 0),
                        0,
                      );
                      return (
                        <div
                          key={it.iterationIndex}
                          className={`iter-card ${iterOk ? '' : 'err'}`}
                        >
                          <div className="iter-head">
                            <span style={{ color: 'var(--fg-1)', fontWeight: 500 }}>
                              Iteration {j + 1}
                            </span>
                            <span>· {it.toolCalls.length} tools</span>
                            {totalDur > 0 && <span>· {formatDuration(totalDur)}</span>}
                            {!iterOk && (
                              <span style={{ color: 'var(--err)', marginLeft: 'auto' }}>
                                errors
                              </span>
                            )}
                          </div>
                          {it.assistantText && (
                            <div className="iter-assistant">{it.assistantText}</div>
                          )}
                        </div>
                      );
                    })}
                    <div
                      style={{
                        paddingTop: 8,
                        borderTop: '1px solid var(--border-1)',
                        fontFamily: 'var(--font-mono)',
                        fontSize: 11,
                        color: 'var(--fg-4)',
                      }}
                    >
                      {turn.modelId && <>{turn.modelId} · </>}
                      {turn.inputTokens.toLocaleString()} in /{' '}
                      {turn.outputTokens.toLocaleString()} out
                    </div>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default SessionReplay;
