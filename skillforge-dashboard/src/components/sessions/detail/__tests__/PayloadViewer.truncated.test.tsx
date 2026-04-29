/**
 * OBS-1 R2-W6 — PayloadViewer with blob_status='truncated': button enabled,
 * after load an Alert displays the truncation notice.
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PayloadViewer from '../PayloadViewer';

void React;

describe('PayloadViewer (blob_status=truncated)', () => {
  it('shows alert about 50MB truncation immediately and still allows load', async () => {
    const loader = vi.fn().mockResolvedValue('TRUNCATED_PAYLOAD');
    render(
      <PayloadViewer
        title="原始请求 (Request)"
        summary='{"input":"big"}'
        blobStatus="truncated"
        hasBlob
        part="request"
        loadFullBlob={loader}
      />,
    );

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toMatch(/50MB.*截断/);

    const btn = screen.getByRole('button', { name: /Load full/i });
    expect(btn).toBeEnabled();
    fireEvent.click(btn);
    await waitFor(() => expect(loader).toHaveBeenCalledWith('request'));
  });
});
