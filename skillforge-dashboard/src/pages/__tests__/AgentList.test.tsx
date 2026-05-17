/**
 * SYSTEM-AGENT-TYPING Phase 2 UX refactor (2026-05-18) — AgentList Tabs.
 *
 * Replaces the Phase 2.2 Switch toggle ("Show system agents") with a
 * top-level Tabs UI ("User Agents" / "System Agents"). The localStorage
 * key migrates from `agentlist.show_system_agents` (boolean string) to
 * `agentlist.active_tab` (string 'user' | 'system', default 'user').
 *
 * Cases:
 *   1. Tabs render; "User Agents" is the default active tab; getAgents
 *      called with 'user' on first mount.
 *   2. Click "System Agents" tab → re-fetch with 'system'; System tag +
 *      inline SystemAgentMonitorCard surface for system-typed agents.
 *   3. Run Manually click → POST /api/schedules/{taskId}/trigger
 *   4. localStorage `agentlist.active_tab` is read on mount + written on
 *      switch (so the choice survives reload).
 */
import React from 'react';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

// jsdom polyfills
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

const userAgent = {
  id: 1,
  name: 'Main Assistant',
  description: 'a user agent',
  agentType: 'user' as const,
  modelId: 'claude-4',
  executionMode: 'ask' as const,
};
const systemAgent = {
  id: 7,
  name: 'session-annotator',
  description: 'system cron annotator',
  agentType: 'system' as const,
  modelId: 'haiku-4',
  executionMode: 'auto' as const,
};

// `getAgents(agentType)` — captures the agentType arg so we can assert the
// tab switch actually re-fetches with the new param.
const getAgentsMock = vi.fn();
function setAgentsResponse(agentType: 'user' | 'system' | 'all' | undefined) {
  // BE contract: 'user' → only user agents; 'system' → only system agents.
  // 'all' is allowed by the typing but isn't used in the Tabs UX.
  if (agentType === 'system') {
    return Promise.resolve({ data: [systemAgent] });
  }
  if (agentType === 'all') {
    return Promise.resolve({ data: [userAgent, systemAgent] });
  }
  return Promise.resolve({ data: [userAgent] });
}

vi.mock('../../api', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('../../api');
  return {
    ...actual,
    getAgents: (...args: unknown[]) => {
      getAgentsMock(...args);
      return setAgentsResponse(args[0] as 'user' | 'system' | 'all' | undefined);
    },
    getTools: vi.fn(() => Promise.resolve({ data: [] })),
    getSkills: vi.fn(() => Promise.resolve({ data: [] })),
    createAgent: vi.fn(() => Promise.resolve({ data: { id: 99 } })),
    extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
      if (Array.isArray(res.data)) return res.data;
      const items = (res.data as { items?: T[] }).items;
      return Array.isArray(items) ? items : [];
    },
  };
});

// listSchedules → return one schedule for the system agent.
vi.mock('../../api/schedules', () => ({
  listSchedules: vi.fn(() =>
    Promise.resolve({
      data: [
        {
          id: 42,
          name: 'session-annotator-hourly',
          agentId: systemAgent.id,
          creatorUserId: 1,
          cronExpr: '0 30 * * * *',
          oneShotAt: null,
          timezone: 'UTC',
          promptTemplate: '',
          sessionMode: 'new',
          reusedSessionId: null,
          channelTarget: null,
          enabled: true,
          concurrencyPolicy: 'allow',
          nextFireAt: null,
          lastFireAt: '2026-05-17T11:30:00Z',
          status: 'idle',
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-05-17T11:30:00Z',
        },
      ],
    }),
  ),
  triggerSchedule: vi.fn(() =>
    Promise.resolve({ data: { taskId: 42, status: 'trigger_requested' } }),
  ),
}));

// getSystemAgentMonitor → return one monitor row for the system agent.
const monitorRow = {
  agentId: systemAgent.id,
  name: systemAgent.name,
  description: systemAgent.description,
  cronExpression: '0 30 * * * *',
  lastRunAt: '2026-05-17T11:30:00Z',
  lastRunStatus: 'success' as const,
  sevenDayTriggerCount: 168,
  sevenDayOutputCount: 29,
  outputEntityType: 'annotations' as const,
};
vi.mock('../../api/systemAgents', () => ({
  getSystemAgentMonitor: vi.fn(() => Promise.resolve({ data: [monitorRow] })),
}));

// Mock useLlmModels — empty options is fine for the New Agent modal.
vi.mock('../../hooks/useLlmModels', () => ({
  useLlmModels: () => ({ options: [] }),
}));

// AgentDrawer is heavy and unrelated to AgentList behavior under test.
vi.mock('../../components/agents/AgentDrawer', () => ({
  default: () => <div data-testid="agent-drawer-mock" />,
}));

// Auth mock
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

// Re-import after vi.mock so the test gets the mocked transitive deps.
import AgentList from '../AgentList';
import { triggerSchedule } from '../../api/schedules';

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <AgentList />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

// AntD Tabs renders each label as a div with role="tab"; the easiest stable
// selector is `getByRole('tab', { name: ... })`.
function getTab(name: 'User Agents' | 'System Agents') {
  return screen.getByRole('tab', { name });
}

describe('AgentList — User/System Tabs (SYSTEM-AGENT-TYPING Phase 2 UX refactor)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    getAgentsMock.mockClear();
    vi.mocked(triggerSchedule).mockClear();
  });

  it('defaults to User Agents tab and fetches only user agents', async () => {
    renderPage();

    // Both tabs render
    const userTab = await screen.findByRole('tab', { name: 'User Agents' });
    const systemTab = screen.getByRole('tab', { name: 'System Agents' });
    expect(userTab).toBeInTheDocument();
    expect(systemTab).toBeInTheDocument();

    // User tab is active by default (aria-selected)
    expect(userTab).toHaveAttribute('aria-selected', 'true');
    expect(systemTab).toHaveAttribute('aria-selected', 'false');

    // Initial fetch: agentType='user'
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('user');
    });

    // System agent NOT in the rendered list (BE filtered server-side)
    expect(await screen.findByText('Main Assistant')).toBeInTheDocument();
    expect(screen.queryByText('session-annotator')).not.toBeInTheDocument();

    // No System tag (no system agent rendered)
    expect(screen.queryByTestId(`system-agent-tag-${systemAgent.id}`)).not.toBeInTheDocument();
  });

  it('clicking System Agents tab re-fetches with agentType=system + shows tag + monitor card', async () => {
    renderPage();

    const systemTab = await screen.findByRole('tab', { name: 'System Agents' });
    await act(async () => {
      fireEvent.click(systemTab);
    });

    // Re-fetch with 'system'
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('system');
    });

    // System agent now rendered
    expect(await screen.findByText('session-annotator')).toBeInTheDocument();
    // System tag is on the system agent's card (redundant but reinforces visually)
    expect(screen.getByTestId(`system-agent-tag-${systemAgent.id}`)).toBeInTheDocument();
    // User agent isn't in the rendered list at all on the system tab
    expect(screen.queryByText('Main Assistant')).not.toBeInTheDocument();

    // Inline monitor card rendered for the system agent
    const monitor = await screen.findByTestId(`system-agent-monitor-${systemAgent.id}`);
    expect(monitor).toBeInTheDocument();
    expect(monitor).toHaveTextContent(/success/i);
    expect(monitor).toHaveTextContent(/168/);
    expect(monitor).toHaveTextContent(/29/);
    expect(monitor).toHaveTextContent(/0 30 \* \* \* \*/);

    // localStorage persistence — new key + new value shape (string, not boolean)
    expect(window.localStorage.getItem('agentlist.active_tab')).toBe('system');
  });

  it('Run Manually click triggers schedule for the resolved taskId', async () => {
    renderPage();

    const systemTab = await screen.findByRole('tab', { name: 'System Agents' });
    await act(async () => {
      fireEvent.click(systemTab);
    });

    // Wait for monitor card to render
    await screen.findByTestId(`system-agent-monitor-${systemAgent.id}`);

    // Click Run Manually
    const runBtn = await screen.findByTestId('run-manually-btn');
    expect(runBtn).not.toBeDisabled();
    await act(async () => {
      fireEvent.click(runBtn);
    });

    // triggerSchedule called with the resolved taskId (42 per mock) + userId 1
    await waitFor(() => {
      expect(triggerSchedule).toHaveBeenCalledWith(42, 1);
    });
  });

  it('reads `agentlist.active_tab` from localStorage on mount', async () => {
    window.localStorage.setItem('agentlist.active_tab', 'system');

    renderPage();

    // The system tab starts active because localStorage said so.
    const systemTab = await screen.findByRole('tab', { name: 'System Agents' });
    expect(systemTab).toHaveAttribute('aria-selected', 'true');

    // Initial fetch goes straight to 'system'
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('system');
    });
    // 'user' was never requested
    expect(getAgentsMock).not.toHaveBeenCalledWith('user');
  });

  it('falls back to default `user` tab when localStorage holds an unknown key', async () => {
    // useLocalStorageString narrows the persisted value via the allowedValues
    // guard. A garbage value (manual edit / schema drift) MUST fall back to
    // the default instead of getting passed to Tabs as activeKey (which would
    // silently render nothing).
    window.localStorage.setItem('agentlist.active_tab', 'all');

    renderPage();

    const userTab = await screen.findByRole('tab', { name: 'User Agents' });
    expect(userTab).toHaveAttribute('aria-selected', 'true');

    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('user');
    });
  });
});
