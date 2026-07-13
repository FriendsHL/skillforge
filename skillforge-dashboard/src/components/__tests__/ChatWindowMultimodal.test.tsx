/**
 * MULTIMODAL-MVP frontend coverage (redesign 2026-05-14):
 *   - Attach button is **visible but disabled** when activeAgent.modelId is
 *     not vision-capable; tooltip copy + jump link are present.
 *   - Attach button is **enabled** when the main model is vision-capable
 *     (caller passes multimodalEnabled=true derived from the LLM model list).
 *   - Clicking the disabled button does NOT trigger the hidden file input.
 *   - sessionResetKey change clears in-flight file picks.
 *
 * We import the inner `ChatInput` rather than `ChatWindow` to avoid the
 * thread/scroll/markdown deps; the gating logic lives entirely in `ChatInput`.
 * The derivation `multimodalEnabled = visionCapableSet.has(agent.modelId)`
 * happens in `Chat.tsx`; ChatInput just consumes the boolean.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';

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

// Polyfill ResizeObserver (AntD Tooltip overlay uses it for positioning).
class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;

vi.mock('../../api/commands', () => ({
  executeCommand: vi.fn(),
}));

vi.mock('../AttachmentThumbnail', () => ({
  default: ({ filename, caption }: { filename: string; caption?: string }) => (
    <div data-testid="rendered-attachment">
      {filename}
      {caption ? `: ${caption}` : ''}
    </div>
  ),
}));

const errorSpy = vi.fn();
vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      success: vi.fn(),
      error: (...args: unknown[]) => errorSpy(...args),
      info: vi.fn(),
      warning: vi.fn(),
    },
  };
});

import ChatWindow, { ChatInput } from '../ChatWindow';

interface Handlers {
  onSend: ReturnType<typeof vi.fn>;
  onOpenAgentConfig: ReturnType<typeof vi.fn>;
}

function renderInput(props: {
  multimodalEnabled?: boolean;
  sessionResetKey?: string;
} = {}) {
  const handlers: Handlers = {
    onSend: vi.fn(),
    onOpenAgentConfig: vi.fn(),
  };
  const utils = render(
    <ChatInput
      onSend={handlers.onSend}
      multimodalEnabled={props.multimodalEnabled}
      onOpenAgentConfig={handlers.onOpenAgentConfig}
      sessionResetKey={props.sessionResetKey}
    />,
  );
  return { ...utils, handlers };
}

describe('ChatInput — MULTIMODAL-MVP attach button gate', () => {
  beforeEach(() => {
    errorSpy.mockClear();
  });
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders attach button DISABLED (aria-disabled) with tooltip copy when multimodal is not configured', async () => {
    renderInput({ multimodalEnabled: false });

    const btn = screen.getByTestId('chat-attach-button') as HTMLButtonElement;
    // Visible but disabled — see PRD Ratify #6 ("不采用『隐藏按钮』").
    // r2 W4: a11y — uses aria-disabled (not native `disabled`) so keyboard
    // users can still focus the button to discover the gate tooltip.
    expect(btn).toBeInTheDocument();
    expect(btn).toHaveAttribute('aria-disabled', 'true');
    // Native `disabled` must NOT be set (would remove from tab order).
    expect(btn.disabled).toBe(false);

    // AntD Tooltip mounts the overlay lazily on hover; trigger it.
    // jsdom can be flaky with single mouseEnter — fire both pointer + mouse
    // and wait up to 3s for the portal to settle.
    await act(async () => {
      fireEvent.pointerEnter(btn);
      fireEvent.mouseEnter(btn);
      fireEvent.mouseOver(btn);
    });
    const content = await screen.findByTestId(
      'multimodal-tooltip-content',
      {},
      { timeout: 3000 },
    );
    expect(content.textContent ?? '').toMatch(/请把 agent 的主模型切换为多模态模型/);
  });

  it('clicking the aria-disabled attach button does NOT open the file picker', async () => {
    const { handlers } = renderInput({ multimodalEnabled: false });
    const btn = screen.getByTestId('chat-attach-button') as HTMLButtonElement;
    const fileInput = screen.getByTestId('chat-attach-file-input') as HTMLInputElement;
    const clickSpy = vi.spyOn(fileInput, 'click');

    // r2 W4: With native `disabled` removed, only the onClick guard prevents
    // the picker. This is the test that proves the guard actually works —
    // critical now that the browser no longer suppresses clicks for us.
    fireEvent.click(btn);
    expect(clickSpy).not.toHaveBeenCalled();
    expect(handlers.onOpenAgentConfig).not.toHaveBeenCalled();
  });

  it('renders attach button ENABLED when multimodal is configured', () => {
    renderInput({ multimodalEnabled: true });
    const btn = screen.getByTestId('chat-attach-button') as HTMLButtonElement;
    // r2 W4 — enabled state asserts aria-disabled=false; native disabled is
    // still absent (we never set it now).
    expect(btn).toHaveAttribute('aria-disabled', 'false');
    expect(btn.disabled).toBe(false);
  });

  it('tooltip link click invokes onOpenAgentConfig', async () => {
    const { handlers } = renderInput({ multimodalEnabled: false });
    const btn = screen.getByTestId('chat-attach-button') as HTMLButtonElement;
    await act(async () => {
      fireEvent.pointerEnter(btn);
      fireEvent.mouseEnter(btn);
      fireEvent.mouseOver(btn);
    });
    const link = await screen.findByTestId(
      'multimodal-tooltip-link',
      {},
      { timeout: 3000 },
    );
    fireEvent.click(link);
    expect(handlers.onOpenAgentConfig).toHaveBeenCalledTimes(1);
  });

  it('flushes in-flight file picks when sessionResetKey changes', async () => {
    const handlers: Handlers = {
      onSend: vi.fn(),
      onOpenAgentConfig: vi.fn(),
    };
    const { rerender } = render(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={true}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-A"
      />,
    );
    const fileInput = screen.getByTestId('chat-attach-file-input') as HTMLInputElement;
    const file = new File(['payload'], 'x.png', { type: 'image/png' });
    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [file] } });
    });
    // Chip rendered.
    await waitFor(() => {
      expect(screen.getByLabelText('Remove x.png')).toBeInTheDocument();
    });
    // Switch session — chip must disappear.
    rerender(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={true}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-B"
      />,
    );
    await waitFor(() => {
      expect(screen.queryByLabelText('Remove x.png')).toBeNull();
    });
  });

  it('r2 W1 — attachment chip renders filename + type badge + formatted size', async () => {
    const handlers: Handlers = {
      onSend: vi.fn(),
      onOpenAgentConfig: vi.fn(),
    };
    render(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={true}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-A"
      />,
    );
    const fileInput = screen.getByTestId('chat-attach-file-input') as HTMLInputElement;
    // 4096 bytes → exactly "4 KB" (formatBytes uses toFixed(0) on KB).
    const bytes = new Uint8Array(4096);
    const png = new File([bytes], 'screenshot.png', { type: 'image/png' });
    const pdf = new File([new Uint8Array(2 * 1024 * 1024)], 'paper.pdf', {
      type: 'application/pdf',
    });
    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [png, pdf] } });
    });

    // Two chips with the new metadata fields (PRD: filename + 类型 + 大小 + remove).
    const chips = await screen.findAllByTestId('attachment-chip');
    expect(chips).toHaveLength(2);

    // PNG chip: kind=IMG, name=screenshot.png, size=4 KB.
    expect(chips[0].textContent ?? '').toMatch(/IMG/);
    expect(chips[0].textContent ?? '').toMatch(/screenshot\.png/);
    expect(chips[0].textContent ?? '').toMatch(/4 KB/);

    // PDF chip: kind=PDF, name=paper.pdf, size=2.0 MB.
    expect(chips[1].textContent ?? '').toMatch(/PDF/);
    expect(chips[1].textContent ?? '').toMatch(/paper\.pdf/);
    expect(chips[1].textContent ?? '').toMatch(/2\.0 MB/);

    // Remove button still works (regression — chip restructure preserved
    // the remove affordance).
    const removeBtn = screen.getByLabelText('Remove screenshot.png');
    fireEvent.click(removeBtn);
    await waitFor(() => {
      expect(screen.queryByLabelText('Remove screenshot.png')).toBeNull();
    });
  });

  it('Wave 3 — accepts .docx file, rejects unsupported (.mp3) type', async () => {
    const handlers: Handlers = {
      onSend: vi.fn(),
      onOpenAgentConfig: vi.fn(),
    };
    render(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={true}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-A"
      />,
    );
    const fileInput = screen.getByTestId('chat-attach-file-input') as HTMLInputElement;
    // .docx: canonical OpenXML word mime
    const docx = new File([new Uint8Array(1024)], 'report.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
    // .mp3: unsupported, should be filtered out
    const mp3 = new File([new Uint8Array(2048)], 'song.mp3', { type: 'audio/mpeg' });
    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [docx, mp3] } });
    });
    // Only the docx chip should appear; mp3 dropped silently with warning.
    const chips = await screen.findAllByTestId('attachment-chip');
    expect(chips).toHaveLength(1);
    expect(chips[0].textContent ?? '').toMatch(/DOC/);
    expect(chips[0].textContent ?? '').toMatch(/report\.docx/);
  });

  it('Wave 3 — accepts .xlsx and .csv files (including ms-excel-mime csv fallback)', async () => {
    const handlers: Handlers = {
      onSend: vi.fn(),
      onOpenAgentConfig: vi.fn(),
    };
    render(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={true}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-A"
      />,
    );
    const fileInput = screen.getByTestId('chat-attach-file-input') as HTMLInputElement;
    const xlsx = new File([new Uint8Array(2048)], 'data.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const csv = new File([new Uint8Array(512)], 'data.csv', { type: 'text/csv' });
    // Windows fallback: some browsers report empty mime for .csv — must still
    // pass via filename extension fallback.
    const csvNoMime = new File([new Uint8Array(256)], 'legacy.csv', { type: '' });
    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [xlsx, csv, csvNoMime] } });
    });
    const chips = await screen.findAllByTestId('attachment-chip');
    expect(chips).toHaveLength(3);
    expect(chips[0].textContent ?? '').toMatch(/XLS/);
    expect(chips[0].textContent ?? '').toMatch(/data\.xlsx/);
    expect(chips[1].textContent ?? '').toMatch(/CSV/);
    expect(chips[1].textContent ?? '').toMatch(/data\.csv/);
    expect(chips[2].textContent ?? '').toMatch(/CSV/);
    expect(chips[2].textContent ?? '').toMatch(/legacy\.csv/);
  });

  it('Send is disabled when files are queued but multimodal becomes unavailable', async () => {
    const handlers: Handlers = {
      onSend: vi.fn(),
      onOpenAgentConfig: vi.fn(),
    };
    const { rerender } = render(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={true}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-A"
      />,
    );
    const fileInput = screen.getByTestId('chat-attach-file-input') as HTMLInputElement;
    const file = new File(['x'], 'a.png', { type: 'image/png' });
    await act(async () => {
      fireEvent.change(fileInput, { target: { files: [file] } });
    });
    // With files only + multimodal on, Send is enabled.
    const sendBtnEnabled = screen.getByLabelText('Send') as HTMLButtonElement;
    expect(sendBtnEnabled).toBeEnabled();
    // Toggle multimodal off — Send must disable (defense in depth).
    // Note we keep sessionResetKey same so the file picks survive the rerender.
    rerender(
      <ChatInput
        onSend={handlers.onSend}
        multimodalEnabled={false}
        onOpenAgentConfig={handlers.onOpenAgentConfig}
        sessionResetKey="sess-A"
      />,
    );
    const sendBtnDisabled = screen.getByLabelText('Send') as HTMLButtonElement;
    expect(sendBtnDisabled).toBeDisabled();
  });
});

describe('ChatWindow — outbound assistant attachments', () => {
  it('renders assistant attachments and keeps attachment-only messages in the transcript', () => {
    render(
      <ChatWindow
        messages={[
          {
            id: 'assistant-artifact-1',
            role: 'assistant',
            content: '',
            attachments: [
              {
                kind: 'pdf',
                attachmentId: 'artifact-1',
                filename: 'report.pdf',
                caption: 'Final report',
              },
            ],
          },
        ]}
        loading={false}
        onSend={vi.fn()}
        slashCommandConfig={{
          userId: 42,
          sessionId: 'sess-A',
          onRedirect: vi.fn(),
          onModelChanged: vi.fn(),
        }}
      />,
    );

    expect(screen.getAllByText('Assistant')).toHaveLength(1);
    expect(screen.getByTestId('rendered-attachment')).toHaveTextContent('report.pdf: Final report');
  });
});
