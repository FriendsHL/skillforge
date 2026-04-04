import React, { useEffect, useState } from 'react';
import { Select, List, Card, message, Typography, Empty } from 'antd';
import { useParams } from 'react-router-dom';
import ChatWindow from '../components/ChatWindow';
import type { ChatMessage } from '../components/ChatWindow';
import { getAgents, createSession, getSessions, getSessionMessages, sendMessage } from '../api';

const { Text } = Typography;

const Chat: React.FC = () => {
  const { sessionId: urlSessionId } = useParams<{ sessionId?: string }>();
  const [agents, setAgents] = useState<any[]>([]);
  const [selectedAgent, setSelectedAgent] = useState<number | undefined>();
  const [sessions, setSessions] = useState<any[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(urlSessionId);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);

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

  // Load messages when session changes
  useEffect(() => {
    if (!activeSessionId) {
      setMessages([]);
      return;
    }
    getSessionMessages(activeSessionId)
      .then((res) => {
        const list = Array.isArray(res.data) ? res.data : res.data?.data ?? [];
        setMessages(
          list.map((m: any) => {
            let textContent = '';
            let toolCalls: any[] = [];

            if (typeof m.content === 'string') {
              textContent = m.content;
            } else if (Array.isArray(m.content)) {
              const textBlocks = m.content
                .filter((b: any) => b.type === 'text')
                .map((b: any) => b.text);
              textContent = textBlocks.join('\n');
              toolCalls = m.content.filter((b: any) => b.type === 'tool_use');
            }

            return {
              role: m.role,
              content: textContent,
              toolCalls: toolCalls.length > 0 ? toolCalls : m.toolCalls,
            };
          })
        );
      })
      .catch(() => message.error('Failed to load messages'));
  }, [activeSessionId]);

  // If URL has sessionId, use it
  useEffect(() => {
    if (urlSessionId) setActiveSessionId(urlSessionId);
  }, [urlSessionId]);

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
      setMessages([]);
      message.success('Session created');
    } catch {
      message.error('Failed to create session');
    }
  };

  const handleSend = async (text: string) => {
    if (!activeSessionId) {
      // Auto-create session
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
    setMessages((prev) => [...prev, { role: 'user', content: text }]);
    setLoading(true);
    try {
      const res = await sendMessage(sid, { message: text, userId: 1 });
      const reply = res.data;
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: reply.response ?? reply.content ?? reply.message ?? JSON.stringify(reply),
          toolCalls: reply.toolCalls,
        },
      ]);
    } catch {
      message.error('Failed to send message');
    } finally {
      setLoading(false);
    }
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
            setMessages([]);
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
                    Session {sid.slice(0, 8)}...
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
          <ChatWindow messages={messages} loading={loading} onSend={handleSend} />
        ) : (
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Empty description="Select an Agent to start chatting" />
          </div>
        )}
      </Card>
    </div>
  );
};

export default Chat;
