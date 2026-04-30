/**
 * P1-D T9 — SkillDrawer governance UI:
 *
 *   1) `isSystem=true` skills must surface the Delete button as disabled with
 *      a Tooltip whose body reads "System skill 不可删除". The backend
 *      enforces the same rule (T7 fail-secure 403); the FE disable is the
 *      first line of defence.
 *   2) `isSystem=false` skills retain the existing Disable + Delete actions
 *      (regression guard: we did not accidentally hide them on the happy
 *      path while wiring up the system-skill branch).
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { SkillDrawer } from '../SkillDrawer';
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

// Mock the api module so the drawer's useQuery + the optional A/B + Evolution
// panels do not hit the network. Empty payloads are fine — the assertions
// here cover only the Delete-button branch in the drawer header.
vi.mock('../../../api', () => ({
  getSkillDetail: vi.fn(() => Promise.resolve({ data: {} })),
  getSkillAbTests: vi.fn(() => Promise.resolve({ data: [] })),
  getSkillEvolutions: vi.fn(() => Promise.resolve({ data: [] })),
  forkSkill: vi.fn(() => Promise.resolve({ data: {} })),
  startSkillAbTest: vi.fn(() => Promise.resolve({ data: {} })),
  startSkillEvolution: vi.fn(() => Promise.resolve({ data: {} })),
}));

// AuthContext is consumed transitively through SkillAbPanel.
vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

function makeRow(overrides: Partial<SkillRow>): SkillRow {
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

function renderDrawer(skill: SkillRow) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  const onClose = vi.fn();
  const onToggle = vi.fn();
  const onDelete = vi.fn();
  const setTab = vi.fn();
  const utils = render(
    <QueryClientProvider client={client}>
      <SkillDrawer
        skill={skill}
        tab="readme"
        setTab={setTab}
        onClose={onClose}
        onToggle={onToggle}
        onDelete={onDelete}
        currentUserId={1}
        sourceAgentId={null}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onDelete, onToggle, onClose };
}

describe('SkillDrawer — system skill governance (P1-D T9)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders Delete as disabled for isSystem=true skills and exposes the tooltip text', async () => {
    const sysRow = makeRow({
      id: 42,
      name: 'system-skill',
      isSystem: true,
      system: true,
      source: 'system',
      type: 'system',
      originSource: 'filesystem',
    });
    const { onDelete } = renderDrawer(sysRow);

    // The disabled Delete button is wrapped in a span (AntD Tooltip footgun
    // workaround) and tagged with data-testid="system-delete-disabled".
    const deleteBtn = await screen.findByTestId('system-delete-disabled');
    expect(deleteBtn).toBeDisabled();
    expect(deleteBtn).toHaveAttribute('aria-disabled', 'true');

    // Tooltip text must render with the exact Chinese copy required by plan.
    // We mount the tooltip via fireEvent.mouseOver on the wrapping span — AntD
    // attaches its mouseenter listener there to dodge the disabled-button bug.
    const wrapper = deleteBtn.parentElement!;
    fireEvent.mouseOver(wrapper);
    await waitFor(() => {
      expect(screen.getByText('System skill 不可删除')).toBeInTheDocument();
    });

    // Even if a click somehow fires, onDelete must NOT be invoked. The
    // wrapping span has pointer-events:none on the inner button, so jsdom
    // clicks land on the disabled button which never propagates.
    fireEvent.click(deleteBtn);
    expect(onDelete).not.toHaveBeenCalled();
  });

  it('renders Delete + Disable as enabled for isSystem=false skills (regression guard)', async () => {
    const customRow = makeRow({
      id: 7,
      name: 'custom-skill',
      isSystem: false,
      system: false,
      source: 'custom',
      type: 'runtime',
      originSource: 'upload',
    });
    const { onDelete, onToggle } = renderDrawer(customRow);

    // The disabled-system testid must NOT exist on a non-system skill.
    expect(screen.queryByTestId('system-delete-disabled')).not.toBeInTheDocument();

    const deleteBtn = await screen.findByRole('button', { name: /^delete$/i });
    expect(deleteBtn).not.toBeDisabled();
    fireEvent.click(deleteBtn);
    expect(onDelete).toHaveBeenCalledWith(7);

    const toggleBtn = screen.getByRole('button', { name: /disable/i });
    fireEvent.click(toggleBtn);
    expect(onToggle).toHaveBeenCalledWith(7, false);
  });
});
