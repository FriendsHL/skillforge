import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

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
const getComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element) => getComputedStyle(element);

const getChatAttachmentBlobMock = vi.fn();
vi.mock('../../api', () => ({
  getChatAttachmentBlob: (...args: unknown[]) => getChatAttachmentBlobMock(...args),
}));

import PersonalAppAttachment, {
  buildPersonalAppDocument,
  safeExternalURL,
} from '../PersonalAppAttachment';

describe('PersonalAppAttachment', () => {
  beforeEach(() => {
    getChatAttachmentBlobMock.mockReset();
    getChatAttachmentBlobMock.mockResolvedValue({
      data: {
        text: () => Promise.resolve(
          '<!doctype html><body><button data-sf-url="https://example.com/source">Source</button></body>',
        ),
      },
    });
  });

  it('injects the source-link bridge without granting same-origin or navigation capability', () => {
    const document = buildPersonalAppDocument(
      '<!doctype html><body><button data-sf-url="https://example.com/source">Source</button></body>',
    );

    expect(document).toContain("closest('[data-sf-url]')");
    expect(document).toContain("type:'open-external-url'");
    expect(document.indexOf('open-external-url')).toBeLessThan(document.indexOf('</body>'));
  });

  it('rejects unsafe external URL schemes and credential-bearing URLs', () => {
    expect(safeExternalURL('javascript:alert(1)')).toBeNull();
    expect(safeExternalURL('data:text/html,<script>alert(1)</script>')).toBeNull();
    expect(safeExternalURL('file:///etc/passwd')).toBeNull();
    expect(safeExternalURL('https://user:pass@example.com/private')).toBeNull();
    expect(safeExternalURL('https://example.com/source')).toBe('https://example.com/source');
  });

  it('ignores bridge messages that did not come from the active Personal App frame', async () => {
    render(
      <PersonalAppAttachment
        attachmentId="artifact-foreign-source"
        filename="brief.html"
        userId={7}
        sessionId="session-1"
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Open Personal App' }));
    await screen.findByTestId('personal-app-frame');
    act(() => {
      window.dispatchEvent(new MessageEvent('message', {
        source: window,
        data: {
          source: 'skillforge-personal-app',
          type: 'open-external-url',
          url: 'https://example.com/forged',
        },
      }));
    });

    expect(screen.queryByText('Open external link?')).not.toBeInTheDocument();
  });

  it('renders a sandboxed desktop viewer and confirms source links before opening', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    render(
      <PersonalAppAttachment
        attachmentId="artifact-1"
        filename="brief.html"
        title="Podcast brief"
        userId={7}
        sessionId="session-1"
      />,
    );

    const openButton = await screen.findByRole('button', { name: 'Open Personal App' });
    fireEvent.click(openButton);
    const frame = await screen.findByTestId('personal-app-frame') as HTMLIFrameElement;
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts');
    expect(frame.getAttribute('sandbox')).not.toContain('allow-same-origin');

    act(() => {
      window.dispatchEvent(new MessageEvent('message', {
        source: frame.contentWindow,
        data: {
          source: 'skillforge-personal-app',
          type: 'open-external-url',
          url: 'https://example.com/source',
        },
      }));
    });

    expect(await screen.findByText('Open external link?')).toBeInTheDocument();
    expect(openSpy).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    await waitFor(() => {
      expect(openSpy).toHaveBeenCalledWith(
        'https://example.com/source', '_blank', 'noopener,noreferrer',
      );
    });
    openSpy.mockRestore();
  });
});
