/**
 * SYSTEM-AGENT-TYPING Phase 2 W2 mandatory fix + follow-up — SessionList deep-link.
 *
 * "View Sessions" button on SystemAgentMonitorCard navigates to
 * `/sessions?agentId=N`. The fix wires SessionList.tsx to consume this param:
 * translate the numeric agentId to the matching agent NAME (existing filter
 * is keyed by name) and apply the filter, then drop the URL param.
 *
 * 2026-05-17 follow-up — the original fix scanned the sessions list to find
 * the agent name. That broke against real production data (76 sessions, many
 * agent-7 / session-annotator sessions not on the first page slice the FE
 * happens to render). The robust fix fetches the agent BY ID via
 * `/api/agents/{id}`, then sets the filter to the resolved name.
 *
 * 2026-05-18 Phase 2 UX refactor — page now has a top-level User/System
 * Sessions Tabs UI. Sessions are client-side filtered by the active tab's
 * agentType. Deep-linking `?agentId=N` MUST auto-switch the tab to match the
 * resolved agent's agentType (so the filter chip is visible AND the tab
 * scope doesn't accidentally exclude the deep-linked agent's sessions).
 *
 * The fixtures below are intentionally shaped to mimic the production
 * pattern: the agent we deep-link to (agent 7 / session-annotator) is NOT
 * present in the sessions list at all — its name MUST be resolved via the
 * separate `getAgent(7)` fetch. This guards the regression: a fix that
 * silently falls back to sessions lookup will fail this test.
 *
 * We assert behavior through the rendered DOM (filter-item button's `on`
 * class, AntD tab `aria-selected`) rather than poking internal state.
 */
import React from 'react';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

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

// Production-shaped fixture: user-tab returns the Main Assistant sessions
// (matches what BE `?agentType=user` would return). System-tab returns
// empty — agent 7 / session-annotator exists but no session of it in this
// fixture (matches the original deep-link-with-zero-sessions scenario).
//
// 2026-05-18 Phase B visibility — the FE no longer client-side filters by
// scopedAgents; the BE returns the right scope based on `?agentType=`.
// `getSessions(userId, agentType)` mock returns per-tab fixtures.
const userSessions = [
  {
    id: 'sess-1',
    title: 'main assistant session 1',
    agentId: 1,
    agentName: 'Main Assistant',
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
    title: 'main assistant session 2',
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
const systemSessions: typeof userSessions = []; // mirror the zero-sessions deep-link case

const getAgentMock = vi.fn((id: number) => {
  if (id === 7) {
    // Phase 2 UX refactor — getAgent(7) must include `agentType: 'system'`
    // so the deep-link effect can auto-switch the active tab to 'system'.
    return Promise.resolve({
      data: { id: 7, name: 'session-annotator', agentType: 'system' },
    });
  }
  // Unknown agent — simulate a 404 by rejecting (matches axios behavior).
  return Promise.reject(new Error(`agent ${id} not found`));
});

// Phase B visibility — getSessions(userId, agentType) returns per-scope
// fixture. agentType='system' bypass userId on the BE (cron-owned sessions
// of ownerId=0 returned to dashboard userId=1=admin).
const getSessionsMock = vi.fn(
  (_userId: number, agentType?: 'user' | 'system') => {
    if (agentType === 'system') {
      return Promise.resolve({ data: systemSessions });
    }
    return Promise.resolve({ data: userSessions });
  },
);

vi.mock('../../api', () => ({
  getSessions: (userId: number, agentType?: 'user' | 'system') =>
    getSessionsMock(userId, agentType),
  getSessionMessages: vi.fn(() => Promise.resolve({ data: [] })),
  getAgent: (id: number) => getAgentMock(id),
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

describe('SessionList — `?agentId=` deep link + Tabs (SYSTEM-AGENT-TYPING Phase 2)', () => {
  beforeEach(() => {
    getAgentMock.mockClear();
    getSessionsMock.mockClear();
    window.localStorage.clear();
  });

  it('resolves agent NAME from /api/agents and auto-switches to System tab + applies filter', async () => {
    renderWithInitialUrl('/sessions?agentId=7');

    // /api/agents/7 is called to resolve the agent (W2 follow-up: NOT
    // resolved by scanning the sessions list).
    await waitFor(() => {
      expect(getAgentMock).toHaveBeenCalledWith(7);
    });
    // Phase 2 UX refactor — agent 7 is `agentType: 'system'`, so the active
    // tab auto-switches to "System Sessions".
    const systemTab = await screen.findByRole('tab', { name: 'System Sessions' });
    await waitFor(() => {
      expect(systemTab).toHaveAttribute('aria-selected', 'true');
    });

    // No user-agent sessions visible (system tab scope excludes them) AND
    // the session-annotator filter is applied — so we see the empty state.
    await waitFor(() => {
      expect(screen.queryByText('main assistant session 1')).not.toBeInTheDocument();
    });
    expect(screen.queryByText('main assistant session 2')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('No sessions match your filters.')).toBeInTheDocument();
    });

    // UX guard: even though session-annotator has zero sessions in the
    // loaded list, the sidebar must still surface it as an active filter
    // pill so the operator can SEE the filter is on and clear it. This
    // verifies the W2 follow-up `agents` union with `filterAgent` is
    // preserved across the Tabs refactor.
    const filterBtns = Array.from(document.querySelectorAll('.filter-item'));
    const annotatorBtn = filterBtns.find((b) =>
      (b.textContent || '').includes('session-annotator'),
    );
    expect(annotatorBtn).toBeDefined();
    expect(annotatorBtn?.className).toMatch(/\bon\b/);
  });

  it('Tabs render and User Sessions is default; clicking System Sessions re-fetches with agentType=system', async () => {
    renderWithInitialUrl('/sessions');

    // Both tabs render with the default = User Sessions.
    const userTab = await screen.findByRole('tab', { name: 'User Sessions' });
    const systemTab = screen.getByRole('tab', { name: 'System Sessions' });
    expect(userTab).toHaveAttribute('aria-selected', 'true');
    expect(systemTab).toHaveAttribute('aria-selected', 'false');

    // User sessions visible on the user tab.
    await waitFor(() => {
      expect(screen.getByText('main assistant session 1')).toBeInTheDocument();
    });

    // Phase B (2026-05-18): initial fetch was getSessions(1, 'user').
    expect(getSessionsMock).toHaveBeenCalledWith(1, 'user');

    // Click System Sessions → tab activates, getSessions(1, 'system') fires,
    // returns the empty systemSessions fixture → empty state.
    await act(async () => {
      fireEvent.click(systemTab);
    });
    await waitFor(() => {
      expect(systemTab).toHaveAttribute('aria-selected', 'true');
    });
    await waitFor(() => {
      expect(getSessionsMock).toHaveBeenCalledWith(1, 'system');
    });
    await waitFor(() => {
      expect(screen.queryByText('main assistant session 1')).not.toBeInTheDocument();
    });
    expect(window.localStorage.getItem('sessionlist.active_tab')).toBe('system');
  });

  it('no filter applied when agentId param is absent', async () => {
    renderWithInitialUrl('/sessions');

    // Both sessions should be visible (no agent filter applied).
    await waitFor(() => {
      expect(screen.getByText('main assistant session 1')).toBeInTheDocument();
    });
    expect(screen.getByText('main assistant session 2')).toBeInTheDocument();
    // /api/agents/{id} should NOT have been called.
    expect(getAgentMock).not.toHaveBeenCalled();
  });

  // NOTE: a "URL param is dropped after consumption" assertion would require
  // peeking at the MemoryRouter's internal location, which isn't exposed via
  // testing-library. The no-loop behavior is implicitly covered by the first
  // test (single render, no flicker). A regression here would show up as a
  // flake in CI rather than a silent miss.

  it('drops a malformed agentId param without crashing and without fetching', async () => {
    renderWithInitialUrl('/sessions?agentId=not-a-number');

    // Both sessions visible (no filter applied because the param was
    // malformed → numericAgentIdParam=null → query disabled → drop URL
    // param + no filter).
    await waitFor(() => {
      expect(screen.getByText('main assistant session 1')).toBeInTheDocument();
    });
    expect(screen.getByText('main assistant session 2')).toBeInTheDocument();
    // Crucially, /api/agents/{id} should NOT be called with NaN.
    expect(getAgentMock).not.toHaveBeenCalled();
  });

  it('gracefully ignores agentId pointing to a non-existent agent (404)', async () => {
    renderWithInitialUrl('/sessions?agentId=999');

    // /api/agents/999 will reject (404). The fix should swallow the error,
    // drop the URL param, and leave the list unfiltered.
    await waitFor(() => {
      expect(getAgentMock).toHaveBeenCalledWith(999);
    });
    await waitFor(() => {
      expect(screen.getByText('main assistant session 1')).toBeInTheDocument();
    });
    expect(screen.getByText('main assistant session 2')).toBeInTheDocument();
  });
});
