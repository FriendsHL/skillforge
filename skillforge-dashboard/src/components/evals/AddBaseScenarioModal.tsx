import { useEffect, useState } from 'react';
import { Select, message } from 'antd';
import { addBaseScenario, type BaseScenarioInput, type ConversationTurn } from '../../api';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);

const ORACLE_TYPE_OPTIONS = [
  { value: 'llm_judge', label: 'llm_judge — LLM scores the output' },
  { value: 'exact_match', label: 'exact_match — output must equal expected' },
  { value: 'contains', label: 'contains — output must contain expected' },
  { value: 'regex', label: 'regex — output must match regex' },
];

interface AddBaseScenarioModalProps {
  onClose: () => void;
  /** Called after a successful POST so the parent can refetch the list. */
  onAdded?: (savedId: string) => void;
}

/**
 * EVAL-V2 Q2: operator-facing modal for adding a base eval scenario. POSTs
 * to {@code /api/eval/scenarios/base}; the BE validates / persists to
 * {@code ~/.skillforge/eval-scenarios/<id>.json} and the next eval run
 * picks it up automatically.
 *
 * <p>Most users let the BE auto-generate the id (UUID); the advanced "id"
 * field is only revealed on demand. {@code oracleType} defaults to
 * {@code llm_judge} so a user can omit {@code oracleExpected} and still
 * submit. On 409 (file exists), shows a retry hint without resetting form.
 */
const ASSISTANT_PLACEHOLDER = '<placeholder>';

const TURN_ROLE_OPTIONS = [
  { value: 'user', label: 'user' },
  { value: 'assistant', label: 'assistant (placeholder)' },
  { value: 'system', label: 'system' },
];

function AddBaseScenarioModal({ onClose, onAdded }: AddBaseScenarioModalProps) {
  const [name, setName] = useState('');
  const [task, setTask] = useState('');
  const [oracleExpected, setOracleExpected] = useState('');
  const [oracleType, setOracleType] = useState<string>('llm_judge');
  const [category, setCategory] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [explicitId, setExplicitId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // EVAL-V2 M2: multi-turn editor state. Hidden behind a toggle inside the
  // advanced disclosure — single-turn flow stays unchanged for everyday use.
  // r2 fix B2: each row carries a stable `id` used as the React key. Without
  // it (key=index), removing a middle turn caused React to reconcile DOM
  // nodes onto the wrong row — textarea state from the deleted row stuck on
  // the next row, so editing the "right" turn silently mutated the wrong
  // one. The id is FE-only; stripped at submit time so the BE keeps seeing
  // the canonical {role, content} payload.
  type TurnRow = ConversationTurn & { id: string };
  const newId = () =>
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `t-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
  const [multiTurn, setMultiTurn] = useState(false);
  const [turns, setTurns] = useState<TurnRow[]>(() => [
    { id: newId(), role: 'user', content: '' },
    { id: newId(), role: 'assistant', content: ASSISTANT_PLACEHOLDER },
  ]);

  const updateTurnById = (id: string, patch: Partial<ConversationTurn>) => {
    setTurns(prev => prev.map(t => {
      if (t.id !== id) return t;
      const merged = { ...t, ...patch } as TurnRow;
      // Assistant turns must use the placeholder literal — enforce in the editor
      // so submission can never produce an invalid spec.
      if (merged.role === 'assistant') {
        merged.content = ASSISTANT_PLACEHOLDER;
      }
      return merged;
    }));
  };
  const addTurn = (role: ConversationTurn['role']) => {
    setTurns(prev => [
      ...prev,
      { id: newId(), role, content: role === 'assistant' ? ASSISTANT_PLACEHOLDER : '' },
    ]);
  };
  const removeTurnById = (id: string) => {
    setTurns(prev => prev.filter(t => t.id !== id));
  };

  // Esc closes — same affordance as AnalyzeCaseModal so the dataset page
  // feels consistent on keyboard.
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  // Multi-turn turns must contain at least one user turn with non-empty content.
  const multiTurnValid =
    turns.length > 0 &&
    turns.some(t => t.role === 'user' && t.content.trim().length > 0) &&
    turns.every(t => t.content.trim().length > 0);

  const canSubmit =
    name.trim().length > 0 &&
    task.trim().length > 0 &&
    !submitting &&
    (!multiTurn || multiTurnValid);

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    const payload: BaseScenarioInput = {
      name: name.trim(),
      task: task.trim(),
    };
    // Only forward an id when the operator opened the advanced section and
    // filled it in — otherwise BaseScenarioService auto-generates a UUID.
    if (showAdvanced && explicitId.trim()) {
      payload.id = explicitId.trim();
    }
    if (category.trim()) payload.category = category.trim();
    // oracleExpected has weight on its own; oracleType only travels when the
    // operator changed it from the default.
    if (oracleExpected.trim() || oracleType !== 'llm_judge') {
      payload.oracle = {
        type: oracleType,
        ...(oracleExpected.trim() ? { expected: oracleExpected.trim() } : {}),
      };
    }
    // EVAL-V2 M2: forward turns only when the multi-turn toggle is on AND the
    // editor has valid content. Strip the FE-only `id` field so the BE sees
    // the canonical {role, content} shape it validates against. Trim user
    // content; assistant content stays exactly the placeholder literal (BE
    // rejects anything else).
    if (multiTurn && multiTurnValid) {
      payload.conversationTurns = turns.map(t => ({
        role: t.role,
        content: t.role === 'assistant' ? ASSISTANT_PLACEHOLDER : t.content.trim(),
      }));
    }

    try {
      const res = await addBaseScenario(payload);
      const savedId = res.data?.id ?? '';
      message.success(`Saved ${savedId || 'scenario'}`);
      if (onAdded) onAdded(savedId);
      onClose();
    } catch (err: unknown) {
      // 409 conflict: the operator picked an id that's already on disk.
      const status = (err && typeof err === 'object' && 'response' in err
        ? (err as { response?: { status?: number } }).response?.status
        : undefined);
      if (status === 409) {
        message.error('Scenario id already exists — pick a different id or retry.');
      } else {
        const apiErr = (err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { error?: string } } }).response?.data?.error
          : undefined);
        message.error(apiErr || 'Failed to save scenario');
      }
      setSubmitting(false);
    }
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()} style={{ width: 'min(560px, 94vw)' }}>
        <div className="sf-modal-h">
          <h3>新增 Base 评测场景</h3>
          <button className="sf-drawer-close" onClick={onClose} aria-label="Close">{CLOSE_ICON}</button>
        </div>
        <div className="sf-modal-body">
          <p style={{ fontSize: 12, color: 'var(--fg-3)', marginTop: 0 }}>
            写入 <code>~/.skillforge/eval-scenarios/&lt;id&gt;.json</code>，下次 eval 运行自动识别。
            id 默认自动生成，不需要手填。
          </p>

          <div className="sf-modal-field">
            <label>名称<span style={{ color: 'var(--danger)' }}> *</span></label>
            <input
              className="agents-search"
              style={{ width: '100%' }}
              placeholder="e.g. Reads input file and returns first line"
              value={name}
              onChange={e => setName(e.target.value)}
              maxLength={200}
            />
          </div>

          <div className="sf-modal-field">
            <label>
              Task 指令<span style={{ color: 'var(--danger)' }}> *</span>
              {multiTurn && (
                <span style={{ marginLeft: 8, fontSize: 11, color: 'var(--fg-4)', fontWeight: 400 }}>
                  multi-turn 仍需要 task summary（用于列表 / 搜索 / fallback 单轮 AB pipeline）
                </span>
              )}
            </label>
            <textarea
              className="agents-search"
              style={{ width: '100%', minHeight: 96, fontFamily: 'var(--font-mono)' }}
              placeholder={multiTurn
                ? 'e.g. Multi-turn debugging session — agent should ask for stack trace then identify root cause.'
                : 'e.g. Read /tmp/eval/input.txt and return only its first line.'}
              value={task}
              onChange={e => setTask(e.target.value)}
            />
            {/* r2 fix B1: surface the missing-task validation when the form
                is otherwise complete (esp. in multi-turn mode where the user
                might focus on turns and forget the task summary, hitting a
                silent disabled-button dead-end). */}
            {!task.trim() && (multiTurn ? multiTurnValid : name.trim()) && (
              <p style={{ fontSize: 11, color: 'var(--danger)', marginTop: 4 }}>
                Task summary 必填{multiTurn ? '（多轮模式也需要简短摘要）' : ''}
              </p>
            )}
          </div>

          <div className="sf-modal-field">
            <label>预期输出（可选）</label>
            <textarea
              className="agents-search"
              style={{ width: '100%', minHeight: 64, fontFamily: 'var(--font-mono)' }}
              placeholder="留空时 oracle 走 llm_judge"
              value={oracleExpected}
              onChange={e => setOracleExpected(e.target.value)}
            />
          </div>

          <div className="sf-modal-field">
            <label>Oracle 类型</label>
            <Select
              value={oracleType}
              onChange={(v) => setOracleType(v)}
              options={ORACLE_TYPE_OPTIONS}
              style={{ width: '100%' }}
            />
          </div>

          <div className="sf-modal-field">
            <label>分类（可选）</label>
            <input
              className="agents-search"
              style={{ width: '100%' }}
              placeholder="e.g. basic / tool_chain / error_recovery"
              value={category}
              onChange={e => setCategory(e.target.value)}
              maxLength={64}
            />
          </div>

          <button
            type="button"
            className="btn-ghost-sf"
            style={{ alignSelf: 'flex-start', padding: 0, height: 'auto', background: 'transparent', border: 0, color: 'var(--fg-3)', fontSize: 12 }}
            onClick={() => setShowAdvanced(v => !v)}
          >
            {showAdvanced ? '▾' : '▸'} 高级选项
          </button>

          {showAdvanced && (
            <>
              <div className="sf-modal-field">
                <label>id（可选，留空自动生成 UUID）</label>
                <input
                  className="agents-search"
                  style={{ width: '100%', fontFamily: 'var(--font-mono)' }}
                  placeholder="sc-bs-my-case (a-z 0-9 . _ -)"
                  value={explicitId}
                  onChange={e => setExplicitId(e.target.value)}
                  maxLength={64}
                />
              </div>

              {/* EVAL-V2 M2: multi-turn toggle + turns editor. Off by default;
                  flipping on switches the case to a multi-turn spec. The turns
                  editor enforces the assistant placeholder literal so the user
                  can't accidentally bake a real assistant reply into the case. */}
              <div className="sf-modal-field" style={{ borderTop: '1px solid var(--border-1)', paddingTop: 12 }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <input
                    type="checkbox"
                    checked={multiTurn}
                    onChange={e => setMultiTurn(e.target.checked)}
                  />
                  <span>多轮对话 case（multi-turn）</span>
                </label>
                <p style={{ fontSize: 11, color: 'var(--fg-4)', marginTop: 4 }}>
                  开启后场景按 <code>conversation_turns</code> 数组执行。
                  Assistant turns 自动写入 <code>{ASSISTANT_PLACEHOLDER}</code>，runtime 替换为实际响应。
                </p>
              </div>

              {multiTurn && (
                <div className="sf-modal-field">
                  <label>Turns（至少 1 个 user turn）</label>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {turns.map((t) => {
                      const isPlaceholder = t.role === 'assistant';
                      return (
                        <div
                          key={t.id}
                          style={{
                            display: 'flex',
                            gap: 8,
                            alignItems: 'flex-start',
                            padding: 8,
                            background: 'var(--bg-elev-1)',
                            border: '1px solid var(--border-1)',
                            borderRadius: 6,
                          }}
                        >
                          <Select
                            value={t.role}
                            onChange={(v) => updateTurnById(t.id, { role: v as ConversationTurn['role'] })}
                            options={TURN_ROLE_OPTIONS}
                            size="small"
                            style={{ width: 130, flexShrink: 0 }}
                          />
                          <textarea
                            className="agents-search"
                            style={{
                              flex: 1,
                              minHeight: 48,
                              fontFamily: 'var(--font-mono)',
                              fontSize: 12,
                              opacity: isPlaceholder ? 0.6 : 1,
                            }}
                            placeholder={isPlaceholder ? ASSISTANT_PLACEHOLDER : 'turn content'}
                            value={t.content}
                            disabled={isPlaceholder}
                            onChange={e => updateTurnById(t.id, { content: e.target.value })}
                          />
                          <button
                            type="button"
                            className="btn-ghost-sf"
                            style={{ height: 28, fontSize: 11, padding: '0 8px', flexShrink: 0 }}
                            onClick={() => removeTurnById(t.id)}
                            disabled={turns.length <= 1}
                            title="Remove turn"
                          >
                            ×
                          </button>
                        </div>
                      );
                    })}
                  </div>
                  <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                    <button
                      type="button"
                      className="btn-ghost-sf"
                      style={{ fontSize: 11, padding: '4px 10px' }}
                      onClick={() => addTurn('user')}
                    >
                      + user
                    </button>
                    <button
                      type="button"
                      className="btn-ghost-sf"
                      style={{ fontSize: 11, padding: '4px 10px' }}
                      onClick={() => addTurn('assistant')}
                    >
                      + assistant placeholder
                    </button>
                    <button
                      type="button"
                      className="btn-ghost-sf"
                      style={{ fontSize: 11, padding: '4px 10px' }}
                      onClick={() => addTurn('system')}
                    >
                      + system
                    </button>
                  </div>
                  {!multiTurnValid && (
                    <p style={{ fontSize: 11, color: 'var(--danger)', marginTop: 6 }}>
                      至少需要 1 个 user turn 且所有 turn 的 content 非空
                    </p>
                  )}
                </div>
              )}
            </>
          )}
        </div>
        <div className="sf-modal-f">
          <button className="btn-ghost-sf" onClick={onClose} disabled={submitting}>取消</button>
          <button className="btn-primary-sf" disabled={!canSubmit} onClick={handleSubmit}>
            {submitting ? '保存中…' : '保存场景'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default AddBaseScenarioModal;
