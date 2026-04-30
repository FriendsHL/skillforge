/**
 * P1-C-7 FE: SkillList must disable Extract / A-B / Evolution actions until
 * the operator picks a source agent. This test asserts the initial disabled
 * state of the Extract button when no agent is selected.
 *
 * P1-C-8 FE (B-FE-2/3): high-similarity draft approve must trigger a
 * confirmation Modal and forward `forceCreate=true` only after the operator
 * explicitly clicks "Create anyway".
 */
import React from 'react';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
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

// jsdom lacks WebSocket — stub so the SkillList useEffect doesn't blow up.
class FakeWs {
  onmessage: ((ev: { data: string }) => void) | null = null;
  close() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

const reviewSkillDraftMock = vi.fn(() => Promise.resolve({ data: {} }));
const getSkillDraftsMock = vi.fn(() => Promise.resolve({ data: [] as SkillDraft[] }));
const rescanSkillsMock = vi.fn(() =>
  Promise.resolve({
    data: {
      created: 2,
      updated: 1,
      missing: 0,
      invalid: 0,
      shadowed: 1,
      disabledDuplicates: 0,
    },
  }),
);
const getSkillsMock = vi.fn(() => Promise.resolve({ data: [] }));

vi.mock('../../api', () => ({
  getSkills: (...args: unknown[]) => getSkillsMock(...(args as [])),
  uploadSkill: vi.fn(() => Promise.resolve({ data: {} })),
  deleteSkill: vi.fn(() => Promise.resolve({ data: {} })),
  toggleSkill: vi.fn(() => Promise.resolve({ data: {} })),
  getSkillDrafts: (...args: unknown[]) => getSkillDraftsMock(...(args as [])),
  triggerSkillExtraction: vi.fn(() => Promise.resolve({ data: { status: 'started' } })),
  reviewSkillDraft: (...args: unknown[]) => reviewSkillDraftMock(...(args as [])),
  rescanSkills: (...args: unknown[]) => rescanSkillsMock(...(args as [])),
  getAgents: vi.fn(() => Promise.resolve({ data: [{ id: 7, name: 'My Agent' }] })),
  extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
    if (Array.isArray(res.data)) return res.data;
    const items = (res.data as { items?: T[] }).items;
    return Array.isArray(items) ? items : [];
  },
}));

// Mock AuthContext.useAuth so tests don't need the AuthProvider.
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

import SkillList from '../SkillList';

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <SkillList />
    </QueryClientProvider>,
  );
}

describe('SkillList — source agent selector (P1-C-7)', () => {
  beforeEach(() => {
    reviewSkillDraftMock.mockClear();
    getSkillDraftsMock.mockReset();
    getSkillDraftsMock.mockResolvedValue({ data: [] as SkillDraft[] });
  });

  it('Extract from Sessions button is disabled when no agent is selected', async () => {
    renderPage();
    const extractBtn = await screen.findByTestId('extract-btn');
    expect(extractBtn).toBeDisabled();
  });

  it('renders the source-agent selector control', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('source-agent-select')).toBeInTheDocument();
    });
  });

  /**
   * B-FE-2/3 — high-similarity approve must NOT call reviewSkillDraft
   * directly; it must first open Modal.confirm. After the operator clicks
   * "Create anyway", reviewSkillDraft is invoked with forceCreate=true.
   */
  it('high-similarity draft approve opens Modal and forwards forceCreate=true on confirm', async () => {
    const dupDraft: SkillDraft = {
      id: 'dup-1',
      ownerId: 1,
      name: 'duplicate-skill',
      description: 'A near-duplicate',
      status: 'draft',
      createdAt: new Date().toISOString(),
      similarity: 0.92,
      mergeCandidateName: 'existing-skill',
    };
    getSkillDraftsMock.mockResolvedValue({ data: [dupDraft] });

    renderPage();

    // Wait until the "pending drafts" toggle button shows up (depends on the
    // mocked draft loading via react-query).
    const pendingToggle = await screen.findByRole('button', {
      name: /1 pending draft/i,
    });
    await act(async () => {
      fireEvent.click(pendingToggle);
    });

    // Click the in-section Approve button. There are no other Approve buttons
    // on the page, so a name-based query is unambiguous.
    const approveBtn = await screen.findByRole('button', { name: /^Approve$/ });
    await act(async () => {
      fireEvent.click(approveBtn);
    });

    // Modal.confirm appears — assert by the danger CTA "Create anyway".
    const confirmBtn = await screen.findByRole('button', { name: /Create anyway/i });
    expect(confirmBtn).toBeInTheDocument();

    // reviewSkillDraft must NOT have been called yet (Modal still open).
    expect(reviewSkillDraftMock).not.toHaveBeenCalled();

    await act(async () => {
      fireEvent.click(confirmBtn);
    });

    await waitFor(() => {
      expect(reviewSkillDraftMock).toHaveBeenCalledTimes(1);
    });
    const call = reviewSkillDraftMock.mock.calls[0] as unknown as [
      string,
      'approve' | 'discard',
      number,
      { forceCreate?: boolean } | undefined,
    ];
    expect(call[0]).toBe('dup-1');
    expect(call[1]).toBe('approve');
    expect(call[2]).toBe(1);
    expect(call[3]).toEqual({ forceCreate: true });
  });
});

describe('SkillList — Rescan button (P1-D T9)', () => {
  beforeEach(() => {
    rescanSkillsMock.mockClear();
    getSkillDraftsMock.mockReset();
    getSkillDraftsMock.mockResolvedValue({ data: [] as SkillDraft[] });
    getSkillsMock.mockReset();
    getSkillsMock.mockResolvedValue({ data: [] });
  });

  it('renders the Rescan button', async () => {
    renderPage();
    const btn = await screen.findByTestId('rescan-btn');
    expect(btn).toBeInTheDocument();
    expect(btn).toHaveTextContent(/Rescan/i);
  });

  it('calls rescanSkills and surfaces the report when clicked', async () => {
    renderPage();
    const btn = await screen.findByTestId('rescan-btn');
    await act(async () => {
      fireEvent.click(btn);
    });
    await waitFor(() => {
      expect(rescanSkillsMock).toHaveBeenCalledTimes(1);
    });
    // Modal.info renders the rescan report; assert by data-testid + counts.
    const report = await screen.findByTestId('rescan-report');
    expect(report).toBeInTheDocument();
    expect(report.textContent).toMatch(/2.*created/);
    expect(report.textContent).toMatch(/1.*updated/);
    expect(report.textContent).toMatch(/1.*shadowed/);
  });
});
