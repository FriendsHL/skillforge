import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Select, List, Card, message, Typography, Empty, Alert, Button, Input, Segmented, Space, Modal, Tag, Table } from 'antd';
import { ArrowUpOutlined, HistoryOutlined, MessageOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import ChatWindow from '../components/ChatWindow';
import type { ChatMessage } from '../components/ChatWindow';
import SessionReplay from '../components/SessionReplay';
import SubAgentRunsPanel from '../components/SubAgentRunsPanel';
import ChildSessionsPanel from '../components/ChildSessionsPanel';
import {
  getAgents,
  createSession,
  getSessions,
  getSessionMessages,
  sendMessage,
  cancelChat,
  answerAsk,
  setSessionMode,
  getSession,
  compactSession,
  getCompactions,
} from '../api';

const { Text } = Typography;

type RuntimeStatus = 'idle' | 'running' | 'waiting_user' | 'error';
type ExecutionMode = 'ask' | 'auto';

interface PendingAskOption {
  label: string;
  description?: string;
}
interface PendingAsk {
  askId: string;
  question: string;
  context?: string;
  options: PendingAskOption[];
  allowOther: boolean;
}

/**
 * 归一化后端返回的消息列表:
 * Agent Loop 产生的历史消息里 tool_result 以 role=user 发回,会变成空文本的用户气泡。
 * 这里把这类消息过滤掉,并把 tool_result 按 tool_use_id 合并到上一条 assistant 的 toolCalls。
 */
function normalizeMessages(list: any[]): ChatMessage[] {
  const result: ChatMessage[] = [];
  const extractBlocks = (content: any) => {
    let text = '';
    const toolUseBlocks: any[] = [];
    const toolResultBlocks: any[] = [];
    if (typeof content === 'string') {
      text = content;
    } else if (Array.isArray(content)) {
      for (const b of content) {
        if (!b || typeof b !== 'object') continue;
        if (b.type === 'text' && b.text) {
          text += (text ? '\n' : '') + b.text;
        } else if (b.type === 'tool_use') {
          toolUseBlocks.push(b);
        } else if (b.type === 'tool_result') {
          toolResultBlocks.push(b);
        }
      }
    }
    return { text, toolUseBlocks, toolResultBlocks };
  };

  for (const m of list) {
    const { text, toolUseBlocks, toolResultBlocks } = extractBlocks(m.content);

    if (m.role === 'user') {
      if (toolResultBlocks.length > 0 && result.length > 0) {
        const prev = result[result.length - 1];
        if (prev.role === 'assistant' && Array.isArray(prev.toolCalls)) {
          for (const tr of toolResultBlocks) {
            const id = tr.tool_use_id ?? tr.toolUseId ?? tr.id;
            const match = prev.toolCalls.find((tc: any) => tc.id === id);
            const outputText =
              typeof tr.content === 'string'
                ? tr.content
                : Array.isArray(tr.content)
                  ? tr.content.map((c: any) => c?.text ?? JSON.stringify(c)).join('\n')
                  : JSON.stringify(tr.content ?? '');
            if (match) {
              match.output = outputText;
              match.status = tr.is_error || tr.isError ? 'error' : 'success';
            } else {
              prev.toolCalls.push({
                id,
                name: 'tool',
                output: outputText,
                status: tr.is_error || tr.isError ? 'error' : 'success',
              });
            }
          }
        }
      }
      if (!text.trim()) continue;
      result.push({ role: 'user', content: text });
    } else if (m.role === 'assistant') {
      const toolCalls = toolUseBlocks.map((b: any) => ({
        id: b.id,
        name: b.name,
        input: b.input,
      }));
      if (!text.trim() && toolCalls.length === 0) continue;
      result.push({
        role: 'assistant',
        content: text,
        toolCalls: toolCalls.length > 0 ? toolCalls : m.toolCalls,
      });
    }
  }
  return result;
}

const Chat: React.FC = () => {
  const { sessionId: urlSessionId } = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const [agents, setAgents] = useState<any[]>([]);
  const [parentSessionId, setParentSessionId] = useState<string | null>(null);
  const [sessionDepth, setSessionDepth] = useState<number>(0);
  const [selectedAgent, setSelectedAgent] = useState<number | undefined>();
  const [sessions, setSessions] = useState<any[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(urlSessionId);
  // 真理源:后端原始消息列表(WS message_appended 事件直接增量追加,避免 refetch 竞态)
  const [rawMessages, setRawMessages] = useState<any[]>([]);
  const messages = useMemo(() => normalizeMessages(rawMessages), [rawMessages]);

  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus>('idle');
  const [runtimeStep, setRuntimeStep] = useState<string>('');
  const [runtimeError, setRuntimeError] = useState<string>('');
  const [executionMode, setExecutionModeState] = useState<ExecutionMode>('ask');
  const [pendingAsk, setPendingAsk] = useState<PendingAsk | null>(null);
  const [otherInput, setOtherInput] = useState('');
  // Phase A: 进行中的工具调用(toolUseId -> 信息),用于在消息流末尾显示 spinner 卡片
  const [inflightTools, setInflightTools] = useState<Record<string, { name: string; input: any; startTs: number }>>({});
  // Phase B: LLM 流式输出累计文本,在 message_appended(assistant) 或 assistant_stream_end 时清空
  const [streamingText, setStreamingText] = useState<string>('');
  // Phase C: 正在流式到达的 tool_use input JSON 片段, key=toolUseId, value=已拼接 JSON 字符串
  // tool_use_delta 累加, tool_use_complete 清掉, tool_started 以真实输入覆盖
  const [streamingToolInputs, setStreamingToolInputs] = useState<Record<string, { name: string; jsonBuffer: string }>>({});
  // cancel 按钮飞行中状态(避免重复点击)
  const [cancelling, setCancelling] = useState(false);
  // compact badge stats (read from session detail)
  const [lightCompactCount, setLightCompactCount] = useState(0);
  const [fullCompactCount, setFullCompactCount] = useState(0);
  const [totalTokensReclaimed, setTotalTokensReclaimed] = useState(0);
  const [viewMode, setViewMode] = useState<'chat' | 'replay'>('chat');
  const [compactModalOpen, setCompactModalOpen] = useState(false);
  const [compactEvents, setCompactEvents] = useState<any[]>([]);
  const [compacting, setCompacting] = useState(false);

  const wsRef = useRef<WebSocket | null>(null);

  // URL → activeSessionId 同步(navigate('/chat/:id') 时生效)
  useEffect(() => {
    if (urlSessionId && urlSessionId !== activeSessionId) {
      setActiveSessionId(urlSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlSessionId]);

  // Load agents
  useEffect(() => {
    getAgents()
      .then((res) => {
        const list = Array.isArray(res.data) ? res.data : res.data?.data ?? [];
        setAgents(list);
      })
      .catch(() => message.error('Failed to load agents'));
  }, []);

  // Load sessions when agent selected
  useEffect(() => {
    if (selectedAgent == null) return;
    getSessions(1)
      .then((res) => {
        const list = (Array.isArray(res.data) ? res.data : res.data?.data ?? []).filter(
          (s: any) => s.agentId === selectedAgent
        );
        setSessions(list);
      })
      .catch(() => {});
  }, [selectedAgent]);

  // Load messages + session detail when session changes; also (re)connect WS
  useEffect(() => {
    // cleanup previous WS
    if (wsRef.current) {
      try { wsRef.current.close(); } catch {}
      wsRef.current = null;
    }
    setPendingAsk(null);
    setRuntimeError('');
    setInflightTools({});
    setStreamingText('');
    setStreamingToolInputs({});
    setCancelling(false);
    setViewMode('chat');
    setParentSessionId(null);
    setSessionDepth(0);

    if (!activeSessionId) {
      setRawMessages([]);
      setRuntimeStatus('idle');
      setRuntimeStep('');
      return;
    }

    // load messages
    getSessionMessages(activeSessionId, 1)
      .then((res) => {
        const list = Array.isArray(res.data) ? res.data : res.data?.data ?? [];
        setRawMessages(list);
      })
      .catch(() => message.error('Failed to load messages'));

    // load session detail for runtime status + mode
    getSession(activeSessionId, 1)
      .then((res) => {
        const s = res.data;
        setRuntimeStatus((s.runtimeStatus ?? 'idle') as RuntimeStatus);
        setRuntimeStep(s.runtimeStep ?? '');
        setRuntimeError(s.runtimeError ?? '');
        setExecutionModeState((s.executionMode ?? 'ask') as ExecutionMode);
        // 通过 URL 进入时自动选中 agent，触发 session 列表加载
        if (s.agentId != null && selectedAgent !== s.agentId) {
          setSelectedAgent(s.agentId);
        }
        setParentSessionId(s.parentSessionId ?? null);
        setSessionDepth(typeof s.depth === 'number' ? s.depth : 0);
        setLightCompactCount(s.lightCompactCount ?? 0);
        setFullCompactCount(s.fullCompactCount ?? 0);
        setTotalTokensReclaimed(s.totalTokensReclaimed ?? 0);
      })
      .catch(() => {});

    // open WS
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${window.location.host}/ws/chat/${activeSessionId}`);
    wsRef.current = ws;
    ws.onmessage = (ev) => {
      try {
        const evt = JSON.parse(ev.data);
        handleWsEvent(evt);
      } catch (e) {
        console.warn('Bad WS payload', ev.data);
      }
    };
    ws.onclose = () => {
      if (wsRef.current === ws) wsRef.current = null;
    };
    return () => {
      try { ws.close(); } catch {}
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  const handleWsEvent = (evt: any) => {
    if (!evt || !evt.type) return;
    if (evt.type === 'session_status') {
      setRuntimeStatus((evt.status ?? 'idle') as RuntimeStatus);
      setRuntimeStep(evt.step ?? '');
      setRuntimeError(evt.error ?? '');
      if (evt.status !== 'waiting_user') {
        setPendingAsk(null);
      }
      if (evt.status === 'idle' || evt.status === 'error') {
        // 终态:把任何残留 inflight / streaming 清掉
        setInflightTools({});
        setStreamingText('');
        setStreamingToolInputs({});
        setCancelling(false);
      }
    } else if (evt.type === 'message_appended') {
      const role = evt.message?.role;
      if (role === 'assistant') {
        // assistant 消息真正进入列表 → 流式临时文本可以清空了
        setStreamingText('');
      }
      // 直接把后端推过来的原始消息追加到本地 raw 列表(同一真理源,
      // 不依赖后端持久化时机,避免 refetch 竞态)
      if (evt.message) {
        setRawMessages((prev) => {
          // 乐观追加过的 user 气泡可能已经在尾部,做一次去重:
          // 尾部 role+纯文本 content 都相同则跳过
          if (prev.length > 0) {
            const last = prev[prev.length - 1];
            if (
              last &&
              last.role === evt.message.role &&
              typeof last.content === 'string' &&
              typeof evt.message.content === 'string' &&
              last.content === evt.message.content
            ) {
              return prev;
            }
          }
          return [...prev, evt.message];
        });
      }
    } else if (evt.type === 'messages_snapshot') {
      // 全量快照:直接覆盖本地 raw 列表
      if (Array.isArray(evt.messages)) {
        setRawMessages(evt.messages);
      }
    } else if (evt.type === 'ask_user') {
      setPendingAsk({
        askId: evt.askId,
        question: evt.question,
        context: evt.context,
        options: evt.options ?? [],
        allowOther: evt.allowOther !== false,
      });
      setOtherInput('');
    } else if (evt.type === 'tool_started') {
      setInflightTools((prev) => ({
        ...prev,
        [evt.toolUseId]: { name: evt.name, input: evt.input, startTs: Date.now() },
      }));
    } else if (evt.type === 'tool_finished') {
      setInflightTools((prev) => {
        const next = { ...prev };
        delete next[evt.toolUseId];
        return next;
      });
    } else if (evt.type === 'assistant_delta' || evt.type === 'text_delta') {
      // assistant_delta (legacy) 和 text_delta (new) 语义一致, 都累加到 streamingText
      const chunk = evt.type === 'assistant_delta' ? (evt.text ?? '') : (evt.delta ?? '');
      setStreamingText((prev) => prev + chunk);
    } else if (evt.type === 'tool_use_delta') {
      // LLM 正在流式组装 tool_use 的 input JSON
      setStreamingToolInputs((prev) => {
        const next = { ...prev };
        const existing = next[evt.toolUseId];
        next[evt.toolUseId] = {
          name: existing?.name || evt.toolName || 'tool',
          jsonBuffer: (existing?.jsonBuffer ?? '') + (evt.jsonFragment ?? ''),
        };
        return next;
      });
    } else if (evt.type === 'tool_use_complete') {
      // input 完整解析后, 清掉流式 buffer — 之后 tool_started 会以 inflightTools 替代
      setStreamingToolInputs((prev) => {
        const next = { ...prev };
        delete next[evt.toolUseId];
        return next;
      });
    } else if (evt.type === 'assistant_stream_end') {
      // 此时 message_appended 通常马上就到,不主动清,让 message_appended 清(避免抖动)
    } else if (evt.type === 'session_title_updated') {
      const newTitle = evt.title;
      if (newTitle) {
        setSessions((prev) =>
          prev.map((s) => {
            const sid = String(s.id ?? s.sessionId);
            return sid === evt.sessionId ? { ...s, title: newTitle } : s;
          })
        );
      }
    }
  };

  const handleNewSession = async () => {
    if (!selectedAgent) {
      message.warning('Please select an agent first');
      return;
    }
    try {
      const res = await createSession({ userId: 1, agentId: selectedAgent });
      const newSession = res.data;
      const sid = newSession.id ?? newSession.sessionId;
      setActiveSessionId(String(sid));
      setSessions((prev) => [newSession, ...prev]);
      setRawMessages([]);
      message.success('Session created');
    } catch {
      message.error('Failed to create session');
    }
  };

  const handleSend = async (text: string) => {
    if (!activeSessionId) {
      if (!selectedAgent) {
        message.warning('Please select an agent first');
        return;
      }
      try {
        const res = await createSession({ userId: 1, agentId: selectedAgent });
        const newSession = res.data;
        const sid = String(newSession.id ?? newSession.sessionId);
        setActiveSessionId(sid);
        setSessions((prev) => [newSession, ...prev]);
        await doSend(sid, text);
      } catch {
        message.error('Failed to create session');
      }
      return;
    }
    await doSend(activeSessionId, text);
  };

  const doSend = async (sid: string, text: string) => {
    // 乐观追加 user 气泡到 raw 列表;后续 message_appended 事件里会做去重
    setRawMessages((prev) => [...prev, { role: 'user', content: text }]);
    setRuntimeStatus('running');
    setRuntimeStep('Starting');
    try {
      await sendMessage(sid, { message: text, userId: 1 });
      // no need to handle response body — real updates arrive via WS
    } catch (e: any) {
      const status = e?.response?.status;
      if (status === 429) {
        message.error('服务器繁忙,请稍后再试');
      } else {
        message.error('Failed to send message');
      }
      setRuntimeStatus('idle');
    }
  };

  const handleAnswerAsk = async (answer: string) => {
    if (!pendingAsk || !activeSessionId) return;
    try {
      await answerAsk(activeSessionId, pendingAsk.askId, answer, 1);
      setPendingAsk(null);
      setOtherInput('');
    } catch {
      message.error('Failed to submit answer');
    }
  };

  const handleModeChange = async (mode: ExecutionMode) => {
    if (!activeSessionId) {
      setExecutionModeState(mode);
      return;
    }
    try {
      await setSessionMode(activeSessionId, mode, 1);
      setExecutionModeState(mode);
      message.success(`已切换为 ${mode} 模式`);
    } catch {
      message.error('Failed to switch mode');
    }
  };

  const renderBanner = () => {
    if (!activeSessionId) return null;
    // idle 时通常不显示, 但 runtimeStep === 'cancelled' 时用 warning 显示一次"已取消"
    if (runtimeStatus === 'idle' && runtimeStep !== 'cancelled') return null;
    if (runtimeStatus === 'running') {
      return (
        <Alert
          type="info"
          showIcon
          message={`Agent 正在运行${runtimeStep ? `:${runtimeStep}` : ''}`}
          action={
            <Button
              size="small"
              danger
              loading={cancelling}
              onClick={handleCancel}
            >
              ✕ 取消
            </Button>
          }
          style={{ margin: '8px 12px 0' }}
        />
      );
    }
    if (runtimeStatus === 'idle' && runtimeStep === 'cancelled') {
      return (
        <Alert
          type="warning"
          showIcon
          message="已取消"
          style={{ margin: '8px 12px 0' }}
          closable
        />
      );
    }
    if (runtimeStatus === 'waiting_user') {
      return (
        <Alert
          type="warning"
          showIcon
          message="Agent 正在等你回答"
          style={{ margin: '8px 12px 0' }}
        />
      );
    }
    if (runtimeStatus === 'error') {
      return (
        <Alert
          type="error"
          showIcon
          message={`出错了${runtimeError ? `:${runtimeError}` : ''}`}
          style={{ margin: '8px 12px 0' }}
        />
      );
    }
    return null;
  };

  const renderPendingAsk = () => {
    if (!pendingAsk) return null;
    return (
      <Card
        size="small"
        title="💬 Agent 在问你"
        style={{ margin: '8px 12px 0', borderColor: '#faad14' }}
      >
        {pendingAsk.context && (
          <div style={{ color: '#888', fontSize: 12, marginBottom: 8 }}>{pendingAsk.context}</div>
        )}
        <div style={{ fontWeight: 500, marginBottom: 12 }}>{pendingAsk.question}</div>
        <Space direction="vertical" style={{ width: '100%' }}>
          {pendingAsk.options.map((opt, i) => (
            <Button
              key={i}
              block
              style={{ textAlign: 'left', height: 'auto', padding: '8px 12px' }}
              onClick={() => handleAnswerAsk(opt.label)}
            >
              <div style={{ fontWeight: 500 }}>{opt.label}</div>
              {opt.description && (
                <div style={{ fontSize: 12, color: '#888' }}>{opt.description}</div>
              )}
            </Button>
          ))}
          {pendingAsk.allowOther && (
            <Space.Compact style={{ width: '100%', marginTop: 4 }}>
              <Input
                placeholder="或自己输入答复..."
                value={otherInput}
                onChange={(e) => setOtherInput(e.target.value)}
                onPressEnter={() => otherInput.trim() && handleAnswerAsk(otherInput.trim())}
              />
              <Button
                type="primary"
                disabled={!otherInput.trim()}
                onClick={() => handleAnswerAsk(otherInput.trim())}
              >
                发送
              </Button>
            </Space.Compact>
          )}
        </Space>
      </Card>
    );
  };

  const inputDisabled = runtimeStatus === 'waiting_user';

  // 合并正在执行的工具(inflightTools)+ 正在流式组装 input 的工具(streamingToolInputs)
  // tool_started 事件到达后, inflightTools 里的条目会覆盖同 id 的 streaming 条目
  const combinedInflightTools = useMemo(() => {
    const merged: Record<string, { name: string; input: any; startTs: number }> = {};
    for (const [id, s] of Object.entries(streamingToolInputs)) {
      merged[id] = { name: s.name, input: s.jsonBuffer, startTs: Date.now() };
    }
    for (const [id, t] of Object.entries(inflightTools)) {
      merged[id] = t;
    }
    return merged;
  }, [inflightTools, streamingToolInputs]);

  const refreshCompactStats = async () => {
    if (!activeSessionId) return;
    try {
      const res = await getSession(activeSessionId, 1);
      const s = res.data;
      setLightCompactCount(s.lightCompactCount ?? 0);
      setFullCompactCount(s.fullCompactCount ?? 0);
      setTotalTokensReclaimed(s.totalTokensReclaimed ?? 0);
    } catch {}
  };

  const handleCompactClick = async () => {
    if (!activeSessionId || compacting || runtimeStatus === 'running') return;
    setCompacting(true);
    try {
      const res = await compactSession(activeSessionId, 'full', 1, 'user clicked compact button');
      const reclaimed = res.data?.tokensReclaimed ?? 0;
      message.success(`已压缩:释放 ${reclaimed} tokens`);
      await refreshCompactStats();
      // also refetch messages so the new synthetic summary shows up
      try {
        const mres = await getSessionMessages(activeSessionId, 1);
        const list = Array.isArray(mres.data) ? mres.data : mres.data?.data ?? [];
        setRawMessages(list);
      } catch {}
    } catch (e: any) {
      const status = e?.response?.status;
      if (status === 409) {
        message.warning('Session 正在运行, 无法压缩');
      } else {
        message.error('压缩失败');
      }
    }
    setCompacting(false);
  };

  const handleOpenCompactModal = async () => {
    setCompactModalOpen(true);
    if (!activeSessionId) return;
    try {
      const res = await getCompactions(activeSessionId, 1);
      const list = Array.isArray(res.data) ? res.data : res.data?.data ?? [];
      setCompactEvents(list);
    } catch {
      setCompactEvents([]);
    }
  };

  const handleCancel = async () => {
    if (!activeSessionId || cancelling) return;
    setCancelling(true);
    try {
      await cancelChat(activeSessionId, 1);
    } catch (e: any) {
      const status = e?.response?.status;
      if (status === 409) {
        message.info('当前没有正在运行的 loop');
      } else {
        message.error('取消失败');
      }
      setCancelling(false);
    }
    // 成功: 等 WS session_status=idle 事件到达, useEffect 里会清 cancelling
  };

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 130px)', gap: 16, overflow: 'hidden' }}>
      {/* Left panel */}
      <div style={{ width: 280, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 12, overflow: 'hidden' }}>
        <Select
          placeholder="Select an Agent"
          style={{ width: '100%' }}
          value={selectedAgent}
          onChange={(v) => {
            setSelectedAgent(v);
            setActiveSessionId(undefined);
            setRawMessages([]);
          }}
          options={agents.map((a: any) => ({ label: a.name, value: a.id }))}
        />
        <a onClick={handleNewSession} style={{ fontSize: 13 }}>
          + New Session
        </a>
        <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
          <List
            size="small"
            dataSource={sessions}
            renderItem={(item: any) => {
              const sid = String(item.id ?? item.sessionId);
              return (
                <List.Item
                  onClick={() => setActiveSessionId(sid)}
                  style={{
                    cursor: 'pointer',
                    background: activeSessionId === sid ? '#e6f4ff' : undefined,
                    padding: '8px 12px',
                    borderRadius: 6,
                  }}
                >
                  <Text ellipsis style={{ width: '100%' }}>
                    {item.title && item.title !== 'New Session'
                      ? item.title
                      : `Session ${sid.slice(0, 8)}...`}
                  </Text>
                </List.Item>
              );
            }}
          />
        </div>
      </div>

      {/* Right panel - chat */}
      <Card
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' } }}
      >
        {activeSessionId || selectedAgent ? (
          <>
            {activeSessionId && (
              <div
                style={{
                  padding: '8px 12px',
                  borderBottom: '1px solid #f0f0f0',
                  display: 'flex',
                  justifyContent: 'flex-end',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                {(lightCompactCount > 0 || fullCompactCount > 0 || totalTokensReclaimed > 0) && (
                  <a
                    onClick={handleOpenCompactModal}
                    title="查看压缩历史"
                    style={{ fontSize: 12, color: '#888', marginRight: 8 }}
                  >
                    🗜 {lightCompactCount} light · {fullCompactCount} full · -{totalTokensReclaimed} tok
                  </a>
                )}
                <Segmented
                  size="small"
                  value={viewMode}
                  options={[
                    { label: <span><MessageOutlined /> Chat</span>, value: 'chat' },
                    { label: <span><HistoryOutlined /> Replay</span>, value: 'replay' },
                  ]}
                  onChange={(v) => setViewMode(v as 'chat' | 'replay')}
                />
                <Button
                  size="small"
                  disabled={runtimeStatus === 'running' || compacting}
                  loading={compacting}
                  onClick={handleCompactClick}
                  title="立即对老历史做一次 LLM 总结压缩"
                >
                  🗜 Compact (full)
                </Button>
                <Text type="secondary" style={{ fontSize: 12 }}>模式:</Text>
                <Segmented
                  size="small"
                  value={executionMode}
                  options={[
                    { label: 'ask', value: 'ask' },
                    { label: 'auto', value: 'auto' },
                  ]}
                  onChange={(v) => handleModeChange(v as ExecutionMode)}
                />
              </div>
            )}
            {activeSessionId && parentSessionId && (
              <div
                style={{
                  padding: '6px 12px',
                  borderBottom: '1px solid #f0f0f0',
                  background: '#fafafa',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <Button
                  type="link"
                  size="small"
                  icon={<ArrowUpOutlined />}
                  onClick={() => navigate(`/chat/${parentSessionId}`)}
                  style={{ padding: 0 }}
                >
                  Back to parent
                </Button>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  (SubAgent child · depth {sessionDepth})
                </Text>
              </div>
            )}
            {renderBanner()}
            <SubAgentRunsPanel
              sessionId={activeSessionId}
              parentRunning={runtimeStatus === 'running'}
            />
            <ChildSessionsPanel
              sessionId={activeSessionId}
              parentRunning={runtimeStatus === 'running'}
              agents={agents}
            />
            {viewMode === 'chat' ? (
              <>
                {renderPendingAsk()}
                <ChatWindow
                  messages={messages}
                  loading={runtimeStatus === 'running'}
                  onSend={handleSend}
                  inputDisabled={inputDisabled}
                  inflightTools={combinedInflightTools}
                  streamingText={streamingText}
                />
              </>
            ) : (
              <SessionReplay sessionId={activeSessionId} />
            )}
          </>
        ) : (
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Empty description="Select an Agent to start chatting" />
          </div>
        )}
      </Card>
      <Modal
        title="压缩历史"
        open={compactModalOpen}
        onCancel={() => setCompactModalOpen(false)}
        footer={null}
        width={900}
      >
        <Table
          size="small"
          rowKey="id"
          dataSource={compactEvents}
          pagination={{ pageSize: 10 }}
          columns={[
            {
              title: 'Time',
              dataIndex: 'triggeredAt',
              width: 160,
              render: (v: string) => (v ? new Date(v).toLocaleString() : '-'),
            },
            {
              title: 'Level',
              dataIndex: 'level',
              width: 70,
              render: (v: string) => (
                <Tag color={v === 'full' ? 'volcano' : 'blue'}>{v}</Tag>
              ),
            },
            { title: 'Source', dataIndex: 'source', width: 110 },
            { title: 'Reason', dataIndex: 'reason', ellipsis: true },
            {
              title: 'Reclaimed',
              dataIndex: 'tokensReclaimed',
              width: 100,
              render: (v: number) => `${v} tok`,
            },
            {
              title: 'Strategies',
              dataIndex: 'strategiesApplied',
              width: 200,
              ellipsis: true,
            },
          ]}
        />
      </Modal>
    </div>
  );
};

export default Chat;
