/**
 * OBS-1 Phase 3 R2 fix verification — FE-B1.
 *
 * Verifies that switching the `span` prop on `SpanDetailPanel` between two LLM spans
 * remounts the inner detail view (via React `key={span.spanId}`), so that
 * `PayloadViewer`'s internal `blob` state from span A does NOT bleed into span B.
 *
 * Without `key=`, the React reconciler reuses the same component instance at the same
 * tree position; `useState({...})` is preserved; spanA's loaded payload text would
 * remain visible while spanB's meta header changes — a silent "wrong data" defect.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SpanDetailPanel from '../SpanDetailPanel';
import type { LlmSpanSummary } from '../../../../types/observability';

void React;


vi.mock('../../../../api', () => ({
  getLlmSpanDetail: vi.fn(),
  getLlmSpanBlob: vi.fn(),
  getToolSpanDetail: vi.fn(),
}));

import { getLlmSpanDetail, getLlmSpanBlob } from '../../../../api';

const makeSpan = (id: string, model: string): LlmSpanSummary => ({
  kind: 'llm',
  spanId: id,
  traceId: `trace-${id}`,
  parentSpanId: null,
  startedAt: '2026-04-29T10:00:00Z',
  endedAt: '2026-04-29T10:00:01Z',
  latencyMs: 1000,
  model,
  provider: 'claude',
  inputTokens: 100,
  outputTokens: 200,
  cacheReadTokens: 0,
  costUsd: 0.001,
  source: 'live',
  finishReason: 'stop',
  errorType: null,
});

const detailFor = (span: LlmSpanSummary) => ({
  data: {
    spanId: span.spanId,
    traceId: span.traceId,
    provider: span.provider,
    model: span.model,
    latencyMs: span.latencyMs,
    cacheReadTokens: 0,
    finishReason: span.finishReason,
    errorType: null,
    error: null,
    inputSummary: `INPUT-${span.spanId}`,
    outputSummary: `OUTPUT-${span.spanId}`,
    reasoningContent: null,
    blobStatus: 'ok' as const,
    blobs: { hasRawRequest: true, hasRawResponse: true, hasRawSse: false },
    stream: false,
    usage: null,
    source: 'live' as const,
  },
});

describe('SpanDetailPanel cross-span isolation (FE-B1)', () => {
  it('remounts LlmSpanDetailView on span switch so PayloadViewer state resets', async () => {
    const spanA = makeSpan('span-a', 'claude-sonnet-4-6');
    const spanB = makeSpan('span-b', 'claude-opus-4-7');

    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockImplementation((spanId: string) =>
      Promise.resolve(
        detailFor(spanId === 'span-a' ? spanA : spanB),
      ),
    );
    (getLlmSpanBlob as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: '{"loaded":"from spanA"}',
    });

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <SpanDetailPanel span={spanA} />
      </QueryClientProvider>,
    );

    // Wait for spanA detail to be fetched + rendered.
    await waitFor(() => expect(screen.getByText('INPUT-span-a')).toBeInTheDocument());

    // Click "load full payload" button in the request PayloadViewer.
    const requestSection = screen.getByTestId('llm-payload-request');
    const loadBtn = within(requestSection).getByRole('button', { name: /Load full/ });
    fireEvent.click(loadBtn);
    await waitFor(() =>
      expect(within(requestSection).getByText(/from spanA/)).toBeInTheDocument(),
    );
    // After loading, the load button transitions to disabled "已加载" state.
    expect(within(requestSection).getByRole('button', { name: /Load full/ })).toBeDisabled();

    // Switch to spanB — without `key={span.spanId}` the PayloadViewer instance would
    // be reused and still display "from spanA" while showing spanB's meta header.
    rerender(
      <QueryClientProvider client={client}>
        <SpanDetailPanel span={spanB} />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByText('INPUT-span-b')).toBeInTheDocument());

    // The remounted PayloadViewer must have fresh state: load button enabled again,
    // no spanA payload text visible.
    const newRequestSection = screen.getByTestId('llm-payload-request');
    expect(within(newRequestSection).queryByText(/from spanA/)).not.toBeInTheDocument();
    const newLoadBtn = within(newRequestSection).getByRole('button', { name: /Load full/ });
    expect(newLoadBtn).not.toBeDisabled();
  });
});
