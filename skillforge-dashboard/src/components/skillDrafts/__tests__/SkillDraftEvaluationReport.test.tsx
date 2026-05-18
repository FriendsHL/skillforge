/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 (FE F5) — tests for SkillDraftEvaluationReport.
 *
 * 3 cases (matching the brief):
 *   - delta > 0 → success Alert + benchmark rendered
 *   - delta < 0 (reject) → warning Alert + rejected status badge
 *   - drill-down click navigates to /sessions?sessionIds=...
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import type { EvaluationResult } from '../../../api/skillDrafts';

// Use module-level mock so we can assert navigation calls. react-router-dom
// `useNavigate` is otherwise wired to the real router which has no recorded
// navigation in the testing memory history we can introspect cheaply.
const navigateMock = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual: typeof import('react-router-dom') = await importOriginal();
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

// jsdom polyfills for AntD components that touch ResizeObserver / matchMedia.
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

import { SkillDraftEvaluationReport } from '../SkillDraftEvaluationReport';

function makeResult(overrides: Partial<EvaluationResult> = {}): EvaluationResult {
  return {
    withSkill: {
      compositeScore: 0.82,
      overallScore: 0.78,
      passRate: 0.85,
      avgLatencyMs: 4500,
      totalCostUsd: 0.0023,
    },
    withoutSkill: {
      compositeScore: 0.48,
      overallScore: 0.45,
      passRate: 0.33,
      avgLatencyMs: 3200,
      totalCostUsd: 0.0011,
    },
    delta: {
      compositeScore: 0.34,
      overallScore: 0.33,
      passRate: 0.52,
      avgLatencyMs: 1300,
      totalCostUsd: 0.0012,
    },
    llmSummary:
      'csv-analyzer skill lifts pass_rate by 52pp on the 3-scenario eval set; recommend promote.',
    sourceSessionIds: ['sess-abc', 'sess-def', 'sess-xyz'],
    scenarioCount: 3,
    evaluatedAt: '2026-05-18T10:23:45Z',
    evaluatorVersion: 'skill-creator-1.0',
    ...overrides,
  };
}

function renderWithRouter(ui: React.ReactNode) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('SkillDraftEvaluationReport', () => {
  beforeEach(() => {
    navigateMock.mockReset();
  });

  it('renders success summary when delta.passRate >= threshold', () => {
    const result = makeResult();
    renderWithRouter(<SkillDraftEvaluationReport result={result} />);

    // Headline statistic shows +52.0% pass rate delta — appears both in the
    // top Statistic block and in the benchmark table delta column, so we
    // assert at least one match (getAllByText vs throwing single getByText).
    expect(screen.getAllByText(/\+52\.0%/).length).toBeGreaterThan(0);

    // LLM summary alert in success tone
    expect(screen.getByText(/csv-analyzer skill lifts pass_rate/i)).toBeInTheDocument();
    expect(screen.getByText(/skill adds measurable value/i)).toBeInTheDocument();

    // Benchmark table renders all 5 metric rows
    const table = screen.getByTestId('eval-benchmark-table');
    expect(table).toBeInTheDocument();
    expect(screen.getByText('Pass rate')).toBeInTheDocument();
    expect(screen.getByText('Composite score')).toBeInTheDocument();
    expect(screen.getByText('Overall score')).toBeInTheDocument();
    expect(screen.getByText('Avg latency')).toBeInTheDocument();
    expect(screen.getByText('Total cost')).toBeInTheDocument();

    // Verdict badge — passed (derived from result, no explicit draftStatus)
    const badge = screen.getByTestId('eval-status-badge');
    expect(badge).toHaveTextContent(/evaluated.*passed/i);

    // Drill-down button shows the count and is enabled
    const drillBtn = screen.getByTestId('eval-drilldown-btn');
    expect(drillBtn).toHaveTextContent(/View source sessions \(3\)/);
    expect(drillBtn).not.toBeDisabled();
  });

  it('renders warning alert + rejected badge when delta is below threshold', () => {
    const result = makeResult({
      delta: {
        compositeScore: -0.04,
        overallScore: -0.03,
        passRate: -0.02, // below +0.05 threshold → reject
        avgLatencyMs: 500,
        totalCostUsd: 0.0005,
      },
      llmSummary: 'New skill does not improve pass_rate; recommend reject.',
    });
    renderWithRouter(<SkillDraftEvaluationReport result={result} draftStatus="rejected" />);

    // Warning headline
    expect(screen.getByText(/did not clear the threshold/i)).toBeInTheDocument();
    expect(screen.getByText(/does not improve pass_rate/i)).toBeInTheDocument();

    // Rejected status badge
    const badge = screen.getByTestId('eval-status-badge');
    expect(badge).toHaveTextContent(/rejected/i);
  });

  it('drills down to /sessions?sessionIds=... on button click', () => {
    const result = makeResult({
      sourceSessionIds: ['sess-a', 'sess-b'],
    });
    renderWithRouter(<SkillDraftEvaluationReport result={result} />);

    const drillBtn = screen.getByTestId('eval-drilldown-btn');
    fireEvent.click(drillBtn);

    expect(navigateMock).toHaveBeenCalledTimes(1);
    const arg = navigateMock.mock.calls[0][0] as string;
    expect(arg).toContain('/sessions?sessionIds=');
    // Comma in `a,b` is URL-encoded by encodeURIComponent → %2C
    expect(arg).toMatch(/sess-a%2Csess-b/);
  });

  it('renders empty state when no result is provided', () => {
    renderWithRouter(<SkillDraftEvaluationReport result={undefined} />);
    expect(screen.getByTestId('eval-report-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('eval-benchmark-table')).not.toBeInTheDocument();
  });
});
