import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatMessage } from '../../ChatWindow';
import WorkspaceTab from '../WorkspaceTab';
import { collectWorkspaceAttachments } from '../workspaceAttachments';

const getChatAttachmentBlobMock = vi.fn();
const getWorkspaceEntriesMock = vi.fn();
const getWorkspaceContentMock = vi.fn();

vi.mock('../../../api', () => ({
  getChatAttachmentBlob: (...args: unknown[]) =>
    getChatAttachmentBlobMock(...args),
}));

vi.mock('../../../api/workspace', () => ({
  getWorkspaceEntries: (...args: unknown[]) => getWorkspaceEntriesMock(...args),
  getWorkspaceContent: (...args: unknown[]) => getWorkspaceContentMock(...args),
}));

function renderWorkspace(
  props: React.ComponentProps<typeof WorkspaceTab> = {
    messages,
    sessionId: 'session-1',
    sessionTitle: 'Tokyo trip',
    userId: 7,
  },
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <WorkspaceTab {...props} />
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

const messages: ChatMessage[] = [
  {
    role: 'user',
    content: 'Use this booking confirmation.',
    attachments: [
      {
        kind: 'pdf',
        attachmentId: 'source-1',
        filename: 'hotel-confirmation.pdf',
        pageCount: 2,
      },
    ],
  },
  {
    role: 'assistant',
    content: 'I created the budget.',
    attachments: [
      {
        kind: 'excel',
        attachmentId: 'generated-1',
        filename: 'tokyo-budget.xlsx',
        sheetCount: 3,
        caption: 'Generated travel budget',
      },
    ],
  },
  {
    role: 'assistant',
    content: 'The same file is still available.',
    attachments: [
      {
        kind: 'excel',
        attachmentId: 'generated-1',
        filename: 'tokyo-budget.xlsx',
      },
    ],
  },
];

describe('WorkspaceTab', () => {
  beforeEach(() => {
    getChatAttachmentBlobMock.mockReset();
    getWorkspaceEntriesMock.mockReset();
    getWorkspaceContentMock.mockReset();
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:workspace-file'),
      revokeObjectURL: vi.fn(),
    });
  });

  it('deduplicates attachments and keeps their original message role', () => {
    const items = collectWorkspaceAttachments(messages);

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ attachmentId: 'source-1', origin: 'source' });
    expect(items[1]).toMatchObject({ attachmentId: 'generated-1', origin: 'generated' });
  });

  it('keeps the first-seen origin when a later message references the same file', () => {
    const items = collectWorkspaceAttachments([
      {
        role: 'user',
        content: 'Source',
        attachments: [{ kind: 'pdf', attachmentId: 'shared-1', filename: 'shared.pdf' }],
      },
      {
        role: 'assistant',
        content: 'Referenced again',
        attachments: [{ kind: 'pdf', attachmentId: 'shared-1', filename: 'shared.pdf' }],
      },
    ]);

    expect(items).toEqual([
      expect.objectContaining({ attachmentId: 'shared-1', origin: 'source' }),
    ]);
  });

  it('renders current-session sources and generated files', () => {
    renderWorkspace();

    expect(screen.getByRole('tab', { name: 'This session' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'MySpace' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByText('Session workspace')).toBeInTheDocument();
    expect(screen.getByText('Tokyo trip')).toBeInTheDocument();
    expect(screen.getByText('Sources')).toBeInTheDocument();
    expect(screen.getByText('Generated')).toBeInTheDocument();
    expect(screen.getByText('hotel-confirmation.pdf')).toBeInTheDocument();
    expect(screen.getByText('tokyo-budget.xlsx')).toBeInTheDocument();
    expect(screen.getByText('1 source')).toBeInTheDocument();
    expect(screen.getByText('1 generated')).toBeInTheDocument();
  });

  it('supports arrow-key navigation between the workspace views', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false, entries: [],
      },
    });
    renderWorkspace();
    const sessionTab = screen.getByRole('tab', { name: 'This session' });

    fireEvent.keyDown(sessionTab, { key: 'ArrowRight' });

    const mySpaceTab = screen.getByRole('tab', { name: 'MySpace' });
    expect(mySpaceTab).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(mySpaceTab).toHaveFocus());
  });

  it('renders an honest empty state when the session has no files', () => {
    renderWorkspace({
      messages: [],
      sessionId: 'session-empty',
      sessionTitle: 'Empty session',
      userId: 7,
    });

    expect(screen.getByText('No files in this session yet.')).toBeInTheDocument();
    expect(screen.getByText(/Uploaded and agent-produced files/)).toBeInTheDocument();
  });

  it('disables authenticated downloads when user context is unavailable', () => {
    renderWorkspace({ messages, sessionId: 'session-1', sessionTitle: 'Tokyo trip' });

    expect(
      screen.getByRole('button', { name: 'Download tokyo-budget.xlsx' }),
    ).toBeDisabled();
  });

  it('downloads an attachment through the authenticated blob endpoint', async () => {
    getChatAttachmentBlobMock.mockResolvedValue({ data: new Blob(['budget']) });
    const clickSpy = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    renderWorkspace();

    fireEvent.click(screen.getByRole('button', { name: 'Download tokyo-budget.xlsx' }));

    await waitFor(() => {
      expect(getChatAttachmentBlobMock).toHaveBeenCalledWith(
        'generated-1',
        7,
        'session-1',
      );
    });
    expect(URL.createObjectURL).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalledOnce();
  });

  it('shows a retryable error when an attachment download fails', async () => {
    getChatAttachmentBlobMock.mockRejectedValue(new Error('network down'));
    renderWorkspace();

    const download = screen.getByRole('button', { name: 'Download tokyo-budget.xlsx' });
    fireEvent.click(download);

    expect(await screen.findByRole('alert')).toHaveTextContent('Download failed. Try again.');
    expect(download).toBeEnabled();
  });

  it('browses MySpace without an active session and filters only the current directory', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge',
        path: '',
        parentPath: null,
        truncated: true,
        entries: [
          { name: 'docs', path: 'docs', type: 'directory', previewable: false },
          { name: 'README.md', path: 'README.md', type: 'file', sizeBytes: 120, previewable: true },
        ],
      },
    });
    renderWorkspace({ messages: [], sessionId: null });

    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));

    expect(await screen.findByText('README.md')).toBeInTheDocument();
    expect(screen.getByText('120 B')).toBeInTheDocument();
    expect(screen.getByText('Showing the first entries in this folder.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open folder docs' })).toBeInTheDocument();
    expect(screen.queryByText('Filter current directory')).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox', { name: 'Filter current directory' }), {
      target: { value: 'read' },
    });
    expect(screen.queryByText('docs')).not.toBeInTheDocument();
    expect(screen.getByText('README.md')).toBeInTheDocument();
    expect(screen.getByLabelText('1 visible entries')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Clear file filter' }));
    expect(screen.getByText('docs')).toBeInTheDocument();
    expect(screen.getByLabelText('2 visible entries')).toBeInTheDocument();
  });

  it('loads folders lazily and navigates back through breadcrumbs', async () => {
    getWorkspaceEntriesMock
      .mockResolvedValueOnce({
        data: {
          rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false,
          entries: [{ name: 'docs', path: 'docs', type: 'directory', previewable: false }],
        },
      })
      .mockResolvedValueOnce({
        data: {
          rootLabel: 'SkillForge', path: 'docs', parentPath: '', truncated: false,
          entries: [{ name: 'guide.md', path: 'docs/guide.md', type: 'file', previewable: true }],
        },
      });
    renderWorkspace();
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Open folder docs' }));

    expect(await screen.findByText('guide.md')).toBeInTheDocument();
    expect(getWorkspaceEntriesMock).toHaveBeenLastCalledWith('docs');
    fireEvent.click(screen.getByRole('button', { name: 'SkillForge' }));
    expect(await screen.findByRole('button', { name: 'Open folder docs' })).toBeInTheDocument();
  });

  it('previews markdown inline and returns to the directory', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false,
        entries: [{ name: 'README.md', path: 'README.md', type: 'file', sizeBytes: 80, previewable: true }],
      },
    });
    getWorkspaceContentMock.mockResolvedValueOnce({
      data: {
        path: 'README.md', content: '# Workspace overview', sizeBytes: 80,
        truncated: false, binary: false,
      },
    });
    renderWorkspace();
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Preview README.md' }));

    expect(await screen.findByRole('heading', { name: 'Workspace overview' })).toBeInTheDocument();
    expect(getWorkspaceContentMock).toHaveBeenCalledWith('README.md');
    fireEvent.click(screen.getByRole('button', { name: 'Back to files' }));
    expect(screen.getByRole('button', { name: 'Preview README.md' })).toBeInTheDocument();
  });

  it('uses a plain pre for non-markdown text and marks truncated previews', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false,
        entries: [{ name: 'settings.txt', path: 'settings.txt', type: 'file', previewable: true }],
      },
    });
    getWorkspaceContentMock.mockResolvedValueOnce({
      data: {
        path: 'settings.txt', content: 'mode=local', sizeBytes: 300000,
        truncated: true, binary: false,
      },
    });
    renderWorkspace();
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Preview settings.txt' }));

    expect(await screen.findByTestId('workspace-text-preview')).toHaveTextContent('mode=local');
    expect(screen.getByText('Preview truncated for safety.')).toBeInTheDocument();
  });

  it('shows safe folder errors with retry and does not expose server details', async () => {
    getWorkspaceEntriesMock
      .mockRejectedValueOnce(new Error('/Users/private/root leaked'))
      .mockResolvedValueOnce({
        data: { rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false, entries: [] },
      });
    renderWorkspace();
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));

    expect(await screen.findByText('Could not load this folder.')).toBeInTheDocument();
    expect(screen.queryByText(/private\/root/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading folder' }));
    expect(await screen.findByText('This folder is empty.')).toBeInTheDocument();
  });

  it('keeps stale folder entries with a nonblocking notice when refresh fails', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false,
        entries: [{ name: 'README.md', path: 'README.md', type: 'file', previewable: true }],
      },
    });
    const { queryClient } = renderWorkspace();
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));
    expect(await screen.findByText('README.md')).toBeInTheDocument();
    getWorkspaceEntriesMock.mockRejectedValueOnce(new Error('refresh failed'));

    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: ['workspace', 'entries', 7, ''],
        exact: true,
      });
    });

    expect(screen.getByText('README.md')).toBeInTheDocument();
    expect(screen.queryByText('Could not load this folder.')).not.toBeInTheDocument();
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Could not refresh this folder. Showing previously loaded files.',
    );
  });

  it('keeps stale preview content with a nonblocking notice when refresh fails', async () => {
    getWorkspaceEntriesMock.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: '', parentPath: null, truncated: false,
        entries: [{ name: 'README.md', path: 'README.md', type: 'file', previewable: true }],
      },
    });
    getWorkspaceContentMock.mockResolvedValueOnce({
      data: {
        path: 'README.md', content: '# Retained preview', sizeBytes: 20,
        truncated: false, binary: false,
      },
    });
    const { queryClient } = renderWorkspace();
    fireEvent.click(screen.getByRole('tab', { name: 'MySpace' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Preview README.md' }));
    expect(await screen.findByRole('heading', { name: 'Retained preview' })).toBeInTheDocument();
    getWorkspaceContentMock.mockRejectedValueOnce(new Error('refresh failed'));

    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: ['workspace', 'content', 7, 'README.md'],
        exact: true,
      });
    });

    expect(screen.getByRole('heading', { name: 'Retained preview' })).toBeInTheDocument();
    expect(screen.queryByText('Could not preview this file.')).not.toBeInTheDocument();
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Could not refresh this preview. Showing previously loaded content.',
    );
  });
});
