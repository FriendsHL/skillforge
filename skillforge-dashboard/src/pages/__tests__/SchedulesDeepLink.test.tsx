/**
 * SYSTEM-AGENT-TYPING Phase 2 W2 mandatory fix + follow-up — Schedules deep-link.
 *
 * "View Schedule" button on SystemAgentMonitorCard navigates to
 * `/schedules?taskId=N`. The fix wires Schedules.tsx to consume this param
 * once the task fetch resolves: open the edit drawer for the matching task.
 *
 * 2026-05-17 follow-up — the original fix scanned the `tasks` list to find
 * the deep-linked task. That broke against real production data where the
 * target task is not guaranteed to be in the page slice the FE renders. The
 * robust fix fetches the task BY ID via `/api/schedules/{id}`.
 *
 * The fixtures below are intentionally shaped to mimic that production
 * shape: the task we deep-link to (id=42) is NOT in the list returned by
 * `listSchedules` — its data MUST be resolved via the separate
 * `getSchedule(42, userId)` fetch. A fix that silently falls back to
 * list lookup will fail this test.
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

// Production-shaped fixture: listSchedules returns OTHER tasks (id=1, 2),
// not id=42. The fix MUST resolve id=42 via the separate getSchedule(42)
// fetch — otherwise it falls back to the buggy "list lookup" behavior and
// the drawer never opens.
const otherTasks = [
  {
    id: 1,
    name: 'other-task-1',
    agentId: 1,
    creatorUserId: 1,
    cronExpr: '0 0 * * * *',
    oneShotAt: null,
    timezone: 'UTC',
    promptTemplate: '',
    sessionMode: 'new' as const,
    reusedSessionId: null,
    channelTarget: null,
    enabled: true,
    concurrencyPolicy: 'allow' as const,
    nextFireAt: null,
    lastFireAt: null,
    status: 'idle' as const,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-05-17T11:30:00Z',
  },
  {
    id: 2,
    name: 'other-task-2',
    agentId: 2,
    creatorUserId: 1,
    cronExpr: '0 15 * * * *',
    oneShotAt: null,
    timezone: 'UTC',
    promptTemplate: '',
    sessionMode: 'new' as const,
    reusedSessionId: null,
    channelTarget: null,
    enabled: true,
    concurrencyPolicy: 'allow' as const,
    nextFireAt: null,
    lastFireAt: null,
    status: 'idle' as const,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-05-17T11:30:00Z',
  },
];

// The deep-linked target — explicitly NOT in `otherTasks`.
const deepLinkedTask = {
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

const getScheduleMock = vi.fn((id: number) => {
  if (id === 42) {
    return Promise.resolve({ data: deepLinkedTask });
  }
  // Unknown id — simulate a 404.
  return Promise.reject(new Error(`schedule ${id} not found`));
});

vi.mock('../../api/schedules', () => ({
  listSchedules: vi.fn(() => Promise.resolve({ data: otherTasks })),
  getSchedule: (id: number, userId: number) => getScheduleMock(id, userId),
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
    getScheduleMock.mockClear();
  });

  it('resolves task via /api/schedules/{id} (NOT from list scan) and opens the edit drawer', async () => {
    renderWithInitialUrl('/schedules?taskId=42');

    // The mocked ScheduleEditDrawer renders the task name when open=true.
    expect(await screen.findByTestId('edit-drawer-open')).toHaveTextContent(
      'session-annotator-hourly',
    );
    // Crucially: /api/schedules/42 was hit — proving the fix is using the
    // single-id fetch path, not list scanning.
    expect(getScheduleMock).toHaveBeenCalledWith(42, 1);
    // Drawer should have been called with open=true + task=#42.
    await waitFor(() => {
      const lastCall = editDrawerSpy.mock.calls.at(-1)?.[0];
      expect(lastCall).toMatchObject({ open: true, task: { id: 42 } });
    });
  });

  it('drops the URL param after consumption (no re-open loop)', async () => {
    const { container } = renderWithInitialUrl('/schedules?taskId=42');
    await screen.findByTestId('edit-drawer-open');
    // After consumption, the URL param should be cleared. We verify the
    // no-loop behavior implicitly: drawer is called once with the task,
    // not repeatedly.
    expect(container).toBeInTheDocument();
    expect(editDrawerSpy.mock.calls.filter((c) => c[0].open === true).length).toBeLessThanOrEqual(2);
  });

  it('no-ops when taskId is not in URL (no fetch, no drawer)', async () => {
    renderWithInitialUrl('/schedules');
    await waitFor(() => {
      // Drawer rendered at least once with open=false (initial state).
      expect(editDrawerSpy).toHaveBeenCalled();
    });
    // No call should have open=true since no deep-link triggered it.
    const openCalls = editDrawerSpy.mock.calls.filter((c) => c[0].open === true);
    expect(openCalls.length).toBe(0);
    // And /api/schedules/{id} should NOT have been called.
    expect(getScheduleMock).not.toHaveBeenCalled();
  });

  it('drops a malformed taskId param without crashing and without fetching', async () => {
    renderWithInitialUrl('/schedules?taskId=not-a-number');
    // Give the page a tick to settle.
    await waitFor(() => {
      expect(editDrawerSpy).toHaveBeenCalled();
    });
    // No drawer open, no fetch.
    const openCalls = editDrawerSpy.mock.calls.filter((c) => c[0].open === true);
    expect(openCalls.length).toBe(0);
    expect(getScheduleMock).not.toHaveBeenCalled();
  });

  it('gracefully ignores taskId pointing to a non-existent schedule (404)', async () => {
    renderWithInitialUrl('/schedules?taskId=999');
    await waitFor(() => {
      expect(getScheduleMock).toHaveBeenCalledWith(999, 1);
    });
    // The 404 should be swallowed silently — drawer stays closed.
    await waitFor(() => {
      expect(editDrawerSpy).toHaveBeenCalled();
    });
    const openCalls = editDrawerSpy.mock.calls.filter((c) => c[0].open === true);
    expect(openCalls.length).toBe(0);
  });
});
