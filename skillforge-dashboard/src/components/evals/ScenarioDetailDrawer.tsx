import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  getScenarioRecentRuns,
  getAnalysisSessions,
  type ConversationTurn,
  type EvalDatasetScenario,
  type ScenarioRecentRun,
  type AnalysisSession,
} from '../../api';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);

interface ScenarioDetailDrawerProps {
  scenario: EvalDatasetScenario;
  /** EVAL-V2 Q1: needed to query analysis sessions filtered by current user. */
  userId?: number;
  onClose: () => void;
  onAnalyze?: (scenario: EvalDatasetScenario) => void;
}

function scoreColor(score01: number): string {
  if (score01 >= 0.9) return '#3a7d54';
  if (score01 >= 0.75) return '#3a527d';
  if (score01 >= 0.6) return '#b07a3a';
  return '#8a2a2a';
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/**
 * EVAL-V2 M2: render a multi-turn conversation as role-tagged bubbles.
 * Assistant turns whose content is the literal '<placeholder>' are styled
 * as faded placeholders (no actual response yet — fills in at runtime). User
 * turns get a left-aligned filled bubble; assistants and system get a
 * surface-tinted bubble on the right.
 */
function ConversationTurns({ turns }: { turns: ConversationTurn[] }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {turns.map((t, i) => {
        const isUser = t.role === 'user';
        const isPlaceholder = t.role === 'assistant' && t.content === '<placeholder>';
        const isSystem = t.role === 'system' || t.role === 'tool';
        return (
          <div
            key={i}
            style={{
              alignSelf: isUser ? 'flex-start' : 'flex-end',
              maxWidth: '85%',
              padding: '8px 12px',
              borderRadius: 8,
              background: isUser
                ? 'var(--bg-elev-1, #1a1a1e)'
                : isSystem
                  ? 'var(--bg-elev-2, #232328)'
                  : 'var(--bg-elev-1, #1a1a1e)',
              border: '1px solid var(--border-1, #2c2c33)',
              fontSize: 13,
              lineHeight: 1.5,
              opacity: isPlaceholder ? 0.55 : 1,
              fontStyle: isPlaceholder ? 'italic' : 'normal',
              color: isPlaceholder ? 'var(--fg-3)' : 'var(--fg-1)',
            }}
          >
            <div style={{
              fontSize: 10,
              fontFamily: 'var(--font-mono)',
              textTransform: 'uppercase',
              color: 'var(--fg-4)',
              letterSpacing: 0.5,
              marginBottom: 4,
            }}>
              {t.role}
            </div>
            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {isPlaceholder ? '⟨ assistant response — populated at runtime ⟩' : t.content}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function RecentRunsTrend({ runs }: { runs: ScenarioRecentRun[] }) {
  if (runs.length < 2) return null;
  const w = 320;
  const h = 48;
  const pts = runs
    .slice()
    .reverse() // chronological order, oldest left
    .map((r, i) => {
      const score = (r.compositeScore ?? 0) / 100;
      const x = (i / (runs.length - 1)) * w;
      const y = h - score * h;
      return `${x},${y}`;
    });
  return (
    <svg width={w} height={h} style={{ display: 'block', marginBottom: 8 }}>
      <polyline points={pts.join(' ')} fill="none" stroke="var(--accent, #6366f1)" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  );
}

function ScenarioDetailDrawer({ scenario, userId, onClose, onAnalyze }: ScenarioDetailDrawerProps) {
  const navigate = useNavigate();

  // Esc to close
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const { data: recentRuns = [], isLoading } = useQuery({
    queryKey: ['scenario-recent-runs', scenario.id],
    queryFn: () => getScenarioRecentRuns(scenario.id, 5).then(r => r.data ?? []),
    enabled: !!scenario.id,
  });

  // EVAL-V2 Q1: list of prior analysis sessions linked to this scenario.
  // Disabled when userId is not provided (e.g., legacy callers); the BE
  // requires userId so the empty-userId path would 400 anyway.
  const { data: analysisSessions = [] } = useQuery({
    queryKey: ['scenario-analysis-sessions', scenario.id, userId],
    queryFn: () =>
      userId != null
        ? getAnalysisSessions(scenario.id, userId).then(r => r.data ?? [])
        : Promise.resolve([] as AnalysisSession[]),
    enabled: !!scenario.id && userId != null,
  });

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div style={{ minWidth: 0 }}>
              <h2 className="sf-drawer-title" style={{ wordBreak: 'break-word' }}>{scenario.name}</h2>
              <p className="sf-drawer-subtitle">
                {scenario.category} · {scenario.split} · {scenario.oracleType}
              </p>
            </div>
            {onAnalyze && (
              <div className="sf-drawer-actions">
                <button className="btn-ghost-sf" onClick={() => onAnalyze(scenario)}>
                  Analyze
                </button>
              </div>
            )}
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`sess-status s-${scenario.status === 'active' ? 'idle' : scenario.status === 'draft' ? 'waiting' : 'error'}`}>
              {scenario.status}
            </span>
            {scenario.sourceSessionId && (
              <span className="kv-chip-sf" title={scenario.sourceSessionId}>
                source · {scenario.sourceSessionId.slice(0, 8)}
              </span>
            )}
            <span className="kv-chip-sf">created · {fmtTime(scenario.createdAt)}</span>
          </div>
        </div>

        <div className="sf-drawer-body">
          {/* EVAL-V2 M2: multi-turn cases render the conversation transcript instead
              of a single task pre. The summary `task` (a multi-turn synopsis) is still
              shown in a collapsed disclosure for context. Single-turn cases keep the
              original task / oracleExpected shape unchanged. */}
          {scenario.conversationTurns && scenario.conversationTurns.length > 0 ? (
            <>
              <div className="scn-detail-section">
                <h4>
                  Conversation
                  <span style={{
                    marginLeft: 8,
                    fontSize: 11,
                    color: 'var(--fg-4)',
                    fontWeight: 400,
                  }}>
                    {scenario.conversationTurns.length} turns · multi-turn
                  </span>
                </h4>
                <ConversationTurns turns={scenario.conversationTurns} />
              </div>
              {scenario.task && (
                <details className="scn-detail-section" style={{ cursor: 'pointer' }}>
                  <summary style={{ fontWeight: 500, fontSize: 12, color: 'var(--fg-3)' }}>
                    Task summary
                  </summary>
                  <pre style={{ marginTop: 8 }}>{scenario.task}</pre>
                </details>
              )}
            </>
          ) : (
            <div className="scn-detail-section">
              <h4>Task</h4>
              <pre>{scenario.task}</pre>
            </div>
          )}

          {scenario.oracleExpected && (
            <div className="scn-detail-section">
              <h4>Expected output</h4>
              <pre>{scenario.oracleExpected}</pre>
            </div>
          )}

          {/* EVAL-V2 M3b: description is prose, not code — render in a regular
              text block (not <pre>, which forces mono + preformatting). The
              wrapping div mimics the .scn-detail-section pre frame for visual
              consistency but uses sans body font and natural whitespace. */}
          {scenario.description && scenario.description.trim().length > 0 && (
            <div className="scn-detail-section">
              <h4>Description</h4>
              <div
                style={{
                  padding: '10px 12px',
                  background: 'var(--bg-base)',
                  border: '1px solid var(--border-1)',
                  borderRadius: 4,
                  fontSize: 13,
                  color: 'var(--fg-2)',
                  lineHeight: 1.55,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {scenario.description}
              </div>
            </div>
          )}

          {scenario.toolsHint && scenario.toolsHint.length > 0 && (
            <div className="scn-detail-section">
              <h4>Tools</h4>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {scenario.toolsHint.map(t => (
                  <span key={`tool-${t}`} className="kv-chip-sf" title={t}>{t}</span>
                ))}
              </div>
            </div>
          )}

          {scenario.tags && scenario.tags.length > 0 && (
            <div className="scn-detail-section">
              <h4>Tags</h4>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {scenario.tags.map(t => (
                  <span key={`tag-${t}`} className="kv-chip-sf" title={t}>#{t}</span>
                ))}
              </div>
            </div>
          )}

          {scenario.setupFiles && scenario.setupFiles.length > 0 && (
            <div className="scn-detail-section">
              <h4>Setup files</h4>
              <ul
                style={{
                  margin: 0,
                  padding: '8px 12px 8px 26px',
                  background: 'var(--bg-base)',
                  border: '1px solid var(--border-1)',
                  borderRadius: 4,
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11.5,
                  color: 'var(--fg-2)',
                  lineHeight: 1.6,
                }}
              >
                {scenario.setupFiles.map(f => (
                  <li key={`file-${f}`} style={{ wordBreak: 'break-all' }}>{f}</li>
                ))}
              </ul>
            </div>
          )}

          {/* EVAL-V2 M3b: technical knobs (loop / perf budget). BE always
              emits both for base scenarios with primitive defaults (10 / 30000),
              but on the agent path these are undefined — guard each so we
              don't render "max loops: undefined". */}
          {(scenario.maxLoops != null || scenario.performanceThresholdMs != null) && (
            <div className="scn-detail-section">
              <h4>Technical</h4>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {scenario.maxLoops != null && (
                  <span className="kv-chip-sf">max loops · {scenario.maxLoops}</span>
                )}
                {scenario.performanceThresholdMs != null && (
                  <span className="kv-chip-sf">perf budget · {scenario.performanceThresholdMs}ms</span>
                )}
              </div>
            </div>
          )}

          {scenario.extractionRationale && (
            <div className="scn-detail-section">
              <h4>Extraction rationale</h4>
              <pre>{scenario.extractionRationale}</pre>
            </div>
          )}

          <div className="scn-detail-section">
            <h4>Recent runs (last {recentRuns.length})</h4>
            {isLoading ? (
              <div className="sf-empty-state" style={{ padding: 12 }}>Loading…</div>
            ) : recentRuns.length === 0 ? (
              <div className="sf-empty-state" style={{ padding: 12 }}>No runs yet.</div>
            ) : (
              <>
                <RecentRunsTrend runs={recentRuns} />
                {recentRuns.map(r => {
                  const score01 = (r.compositeScore ?? 0) / 100;
                  return (
                    <div key={r.evalRunId} className="scn-recent-run">
                      <div>
                        <div className="rid">{r.evalRunId.slice(0, 8)}</div>
                        <div style={{ fontSize: 11, color: 'var(--fg-4)', marginTop: 2 }}>
                          {fmtTime(r.completedAt ?? r.startedAt)}
                        </div>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span className={`sess-status s-${r.status === 'PASS' ? 'idle' : r.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
                          {r.status}
                        </span>
                        <span style={{ color: scoreColor(score01), fontFamily: 'var(--font-mono)', fontSize: 12, fontWeight: 500, minWidth: 48, textAlign: 'right' }}>
                          {r.compositeScore != null ? `${Math.round(r.compositeScore)}%` : '—'}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </>
            )}
          </div>

          {/* EVAL-V2 Q1: prior analysis sessions for this scenario. Hidden
              when there are none so we don't add chrome to first-time uses. */}
          {analysisSessions.length > 0 && (
            <div className="scn-detail-section">
              <h4>Analysis sessions ({analysisSessions.length})</h4>
              {analysisSessions.map(s => (
                <button
                  key={s.id}
                  type="button"
                  className="scn-recent-run"
                  style={{
                    width: '100%',
                    background: 'var(--bg-surface)',
                    border: '1px solid var(--border-1)',
                    cursor: 'pointer',
                    font: 'inherit',
                    color: 'inherit',
                    textAlign: 'left',
                  }}
                  onClick={() => navigate(`/chat/${s.id}`)}
                  title={`Open analysis session ${s.id}`}
                >
                  <div style={{ minWidth: 0 }}>
                    <div className="rid" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {s.title || s.id.slice(0, 8)}
                    </div>
                    <div style={{ fontSize: 11, color: 'var(--fg-4)', marginTop: 2 }}>
                      {fmtTime(s.updatedAt ?? s.createdAt)} · {s.messageCount} msgs
                    </div>
                  </div>
                  <span className={`sess-status s-${s.runtimeStatus === 'idle' ? 'idle' : s.runtimeStatus === 'running' ? 'running' : 'waiting'}`}>
                    {s.runtimeStatus ?? s.status ?? '—'}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      </aside>
    </>
  );
}

export default ScenarioDetailDrawer;
