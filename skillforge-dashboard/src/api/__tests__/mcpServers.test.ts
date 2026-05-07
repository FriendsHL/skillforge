/**
 * MCP-CLIENT-MVP — `mcpServers.ts` API client unit tests.
 *
 * Covers:
 *   1. Every endpoint sends `userId` as a **query param** (P12 ownership
 *      pattern — see `mcpServers.ts` header comment for why MCP doesn't
 *      use P10's body-only pattern).
 *   2. `parseDeleteConflict` tolerates both shape variants the BE may
 *      emit on 409 (`{conflict}` vs `{agents}`), and returns `[]` for
 *      non-conflict errors so callers can branch off `isDeleteConflict`.
 *   3. `isDeleteConflict` distinguishes 409 from other error classes —
 *      important because the page-level Modal copy is different for
 *      "blocked by agents" vs "BE down".
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../index', () => {
  const get = vi.fn();
  const post = vi.fn();
  const put = vi.fn();
  const del = vi.fn();
  return {
    default: { get, post, put, delete: del },
  };
});

import api from '../index';
import {
  listMcpServers,
  getMcpServer,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  testMcpServerConnection,
  parseDeleteConflict,
  isDeleteConflict,
} from '../mcpServers';
import type { McpServer, McpServerCreate, McpServerUpdate } from '../../types/mcpServer';

const mocked = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

const SAMPLE: McpServer = {
  id: 1,
  name: 'time',
  command: 'npx',
  args: ['-y', '@modelcontextprotocol/server-time'],
  env: {},
  description: 'Time MCP server',
  enabled: true,
  status: 'connected',
  toolCount: 2,
  createdAt: '2026-05-07T00:00:00Z',
  updatedAt: '2026-05-07T00:00:00Z',
};

describe('mcpServers API client — request shapes', () => {
  beforeEach(() => {
    mocked.get.mockReset();
    mocked.post.mockReset();
    mocked.put.mockReset();
    mocked.delete.mockReset();
  });

  it('listMcpServers passes userId as query param', async () => {
    mocked.get.mockResolvedValueOnce({ data: [SAMPLE] });
    await listMcpServers(7);
    expect(mocked.get).toHaveBeenCalledWith('/mcp-servers', {
      params: { userId: 7 },
    });
  });

  it('getMcpServer composes id path and userId param', async () => {
    mocked.get.mockResolvedValueOnce({ data: SAMPLE });
    await getMcpServer(42, 7);
    expect(mocked.get).toHaveBeenCalledWith('/mcp-servers/42', {
      params: { userId: 7 },
    });
  });

  it('createMcpServer puts userId in query, payload in body', async () => {
    mocked.post.mockResolvedValueOnce({ data: SAMPLE });
    const body: McpServerCreate = {
      name: 'time',
      command: 'npx',
      args: ['-y'],
      env: {},
      description: null,
    };
    await createMcpServer(body, 7);
    expect(mocked.post).toHaveBeenCalledWith('/mcp-servers', body, {
      params: { userId: 7 },
    });
  });

  it('updateMcpServer puts userId in query, partial payload in body', async () => {
    mocked.put.mockResolvedValueOnce({ data: SAMPLE });
    const body: McpServerUpdate = { enabled: false };
    await updateMcpServer(42, body, 7);
    expect(mocked.put).toHaveBeenCalledWith('/mcp-servers/42', body, {
      params: { userId: 7 },
    });
  });

  it('deleteMcpServer passes userId as query param', async () => {
    mocked.delete.mockResolvedValueOnce({ data: undefined });
    await deleteMcpServer(42, 7);
    expect(mocked.delete).toHaveBeenCalledWith('/mcp-servers/42', {
      params: { userId: 7 },
    });
  });

  it('testMcpServerConnection POSTs with null body and userId param', async () => {
    mocked.post.mockResolvedValueOnce({ data: { success: true, tools: [] } });
    await testMcpServerConnection(42, 7);
    expect(mocked.post).toHaveBeenCalledWith(
      '/mcp-servers/42/test-connection',
      null,
      { params: { userId: 7 } },
    );
  });
});

describe('parseDeleteConflict', () => {
  it('returns names from {conflict: [...]} shape', () => {
    const error = {
      response: { status: 409, data: { conflict: ['agent-a', 'agent-b'] } },
    };
    expect(parseDeleteConflict(error)).toEqual(['agent-a', 'agent-b']);
  });

  it('falls back to {agents: [...]} legacy envelope', () => {
    const error = {
      response: {
        status: 409,
        data: { agents: ['agent-a'], message: 'still referenced' },
      },
    };
    expect(parseDeleteConflict(error)).toEqual(['agent-a']);
  });

  it('returns [] when status is not 409 even if body has conflict key', () => {
    const error = {
      response: { status: 500, data: { conflict: ['agent-a'] } },
    };
    expect(parseDeleteConflict(error)).toEqual([]);
  });

  it('returns [] when error has no response (e.g. network error)', () => {
    expect(parseDeleteConflict(new Error('ECONNREFUSED'))).toEqual([]);
  });

  it('returns [] when conflict body lacks the expected fields', () => {
    const error = { response: { status: 409, data: { something: 'else' } } };
    expect(parseDeleteConflict(error)).toEqual([]);
  });

  it('filters non-string entries from the conflict array', () => {
    const error = {
      response: {
        status: 409,
        data: { conflict: ['ok', 42, null, 'also-ok'] },
      },
    };
    expect(parseDeleteConflict(error)).toEqual(['ok', 'also-ok']);
  });
});

describe('isDeleteConflict', () => {
  it('returns true for 409', () => {
    expect(isDeleteConflict({ response: { status: 409 } })).toBe(true);
  });

  it('returns false for non-409 statuses', () => {
    expect(isDeleteConflict({ response: { status: 500 } })).toBe(false);
    expect(isDeleteConflict({ response: { status: 401 } })).toBe(false);
  });

  it('returns false for errors without a response', () => {
    expect(isDeleteConflict(new Error('boom'))).toBe(false);
    expect(isDeleteConflict(null)).toBe(false);
    expect(isDeleteConflict(undefined)).toBe(false);
  });
});
