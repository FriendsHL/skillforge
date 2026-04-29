/**
 * OBS-1 — PayloadViewer happy path: blob_status='ok' summary + button click
 * loads full payload via the loader prop.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PayloadViewer from '../PayloadViewer';

// Suppress unused warning for React in JSX-runtime mode.
void React;

describe('PayloadViewer (blob_status=ok)', () => {
  it('renders summary, enables load buttons, loads full blob on click', async () => {
    const loader = vi.fn().mockResolvedValue('{"hello":"world"}');
    render(
      <PayloadViewer
        title="原始请求 (Request)"
        summary='{"input":"hello"}'
        blobStatus="ok"
        hasBlob
        part="request"
        loadFullBlob={loader}
      />,
    );

    expect(screen.getByText(/{"input":"hello"}/)).toBeInTheDocument();
    const btn = screen.getByRole('button', { name: /Load full/i });
    expect(btn).toBeEnabled();
    fireEvent.click(btn);

    await waitFor(() => expect(loader).toHaveBeenCalledWith('request'));
    await waitFor(() => expect(screen.getByText('已加载')).toBeInTheDocument());
  });
});
