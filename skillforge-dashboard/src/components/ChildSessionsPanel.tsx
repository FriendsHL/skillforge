import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Card, List, Tag, Typography, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { getChildSessions, extractList } from '../api';

const { Text } = Typography;

interface ChildSession {
  id: string;
  agentId?: number | null;
  title?: string | null;
  depth?: number | null;
  runtimeStatus?: string | null;
  messageCount?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  subAgentRunId?: string | null;
}

interface Props {
  sessionId?: string;
  parentRunning: boolean;
  agents?: Array<{ id: number; name: string }>;
}

const statusColor = (status?: string | null) => {
  switch (status) {
    case 'running':
      return 'processing';
    case 'idle':
      return 'default';
    case 'waiting_user':
      return 'warning';
    case 'error':
      return 'error';
    default:
      return 'default';
  }
};

const ChildSessionsPanel: React.FC<Props> = ({ sessionId, parentRunning, agents }) => {
  const [children, setChildren] = useState<ChildSession[]>([]);
  const navigate = useNavigate();
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const agentNameById = useCallback(
    (id?: number | null) => {
      if (id == null) return null;
      const hit = (agents ?? []).find((a) => a.id === id);
      return hit?.name ?? null;
    },
    [agents]
  );

  const fetchChildren = useCallback(async () => {
    if (!sessionId) return;
    try {
      const res = await getChildSessions(sessionId, 1);
      const list = extractList<ChildSession>(res);
      list.sort((a, b) => {
        const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return tb - ta;
      });
      setChildren(list);
    } catch {
      // swallow — panel is non-critical
    }
  }, [sessionId]);

  useEffect(() => {
    setChildren([]);
    if (sessionId) fetchChildren();
  }, [sessionId, fetchChildren]);

  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    if (sessionId && parentRunning) {
      pollRef.current = setInterval(fetchChildren, 3000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [sessionId, parentRunning, fetchChildren]);

  const wasRunningRef = useRef(parentRunning);
  useEffect(() => {
    if (wasRunningRef.current && !parentRunning && sessionId) {
      fetchChildren();
    }
    wasRunningRef.current = parentRunning;
  }, [parentRunning, sessionId, fetchChildren]);

  if (!sessionId || children.length === 0) {
    return null;
  }

  return (
    <Card
      size="small"
      title="Child sessions"
      style={{ margin: '8px 12px 0' }}
      styles={{ body: { padding: '4px 0' } }}
    >
      <List
        size="small"
        dataSource={children}
        renderItem={(child) => {
          const agentLabel =
            agentNameById(child.agentId) ?? `Agent #${child.agentId ?? '?'}`;
          const title =
            child.title && child.title !== 'New Session'
              ? child.title
              : `Session ${child.id.slice(0, 8)}...`;
          return (
            <List.Item
              style={{ padding: '6px 12px' }}
              actions={[
                <Button
                  key="open"
                  size="small"
                  type="link"
                  onClick={() => navigate(`/chat/${child.id}`)}
                >
                  Open child
                </Button>,
              ]}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Text strong ellipsis style={{ maxWidth: 220 }}>
                    {title}
                  </Text>
                  <Tag color={statusColor(child.runtimeStatus)} style={{ marginRight: 0 }}>
                    {child.runtimeStatus || 'idle'}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    depth {child.depth ?? 0}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {child.messageCount ?? 0} msgs
                  </Text>
                </div>
                <Text type="secondary" ellipsis style={{ fontSize: 12 }}>
                  {agentLabel}
                </Text>
              </div>
            </List.Item>
          );
        }}
      />
    </Card>
  );
};

export default ChildSessionsPanel;
