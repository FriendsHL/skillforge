/**
 * REMINDER-MVP — frontend filter for `<system-reminder>` content blocks.
 *
 * Backend now persists user messages with reminder text as a leading content
 * block instead of appending to the system prompt (preserves prompt cache).
 * Wire shape:
 *   - No reminder: `content` stays a String (legacy shape, unchanged).
 *   - With reminder: `content` becomes an array; the first block is
 *     `{ type: 'text', text: '<system-reminder>...</system-reminder>\n' }`,
 *     followed by the user's actual text and any tool blocks.
 *
 * Helpers in this module strip reminder blocks before display so end users
 * never see the framework's internal reminders in the dashboard.
 *
 * Identification rule (strict, contains-tag in user input is NOT a reminder):
 *   block.type === 'text' &&
 *   text.startsWith('<system-reminder>') &&
 *   (text.endsWith('</system-reminder>') || text.endsWith('</system-reminder>\n'))
 *
 * Tool blocks (`tool_use`, `tool_result`) and assistant content are never
 * touched — only `type: 'text'` blocks are inspected.
 */

const REMINDER_OPEN = '<system-reminder>';
const REMINDER_CLOSE = '</system-reminder>';

interface TextBlockShape {
  type: 'text';
  text: string;
}

function isTextBlock(b: unknown): b is TextBlockShape {
  return (
    !!b &&
    typeof b === 'object' &&
    (b as { type?: unknown }).type === 'text' &&
    typeof (b as { text?: unknown }).text === 'string'
  );
}

function isReminderText(text: string): boolean {
  if (!text.startsWith(REMINDER_OPEN)) return false;
  return text.endsWith(REMINDER_CLOSE) || text.endsWith(`${REMINDER_CLOSE}\n`);
}

function isReminderBlock(b: unknown): boolean {
  return isTextBlock(b) && isReminderText(b.text);
}

/**
 * Strip `<system-reminder>` content blocks from a message's `content` field.
 *
 * - String content → returned as-is, EXCEPT when the entire string is itself a
 *   reminder wrapper (RecoveryPayloadBuilder D6 wraps payload as
 *   `"<system-reminder>\n…\n</system-reminder>\n"` String content). In that
 *   case we return `""` so the caller can decide to skip render. The render-
 *   path side filter for RECOVERY_PAYLOAD msgType in `useChatMessages.ts` is
 *   the primary mechanism; this is a defensive second layer.
 * - Non-array, non-string content (`null`, `undefined`, `{}`, etc.) → returned as-is.
 * - Array content → reminder blocks removed. If filtering leaves exactly one
 *   text block (and no other blocks), the value is collapsed back to a String
 *   so downstream consumers that expected the legacy String shape (e.g. the
 *   `{isUser ? msg.content : ...}` render path in ChatWindow.tsx) continue to
 *   work without any type changes. Empty result collapses to `""` for the
 *   same reason. Otherwise the filtered array is returned.
 */
export function stripSystemReminderBlocks(content: unknown): unknown {
  if (typeof content === 'string') {
    return isReminderText(content) ? '' : content;
  }
  if (!Array.isArray(content)) return content;

  // Fast path — nothing to strip, return original reference unchanged.
  if (!content.some(isReminderBlock)) return content;

  const filtered = content.filter((b) => !isReminderBlock(b));

  // Collapse single text block back to String shape (legacy compatibility).
  if (filtered.length === 0) return '';
  if (filtered.length === 1 && isTextBlock(filtered[0])) return filtered[0].text;
  return filtered;
}

/** True iff the content contains at least one `<system-reminder>` text block. */
export function hasSystemReminderBlock(content: unknown): boolean {
  if (!Array.isArray(content)) return false;
  return content.some(isReminderBlock);
}

/**
 * Convenience helper: apply `stripSystemReminderBlocks` to every message's
 * `.content` field in a list. Messages are returned as fresh objects (immutable
 * update) so React state setters see new references.
 *
 * Non-object entries pass through untouched (defensive — should not happen in
 * practice but cheap to guard).
 */
export function stripRemindersFromMessageList<T>(list: T[]): T[] {
  if (!Array.isArray(list)) return list;
  return list.map((m) => {
    if (!m || typeof m !== 'object') return m;
    const obj = m as Record<string, unknown>;
    if (!('content' in obj)) return m;
    const filtered = stripSystemReminderBlocks(obj.content);
    if (filtered === obj.content) return m; // unchanged — preserve reference
    return { ...obj, content: filtered } as T;
  });
}
