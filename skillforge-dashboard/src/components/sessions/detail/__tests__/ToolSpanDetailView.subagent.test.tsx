/**
 * OBS-1 R3-WN2 — ToolSpanDetailView renders SubagentJumpLink ONLY when
 * toolName='SubAgent' AND backend resolved subagentSessionId. Other tools
 * (or SubAgent without subagentSessionId) must not render the link.
 */
import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ToolSpanSummary, ToolSpanDetail } from '../../../../types/observability';

const getToolSpanDetailMock = vi.fn();
vi.mock('../../../../api', () => ({
  getToolSpanDetail: (spanId: string) => getToolSpanDetailMock(spanId),
}));

import ToolSpanDetailView from '../ToolSpanDetailView';

function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname}</div>;
}

function renderWithProviders(span: ToolSpanSummary) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={['/sessions/parent']}>
      <QueryClientProvider client={qc}>
        <Routes>
          <Route
            path="/sessions/:id"
            element={
              <>
                <ToolSpanDetailView span={span} />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const baseSummary: ToolSpanSummary = {
  kind: 'tool',
  spanId: 'span-sa-1',
  traceId: 'trace-1',
  parentSpanId: null,
  startedAt: '2026-04-29T00:00:00.000Z',
  endedAt: '2026-04-29T00:00:01.000Z',
  latencyMs: 1000,
  toolName: 'SubAgent',
  toolUseId: 'tu-1',
  success: true,
};

const baseDetail: ToolSpanDetail = {
  spanId: 'span-sa-1',
  traceId: 'trace-1',
  parentSpanId: null,
  sessionId: 'sess-1',
  toolName: 'SubAgent',
  toolUseId: 'tu-1',
  startedAt: '2026-04-29T00:00:00.000Z',
  endedAt: '2026-04-29T00:00:01.000Z',
  latencyMs: 1000,
  iterationIndex: 0,
  success: true,
  input: 'do thing',
  output: '  childSessionId: 11111111-2222-3333-4444-555555555555\n',
};

describe('ToolSpanDetailView (SubAgent rendering)', () => {
  it('renders SubagentJumpLink and navigates when subagentSessionId is set', async () => {
    const childId = '11111111-2222-3333-4444-555555555555';
    getToolSpanDetailMock.mockResolvedValueOnce({
      data: { ...baseDetail, subagentSessionId: childId },
    });
    renderWithProviders({ ...baseSummary, subagentSessionId: childId });

    const link = await screen.findByRole('button', {
      name: /Jump to child session/,
    });
    fireEvent.click(link);
    expect(screen.getByTestId('loc').textContent).toBe(`/sessions/${childId}`);
  });

  it('does NOT render SubagentJumpLink when subagentSessionId is missing', async () => {
    getToolSpanDetailMock.mockResolvedValueOnce({
      data: { ...baseDetail, subagentSessionId: null },
    });
    renderWithProviders({ ...baseSummary, subagentSessionId: null });

    await waitFor(() => {
      expect(screen.getByText('do thing')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /Jump to child session/ })).toBeNull();
  });

  it('does NOT render SubagentJumpLink for non-SubAgent tools', async () => {
    getToolSpanDetailMock.mockResolvedValueOnce({
      data: { ...baseDetail, toolName: 'Bash', subagentSessionId: null },
    });
    renderWithProviders({ ...baseSummary, toolName: 'Bash', subagentSessionId: null });

    await waitFor(() => {
      // Tool name should appear in detail
      const bashes = screen.getAllByText('Bash');
      expect(bashes.length).toBeGreaterThan(0);
    });
    expect(screen.queryByRole('button', { name: /Jump to child session/ })).toBeNull();
  });
});
