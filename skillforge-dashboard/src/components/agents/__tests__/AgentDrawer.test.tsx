/**
 * Component tests for the P13-2 AgentDrawer Hooks tab rewrite.
 *
 * Mocks the `api` module so the tree can render without real network calls.
 * We only assert observable DOM + `message` side-effects; we deliberately do
 * not import private factories from FormMode.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import type { AgentDto } from '../../../api/schemas';

// jsdom lacks ResizeObserver; antd Radio.Group / Tooltip / Select depend on
// it for overlay positioning. Polyfill with a no-op class — tests only assert
// text content, not actual geometry, so empty implementations are safe.
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
// Double cast needed because the global interface expects the real browser
// ResizeObserver constructor signature; we only need the shape antd touches.
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;

// jsdom lacks matchMedia too (used by antd theme detection).
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

// Stable mock for the api module. updateAgent resolves to a minimal response.
vi.mock('../../../api', () => {
  return {
    updateAgent: vi.fn(() => Promise.resolve({ data: {} })),
    getTools: vi.fn(() => Promise.resolve({ data: [] })),
    getSkills: vi.fn(() =>
      Promise.resolve({
        data: [
          { name: 'greeter', description: 'Say hi' },
          { name: 'audit', description: 'Audit events' },
        ],
      }),
    ),
    getLifecycleHookEvents: vi.fn(() => Promise.resolve({ data: [] })),
    getLifecycleHookPresets: vi.fn(() => Promise.resolve({ data: [] })),
    getLifecycleHookMethods: vi.fn(() => Promise.resolve({ data: [] })),
    dryRunHook: vi.fn(() => Promise.resolve({ data: {} })),
    extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
      if (Array.isArray(res.data)) return res.data;
      const items = (res.data as { items?: T[] }).items;
      return Array.isArray(items) ? items : [];
    },
  };
});

// Mock the Ant Design message to spy on warning calls without real DOM toasts.
const warningSpy = vi.fn();
vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      ...actual.message,
      warning: (...args: unknown[]) => warningSpy(...args),
      success: vi.fn(),
      error: vi.fn(),
      info: vi.fn(),
    },
  };
});

import AgentDrawer from '../AgentDrawer';
import { updateAgent } from '../../../api';

function makeAgent(overrides: Partial<AgentDto> = {}): AgentDto {
  return {
    id: 1,
    name: 'Test agent',
    description: 'desc',
    systemPrompt: '',
    soulPrompt: '',
    modelId: 'openai:gpt-4o',
    behaviorRules: null,
    lifecycleHooks: null,
    skillIds: null,
    toolIds: null,
    ...overrides,
  } as AgentDto;
}

function renderDrawer(agent: AgentDto) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  const utils = render(
    <QueryClientProvider client={client}>
      <AgentDrawer agent={agent} onClose={() => {}} />
    </QueryClientProvider>,
  );
  // Expose a typed helper for rerender so tests can swap the agent prop without
  // remounting the provider (simulates AgentList's `setOpenAgent(newAgent)`).
  const rerenderWithAgent = (nextAgent: AgentDto) =>
    utils.rerender(
      <QueryClientProvider client={client}>
        <AgentDrawer agent={nextAgent} onClose={() => {}} />
      </QueryClientProvider>,
    );
  return { ...utils, rerenderWithAgent };
}

async function gotoHooksTab() {
  const tab = await screen.findByRole('button', { name: /Hooks/i });
  fireEvent.click(tab);
}

describe('AgentDrawer Hooks tab (P13-2)', () => {
  beforeEach(() => {
    warningSpy.mockClear();
    vi.mocked(updateAgent).mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders with null lifecycleHooks and Save is disabled (Saved state)', async () => {
    renderDrawer(makeAgent({ lifecycleHooks: null }));
    await gotoHooksTab();
    // Wait for the editor's first onRawJsonChange emission to latch baseline.
    await waitFor(() => {
      const saveBtn = screen.getByRole('button', { name: /^Saved$/ });
      expect(saveBtn).toBeDisabled();
    });
    // No migration warning toast should fire for null input.
    expect(warningSpy).not.toHaveBeenCalled();
  });

  it('fires migration warning for legacy flat hooks with mixed migratable + dropped entries', async () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 's1', type: 'skill' },
      { event: 'PostToolUse', name: 'log.file', type: 'method' },
      { event: 'SessionEnd', name: 's3', type: 'skill' },
      { event: 'PreToolUse', name: 'unknown-evt', type: 'skill' },
      { event: 'SessionStart', name: 'scriptHere', type: 'script' },
    ]);
    renderDrawer(makeAgent({ lifecycleHooks: legacy }));
    await gotoHooksTab();

    await waitFor(() => {
      expect(warningSpy).toHaveBeenCalledTimes(1);
    });
    const msg = warningSpy.mock.calls[0][0] as string;
    expect(msg).toMatch(/Migrated 3/);
    expect(msg).toMatch(/dropped 2/);

    // After baseline latch, Save is Saved/disabled.
    await waitFor(() => {
      const saveBtn = screen.getByRole('button', { name: /^Saved$/ });
      expect(saveBtn).toBeDisabled();
    });
  });

  it('does not fire migration warning for already-canonical hooks', async () => {
    const canonical = JSON.stringify({
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'greeter' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
      },
    });
    renderDrawer(makeAgent({ lifecycleHooks: canonical }));
    await gotoHooksTab();

    // Allow the editor to mount and latch.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeInTheDocument();
    });
    expect(warningSpy).not.toHaveBeenCalled();
  });

  it('JSON mode validation errors switch Save button to "Fix JSON first" and keep it disabled', async () => {
    renderDrawer(makeAgent({ lifecycleHooks: null }));
    await gotoHooksTab();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeInTheDocument();
    });

    // Click the JSON mode radio button.
    const jsonBtn = screen.getByRole('radio', { name: /JSON/i });
    await act(async () => {
      fireEvent.click(jsonBtn);
    });

    // Locate the editable JSON textarea and inject bad JSON.
    const textarea = document.querySelector(
      'textarea',
    ) as HTMLTextAreaElement | null;
    expect(textarea).not.toBeNull();
    await act(async () => {
      fireEvent.change(textarea!, { target: { value: '{ not valid json' } });
    });

    await waitFor(() => {
      const btn = screen.getByRole('button', { name: /Fix JSON first/i });
      expect(btn).toBeDisabled();
    });
    expect(updateAgent).not.toHaveBeenCalled();
  });

  it('Form-mode change flips dirty and enables Save (toggle populated event OFF)', async () => {
    // Start with a valid canonical payload that has one SessionStart entry.
    // In form mode, the event card's Switch is ON; clicking it removes the
    // entry (onEntriesChange([])), which is still a valid empty-hooks config.
    // That path exercises Form → liveRawJson → dirty → Save enabled WITHOUT
    // depending on AntD Select interaction (which is brittle in jsdom).
    const canonical = JSON.stringify({
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'greeter' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
      },
    });
    renderDrawer(makeAgent({ lifecycleHooks: canonical }));
    await gotoHooksTab();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeDisabled();
    });

    // Each event card renders an ON/OFF AntD Switch. The first ON switch
    // corresponds to SessionStart (the only populated event). Click it to
    // remove the entry.
    const switches = screen.getAllByRole('switch');
    const onSwitch = switches.find((s) => s.getAttribute('aria-checked') === 'true');
    expect(onSwitch).toBeDefined();
    await act(async () => {
      fireEvent.click(onSwitch!);
    });

    // Form change bubbled → liveRawJson diverged from baseline → Save is 'Save'
    // and enabled (no Zod errors because empty hooks are valid).
    // Bumped timeout to 3s because jsdom + antd Switch commit can run slow on
    // first-cold CI runs (occasional flake at default 1000ms).
    await waitFor(
      () => {
        const saveBtn = screen.getByRole('button', { name: /^Save$/ });
        expect(saveBtn).toBeEnabled();
      },
      { timeout: 3000 },
    );
  });

  it('Save sends current liveRawJson to updateAgent and re-latches baseline on success', async () => {
    renderDrawer(makeAgent({ lifecycleHooks: null }));
    await gotoHooksTab();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeInTheDocument();
    });

    // Enter JSON mode and write a new canonical payload.
    fireEvent.click(screen.getByRole('radio', { name: /JSON/i }));
    const textarea = document.querySelector(
      'textarea',
    ) as HTMLTextAreaElement | null;
    expect(textarea).not.toBeNull();
    const newJson = JSON.stringify(
      {
        version: 1,
        hooks: {
          SessionStart: [
            {
              handler: { type: 'skill', skillName: 'greeter' },
              timeoutSeconds: 30,
              failurePolicy: 'CONTINUE',
              async: false,
            },
          ],
        },
      },
      null,
      2,
    );
    await act(async () => {
      fireEvent.change(textarea!, { target: { value: newJson } });
    });

    // Save button enables → click.
    const saveBtn = await screen.findByRole('button', { name: /^Save$/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await act(async () => {
      fireEvent.click(saveBtn);
    });

    // Assert updateAgent called with the exact rawJson as payload.lifecycleHooks.
    await waitFor(() => {
      expect(vi.mocked(updateAgent)).toHaveBeenCalled();
    });
    const lastCall = vi.mocked(updateAgent).mock.calls.at(-1)!;
    const [agentId, payload] = lastCall;
    expect(agentId).toBe(1);
    expect(payload.lifecycleHooks).toBe(newJson);

    // After onSuccess fires the baseline re-latches; Save returns to 'Saved'.
    await waitFor(() => {
      const btn = screen.getByRole('button', { name: /^Saved$/ });
      expect(btn).toBeDisabled();
    });
  });

  it('switching to a different agent re-latches baseline and clears previous dirty state', async () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 's1', type: 'skill' },
    ]);
    const { rerenderWithAgent } = renderDrawer(
      makeAgent({ id: 1, lifecycleHooks: legacy }),
    );
    await gotoHooksTab();
    await waitFor(() => {
      expect(warningSpy).toHaveBeenCalledTimes(1);
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeDisabled();
    });

    // Swap to a different agent whose hooks are null — drawer re-points but
    // does not unmount, exercising the render-phase state reset.
    warningSpy.mockClear();
    rerenderWithAgent(makeAgent({ id: 2, lifecycleHooks: null }));

    // Give the editor a moment to remount under key={agent.id} and re-emit.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeDisabled();
    });
    // No migration toast should fire for the new null agent.
    expect(warningSpy).not.toHaveBeenCalled();
  });

  it('Revert restores baseline and re-disables Save', async () => {
    const canonical = JSON.stringify({
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'greeter' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
      },
    });
    renderDrawer(makeAgent({ lifecycleHooks: canonical }));
    await gotoHooksTab();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Saved$/ })).toBeInTheDocument();
    });

    // Switch to JSON mode and make a benign edit that still parses so we can
    // verify Revert; we inject valid but different canonical JSON.
    fireEvent.click(screen.getByRole('radio', { name: /JSON/i }));
    const textarea = document.querySelector(
      'textarea',
    ) as HTMLTextAreaElement | null;
    expect(textarea).not.toBeNull();
    const edited = JSON.stringify(
      {
        version: 1,
        hooks: {
          SessionStart: [
            {
              handler: { type: 'skill', skillName: 'audit' },
              timeoutSeconds: 30,
              failurePolicy: 'CONTINUE',
              async: false,
            },
          ],
        },
      },
      null,
      2,
    );
    await act(async () => {
      fireEvent.change(textarea!, { target: { value: edited } });
    });

    // Save should now be enabled & show 'Save'.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Save$/ })).toBeEnabled();
    });

    // Revert.
    fireEvent.click(screen.getByRole('button', { name: /^Revert$/ }));
    await waitFor(() => {
      const btn = screen.getByRole('button', { name: /^Saved$/ });
      expect(btn).toBeDisabled();
    });
  });
});
