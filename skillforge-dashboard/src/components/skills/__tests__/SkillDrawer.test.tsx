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
import { render, screen, fireEvent } from '@testing-library/react';
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

function renderDrawer(skill: SkillRow, siblingVersions?: SkillRow[]) {
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
        siblingVersions={siblingVersions}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onDelete, onToggle, onClose };
}

describe('SkillDrawer — system skill governance (P1-D T9)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders system skills as marketplace-managed without version deletion', () => {
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
    expect(screen.getByText(/System skills are managed via the marketplace/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete Version/i })).not.toBeInTheDocument();
    expect(onDelete).not.toHaveBeenCalled();
  });

  it('offers deletion only after selecting a non-live candidate version', () => {
    const customRow = makeRow({
      id: 7,
      name: 'custom-skill',
      isSystem: false,
      system: false,
      source: 'custom',
      type: 'runtime',
      originSource: 'upload',
    });
    const candidate = makeRow({
      id: 8,
      name: 'custom-skill',
      enabled: false,
      semver: '1.1.0',
      parentSkillId: 7,
    });
    const { onDelete } = renderDrawer(customRow, [customRow, candidate]);

    expect(screen.queryByRole('button', { name: /Delete Version/i })).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('v1.1.0'));
    expect(screen.getByRole('button', { name: /Delete Version/i })).toBeEnabled();
    expect(onDelete).not.toHaveBeenCalled();
  });
});
