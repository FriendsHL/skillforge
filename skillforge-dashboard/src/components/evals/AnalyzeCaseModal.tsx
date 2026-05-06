import { useEffect, useState } from 'react';
import { Select, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { analyzeEvalTask, analyzeEvalTaskItem, analyzeScenario, sendMessage } from '../../api';
import type { EvalDatasetScenario, EvalTaskItem, EvalTaskSummary } from '../../api';

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
export interface ScenarioAnalyzeContext {
  evalRunId?: string;
  compositeScore?: number;
  attribution?: string;
}

export type AnalyzeTarget =
  | { kind: 'scenario'; scenario: EvalDatasetScenario; context?: ScenarioAnalyzeContext }
  | { kind: 'task'; task: EvalTaskSummary }
  | { kind: 'item'; task: EvalTaskSummary; item: EvalTaskItem };

interface AnalyzeCaseModalProps {
  target: AnalyzeTarget;
  agents: Record<string, unknown>[];
  userId: number;
  onClose: () => void;
}

function AnalyzeCaseModal({ target, agents, userId, onClose }: AnalyzeCaseModalProps) {
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
    if (target.kind === 'scenario') {
      const { scenario, context } = target;
      lines.push(`Please analyze the eval case "${scenario.name}".`);
      if (scenario.sourceSessionId) {
        lines.push(`Source session id: ${scenario.sourceSessionId}`);
      }
      if (context?.evalRunId) {
        lines.push(`Eval task id: ${context.evalRunId}`);
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
    }

    if (target.kind === 'task') {
      const { task } = target;
      lines.push(`Please analyze eval task ${task.id}.`);
      lines.push(`Status: ${task.status}`);
      if (task.scenarioCount != null) {
        lines.push(`Scenarios: ${task.scenarioCount}`);
      }
      if (task.passCount != null || task.failCount != null) {
        lines.push(`Pass/fail: ${task.passCount ?? 0}/${task.failCount ?? 0}`);
      }
      if (task.compositeAvg != null) {
        lines.push(`Average score: ${Math.round(task.compositeAvg)}%`);
      }
      if (task.primaryAttribution) {
        lines.push(`Primary attribution: ${task.primaryAttribution}`);
      }
      if (task.datasetFilter) {
        lines.push(`Dataset filter: ${task.datasetFilter}`);
      }
      if (task.attributionSummary) {
        lines.push('');
        lines.push('Existing attribution summary:');
        lines.push(task.attributionSummary);
      }
      if (task.improvementSuggestion) {
        lines.push('');
        lines.push('Existing improvement suggestion:');
        lines.push(task.improvementSuggestion);
      }
      lines.push('');
      lines.push('Please provide:');
      lines.push('1. An overall attribution summary for the task.');
      lines.push('2. The most important failure patterns across cases.');
      lines.push('3. Concrete prompt or skill improvements with priority order.');
      lines.push('4. After the analysis, call AnalyzeEvalTask to persist the summary and suggestion back to the task.');
      return lines.join('\n');
    }

    const { task, item } = target;
    lines.push(`Please analyze eval task item ${item.id} from task ${task.id}.`);
    lines.push(`Scenario id: ${item.scenarioId}`);
    lines.push(`Status: ${item.status}`);
    if (item.compositeScore != null) {
      lines.push(`Score: ${Math.round(item.compositeScore)}%`);
    }
    if (item.attribution) {
      lines.push(`Attribution: ${item.attribution}`);
    }
    if (item.sessionId) {
      lines.push(`Execution session id: ${item.sessionId}`);
    }
    if (item.rootTraceId) {
      lines.push(`Root trace id: ${item.rootTraceId}`);
    }
    if (item.judgeRationale) {
      lines.push('');
      lines.push('Judge rationale:');
      lines.push(item.judgeRationale);
    }
    if (item.agentFinalOutput) {
      lines.push('');
      lines.push('Agent final output:');
      lines.push(item.agentFinalOutput);
    }
    lines.push('');
    lines.push('Please provide:');
    lines.push('1. Why this specific case failed or underperformed.');
    lines.push('2. Whether the root cause is prompt, skill, tool execution, or context related.');
    lines.push('3. Concrete changes that would most likely improve this case.');
    return lines.join('\n');
  };

  const createAnalysisSession = async () => {
    if (target.kind === 'scenario') {
      return analyzeScenario(target.scenario.id, { userId, agentId: Number(agentId) });
    }
    if (target.kind === 'task') {
      return analyzeEvalTask(target.task.id, { userId, agentId: Number(agentId) });
    }
    return analyzeEvalTaskItem(target.task.id, target.item.id, { userId, agentId: Number(agentId) });
  };

  const title =
    target.kind === 'task'
      ? 'Analyze task'
      : target.kind === 'item'
        ? 'Analyze case run'
        : 'Analyze case';

  const description =
    target.kind === 'task'
      ? 'Spawns a new chat for whole-task attribution and improvement planning.'
      : target.kind === 'item'
        ? 'Spawns a new chat focused on this specific case execution and its trace context.'
        : "Spawns a new chat with this case's task, score, and source context for historical analysis.";

  const handleSubmit = async () => {
    if (!agentId) return;
    setSubmitting(true);
    try {
      const res = await createAnalysisSession();
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
          <h3>{title}</h3>
          <button className="sf-drawer-close" onClick={onClose} aria-label="Close">{CLOSE_ICON}</button>
        </div>
        <div className="sf-modal-body">
          <p style={{ fontSize: 12, color: 'var(--fg-3)', marginTop: 0 }}>
            {description}
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
