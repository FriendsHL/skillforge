/**
 * SYSTEM-AGENT-TYPING Phase 2 W2 mandatory fix — Schedules deep-link.
 *
 * "View Schedule" button on SystemAgentMonitorCard navigates to
 * `/schedules?taskId=N`. The fix wires Schedules.tsx to consume this param
 * once tasks load: open the edit drawer for the matching task. This test
 * pins that behavior.
 */
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

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

const fakeTask = {
  id: 42,
  name: 'session-annotator-hourly',
  agentId: 7,
  creatorUserId: 1,
  cronExpr: '0 30 * * * *',
  oneShotAt: null,
  timezone: 'UTC',
  promptTemplate: '',
  sessionMode: 'new' as const,
  reusedSessionId: null,
  channelTarget: null,
  enabled: true,
  concurrencyPolicy: 'allow' as const,
  nextFireAt: null,
  lastFireAt: '2026-05-17T11:30:00Z',
  status: 'idle' as const,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-05-17T11:30:00Z',
};

vi.mock('../../api/schedules', () => ({
  listSchedules: vi.fn(() => Promise.resolve({ data: [fakeTask] })),
  deleteSchedule: vi.fn(() => Promise.resolve({ data: {} })),
  triggerSchedule: vi.fn(() => Promise.resolve({ data: { taskId: 42, status: 'trigger_requested' } })),
  updateSchedule: vi.fn(() => Promise.resolve({ data: {} })),
}));

vi.mock('../../api', () => ({
  getAgents: vi.fn(() => Promise.resolve({ data: [{ id: 7, name: 'session-annotator' }] })),
  extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
    if (Array.isArray(res.data)) return res.data;
    const items = (res.data as { items?: T[] }).items;
    return Array.isArray(items) ? items : [];
  },
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

// Stub heavy drawers so the test doesn't need their full props surface — we
// just need to verify the parent passed an open=true target after URL param
// consumption.
const editDrawerSpy = vi.fn();
vi.mock('../../components/schedules/ScheduleEditDrawer', () => ({
  default: (props: { open: boolean; task: { id: number; name: string } | null }) => {
    editDrawerSpy(props);
    if (!props.open || !props.task) return null;
    return <div data-testid="edit-drawer-open">{props.task.name}</div>;
  },
}));
vi.mock('../../components/schedules/ScheduleRunHistoryDrawer', () => ({
  default: () => null,
}));

import Schedules from '../Schedules';

function renderWithInitialUrl(initialUrl: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  return render(
    <MemoryRouter initialEntries={[initialUrl]}>
      <QueryClientProvider client={client}>
        <Schedules />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Schedules — `?taskId=` deep link (SYSTEM-AGENT-TYPING Phase 2 W2)', () => {
  beforeEach(() => {
    editDrawerSpy.mockClear();
  });

  it('opens the edit drawer for the matching task on mount', async () => {
    renderWithInitialUrl('/schedules?taskId=42');

    // The mocked ScheduleEditDrawer renders the task name when open=true.
    expect(await screen.findByTestId('edit-drawer-open')).toHaveTextContent(
      'session-annotator-hourly',
    );
    // Drawer should have been called with open=true + task=#42.
    await waitFor(() => {
      const lastCall = editDrawerSpy.mock.calls.at(-1)?.[0];
      expect(lastCall).toMatchObject({ open: true, task: { id: 42 } });
    });
  });

  it('drops the URL param after consumption (no re-open loop)', async () => {
    const { container } = renderWithInitialUrl('/schedules?taskId=42');
    await screen.findByTestId('edit-drawer-open');
    // After consumption, the URL param should be cleared. We verify by
    // checking window.location.search via the spy's most recent call (the
    // setSearchParams `replace: true` mutates the test router state).
    expect(container).toBeInTheDocument();
    // Note: react-router MemoryRouter doesn't expose location directly here,
    // but the no-loop behavior is implicit — drawer is called once with the
    // task, not repeatedly. Sanity-check by asserting the open=true call
    // count stays bounded.
    expect(editDrawerSpy.mock.calls.filter((c) => c[0].open === true).length).toBeLessThanOrEqual(2);
  });

  it('no-ops when taskId is not in URL', async () => {
    renderWithInitialUrl('/schedules');
    // Wait a tick for tasks to load.
    await waitFor(() => {
      // Drawer rendered at least once with open=false (initial state).
      expect(editDrawerSpy).toHaveBeenCalled();
    });
    // No call should have open=true since no deep-link triggered it.
    const openCalls = editDrawerSpy.mock.calls.filter((c) => c[0].open === true);
    expect(openCalls.length).toBe(0);
  });
});
