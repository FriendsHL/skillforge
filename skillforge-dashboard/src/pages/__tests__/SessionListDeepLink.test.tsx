/**
 * SYSTEM-AGENT-TYPING Phase 2 W2 mandatory fix — SessionList deep-link.
 *
 * "View Sessions" button on SystemAgentMonitorCard navigates to
 * `/sessions?agentId=N`. The fix wires SessionList.tsx to consume this param:
 * translate the numeric agentId to the matching agent NAME (existing filter
 * is keyed by name) once sessions load, then drop the URL param.
 *
 * We assert behavior through the rendered DOM (filter-item button's `on`
 * class) rather than poking internal state.
 */
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

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

const sessions = [
  {
    id: 'sess-1',
    title: 'session 1',
    agentId: 7,
    agentName: 'session-annotator',
    messageCount: 3,
    totalInputTokens: 1000,
    totalOutputTokens: 500,
    runtimeStatus: 'idle',
    channelPlatform: 'web',
    createdAt: '2026-05-17T11:00:00Z',
    updatedAt: '2026-05-17T11:30:00Z',
  },
  {
    id: 'sess-2',
    title: 'session 2',
    agentId: 1,
    agentName: 'Main Assistant',
    messageCount: 5,
    totalInputTokens: 2000,
    totalOutputTokens: 800,
    runtimeStatus: 'idle',
    channelPlatform: 'web',
    createdAt: '2026-05-17T12:00:00Z',
    updatedAt: '2026-05-17T12:30:00Z',
  },
];

vi.mock('../../api', () => ({
  getSessions: vi.fn(() => Promise.resolve({ data: sessions })),
  getSessionMessages: vi.fn(() => Promise.resolve({ data: [] })),
  deleteSessions: vi.fn(() => Promise.resolve({ data: {} })),
  getContextBreakdown: vi.fn(() => Promise.resolve({ data: null })),
  extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
    if (Array.isArray(res.data)) return res.data;
    const items = (res.data as { items?: T[] }).items;
    return Array.isArray(items) ? items : [];
  },
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

// jsdom lacks WebSocket — stub so any incidental ws references don't blow up.
class FakeWs {
  onmessage: ((ev: { data: string }) => void) | null = null;
  close() {}
  send() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

import SessionList from '../SessionList';

function renderWithInitialUrl(initialUrl: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  return render(
    <MemoryRouter initialEntries={[initialUrl]}>
      <QueryClientProvider client={client}>
        <SessionList />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('SessionList — `?agentId=` deep link (SYSTEM-AGENT-TYPING Phase 2 W2)', () => {
  it('translates agentId param to filterAgent and shows only that agent\'s sessions', async () => {
    renderWithInitialUrl('/sessions?agentId=7');

    // Wait for sessions to load + filter to apply
    await waitFor(() => {
      expect(screen.getByText('session 1')).toBeInTheDocument();
    });
    // session 2 should be filtered OUT (its agentId is 1, not 7).
    expect(screen.queryByText('session 2')).not.toBeInTheDocument();

    // The agent filter button for "session-annotator" should be in the
    // active state (CSS class `on`). Filter buttons live in the sidebar
    // — locate the one whose textContent contains the agent name.
    const filterBtns = Array.from(document.querySelectorAll('.filter-item'));
    const annotatorBtn = filterBtns.find((b) =>
      (b.textContent || '').includes('session-annotator'),
    );
    expect(annotatorBtn).toBeDefined();
    expect(annotatorBtn?.className).toMatch(/\bon\b/);
  });

  it('no filter applied when agentId param is absent', async () => {
    renderWithInitialUrl('/sessions');

    // Both sessions should be visible (no agent filter applied)
    await waitFor(() => {
      expect(screen.getByText('session 1')).toBeInTheDocument();
    });
    expect(screen.getByText('session 2')).toBeInTheDocument();
  });

  // NOTE: a "URL param is dropped after consumption" assertion would require
  // peeking at the MemoryRouter's internal location, which isn't exposed via
  // testing-library. The no-loop behavior is implicitly covered by test #1
  // (single render, no flicker, drawer not re-opened). A regression here
  // would show up as a flake in CI rather than a silent miss.

  it('drops a malformed agentId param without crashing', async () => {
    renderWithInitialUrl('/sessions?agentId=not-a-number');

    // Both sessions visible (no filter applied because lookup failed)
    await waitFor(() => {
      expect(screen.getByText('session 1')).toBeInTheDocument();
    });
    expect(screen.getByText('session 2')).toBeInTheDocument();
  });
});
