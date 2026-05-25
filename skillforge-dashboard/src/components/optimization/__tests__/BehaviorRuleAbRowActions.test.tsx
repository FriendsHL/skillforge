/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — unit coverage for the ownerAgentRole
 * Tag render branch added to BehaviorRuleAbRowActions (tech-design §5.2).
 *
 * <p>Two render branches under test:
 *   1. {@code ownerAgentRole='design'} → magenta Tag with "Design" label
 *      precedes the BehaviorRuleAbBadge
 *   2. {@code ownerAgentRole=null}     → NO Tag is rendered (defensive guard:
 *      legacy rows pre-V1.1 should not show a placeholder)
 *
 * <p>Mocks the `behaviorRuleApi.latestAbRun` HTTP call so the react-query
 * hook resolves synchronously with the canned run shape.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// AntD Tooltip needs matchMedia + ResizeObserver in jsdom — mirror the
// polyfill block from BehaviorRuleAbBadge.test.tsx so the Modal/Tag mounts
// without warnings.
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
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;

// Mock the api/behaviorRule module — replace the network calls but keep the
// real helper / type exports so the production component still gets the
// genuine roleLabel / roleColor.
vi.mock('../../../api/behaviorRule', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/behaviorRule')>();
  return {
    ...actual,
    behaviorRuleApi: {
      latestAbRun: vi.fn(),
      runAb: vi.fn(),
      promote: vi.fn(),
    },
  };
});

import { BehaviorRuleAbRowActions } from '../BehaviorRuleAbRowActions';
import { behaviorRuleApi, type BehaviorRuleAbRun } from '../../../api/behaviorRule';

function makeRun(overrides: Partial<BehaviorRuleAbRun> = {}): BehaviorRuleAbRun {
  return {
    id: 'ab-test-id',
    agentId: '1',
    candidateVersionId: 'v-1',
    status: 'COMPLETED',
    abRunKind: 'with_vs_without',
    baselinePassRate: 0.4,
    candidatePassRate: 0.55,
    deltaPassRate: 0.15,
    targetDeltaPp: 15,
    regressionDeltaPp: -1,
    targetCount: 5,
    regressionCount: 44,
    datasetVersionId: 'ds-v1',
    promoted: false,
    failureReason: null,
    startedAt: '2026-05-24T00:00:00Z',
    completedAt: '2026-05-24T00:05:00Z',
    dualCriteriaSatisfied: true,
    scenarioResults: null,
    ownerAgentRole: null,
    ...overrides,
  };
}

function renderRowActions(versionId = 'v-1') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <BehaviorRuleAbRowActions versionId={versionId} onOpenDetail={() => {}} />
    </QueryClientProvider>,
  );
}

describe('BehaviorRuleAbRowActions — ownerAgentRole Tag (FLYWHEEL-AB-AGENT-AWARE-DATASET V1)', () => {
  beforeEach(() => {
    vi.mocked(behaviorRuleApi.latestAbRun).mockReset();
  });

  it('renders a magenta "Design" Tag when ownerAgentRole="design"', async () => {
    vi.mocked(behaviorRuleApi.latestAbRun).mockResolvedValueOnce({
      data: makeRun({ ownerAgentRole: 'design' }),
    } as Awaited<ReturnType<typeof behaviorRuleApi.latestAbRun>>);

    renderRowActions();

    // Wait for the query to resolve and the Tag to mount.
    const tag = await screen.findByText('Design');
    expect(tag).toBeInTheDocument();
    // AntD applies preset color via a class — assert it ends up magenta
    // per tech-design §5.2 (design → magenta).
    // Closest ancestor with the `ant-tag` class will carry the color class.
    const tagEl = tag.closest('.ant-tag');
    expect(tagEl?.className).toMatch(/magenta/);
  });

  it('renders a blue "Main Assistant" Tag with snake_case → label mapping', async () => {
    vi.mocked(behaviorRuleApi.latestAbRun).mockResolvedValueOnce({
      data: makeRun({ ownerAgentRole: 'main_assistant' }),
    } as Awaited<ReturnType<typeof behaviorRuleApi.latestAbRun>>);

    renderRowActions();

    const tag = await screen.findByText('Main Assistant');
    expect(tag).toBeInTheDocument();
    const tagEl = tag.closest('.ant-tag');
    expect(tagEl?.className).toMatch(/blue/);
  });

  it('does NOT render any role Tag when ownerAgentRole=null (legacy row)', async () => {
    vi.mocked(behaviorRuleApi.latestAbRun).mockResolvedValueOnce({
      data: makeRun({ ownerAgentRole: null }),
    } as Awaited<ReturnType<typeof behaviorRuleApi.latestAbRun>>);

    renderRowActions();

    // The Badge still mounts — wait for any post-query render to settle by
    // looking for the Promote button (dualCriteriaSatisfied=true above).
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Promote v1/i })).toBeInTheDocument();
    });
    // None of the 5 role labels should appear in the DOM.
    expect(screen.queryByText('Design')).not.toBeInTheDocument();
    expect(screen.queryByText('Code')).not.toBeInTheDocument();
    expect(screen.queryByText('Research')).not.toBeInTheDocument();
    expect(screen.queryByText('Main Assistant')).not.toBeInTheDocument();
    expect(screen.queryByText('General')).not.toBeInTheDocument();
  });

  it('does NOT render any role Tag when latestAbRun resolves to null (no-run-yet)', async () => {
    vi.mocked(behaviorRuleApi.latestAbRun).mockResolvedValueOnce({
      data: null,
    } as Awaited<ReturnType<typeof behaviorRuleApi.latestAbRun>>);

    renderRowActions();

    // Wait for the no-run-yet Retry button to mount so the query has resolved.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Retry A\/B/i })).toBeInTheDocument();
    });
    // No role chip should appear.
    expect(screen.queryByText('Design')).not.toBeInTheDocument();
    expect(screen.queryByText('General')).not.toBeInTheDocument();
  });
});
