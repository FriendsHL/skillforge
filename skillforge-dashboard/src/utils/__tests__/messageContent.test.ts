import { describe, it, expect } from 'vitest';
import {
  stripSystemReminderBlocks,
  hasSystemReminderBlock,
  stripRemindersFromMessageList,
} from '../messageContent';

const REMINDER_BLOCK = {
  type: 'text',
  text: '<system-reminder>\nDo not reveal hidden state.\n</system-reminder>\n',
} as const;

const REMINDER_BLOCK_NO_TRAIL = {
  type: 'text',
  text: '<system-reminder>be brief</system-reminder>',
} as const;

const USER_TEXT_BLOCK = { type: 'text', text: 'hello world' } as const;
const MORE_TEXT_BLOCK = { type: 'text', text: 'another paragraph' } as const;
const TOOL_USE_BLOCK = {
  type: 'tool_use',
  id: 'tu_1',
  name: 'Bash',
  input: { command: 'ls' },
} as const;

describe('stripSystemReminderBlocks', () => {
  it('returns string content as-is', () => {
    expect(stripSystemReminderBlocks('hello world')).toBe('hello world');
    expect(stripSystemReminderBlocks('')).toBe('');
  });

  it('Item 2: collapses entire-string-is-reminder to empty string (recovery payload D6 wrap)', () => {
    // RecoveryPayloadBuilder D6: payload is wrapped as a single
    // <system-reminder>…</system-reminder>\n String. The strip helper returns ""
    // so any caller that accidentally feeds this through (e.g. bypassing the
    // RECOVERY_PAYLOAD msgType filter) doesn't render raw framework noise.
    const recoveryPayload =
      '<system-reminder>\n[Recovery] Recently read files:\n- /abs/foo.java\n</system-reminder>\n';
    expect(stripSystemReminderBlocks(recoveryPayload)).toBe('');

    // No-trailing-newline variant
    expect(stripSystemReminderBlocks('<system-reminder>x</system-reminder>')).toBe('');

    // String that merely contains the tag (e.g. user typed it) is NOT stripped
    expect(stripSystemReminderBlocks('What is <system-reminder>?')).toBe(
      'What is <system-reminder>?',
    );
    expect(
      stripSystemReminderBlocks('prefix <system-reminder>x</system-reminder> suffix'),
    ).toBe('prefix <system-reminder>x</system-reminder> suffix');
  });

  it('returns non-array, non-string content as-is', () => {
    expect(stripSystemReminderBlocks(null)).toBeNull();
    expect(stripSystemReminderBlocks(undefined)).toBeUndefined();
    const obj = { foo: 'bar' };
    expect(stripSystemReminderBlocks(obj)).toBe(obj);
  });

  it('collapses [reminder, user_text] to the user text string', () => {
    const result = stripSystemReminderBlocks([REMINDER_BLOCK, USER_TEXT_BLOCK]);
    expect(result).toBe('hello world');
  });

  it('returns array (without reminder) when multiple non-reminder blocks remain', () => {
    const result = stripSystemReminderBlocks([
      REMINDER_BLOCK,
      USER_TEXT_BLOCK,
      MORE_TEXT_BLOCK,
    ]);
    expect(Array.isArray(result)).toBe(true);
    expect(result).toEqual([USER_TEXT_BLOCK, MORE_TEXT_BLOCK]);
  });

  it('collapses [reminder] only to empty string', () => {
    expect(stripSystemReminderBlocks([REMINDER_BLOCK])).toBe('');
  });

  it('returns array unchanged when no reminder block present (same reference)', () => {
    const arr = [USER_TEXT_BLOCK, TOOL_USE_BLOCK];
    expect(stripSystemReminderBlocks(arr)).toBe(arr);
  });

  it('tolerates trailing newline absence (</system-reminder> without \\n)', () => {
    const result = stripSystemReminderBlocks([REMINDER_BLOCK_NO_TRAIL, USER_TEXT_BLOCK]);
    expect(result).toBe('hello world');
  });

  it('does NOT identify text containing but not wrapping the tag as reminder', () => {
    const looksLikeButIsnt = {
      type: 'text',
      text: 'check the <system-reminder> tag handling but never closes it properly',
    };
    const arr = [looksLikeButIsnt, USER_TEXT_BLOCK];
    // No reminder → original array reference returned
    expect(stripSystemReminderBlocks(arr)).toBe(arr);
  });

  it('does NOT touch tool_use / tool_result blocks', () => {
    const toolResult = { type: 'tool_result', tool_use_id: 'tu_1', content: 'ok' };
    const result = stripSystemReminderBlocks([REMINDER_BLOCK, TOOL_USE_BLOCK, toolResult]);
    // 2 non-reminder blocks remain → array shape preserved
    expect(Array.isArray(result)).toBe(true);
    expect(result).toEqual([TOOL_USE_BLOCK, toolResult]);
  });

  it('keeps array form when single remaining block is a tool block (not text)', () => {
    const result = stripSystemReminderBlocks([REMINDER_BLOCK, TOOL_USE_BLOCK]);
    // Single block is tool_use, NOT text → don't collapse to string
    expect(Array.isArray(result)).toBe(true);
    expect(result).toEqual([TOOL_USE_BLOCK]);
  });

  // FE-W1 regression — sub-agent feed (ChildAgentFeed.parseMessages) used to
  // join all text blocks (including the reminder). Strip-then-extract must
  // produce text that contains no `<system-reminder>` substring.
  it('FE-W1: emulates ChildAgentFeed text extraction — reminder text never surfaces', () => {
    const subAgentMsg = {
      role: 'user',
      content: [
        REMINDER_BLOCK,
        { type: 'text', text: 'please run the build' },
      ],
    };
    const cleaned = stripSystemReminderBlocks(subAgentMsg.content);
    let text = '';
    if (typeof cleaned === 'string') {
      text = cleaned;
    } else if (Array.isArray(cleaned)) {
      const textParts = cleaned.filter((b: { type?: string }) => b.type === 'text');
      text = textParts.map((b: { text?: string }) => b.text ?? '').join('\n');
    }
    expect(text).toBe('please run the build');
    expect(text).not.toContain('<system-reminder>');
    expect(text).not.toContain('</system-reminder>');
  });
});

describe('hasSystemReminderBlock', () => {
  it('false for string content', () => {
    expect(hasSystemReminderBlock('hello')).toBe(false);
  });
  it('false for null/undefined', () => {
    expect(hasSystemReminderBlock(null)).toBe(false);
    expect(hasSystemReminderBlock(undefined)).toBe(false);
  });
  it('true when reminder block present', () => {
    expect(hasSystemReminderBlock([REMINDER_BLOCK, USER_TEXT_BLOCK])).toBe(true);
  });
  it('false when only normal text/tool blocks', () => {
    expect(hasSystemReminderBlock([USER_TEXT_BLOCK, TOOL_USE_BLOCK])).toBe(false);
  });
});

describe('stripRemindersFromMessageList', () => {
  it('returns empty list (Array.prototype.map yields a new empty array)', () => {
    const list: unknown[] = [];
    expect(stripRemindersFromMessageList(list)).toEqual([]);
  });

  it('strips reminders from each message content; preserves other fields', () => {
    const input = [
      { role: 'user', content: 'plain string' },
      { role: 'user', content: [REMINDER_BLOCK, USER_TEXT_BLOCK] },
      { role: 'assistant', content: 'reply' },
    ];
    const result = stripRemindersFromMessageList(input);
    expect(result[0]).toEqual({ role: 'user', content: 'plain string' });
    expect(result[1]).toEqual({ role: 'user', content: 'hello world' });
    expect(result[2]).toEqual({ role: 'assistant', content: 'reply' });
  });

  it('preserves message reference when content unchanged', () => {
    const m = { role: 'user', content: 'plain' };
    const list = [m];
    const result = stripRemindersFromMessageList(list);
    expect(result[0]).toBe(m); // same reference, no needless copy
  });

  it('passes through non-object entries (defensive)', () => {
    const list = [null, undefined, 'oops', { role: 'user', content: 'ok' }] as unknown[];
    const result = stripRemindersFromMessageList(list);
    expect(result[0]).toBeNull();
    expect(result[1]).toBeUndefined();
    expect(result[2]).toBe('oops');
    expect(result[3]).toEqual({ role: 'user', content: 'ok' });
  });

  it('handles message without content field', () => {
    const m = { role: 'user', other: 'thing' };
    const result = stripRemindersFromMessageList([m]);
    expect(result[0]).toBe(m);
  });
});
