import { act, renderHook } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { normalizeMessages, useChatMessages } from '../useChatMessages';
import type { RawMessage } from '../../types/messages';

describe('normalizeMessages — createdAt → timestamp passthrough', () => {
  it('passes createdAt to ChatMessage.timestamp on a user message', () => {
    const raw: RawMessage[] = [
      {
        role: 'user',
        content: 'hello world',
        createdAt: '2026-05-19T14:23:45.123Z',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].role).toBe('user');
    expect(out[0].content).toBe('hello world');
    expect(out[0].timestamp).toBe('2026-05-19T14:23:45.123Z');
  });

  it('passes createdAt to ChatMessage.timestamp on an assistant message', () => {
    const raw: RawMessage[] = [
      {
        role: 'assistant',
        content: 'reply text',
        createdAt: '2026-05-19T14:24:00.000Z',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].role).toBe('assistant');
    expect(out[0].content).toBe('reply text');
    expect(out[0].timestamp).toBe('2026-05-19T14:24:00.000Z');
  });

  it('leaves timestamp undefined when createdAt is missing (legacy rows)', () => {
    const raw: RawMessage[] = [
      {
        role: 'user',
        content: 'no timestamp here',
      },
      {
        role: 'assistant',
        content: 'also no timestamp',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(2);
    expect(out[0].timestamp).toBeUndefined();
    expect(out[1].timestamp).toBeUndefined();
  });

  it('leaves timestamp undefined when createdAt is not a string', () => {
    // Defensive: BE Jackson should always emit string, but if a future
    // backwards-compat path emits number / null, we don't want to crash or
    // pass garbage through.
    const raw: RawMessage[] = [
      {
        role: 'user',
        content: 'odd shape',
        createdAt: 12345 as unknown as string,
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].timestamp).toBeUndefined();
  });

  it('passes createdAt on an ask_user control message', () => {
    const raw: RawMessage[] = [
      {
        role: 'assistant',
        content: 'pick one',
        messageType: 'ask_user',
        controlId: 'ask-123',
        createdAt: '2026-05-19T15:00:00.000Z',
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].messageType).toBe('ask_user');
    expect(out[0].timestamp).toBe('2026-05-19T15:00:00.000Z');
  });
});

describe('normalizeMessages — outbound assistant attachments', () => {
  it('retains an attachment-only assistant message and its caption', () => {
    const raw: RawMessage[] = [
      {
        role: 'assistant',
        seqNo: 17,
        content: [
          {
            type: 'pdf_ref',
            attachment_id: 'artifact-17',
            filename: 'analysis.pdf',
            page_count: 8,
            caption: 'Quarterly analysis',
          },
        ],
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      role: 'assistant',
      content: '',
      id: '17',
      attachments: [
        {
          kind: 'pdf',
          attachmentId: 'artifact-17',
          filename: 'analysis.pdf',
          pageCount: 8,
          caption: 'Quarterly analysis',
        },
      ],
    });
  });

  it('keeps mixed assistant text and all supported attachment refs in one message', () => {
    const raw: RawMessage[] = [
      {
        role: 'assistant',
        content: [
          { type: 'text', text: 'Generated files:' },
          { type: 'image_ref', attachment_id: 'img-1', filename: 'chart.png' },
          { type: 'word_ref', attachment_id: 'doc-1', filename: 'notes.docx' },
          { type: 'excel_ref', attachment_id: 'xls-1', filename: 'data.xlsx', sheet_count: 3 },
          { type: 'csv_ref', attachment_id: 'csv-1', filename: 'data.csv' },
        ],
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(1);
    expect(out[0].content).toBe('Generated files:');
    expect(out[0].attachments?.map(({ kind, attachmentId }) => ({ kind, attachmentId }))).toEqual([
      { kind: 'image', attachmentId: 'img-1' },
      { kind: 'word', attachmentId: 'doc-1' },
      { kind: 'excel', attachmentId: 'xls-1' },
      { kind: 'csv', attachmentId: 'csv-1' },
    ]);
    expect(out[0].attachments?.[2].sheetCount).toBe(3);
  });

  it('does not split or duplicate the assistant message when it has text and attachments', () => {
    const raw: RawMessage[] = [
      { role: 'user', content: 'Create a report' },
      {
        role: 'assistant',
        content: [
          { type: 'text', text: 'Done' },
          { type: 'pdf_ref', attachment_id: 'report-1', filename: 'report.pdf' },
        ],
      },
    ];

    const out = normalizeMessages(raw);

    expect(out).toHaveLength(2);
    expect(out.map((message) => message.role)).toEqual(['user', 'assistant']);
    expect(out[1].attachments).toHaveLength(1);
  });
});

describe('useChatMessages — session ownership', () => {
  it('never exposes the previous session messages under a new session id', () => {
    const renders: Array<{ sessionId: string | undefined; content: string[] }> = [];
    const { result, rerender } = renderHook(
      ({ sessionId }: { sessionId: string | undefined }) => {
        const state = useChatMessages(sessionId);
        renders.push({
          sessionId,
          content: state.messages.map((message) => message.content),
        });
        return state;
      },
      { initialProps: { sessionId: 'session-a' } },
    );

    act(() => {
      result.current.setRawMessages([{ role: 'user', content: 'session-a file' }]);
    });
    expect(result.current.messages[0]?.content).toBe('session-a file');

    renders.length = 0;
    rerender({ sessionId: 'session-b' });

    expect(
      renders.some(
        (render) =>
          render.sessionId === 'session-b' &&
          render.content.includes('session-a file'),
      ),
    ).toBe(false);
    expect(result.current.messages).toEqual([]);
  });
});
