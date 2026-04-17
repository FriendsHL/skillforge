import React, { useEffect, useRef, useState } from 'react';
import { Button, Card, List, Typography } from 'antd';
import { ClearOutlined } from '@ant-design/icons';

const { Text } = Typography;

interface PeerMessage {
  fromHandle: string;
  toHandle: string;
  messageId: string;
  timestamp: string;
  isBroadcast: boolean;
}

interface Props {
  collabRunId?: string | null;
}

const MAX_ENTRIES = 50;

const formatTime = (iso: string) => {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch {
    return '';
  }
};

const PeerMessageFeed: React.FC<Props> = ({ collabRunId }) => {
  const [messages, setMessages] = useState<PeerMessage[]>([]);
  const listEndRef = useRef<HTMLDivElement>(null);
  const prevCollabRunId = useRef(collabRunId);

  // Reset messages when collabRunId changes
  useEffect(() => {
    if (prevCollabRunId.current !== collabRunId) {
      setMessages([]);
      prevCollabRunId.current = collabRunId;
    }
  }, [collabRunId]);

  // Listen for collab_message_routed events via a custom event on the ws
  // We use a pattern where Chat.tsx dispatches custom events for collab WS events
  useEffect(() => {
    if (!collabRunId) return;

    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.type === 'collab_message_routed' && detail.collabRunId === collabRunId) {
        const isBroadcast = detail.toHandle === '*' || detail.broadcast === true;
        const msg: PeerMessage = {
          fromHandle: detail.fromHandle ?? '?',
          toHandle: detail.toHandle ?? '?',
          messageId: detail.messageId ?? '',
          timestamp: new Date().toISOString(),
          isBroadcast,
        };
        setMessages((prev) => {
          const next = [...prev, msg];
          return next.length > MAX_ENTRIES ? next.slice(next.length - MAX_ENTRIES) : next;
        });
      }
    };

    window.addEventListener('collab_ws_event', handler);
    return () => window.removeEventListener('collab_ws_event', handler);
  }, [collabRunId]);

  // Auto-scroll
  useEffect(() => {
    listEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  if (!collabRunId || messages.length === 0) return null;

  return (
    <Card
      size="small"
      title={`Peer messages (${messages.length})`}
      extra={
        <Button
          type="text"
          size="small"
          icon={<ClearOutlined />}
          onClick={() => setMessages([])}
          title="Clear messages"
        />
      }
      style={{ margin: '8px 12px 0' }}
      styles={{ body: { padding: '4px 0', maxHeight: 200, overflowY: 'auto' } }}
    >
      <List
        size="small"
        dataSource={messages}
        renderItem={(msg) => (
          <List.Item
            style={{
              padding: '2px 12px',
              borderBottom: 'none',
              background: msg.isBroadcast ? 'var(--accent-muted)' : undefined,
            }}
          >
            <Text style={{ fontSize: 12, fontFamily: 'monospace' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                [{formatTime(msg.timestamp)}]
              </Text>{' '}
              <Text strong style={{ fontSize: 12, color: msg.isBroadcast ? 'var(--color-info)' : undefined }}>
                {msg.fromHandle}
              </Text>
              {' \u2192 '}
              <Text strong style={{ fontSize: 12, color: msg.isBroadcast ? 'var(--color-info)' : undefined }}>
                {msg.toHandle === '*' ? 'all' : msg.toHandle}
              </Text>
              {msg.isBroadcast && (
                <Text style={{ fontSize: 11, color: 'var(--color-info)', marginLeft: 4 }}>(broadcast)</Text>
              )}
            </Text>
          </List.Item>
        )}
      />
      <div ref={listEndRef} />
    </Card>
  );
};

export default PeerMessageFeed;
