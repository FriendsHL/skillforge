import React, { useEffect, useRef, useCallback, useState } from 'react';
import { Button, message, Tooltip } from 'antd';
import {
  RocketOutlined, LoadingOutlined, CheckCircleOutlined,
  MinusCircleOutlined, RedoOutlined, StopOutlined,
} from '@ant-design/icons';
import {
  triggerPromptImprove, getActiveImprovement, getAbRunDetail,
  type AbRunDetail,
} from '../api';
import { useAuth } from '../contexts/AuthContext';

// ── State machine ────────────────────────────────────────────────────────────

type ImproveState =
  | { phase: 'ineligible'; reason: string }
  | { phase: 'idle' }
  | { phase: 'generating' }
  | { phase: 'ab_testing'; progress: number }
  | { phase: 'success'; delta: number; promoted: boolean }
  | { phase: 'skipped'; reason: string }
  | { phase: 'failed'; error: string };

interface ImprovePromptButtonProps {
  agentId: string;
  evalRun: {
    id: string;
    primaryAttribution?: string;
    status: string;
  };
}

const ELIGIBLE_ATTRIBUTIONS = new Set(['PROMPT_QUALITY', 'CONTEXT_OVERFLOW']);
const TOTAL_SCENARIOS = 7;

const ImprovePromptButton: React.FC<ImprovePromptButtonProps> = ({ agentId, evalRun }) => {
  const { userId } = useAuth();
  const [state, setState] = useState<ImproveState>(() => {
    if (!evalRun.primaryAttribution || !ELIGIBLE_ATTRIBUTIONS.has(evalRun.primaryAttribution)) {
      const reason = !evalRun.primaryAttribution
        ? 'No attribution'
        : evalRun.primaryAttribution;
      return { phase: 'ineligible', reason };
    }
    return { phase: 'idle' };
  });

  const activeAbRunIdRef = useRef<string | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const reconnectDelayRef = useRef(2000);

  // ── Sync state from AbRunDetail snapshot ───────────────────────────────────

  const syncFromSnapshot = useCallback((detail: AbRunDetail | null) => {
    if (!detail) return;
    activeAbRunIdRef.current = detail.abRunId;

    switch (detail.status) {
      case 'PENDING':
        setState({ phase: 'generating' });
        break;
      case 'RUNNING':
        setState({ phase: 'ab_testing', progress: detail.completedScenarios });
        break;
      case 'COMPLETED':
        setState({
          phase: 'success',
          delta: detail.deltaPassRate ?? 0,
          promoted: detail.promoted,
        });
        break;
      case 'FAILED':
        setState({ phase: 'failed', error: detail.failureReason ?? 'Unknown error' });
        break;
    }
  }, []);

  // ── Check for active improvement on mount ──────────────────────────────────

  useEffect(() => {
    if (state.phase === 'ineligible') return;

    let cancelled = false;
    getActiveImprovement(agentId)
      .then((res) => {
        if (cancelled) return;
        if (res.data) syncFromSnapshot(res.data);
      })
      .catch(() => { /* no active run, stay idle */ });

    return () => { cancelled = true; };
  }, [agentId, state.phase, syncFromSnapshot]);

  // ── WebSocket subscription ─────────────────────────────────────────────────

  useEffect(() => {
    if (state.phase === 'ineligible') return;

    const connectWs = () => {
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const token = localStorage.getItem('sf_token') ?? '';
      const ws = new WebSocket(
        `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
      );
      wsRef.current = ws;

      ws.onopen = () => {
        reconnectDelayRef.current = 2000;
        // Reconnect snapshot sync
        if (activeAbRunIdRef.current) {
          getAbRunDetail(agentId, activeAbRunIdRef.current)
            .then((res) => syncFromSnapshot(res.data))
            .catch(() => {});
        }
      };

      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          switch (msg.type) {
            case 'ab_test_started':
              activeAbRunIdRef.current = msg.abRunId ?? activeAbRunIdRef.current;
              setState({ phase: 'ab_testing', progress: 0 });
              break;
            case 'ab_scenario_finished':
              setState((prev) => {
                if (prev.phase === 'ab_testing') {
                  return { phase: 'ab_testing', progress: Math.min(prev.progress + 1, TOTAL_SCENARIOS) };
                }
                return prev;
              });
              break;
            case 'ab_test_completed':
              setState({
                phase: 'success',
                delta: msg.deltaPassRate ?? 0,
                promoted: msg.promoted ?? false,
              });
              break;
            case 'improve_paused':
              setState({ phase: 'ineligible', reason: msg.reason ?? 'Paused' });
              break;
          }
        } catch { /* ignore non-JSON */ }
      };

      ws.onclose = () => {
        if (wsRef.current !== ws) return;
        wsRef.current = null;
        const delay = reconnectDelayRef.current;
        reconnectDelayRef.current = Math.min(delay * 2, 30000);
        reconnectTimerRef.current = window.setTimeout(() => {
          reconnectTimerRef.current = null;
          if (wsRef.current === null) connectWs();
        }, delay);
      };
    };

    connectWs();

    return () => {
      if (reconnectTimerRef.current != null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
        reconnectDelayRef.current = 2000;
      }
      const ws = wsRef.current;
      wsRef.current = null;
      try { ws?.close(); } catch { /* ignore */ }
    };
  }, [agentId, state.phase, syncFromSnapshot]);

  // ── Trigger ────────────────────────────────────────────────────────────────

  const handleClick = async () => {
    if (state.phase !== 'idle' && state.phase !== 'failed') return;

    setState({ phase: 'generating' });
    try {
      const res = await triggerPromptImprove(agentId, evalRun.id);
      activeAbRunIdRef.current = res.data.abRunId;
    } catch (err: unknown) {
      const status =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { status?: number } }).response?.status
          : undefined;
      if (status === 409) {
        message.warning('Already have an active improvement running');
        setState({ phase: 'generating' });
      } else {
        const msg =
          err && typeof err === 'object' && 'message' in err
            ? (err as { message: string }).message
            : 'Unknown error';
        setState({ phase: 'failed', error: msg });
        message.error('Failed to trigger improvement');
      }
    }
  };

  // ── Render ─────────────────────────────────────────────────────────────────

  const renderButton = () => {
    const baseStyle: React.CSSProperties = {
      borderRadius: 'var(--radius-sm)',
      fontFamily: 'var(--font-family)',
      fontSize: 'var(--font-size-xs)',
      fontWeight: 500,
      transition: 'all var(--transition-base)',
    };

    switch (state.phase) {
      case 'ineligible':
        return (
          <Tooltip title={`Attribution "${state.reason}" is not eligible for prompt improvement`}>
            <Button
              size="small"
              disabled
              icon={<StopOutlined />}
              style={{
                ...baseStyle,
                opacity: 0.5,
              }}
            >
              Improve Prompt ({state.reason})
            </Button>
          </Tooltip>
        );

      case 'idle':
        return (
          <Button
            size="small"
            type="primary"
            icon={<RocketOutlined />}
            onClick={handleClick}
            style={{
              ...baseStyle,
              background: 'var(--accent-primary)',
              borderColor: 'var(--accent-primary)',
            }}
          >
            Improve Prompt
          </Button>
        );

      case 'generating':
        return (
          <Button
            size="small"
            loading
            disabled
            icon={<LoadingOutlined />}
            style={{
              ...baseStyle,
              borderColor: 'var(--accent-primary)',
              color: 'var(--accent-primary)',
            }}
          >
            Generating candidate...
          </Button>
        );

      case 'ab_testing':
        return (
          <Button
            size="small"
            loading
            disabled
            style={{
              ...baseStyle,
              borderColor: 'var(--accent-primary)',
              color: 'var(--accent-primary)',
            }}
          >
            A/B Testing ({state.progress}/{TOTAL_SCENARIOS})...
          </Button>
        );

      case 'success':
        return state.promoted ? (
          <Button
            size="small"
            disabled
            icon={<CheckCircleOutlined />}
            style={{
              ...baseStyle,
              background: 'var(--bg-hover)',
              borderColor: 'var(--color-success)',
              color: 'var(--color-success)',
            }}
          >
            Promoted (+{state.delta.toFixed(1)}pp)
          </Button>
        ) : (
          <Button
            size="small"
            disabled
            icon={<MinusCircleOutlined />}
            style={{
              ...baseStyle,
              color: 'var(--text-secondary)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            Below threshold ({state.delta >= 0 ? '+' : ''}{state.delta.toFixed(1)}pp)
          </Button>
        );

      case 'skipped':
        return (
          <Tooltip title={state.reason}>
            <Button
              size="small"
              disabled
              style={{
                ...baseStyle,
                color: 'var(--text-muted)',
              }}
            >
              Skipped
            </Button>
          </Tooltip>
        );

      case 'failed':
        return (
          <Tooltip title={state.error}>
            <Button
              size="small"
              type="default"
              danger
              icon={<RedoOutlined />}
              onClick={handleClick}
              style={baseStyle}
            >
              Retry
            </Button>
          </Tooltip>
        );
    }
  };

  return (
    <div style={{ marginTop: 'var(--sp-3)' }}>
      {renderButton()}
    </div>
  );
};

export default React.memo(ImprovePromptButton);
