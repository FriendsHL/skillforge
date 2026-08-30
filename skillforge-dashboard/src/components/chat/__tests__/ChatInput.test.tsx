/**
 * P10 — ChatInput slash-command behavior end-to-end:
 *   - popup eligibility (INV-Q5 (a))
 *   - keyboard nav (Up/Down/Enter/Tab/Esc)
 *   - displayMode dispatch (redirect / toast / modal)
 *   - INV-15: `/model` and `/models` never collide.
 *
 * The pure API-client tests live in `src/api/__tests__/commands.test.ts`;
 * here we mock `executeCommand` so we can drive callbacks directly.
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

vi.mock('../../../api/commands', () => ({
  executeCommand: vi.fn(),
}));

vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      success: vi.fn(),
      error: vi.fn(),
      info: vi.fn(),
      warning: vi.fn(),
    },
  };
});

import { ChatInput } from '../../ChatWindow';
import { executeCommand } from '../../../api/commands';
import { message } from 'antd';
import type { CommandResult } from '../../../types/command';

const exec = executeCommand as unknown as ReturnType<typeof vi.fn>;
const msg = message as unknown as {
  success: ReturnType<typeof vi.fn>;
  error: ReturnType<typeof vi.fn>;
  info: ReturnType<typeof vi.fn>;
  warning: ReturnType<typeof vi.fn>;
};

interface RenderHandlers {
  onSend: ReturnType<typeof vi.fn>;
  onRedirect: ReturnType<typeof vi.fn>;
  onModelChanged: ReturnType<typeof vi.fn>;
  onShowModal: ReturnType<typeof vi.fn>;
}

function renderInput() {
  const handlers: RenderHandlers = {
    onSend: vi.fn(),
    onRedirect: vi.fn(),
    onModelChanged: vi.fn(),
    onShowModal: vi.fn(),
  };
  const utils = render(
    <ChatInput
      onSend={handlers.onSend}
      slashCommands={{
        userId: 1,
        sessionId: 'sess-1',
        onRedirect: handlers.onRedirect,
        onModelChanged: handlers.onModelChanged,
        onShowModal: handlers.onShowModal,
      }}
    />,
  );
  const textarea = utils.getByTestId('chat-input-textarea') as HTMLTextAreaElement;
  return { ...utils, handlers, textarea };
}

describe('ChatInput — slash command popup eligibility', () => {
  beforeEach(() => {
    exec.mockReset();
    msg.success.mockReset();
    msg.error.mockReset();
    msg.info.mockReset();
    msg.warning.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('shows popup when first character is `/`', () => {
    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/' } });
    expect(screen.getByTestId('command-popup')).toBeInTheDocument();
  });

  it('hides popup for plain text', () => {
    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: 'hello' } });
    expect(screen.queryByTestId('command-popup')).not.toBeInTheDocument();
  });

  it('hides popup once a space is typed (args mode)', () => {
    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/model' } });
    expect(screen.getByTestId('command-popup')).toBeInTheDocument();
    fireEvent.change(textarea, { target: { value: '/model gpt-4' } });
    expect(screen.queryByTestId('command-popup')).not.toBeInTheDocument();
  });

  it('does NOT activate when slashCommands prop is omitted', () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);
    const textarea = screen.getByTestId('chat-input-textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '/' } });
    expect(screen.queryByTestId('command-popup')).not.toBeInTheDocument();
  });
});

describe('ChatInput — popup keyboard interactions', () => {
  beforeEach(() => exec.mockReset());

  it('ArrowDown / ArrowUp wraps through filtered list', () => {
    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/m' } });
    expect(screen.getByTestId('command-option-model').getAttribute('aria-selected')).toBe('true');
    fireEvent.keyDown(textarea, { key: 'ArrowDown' });
    expect(screen.getByTestId('command-option-models').getAttribute('aria-selected')).toBe('true');
    fireEvent.keyDown(textarea, { key: 'ArrowDown' });
    // Wraps back to /model
    expect(screen.getByTestId('command-option-model').getAttribute('aria-selected')).toBe('true');
    fireEvent.keyDown(textarea, { key: 'ArrowUp' });
    expect(screen.getByTestId('command-option-models').getAttribute('aria-selected')).toBe('true');
  });

  it('Tab autocompletes selected name + space and closes popup', () => {
    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/m' } });
    fireEvent.keyDown(textarea, { key: 'ArrowDown' }); // select /models
    fireEvent.keyDown(textarea, { key: 'Tab' });
    expect(textarea.value).toBe('/models ');
    expect(screen.queryByTestId('command-popup')).not.toBeInTheDocument();
  });

  it('Esc closes the popup but preserves input value', () => {
    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/m' } });
    expect(screen.getByTestId('command-popup')).toBeInTheDocument();
    fireEvent.keyDown(textarea, { key: 'Escape' });
    expect(screen.queryByTestId('command-popup')).not.toBeInTheDocument();
    expect(textarea.value).toBe('/m');
  });
});

describe('ChatInput — IME composition Enter', () => {
  beforeEach(() => exec.mockReset());

  it('does not send plain text while Enter confirms an active composition', () => {
    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '拼音' } });

    const wasNotCancelled = fireEvent.keyDown(textarea, { key: 'Enter', isComposing: true });

    expect(wasNotCancelled).toBe(true);
    expect(handlers.onSend).not.toHaveBeenCalled();
    expect(textarea.value).toBe('拼音');
  });

  it('sends exactly once when a normal Enter follows the composition Enter', () => {
    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '拼音' } });

    fireEvent.keyDown(textarea, { key: 'Enter', isComposing: true });
    expect(handlers.onSend).not.toHaveBeenCalled();

    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(handlers.onSend).toHaveBeenCalledTimes(1);
    expect(handlers.onSend).toHaveBeenCalledWith('拼音');
  });

  it('does not execute the selected slash command for Safari IME keyCode 229', () => {
    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '/m' } });
    expect(screen.getByTestId('command-popup')).toBeInTheDocument();

    const wasNotCancelled = fireEvent.keyDown(textarea, { key: 'Enter', keyCode: 229 });

    expect(wasNotCancelled).toBe(true);
    expect(exec).not.toHaveBeenCalled();
    expect(handlers.onSend).not.toHaveBeenCalled();
    expect(textarea.value).toBe('/m');
  });
});

describe('ChatInput — displayMode dispatch', () => {
  beforeEach(() => {
    exec.mockReset();
    msg.success.mockReset();
    msg.error.mockReset();
  });

  it('displayMode="redirect" → onRedirect(newSessionId)', async () => {
    exec.mockResolvedValueOnce({
      success: true,
      message: 'created',
      displayMode: 'redirect',
      newSessionId: 'sess-99',
    } satisfies CommandResult);

    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '/new' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(exec).toHaveBeenCalledWith(1, 'sess-1', '/new'));
    expect(handlers.onRedirect).toHaveBeenCalledWith('sess-99');
    expect(handlers.onSend).not.toHaveBeenCalled();
  });

  it('displayMode="toast" + modelId → message.success + onModelChanged', async () => {
    exec.mockResolvedValueOnce({
      success: true,
      message: 'switched to gpt-4o',
      displayMode: 'toast',
      modelId: 'gpt-4o',
    } satisfies CommandResult);

    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '/model gpt-4o' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(exec).toHaveBeenCalled());
    expect(msg.success).toHaveBeenCalledWith('switched to gpt-4o');
    expect(handlers.onModelChanged).toHaveBeenCalledWith('gpt-4o');
    expect(handlers.onShowModal).not.toHaveBeenCalled();
    expect(handlers.onRedirect).not.toHaveBeenCalled();
    expect(handlers.onSend).not.toHaveBeenCalled();
  });

  it('displayMode="toast" without modelId (e.g. /compact) → toast only, no onModelChanged', async () => {
    exec.mockResolvedValueOnce({
      success: true,
      message: 'compaction triggered',
      displayMode: 'toast',
    } satisfies CommandResult);

    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '/compact' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(msg.success).toHaveBeenCalledWith('compaction triggered'));
    expect(handlers.onModelChanged).not.toHaveBeenCalled();
  });

  it('displayMode="modal" → onShowModal(title, markdownBody)', async () => {
    exec.mockResolvedValueOnce({
      success: true,
      message: '/help',
      displayMode: 'modal',
      markdownBody: '# Help\nlist of commands',
    } satisfies CommandResult);

    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '/help' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(handlers.onShowModal).toHaveBeenCalledWith('/help', '# Help\nlist of commands'));
  });

  it('success=false → message.error with error string', async () => {
    exec.mockResolvedValueOnce({
      success: false,
      message: '',
      displayMode: 'toast',
      error: 'Unknown command /foo',
    } satisfies CommandResult);

    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: '/foo' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(msg.error).toHaveBeenCalledWith('Unknown command /foo'));
    expect(handlers.onSend).not.toHaveBeenCalled();
  });

  it('plain text Enter routes through onSend, not executeCommand', () => {
    const { textarea, handlers } = renderInput();
    fireEvent.change(textarea, { target: { value: 'hello world' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(handlers.onSend).toHaveBeenCalledWith('hello world');
    expect(exec).not.toHaveBeenCalled();
  });
});

describe('ChatInput — INV-15 /model vs /models routing', () => {
  beforeEach(() => exec.mockReset());

  it('typed `/model gpt-4` is sent verbatim (BE splits → /model handler)', async () => {
    exec.mockResolvedValueOnce({
      success: true,
      message: 'ok',
      displayMode: 'toast',
      modelId: 'gpt-4',
    } satisfies CommandResult);

    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/model gpt-4' } });
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(exec).toHaveBeenCalled());
    expect(exec.mock.calls[0][2]).toBe('/model gpt-4');
  });

  it('popup-selected `/models` sends `/models` exactly (never `/model`)', async () => {
    exec.mockResolvedValueOnce({
      success: true,
      message: 'list',
      displayMode: 'modal',
      markdownBody: '# Models',
    } satisfies CommandResult);

    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/m' } });
    fireEvent.keyDown(textarea, { key: 'ArrowDown' }); // select /models
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(exec).toHaveBeenCalled());
    expect(exec.mock.calls[0][2]).toBe('/models');
  });

  it('popup-selected `/model` (default first) sends `/model` exactly', async () => {
    exec.mockResolvedValueOnce({
      success: false,
      message: '',
      displayMode: 'toast',
      error: 'modelId required',
    } satisfies CommandResult);

    const { textarea } = renderInput();
    fireEvent.change(textarea, { target: { value: '/m' } });
    // first row in catalog order is /model
    await act(async () => {
      fireEvent.keyDown(textarea, { key: 'Enter' });
    });

    await waitFor(() => expect(exec).toHaveBeenCalled());
    expect(exec.mock.calls[0][2]).toBe('/model');
  });
});
