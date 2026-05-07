import React from 'react';
import { Tag } from 'antd';
import type { McpServerStatus } from '../../types/mcpServer';

/**
 * Status colour mapping per the FE brief:
 *   - connected    → green   (healthy stdio session, tools/list cached)
 *   - disconnected → default (idle: enabled=false OR not yet spawned)
 *   - error        → red     (last connect attempt failed; lazy-reconnect on demand)
 *
 * Icons are inlined SVG so the component stays self-contained and does not
 * depend on `@ant-design/icons` (which is already installed but adds a
 * tree-shake step we don't need for three small glyphs).
 */
const STATUS_COLOR: Record<McpServerStatus, string> = {
  connected: 'green',
  disconnected: 'default',
  error: 'red',
};

const STATUS_LABEL: Record<McpServerStatus, string> = {
  connected: 'connected',
  disconnected: 'disconnected',
  error: 'error',
};

const ICON_CONNECTED = (
  <svg
    width={10}
    height={10}
    viewBox="0 0 10 10"
    fill="currentColor"
    aria-hidden
    style={{ marginRight: 4, verticalAlign: '-1px' }}
  >
    <circle cx="5" cy="5" r="3" />
  </svg>
);

const ICON_DISCONNECTED = (
  <svg
    width={10}
    height={10}
    viewBox="0 0 10 10"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.4"
    aria-hidden
    style={{ marginRight: 4, verticalAlign: '-1px' }}
  >
    <circle cx="5" cy="5" r="3" />
  </svg>
);

const ICON_ERROR = (
  <svg
    width={10}
    height={10}
    viewBox="0 0 10 10"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.6"
    strokeLinecap="round"
    aria-hidden
    style={{ marginRight: 4, verticalAlign: '-1px' }}
  >
    <path d="M3 3l4 4M7 3l-4 4" />
  </svg>
);

const STATUS_ICON: Record<McpServerStatus, React.ReactNode> = {
  connected: ICON_CONNECTED,
  disconnected: ICON_DISCONNECTED,
  error: ICON_ERROR,
};

export interface McpServerStatusTagProps {
  /**
   * BE-supplied connection status. May be `undefined` for rows that have
   * never been spawned (e.g. just-created `enabled=false` rows) — in that
   * case we render `disconnected` so the table never has empty cells.
   */
  status?: McpServerStatus;
}

const McpServerStatusTag: React.FC<McpServerStatusTagProps> = ({ status }) => {
  const resolved: McpServerStatus = status ?? 'disconnected';
  return (
    <Tag color={STATUS_COLOR[resolved]} style={{ marginRight: 0 }}>
      {STATUS_ICON[resolved]}
      {STATUS_LABEL[resolved]}
    </Tag>
  );
};

export default McpServerStatusTag;
