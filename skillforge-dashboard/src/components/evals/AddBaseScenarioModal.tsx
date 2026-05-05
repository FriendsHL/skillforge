import { useEffect, useState } from 'react';
import { Select, message } from 'antd';
import { addBaseScenario, type BaseScenarioInput } from '../../api';

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
function AddBaseScenarioModal({ onClose, onAdded }: AddBaseScenarioModalProps) {
  const [name, setName] = useState('');
  const [task, setTask] = useState('');
  const [oracleExpected, setOracleExpected] = useState('');
  const [oracleType, setOracleType] = useState<string>('llm_judge');
  const [category, setCategory] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [explicitId, setExplicitId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Esc closes — same affordance as AnalyzeCaseModal so the dataset page
  // feels consistent on keyboard.
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const canSubmit = name.trim().length > 0 && task.trim().length > 0 && !submitting;

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
            <label>Task 指令<span style={{ color: 'var(--danger)' }}> *</span></label>
            <textarea
              className="agents-search"
              style={{ width: '100%', minHeight: 96, fontFamily: 'var(--font-mono)' }}
              placeholder={"e.g. Read /tmp/eval/input.txt and return only its first line."}
              value={task}
              onChange={e => setTask(e.target.value)}
            />
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
