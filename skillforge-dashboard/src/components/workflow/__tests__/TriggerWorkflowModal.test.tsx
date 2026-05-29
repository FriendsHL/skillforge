/**
 * AUTOEVOLVING V1 Sprint 4 — TriggerWorkflowModal tests.
 *   1. lists registered workflows in the select
 *   2. pick + Run → runWorkflow(name, {args}) + onTriggered(runId)
 *   3. 409 → warning message
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// jsdom polyfills AntD Select / Modal touch.
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

const warningSpy = vi.fn();
const successSpy = vi.fn();
const errorSpy = vi.fn();
vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      ...actual.message,
      warning: (...a: unknown[]) => warningSpy(...a),
      success: (...a: unknown[]) => successSpy(...a),
      error: (...a: unknown[]) => errorSpy(...a),
      info: vi.fn(),
    },
  };
});

const listWorkflowsMock = vi.fn();
const runWorkflowMock = vi.fn();
vi.mock('../../../api/workflow', () => ({
  listWorkflows: (...a: unknown[]) => listWorkflowsMock(...a),
  runWorkflow: (...a: unknown[]) => runWorkflowMock(...a),
}));

import TriggerWorkflowModal from '../TriggerWorkflowModal';

const WORKFLOWS = {
  items: [
    { name: 'opt-report', description: 'Generate an opt report', phases: [{ title: 'Scan', detail: null }, { title: 'Approve', detail: null }] },
    { name: 'find-flaky', description: null, phases: [] },
  ],
  total: 2,
};

function renderModal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  const onTriggered = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <TriggerWorkflowModal open onClose={onClose} onTriggered={onTriggered} />
    </QueryClientProvider>,
  );
  return { onClose, onTriggered };
}

describe('TriggerWorkflowModal', () => {
  beforeEach(() => {
    listWorkflowsMock.mockReset();
    runWorkflowMock.mockReset();
    warningSpy.mockReset();
    successSpy.mockReset();
    errorSpy.mockReset();
    listWorkflowsMock.mockResolvedValue({ data: WORKFLOWS });
  });

  async function pickFirstWorkflow() {
    const combo = await waitFor(() => {
      const list = screen.getAllByRole('combobox');
      if (list.length < 1) throw new Error('select not ready');
      return list[0];
    });
    fireEvent.mouseDown(combo);
    const opt = await screen.findByText('opt-report', {
      selector: '.ant-select-item-option-content',
    });
    fireEvent.click(opt);
  }

  it('lists registered workflows', async () => {
    renderModal();
    await pickFirstWorkflow();
    // Phase preview renders after selection.
    expect(await screen.findByText(/Scan → Approve/)).toBeInTheDocument();
  });

  it('Run calls runWorkflow + onTriggered', async () => {
    runWorkflowMock.mockResolvedValue({ data: { runId: 'run-xyz', name: 'opt-report', status: 'pending' } });
    const { onTriggered } = renderModal();
    await pickFirstWorkflow();
    fireEvent.click(screen.getByRole('button', { name: /^Run$/ }));
    await waitFor(() => expect(runWorkflowMock).toHaveBeenCalledTimes(1));
    expect(runWorkflowMock).toHaveBeenCalledWith('opt-report', {});
    await waitFor(() => expect(onTriggered).toHaveBeenCalledWith('run-xyz'));
    expect(successSpy).toHaveBeenCalled();
  });

  it('rejects non-object args without firing runWorkflow', async () => {
    renderModal();
    await pickFirstWorkflow();
    fireEvent.change(screen.getByTestId('wf-trigger-args'), {
      target: { value: '42' },
    });
    fireEvent.click(screen.getByRole('button', { name: /^Run$/ }));
    await waitFor(() => expect(errorSpy).toHaveBeenCalled());
    expect(runWorkflowMock).not.toHaveBeenCalled();
  });

  it('409 surfaces a warning', async () => {
    runWorkflowMock.mockRejectedValue({ isAxiosError: true, response: { status: 409 }, message: 'conflict' });
    renderModal();
    await pickFirstWorkflow();
    fireEvent.click(screen.getByRole('button', { name: /^Run$/ }));
    await waitFor(() => expect(warningSpy).toHaveBeenCalled());
  });
});
