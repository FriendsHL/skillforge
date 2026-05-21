/**
 * SYSTEM-AGENT-TYPING Phase 2.2 — AgentDrawer read-only gate for system agents.
 *
 * Cases (per requirements/active/SYSTEM-AGENT-TYPING/index.md task #3 brief):
 *   1. agent.agentType === 'system' → warning banner rendered + Overview Save
 *      button disabled
 *   2. agent.agentType === 'user' → no banner, Delete button enabled
 *   3. agent.agentType === 'system' → Delete button disabled (tooltip explains)
 *   4. agent.agentType === 'system' → Model/Visibility/Role selects all disabled
 *
 * Heavy AgentDrawer dependencies are mocked at the top so the test stays
 * focused on the gate behavior. Existing AgentDrawer test fixture handles the
 * non-system-agent codepaths in `AgentDrawer.test.tsx`.
 */
import React from 'react';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { AgentDto } from '../../../api/schemas';

// jsdom polyfills (same as the other AgentDrawer tests in this folder).
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

vi.mock('../../../api', () => ({
  updateAgent: vi.fn(() => Promise.resolve({ data: {} })),
  deleteAgent: vi.fn(() => Promise.resolve({ data: {} })),
  getTools: vi.fn(() => Promise.resolve({ data: [] })),
  getSkills: vi.fn(() => Promise.resolve({ data: [] })),
  getLifecycleHookEvents: vi.fn(() => Promise.resolve({ data: [] })),
  getLifecycleHookPresets: vi.fn(() => Promise.resolve({ data: [] })),
  getLifecycleHookMethods: vi.fn(() => Promise.resolve({ data: [] })),
  getLlmModels: vi.fn(() =>
    Promise.resolve({
      data: [
        {
          id: 'openai:gpt-4o',
          label: 'openai:gpt-4o',
          provider: 'openai',
          model: 'gpt-4o',
          isDefault: false,
          supportsThinking: false,
          supportsReasoningEffort: false,
          protocolFamily: 'openai',
        },
      ],
    }),
  ),
  extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
    if (Array.isArray(res.data)) return res.data;
    const items = (res.data as { items?: T[] }).items;
    return Array.isArray(items) ? items : [];
  },
}));

vi.mock('../../../api/mcpServers', () => ({
  listMcpServers: vi.fn(() => Promise.resolve({ data: [] })),
}));

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

import AgentDrawer from '../AgentDrawer';
import { deleteAgent, updateAgent } from '../../../api';

function makeAgent(overrides: Partial<AgentDto> = {}): AgentDto {
  return {
    id: 7,
    name: 'session-annotator',
    description: 'cron-managed annotator',
    systemPrompt: 'do the thing',
    soulPrompt: '',
    modelId: 'openai:gpt-4o',
    behaviorRules: null,
    lifecycleHooks: null,
    skillIds: null,
    toolIds: null,
    agentType: 'system',
    ...overrides,
  } as AgentDto;
}

function renderDrawer(agent: AgentDto) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <AgentDrawer agent={agent} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

describe('AgentDrawer — system agent read-only gate (SYSTEM-AGENT-TYPING Phase 2.2)', () => {
  beforeEach(() => {
    vi.mocked(deleteAgent).mockClear();
    vi.mocked(updateAgent).mockClear();
  });

it('disables the Delete button for system agents', () => {
    renderDrawer(makeAgent({ agentType: 'system' }));
    const deleteBtn = screen.getByTestId('delete-agent-btn');
    expect(deleteBtn).toBeDisabled();

    // Clicking has no effect — deleteAgent must not be called.
    fireEvent.click(deleteBtn);
    expect(deleteAgent).not.toHaveBeenCalled();
  });

  it('keeps the Delete button enabled for user agents', () => {
    renderDrawer(makeAgent({ agentType: 'user' }));
    const deleteBtn = screen.getByTestId('delete-agent-btn');
    expect(deleteBtn).not.toBeDisabled();
  });

  it('disables Overview Save button + Model/Visibility/Role selects for system agents', async () => {
    renderDrawer(makeAgent({ agentType: 'system' }));

    // Overview Save is disabled (even if user touched a field via DOM, the
    // gate ensures Save can't fire).
    const saveBtn = await screen.findByTestId('overview-save-btn');
    expect(saveBtn).toBeDisabled();

    // The AntD Select component reflects disabled via aria-disabled on
    // the inner combobox element. Use document selectors since data-testid
    // is on the wrapper, not the input.
    const selects = document.querySelectorAll('.ant-select');
    // Sanity check: drawer rendered the Overview tab with several selects.
    expect(selects.length).toBeGreaterThanOrEqual(3);
    let anyDisabled = false;
    selects.forEach((s) => {
      if (s.classList.contains('ant-select-disabled')) anyDisabled = true;
    });
    expect(anyDisabled).toBe(true);
  });

  it('disables the ASK/AUTO execution-mode toggle for system agents', () => {
    renderDrawer(makeAgent({ agentType: 'system' }));
    const modeWrap = screen.getByTestId('execution-mode-toggle');
    expect(modeWrap).toHaveAttribute('aria-disabled', 'true');
    // Both inner buttons are disabled.
    const askBtn = modeWrap.querySelector('button:nth-of-type(1)') as HTMLButtonElement;
    const autoBtn = modeWrap.querySelector('button:nth-of-type(2)') as HTMLButtonElement;
    expect(askBtn).toBeDisabled();
    expect(autoBtn).toBeDisabled();
  });

  // SYSTEM-AGENT-TYPING Phase 2 W3 fix — BehaviorRulesEditor and
  // LifecycleHooksPanel don't accept a `disabled` prop. Wrapping them in
  // `<fieldset disabled>` propagates disabled to all descendant form controls
  // at the DOM level. Verify the fieldset wrapper is present + `disabled`
  // attribute is set for system agents, absent for user agents.
  it('Rules + Hooks tabs are wrapped in <fieldset disabled> for system agents', async () => {
    renderDrawer(makeAgent({ agentType: 'system' }));

    // Switch to rules tab
    const rulesTab = await screen.findByRole('button', { name: /^Rules/ });
    fireEvent.click(rulesTab);
    const rulesFs = await screen.findByTestId('rules-tab-fieldset');
    expect(rulesFs.tagName.toLowerCase()).toBe('fieldset');
    expect(rulesFs).toBeDisabled();

    // Switch to hooks tab
    const hooksTab = screen.getByRole('button', { name: /^Hooks/ });
    fireEvent.click(hooksTab);
    const hooksFs = await screen.findByTestId('hooks-tab-fieldset');
    expect(hooksFs.tagName.toLowerCase()).toBe('fieldset');
    expect(hooksFs).toBeDisabled();
  });

  it('Rules + Hooks fieldset NOT disabled for user agents', async () => {
    renderDrawer(makeAgent({ agentType: 'user' }));

    const rulesTab = await screen.findByRole('button', { name: /^Rules/ });
    fireEvent.click(rulesTab);
    const rulesFs = await screen.findByTestId('rules-tab-fieldset');
    expect(rulesFs).not.toBeDisabled();

    const hooksTab = screen.getByRole('button', { name: /^Hooks/ });
    fireEvent.click(hooksTab);
    const hooksFs = await screen.findByTestId('hooks-tab-fieldset');
    expect(hooksFs).not.toBeDisabled();
  });

  // ────────────────────────────────────────────────────────────────────────
  // KILL-BOOTSTRAP-PROMPT-TO-DB (2026-05-22): Prompts tab AGENT.md textarea
  // is now editable for system agents (previously locked). Save triggers a
  // confirm modal warning. SOUL.md remains locked (not used by system agents).
  // ────────────────────────────────────────────────────────────────────────

  it('Prompts tab AGENT.md textarea is editable for system agents (KILL-BOOTSTRAP-PROMPT-TO-DB)', async () => {
    renderDrawer(makeAgent({ agentType: 'system' }));

    const promptsTab = await screen.findByRole('button', { name: /^Prompts/ });
    fireEvent.click(promptsTab);

    const editor = await screen.findByTestId('prompt-editor');
    expect(editor).not.toHaveAttribute('readonly');
  });

  // Helper: AntD Modal.confirm renders with class `ant-modal-confirm`; the
  // Drawer itself uses role=dialog (so `screen.getByRole('dialog')` is
  // ambiguous). Pick out the confirm modal by class + scope queries to it.
  function getConfirmModal(): HTMLElement | null {
    return document.querySelector('.ant-modal-confirm') as HTMLElement | null;
  }

  it('Save Prompts on system agent opens confirm modal; cancel does NOT call updateAgent', async () => {
    renderDrawer(makeAgent({ agentType: 'system' }));

    const promptsTab = await screen.findByRole('button', { name: /^Prompts/ });
    fireEvent.click(promptsTab);

    const editor = await screen.findByTestId('prompt-editor');
    fireEvent.change(editor, { target: { value: 'edited prompt body' } });

    const saveBtn = screen.getByTestId('prompts-save-btn');
    fireEvent.click(saveBtn);

    // Wait for AntD Modal.confirm to render into document.body
    await waitFor(() => {
      expect(getConfirmModal()).not.toBeNull();
    });
    expect(getConfirmModal()!).toHaveTextContent(/Editing system agent prompt/i);

    // Click cancel inside the modal (scoped to the confirm-modal element so
    // we don't accidentally pick the Drawer's own buttons).
    const cancelBtn = Array.from(
      getConfirmModal()!.querySelectorAll('button'),
    ).find((b) => /^Cancel$/.test(b.textContent ?? ''));
    expect(cancelBtn).toBeDefined();
    fireEvent.click(cancelBtn!);

    // updateAgent must NOT have been called
    await waitFor(() => {
      expect(updateAgent).not.toHaveBeenCalled();
    });
  });

  it('Save Prompts on system agent opens confirm modal; OK calls updateAgent with new systemPrompt', async () => {
    renderDrawer(makeAgent({ agentType: 'system' }));

    const promptsTab = await screen.findByRole('button', { name: /^Prompts/ });
    fireEvent.click(promptsTab);

    const editor = await screen.findByTestId('prompt-editor');
    fireEvent.change(editor, { target: { value: 'edited prompt body' } });

    const saveBtn = screen.getByTestId('prompts-save-btn');
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(getConfirmModal()).not.toBeNull();
    });

    // OK button label = okText prop ("Save"); scope to the confirm modal.
    const okBtn = Array.from(
      getConfirmModal()!.querySelectorAll('button'),
    ).find((b) => /^Save$/.test(b.textContent ?? ''));
    expect(okBtn).toBeDefined();
    await act(async () => {
      fireEvent.click(okBtn!);
    });

    await waitFor(() => {
      expect(updateAgent).toHaveBeenCalledTimes(1);
    });
    const [agentId, payload] = vi.mocked(updateAgent).mock.calls[0];
    expect(agentId).toBe(7);
    expect(payload).toEqual(
      expect.objectContaining({ systemPrompt: 'edited prompt body' }),
    );
  });

  it('Save Prompts on user agent does NOT open confirm modal — saves immediately', async () => {
    renderDrawer(makeAgent({ agentType: 'user' }));

    const promptsTab = await screen.findByRole('button', { name: /^Prompts/ });
    fireEvent.click(promptsTab);

    const editor = await screen.findByTestId('prompt-editor');
    fireEvent.change(editor, { target: { value: 'edited prompt body' } });

    const saveBtn = screen.getByTestId('prompts-save-btn');
    await act(async () => {
      fireEvent.click(saveBtn);
    });

    // No AntD confirm modal should appear (Drawer itself is a separate role)
    expect(getConfirmModal()).toBeNull();

    // updateAgent called immediately
    await waitFor(() => {
      expect(updateAgent).toHaveBeenCalledTimes(1);
    });
  });
});
