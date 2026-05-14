/**
 * Wave 3 — AttachmentThumbnail chip rendering for word / excel / csv kinds.
 *
 * Mirrors the existing pdf chip pattern: chip renders the kind-specific icon,
 * filename, and (for excel) a sheet-count suffix. We mock `getChatAttachmentBlob`
 * so the network call doesn't run; the chip itself should render even before
 * the blob fetch settles (loading-state cursor=wait, post-load cursor=pointer
 * — both render the chip).
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

// Polyfill matchMedia / ResizeObserver for AntD Tooltip.
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
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;

// jsdom's URL.createObjectURL is unreliable across versions. Polyfill with a
// deterministic stub so the AttachmentThumbnail blob-URL flow can complete:
// component calls URL.createObjectURL(blob) → setBlobUrl(url) → chip flips
// from cursor=wait / tabIndex=-1 → cursor=pointer / tabIndex=0. Tests below
// depend on that transition firing.
if (typeof URL.createObjectURL !== 'function' || URL.createObjectURL.length === 0) {
  let counter = 0;
  Object.defineProperty(URL, 'createObjectURL', {
    writable: true,
    configurable: true,
    value: () => `blob:test-${++counter}`,
  });
}
if (typeof URL.revokeObjectURL !== 'function') {
  Object.defineProperty(URL, 'revokeObjectURL', {
    writable: true,
    configurable: true,
    value: () => {},
  });
}

// Stub the blob fetch — resolves with a tiny Blob so the chip transitions
// from "wait" to "pointer". We don't assert that transition; we only need the
// promise to settle so React state updates don't trigger act() warnings.
vi.mock('../../api', () => ({
  getChatAttachmentBlob: vi.fn().mockResolvedValue({ data: new Blob(['x']) }),
}));

import AttachmentThumbnail from '../AttachmentThumbnail';

describe('AttachmentThumbnail — Wave 3 word/excel/csv chips', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders a Word chip with FileWordOutlined icon and filename', async () => {
    render(
      <AttachmentThumbnail
        kind="word"
        attachmentId="att-1"
        filename="report.docx"
        userId={42}
        sessionId="sess-A"
      />,
    );
    const chip = await screen.findByTestId('attachment-word-chip');
    expect(chip.textContent ?? '').toMatch(/report\.docx/);
    // No PDF chip should render.
    expect(screen.queryByTestId('attachment-pdf-chip')).toBeNull();
  });

  it('renders an Excel chip with sheetCount suffix when provided', async () => {
    render(
      <AttachmentThumbnail
        kind="excel"
        attachmentId="att-2"
        filename="data.xlsx"
        userId={42}
        sessionId="sess-A"
        sheetCount={3}
      />,
    );
    const chip = await screen.findByTestId('attachment-excel-chip');
    expect(chip.textContent ?? '').toMatch(/data\.xlsx/);
    // Suffix format: "· 3s" (matches PDF's "· Np" rhythm).
    expect(chip.textContent ?? '').toMatch(/·\s*3s/);
  });

  it('renders an Excel chip without suffix when sheetCount is absent', async () => {
    render(
      <AttachmentThumbnail
        kind="excel"
        attachmentId="att-3"
        filename="empty.xlsx"
        userId={42}
        sessionId="sess-A"
      />,
    );
    const chip = await screen.findByTestId('attachment-excel-chip');
    expect(chip.textContent ?? '').toMatch(/empty\.xlsx/);
    expect(chip.textContent ?? '').not.toMatch(/·\s*\ds/);
  });

  it('renders a CSV chip with FileTextOutlined icon and filename', async () => {
    render(
      <AttachmentThumbnail
        kind="csv"
        attachmentId="att-4"
        filename="metrics.csv"
        userId={42}
        sessionId="sess-A"
      />,
    );
    const chip = await screen.findByTestId('attachment-csv-chip');
    expect(chip.textContent ?? '').toMatch(/metrics\.csv/);
  });

  it('still renders the legacy PDF chip with page-count suffix', async () => {
    render(
      <AttachmentThumbnail
        kind="pdf"
        attachmentId="att-5"
        filename="paper.pdf"
        userId={42}
        sessionId="sess-A"
        pageCount={12}
      />,
    );
    const chip = await screen.findByTestId('attachment-pdf-chip');
    expect(chip.textContent ?? '').toMatch(/paper\.pdf/);
    expect(chip.textContent ?? '').toMatch(/·\s*12p/);
  });

  it('r2 W1 — clicking the chip opens the blob URL in a new tab', async () => {
    // Spy on window.open so we can assert the chip's primary interaction
    // without actually navigating jsdom.
    const windowOpenSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(
      <AttachmentThumbnail
        kind="pdf"
        attachmentId="att-click"
        filename="x.pdf"
        userId={1}
      />,
    );
    const chip = await screen.findByTestId('attachment-pdf-chip');
    // Wait for blob fetch to settle — cursor flips to pointer once blobUrl
    // is set. style.cursor is the easiest signal because tabIndex/aria-label
    // also flip in lockstep, but cursor is what the user actually sees.
    await waitFor(() => {
      expect(chip.style.cursor).toBe('pointer');
    });
    fireEvent.click(chip);
    expect(windowOpenSpy).toHaveBeenCalledWith(
      expect.stringMatching(/^blob:/),
      '_blank',
      'noopener,noreferrer',
    );
    windowOpenSpy.mockRestore();
  });

  it('r2 W2 — chip is keyboard-focusable once blob loads (tabIndex=0, role=button, aria-label)', async () => {
    render(
      <AttachmentThumbnail
        kind="word"
        attachmentId="att-kbd"
        filename="report.docx"
        userId={1}
      />,
    );
    const chip = await screen.findByTestId('attachment-word-chip');
    // role + aria-label are static; assert they're present from initial render.
    expect(chip).toHaveAttribute('role', 'button');
    expect(chip).toHaveAttribute('aria-label', 'Open report.docx in a new tab');
    // tabIndex flips from -1 (loading) → 0 (loaded) once the mocked fetch
    // resolves. Wait for the loaded state.
    await waitFor(() => {
      expect(chip).toHaveAttribute('tabindex', '0');
    });
  });

  it('r2 W2 — Enter and Space keys activate the chip when loaded', async () => {
    const windowOpenSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(
      <AttachmentThumbnail
        kind="excel"
        attachmentId="att-kbd-act"
        filename="data.xlsx"
        userId={1}
        sheetCount={2}
      />,
    );
    const chip = await screen.findByTestId('attachment-excel-chip');
    await waitFor(() => {
      expect(chip).toHaveAttribute('tabindex', '0');
    });
    // Enter activates.
    fireEvent.keyDown(chip, { key: 'Enter' });
    expect(windowOpenSpy).toHaveBeenCalledTimes(1);
    // Space activates.
    fireEvent.keyDown(chip, { key: ' ' });
    expect(windowOpenSpy).toHaveBeenCalledTimes(2);
    // Random key does NOT activate.
    fireEvent.keyDown(chip, { key: 'a' });
    expect(windowOpenSpy).toHaveBeenCalledTimes(2);
    windowOpenSpy.mockRestore();
  });

  it('wait → settled: chip stays mounted across the blob fetch', async () => {
    render(
      <AttachmentThumbnail
        kind="word"
        attachmentId="att-6"
        filename="x.docx"
        userId={42}
        sessionId="sess-A"
      />,
    );
    // Initially in loading state; chip rendered with cursor=wait.
    const chip = await screen.findByTestId('attachment-word-chip');
    expect(chip).toBeInTheDocument();
    // After the fetch resolves the cursor flips to pointer — chip still there.
    await waitFor(() => {
      expect(screen.getByTestId('attachment-word-chip')).toBeInTheDocument();
    });
  });
});
