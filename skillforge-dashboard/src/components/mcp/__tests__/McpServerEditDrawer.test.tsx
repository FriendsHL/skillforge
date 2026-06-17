/**
 * McpServerEditDrawer — Form behaviour test.
 *
 * Covers the high-leverage edges that BE INV-3 / FE form rules need to
 * agree on:
 *   - Name regex `[a-z0-9_]+` rejects uppercase / hyphens / spaces; ≤ 32
 *     length.
 *   - `args` Form.List drops empty rows on submit (so a "stale Add" doesn't
 *     ship as `[""]` and break ProcessBuilder).
 *   - `env` Form.List drops rows with empty key, preserves values for
 *     non-empty keys, and forbids whitespace / `=` in env names.
 *   - Edit-mode disables the `name` field (renaming would break per-agent
 *     `mcp_server_ids` references).
 *
 * Keeps mutation mocks light — we only assert the shape passed to
 * `createMcpServer` / `updateMcpServer`, not the network layer.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AuthProvider } from '../../../contexts/AuthContext';

// jsdom polyfills for antd overlay positioning (ResizeObserver / matchMedia).
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

const createSpy = vi.fn(() => Promise.resolve({ data: {} }));
const updateSpy = vi.fn(() => Promise.resolve({ data: {} }));

vi.mock('../../../api/mcpServers', () => ({
  createMcpServer: (...args: unknown[]) => createSpy(...args),
  updateMcpServer: (...args: unknown[]) => updateSpy(...args),
}));

import McpServerEditDrawer from '../McpServerEditDrawer';
import type { McpServer } from '../../../types/mcpServer';

function renderDrawer(props: React.ComponentProps<typeof McpServerEditDrawer>) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <McpServerEditDrawer {...props} />
      </QueryClientProvider>
    </AuthProvider>,
  );
}

describe('McpServerEditDrawer — name validation', () => {
  beforeEach(() => {
    createSpy.mockClear();
    updateSpy.mockClear();
  });

  it('rejects uppercase / hyphen / spaces in name', async () => {
    renderDrawer({ open: true, server: null, onClose: () => {} });

    // Fill required fields first
    const nameInput = screen.getByPlaceholderText('e.g. time') as HTMLInputElement;
    const cmdInput = screen.getByPlaceholderText('npx') as HTMLInputElement;
    fireEvent.change(nameInput, { target: { value: 'Bad-Name' } });
    fireEvent.change(cmdInput, { target: { value: 'npx' } });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      // antd renders validation message inline
      expect(
        screen.getByText(/Use only lowercase letters, digits, and underscores/i),
      ).toBeInTheDocument();
    });
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('rejects names longer than 32 chars', async () => {
    renderDrawer({ open: true, server: null, onClose: () => {} });

    const nameInput = screen.getByPlaceholderText('e.g. time') as HTMLInputElement;
    const cmdInput = screen.getByPlaceholderText('npx') as HTMLInputElement;
    fireEvent.change(nameInput, { target: { value: 'a'.repeat(33) } });
    fireEvent.change(cmdInput, { target: { value: 'npx' } });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(
        screen.getByText(/Name must be 32 characters or fewer/i),
      ).toBeInTheDocument();
    });
    expect(createSpy).not.toHaveBeenCalled();
  });
});

describe('McpServerEditDrawer — submit normalisation', () => {
  beforeEach(() => {
    createSpy.mockClear();
    updateSpy.mockClear();
  });

  it('on create, accepts a valid payload and forwards normalised body', async () => {
    renderDrawer({ open: true, server: null, onClose: () => {} });

    fireEvent.change(screen.getByPlaceholderText('e.g. time'), {
      target: { value: 'time' },
    });
    fireEvent.change(screen.getByPlaceholderText('npx'), {
      target: { value: 'npx' },
    });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(createSpy).toHaveBeenCalledTimes(1);
    });
    const callArgs = createSpy.mock.calls[0];
    const body = callArgs[0] as Record<string, unknown>;
    expect(body.name).toBe('time');
    expect(body.command).toBe('npx');
    // Args / env default to empty when no rows are added
    expect(body.args).toEqual([]);
    expect(body.env).toEqual({});
    expect(body.enabled).toBe(true);
  });

  it('on edit, disables the name field and pre-fills existing values', () => {
    const server: McpServer = {
      id: 1,
      name: 'time',
      transport: 'stdio',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-time'],
      env: { TZ: 'UTC' },
      description: 'time server',
      enabled: true,
      createdAt: '2026-05-07T00:00:00Z',
      updatedAt: '2026-05-07T00:00:00Z',
    };
    renderDrawer({ open: true, server, onClose: () => {} });

    const nameInput = screen.getByPlaceholderText('e.g. time') as HTMLInputElement;
    expect(nameInput).toBeDisabled();
    expect(nameInput.value).toBe('time');

    const cmdInput = screen.getByPlaceholderText('npx') as HTMLInputElement;
    expect(cmdInput.value).toBe('npx');

    // First arg row pre-filled
    const argInputs = screen.getAllByPlaceholderText('-y') as HTMLInputElement[];
    expect(argInputs.length).toBeGreaterThan(0);
    expect(argInputs[0].value).toBe('-y');

    // Env key pre-filled
    const envKey = screen.getByPlaceholderText('GITHUB_TOKEN') as HTMLInputElement;
    expect(envKey.value).toBe('TZ');
  });

  it('keeps *** entries so BE preserve-on-*** can fire (P11 r3)', async () => {
    // P11 r3 architectural fix: FE no longer filters *** entries. BE r2.5
    // has preserve-on-*** logic that requires seeing the sentinel in the
    // request body to substitute back the stored secret. If FE were to
    // strip the entry, BE PUT-replaces semantics would drop that key from
    // the env JSONB → real secret loss. The two defences must compound,
    // not run sequentially.
    const server: McpServer = {
      id: 1,
      name: 'srv',
      transport: 'stdio',
      command: 'npx',
      args: [],
      env: { GITHUB_TOKEN: '***', URL: '${MY_URL}' },
      enabled: true,
      createdAt: '2026-05-07T00:00:00Z',
      updatedAt: '2026-05-07T00:00:00Z',
    };
    renderDrawer({ open: true, server, onClose: () => {} });

    // Save without touching anything — both rows are pre-filled (the
    // masked one as ***, the placeholder one verbatim).
    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });
    // updateMcpServer(id, body, userId) — body is the second arg.
    const body = updateSpy.mock.calls[0][1] as Record<string, unknown>;
    // Both rows propagate. *** lets BE recognise "preserve" intent;
    // ${MY_URL} is a placeholder, also forwarded verbatim.
    expect(body.env).toEqual({ GITHUB_TOKEN: '***', URL: '${MY_URL}' });
    // Sanity: untouched fields still propagate so we know it's a real PUT.
    expect(body.command).toBe('npx');
    // r2 W1: edit body must NOT carry name (rename guard).
    expect(body.name).toBeUndefined();
  });

  it('keeps env entry when user re-types the value over the mask (r2 W3-FE)', async () => {
    const server: McpServer = {
      id: 1,
      name: 'srv',
      transport: 'stdio',
      command: 'npx',
      args: [],
      env: { GITHUB_TOKEN: '***' },
      enabled: true,
      createdAt: '2026-05-07T00:00:00Z',
      updatedAt: '2026-05-07T00:00:00Z',
    };
    renderDrawer({ open: true, server, onClose: () => {} });

    // Re-type the value over the mask — submit should now include the
    // entry. Find the value input via its placeholder and overwrite it.
    const valueInputs = screen.getAllByPlaceholderText(
      '${GITHUB_PAT}',
    ) as HTMLInputElement[];
    fireEvent.change(valueInputs[0], { target: { value: 'real-token-123' } });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });
    // updateMcpServer(id, body, userId) — body is the second arg.
    const body = updateSpy.mock.calls[0][1] as Record<string, unknown>;
    expect(body.env).toEqual({ GITHUB_TOKEN: 'real-token-123' });
  });

  it('rejects env name with whitespace', async () => {
    const server: McpServer = {
      id: 1,
      name: 'srv',
      transport: 'stdio',
      command: 'npx',
      args: [],
      env: {},
      enabled: true,
      createdAt: '2026-05-07T00:00:00Z',
      updatedAt: '2026-05-07T00:00:00Z',
    };
    renderDrawer({ open: true, server, onClose: () => {} });

    // Click "Add env var" button
    fireEvent.click(screen.getByText('Add env var'));

    const envKeyInputs = screen.getAllByPlaceholderText(
      'GITHUB_TOKEN',
    ) as HTMLInputElement[];
    fireEvent.change(envKeyInputs[envKeyInputs.length - 1], {
      target: { value: 'BAD NAME' },
    });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(
        screen.getByText(/Env name cannot contain whitespace or `=`/i),
      ).toBeInTheDocument();
    });
    expect(updateSpy).not.toHaveBeenCalled();
  });
});

describe('McpServerEditDrawer — http transport', () => {
  beforeEach(() => {
    createSpy.mockClear();
    updateSpy.mockClear();
  });

  const httpServer: McpServer = {
    id: 9,
    name: 'anysearch',
    transport: 'http',
    command: null,
    args: [],
    env: {},
    url: 'https://api.anysearch.com/mcp',
    headers: { Authorization: '***', 'X-Api-Version': '2024-01' },
    enabled: true,
    createdAt: '2026-05-07T00:00:00Z',
    updatedAt: '2026-05-07T00:00:00Z',
  };

  it('create mode defaults to stdio (command shown, url/headers hidden)', () => {
    renderDrawer({ open: true, server: null, onClose: () => {} });
    // stdio default → command field present, url field absent.
    expect(screen.getByPlaceholderText('npx')).toBeInTheDocument();
    expect(
      screen.queryByPlaceholderText('https://api.example.com/mcp'),
    ).toBeNull();
  });

  it('edit mode renders url + headers and hides command/args for http', () => {
    renderDrawer({ open: true, server: httpServer, onClose: () => {} });

    const urlInput = screen.getByPlaceholderText(
      'https://api.example.com/mcp',
    ) as HTMLInputElement;
    expect(urlInput.value).toBe('https://api.anysearch.com/mcp');

    // Command field must NOT be rendered for an http server.
    expect(screen.queryByPlaceholderText('npx')).toBeNull();

    // Header rows pre-filled (key inputs share the 'Authorization' placeholder).
    const headerKeys = screen.getAllByPlaceholderText(
      'Authorization',
    ) as HTMLInputElement[];
    expect(headerKeys.map((i) => i.value)).toEqual(['Authorization', 'X-Api-Version']);
  });

  it('on http edit, sends url + headers (*** preserved), omits command/args/name/transport', async () => {
    renderDrawer({ open: true, server: httpServer, onClose: () => {} });

    // Save untouched — masked header must round-trip verbatim so BE
    // preserve-on-*** fires (same contract as env secrets).
    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });
    const body = updateSpy.mock.calls[0][1] as Record<string, unknown>;
    expect(body.url).toBe('https://api.anysearch.com/mcp');
    expect(body.headers).toEqual({
      Authorization: '***',
      'X-Api-Version': '2024-01',
    });
    // http body must not carry stdio fields, nor the immutable name/transport.
    expect(body.command).toBeUndefined();
    expect(body.args).toBeUndefined();
    expect(body.env).toBeUndefined();
    expect(body.name).toBeUndefined();
    expect(body.transport).toBeUndefined();
  });

  it('keeps http header entry when user re-types over the mask', async () => {
    renderDrawer({
      open: true,
      server: { ...httpServer, headers: { Authorization: '***' } },
      onClose: () => {},
    });

    const valueInputs = screen.getAllByPlaceholderText(
      'Bearer ${API_KEY}',
    ) as HTMLInputElement[];
    fireEvent.change(valueInputs[0], { target: { value: 'Bearer real-key' } });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledTimes(1);
    });
    const body = updateSpy.mock.calls[0][1] as Record<string, unknown>;
    expect(body.headers).toEqual({ Authorization: 'Bearer real-key' });
  });

  it('blocks submit when http url is cleared (required)', async () => {
    renderDrawer({ open: true, server: httpServer, onClose: () => {} });

    const urlInput = screen.getByPlaceholderText('https://api.example.com/mcp');
    fireEvent.change(urlInput, { target: { value: '' } });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(
        screen.getByText(/URL is required for http transport/i),
      ).toBeInTheDocument();
    });
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('rejects an http url without an http(s) scheme', async () => {
    renderDrawer({ open: true, server: httpServer, onClose: () => {} });

    const urlInput = screen.getByPlaceholderText('https://api.example.com/mcp');
    fireEvent.change(urlInput, { target: { value: 'ftp://nope' } });

    fireEvent.click(screen.getByTestId('mcp-edit-submit'));

    await waitFor(() => {
      expect(
        screen.getByText(/URL must start with http:\/\/ or https:\/\//i),
      ).toBeInTheDocument();
    });
    expect(updateSpy).not.toHaveBeenCalled();
  });
});
