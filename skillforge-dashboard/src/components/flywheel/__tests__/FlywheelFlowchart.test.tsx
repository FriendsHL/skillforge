/**
 * FLYWHEEL-FLOWCHART — FlywheelFlowchart renders.
 *
 * Five cases (per pipeline.md — lock the visible contract, not the
 * implementation):
 *   1. Panel renders the DAG shell (header + both tab tiers + flowchart
 *      container)
 *   2. Catalogue steps render as React Flow nodes (at least the 4 entry
 *      nodes for the default user/skill surface)
 *   3. Drill-down link inside a node points at the documented operate page
 *      (G1 → OptimizationEvents proposal_pending)
 *   4. `fw-node--running` className is applied when isRunning is true
 *      (in-flight > 0 on an AUTO node) and absent on USER gates even
 *      when they have a pending count
 *   5. `prefers-reduced-motion` query exists in the cascade — verified
 *      indirectly via the `.fw-node--running` rule's presence with an
 *      `outline` fallback (smoke test; deep media-query mocking is out
 *      of scope for vitest+jsdom)
 *
 * Module mocks at API boundary — no real BE; deterministic StepMetrics
 * inputs let us assert on running flags + edge animation classes.
 */
import React from 'react';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

// React Flow uses ResizeObserver + matchMedia (via Background, fitView, etc.)
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
// jsdom doesn't implement DOMMatrix / DOMRect APIs React Flow internals
// occasionally probe — supply minimal stubs so the import doesn't crash.
if (!(globalThis as unknown as { DOMMatrixReadOnly?: unknown }).DOMMatrixReadOnly) {
  class DOMMatrixReadOnly {
    m22 = 1;
    constructor() {}
  }
  (globalThis as unknown as { DOMMatrixReadOnly: typeof DOMMatrixReadOnly }).DOMMatrixReadOnly =
    DOMMatrixReadOnly;
}

// ──────────────────────── module mocks ────────────────────────

// One pattern (step2-cluster will report inFlight=1 → AUTO running pulse).
vi.mock('../../../api/insights', () => ({
  listPatterns: vi.fn(() =>
    Promise.resolve({
      data: [
        {
          id: 1,
          signature: 'sig',
          outcome: 'failure',
          suspectSurface: 'skill',
          memberCount: 3,
          firstSeenAt: new Date(Date.now() - 60_000).toISOString(),
          lastSeenAt: new Date(Date.now() - 30_000).toISOString(),
          topFailingTool: null,
          agentId: null,
          suggestedSurface: null,
        },
      ],
    }),
  ),
}));

// One attribution event in proposal_pending stage (drives G1 PEND chip).
vi.mock('../../../api/attribution', () => ({
  listEvents: vi.fn(() =>
    Promise.resolve({
      data: {
        items: [
          {
            id: 42,
            patternId: 7,
            agentId: 3,
            surfaceType: 'skill',
            changeType: null,
            description: null,
            expectedImpact: null,
            confidence: null,
            risk: null,
            stage: 'proposal_pending',
            candidateSkillId: null,
            candidatePromptVersionId: null,
            abRunId: null,
            canaryId: null,
            attributionSessionId: null,
            cooldownUntil: null,
            createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
            updatedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
          },
        ],
        page: 0,
        size: 200,
        total: 1,
      },
    }),
  ),
}));

vi.mock('../../../api/flywheel', () => ({
  listAbTestRunsGlobal: vi.fn(() => Promise.resolve({ data: { items: [] } })),
  listCanariesGlobal: vi.fn(() => Promise.resolve({ data: [] })),
  listSkillDraftsBySource: vi.fn(() => Promise.resolve({ data: [] })),
  // FLYWHEEL-PER-RUN — runs endpoint. One pending + one errored run.
  listFlywheelRuns: vi.fn(() =>
    Promise.resolve({
      // BE envelope shape: { items, limit, hideTerminal } — NOT bare array.
      // The pre-hotfix mock returned `data: [...]` which matched a wrong FE
      // assumption (now corrected) and triggered "runs is not iterable" at
      // runtime against the real BE. Tests now mock the canonical shape.
      data: {
        limit: 20,
        hideTerminal: true,
        items: [
        {
          optEventId: 101,
          agentId: 3,
          agentName: 'insights-agent',
          surface: 'skill',
          patternId: 7,
          patternSignature: 'sig:abc:001',
          currentStage: 'candidate_failed',
          errorLabel: 'json parse error',
          startedAt: new Date(Date.now() - 30 * 60_000).toISOString(),
          lastUpdatedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
          candidateSkillDraftUuid: null,
          abRunId: null,
          description: 'Candidate generation tripped on malformed JSON returned from LLM. Snippet: {"name": …',
        },
        {
          optEventId: 202,
          agentId: 4,
          agentName: 'pricing-agent',
          surface: 'skill',
          patternId: 8,
          patternSignature: 'sig:def:002',
          currentStage: 'proposal_pending',
          errorLabel: null,
          startedAt: new Date(Date.now() - 90 * 60_000).toISOString(),
          lastUpdatedAt: new Date(Date.now() - 60 * 60_000).toISOString(),
          candidateSkillDraftUuid: null,
          abRunId: null,
          description: null,
        },
        ],
      },
    }),
  ),
}));

vi.mock('../../../api/systemAgents', () => ({
  getSystemAgentMonitor: vi.fn(() =>
    Promise.resolve({
      data: [
        {
          agentId: 1,
          name: 'session-annotator',
          description: null,
          cronExpression: '0 0 * * * *',
          // 30 min ago vs 60-min cron → healthy lag.
          lastRunAt: new Date(Date.now() - 30 * 60_000).toISOString(),
          // 'running' status → flowchart should pulse this AUTO node.
          lastRunStatus: 'running',
          sevenDayTriggerCount: 168,
          sevenDayOutputCount: 24,
          outputEntityType: 'annotations',
        },
      ],
    }),
  ),
}));

vi.mock('../../../api', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('../../../api');
  return {
    ...actual,
    getSessions: vi.fn(() => Promise.resolve({ data: [] })),
    extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
      if (Array.isArray(res.data)) return res.data;
      const items = (res.data as { items?: T[] }).items;
      return Array.isArray(items) ? items : [];
    },
  };
});

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

// ──────────────────────── render helper ────────────────────────

import FlywheelFlowchart from '../FlywheelFlowchart';

function renderChart() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/insights/patterns?tab=flywheel']}>
        <FlywheelFlowchart />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

describe('FlywheelFlowchart', () => {
  it('renders the panel shell with both tab tiers + flowchart container', async () => {
    renderChart();
    expect(await screen.findByText(/Insight Loop/i)).toBeInTheDocument();
    expect(screen.getByTestId('agent-type-tabs')).toHaveAttribute(
      'role',
      'tablist',
    );
    expect(screen.getByTestId('surface-tabs')).toHaveAttribute(
      'role',
      'tablist',
    );
    expect(screen.getByTestId('flywheel-flowchart')).toBeInTheDocument();
    // Default active surface = `skill`.
    const skillTab = screen.getByRole('tab', { name: /^skill$/ });
    expect(skillTab).toHaveClass('on');
    expect(skillTab).toHaveAttribute('aria-selected', 'true');
  });

  it('renders catalogue steps as React Flow nodes for user/skill surface', async () => {
    renderChart();
    // Wait for the data hook to populate the DAG.
    await waitFor(() => {
      expect(screen.getByTestId('fw-node-E1-user-chat')).toBeInTheDocument();
    });
    // E1-E4 entries + the pipeline steps should all show up — sample a few.
    expect(screen.getByTestId('fw-node-E2-upload-skill')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-step1-annotate')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-G1-approve-event')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-step5-abtest')).toBeInTheDocument();
    expect(screen.getByTestId('fw-node-step9-decide')).toBeInTheDocument();
  });

  it('click G1 card → detail Drawer opens with Chinese label + drill-down link footer', async () => {
    renderChart();
    const node = await screen.findByTestId('fw-node-G1-approve-event');
    const card = within(node).getByTestId('step-G1-approve-event');
    // Card is now a <button> (was <a> before drawer landed) — verify the
    // click target is keyboard-friendly + emits onSelect to open drawer.
    expect(card.tagName).toBe('BUTTON');
    fireEvent.click(card);
    // Drawer mounts with Chinese label visible.
    const drawer = await screen.findByTestId('fw-drawer');
    expect(drawer.getAttribute('data-step-id')).toBe('G1-approve-event');
    expect(within(drawer).getByText('G1 · 审 OptEvent')).toBeInTheDocument();
    // Drill-down link is on the Drawer footer, pointing at the operate page.
    const drill = within(drawer).getByTestId('fw-drawer-drill-link');
    expect(drill.tagName).toBe('A');
    expect(drill.getAttribute('href')).toBe(
      '/insights/patterns?tab=optimization&stage=proposal_pending',
    );
  });

  it('Chinese labels render on the cards (E1 / ① 标注 / G1)', async () => {
    renderChart();
    await waitFor(() => {
      expect(screen.getByTestId('fw-node-E1-user-chat')).toBeInTheDocument();
    });
    // Sample one of each node type — confirm labelCn replaced English title.
    expect(screen.getByText('E1 · 用户聊天')).toBeInTheDocument();
    expect(screen.getByText('① 标注')).toBeInTheDocument();
    expect(screen.getByText('G1 · 审 OptEvent')).toBeInTheDocument();
    // Old English titles should NOT appear in the rendered cards.
    expect(screen.queryByText('E1 · user chat session')).not.toBeInTheDocument();
    expect(screen.queryByText('① annotate · session-annotator')).not.toBeInTheDocument();
  });

  it('Drawer Esc-close + backdrop-close', async () => {
    renderChart();
    const node = await screen.findByTestId('fw-node-step1-annotate');
    fireEvent.click(within(node).getByTestId('step-step1-annotate'));
    expect(await screen.findByTestId('fw-drawer')).toBeInTheDocument();
    // Esc closes.
    fireEvent.keyDown(window, { key: 'Escape' });
    await waitFor(() => {
      expect(screen.queryByTestId('fw-drawer')).not.toBeInTheDocument();
    });
    // Re-open + backdrop click closes.
    fireEvent.click(within(node).getByTestId('step-step1-annotate'));
    const drawer2 = await screen.findByTestId('fw-drawer');
    expect(drawer2).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('fw-drawer-backdrop'));
    await waitFor(() => {
      expect(screen.queryByTestId('fw-drawer')).not.toBeInTheDocument();
    });
  });

  it('applies fw-node--running on AUTO nodes with in-flight or running cron, but NOT on USER gates', async () => {
    renderChart();
    // F2 — symmetric assertions: BOTH AUTO running nodes check
    // data-running AND className, both wrapped in waitFor so the async
    // hook tick doesn't race the assertion.
    // step1-annotate: lastRunStatus='running' from monitor mock → pulse on.
    const annotator = await screen.findByTestId('fw-node-step1-annotate');
    await waitFor(() => {
      expect(annotator.getAttribute('data-running')).toBe('true');
      expect(annotator.className).toContain('fw-node--running');
    });
    // step2-cluster: inFlight=1 (one pattern) → AUTO node should also pulse.
    const cluster = await screen.findByTestId('fw-node-step2-cluster');
    await waitFor(() => {
      expect(cluster.getAttribute('data-running')).toBe('true');
      expect(cluster.className).toContain('fw-node--running');
    });
    // G1: USER gate with pending count, but USER nodes never pulse.
    const g1 = screen.getByTestId('fw-node-G1-approve-event');
    expect(g1.getAttribute('data-running')).toBe('false');
    expect(g1.className).not.toContain('fw-node--running');
    // E1: ENTRY nodes also never pulse.
    const e1 = screen.getByTestId('fw-node-E1-user-chat');
    expect(e1.getAttribute('data-running')).toBe('false');
    expect(e1.className).not.toContain('fw-node--running');
  });

  // F3 — was an `expect(true).toBe(true)` no-op (jsdom can't actually flip
  // matchMedia at runtime, so we can't trigger the prefers-reduced-motion
  // cascade in unit tests). Marked as `it.todo` so CI surfaces the gap as
  // an unimplemented test instead of a silent pass; verification belongs
  // in a Playwright/browser e2e where the OS-level reduce-motion can be
  // emulated via `page.emulateMedia({ reducedMotion: 'reduce' })`.
  it.todo(
    'TODO: reduced-motion CSS rule applied — needs Playwright/browser harness to verify, jsdom matchMedia limitation',
  );

  // ──────────── FLYWHEEL-PER-RUN ────────────

  it('mode toggle switches to per-run mode → runs sidebar appears + idle hint shown', async () => {
    renderChart();
    // Default mode is aggregate; no sidebar yet.
    expect(await screen.findByTestId('flywheel-flowchart-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('flywheel-runs-sidebar')).not.toBeInTheDocument();
    // Click the Per-Run mode toggle.
    fireEvent.click(screen.getByTestId('fw-mode-perRun'));
    // Sidebar mounts; idle hint appears (no run selected yet).
    expect(await screen.findByTestId('flywheel-runs-sidebar')).toBeInTheDocument();
    expect(screen.getByTestId('fw-perrun-hint')).toBeInTheDocument();
    // Wait for runs to populate the list (mocked listFlywheelRuns returned 2).
    await waitFor(() => {
      expect(screen.getByTestId('fw-runs-row-101')).toBeInTheDocument();
    });
    expect(screen.getByTestId('fw-runs-row-202')).toBeInTheDocument();
  });

  it('unmapped BE stage (e.g. proposal_approved) → no current-step highlight, no crash', async () => {
    // Override the listFlywheelRuns mock to inject a run with an unmapped
    // stage (transient transition value per types.ts doc). FlywheelFlowchart
    // should render the DAG without throwing + skip the current-step
    // decoration (no node gets fw-node--current-for-run / --error-for-run).
    const flywheelApiMod = await import('../../../api/flywheel');
    const listFlywheelRunsMock = vi.mocked(flywheelApiMod.listFlywheelRuns);
    listFlywheelRunsMock.mockResolvedValueOnce({
      data: {
        limit: 20,
        hideTerminal: true,
        items: [
        {
          optEventId: 999,
          agentId: 7,
          agentName: 'unmapped-stage-agent',
          surface: 'skill',
          patternId: 9,
          patternSignature: 'sig:xyz:003',
          currentStage: 'proposal_approved', // ← unmapped (transient)
          errorLabel: null,
          startedAt: new Date(Date.now() - 20 * 60_000).toISOString(),
          lastUpdatedAt: new Date(Date.now() - 2 * 60_000).toISOString(),
          candidateSkillDraftUuid: null,
          abRunId: null,
          description: null,
        },
      ],
      },
    } as Awaited<ReturnType<typeof flywheelApiMod.listFlywheelRuns>>);
    renderChart();
    fireEvent.click(screen.getByTestId('fw-mode-perRun'));
    const row = await screen.findByTestId('fw-runs-row-999');
    fireEvent.click(row);
    // Sidebar shows the run; idle hint should disappear since a run IS
    // selected (the run is "real", just at an unmapped stage).
    await waitFor(() => {
      expect(screen.queryByTestId('fw-perrun-hint')).not.toBeInTheDocument();
    });
    // No node should carry the current/error highlight for an unmapped stage.
    const allG1 = screen.queryByTestId('fw-node-G1-approve-event');
    expect(allG1).toBeInTheDocument(); // panel didn't crash
    expect(allG1?.className ?? '').not.toContain('fw-node--current-for-run');
    expect(allG1?.className ?? '').not.toContain('fw-node--error-for-run');
    // Pre-OptEvent nodes are still context-classed (selection is valid).
    const step1 = screen.getByTestId('fw-node-step1-annotate');
    expect(step1.className).toContain('fw-node--context');
  });

  it('selecting a run → DAG highlights current step + pre-OptEvent nodes get fw-node--context', async () => {
    renderChart();
    fireEvent.click(screen.getByTestId('fw-mode-perRun'));
    // Wait for sidebar runs to populate.
    const row = await screen.findByTestId('fw-runs-row-101');
    fireEvent.click(row);
    // Idle hint disappears once a run is selected.
    await waitFor(() => {
      expect(screen.queryByTestId('fw-perrun-hint')).not.toBeInTheDocument();
    });
    // candidate_failed maps to step4-candidate (per STAGE_TO_STEP) AND is in
    // ERROR_STAGES → fw-node--error-for-run wins over current-for-run class.
    const step4 = await screen.findByTestId('fw-node-step4-candidate');
    await waitFor(() => {
      expect(step4.className).toContain('fw-node--error-for-run');
    });
    expect(step4.getAttribute('data-current-for-run')).toBe('true');
    // Pre-OptEvent nodes (entry + step1/2/3) have fw-node--context class.
    const step1 = screen.getByTestId('fw-node-step1-annotate');
    expect(step1.className).toContain('fw-node--context');
    expect(step1.getAttribute('data-context-for-run')).toBe('true');
    const step3 = screen.getByTestId('fw-node-step3-attribute');
    expect(step3.className).toContain('fw-node--context');
    // Aggregate-mode "running" class should NOT be on per-run nodes
    // (we suppress running animation in per-run mode).
    expect(step1.className).not.toContain('fw-node--running');
  });

  it('chip text + color: candidate_failed → "failed here" + --error class (red); description renders in Drawer', async () => {
    // Run #101 in default mock = candidate_failed + description populated.
    renderChart();
    fireEvent.click(screen.getByTestId('fw-mode-perRun'));
    const row = await screen.findByTestId('fw-runs-row-101');
    fireEvent.click(row);
    const chip = await screen.findByTestId('current-chip-step4-candidate');
    expect(chip.textContent).toBe('failed here');
    expect(chip.className).toContain('fw-step-chip-current--error');
    expect(chip.className).not.toContain('fw-step-chip-current--rejected');
    // Click the failed node → Drawer should render "原因详情" with description.
    fireEvent.click(screen.getByTestId('fw-node-step4-candidate'));
    const reason = await screen.findByTestId('fw-drawer-reason');
    expect(reason.textContent).toContain('Candidate generation tripped on malformed JSON');
  });

  it('chip text + color: proposal_rejected → "rejected here" + --rejected class (amber)', async () => {
    // Override the runs mock with a single proposal_rejected run so we
    // exercise the amber rejected variant (default mocks only have
    // candidate_failed + proposal_pending).
    const flywheelApiMod = await import('../../../api/flywheel');
    const mock = vi.mocked(flywheelApiMod.listFlywheelRuns);
    mock.mockResolvedValueOnce({
      data: {
        limit: 20,
        hideTerminal: true,
        items: [
          {
            optEventId: 109,
            agentId: 2,
            agentName: 'Code Agent',
            surface: 'skill',
            patternId: 3,
            patternSignature: 'failure|skill|ReadFile|2',
            currentStage: 'proposal_rejected',
            errorLabel: 'Proposal rejected',
            startedAt: new Date(Date.now() - 60 * 60_000).toISOString(),
            lastUpdatedAt: new Date(Date.now() - 30 * 60_000).toISOString(),
            candidateSkillDraftUuid: null,
            abRunId: null,
            description: 'rejected: suspect_surface=other (infrastructure credential failure).',
          },
        ],
      },
    } as Awaited<ReturnType<typeof flywheelApiMod.listFlywheelRuns>>);
    renderChart();
    fireEvent.click(screen.getByTestId('fw-mode-perRun'));
    const row = await screen.findByTestId('fw-runs-row-109');
    fireEvent.click(row);
    // proposal_rejected → STAGE_TO_STEP maps to G1-approve-event.
    const chip = await screen.findByTestId('current-chip-G1-approve-event');
    expect(chip.textContent).toBe('rejected here');
    expect(chip.className).toContain('fw-step-chip-current--rejected');
    expect(chip.className).not.toContain('fw-step-chip-current--error');
  });
});
