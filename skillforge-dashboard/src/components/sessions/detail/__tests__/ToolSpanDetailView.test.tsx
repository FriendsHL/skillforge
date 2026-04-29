/**
 * OBS-1 R2-B1 — ToolSpanDetailView renders tool name / status / input /
 * output for a generic (non-SubAgent) tool span.
 */
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ToolSpanSummary, ToolSpanDetail } from '../../../../types/observability';

const getToolSpanDetailMock = vi.fn();
vi.mock('../../../../api', () => ({
  getToolSpanDetail: (spanId: string) => getToolSpanDetailMock(spanId),
}));

import ToolSpanDetailView from '../ToolSpanDetailView';

function renderWithProviders(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={qc}>{ui}</QueryClientProvider>
    </MemoryRouter>,
  );
}

const summary: ToolSpanSummary = {
  kind: 'tool',
  spanId: 'span-tool-1',
  traceId: 'trace-1',
  parentSpanId: null,
  startedAt: '2026-04-29T00:00:00.000Z',
  endedAt: '2026-04-29T00:00:01.000Z',
  latencyMs: 1000,
  toolName: 'Bash',
  toolUseId: 'tu-1',
  success: true,
};

const detail: ToolSpanDetail = {
  spanId: 'span-tool-1',
  traceId: 'trace-1',
  parentSpanId: null,
  sessionId: 'sess-1',
  toolName: 'Bash',
  toolUseId: 'tu-1',
  startedAt: '2026-04-29T00:00:00.000Z',
  endedAt: '2026-04-29T00:00:01.000Z',
  latencyMs: 1000,
  iterationIndex: 0,
  success: true,
  input: 'ls -la',
  output: 'total 0\ndrwxr-xr-x  2 user  staff  64 Jan 1 ./',
};

describe('ToolSpanDetailView (generic tool)', () => {
  it('renders tool name, status ok, input and output text', async () => {
    getToolSpanDetailMock.mockResolvedValueOnce({ data: detail });
    renderWithProviders(<ToolSpanDetailView span={summary} />);

    await waitFor(() => {
      // tool name in meta block
      const bashes = screen.getAllByText('Bash');
      expect(bashes.length).toBeGreaterThan(0);
    });
    expect(screen.getByText('ok')).toBeInTheDocument();
    expect(screen.getByText('ls -la')).toBeInTheDocument();
    expect(screen.getByText(/total 0/)).toBeInTheDocument();
    // No SubAgent jump for non-SubAgent tools.
    expect(screen.queryByRole('button', { name: /Jump to child session/ })).toBeNull();
  });
});
