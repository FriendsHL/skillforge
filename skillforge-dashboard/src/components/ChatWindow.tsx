import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Tooltip, message } from 'antd';
import { type ToolCall } from './ToolCallTimeline';
import MarkdownRenderer from './MarkdownRenderer';
import AttachmentThumbnail from './AttachmentThumbnail';
import PendingAskCard from './PendingAskCard';
import InstallConfirmationCard from './InstallConfirmationCard';
import ReasoningPanel from './ReasoningPanel';
import { ThrottledMarkdown } from './ThrottledMarkdown';
import type { ConfirmationDecision, ConfirmationPromptPayload } from '../api';
import { executeCommand } from '../api/commands';
import {
  COMMAND_NAME_REGEX,
  filterCommands,
  type CommandResult,
} from '../types/command';
import CommandPopup from './chat/CommandPopup';
import CommandResultModal from './chat/CommandResultModal';
import { RoleAvatar } from './chat/primitives';
import {
  IconAttach,
  IconCheck,
  IconCompact,
  IconMic,
  IconSend,
  IconTool,
  IconX,
} from './chat/ChatIcons';

// 24h HH:MM:SS — `hour12: false` is explicit so users get the same format
// regardless of browser locale (en-US default would give "03:42:18 PM").
// `[]` locale arg lets the browser pick its locale for any other digit-grouping
// quirks, but hour/minute/second formatting is locked by the options.
// Returns '' on Invalid Date (callsite passes `new Date(msg.timestamp)` which
// is safe in the happy path — BE Jackson emits ISO Instant — but a malformed
// legacy row should not render "Invalid Date" in the bubble header).
const formatTime = (date: Date): string => {
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
};

/**
 * P10 — When `slashCommands` is provided, ChatInput intercepts text starting
 * with `/` and routes it through `POST /api/commands/execute` instead of the
 * normal chat send path. The popup is purely UX: dispatch always uses the
 * exact command name typed (or selected), so `/model` and `/models` cannot
 * collide (INV-15).
 */
export interface SlashCommandHandlers {
  userId: number;
  sessionId: string;
  /** Called for `/new` (displayMode='redirect'). */
  onRedirect: (newSessionId: string) => void;
  /**
   * Called for `/model` (displayMode='toast') so the parent can refetch
   * the session and update header chips. `/compact` does not need this —
   * the WS compaction event already triggers a refresh.
   */
  onModelChanged: (modelId: string | undefined) => void;
  /**
   * Called for `/models|/skill|/tool|/context|/help` (displayMode='modal') —
   * ChatWindow uses this to open `CommandResultModal` with the markdown body.
   */
  onShowModal: (title: string, markdownBody: string) => void;
}

interface ChatInputProps {
  disabled?: boolean;
  onSend: (text: string, files?: File[]) => void;
  /** Optional — when present, enables slash-command interception. */
  slashCommands?: SlashCommandHandlers;
  /**
   * MULTIMODAL-MVP — Chat upload gate. When `false` (or undefined), the attach
   * button is rendered DISABLED (visible, but `aria-disabled`) with a tooltip
   * pointing the user to the agent config. The button is never hidden so the
   * affordance stays discoverable. Source: `activeAgent.multimodalModelId`.
   */
  multimodalEnabled?: boolean;
  /** Called when user clicks the in-tooltip "open agent config" link. */
  onOpenAgentConfig?: () => void;
  /**
   * Bumped by parent on session switch — forces the in-flight `files` state to
   * reset so picks don't carry across sessions (PRD §"session 切换时清理未发送
   * 的本地 selected/uploading 状态").
   */
  sessionResetKey?: string;
}

const MULTIMODAL_GATE_TOOLTIP = '请把 agent 的主模型切换为多模态模型（picker 上带「多模态」标签的项）';

/**
 * r2 W1 — Attachment chip metadata. Format bytes as `B / KB / MB`. Single
 * non-decimal digit for KB and one decimal for MB keeps chips compact while
 * staying scan-able. Used by the attachment tray rendering below.
 */
function formatBytes(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * Wave 3 — MIME allowlist for chat upload. Constants exposed at module scope so
 * `handleFileChange` filter + `accept=` attr + `chipKindLabel` stay in sync.
 * Word/Excel/CSV MIMEs are the canonical Office / OpenDocument values. Note
 * some browsers (notably older Windows variants) report `.csv` with
 * `application/vnd.ms-excel` instead of `text/csv` — we accept both via the
 * Excel branch so the file at least passes the filter; the BE parser
 * disambiguates by extension.
 */
const WORD_MIMES = [
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
];
const EXCEL_MIMES = [
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
];
const CSV_MIMES = ['text/csv'];

function isAcceptedMime(mime: string): boolean {
  return (
    mime.startsWith('image/') ||
    mime === 'application/pdf' ||
    WORD_MIMES.includes(mime) ||
    EXCEL_MIMES.includes(mime) ||
    CSV_MIMES.includes(mime)
  );
}

/**
 * Wave 3 — Compact type badge derived from the picked file's MIME. The
 * accepted MIME set is enforced upstream in handleFileChange. Empty-string MIME
 * (some browsers, esp. for .csv) falls through to a filename-extension probe.
 */
function chipKindLabel(file: File): 'PDF' | 'IMG' | 'DOC' | 'XLS' | 'CSV' {
  const mime = file.type;
  if (mime === 'application/pdf') return 'PDF';
  if (mime.startsWith('image/')) return 'IMG';
  if (WORD_MIMES.includes(mime)) return 'DOC';
  if (EXCEL_MIMES.includes(mime)) return 'XLS';
  if (CSV_MIMES.includes(mime)) return 'CSV';
  // Fallback via filename ext — covers browsers that report mime=''.
  const lower = file.name.toLowerCase();
  if (lower.endsWith('.csv')) return 'CSV';
  if (lower.endsWith('.doc') || lower.endsWith('.docx')) return 'DOC';
  if (lower.endsWith('.xls') || lower.endsWith('.xlsx')) return 'XLS';
  return 'IMG';
}

const ChatInput: React.FC<ChatInputProps> = React.memo(
  ({ disabled, onSend, slashCommands, multimodalEnabled, onOpenAgentConfig, sessionResetKey }) => {
    const [input, setInput] = useState('');
    const [popupOpen, setPopupOpen] = useState(false);
    const [selectedIdx, setSelectedIdx] = useState(0);
    const [executing, setExecuting] = useState(false);
    const [files, setFiles] = useState<File[]>([]);
    const textareaRef = useRef<HTMLTextAreaElement>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);

    // Drop in-flight selections when the active session changes — prevents
    // accidentally re-sending a previous session's pick to the next one.
    useEffect(() => {
      setFiles([]);
    }, [sessionResetKey]);

    const autosize = useCallback(() => {
      const el = textareaRef.current;
      if (!el) return;
      el.style.height = 'auto';
      el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
    }, []);

    useEffect(() => {
      autosize();
    }, [input, autosize]);

    // INV-Q5 (a): popup is allowed only when (a) we have slash-command
    // handlers, (b) the input begins with `/`, and (c) no whitespace has
    // been typed yet (so `/model gpt-4` hides the popup but still routes
    // to the slash-command path on Enter).
    const canShowPopup = !!slashCommands && COMMAND_NAME_REGEX.test(input);

    const filtered = useMemo(
      () => (canShowPopup ? filterCommands(input) : []),
      [canShowPopup, input],
    );

    // Keep the popup mounted only while it's eligible AND has rows.
    const isPopupActive = popupOpen && filtered.length > 0;

    // Re-clamp selection when the filtered list shrinks.
    useEffect(() => {
      if (selectedIdx >= filtered.length) {
        setSelectedIdx(0);
      }
    }, [filtered.length, selectedIdx]);

    // Open the popup whenever eligibility flips on; close when it flips off.
    useEffect(() => {
      if (canShowPopup) {
        setPopupOpen(true);
        setSelectedIdx(0);
      } else {
        setPopupOpen(false);
      }
    }, [canShowPopup]);

    const handleCommandResult = useCallback(
      (result: CommandResult) => {
        if (!slashCommands) return;
        if (!result.success) {
          message.error(result.error || result.message || 'Command failed');
          return;
        }
        switch (result.displayMode) {
          case 'redirect': {
            if (result.newSessionId) {
              slashCommands.onRedirect(result.newSessionId);
              if (result.message) message.success(result.message);
            } else {
              message.error('Redirect command returned no newSessionId');
            }
            break;
          }
          case 'toast': {
            if (result.message) message.success(result.message);
            // For `/model`, refresh session so header chip updates. `/compact`
            // is async and already wired through the WS compaction event;
            // calling onModelChanged here with undefined modelId is harmless
            // for `/compact` because parents only react when modelId changes.
            if (result.modelId !== undefined) {
              slashCommands.onModelChanged(result.modelId);
            }
            break;
          }
          case 'modal': {
            slashCommands.onShowModal(
              result.message || 'Command result',
              result.markdownBody ?? '',
            );
            break;
          }
          default: {
            // Unknown displayMode — surface the message anyway so users see SOMETHING.
            message.info(result.message || 'Command executed');
          }
        }
      },
      [slashCommands],
    );

    const runSlashCommand = useCallback(
      async (commandLine: string) => {
        if (!slashCommands || executing) return;
        const trimmed = commandLine.trim();
        if (!trimmed.startsWith('/')) return;
        setExecuting(true);
        try {
          const result = await executeCommand(
            slashCommands.userId,
            slashCommands.sessionId,
            trimmed,
          );
          setInput('');
          setPopupOpen(false);
          handleCommandResult(result);
        } catch (e: unknown) {
          const status = (e as { response?: { status?: number } })?.response?.status;
          if (status === 403 || status === 404) {
            message.error('Session not found or access denied');
          } else {
            message.error('Command failed');
          }
        } finally {
          setExecuting(false);
        }
      },
      [slashCommands, executing, handleCommandResult],
    );

    const handleSend = () => {
      const text = input.trim();
      const selectedFiles = files;
      if (!text && selectedFiles.length === 0) return;
      // Defense-in-depth: even though the picker is gated, refuse to ship
      // attachments when multimodal is not configured. Mirrors PRD Ratify #6
      // and the BE 409 MULTIMODAL_MODEL_NOT_CONFIGURED check.
      if (selectedFiles.length > 0 && !multimodalEnabled) {
        message.error(MULTIMODAL_GATE_TOOLTIP);
        return;
      }
      // First-character `/` always routes to slash-command path (INV-Q5 (a)).
      if (slashCommands && text.startsWith('/')) {
        if (selectedFiles.length > 0) {
          message.warning('Slash commands do not support attachments');
          return;
        }
        void runSlashCommand(text);
        return;
      }
      setInput('');
      setFiles([]);
      if (selectedFiles.length > 0) {
        onSend(text, selectedFiles);
      } else {
        onSend(text);
      }
    };

    const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      const incoming = Array.from(e.target.files ?? []);
      const next = incoming.filter((file) => {
        // Wave 3 — accept image / pdf / word / excel / csv. Some browsers
        // report mime='' for .csv — fall back to filename extension so the
        // user isn't silently blocked.
        if (isAcceptedMime(file.type)) return true;
        const lower = file.name.toLowerCase();
        return (
          lower.endsWith('.csv') ||
          lower.endsWith('.doc') ||
          lower.endsWith('.docx') ||
          lower.endsWith('.xls') ||
          lower.endsWith('.xlsx')
        );
      });
      if (next.length !== incoming.length) {
        message.warning('Only images, PDFs, Word, Excel, and CSV files are supported');
      }
      setFiles((prev) => [...prev, ...next].slice(0, 5));
      e.target.value = '';
    };

    const handlePopupSelect = useCallback(
      (idx: number) => {
        const cmd = filtered[idx];
        if (!cmd) return;
        // Popup selection sends the EXACT command name — INV-15 prevents
        // `/model` vs `/models` collision because BE dispatches by exact name.
        void runSlashCommand(cmd.name);
      },
      [filtered, runSlashCommand],
    );

    const handlePopupTabComplete = (idx: number) => {
      const cmd = filtered[idx];
      if (!cmd) return;
      // Tab completes the name plus a trailing space so the user can keep
      // typing args (e.g. `/model ` → `gpt-4o`). The trailing space also
      // breaks COMMAND_NAME_REGEX so the popup auto-closes.
      setInput(`${cmd.name} `);
      setPopupOpen(false);
    };

    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (isPopupActive) {
        switch (e.key) {
          case 'ArrowDown':
            e.preventDefault();
            setSelectedIdx((i) => (i + 1) % filtered.length);
            return;
          case 'ArrowUp':
            e.preventDefault();
            setSelectedIdx((i) => (i - 1 + filtered.length) % filtered.length);
            return;
          case 'Enter': {
            if (e.shiftKey) break; // shift+enter = newline, fall through
            e.preventDefault();
            handlePopupSelect(selectedIdx);
            return;
          }
          case 'Tab':
            e.preventDefault();
            handlePopupTabComplete(selectedIdx);
            return;
          case 'Escape':
            e.preventDefault();
            setPopupOpen(false);
            return;
          default:
            break;
        }
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    };

    return (
      <div className="composer-wrap" style={{ position: 'relative' }}>
        {isPopupActive && (
          <CommandPopup
            commands={filtered}
            selectedIndex={selectedIdx}
            onSelect={handlePopupSelect}
            onHover={setSelectedIdx}
          />
        )}
        {files.length > 0 && (
          <div className="attachment-tray">
            {files.map((file, idx) => (
              <span
                className="attachment-chip"
                key={`${file.name}-${idx}`}
                data-testid="attachment-chip"
              >
                {/* r2 W1 — PRD §Chat 上传 "必须" content: filename + type + size.
                    Status indicator stays implicit for Phase 1 (chip presence
                    = pending-send; failure → message.error toast covers it). */}
                <span className="att-kind" data-testid="attachment-chip-kind">
                  {chipKindLabel(file)}
                </span>
                <span className="att-name">{file.name}</span>
                <span className="att-size" data-testid="attachment-chip-size">
                  {formatBytes(file.size)}
                </span>
                <button
                  type="button"
                  aria-label={`Remove ${file.name}`}
                  onClick={() => setFiles((prev) => prev.filter((_, i) => i !== idx))}
                >
                  <IconX s={10} />
                </button>
              </span>
            ))}
          </div>
        )}
        <div className="composer">
          <div className="comp-left-tools">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*,application/pdf,.doc,.docx,.xls,.xlsx,.csv"
              multiple
              style={{ display: 'none' }}
              onChange={handleFileChange}
              data-testid="chat-attach-file-input"
            />
            {/* MULTIMODAL-MVP gate (PRD Ratify #6): button is **visible but
                disabled** when the active agent has no multimodalModelId, so
                the affordance stays discoverable. Click handler short-circuits
                in the disabled branch as a defense in depth (in case AntD's
                Tooltip wrapper interferes with the native disabled attr). */}
            <Tooltip
              title={
                !multimodalEnabled ? (
                  <span data-testid="multimodal-tooltip-content">
                    {MULTIMODAL_GATE_TOOLTIP}
                    {onOpenAgentConfig && (
                      <>
                        {' · '}
                        <a
                          role="link"
                          data-testid="multimodal-tooltip-link"
                          onClick={(e) => {
                            e.preventDefault();
                            onOpenAgentConfig();
                          }}
                          style={{ color: 'var(--accent)', cursor: 'pointer' }}
                        >
                          打开 agent 配置
                        </a>
                      </>
                    )}
                  </span>
                ) : ''
              }
              placement="top"
              // 0-delay makes the overlay mount synchronously on hover —
              // matters in jsdom where AntD's default 100ms enter/leave
              // delays interact poorly with `waitFor` defaults.
              mouseEnterDelay={0}
              mouseLeaveDelay={0}
            >
              {/* r2 W4 — drop native `disabled`, keep aria-disabled + onClick
                  guard. Native `disabled` on <button> removes it from the tab
                  order, which means keyboard users can't focus the button to
                  discover the gate tooltip — defeats the "不隐藏按钮" intent.
                  CSS rule `.comp-btn[aria-disabled="true"]` (added in
                  index.css) keeps the visual disabled cue. */}
              <button
                type="button"
                className="comp-btn"
                title="Attach"
                aria-label="Attach"
                aria-disabled={!multimodalEnabled || disabled || executing}
                data-testid="chat-attach-button"
                onClick={() => {
                  if (!multimodalEnabled || disabled || executing) {
                    // Single short-circuit guard handles all gated states
                    // (gate not configured / parent-disabled / mid-execution).
                    return;
                  }
                  fileInputRef.current?.click();
                }}
              >
                <IconAttach s={14} />
              </button>
            </Tooltip>
          </div>
          <textarea
            ref={textareaRef}
            placeholder="Message the agent… (Shift+Enter for newline)"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={disabled || executing}
            rows={1}
            data-testid="chat-input-textarea"
          />
          <button type="button" className="comp-btn" title="Voice" aria-label="Voice">
            <IconMic s={14} />
          </button>
          <button
            type="button"
            className="send-btn"
            onClick={handleSend}
            disabled={
              disabled ||
              executing ||
              (!input.trim() && files.length === 0) ||
              // Defense in depth: refuse to enable Send when files are queued
              // but multimodal is not configured. The picker gate already
              // prevents reaching this state, but a stale agent change after
              // picking would otherwise leave Send enabled.
              (files.length > 0 && !multimodalEnabled)
            }
            aria-label="Send"
          >
            <IconSend s={15} />
          </button>
        </div>
        <div className="comp-foot">
          <div className="tokens">
            <span>⌘ SkillForge</span>
          </div>
          <div>
            <span className="kbd">↵</span> send &nbsp;
            <span className="kbd">⇧↵</span> newline &nbsp;
            <span className="kbd">/</span> commands
          </div>
        </div>
      </div>
    );
  },
);
ChatInput.displayName = 'ChatInput';

// Exported for unit tests — kept inside ChatWindow.tsx to avoid restructuring
// the existing module layout.
export { ChatInput };

/** MULTIMODAL-MVP Phase 2 / Wave 3: lightweight reference to an uploaded
 *  attachment, carried inline on the chat bubble so the thumbnail can render
 *  via `AttachmentThumbnail`. Mirrors BE `image_ref` / `pdf_ref` / `word_ref`
 *  / `excel_ref` / `csv_ref` content blocks. */
export interface ChatAttachmentRef {
  kind: 'image' | 'pdf' | 'word' | 'excel' | 'csv';
  attachmentId: string;
  filename: string;
  /** PDF only — page count surfaced in the chip. */
  pageCount?: number;
  /** Excel only — sheet count surfaced in the chip. */
  sheetCount?: number;
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'summary';
  content: string;
  messageType?: 'normal' | 'team_result' | 'subagent_result' | 'ask_user' | 'confirmation';
  controlId?: string;
  answeredAt?: string;
  metadata?: Record<string, unknown>;
  toolCalls?: ToolCall[];
  timestamp?: string;
  id?: string;
  /** Inline attachment refs (image_ref / pdf_ref). Renderer shows thumbnails
   *  above the message body. Phase 2. */
  attachments?: ChatAttachmentRef[];
  /**
   * CHAT-REASONING-PANEL: assistant-only reasoning / thinking text persisted on
   * `t_session_message.reasoning_content` (OpenAI-compatible providers:
   * DeepSeek / Qwen / mimo). Rendered by `ReasoningPanel` above the assistant
   * bubble. Empty / undefined → panel renders null. Sourced from
   * `RawMessage.reasoningContent` via `normalizeMessages` assistant push site.
   */
  reasoningContent?: string;
}

interface InflightTool {
  name: string;
  input: unknown;
  startTs: number;
}

/**
 * P10 — `slashCommandConfig` is the parent's hook into the slash-command
 * subsystem. ChatWindow owns the modal state (so the modal lifecycle is
 * predictable) and forwards the rest into the inner `ChatInput`.
 */
export interface ChatWindowSlashCommandConfig {
  userId: number;
  sessionId: string;
  /** Navigate to the new session URL after `/new`. */
  onRedirect: (newSessionId: string) => void;
  /** Refetch the session record after `/model` so header chips update. */
  onModelChanged: (modelId: string | undefined) => void;
}

interface ChatWindowProps {
  messages: ChatMessage[];
  loading: boolean;
  onSend: (text: string, files?: File[]) => void;
  inputDisabled?: boolean;
  inflightTools?: Record<string, InflightTool>;
  streamingText?: string;
  compactionNotice?: boolean;
  onCompactionDismiss?: () => void;
  runtimeStatus?: string;
  agentName?: string;
  onAnswerAsk?: (askId: string, answer: string) => void;
  onConfirmDecision?: (confirmationId: string, decision: ConfirmationDecision) => void;
  /** Optional — when provided, enables `/`-prefixed slash commands. */
  slashCommandConfig?: ChatWindowSlashCommandConfig;
  /** MULTIMODAL-MVP: Chat upload gate (active agent has multimodalModelId). */
  multimodalEnabled?: boolean;
  /** Called when user clicks the in-tooltip "open agent config" link. */
  onOpenAgentConfig?: () => void;
  /** Bumped by parent on session switch to flush in-flight file picks. */
  sessionResetKey?: string;
  /**
   * CHAT-REASONING-PANEL: live `reasoning_content` text accumulated from SSE
   * `reasoning_delta` events. Drives the streaming-mode `ReasoningPanel`
   * rendered above the streaming text bubble. Cleared on `message_appended`.
   */
  streamingReasoningText?: string;
  /**
   * CHAT-REASONING-PANEL: wall-clock duration of the reasoning phase
   * (first `reasoning_delta` → first `text_delta`) in milliseconds. Drives
   * the `Thought for N.Ns ▾` label. `null` = no duration computed yet
   * (still in reasoning phase) or historical message (no streaming data).
   */
  reasoningDurationMs?: number | null;
  /**
   * CHAT-REASONING-PANEL: agent-level preference for whether completed
   * reasoning panels start expanded. `null`/`undefined`/`false` = collapsed.
   * Sourced from `agent.thinkingVisible`.
   */
  agentThinkingVisible?: boolean | null;
}

interface ToolCallRowProps {
  tc: ToolCall;
}
const ToolCallRow: React.FC<ToolCallRowProps> = ({ tc }) => {
  const [open, setOpen] = useState(false);
  const ok = tc.status !== 'error';
  const inputText =
    typeof tc.input === 'string' ? tc.input : JSON.stringify(tc.input ?? {});
  const outputText = tc.output;
  const durMs = tc.duration;
  const durStr =
    typeof durMs === 'number'
      ? durMs >= 1000
        ? `${(durMs / 1000).toFixed(1)}s`
        : `${durMs}ms`
      : '';

  return (
    <>
      <button
        type="button"
        className="tool-row"
        onClick={() => setOpen((o) => !o)}
        style={{ border: 0, background: 'transparent', font: 'inherit', color: 'inherit', width: '100%' }}
      >
        <span className="tool-icon">
          <IconTool s={11} />
        </span>
        <span className="tool-name">{tc.name}</span>
        <span className="tool-input">{inputText}</span>
        {durStr && <span className="tool-dur">{durStr}</span>}
        {ok ? (
          <span className="tool-check"><IconCheck s={12} /></span>
        ) : (
          <span className="tool-err"><IconX s={12} /></span>
        )}
      </button>
      {open && (
        <div className="tool-expanded">
          <div className="tool-section">
            <div className="tool-section-label">input</div>
            <pre>{inputText}</pre>
          </div>
          {outputText && (
            <div className="tool-section">
              <div className="tool-section-label">
                output · {outputText.split('\n').length} lines
              </div>
              <pre>{outputText}</pre>
            </div>
          )}
        </div>
      )}
    </>
  );
};

const InlineConfirmationCard: React.FC<{
  payload: ConfirmationPromptPayload;
  answeredAt?: string;
  state?: string;
  onDecision?: (decision: ConfirmationDecision) => void;
}> = ({ payload, answeredAt, state, onDecision }) => {
  const [submitted, setSubmitted] = useState<ConfirmationDecision | null>(null);
  const done = !!answeredAt || submitted != null;
  if (done) {
    const label = state === 'denied' || submitted === 'DENIED' ? 'Denied' : 'Approved';
    return (
      <div className="confirm-card confirm-card-summary" aria-label="Approval summary">
        <div className="confirm-header">
          <span className="confirm-header-dot" aria-hidden="true" />
          {label}
        </div>
        <div className="confirm-title">{payload.title}</div>
        <div className="confirm-meta">
          tool: <strong>{payload.installTool}</strong>
          <span className="confirm-meta-sep">·</span>
          target: <code className="confirm-target">{payload.installTarget}</code>
        </div>
      </div>
    );
  }
  return (
    <InstallConfirmationCard
      payload={payload}
      submitting={submitted != null}
      submittingDecision={submitted}
      onDecision={(decision) => {
        if (submitted != null) return;
        setSubmitted(decision);
        onDecision?.(decision);
      }}
    />
  );
};

const ChatWindow: React.FC<ChatWindowProps> = ({
  messages,
  loading,
  onSend,
  inputDisabled,
  inflightTools,
  streamingText,
  compactionNotice,
  onCompactionDismiss,
  agentName,
  onAnswerAsk,
  onConfirmDecision,
  slashCommandConfig,
  multimodalEnabled,
  onOpenAgentConfig,
  sessionResetKey,
  streamingReasoningText,
  reasoningDurationMs,
  agentThinkingVisible,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const onSendRef = useRef(onSend);
  useEffect(() => { onSendRef.current = onSend; }, [onSend]);
  const stableOnSend = useCallback((text: string, files?: File[]) => onSendRef.current(text, files), []);

  // ─── P10 slash-command modal state ───────────────────────────────────────
  const [commandModalOpen, setCommandModalOpen] = useState(false);
  const [commandModalTitle, setCommandModalTitle] = useState<string>('');
  const [commandModalBody, setCommandModalBody] = useState<string>('');

  const handleShowCommandModal = useCallback((title: string, markdownBody: string) => {
    setCommandModalTitle(title);
    setCommandModalBody(markdownBody);
    setCommandModalOpen(true);
  }, []);

  const slashHandlers: SlashCommandHandlers | undefined = useMemo(() => {
    if (!slashCommandConfig) return undefined;
    return {
      userId: slashCommandConfig.userId,
      sessionId: slashCommandConfig.sessionId,
      onRedirect: slashCommandConfig.onRedirect,
      onModelChanged: slashCommandConfig.onModelChanged,
      onShowModal: handleShowCommandModal,
    };
  }, [slashCommandConfig, handleShowCommandModal]);

  const [summaryExpandedMap, setSummaryExpandedMap] = useState<Record<number, boolean>>({});
  const toggleSummary = useCallback((idx: number) => {
    setSummaryExpandedMap((prev) => ({ ...prev, [idx]: !prev[idx] }));
  }, []);

  const [, setTick] = useState(0);
  useEffect(() => {
    if (!inflightTools || Object.keys(inflightTools).length === 0) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [inflightTools]);

  const isNearBottom = useRef(true);
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    isNearBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
  }, []);
  useEffect(() => {
    if (isNearBottom.current) {
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    }
  }, [messages, streamingText]);

  const inflightList = inflightTools ? Object.entries(inflightTools) : [];
  const assistantName = agentName ?? 'Assistant';

  return (
    <>
      <div className="scroll" ref={scrollRef} onScroll={handleScroll}>
        <div className="thread">
          {compactionNotice && (
            <div className="compact-notice" role="status">
              <IconCompact s={12} />
              Context was compacted to save tokens. Earlier messages may be summarized.
              {onCompactionDismiss && (
                <button
                  type="button"
                  className="x-btn"
                  onClick={onCompactionDismiss}
                  aria-label="Dismiss"
                >
                  <IconX s={12} />
                </button>
              )}
            </div>
          )}
          {messages.map((msg, idx) => {
            if (msg.role === 'summary') {
              const summaryExpanded = summaryExpandedMap[idx] ?? false;
              const summaryBodyId = `summary-body-${idx}`;
              return (
                <div key={`summary-${idx}`} className="msg-compaction-summary">
                  <button
                    type="button"
                    className="mcs-header"
                    onClick={() => toggleSummary(idx)}
                    aria-expanded={summaryExpanded}
                    aria-controls={summaryBodyId}
                  >
                    <IconCompact s={11} />
                    <span className="mcs-header__chevron" aria-hidden="true">
                      {summaryExpanded ? '▴' : '▾'}
                    </span>
                    Context compacted · earlier messages replaced by summary below
                  </button>
                  <div
                    id={summaryBodyId}
                    className={
                      'mcs-body' +
                      (summaryExpanded ? ' mcs-body--expanded' : '')
                    }
                    aria-hidden={!summaryExpanded}
                  >
                    <MarkdownRenderer content={msg.content} />
                  </div>
                </div>
              );
            }
            if (msg.messageType === 'ask_user') {
              const metadata = msg.metadata ?? {};
              const askId = msg.controlId ?? String(metadata.controlId ?? '');
              const options = Array.isArray(metadata.options)
                ? metadata.options
                    .filter((opt): opt is Record<string, unknown> => !!opt && typeof opt === 'object')
                    .map((opt) => ({
                      label: String(opt.label ?? ''),
                      description: opt.description != null ? String(opt.description) : undefined,
                    }))
                : [];
              return (
                <div key={msg.id ?? `ask-${askId || idx}`} className="msg assistant">
                  <PendingAskCard
                    pendingAsk={{
                      askId,
                      question: String(metadata.question ?? msg.content ?? ''),
                      context: metadata.context != null ? String(metadata.context) : undefined,
                      options,
                      allowOther: metadata.allowOther !== false,
                    }}
                    answeredAt={msg.answeredAt}
                    state={typeof metadata.state === 'string' ? metadata.state : undefined}
                    answer={typeof metadata.answer === 'string' ? metadata.answer : undefined}
                    onAnswer={(answer) => {
                      if (askId && onAnswerAsk) onAnswerAsk(askId, answer);
                    }}
                  />
                </div>
              );
            }
            if (msg.messageType === 'confirmation') {
              const metadata = msg.metadata ?? {};
              const choicesRaw = Array.isArray(metadata.choices)
                ? metadata.choices
                : Array.isArray(metadata.options)
                  ? metadata.options
                  : [];
              const choices = choicesRaw
                .filter((choice): choice is Record<string, unknown> => !!choice && typeof choice === 'object')
                .map((choice) => ({
                  id: choice.id != null ? String(choice.id) : undefined,
                  value: choice.value != null ? String(choice.value) : undefined,
                  label: String(choice.label ?? choice.value ?? ''),
                  style: choice.style != null ? String(choice.style) : undefined,
                  description: choice.description != null ? String(choice.description) : undefined,
                }));
              const payload: ConfirmationPromptPayload = {
                confirmationId: msg.controlId ?? String(metadata.confirmationId ?? metadata.controlId ?? ''),
                sessionId: String(metadata.sessionId ?? ''),
                installTool: String(metadata.installTool ?? metadata.toolName ?? 'unknown'),
                installTarget: String(metadata.installTarget ?? '*'),
                commandPreview: String(metadata.commandPreview ?? ''),
                title: String(metadata.title ?? metadata.question ?? msg.content ?? 'Approval required'),
                description: String(metadata.description ?? ''),
                choices,
                expiresAt: String(metadata.expiresAt ?? ''),
              };
              return (
                <div key={msg.id ?? `confirmation-${msg.controlId ?? idx}`} className="msg assistant">
                  <InlineConfirmationCard
                    payload={payload}
                    answeredAt={msg.answeredAt}
                    state={typeof metadata.state === 'string' ? metadata.state : undefined}
                    onDecision={(decision) => {
                      if (payload.confirmationId && onConfirmDecision) {
                        onConfirmDecision(payload.confirmationId, decision);
                      }
                    }}
                  />
                </div>
              );
            }
            const isUser = msg.role === 'user';
            return (
              <div key={msg.id ?? `${msg.role}-${idx}`} className={`msg ${msg.role}`}>
                <div className="msg-head">
                  <RoleAvatar role={isUser ? 'user' : 'assistant'} />
                  <span className="msg-name">{isUser ? 'You' : assistantName}</span>
                  <span className="msg-time">
                    {msg.timestamp ? formatTime(new Date(msg.timestamp)) : ''}
                  </span>
                </div>
                <div className="msg-body">
                  {/* MULTIMODAL-MVP Phase 2: render inline thumbnails above
                      the bubble text. Only for user bubbles for now (BE only
                      attaches image_ref / pdf_ref on user messages — assistant
                      replies stay text-only). userId / sessionId are required
                      to authenticate the blob fetch; both flow in via the
                      slashCommandConfig prop which the parent always sets in
                      production paths. */}
                  {isUser && msg.attachments && msg.attachments.length > 0 && slashCommandConfig && (
                    <div className="msg-attachments">
                      {msg.attachments.map((att) => (
                        <AttachmentThumbnail
                          key={att.attachmentId}
                          kind={att.kind}
                          attachmentId={att.attachmentId}
                          filename={att.filename}
                          pageCount={att.pageCount}
                          sheetCount={att.sheetCount}
                          userId={slashCommandConfig.userId}
                          sessionId={slashCommandConfig.sessionId}
                        />
                      ))}
                    </div>
                  )}
                  {/* CHAT-REASONING-PANEL: completed-message reasoning panel.
                      Renders above the bubble text. ReasoningPanel returns
                      null when reasoningContent is empty/whitespace, so this
                      is safe to mount unconditionally — keeps the JSX flat
                      for assistant messages without reasoning. Historical
                      messages have no `durationMs` available, so we pass
                      null → label shows just `Thought ▾`. */}
                  {!isUser && (
                    <ReasoningPanel
                      reasoningContent={msg.reasoningContent}
                      durationMs={null}
                      defaultExpanded={agentThinkingVisible ?? false}
                    />
                  )}
                  {isUser ? msg.content : <MarkdownRenderer content={msg.content} />}
                  {!isUser && msg.toolCalls && msg.toolCalls.length > 0 && (
                    <div className="tool-calls">
                      {msg.toolCalls.map((tc, i) => (
                        <ToolCallRow key={tc.id ?? i} tc={tc} />
                      ))}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
          {/* CHAT-REASONING-PANEL streaming flow (C1 in plan-notes-for-dev.md):
              - reasoning_delta arrives → streamingReasoningText accumulates →
                panel renders in streaming mode (spinner + live text).
              - First text_delta arrives → handler clears reasoningStartTsRef
                and sets reasoningDurationMs; streamingReasoningText STAYS
                populated so the panel transitions to completed mode
                (Thought for N.Ns ▾) sitting above the assistant text bubble.
              - message_appended → handler clears both streamingReasoningText
                and streamingText; final ReasoningPanel for the assistant
                message is rendered by the messages.map above. */}
          {(streamingReasoningText && streamingReasoningText.length > 0) && (
            <div className="msg assistant streaming-reasoning-wrap">
              <div className="msg-head">
                <RoleAvatar role="assistant" />
                <span className="msg-name">{assistantName}</span>
              </div>
              <ReasoningPanel
                streamingText={streamingReasoningText}
                reasoningContent={streamingReasoningText}
                durationMs={reasoningDurationMs ?? null}
                defaultExpanded={agentThinkingVisible ?? false}
                /* While the timer has not fired yet (text_delta hasn't started),
                   force streaming mode. Once durationMs is set, the panel
                   falls back to completed mode (text + collapsed header). */
                forceStreaming={(reasoningDurationMs ?? null) === null}
              />
            </div>
          )}
          {streamingText && streamingText.length > 0 && (
            <div className="msg assistant">
              <div className="msg-head">
                <RoleAvatar role="assistant" />
                <span className="msg-name">{assistantName}</span>
              </div>
              <div className="msg-body">
                <ThrottledMarkdown content={streamingText} />
                <span style={{ color: 'var(--fg-4)', fontSize: 12, marginLeft: 4 }}>▍</span>
              </div>
            </div>
          )}
          {inflightList.map(([toolUseId, info]) => {
            const elapsed = Math.max(0, Math.floor((Date.now() - info.startTs) / 1000));
            let inputPreview = '';
            try {
              inputPreview =
                typeof info.input === 'string' ? info.input : JSON.stringify(info.input);
            } catch {
              inputPreview = '';
            }
            if (inputPreview.length > 120) inputPreview = inputPreview.slice(0, 120) + '…';
            return (
              <div key={toolUseId} className="inflight">
                <span className="spinner" />
                <span className="inflight-name">{info.name}</span>
                <span className="inflight-input">{inputPreview}</span>
                <span className="inflight-time">{elapsed}s</span>
              </div>
            );
          })}
          {loading && !streamingText && inflightList.length === 0 && (
            <div style={{ textAlign: 'center', padding: 16, color: 'var(--fg-3)', fontSize: 13 }}>
              Agent is thinking…
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>
      <ChatInput
        disabled={inputDisabled}
        onSend={stableOnSend}
        slashCommands={slashHandlers}
        multimodalEnabled={multimodalEnabled}
        onOpenAgentConfig={onOpenAgentConfig}
        sessionResetKey={sessionResetKey}
      />
      <CommandResultModal
        open={commandModalOpen}
        title={commandModalTitle}
        markdownBody={commandModalBody}
        onClose={() => setCommandModalOpen(false)}
      />
    </>
  );
};

export default ChatWindow;
