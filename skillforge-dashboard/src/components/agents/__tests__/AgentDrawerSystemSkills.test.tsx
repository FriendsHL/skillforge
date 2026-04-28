/**
 * P1-C-3: AgentDrawer System Skills toggle panel.
 *
 * Asserts the persisted-disabled-name semantics: PATCH payload contains
 * `disabled_system_skills` JSON-array of *disabled* names, not enabled.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import type { AgentDto } from '../../../api/schemas';

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

vi.mock('../../../api', () => {
  return {
    updateAgent: vi.fn(() => Promise.resolve({ data: {} })),
    getTools: vi.fn(() => Promise.resolve({ data: [] })),
    getSkills: vi.fn((isSystem?: boolean) => {
      if (isSystem === true) {
        return Promise.resolve({
          data: [
            { name: 'Memory', description: 'Memory skill', system: true },
            { name: 'WebSearch', description: 'Search the web', system: true },
          ],
        });
      }
      return Promise.resolve({
        data: [
          { name: 'Memory', description: 'Memory skill', system: true },
          { name: 'WebSearch', description: 'Search the web', system: true },
          { name: 'custom-skill', description: 'A user skill', system: false },
        ],
      });
    }),
    getLifecycleHookEvents: vi.fn(() => Promise.resolve({ data: [] })),
    getLifecycleHookPresets: vi.fn(() => Promise.resolve({ data: [] })),
    getLifecycleHookMethods: vi.fn(() => Promise.resolve({ data: [] })),
    dryRunHook: vi.fn(() => Promise.resolve({ data: {} })),
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
            protocolFamily: 'generic_openai',
          },
        ],
      }),
    ),
    extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
      if (Array.isArray(res.data)) return res.data;
      const items = (res.data as { items?: T[] }).items;
      return Array.isArray(items) ? items : [];
    },
  };
});

import AgentDrawer from '../AgentDrawer';
import { updateAgent } from '../../../api';

function makeAgent(overrides: Partial<AgentDto> = {}): AgentDto {
  return {
    id: 42,
    name: 'Test agent',
    description: 'desc',
    systemPrompt: '',
    soulPrompt: '',
    modelId: 'openai:gpt-4o',
    behaviorRules: null,
    lifecycleHooks: null,
    skillIds: null,
    toolIds: null,
    disabledSystemSkills: null,
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

async function gotoToolsSkillsTab() {
  const tab = await screen.findByRole('button', { name: /Tools & Skills/i });
  fireEvent.click(tab);
}

describe('AgentDrawer — System Skills panel (P1-C-3)', () => {
  beforeEach(() => {
    vi.mocked(updateAgent).mockClear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders system skills derived from BE filtered list and reflects initial enabled state', async () => {
    renderDrawer(makeAgent({ disabledSystemSkills: '[]' }));
    await gotoToolsSkillsTab();

    await waitFor(() => {
      expect(screen.getByTestId('system-skills-panel')).toBeInTheDocument();
    });
    // Both system skills appear; both toggles are ON by default (none disabled).
    const memoryToggle = screen.getByTestId('system-skill-toggle-Memory');
    const searchToggle = screen.getByTestId('system-skill-toggle-WebSearch');
    expect(memoryToggle).toBeInTheDocument();
    expect(searchToggle).toBeInTheDocument();
    // Ant Design Switch exposes aria-checked.
    expect(memoryToggle.getAttribute('aria-checked')).toBe('true');
    expect(searchToggle.getAttribute('aria-checked')).toBe('true');
  });

  it('respects pre-existing disabled list from agent.disabledSystemSkills', async () => {
    renderDrawer(makeAgent({ disabledSystemSkills: JSON.stringify(['Memory']) }));
    await gotoToolsSkillsTab();

    await waitFor(() => {
      expect(screen.getByTestId('system-skill-toggle-Memory')).toBeInTheDocument();
    });
    const memoryToggle = screen.getByTestId('system-skill-toggle-Memory');
    const searchToggle = screen.getByTestId('system-skill-toggle-WebSearch');
    expect(memoryToggle.getAttribute('aria-checked')).toBe('false');
    expect(searchToggle.getAttribute('aria-checked')).toBe('true');
  });

  it('saving sends disabledSystemSkills as JSON array of *disabled* names (not enabled)', async () => {
    renderDrawer(makeAgent({ disabledSystemSkills: '[]' }));
    await gotoToolsSkillsTab();

    await waitFor(() => {
      expect(screen.getByTestId('system-skill-toggle-Memory')).toBeInTheDocument();
    });

    // Disable Memory by clicking its switch.
    const memoryToggle = screen.getByTestId('system-skill-toggle-Memory');
    await act(async () => {
      fireEvent.click(memoryToggle);
    });
    await waitFor(() => {
      expect(memoryToggle.getAttribute('aria-checked')).toBe('false');
    });

    // Click Save button in the toolsSkills tab footer.
    const saveBtn = screen.getByRole('button', { name: /^Save$/ });
    expect(saveBtn).not.toBeDisabled();
    await act(async () => {
      fireEvent.click(saveBtn);
    });

    await waitFor(() => {
      expect(updateAgent).toHaveBeenCalled();
    });
    const call = vi.mocked(updateAgent).mock.calls[0];
    expect(call[0]).toBe(42);
    const payload = call[1] as { disabledSystemSkills?: string };
    expect(payload.disabledSystemSkills).toBeDefined();
    const parsed = JSON.parse(payload.disabledSystemSkills!) as string[];
    // Must contain the *disabled* name; NOT the enabled one.
    expect(parsed).toEqual(['Memory']);
    expect(parsed).not.toContain('WebSearch');
  });

  it('toggling a previously-disabled skill ON removes it from the disabled list', async () => {
    renderDrawer(makeAgent({ disabledSystemSkills: JSON.stringify(['Memory', 'WebSearch']) }));
    await gotoToolsSkillsTab();

    await waitFor(() => {
      expect(screen.getByTestId('system-skill-toggle-Memory')).toBeInTheDocument();
    });

    const memoryToggle = screen.getByTestId('system-skill-toggle-Memory');
    expect(memoryToggle.getAttribute('aria-checked')).toBe('false');
    await act(async () => {
      fireEvent.click(memoryToggle);
    });
    await waitFor(() => {
      expect(memoryToggle.getAttribute('aria-checked')).toBe('true');
    });

    const saveBtn = screen.getByRole('button', { name: /^Save$/ });
    await act(async () => {
      fireEvent.click(saveBtn);
    });

    await waitFor(() => {
      expect(updateAgent).toHaveBeenCalled();
    });
    const payload = vi.mocked(updateAgent).mock.calls[0][1] as { disabledSystemSkills?: string };
    const parsed = JSON.parse(payload.disabledSystemSkills!) as string[];
    expect(parsed).toEqual(['WebSearch']);
  });
});
