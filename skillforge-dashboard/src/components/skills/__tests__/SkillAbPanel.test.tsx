/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.5 — SkillAbPanel canary removal regression.
 *
 * Asserts that the embedded CanaryPanel (SKILL-CANARY-ROLLOUT V2 Phase 1.5)
 * is no longer rendered by SkillAbPanel after the dogfood single-user phase
 * disable. The CanaryPanel's distinctive header ("Canary Rollout") and its
 * lifecycle action labels ("Start Canary", "Step Up", "Publish", "Rollback")
 * must NOT appear in the rendered tree.
 *
 * If the CanaryPanel embed is restored in the future for multi-user canary
 * re-enable, this test should be deleted (not adjusted) — it exists solely
 * to lock in the disable.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { SkillAbPanel } from '../SkillAbPanel';
import type { SkillRow } from '../types';

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

// Empty payloads keep the panel in its "no runs yet" branch, which is enough
// to verify the canary subpanel is not rendered. Even if `getSkillAbTests`
// returned a run with `candidateSkillId`, the removed canary lookup path
// would not fire — but we keep mocks minimal.
vi.mock('../../../api', () => ({
  getSkillAbTests: vi.fn(() => Promise.resolve({ data: [] })),
  forkSkill: vi.fn(() => Promise.resolve({ data: {} })),
  startSkillAbTest: vi.fn(() => Promise.resolve({ data: {} })),
  manualPromoteAbRun: vi.fn(() => Promise.resolve({ data: {} })),
  rollbackSkill: vi.fn(() => Promise.resolve({ data: {} })),
}));

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

function makeRow(overrides: Partial<SkillRow> = {}): SkillRow {
  return {
    id: 1,
    name: 'sample-skill',
    description: 'desc',
    source: 'custom',
    lang: 'md',
    enabled: true,
    system: false,
    isSystem: false,
    artifactStatus: 'active',
    type: 'runtime',
    tags: [],
    ...overrides,
  };
}

function renderPanel(skill: SkillRow, agentId: number | null = 7) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <SkillAbPanel skillId={skill.id as number} agentId={agentId} skill={skill} />
    </QueryClientProvider>,
  );
}

describe('SkillAbPanel — canary panel removal (FLYWHEEL-LOOP-CLOSURE Phase 1.5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not render the embedded CanaryPanel', () => {
    renderPanel(makeRow());

    // CanaryPanel renders a "Canary Rollout" section header (CanaryPanel.tsx
    // line 567). Its absence is the canonical signal that the embed is gone.
    expect(screen.queryByText(/canary rollout/i)).not.toBeInTheDocument();

    // Belt-and-suspenders: none of the canary lifecycle button labels should
    // render either.
    expect(screen.queryByText(/start canary/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/step up/i)).not.toBeInTheDocument();
  });

  it('still renders the A/B Testing header (main panel preserved)', () => {
    renderPanel(makeRow());
    expect(screen.getByText('A/B Testing')).toBeInTheDocument();
  });
});
