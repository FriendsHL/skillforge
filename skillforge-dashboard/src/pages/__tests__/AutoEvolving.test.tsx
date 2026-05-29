/**
 * AUTOEVOLVING V1 Sprint 4 — AutoEvolving page tests.
 *   1. KPI strip renders overview counts
 *   2. signal-panel row click navigates to the deep-dive route
 *   3. a `workflow_human_approve_required` WS frame appends an approve card
 *
 * WorkflowRunsPanel is stubbed (it owns its own WS + react-flow canvas).
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

// jsdom polyfills AntD touches.
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

// Capture FakeWs instances so the test can drive WS frames.
const wsInstances: FakeWs[] = [];
class FakeWs {
  onmessage: ((ev: { data: string }) => void) | null = null;
  constructor() {
    wsInstances.push(this);
  }
  close() {}
  send() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

// react-router navigate spy.
const navigateSpy = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateSpy };
});

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

vi.mock('../../components/workflow/WorkflowRunsPanel', () => ({
  default: () => <div data-testid="wf-runs-panel-stub" />,
}));

const getOverviewMock = vi.fn();
vi.mock('../../api/autoevolving', () => ({
  getOverview: (...a: unknown[]) => getOverviewMock(...a),
}));

const listMemoryProposalsMock = vi.fn();
vi.mock('../../api/memoryProposalsApi', () => ({
  listMemoryProposals: (...a: unknown[]) => listMemoryProposalsMock(...a),
}));

// usePendingApprovals → api/workflow (paused runs + approveRun for the card).
const listWorkflowRunsMock = vi.fn();
const getWorkflowRunMock = vi.fn();
vi.mock('../../api/workflow', () => ({
  listWorkflowRuns: (...a: unknown[]) => listWorkflowRunsMock(...a),
  getWorkflowRun: (...a: unknown[]) => getWorkflowRunMock(...a),
  approveRun: vi.fn(),
}));

const OVERVIEW = {
  kpi: {
    workflowRunning: 2,
    workflowCompletedThisWeek: 5,
    memoryProposalPending: 3,
    autoResearchPending: null,
  },
  recentReports: [
    { reportId: 'rep-1', agentId: 7, agentName: 'annotator', windowEnd: null, status: 'completed', topIssueCount: 4 },
  ],
  recentAnomalies: [
    { runId: 'run-bad', name: 'opt-report', status: 'error', errorReason: 'boom', updatedAt: null },
  ],
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <AutoEvolving />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

import AutoEvolving from '../AutoEvolving';

describe('AutoEvolving page', () => {
  beforeEach(() => {
    wsInstances.length = 0;
    navigateSpy.mockReset();
    getOverviewMock.mockReset().mockResolvedValue({ data: OVERVIEW });
    listMemoryProposalsMock.mockReset().mockResolvedValue({ data: [] });
    listWorkflowRunsMock.mockReset().mockResolvedValue({ data: { items: [], total: 0, limit: 20, offset: 0 } });
    getWorkflowRunMock.mockReset();
  });

  it('renders KPI counts from overview', async () => {
    renderPage();
    expect(await screen.findByTestId('ae-kpi-strip')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByTestId('ae-kpi-running')).toHaveTextContent('2');
      expect(screen.getByTestId('ae-kpi-completed')).toHaveTextContent('5');
      expect(screen.getByTestId('ae-kpi-memory')).toHaveTextContent('3');
    });
    // autoResearch placeholder
    expect(screen.getByTestId('ae-kpi-research')).toHaveTextContent('N/A');
  });

  it('navigates on production report row click', async () => {
    renderPage();
    const row = await screen.findByTestId('ae-report-row-rep-1');
    fireEvent.click(row);
    expect(navigateSpy).toHaveBeenCalledWith(
      expect.stringContaining('/insights/patterns?tab=reports'),
    );
  });

  it('appends an approve card on workflow_human_approve_required WS frame', async () => {
    renderPage();
    await screen.findByTestId('ae-kpi-strip');
    // At least one WS connection opened (usePendingApprovals).
    await waitFor(() => expect(wsInstances.length).toBeGreaterThan(0));

    act(() => {
      for (const ws of wsInstances) {
        ws.onmessage?.({
          data: JSON.stringify({
            type: 'workflow_human_approve_required',
            runId: 'run-live1',
            stepIndex: 1,
            payload: { topIssues: ['x'] },
          }),
        });
      }
    });

    expect(await screen.findByTestId('wf-approve-card-run-live1-1')).toBeInTheDocument();
  });
});
