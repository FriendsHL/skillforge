/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 (FE F6) — SkillDrafts page tests for the
 * Evaluated / Rejected tabs and the rejected-iterate deep-link.
 *
 * Asserts:
 *   - Rejected tab renders the rejected drafts and surfaces an Iterate button
 *   - Evaluated_passed tab renders evaluated drafts (positive verdict)
 *   - Iterate click deep-links to /chat?prefill=...&draftId=...
 */
import React from 'react';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import type { SkillDraft } from '../../api';

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

// jsdom lacks WebSocket — stub so the WS useEffect doesn't blow up.
class FakeWs {
  onmessage: ((ev: { data: string }) => void) | null = null;
  close() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

const getSkillDraftsMock = vi.fn(() => Promise.resolve({ data: [] as SkillDraft[] }));

vi.mock('../../api', () => ({
  getSkillDrafts: (...args: unknown[]) => getSkillDraftsMock(...(args as [])),
  reviewSkillDraft: vi.fn(() => Promise.resolve({ data: {} })),
  mergeDraftIntoSkill: vi.fn(() => Promise.resolve({ data: {} })),
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual: typeof import('react-router-dom') = await importOriginal();
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

import SkillDraftsPage from '../SkillDrafts';

function makeRejectedDraft(overrides: Partial<SkillDraft> = {}): SkillDraft {
  return {
    id: 'draft-rej-1',
    ownerId: 1,
    name: 'failed-skill',
    description: 'A skill that did not pass the eval threshold',
    status: 'rejected',
    createdAt: '2026-05-10T00:00:00Z',
    reviewedAt: '2026-05-11T00:00:00Z',
    source: 'extract-from-sessions',
    targetAgentId: 7,
    evaluatedAt: '2026-05-11T00:00:00Z',
    evaluationResult: {
      withSkill: {
        compositeScore: 0.4,
        overallScore: 0.42,
        passRate: 0.3,
        avgLatencyMs: 4000,
        totalCostUsd: 0.002,
      },
      withoutSkill: {
        compositeScore: 0.45,
        overallScore: 0.46,
        passRate: 0.33,
        avgLatencyMs: 3200,
        totalCostUsd: 0.001,
      },
      delta: {
        compositeScore: -0.05,
        overallScore: -0.04,
        passRate: -0.03,
        avgLatencyMs: 800,
        totalCostUsd: 0.001,
      },
      llmSummary: 'failed-skill does not improve pass_rate; recommend reject.',
      sourceSessionIds: ['sess-r1'],
      scenarioCount: 1,
      evaluatedAt: '2026-05-11T00:00:00Z',
      evaluatorVersion: 'skill-creator-1.0',
    },
    ...overrides,
  };
}

function makeEvaluatedDraft(overrides: Partial<SkillDraft> = {}): SkillDraft {
  return {
    id: 'draft-eval-1',
    ownerId: 1,
    name: 'csv-analyzer',
    description: 'Analyses CSV files',
    status: 'evaluated_passed',
    createdAt: '2026-05-10T00:00:00Z',
    source: 'natural-language',
    targetAgentId: 7,
    evaluatedAt: '2026-05-11T00:00:00Z',
    evaluationResult: {
      withSkill: {
        compositeScore: 0.85,
        overallScore: 0.82,
        passRate: 0.85,
        avgLatencyMs: 4500,
        totalCostUsd: 0.0023,
      },
      withoutSkill: {
        compositeScore: 0.5,
        overallScore: 0.48,
        passRate: 0.33,
        avgLatencyMs: 3200,
        totalCostUsd: 0.0011,
      },
      delta: {
        compositeScore: 0.35,
        overallScore: 0.34,
        passRate: 0.52,
        avgLatencyMs: 1300,
        totalCostUsd: 0.0012,
      },
      llmSummary: 'csv-analyzer lifts pass_rate by 52pp; recommend promote.',
      sourceSessionIds: ['sess-e1', 'sess-e2'],
      scenarioCount: 2,
      evaluatedAt: '2026-05-11T00:00:00Z',
      evaluatorVersion: 'skill-creator-1.0',
    },
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <SkillDraftsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('SkillDraftsPage — SKILL-CREATOR-WITH-EVAL Phase 1.3 tabs', () => {
  beforeEach(() => {
    getSkillDraftsMock.mockReset();
    navigateMock.mockReset();
  });

  it('renders the Rejected and Evaluated stat chips alongside the legacy filters', async () => {
    getSkillDraftsMock.mockResolvedValue({ data: [] as SkillDraft[] });
    renderPage();

    expect(await screen.findByTestId('filter-rejected')).toBeInTheDocument();
    expect(screen.getByTestId('filter-evaluated-passed')).toBeInTheDocument();
  });

  it('renders rejected drafts when the Rejected filter is selected', async () => {
    const rejected = makeRejectedDraft();
    const pending = { ...makeRejectedDraft({ id: 'draft-pending-1', status: 'draft', name: 'pending-skill' }) };
    getSkillDraftsMock.mockResolvedValue({ data: [pending, rejected] });

    renderPage();

    // Wait for the data fetch to land — the rejected chip count switches
    // from 0 to 1 once the useQuery promise resolves.
    await waitFor(() => {
      expect(screen.getByTestId('filter-rejected')).toHaveTextContent(/1 rejected/);
    });

    const rejectedTab = screen.getByTestId('filter-rejected');
    await act(async () => {
      fireEvent.click(rejectedTab);
    });

    await waitFor(() => {
      expect(screen.getByTestId('skill-draft-detail-drawer')).toBeInTheDocument();
    });

    // failed-skill appears in both the list item and the drawer header — use
    // getAllByText to tolerate the duplicate without failing assertion.
    expect(screen.getAllByText('failed-skill').length).toBeGreaterThan(0);
  });

  it('renders evaluated_passed drafts when the Evaluated filter is selected', async () => {
    const evaluated = makeEvaluatedDraft();
    getSkillDraftsMock.mockResolvedValue({ data: [evaluated] });

    renderPage();

    // Wait for chip to update with count=1 once useQuery resolves.
    await waitFor(() => {
      expect(screen.getByTestId('filter-evaluated-passed')).toHaveTextContent(/1 evaluated/);
    });
    const evaluatedTab = screen.getByTestId('filter-evaluated-passed');
    await act(async () => {
      fireEvent.click(evaluatedTab);
    });

    await waitFor(() => {
      expect(screen.getAllByText('csv-analyzer').length).toBeGreaterThan(0);
    });
  });

  it('Iterate button on a rejected draft deep-links to /chat with prefill payload', async () => {
    const rejected = makeRejectedDraft();
    getSkillDraftsMock.mockResolvedValue({ data: [rejected] });

    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('filter-rejected')).toHaveTextContent(/1 rejected/);
    });
    const rejectedTab = screen.getByTestId('filter-rejected');
    await act(async () => {
      fireEvent.click(rejectedTab);
    });

    // Iterate button lives in the detail drawer footer.
    const iterateBtn = await screen.findByTestId('iterate-btn');
    await act(async () => {
      fireEvent.click(iterateBtn);
    });

    expect(navigateMock).toHaveBeenCalledTimes(1);
    const target = navigateMock.mock.calls[0][0] as string;
    expect(target).toMatch(/^\/chat\?prefill=/);
    expect(target).toContain('draftId=draft-rej-1');
    // The reject reason from llmSummary should be URL-encoded into the prefill.
    expect(decodeURIComponent(target)).toContain('failed-skill does not improve pass_rate');
  });
});
