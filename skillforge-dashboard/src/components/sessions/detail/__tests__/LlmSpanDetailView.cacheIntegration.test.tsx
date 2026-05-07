/**
 * LlmSpanDetailView × CacheStatsRow integration test (PROMPT-CACHE-MVP r2 W2).
 *
 * Why this exists
 * ---------------
 * The unit tests for {@link CacheStatsRow} pass `cacheReadTokens` /
 * `cacheCreationTokens` / `nonCachedInputTokens` / `metadata` directly as
 * props — they verify the *rendering* contract, but cannot detect a
 * mismatch on the wire-level path:
 *
 *   `getLlmSpanDetail` → `LlmSpanDetail` shape → `LlmSpanDetailView` →
 *   `<CacheStatsRow ...spread />`
 *
 * r1 review B1 (wrong denominator) and B2 (`metadata.cache_break` field
 * never populated by BE) both slipped through the unit suite for exactly
 * that reason. This test mocks the BE response with realistic Anthropic
 * cache fields, renders `LlmSpanDetailView`, and asserts that:
 *
 *   - the hit-rate badge percentage is computed off the SUM of the three
 *     token buckets (B1 regression);
 *   - the green colour tag fires for high-hit scenarios that previously
 *     produced 16,000% bogus rates;
 *   - the red break badge fires when `metadata.cache_break === true`
 *     (B2 regression — would catch a drift to top-level `cacheBreak`
 *     field, since FE reads strictly from the `metadata` map).
 *
 * Mock strategy
 * -------------
 * `vi.mock` the `../../../api` module (same approach as
 * SpanDetailPanel.crossSpanIsolation.test.tsx). `useAuth` is unmocked —
 * its `createContext` default returns `userId: 1`, which is fine for the
 * query key. Wrap with `QueryClientProvider` configured with `retry: false`
 * to avoid cross-test bleed.
 */
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LlmSpanDetailView from '../LlmSpanDetailView';
import type { LlmSpanSummary, LlmSpanDetail } from '../../../../types/observability';

void React;

vi.mock('../../../../api', () => ({
  getLlmSpanDetail: vi.fn(),
  getLlmSpanBlob: vi.fn(),
}));

import { getLlmSpanDetail } from '../../../../api';

const baseSummary: LlmSpanSummary = {
  kind: 'llm',
  spanId: 'span-cache-1',
  traceId: 'trace-cache-1',
  parentSpanId: null,
  startedAt: '2026-05-08T10:00:00Z',
  endedAt: '2026-05-08T10:00:01Z',
  latencyMs: 1200,
  provider: 'claude',
  model: 'claude-sonnet-4-7',
  // Anthropic semantics: inputTokens is the *non-cached* portion only.
  inputTokens: 500,
  outputTokens: 250,
  source: 'live',
  stream: false,
  hasRawRequest: true,
  hasRawResponse: true,
  hasRawSse: false,
  blobStatus: 'ok',
  finishReason: 'end_turn',
  errorType: null,
};

const baseDetail: LlmSpanDetail = {
  spanId: baseSummary.spanId,
  traceId: baseSummary.traceId,
  parentSpanId: null,
  sessionId: 'sess-1',
  provider: 'claude',
  model: baseSummary.model,
  iterationIndex: 0,
  stream: false,
  inputSummary: 'INPUT',
  outputSummary: 'OUTPUT',
  cacheReadTokens: 80000,
  cacheCreationTokens: 0,
  usage: null,
  costUsd: null,
  latencyMs: baseSummary.latencyMs,
  startedAt: baseSummary.startedAt,
  endedAt: baseSummary.endedAt,
  finishReason: baseSummary.finishReason,
  reasoningContent: null,
  error: null,
  errorType: null,
  source: 'live',
  blobStatus: 'ok',
  blobs: { hasRawRequest: true, hasRawResponse: true, hasRawSse: false },
  metadata: null,
};

function renderWithQuery(span: LlmSpanSummary) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <LlmSpanDetailView span={span} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('LlmSpanDetailView × CacheStatsRow (wire-level integration)', () => {
  it('B1 regression: high-cache Anthropic scenario yields hit rate <= 100% (green)', async () => {
    // r1 B1 broken example: cacheRead=80k, nonCached(inputTokens)=500.
    // Expected: 80000 / (500 + 80000 + 0) ≈ 99.4%, green tag.
    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { ...baseDetail, cacheReadTokens: 80000, cacheCreationTokens: 0 },
    });

    const { container } = renderWithQuery(baseSummary);

    await waitFor(() => expect(screen.getByTestId('cache-stats-row')).toBeInTheDocument());

    const cacheRow = screen.getByTestId('cache-stats-row');
    expect(cacheRow.textContent).toContain('read 80,000');
    expect(cacheRow.textContent).toContain('write 0');
    // prompt = inputTokens + cacheRead + cacheCreation = 500 + 80000 + 0 = 80,500
    expect(cacheRow.textContent).toContain('prompt 80,500');

    const hitRow = screen.getByTestId('cache-hit-rate-row');
    const text = hitRow.textContent ?? '';
    const match = text.match(/(\d+\.\d+)%/);
    expect(match).toBeTruthy();
    const pct = parseFloat(match![1]);
    expect(pct).toBeGreaterThan(99);
    expect(pct).toBeLessThanOrEqual(100);

    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag!.className).toMatch(/ant-tag-green/);
  });

  it('shows a gold badge for a moderate-cache scenario (35%)', async () => {
    // 3500 / (5500 + 3500 + 1000) = 35.0%
    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { ...baseDetail, cacheReadTokens: 3500, cacheCreationTokens: 1000 },
    });

    const { container } = renderWithQuery({
      ...baseSummary,
      inputTokens: 5500,
    });

    await waitFor(() => expect(screen.getByTestId('cache-hit-rate-row')).toBeInTheDocument());

    const hitRow = screen.getByTestId('cache-hit-rate-row');
    expect(hitRow.textContent).toContain('35.0%');
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag!.className).toMatch(/ant-tag-gold/);
  });

  it('hides the hit-rate badge entirely on cold-cache calls (cacheRead=0)', async () => {
    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { ...baseDetail, cacheReadTokens: 0, cacheCreationTokens: 2000 },
    });

    renderWithQuery(baseSummary);

    await waitFor(() => expect(screen.getByTestId('cache-stats-row')).toBeInTheDocument());

    expect(screen.queryByTestId('cache-hit-rate-row')).toBeNull();
    // Cache write of 2,000 still shows in the numbers row.
    const cacheRow = screen.getByTestId('cache-stats-row');
    expect(cacheRow.textContent).toContain('write 2,000');
    // prompt = 500 + 0 + 2000 = 2,500
    expect(cacheRow.textContent).toContain('prompt 2,500');
  });

  it('falls back to 0 for spans persisted before V62 (legacy NULL cacheCreationTokens)', async () => {
    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockResolvedValue({
      // Simulate a legacy span: backend returns the field as null/missing.
      data: { ...baseDetail, cacheReadTokens: 5000, cacheCreationTokens: null },
    });

    renderWithQuery(baseSummary);

    await waitFor(() => expect(screen.getByTestId('cache-stats-row')).toBeInTheDocument());

    const cacheRow = screen.getByTestId('cache-stats-row');
    expect(cacheRow.textContent).toContain('write 0');
    // prompt = 500 + 5000 + 0 = 5,500
    expect(cacheRow.textContent).toContain('prompt 5,500');
  });

  it('B2 regression: renders the red break badge when metadata.cache_break === true', async () => {
    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        ...baseDetail,
        cacheReadTokens: 0,
        cacheCreationTokens: 8000,
        metadata: {
          cache_break: true,
          cache_break_reason: 'tool schema drifted (SHA mismatch on Bash tool)',
        },
      },
    });

    const { container } = renderWithQuery(baseSummary);

    await waitFor(() => expect(screen.getByTestId('cache-break-row')).toBeInTheDocument());

    const breakRow = screen.getByTestId('cache-break-row');
    expect(breakRow.textContent).toContain('cache broken at this turn');
    const tag = container.querySelector('[data-testid="cache-break-row"] .ant-tag');
    expect(tag).toBeTruthy();
    expect(tag!.className).toMatch(/ant-tag-red/);
  });

  it('omits the break badge when metadata is absent (default), even with cache fields', async () => {
    (getLlmSpanDetail as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { ...baseDetail, cacheReadTokens: 5000, metadata: null },
    });

    renderWithQuery(baseSummary);

    await waitFor(() => expect(screen.getByTestId('cache-stats-row')).toBeInTheDocument());

    expect(screen.queryByTestId('cache-break-row')).toBeNull();
  });
});
