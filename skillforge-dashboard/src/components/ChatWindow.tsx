import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Tooltip, message } from 'antd';
import { type ToolCall } from './ToolCallTimeline';
import MarkdownRenderer from './MarkdownRenderer';
import PendingAskCard from './PendingAskCard';
import InstallConfirmationCard from './InstallConfirmationCard';
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

const formatTime = (date: Date): string =>
  date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

const ThrottledMarkdown: React.FC<{ content: string }> = ({ content }) => {
  const [rendered, setRendered] = useState(content);
  const latestRef = useRef(content);
  latestRef.current = content;

  useEffect(() => {
    const interval = setInterval(() => setRendered(latestRef.current), 200);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (content && !rendered) setRendered(content);
  }, [content, rendered]);

  return <MarkdownRenderer content={rendered} />;
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

const MULTIMODAL_GATE_TOOLTIP = '请先在 agent 配置中选择多模态模型';

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
 * r2 W1 — Compact type badge derived from the picked file's MIME. We only
 * accept image/* and application/pdf (enforced upstream in handleFileChange),
 * so the dichotomy below is exhaustive.
 */
function chipKindLabel(mime: string): 'PDF' | 'IMG' {
  return mime === 'application/pdf' ? 'PDF' : 'IMG';
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
      const next = Array.from(e.target.files ?? []).filter((file) => {
        return file.type.startsWith('image/') || file.type === 'application/pdf';
      });
      if (next.length !== (e.target.files?.length ?? 0)) {
        message.warning('Only images and PDFs are supported');
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
                  {chipKindLabel(file.type)}
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
              accept="image/*,application/pdf"
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
              return (
                <div key={`summary-${idx}`} className="msg-compaction-summary">
                  <div className="mcs-header">
                    <IconCompact s={11} />
                    Context compacted · earlier messages replaced by summary below
                  </div>
                  <div className="mcs-body">
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
