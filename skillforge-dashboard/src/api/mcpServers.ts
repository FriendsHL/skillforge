import api from './index';
import type {
  McpServer,
  McpServerCreate,
  McpServerUpdate,
  TestConnectionResponse,
  DeleteConflictBody,
} from '../types/mcpServer';

/**
 * MCP-CLIENT-MVP — REST client for `/api/mcp-servers`.
 *
 * Ownership: every endpoint takes `userId` as a **query parameter**, mirror-
 * ing P12 `schedules.ts`. Although MCP servers are global-scope (admin-only
 * CRUD per INV-9), the BE still requires `userId` in order to reuse the
 * same `@RequestParam Long userId` ownership pattern across `/api/*`. We do
 * NOT also stuff `userId` into the JSON body — that's a P10 commands.ts
 * pattern specific to its body-only signature, not a project-wide rule.
 */

export const listMcpServers = (userId: number) =>
  api.get<McpServer[]>('/mcp-servers', { params: { userId } });

export const getMcpServer = (id: number, userId: number) =>
  api.get<McpServer>(`/mcp-servers/${id}`, { params: { userId } });

export const createMcpServer = (data: McpServerCreate, userId: number) =>
  api.post<McpServer>('/mcp-servers', data, { params: { userId } });

export const updateMcpServer = (id: number, data: McpServerUpdate, userId: number) =>
  api.put<McpServer>(`/mcp-servers/${id}`, data, { params: { userId } });

/**
 * `DELETE /api/mcp-servers/{id}`. Rejects with the raw axios error so the
 * caller can intercept 409 with {@link parseDeleteConflict} and present a
 * "list of referencing agents" Modal (INV-12 / Q4 dashboard UX).
 */
export const deleteMcpServer = (id: number, userId: number) =>
  api.delete(`/mcp-servers/${id}`, { params: { userId } });

/**
 * `POST /api/mcp-servers/{id}/test-connection`. BE always replies 200 with
 * a discriminated body (success+tools | success=false+error) — see
 * {@link TestConnectionResponse}.
 */
export const testMcpServerConnection = (id: number, userId: number) =>
  api.post<TestConnectionResponse>(
    `/mcp-servers/${id}/test-connection`,
    null,
    { params: { userId } },
  );

/**
 * Best-effort extractor for a 409 Conflict body produced by `deleteMcpServer`.
 *
 * Accepts either of the two shapes the BE may use:
 *   1. `{conflict: ["agent-a", "agent-b"]}` (current tech-design)
 *   2. `{agents: [...], message: "..."}` (legacy envelope)
 *
 * Anything else returns an empty array — callers should still surface a
 * generic "agents reference this server" message in that case, since the
 * BE always intends 409 to mean "blocked by agent references".
 */
export function parseDeleteConflict(error: unknown): string[] {
  if (!error || typeof error !== 'object') return [];
  const response = (error as { response?: { status?: number; data?: unknown } }).response;
  if (!response || response.status !== 409) return [];
  const data = response.data as DeleteConflictBody | undefined;
  if (!data || typeof data !== 'object') return [];
  if (Array.isArray(data.conflict)) {
    return data.conflict.filter((n): n is string => typeof n === 'string');
  }
  if (Array.isArray(data.agents)) {
    return data.agents.filter((n): n is string => typeof n === 'string');
  }
  return [];
}

/**
 * Type-narrow helper for "is this a 409 from the MCP delete endpoint?". Use
 * before {@link parseDeleteConflict} when callers want to distinguish a
 * reference-conflict from any other error class (5xx, 401, network).
 */
export function isDeleteConflict(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const status = (error as { response?: { status?: number } }).response?.status;
  return status === 409;
}
