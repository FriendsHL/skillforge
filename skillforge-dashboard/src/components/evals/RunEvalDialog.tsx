import { useEffect, useState } from 'react';
import { Select } from 'antd';
import { triggerEvalTask } from '../../api';
import { CLOSE_ICON, PLAY_ICON } from './evalUtils';

export default function RunEvalDialog({ agents, userId, onClose, onSuccess }: {
  agents: Record<string, unknown>[];
  userId: number;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [agentId, setAgentId] = useState('');
  const [starting, setStarting] = useState(false);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const handleRun = async () => {
    if (!agentId) return;
    setStarting(true);
    try {
      await triggerEvalTask(agentId, userId);
      onSuccess();
    } catch {
      setStarting(false);
    }
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()} style={{ width: 'min(480px, 94vw)' }}>
        <div className="sf-modal-h">
          <h3>Run Eval</h3>
          <button className="sf-drawer-close" onClick={onClose}>{CLOSE_ICON}</button>
        </div>
        <div className="sf-modal-body">
          <div className="sf-modal-field">
            <label>Target agent</label>
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
          <button className="btn-ghost-sf" onClick={onClose}>Cancel</button>
          <button className="btn-primary-sf" disabled={!agentId || starting} onClick={handleRun}>
            {starting ? 'Starting…' : <>{PLAY_ICON} Run eval</>}
          </button>
        </div>
      </div>
    </div>
  );
}
