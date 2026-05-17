/**
 * SYSTEM-AGENT-TYPING Phase 2.2 — AgentList toggle + monitor inline + Tag.
 *
 * Cases (per requirements/active/SYSTEM-AGENT-TYPING/index.md task #3 brief):
 *   1. toggle "Show system agents" defaults OFF + persists in localStorage
 *   2. toggle ON re-queries with agentType='all' and shows the System tag +
 *      inline SystemAgentMonitorCard for system-typed agents
 *   3. Run Manually click → POST /api/schedules/{taskId}/trigger
 *
 * We mock the api/api/schedules/systemAgents wrappers so the test stays
 * focused on AgentList behavior (toggle wiring + tag + monitor card rendering
 * + click → trigger). AgentDrawer is mocked to keep noise low.
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
// toggle actually re-fetches with the new param.
const getAgentsMock = vi.fn();
function setAgentsResponse(agentType: 'user' | 'system' | 'all' | undefined) {
  if (agentType === 'all' || agentType === 'system') {
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
      return setAgentsResponse(args[0] as 'user' | 'all' | undefined);
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

describe('AgentList — Show system agents toggle (SYSTEM-AGENT-TYPING Phase 2.2)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    getAgentsMock.mockClear();
    vi.mocked(triggerSchedule).mockClear();
  });

  it('defaults toggle OFF and fetches only user agents', async () => {
    renderPage();

    // Toggle exists + unchecked
    const toggle = await screen.findByTestId('show-system-agents-toggle');
    expect(toggle).toBeInTheDocument();
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    // Initial fetch: agentType='user'
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('user');
    });

    // System agent NOT in the rendered list
    expect(await screen.findByText('Main Assistant')).toBeInTheDocument();
    expect(screen.queryByText('session-annotator')).not.toBeInTheDocument();

    // No System tag for the user agent
    expect(screen.queryByTestId(`system-agent-tag-${systemAgent.id}`)).not.toBeInTheDocument();
  });

  it('toggle ON re-queries agentType=all and shows System tag + monitor card', async () => {
    renderPage();

    // Flip toggle on
    const toggle = await screen.findByTestId('show-system-agents-toggle');
    await act(async () => {
      fireEvent.click(toggle);
    });

    // Re-fetch with 'all'
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('all');
    });

    // System agent now rendered
    expect(await screen.findByText('session-annotator')).toBeInTheDocument();
    // System tag is on the system agent's card
    expect(screen.getByTestId(`system-agent-tag-${systemAgent.id}`)).toBeInTheDocument();
    // User agent does NOT get a tag
    expect(screen.queryByTestId(`system-agent-tag-${userAgent.id}`)).not.toBeInTheDocument();

    // Inline monitor card rendered for the system agent
    const monitor = await screen.findByTestId(`system-agent-monitor-${systemAgent.id}`);
    expect(monitor).toBeInTheDocument();
    // Status tag visible (last_run_status === 'success')
    expect(monitor).toHaveTextContent(/success/i);
    // 7d triggers / output counts visible
    expect(monitor).toHaveTextContent(/168/);
    expect(monitor).toHaveTextContent(/29/);
    // cron visible
    expect(monitor).toHaveTextContent(/0 30 \* \* \* \*/);

    // localStorage persistence
    expect(window.localStorage.getItem('agentlist.show_system_agents')).toBe('true');
  });

  it('Run Manually click triggers schedule for the resolved taskId', async () => {
    renderPage();

    // Flip toggle on
    const toggle = await screen.findByTestId('show-system-agents-toggle');
    await act(async () => {
      fireEvent.click(toggle);
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

  it('reads toggle state from localStorage on mount', async () => {
    window.localStorage.setItem('agentlist.show_system_agents', 'true');

    renderPage();

    // Toggle starts checked because localStorage said true
    const toggle = await screen.findByTestId('show-system-agents-toggle');
    expect(toggle).toHaveAttribute('aria-checked', 'true');

    // Initial fetch goes straight to 'all'
    await waitFor(() => {
      expect(getAgentsMock).toHaveBeenCalledWith('all');
    });
    // 'user' was never requested
    expect(getAgentsMock).not.toHaveBeenCalledWith('user');
  });
});
