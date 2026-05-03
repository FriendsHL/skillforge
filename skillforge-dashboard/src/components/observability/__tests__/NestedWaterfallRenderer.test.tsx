/**
 * OBS-3 — NestedWaterfallRenderer unit tests.
 *
 * Covers:
 *   1. full-mode renders a parent span row + a TeamCreate dispatch row with toggle
 *   2. mini-mode renders compact rows with status dot
 *   3. toggle expands child sub-tree (descendant rows visible only when expanded)
 *   4. truncated=true triggers "Show more" → onLoadMore fires with the right traceId
 *   5. status dot/badge reflects descendant.status (live update path)
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import NestedWaterfallRenderer from '../NestedWaterfallRenderer';
import type {
  UnifiedSpan,
  DescendantTraceMeta,
  ToolSpanSummary,
  LlmSpanSummary,
} from '../../../types/observability';

void React;

const llmParent: UnifiedSpan = {
  depth: 0,
  parentTraceId: null,
  span: {
    kind: 'llm',
    spanId: 'llm-1',
    traceId: 'trace-parent',
    parentSpanId: null,
    startedAt: '2026-05-03T10:00:00.000Z',
    endedAt: '2026-05-03T10:00:01.000Z',
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
  } satisfies LlmSpanSummary,
};

const dispatchTool: UnifiedSpan = {
  depth: 0,
  parentTraceId: null,
  span: {
    kind: 'tool',
    spanId: 'tool-dispatch-1',
    traceId: 'trace-parent',
    parentSpanId: null,
    startedAt: '2026-05-03T10:00:02.000Z',
    endedAt: '2026-05-03T10:00:03.000Z',
    latencyMs: 1000,
    toolName: 'TeamCreate',
    toolUseId: 'tu-1',
    success: true,
  } satisfies ToolSpanSummary,
};

const childSpan: UnifiedSpan = {
  depth: 1,
  parentTraceId: 'trace-parent',
  span: {
    kind: 'llm',
    spanId: 'llm-child-1',
    traceId: 'trace-child-1',
    parentSpanId: null,
    startedAt: '2026-05-03T10:00:02.500Z',
    endedAt: '2026-05-03T10:00:03.000Z',
    latencyMs: 500,
    provider: 'claude',
    model: 'claude-haiku-4-5',
    inputTokens: 20,
    outputTokens: 10,
    source: 'live',
    stream: true,
    hasRawRequest: true,
    hasRawResponse: true,
    hasRawSse: true,
    blobStatus: 'ok',
  } satisfies LlmSpanSummary,
};

const childMeta: DescendantTraceMeta = {
  traceId: 'trace-child-1',
  sessionId: 'sess-child-1',
  depth: 1,
  parentTraceId: 'trace-parent',
  parentSpanId: 'tool-dispatch-1',
  agentName: 'reviewer',
  status: 'running',
  totalDurationMs: 500,
  toolCallCount: 1,
  eventCount: 0,
};

describe('NestedWaterfallRenderer', () => {
  it('full mode: renders parent span row, dispatch row with toggle, and child status badge', () => {
    const onSelect = vi.fn();
    const onToggle = vi.fn();
    render(
      <NestedWaterfallRenderer
        spans={[llmParent, dispatchTool, childSpan]}
        descendants={[childMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={onSelect}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={onToggle}
      />,
    );

    // Parent llm span visible.
    expect(screen.getByText('claude-sonnet-4-6')).toBeInTheDocument();
    // Dispatch tool span visible.
    expect(screen.getByText('TeamCreate')).toBeInTheDocument();
    // Toggle button (▶ collapsed).
    const toggle = screen.getByRole('button', { name: /Expand child trace/i });
    expect(toggle).toHaveTextContent('▶');
    // Status badge — full text "running".
    expect(screen.getByText('running')).toBeInTheDocument();
    // Child span hidden when collapsed (depth=1 not expanded).
    expect(screen.queryByText('claude-haiku-4-5')).not.toBeInTheDocument();
  });

  it('full mode: clicking toggle calls onToggleSubtree with child traceId', () => {
    const onToggle = vi.fn();
    render(
      <NestedWaterfallRenderer
        spans={[dispatchTool]}
        descendants={[childMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={onToggle}
      />,
    );
    const toggle = screen.getByRole('button', { name: /Expand child trace/i });
    fireEvent.click(toggle);
    expect(onToggle).toHaveBeenCalledWith('trace-child-1');
  });

  it('full mode: child span visible when expandedSubtrees contains its traceId', () => {
    render(
      <NestedWaterfallRenderer
        spans={[llmParent, dispatchTool, childSpan]}
        descendants={[childMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="full"
        expandedSubtrees={new Set(['trace-child-1'])}
        onToggleSubtree={vi.fn()}
      />,
    );
    // Now visible.
    expect(screen.getByText('claude-haiku-4-5')).toBeInTheDocument();
    // Toggle now shows ▼ (expanded).
    const toggle = screen.getByRole('button', { name: /Collapse child trace/i });
    expect(toggle).toHaveTextContent('▼');
  });

  it('mini mode: renders compact rows with status dot (no full text badge)', () => {
    render(
      <NestedWaterfallRenderer
        spans={[dispatchTool]}
        descendants={[childMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="mini"
        expandedSubtrees={new Set()}
        onToggleSubtree={vi.fn()}
      />,
    );
    // Mini renders the toggle as ▸ (closed).
    const toggle = screen.getByRole('button', { name: /Expand child trace/i });
    expect(toggle).toHaveTextContent('▸');
    // Status dot is rendered (aria-label='running'), not full badge text.
    expect(screen.getByLabelText('Child trace running')).toBeInTheDocument();
  });

  it('truncated=true triggers Show more → onLoadMore fires with parentTraceId', () => {
    const onLoadMore = vi.fn();
    render(
      <NestedWaterfallRenderer
        spans={[dispatchTool]}
        descendants={[childMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={vi.fn()}
        truncated={true}
        onLoadMore={onLoadMore}
      />,
    );
    const showMore = screen.getByRole('button', { name: /Show more descendants/i });
    fireEvent.click(showMore);
    // childMeta.parentTraceId === 'trace-parent'
    expect(onLoadMore).toHaveBeenCalledWith('trace-parent');
  });

  it('child status badge reflects descendant.status (running → ok)', () => {
    const okMeta: DescendantTraceMeta = { ...childMeta, status: 'ok' };
    const { rerender } = render(
      <NestedWaterfallRenderer
        spans={[dispatchTool]}
        descendants={[childMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={vi.fn()}
      />,
    );
    expect(screen.getByText('running')).toBeInTheDocument();

    // Simulate WS-driven cache mutation: descendants[0].status flips to 'ok'.
    rerender(
      <NestedWaterfallRenderer
        spans={[dispatchTool]}
        descendants={[okMeta]}
        totalMs={3000}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={vi.fn()}
      />,
    );
    expect(screen.queryByText('running')).not.toBeInTheDocument();
    expect(screen.getByText('ok')).toBeInTheDocument();
  });

  it('renders empty state when no spans and no rootRow', () => {
    render(
      <NestedWaterfallRenderer
        spans={[]}
        descendants={[]}
        totalMs={1}
        selectedSpanId={null}
        onSelectSpan={vi.fn()}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={vi.fn()}
      />,
    );
    expect(screen.getByText('No spans in this trace.')).toBeInTheDocument();
  });

  it('clicking a non-dispatch span row calls onSelectSpan with that span', () => {
    const onSelect = vi.fn();
    render(
      <NestedWaterfallRenderer
        spans={[llmParent]}
        descendants={[]}
        totalMs={1000}
        selectedSpanId={null}
        onSelectSpan={onSelect}
        mode="full"
        expandedSubtrees={new Set()}
        onToggleSubtree={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByText('claude-sonnet-4-6'));
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0][0].spanId).toBe('llm-1');
  });
});
