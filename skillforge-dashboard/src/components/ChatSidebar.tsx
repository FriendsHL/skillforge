import React from 'react';
import { Select, Button, List, Typography, Skeleton } from 'antd';
import { TeamOutlined } from '@ant-design/icons';

const { Text } = Typography;

interface ChatSidebarProps {
  agents: any[];
  sessions: any[];
  selectedAgent: number | undefined;
  activeSessionId: string | undefined;
  onSelectAgent: (id: number) => void;
  onNewChat: () => void;
  onSelectSession: (sid: string) => void;
  loading?: boolean;
}

const ChatSidebar: React.FC<ChatSidebarProps> = ({
  agents,
  sessions,
  selectedAgent,
  activeSessionId,
  onSelectAgent,
  onNewChat,
  onSelectSession,
  loading,
}) => (
  <div
    style={{
      width: 260,
      flexShrink: 0,
      display: 'flex',
      flexDirection: 'column',
      overflow: 'hidden',
      background: 'var(--bg-sidebar)',
      borderRight: '1px solid var(--border-subtle)',
    }}
  >
    <div style={{ padding: 12 }}>
      <Select
        placeholder="Select an Agent"
        style={{ width: '100%', marginBottom: 8 }}
        value={selectedAgent}
        onChange={onSelectAgent}
        options={agents.map((a: any) => ({ label: a.name, value: a.id }))}
      />
      <Button type="primary" block onClick={onNewChat} style={{ borderRadius: 8 }}>
        + New Chat
      </Button>
    </div>
    <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }} aria-live="polite">
      {loading ? (
        <Skeleton active paragraph={{ rows: 3 }} style={{ padding: '12px 16px' }} />
      ) : (
        <List
          size="small"
          dataSource={sessions}
          locale={{ emptyText: 'No sessions yet' }}
          renderItem={(item: any) => {
            const sid = String(item.id ?? item.sessionId);
            const isActive = activeSessionId === sid;
            return (
              <List.Item
                onClick={() => onSelectSession(sid)}
                className={`sf-session-item${isActive ? ' sf-session-item--active' : ''}`}
              >
                <Text ellipsis style={{ width: '100%' }}>
                  {item.collabRunId && (
                    <TeamOutlined
                      style={{ marginRight: 4, color: 'var(--accent-primary)', fontSize: 12 }}
                      title="Team session"
                    />
                  )}
                  {item.title && item.title !== 'New Session'
                    ? item.title
                    : `Session ${sid.slice(0, 8)}...`}
                </Text>
              </List.Item>
            );
          }}
        />
      )}
    </div>
  </div>
);

export default ChatSidebar;
