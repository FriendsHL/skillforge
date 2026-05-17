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
import { render, screen, waitFor } from '@testing-library/react';
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
vi.mock('../../components/ChatSidebar', () => ({ default: () => <aside /> }));
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
});
