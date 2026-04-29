/**
 * OBS-1 R2-B1 — SessionTimelinePanel merges chat messages with LLM + Tool
 * spans by timestamp; clicking a span surfaces it via onSelectSpan.
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SessionTimelinePanel, {
  type TimelineMessage,
} from '../SessionTimelinePanel';

void React;
import type {
  LlmSpanSummary,
  ToolSpanSummary,
} from '../../../../types/observability';

const t = (iso: string) => iso;

const messages: TimelineMessage[] = [
  { id: 'm1', role: 'user', createdAt: t('2026-04-29T00:00:00.000Z'), text: 'hello' },
  { id: 'm2', role: 'assistant', createdAt: t('2026-04-29T00:00:05.000Z'), text: 'hi there' },
];

const llmSpan: LlmSpanSummary = {
  kind: 'llm',
  spanId: 'span-llm-1',
  traceId: 'trace-1',
  parentSpanId: null,
  startedAt: '2026-04-29T00:00:01.000Z',
  endedAt: '2026-04-29T00:00:02.000Z',
  latencyMs: 1000,
  provider: 'claude',
  model: 'claude-sonnet-4-6',
  inputTokens: 100,
  outputTokens: 50,
  source: 'live',
  stream: true,
  hasRawRequest: true,
  hasRawResponse: true,
  hasRawSse: true,
  blobStatus: 'ok',
};

const toolSpan: ToolSpanSummary = {
  kind: 'tool',
  spanId: 'span-tool-1',
  traceId: 'trace-1',
  parentSpanId: null,
  startedAt: '2026-04-29T00:00:03.000Z',
  endedAt: '2026-04-29T00:00:04.000Z',
  latencyMs: 200,
  toolName: 'Bash',
  toolUseId: 'tu-1',
  success: true,
};

describe('SessionTimelinePanel (merged messages + LLM + Tool spans)', () => {
  it('renders all items in chronological order and surfaces clicks', () => {
    const onSelect = vi.fn();
    render(
      <SessionTimelinePanel
        messages={messages}
        spans={[llmSpan, toolSpan]}
        selectedSpanId={null}
        onSelectSpan={onSelect}
      />,
    );

    // Both spans visible (kind tag rendered).
    expect(screen.getByText(/claude/)).toBeInTheDocument();
    expect(screen.getByText(/Bash/)).toBeInTheDocument();
    // Both messages visible.
    expect(screen.getByText('hello')).toBeInTheDocument();
    expect(screen.getByText('hi there')).toBeInTheDocument();

    // Order: user msg @00s → llm @01s → tool @03s → asst msg @05s.
    const llmRow = screen.getByText(/claude/).closest('button');
    const toolRow = screen.getByText(/Bash/).closest('button');
    expect(llmRow).not.toBeNull();
    expect(toolRow).not.toBeNull();

    const allButtons = screen.getAllByRole('button');
    const llmIdx = allButtons.indexOf(llmRow as HTMLElement);
    const toolIdx = allButtons.indexOf(toolRow as HTMLElement);
    expect(llmIdx).toBeLessThan(toolIdx);

    fireEvent.click(toolRow as HTMLElement);
    expect(onSelect).toHaveBeenCalledWith(toolSpan);
  });
});
