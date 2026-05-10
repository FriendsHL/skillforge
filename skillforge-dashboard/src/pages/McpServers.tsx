import React, { useState, useCallback, useMemo } from 'react';
import {
  Modal,
  Switch,
  Tooltip,
  Typography,
  message,
  List,
  Empty,
  Spin,
} from 'antd';
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
import type { McpServer, McpToolDescriptor, McpServerStatus } from '../types/mcpServer';
import McpServerEditDrawer from '../components/mcp/McpServerEditDrawer';
import './McpServers.css';

const { Text, Paragraph } = Typography;

const PLUS_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden>
    <path d="M8 3v10M3 8h10" />
  </svg>
);

const SERVER_ICON = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden>
    <rect x="3" y="4" width="18" height="6" rx="2" />
    <rect x="3" y="14" width="18" height="6" rx="2" />
    <circle cx="7" cy="7" r="1" fill="currentColor" />
    <circle cx="7" cy="17" r="1" fill="currentColor" />
  </svg>
);

const TEST_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden>
    <path d="M2 8l4 4 8-8" />
  </svg>
);

const EDIT_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden>
    <path d="M11.5 2.5l2 2M3 13l8-8 2 2-8 8H3z" />
  </svg>
);

const DELETE_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden>
    <path d="M3 4h10M5 4V3h6v1M6 7v5M10 7v5M4 4l1 10h6l1-10" />
  </svg>
);

const SPINNER_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden style={{ animation: 'mcp-spin 0.8s linear infinite' }}>
    <circle cx="8" cy="8" r="6" strokeDasharray="30" strokeDashoffset="10" />
  </svg>
);

/**
 * Render command cell with mono styling
 */
function renderCommand(server: McpServer): string {
  const args = (server.args ?? []).join(' ');
  return args ? `${server.command} ${args}` : server.command;
}

/**
 * Status pill component with visual indicators
 */
function StatusPill({ status }: { status?: McpServerStatus }) {
  const resolved: McpServerStatus = status ?? 'disconnected';
  return (
    <span className={`mcp-status-pill ${resolved}`}>
      <span className="mcp-status-icon" />
      {resolved}
    </span>
  );
}

/**
 * Tool count badge
 */
function ToolBadge({ count }: { count?: number }) {
  const hasTools = typeof count === 'number' && count > 0;
  return (
    <span className={`mcp-tool-badge ${hasTools ? '' : 'zero'}`}>
      {hasTools ? count : '—'}
    </span>
  );
}

const McpServers: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [editOpen, setEditOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<McpServer | null>(null);
  const [testingId, setTestingId] = useState<number | null>(null);

  const { data: servers = [], isLoading } = useQuery({
    queryKey: ['mcp-servers', userId],
    queryFn: () => listMcpServers(userId).then((r) => r.data ?? []),
    staleTime: 15_000,
  });

  // Compute stats for quick bar
  const stats = useMemo(() => {
    const connected = servers.filter(s => s.status === 'connected').length;
    const disconnected = servers.filter(s => s.status === 'disconnected' || !s.status).length;
    const error = servers.filter(s => s.status === 'error').length;
    const totalTools = servers.reduce((sum, s) => sum + (s.toolCount ?? 0), 0);
    return { connected, disconnected, error, totalTools, total: servers.length };
  }, [servers]);

  const { mutate: removeServer } = useMutation({
    mutationFn: (id: number) => deleteMcpServer(id, userId),
    onSuccess: () => {
      message.success('MCP server removed');
      queryClient.invalidateQueries({ queryKey: ['mcp-servers'] });
    },
    onError: (e: unknown) => {
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

  return (
    <div className="mcp-page">
      {/* Header */}
      <header className="mcp-head">
        <div>
          <h1 className="mcp-head-title">MCP Servers</h1>
          <p className="mcp-head-sub">
            External Model Context Protocol servers. Tools are auto-injected as{' '}
            <code>mcp_&lt;name&gt;_&lt;tool&gt;</code> for agents that opt-in.
          </p>
        </div>
        <div className="mcp-head-actions">
          <button className="mcp-btn-add" onClick={handleOpenCreate}>
            {PLUS_ICON}
            New MCP server
          </button>
        </div>
      </header>

      {/* Quick stats bar */}
      {servers.length > 0 && (
        <div className="mcp-stats-bar">
          <div className="mcp-stat-item">
            <span className="mcp-stat-dot connected" />
            <span className="mcp-stat-count">{stats.connected}</span>
            <span className="mcp-stat-label">connected</span>
          </div>
          <div className="mcp-stat-item">
            <span className="mcp-stat-dot disconnected" />
            <span className="mcp-stat-count">{stats.disconnected}</span>
            <span className="mcp-stat-label">idle</span>
          </div>
          {stats.error > 0 && (
            <div className="mcp-stat-item">
              <span className="mcp-stat-dot error" />
              <span className="mcp-stat-count">{stats.error}</span>
              <span className="mcp-stat-label">error</span>
            </div>
          )}
          <div className="mcp-stat-item">
            <span className="mcp-stat-count">{stats.totalTools}</span>
            <span className="mcp-stat-label">tools</span>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="mcp-table-wrap">
        {isLoading ? (
          <div className="mcp-loading">
            <Spin />
          </div>
        ) : servers.length === 0 ? (
          <div className="mcp-empty">
            <div className="mcp-empty-icon">
              {SERVER_ICON}
            </div>
            <h3 className="mcp-empty-title">No MCP servers configured</h3>
            <p className="mcp-empty-desc">
              Add an MCP server to inject external tools into your agents.
            </p>
            <button className="mcp-btn-add" onClick={handleOpenCreate}>
              {PLUS_ICON}
              Add first server
            </button>
          </div>
        ) : (
          <table className="mcp-table">
            <thead>
              <tr>
                <th style={{ paddingLeft: 20 }}>Server</th>
                <th>Command</th>
                <th>Status</th>
                <th>Tools</th>
                <th>Enabled</th>
                <th style={{ textAlign: 'right', paddingRight: 20 }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {servers.map((server) => (
                <tr key={server.id} onClick={() => handleOpenEdit(server)}>
                  <td style={{ paddingLeft: 20 }}>
                    <div className="mcp-name-cell">
                      <span className={`mcp-name-dot ${server.status ?? 'disconnected'}`} />
                      <div className="mcp-name-text">
                        <span className="mcp-name-main">{server.name}</span>
                        {server.description && (
                          <span className="mcp-name-desc">{server.description}</span>
                        )}
                      </div>
                    </div>
                  </td>
                  <td>
                    <span className="mcp-cmd-cell">{renderCommand(server)}</span>
                  </td>
                  <td>
                    <StatusPill status={server.status} />
                  </td>
                  <td>
                    <ToolBadge count={server.toolCount} />
                  </td>
                  <td>
                    <div className="mcp-toggle-wrap">
                      <Switch
                        checked={server.enabled}
                        size="small"
                        onChange={(checked) => {
                          toggleEnabled({ id: server.id, enabled: checked });
                        }}
                        onClick={(_, e) => e.stopPropagation()}
                      />
                    </div>
                  </td>
                  <td style={{ paddingRight: 20 }}>
                    <div className="mcp-actions" onClick={(e) => e.stopPropagation()}>
                      <Tooltip title="Edit configuration">
                        <button
                          className="mcp-btn-action edit"
                          onClick={() => handleOpenEdit(server)}
                        >
                          {EDIT_ICON}
                          Edit
                        </button>
                      </Tooltip>
                      <Tooltip title="Test connection: spawn → initialize → tools/list → close">
                        <button
                          className={`mcp-btn-action test ${testingId === server.id ? 'loading' : ''}`}
                          onClick={() => handleTestConnection(server)}
                          disabled={testingId === server.id}
                        >
                          {testingId === server.id ? SPINNER_ICON : TEST_ICON}
                          Test
                        </button>
                      </Tooltip>
                      <Tooltip title="Delete server">
                        <button
                          className="mcp-btn-action delete"
                          onClick={() => handleDelete(server)}
                        >
                          {DELETE_ICON}
                        </button>
                      </Tooltip>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Edit drawer */}
      <McpServerEditDrawer
        open={editOpen}
        server={editTarget}
        onClose={() => setEditOpen(false)}
      />
    </div>
  );
};

export default McpServers;