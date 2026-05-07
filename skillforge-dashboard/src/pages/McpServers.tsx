import React, { useState, useCallback } from 'react';
import {
  Button,
  Modal,
  Space,
  Switch,
  Table,
  Tooltip,
  Typography,
  message,
  List,
  Empty,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listMcpServers,
  deleteMcpServer,
  updateMcpServer,
  testMcpServerConnection,
  parseDeleteConflict,
  isDeleteConflict,
} from '../api/mcpServers';
import { useAuth } from '../contexts/AuthContext';
import type { McpServer, McpToolDescriptor } from '../types/mcpServer';
import McpServerStatusTag from '../components/mcp/McpServerStatusTag';
import McpServerEditDrawer from '../components/mcp/McpServerEditDrawer';

const { Text, Paragraph } = Typography;

const PLUS_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden>
    <path d="M8 3v10M3 8h10" />
  </svg>
);

/**
 * Render the `command + args` columns as a single mono-styled cell — keeps
 * the table dense and lets the user paste the rendered string back into a
 * shell as-is for spot debugging.
 */
function renderCommandCell(server: McpServer): React.ReactNode {
  const args = (server.args ?? []).join(' ');
  return (
    <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
      {server.command}
      {args ? ` ${args}` : ''}
    </Text>
  );
}

const McpServers: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [editOpen, setEditOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<McpServer | null>(null);
  /** Per-row spinner state for the "Test" button. Like P12 Schedules.tsx. */
  const [testingId, setTestingId] = useState<number | null>(null);

  const { data: servers = [], isLoading } = useQuery({
    queryKey: ['mcp-servers', userId],
    queryFn: () => listMcpServers(userId).then((r) => r.data ?? []),
    staleTime: 15_000,
  });

  const { mutate: removeServer } = useMutation({
    mutationFn: (id: number) => deleteMcpServer(id, userId),
    onSuccess: () => {
      message.success('MCP server removed');
      queryClient.invalidateQueries({ queryKey: ['mcp-servers'] });
    },
    onError: (e: unknown) => {
      // INV-12: 409 + agentNames means "still referenced by N agents".
      // Surface a Modal listing them rather than a generic toast so the
      // user can see exactly which agents to unbind first.
      if (isDeleteConflict(e)) {
        const agentNames = parseDeleteConflict(e);
        Modal.warning({
          title: 'Cannot delete — still referenced',
          width: 480,
          content: (
            <div>
              <Paragraph style={{ marginBottom: 8 }}>
                Unbind this server from the following agents first (Agents → drawer →
                Tools &amp; Skills → MCP servers), then retry the delete.
              </Paragraph>
              {agentNames.length > 0 ? (
                <List
                  size="small"
                  bordered
                  dataSource={agentNames}
                  renderItem={(name) => (
                    <List.Item style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                      {name}
                    </List.Item>
                  )}
                />
              ) : (
                <Text type="secondary">
                  Backend reported a conflict but did not list specific agents.
                </Text>
              )}
            </div>
          ),
        });
        return;
      }
      const detail = e instanceof Error ? e.message : 'unknown';
      message.error(`Delete failed: ${detail}`);
    },
  });

  const { mutate: toggleEnabled } = useMutation({
    mutationFn: (vars: { id: number; enabled: boolean }) =>
      updateMcpServer(vars.id, { enabled: vars.enabled }, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['mcp-servers'] });
    },
    onError: () => message.error('Failed to toggle enabled'),
  });

  const handleOpenCreate = useCallback(() => {
    setEditTarget(null);
    setEditOpen(true);
  }, []);

  const handleOpenEdit = useCallback((server: McpServer) => {
    setEditTarget(server);
    setEditOpen(true);
  }, []);

  const handleDelete = useCallback(
    (server: McpServer) => {
      Modal.confirm({
        title: `Remove "${server.name}"?`,
        content:
          'This stops the running stdio session and removes the configuration. ' +
          'Agents that reference this server must unbind it first.',
        okText: 'Remove',
        okType: 'danger',
        onOk: () => removeServer(server.id),
      });
    },
    [removeServer],
  );

  const handleTestConnection = useCallback(
    async (server: McpServer) => {
      setTestingId(server.id);
      try {
        const res = await testMcpServerConnection(server.id, userId);
        const body = res.data;
        if (body.success) {
          const tools: McpToolDescriptor[] = body.tools ?? [];
          Modal.info({
            title: `Connection OK — ${server.name}`,
            width: 560,
            content: (
              <div>
                <Paragraph>
                  Server reported <Text strong>{tools.length}</Text> tool
                  {tools.length === 1 ? '' : 's'}.
                </Paragraph>
                {tools.length > 0 ? (
                  <List
                    size="small"
                    bordered
                    dataSource={tools}
                    renderItem={(t) => (
                      <List.Item>
                        <div style={{ width: '100%' }}>
                          <div
                            style={{
                              fontFamily: 'var(--font-mono)',
                              fontSize: 12,
                              fontWeight: 600,
                            }}
                          >
                            {t.name}
                          </div>
                          {t.description && (
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              {t.description}
                            </Text>
                          )}
                        </div>
                      </List.Item>
                    )}
                  />
                ) : (
                  <Empty description="Server connected but advertised no tools" />
                )}
              </div>
            ),
          });
        } else {
          Modal.error({
            title: `Connection failed — ${server.name}`,
            width: 480,
            content: (
              <div>
                <Paragraph>The server could not be initialised:</Paragraph>
                <pre
                  style={{
                    fontFamily: 'var(--font-mono)',
                    fontSize: 12,
                    background: 'var(--bg-2, #1a1a1e)',
                    padding: 8,
                    borderRadius: 4,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}
                >
                  {body.error ?? 'unknown error'}
                </pre>
              </div>
            ),
          });
        }
      } catch (e: unknown) {
        const detail = e instanceof Error ? e.message : 'unknown error';
        message.error(`Test failed: ${detail}`);
      } finally {
        setTestingId(null);
      }
    },
    [userId],
  );

  const columns: ColumnsType<McpServer> = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, server) => (
        <div>
          <div style={{ fontWeight: 600, fontFamily: 'var(--font-mono)' }}>{name}</div>
          <Text type="secondary" style={{ fontSize: 11 }}>
            #{server.id}
          </Text>
        </div>
      ),
    },
    {
      title: 'Command',
      key: 'command',
      render: (_, server) => renderCommandCell(server),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 140,
      render: (status: McpServer['status']) => <McpServerStatusTag status={status} />,
    },
    {
      title: 'Tools',
      dataIndex: 'toolCount',
      key: 'toolCount',
      width: 80,
      render: (n: number | undefined) => (
        <Text style={{ fontFamily: 'var(--font-mono)' }}>
          {typeof n === 'number' ? n : '—'}
        </Text>
      ),
    },
    {
      title: 'Enabled',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled: boolean, server) => (
        <Switch
          checked={enabled}
          size="small"
          onChange={(checked) => toggleEnabled({ id: server.id, enabled: checked })}
        />
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 240,
      render: (_, server) => (
        <Space size="small">
          <Tooltip title="Edit">
            <Button size="small" onClick={() => handleOpenEdit(server)}>
              Edit
            </Button>
          </Tooltip>
          <Tooltip title="Dry-run: spawn → initialize → tools/list → close">
            <Button
              size="small"
              loading={testingId === server.id}
              onClick={() => handleTestConnection(server)}
            >
              Test
            </Button>
          </Tooltip>
          <Tooltip title="Delete">
            <Button size="small" danger onClick={() => handleDelete(server)}>
              Delete
            </Button>
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px 32px', maxWidth: 1400, margin: '0 auto' }}>
      <header
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'space-between',
          marginBottom: 24,
          gap: 16,
        }}
      >
        <div>
          <h1 style={{ margin: 0, fontSize: 24, fontWeight: 600 }}>MCP Servers</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--fg-3)', fontSize: 13 }}>
            External Model Context Protocol servers. Tools are auto-injected as{' '}
            <code>mcp_&lt;name&gt;_&lt;tool&gt;</code> for agents that opt-in.
          </p>
        </div>
        <Button type="primary" icon={PLUS_ICON} onClick={handleOpenCreate}>
          New MCP server
        </Button>
      </header>

      <Table<McpServer>
        rowKey="id"
        columns={columns}
        dataSource={servers}
        loading={isLoading}
        pagination={{ pageSize: 20 }}
        size="middle"
        locale={{
          emptyText:
            'No MCP servers configured yet — click "New MCP server" to add one.',
        }}
      />

      <McpServerEditDrawer
        open={editOpen}
        server={editTarget}
        onClose={() => setEditOpen(false)}
      />
    </div>
  );
};

export default McpServers;
