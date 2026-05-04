import React, { useEffect, useState } from 'react';
import { Select, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { createSession, sendMessage } from '../../api';
import type { EvalDatasetScenario } from '../../api';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);

/**
 * EVAL-V2 D4 (M0 stretch): "Analyze this case" — opens a small modal where
 * the user picks an agent (typically a different/analysis agent), then we
 * create a fresh chat session with that agent and send an opening user
 * message that references the source session + the failure attribution. We
 * then navigate to the chat so the user can keep iterating.
 *
 * This is intentionally minimal (D4 budget ~30 BE / 40 FE lines) — when an
 * eval scenario also has a recent run with `evalRunId` / `compositeScore`,
 * the parent can pre-fill those via {@link AnalyzeContext}; otherwise the
 * opening message references just the scenario name + source session.
 */
export interface AnalyzeContext {
  evalRunId?: string;
  compositeScore?: number;
  attribution?: string;
}

interface AnalyzeCaseModalProps {
  scenario: EvalDatasetScenario;
  agents: Record<string, unknown>[];
  userId: number;
  context?: AnalyzeContext;
  onClose: () => void;
}

function AnalyzeCaseModal({ scenario, agents, userId, context, onClose }: AnalyzeCaseModalProps) {
  const navigate = useNavigate();
  const [agentId, setAgentId] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const buildPrompt = (): string => {
    const lines: string[] = [];
    lines.push(`Please analyze the eval case "${scenario.name}".`);
    if (scenario.sourceSessionId) {
      lines.push(`Source session id: ${scenario.sourceSessionId}`);
    }
    if (context?.evalRunId) {
      lines.push(`Eval run id: ${context.evalRunId}`);
    }
    if (context?.compositeScore != null) {
      lines.push(`Score: ${Math.round(context.compositeScore)}%`);
    }
    if (context?.attribution && context.attribution !== 'NONE') {
      lines.push(`Failure attribution: ${context.attribution}`);
    }
    lines.push('');
    lines.push('Task:');
    lines.push(scenario.task);
    if (scenario.oracleExpected) {
      lines.push('');
      lines.push('Expected output:');
      lines.push(scenario.oracleExpected);
    }
    lines.push('');
    lines.push('Please give:');
    lines.push('1. A failure attribution analysis (skill missing / prompt quality / context overflow / etc.)');
    lines.push('2. Concrete improvement suggestions for the agent prompt or skills.');
    return lines.join('\n');
  };

  const handleSubmit = async () => {
    if (!agentId) return;
    setSubmitting(true);
    try {
      const res = await createSession({ userId, agentId: Number(agentId) });
      const newSession = (res.data ?? {}) as { id?: string; sessionId?: string };
      const sid = String(newSession.id ?? newSession.sessionId ?? '');
      if (!sid) throw new Error('no session id returned');
      await sendMessage(sid, { message: buildPrompt(), userId });
      message.success('Analysis session created');
      navigate(`/chat/${sid}`);
      onClose();
    } catch {
      message.error('Failed to start analysis session');
      setSubmitting(false);
    }
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()} style={{ width: 'min(520px, 94vw)' }}>
        <div className="sf-modal-h">
          <h3>Analyze case</h3>
          <button className="sf-drawer-close" onClick={onClose} aria-label="Close">{CLOSE_ICON}</button>
        </div>
        <div className="sf-modal-body">
          <p style={{ fontSize: 12, color: 'var(--fg-3)', marginTop: 0 }}>
            Spawns a new chat with the selected agent, prefilled with this case's task,
            score, and source session reference. Useful for asking an analysis agent for
            failure attribution + improvement ideas.
          </p>
          <div className="sf-modal-field">
            <label>Analysis agent</label>
            <Select
              value={agentId || undefined}
              onChange={(v) => setAgentId(v ?? '')}
              placeholder="Select agent…"
              style={{ width: '100%' }}
              options={agents.map((a) => ({
                value: String(a.id),
                label: String(a.name || `Agent #${a.id}`),
              }))}
            />
          </div>
        </div>
        <div className="sf-modal-f">
          <button className="btn-ghost-sf" onClick={onClose} disabled={submitting}>Cancel</button>
          <button className="btn-primary-sf" disabled={!agentId || submitting} onClick={handleSubmit}>
            {submitting ? 'Starting…' : 'Start analysis'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default AnalyzeCaseModal;
