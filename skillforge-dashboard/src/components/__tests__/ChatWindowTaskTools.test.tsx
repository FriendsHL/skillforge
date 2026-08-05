import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

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

vi.mock('../../api/commands', () => ({ executeCommand: vi.fn() }));
vi.mock('../AttachmentThumbnail', () => ({ default: () => null }));
vi.mock('../MediaJobCard', () => ({ default: () => null }));

import ChatWindow from '../ChatWindow';

describe('ChatWindow task tool cards', () => {
  it('renders dedicated TaskCreate and TaskUpdate summaries', () => {
    render(
      <ChatWindow
        messages={[{
          role: 'assistant',
          content: 'Working on it.',
          toolCalls: [
            {
              id: 'create-1',
              name: 'TaskCreate',
              input: {
                subject: 'Build daily digest',
                description: 'Collect, summarize, and publish the digest.',
                activeForm: 'Building daily digest',
              },
              output: JSON.stringify({
                task: {
                  taskId: 'task-1',
                  subject: 'Build daily digest',
                  status: 'pending',
                  activeForm: 'Building daily digest',
                },
              }),
              status: 'success',
            },
            {
              id: 'update-1',
              name: 'TaskUpdate',
              input: { taskId: 'task-1', status: 'in_progress', activeForm: 'Publishing digest' },
              output: JSON.stringify({
                task: {
                  taskId: 'task-1',
                  subject: 'Build daily digest',
                  status: 'in_progress',
                  activeForm: 'Publishing digest',
                },
              }),
              status: 'success',
            },
          ],
        }]}
        loading={false}
        onSend={vi.fn()}
      />,
    );

    expect(screen.getByText('Task created')).toBeInTheDocument();
    expect(screen.getAllByText('Build daily digest').length).toBeGreaterThan(0);
    expect(screen.getByText('Task updated')).toBeInTheDocument();
    expect(screen.getByText('status → in progress')).toBeInTheDocument();
    expect(screen.getByText('Publishing digest')).toBeInTheDocument();
  });

  it('keeps historical TodoWrite on the generic tool renderer', () => {
    render(
      <ChatWindow
        messages={[{
          role: 'assistant',
          content: '',
          toolCalls: [{
            id: 'todo-legacy',
            name: 'TodoWrite',
            input: { todos: [{ subject: 'Legacy task', status: 'completed' }] },
            output: '1 todo updated',
            status: 'success',
          }],
        }]}
        loading={false}
        onSend={vi.fn()}
      />,
    );

    const row = screen.getByRole('button', { name: /TodoWrite/i });
    expect(row).toBeInTheDocument();
    fireEvent.click(row);
    expect(screen.getAllByText(/Legacy task/).length).toBeGreaterThan(0);
    expect(screen.getByText('1 todo updated')).toBeInTheDocument();
  });

  it('falls back safely when a Task tool result is not JSON', () => {
    render(
      <ChatWindow
        messages={[{
          role: 'assistant',
          content: '',
          toolCalls: [{
            id: 'task-malformed',
            name: 'TaskUpdate',
            input: { taskId: 'task-1', status: 'completed' },
            output: 'legacy text result',
            status: 'success',
          }],
        }]}
        loading={false}
        onSend={vi.fn()}
      />,
    );

    expect(screen.getByText('Task updated')).toBeInTheDocument();
    expect(screen.getByText('status → completed')).toBeInTheDocument();
  });
});
