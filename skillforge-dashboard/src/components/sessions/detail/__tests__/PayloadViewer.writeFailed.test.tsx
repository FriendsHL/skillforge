/**
 * OBS-1 R2-W6 — PayloadViewer with blob_status='write_failed': button disabled
 * with a different tooltip than legacy; clarifies live span data is in DB
 * summary even though blob write failed.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PayloadViewer from '../PayloadViewer';

void React;

describe('PayloadViewer (blob_status=write_failed)', () => {
  it('disables load button with write-failed tooltip, distinct from legacy', () => {
    const loader = vi.fn();
    render(
      <PayloadViewer
        title="原始请求 (Request)"
        summary='{"input":"hi"}'
        blobStatus="write_failed"
        hasBlob={false}
        part="request"
        loadFullBlob={loader}
      />,
    );

    const btn = screen.getByRole('button', { name: /Load full/i });
    expect(btn).toBeDisabled();
    const tooltip = btn.getAttribute('title') ?? '';
    expect(tooltip).toContain('blob 写入失败');
    expect(tooltip).not.toContain('历史 session');
    expect(screen.getByText(/write failed/i)).toBeInTheDocument();
    expect(loader).not.toHaveBeenCalled();
  });
});
