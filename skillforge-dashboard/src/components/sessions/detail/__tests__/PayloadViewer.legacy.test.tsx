/**
 * OBS-1 R2-W6 — PayloadViewer with blob_status='legacy': button disabled +
 * tooltip explains historical session has no raw payload.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PayloadViewer from '../PayloadViewer';

void React;

describe('PayloadViewer (blob_status=legacy)', () => {
  it('disables load button with legacy tooltip and never invokes loader', () => {
    const loader = vi.fn();
    render(
      <PayloadViewer
        title="原始请求 (Request)"
        summary={null}
        blobStatus="legacy"
        hasBlob={false}
        part="request"
        loadFullBlob={loader}
      />,
    );

    const btn = screen.getByRole('button', { name: /Load full/i });
    expect(btn).toBeDisabled();
    expect(btn.getAttribute('title')).toContain('历史 session 无 raw payload');
    expect(screen.getByText(/legacy/i)).toBeInTheDocument();
    expect(loader).not.toHaveBeenCalled();
  });
});
