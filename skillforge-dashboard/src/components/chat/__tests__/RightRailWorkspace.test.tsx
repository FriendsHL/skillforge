import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import type { ChatMessage } from '../../ChatWindow';
import RightRail from '../RightRail';

vi.mock('../../../api', () => ({
  getSubAgentRuns: vi.fn(() => Promise.resolve({ data: [] })),
  getContextBreakdown: vi.fn(() => Promise.resolve({ data: {} })),
  getTraces: vi.fn(() => Promise.resolve({ data: [] })),
  getTraceTree: vi.fn(() => Promise.resolve({ data: { traces: [] } })),
  getChatAttachmentBlob: vi.fn(() => Promise.resolve({ data: new Blob() })),
}));

const getWorkspaceEntriesMock = vi.fn();

vi.mock('../../../api/workspace', () => ({
  getWorkspaceEntries: (...args: unknown[]) => getWorkspaceEntriesMock(...args),
  getWorkspaceContent: vi.fn(),
}));

describe('RightRail workspace integration', () => {
  it('keeps MySpace available in the rail when no session is selected', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false,
        entries: [{ name: 'README.md', path: 'README.md', type: 'file', previewable: true }],
      },
    });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <RightRail inflightTools={{}} runtimeStatus="idle" messages={[]} />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Workspace' }));
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));

    expect(await screen.findByText('README.md')).toBeInTheDocument();
  });

  it('opens the current session workspace without changing existing tabs', () => {
    const messages: ChatMessage[] = [
      {
        role: 'assistant',
        content: 'Generated file',
        attachments: [
          {
            kind: 'pdf',
            attachmentId: 'artifact-1',
            filename: 'travel-plan.pdf',
          },
        ],
      },
    ];
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <RightRail
          inflightTools={{}}
          runtimeStatus="idle"
          messages={messages}
          sessionId="session-1"
          sessionTitle="Tokyo trip"
          userId={7}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByRole('button', { name: 'Context' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Activity/ })).toHaveClass('on');
    expect(screen.getByRole('button', { name: 'SubAgent' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Team/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Workspace' }));

    expect(screen.getByText('Tokyo trip')).toBeInTheDocument();
    expect(screen.getByText('travel-plan.pdf')).toBeInTheDocument();
  });

  it('keeps Team as the default tab for an active collaboration run', () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <RightRail
          collabRunId="run-1"
          collabMembers={[]}
          inflightTools={{}}
          runtimeStatus="idle"
          messages={[]}
          sessionId="session-1"
          userId={7}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByRole('button', { name: /Team/ })).toHaveClass('on');
    expect(screen.getByText(/Not part of a team run/)).toBeInTheDocument();
  });
});
