/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — unit coverage for the role-filter
 * Segmented control added to DatasetBrowser (tech-design §5.3).
 *
 * <p>Covers three concerns:
 *   1. role tab switch hides scenarios whose applicableAgentRoles doesn't
 *      intersect the picked role (e.g. picking "Design" hides general-only
 *      scenarios)
 *   2. multi-role scenarios (e.g. ['general','design']) survive both the
 *      "general" and "design" tabs (OR-semantics intersection)
 *   3. **defensive undefined guard**: scenarios with applicableAgentRoles
 *      undefined / null / missing safely return false (do not throw
 *      TypeError) under any non-'all' role filter — the optional-chain
 *      operator `?.includes(...)` is the guard
 *
 * <p>Mocks the api modules so the BaseScenario fetch resolves synchronously
 * with controlled fixtures.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { BaseScenario } from '../../../api';

// AntD Select / Tag jsdom polyfills.
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

vi.mock('../../../api', () => ({
  getBaseScenarios: vi.fn(),
  getEvalDatasetScenarios: vi.fn(() => Promise.resolve({ data: [] })),
}));

vi.mock('../../../api/evalDataset', () => ({
  listDatasets: vi.fn(() => Promise.resolve({ data: [] })),
  listVersions: vi.fn(() => Promise.resolve({ data: [] })),
  getDatasetVersion: vi.fn(() =>
    Promise.resolve({ data: { version: {}, scenarioIds: [], scenarios: [] } }),
  ),
}));

// Lazy children — stub so the test renders synchronously without dragging
// their internal queries / portals into the assertion graph.
vi.mock('../ScenarioDetailDrawer', () => ({
  default: () => null,
}));
vi.mock('../AnalyzeCaseModal', () => ({
  default: () => null,
}));
vi.mock('../AddBaseScenarioModal', () => ({
  default: () => null,
}));
vi.mock('../TraceImportSuggester', () => ({
  default: () => null,
}));

import DatasetBrowser from '../DatasetBrowser';
import { getBaseScenarios } from '../../../api';

function makeBaseScenario(
  id: string,
  name: string,
  // Use `string[] | null | undefined` so tests can exercise the missing /
  // null branch — matches the BE wire shape (Jackson may serialize null).
  applicableAgentRoles: string[] | null | undefined,
): BaseScenario {
  return {
    id,
    name,
    task: `Task ${id}`,
    sourceType: 'benchmark',
    source: 'classpath',
    applicableAgentRoles: applicableAgentRoles as BaseScenario['applicableAgentRoles'],
  };
}

function renderBrowser() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <DatasetBrowser agents={[]} userId={1} />
    </QueryClientProvider>,
  );
}

/**
 * Both segmented controls (source_type + role) carry buttons labelled "All",
 * so a global `getByRole('button', { name: 'All' })` would match multiple.
 * Scope via the `aria-label="Filter by agent role"` div, fetched by class
 * selector since AntD's segmented control is custom-rolled here (no
 * implicit role) and `getByLabelText` on a plain div+aria-label is
 * inconsistent across testing-library versions.
 */
function getRoleSegmentContainer(container: HTMLElement): HTMLElement {
  const el = container.querySelector(
    '[aria-label="Filter by agent role"]',
  ) as HTMLElement | null;
  if (!el) throw new Error('role segmented control not found in DOM');
  return el;
}

function clickRoleTab(container: HTMLElement, label: string) {
  const scope = getRoleSegmentContainer(container);
  const btn = within(scope).getByRole('button', { name: label });
  fireEvent.click(btn);
}

describe('DatasetBrowser — role filter Segmented (FLYWHEEL-AB-AGENT-AWARE-DATASET V1)', () => {
  beforeEach(() => {
    vi.mocked(getBaseScenarios).mockReset();
  });

  it('renders all 6 role tabs (All / General / Design / Code / Research / Main Assistant)', async () => {
    vi.mocked(getBaseScenarios).mockResolvedValueOnce({ data: [] });
    const { container } = renderBrowser();
    // Wait for the role-filter container itself to mount (proxy for toolbar
    // having rendered without coupling to a specific scenario card).
    await waitFor(() => {
      expect(
        container.querySelector('[aria-label="Filter by agent role"]'),
      ).not.toBeNull();
    });
    const scope = getRoleSegmentContainer(container);
    expect(within(scope).getByRole('button', { name: 'All' })).toBeInTheDocument();
    expect(within(scope).getByRole('button', { name: 'General' })).toBeInTheDocument();
    expect(within(scope).getByRole('button', { name: 'Design' })).toBeInTheDocument();
    expect(within(scope).getByRole('button', { name: 'Code' })).toBeInTheDocument();
    expect(within(scope).getByRole('button', { name: 'Research' })).toBeInTheDocument();
    expect(within(scope).getByRole('button', { name: 'Main Assistant' })).toBeInTheDocument();
  });

  it('All tab shows every scenario; Design tab hides general-only ones; multi-role survives both', async () => {
    const data: BaseScenario[] = [
      makeBaseScenario('s-gen', 'General-only scenario', ['general']),
      makeBaseScenario('s-design', 'Design-only scenario', ['design']),
      makeBaseScenario('s-both', 'General-and-design scenario', ['general', 'design']),
    ];
    vi.mocked(getBaseScenarios).mockResolvedValueOnce({ data });
    const { container } = renderBrowser();

    // All tab — see all three card names.
    await waitFor(() => {
      expect(screen.getByText('General-only scenario')).toBeInTheDocument();
    });
    expect(screen.getByText('Design-only scenario')).toBeInTheDocument();
    expect(screen.getByText('General-and-design scenario')).toBeInTheDocument();

    // Click Design — general-only should disappear.
    clickRoleTab(container, 'Design');
    await waitFor(() => {
      expect(screen.queryByText('General-only scenario')).not.toBeInTheDocument();
    });
    expect(screen.getByText('Design-only scenario')).toBeInTheDocument();
    expect(screen.getByText('General-and-design scenario')).toBeInTheDocument();

    // Click General — design-only should disappear; general-only + multi
    // should appear.
    clickRoleTab(container, 'General');
    await waitFor(() => {
      expect(screen.queryByText('Design-only scenario')).not.toBeInTheDocument();
    });
    expect(screen.getByText('General-only scenario')).toBeInTheDocument();
    expect(screen.getByText('General-and-design scenario')).toBeInTheDocument();
  });

  it('defensive guard: scenarios with applicableAgentRoles undefined / null safely filter out under non-All tabs (no TypeError)', async () => {
    const data: BaseScenario[] = [
      makeBaseScenario('s-undef', 'No-role-field scenario', undefined),
      makeBaseScenario('s-null', 'Null-role-field scenario', null),
      makeBaseScenario('s-design', 'Design scenario', ['design']),
    ];
    vi.mocked(getBaseScenarios).mockResolvedValueOnce({ data });
    const { container } = renderBrowser();

    // All tab — undefined / null rows still render (filter is all).
    await waitFor(() => {
      expect(screen.getByText('No-role-field scenario')).toBeInTheDocument();
    });
    expect(screen.getByText('Null-role-field scenario')).toBeInTheDocument();
    expect(screen.getByText('Design scenario')).toBeInTheDocument();

    // Click Design — both undefined and null rows must DISAPPEAR cleanly
    // (no thrown TypeError about calling .includes on undefined).
    clickRoleTab(container, 'Design');
    await waitFor(() => {
      expect(screen.queryByText('No-role-field scenario')).not.toBeInTheDocument();
    });
    expect(screen.queryByText('Null-role-field scenario')).not.toBeInTheDocument();
    expect(screen.getByText('Design scenario')).toBeInTheDocument();
  });

  it('main_assistant tab uses snake_case wire id (label "Main Assistant" → filter "main_assistant")', async () => {
    const data: BaseScenario[] = [
      makeBaseScenario('s-ma', 'Main Assistant scenario', ['main_assistant']),
      makeBaseScenario('s-code', 'Code scenario', ['code']),
    ];
    vi.mocked(getBaseScenarios).mockResolvedValueOnce({ data });
    const { container } = renderBrowser();

    await waitFor(() => {
      expect(screen.getByText('Main Assistant scenario')).toBeInTheDocument();
    });

    clickRoleTab(container, 'Main Assistant');
    await waitFor(() => {
      expect(screen.queryByText('Code scenario')).not.toBeInTheDocument();
    });
    expect(screen.getByText('Main Assistant scenario')).toBeInTheDocument();
  });

  it('filtered count summary updates as role tab changes (X of N)', async () => {
    const data: BaseScenario[] = [
      makeBaseScenario('s-gen', 'gen-row', ['general']),
      makeBaseScenario('s-design', 'design-row', ['design']),
    ];
    vi.mocked(getBaseScenarios).mockResolvedValueOnce({ data });
    const { container } = renderBrowser();

    await waitFor(() => {
      // Match the summary span exactly: "2 of 2 cases"
      expect(within(container).getByText(/2 of 2 cases/)).toBeInTheDocument();
    });

    clickRoleTab(container, 'Design');
    await waitFor(() => {
      expect(within(container).getByText(/1 of 2 cases/)).toBeInTheDocument();
    });
  });
});
