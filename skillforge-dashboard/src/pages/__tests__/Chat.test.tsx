/**
 * SYSTEM-AGENT-TYPING Phase 2.3 — FE Chat send gate.
 *
 * Two cases (per requirements/active/SYSTEM-AGENT-TYPING/index.md task #4):
 *   1. `system agent disables send and shows banner`
 *   2. `system agent transcripts still rendered`
 *
 * Chat.tsx pulls in ~22 API helpers + 6 custom hooks + 12 child components.
 * To keep this test focused on the gate behavior, we replace ChatWindow with a
 * thin shim that surfaces `inputDisabled` + the messages list as test IDs, and
 * we stub every hook/API call this page uses on mount.
 *
 * NOTE: Mocking happens at the top of the file (Vitest hoists `vi.mock`).
 */
import React from 'react';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

// jsdom lacks ResizeObserver / matchMedia / WebSocket — the chat surface and
// AntD components need them at import time.
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
class FakeWs {
  onmessage: ((ev: { data: string }) => void) | null = null;
  close() {}
  send() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

// ---- API mocks ------------------------------------------------------------
// We return a single user agent + a single system agent so the test can swap
// the selected agent via the URL ?agent= param and observe the gate.
const userAgent = {
  id: 1,
  name: 'Main Assistant',
  description: 'user agent',
  agentType: 'user' as const,
};
const systemAgent = {
  id: 7,
  name: 'session-annotator',
  description: 'system agent',
  agentType: 'system' as const,
};

const getAgentsMock = vi.fn(() => Promise.resolve({ data: [userAgent, systemAgent] }));
const getSessionsMock = vi.fn(() =>
  Promise.resolve({
    data: [
      {
        id: 's-sys-1',
        agentId: systemAgent.id,
        title: 'system agent session',
        createdAt: new Date().toISOString(),
      },
      {
        id: 's-user-1',
        agentId: userAgent.id,
        title: 'user session',
        createdAt: new Date().toISOString(),
      },
    ],
  }),
);

vi.mock('../../api', () => ({
  getAgents: (...args: unknown[]) => getAgentsMock(...(args as [])),
  createSession: vi.fn(() => Promise.resolve({ data: { id: 'new-sess' } })),
  getSessions: (...args: unknown[]) => getSessionsMock(...(args as [])),
  getSessionMessages: vi.fn(() => Promise.resolve({ data: [] })),
  sendMessage: vi.fn(() => Promise.resolve({ data: {} })),
  uploadChatAttachment: vi.fn(() => Promise.resolve({ data: { id: 'a' } })),
  cancelChat: vi.fn(() => Promise.resolve({ data: {} })),
  answerAsk: vi.fn(() => Promise.resolve({ data: {} })),
  setSessionMode: vi.fn(() => Promise.resolve({ data: {} })),
  getSession: vi.fn(() => Promise.resolve({ data: {} })),
  compactSession: vi.fn(() => Promise.resolve({ data: { tokensReclaimed: 0 } })),
  getCompactions: vi.fn(() => Promise.resolve({ data: [] })),
  getSessionCheckpoints: vi.fn(() => Promise.resolve({ data: [] })),
  getSessionCheckpoint: vi.fn(() => Promise.resolve({ data: {} })),
  branchFromCheckpoint: vi.fn(() => Promise.resolve({ data: {} })),
  restoreFromCheckpoint: vi.fn(() => Promise.resolve({ data: {} })),
  getCollabRunMembers: vi.fn(() => Promise.resolve({ data: { members: [] } })),
  submitConfirmation: vi.fn(() => Promise.resolve({ data: {} })),
  extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
    if (Array.isArray(res.data)) return res.data;
    const items = (res.data as { items?: T[] }).items;
    return Array.isArray(items) ? items : [];
  },
}));

// ---- Auth mock -------------------------------------------------------------
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

// ---- Hook mocks ------------------------------------------------------------
// useChatMessages — return a fixed transcript so the "transcripts still
// rendered" case has something assertable.
const fixedTranscript = [
  { role: 'user', content: 'hello system agent' },
  { role: 'assistant', content: 'reply from system agent run' },
];
vi.mock('../../hooks/useChatMessages', () => ({
  useChatMessages: () => ({
    setRawMessages: vi.fn(),
    messages: fixedTranscript,
    streamingText: '',
    setStreamingText: vi.fn(),
    streamingToolInputs: {},
    setStreamingToolInputs: vi.fn(),
    inflightTools: {},
    setInflightTools: vi.fn(),
    loopSpans: [],
    setLoopSpans: vi.fn(),
  }),
}));
vi.mock('../../hooks/useChatWebSocket', () => ({
  useChatWebSocket: () => {},
}));
vi.mock('../../hooks/useLlmModels', () => ({
  useLlmModels: () => ({ options: [] }),
}));
vi.mock('../../hooks/useCollabState', () => ({
  useCollabState: () => ({
    collabRunId: null,
    setCollabRunId: vi.fn(),
    collabHandle: null,
    setCollabHandle: vi.fn(),
    collabLeaderSessionId: null,
    setCollabLeaderSessionId: vi.fn(),
    collabRunStatus: null,
    setCollabRunStatus: vi.fn(),
  }),
}));
vi.mock('../../hooks/useChatSession', () => ({
  useChatSession: () => {},
}));
vi.mock('../../hooks/useChatWsEventHandler', () => ({
  useChatWsEventHandler: () => () => {},
}));

// ---- Child component mocks ------------------------------------------------
// ChatWindow shim — surface `inputDisabled` + `messages` so the gate behavior
// is visible to the test. We render the disabled state as a data-testid that
// the test can query without depending on AntD internals.
vi.mock('../../components/ChatWindow', () => {
  return {
    default: (props: { inputDisabled?: boolean; messages?: Array<{ role: string; content: string }> }) => (
      <div data-testid="chat-window">
        <div data-testid="chat-window-input-disabled">
          {String(!!props.inputDisabled)}
        </div>
        <ul data-testid="chat-window-transcripts">
          {(props.messages ?? []).map((m, i) => (
            <li key={i} data-testid={`transcript-${m.role}`}>
              {String(m.content)}
            </li>
          ))}
        </ul>
      </div>
    ),
  };
});
vi.mock('../../components/SessionReplay', () => ({ default: () => <div /> }));
vi.mock('../../components/CompactionHistoryModal', () => ({ default: () => <div /> }));
vi.mock('../../components/CheckpointModal', () => ({ default: () => <div /> }));
vi.mock('../../components/RuntimeBanner', () => ({ default: () => <div data-testid="runtime-banner" /> }));
vi.mock('../../components/PendingAskCard', () => ({ default: () => <div /> }));
vi.mock('../../components/InstallConfirmationCard', () => ({ default: () => <div /> }));
// ChatSidebar shim — surface props (activeAgentTab, scoped agent list,
// selectedAgent, callbacks) so the Phase 2 UX refactor tests can drive tab
// switches and assert agent picker scope without depending on AntD Tabs
// internals or the full ChatSidebar DOM.
//
// We intentionally re-implement the agentType filter in the mock to match
// ChatSidebar's actual logic ("agentType=null → user"). This keeps the test
// honest: if Chat.tsx fails to pass the prop or wires the wrong key, the
// scoped-list assertion below will catch it.
vi.mock('../../components/ChatSidebar', () => ({
  default: (props: {
    agents?: Array<{ id: number; name: string; agentType?: 'user' | 'system' | null }>;
    selectedAgent?: number;
    activeAgentTab?: 'user' | 'system';
    onSelectAgent?: (id: number) => void;
    onAgentTabChange?: (next: 'user' | 'system') => void;
  }) => {
    const tab = props.activeAgentTab ?? 'user';
    const scoped = (props.agents ?? []).filter(
      (a) => (a.agentType ?? 'user') === tab,
    );
    return (
      <aside data-testid="chat-sidebar">
        <div data-testid="chat-sidebar-active-tab">{tab}</div>
        <div data-testid="chat-sidebar-selected-agent">
          {props.selectedAgent == null ? 'none' : String(props.selectedAgent)}
        </div>
        <ul data-testid="chat-sidebar-scoped-agents">
          {scoped.map((a) => (
            <li key={a.id} data-testid={`sidebar-agent-${a.id}`}>
              {a.name}
            </li>
          ))}
        </ul>
        <button
          type="button"
          data-testid="chat-sidebar-tab-user"
          onClick={() => props.onAgentTabChange?.('user')}
        />
        <button
          type="button"
          data-testid="chat-sidebar-tab-system"
          onClick={() => props.onAgentTabChange?.('system')}
        />
      </aside>
    );
  },
}));
vi.mock('../../components/chat/RightRail', () => ({ default: () => <aside /> }));
vi.mock('../../components/chat/primitives', () => ({
  Chip: (props: { children?: React.ReactNode }) => <span>{props.children}</span>,
  Seg: () => <div />,
}));
vi.mock('../../components/chat/ChatIcons', () => ({
  IconChat: () => <span />,
  IconCompact: () => <span />,
  IconReplay: () => <span />,
}));
vi.mock('../../components/channels/ChannelBadge', () => ({
  ChannelBadge: () => <span />,
}));

import Chat from '../Chat';

function renderChatWithAgent(agentId: number, sessionId = 's-test-1') {
  // Chat reads selectedAgent from `?agent=` and the sessionId from URL params.
  return render(
    <MemoryRouter initialEntries={[`/chat/${sessionId}?agent=${agentId}`]}>
      <Routes>
        <Route path="/chat/:sessionId" element={<Chat />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Chat — system agent send gate (SYSTEM-AGENT-TYPING Phase 2.3)', () => {
  beforeEach(() => {
    getAgentsMock.mockClear();
    getSessionsMock.mockClear();
    // Phase 2 UX refactor — chat sidebar tab is persisted in localStorage.
    // Clear between tests so localStorage state from one case doesn't leak
    // into the next (which would otherwise auto-flip the tab before the
    // assertion runs).
    window.localStorage.clear();
  });

  it('system agent disables send and shows banner', async () => {
    renderChatWithAgent(systemAgent.id);

    // Banner: surfaces the read-only message.
    const banner = await screen.findByTestId('system-agent-chat-banner');
    expect(banner).toBeInTheDocument();
    expect(banner.textContent).toMatch(/read-only via Chat/i);

    // inputDisabled flows into ChatWindow as `true` — the shim renders the
    // boolean as text so we can assert without poking AntD internals.
    await waitFor(() => {
      expect(screen.getByTestId('chat-window-input-disabled').textContent).toBe('true');
    });
  });

  it('system agent transcripts still rendered', async () => {
    renderChatWithAgent(systemAgent.id);

    // Even though send is gated, the message list (transcripts) must keep
    // rendering — operator can audit prior tool_use / tool_result blocks.
    await waitFor(() => {
      expect(screen.getByTestId('chat-window-transcripts')).toBeInTheDocument();
    });
    expect(screen.getByTestId('transcript-user').textContent).toBe('hello system agent');
    expect(screen.getByTestId('transcript-assistant').textContent).toBe(
      'reply from system agent run',
    );
  });

  it('user agent does not show the system-agent banner and does not force disable', async () => {
    renderChatWithAgent(userAgent.id);

    // Wait for agents to load before asserting absence — otherwise the gate
    // is trivially "not system" because agents=[] during the very first
    // render and the banner correctly doesn't show yet.
    await waitFor(() => {
      expect(screen.getByTestId('chat-window')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('system-agent-chat-banner')).not.toBeInTheDocument();
    // Send gate is only set by the system-agent path; with no pendingAsk /
    // pendingConfirm in this test setup, inputDisabled must be false.
    expect(screen.getByTestId('chat-window-input-disabled').textContent).toBe('false');
  });

  // ---- Phase 2 UX refactor (2026-05-18) — sidebar Tabs --------------------

  it('sidebar tab auto-switches to System when the selected agent is a system agent', async () => {
    renderChatWithAgent(systemAgent.id);

    // After agents load, the auto-switch effect in Chat.tsx picks up that
    // selectedAgent → systemAgent, which has agentType='system', and flips
    // the sidebar tab. We assert via the ChatSidebar mock's surface.
    await waitFor(() => {
      expect(screen.getByTestId('chat-sidebar-active-tab').textContent).toBe('system');
    });
    // selectedAgent is preserved (system agent is the picked one).
    expect(screen.getByTestId('chat-sidebar-selected-agent').textContent).toBe(
      String(systemAgent.id),
    );
    // Scoped agent list shows ONLY the system agent (user agent is filtered
    // out by the active tab).
    expect(screen.getByTestId(`sidebar-agent-${systemAgent.id}`)).toBeInTheDocument();
    expect(screen.queryByTestId(`sidebar-agent-${userAgent.id}`)).not.toBeInTheDocument();
  });

  // ---- Phase B visibility (2026-05-18) — agentType-aware fetch ------------

  it('selecting a system agent fetches sessions with agentType=system (Phase B)', async () => {
    renderChatWithAgent(systemAgent.id);

    // The agents=[user,system] list loads via getAgents('all') first, then
    // Chat's effect runs getSessions(userId, agentType) where agentType
    // is derived from the selected agent. With selectedAgent=systemAgent.id,
    // the call MUST include agentType='system' so BE bypasses userId filter
    // and returns cron-owned (ownerId=0) sessions.
    await waitFor(() => {
      expect(getSessionsMock).toHaveBeenCalledWith(1, 'system');
    });
  });

  it('selecting a user agent fetches sessions with agentType=user (legacy path)', async () => {
    renderChatWithAgent(userAgent.id);

    await waitFor(() => {
      expect(getSessionsMock).toHaveBeenCalledWith(1, 'user');
    });
  });

  it('manual sidebar tab switch preserves selectedAgent (cross-tab persistence)', async () => {
    renderChatWithAgent(userAgent.id);

    // Wait for the user tab to render with the user agent.
    await waitFor(() => {
      expect(screen.getByTestId('chat-sidebar-active-tab').textContent).toBe('user');
    });
    expect(screen.getByTestId('chat-sidebar-selected-agent').textContent).toBe(
      String(userAgent.id),
    );

    // Operator manually switches to the System tab — selectedAgent must
    // survive (UX intent: "切 tab 不重置 selectedAgent").
    await act(async () => {
      fireEvent.click(screen.getByTestId('chat-sidebar-tab-system'));
    });
    await waitFor(() => {
      expect(screen.getByTestId('chat-sidebar-active-tab').textContent).toBe('system');
    });
    // selectedAgent still points at the user agent (cross-tab state preserved).
    expect(screen.getByTestId('chat-sidebar-selected-agent').textContent).toBe(
      String(userAgent.id),
    );
    // localStorage persistence — manual switch wins, written to the chat key.
    expect(window.localStorage.getItem('chat.active_agent_tab')).toBe('system');
  });
});
