/**
 * Protocol family identifier. Mirrors the backend `ProviderProtocolFamily`
 * enum string values — used to decide whether `thinkingMode` / `reasoningEffort`
 * are wire-visible for a given model, and to inform UI tooltip copy.
 *
 * Keep this list in sync with `ProviderProtocolFamily.java` on the server.
 */
export type ProtocolFamily =
  | 'qwen_dashscope'
  | 'deepseek_v4'
  | 'deepseek_chat_legacy'
  | 'deepseek_reasoner_legacy'
  | 'openai_reasoning'
  | 'generic_openai'
  | 'claude';

export interface ModelOption {
  id: string;
  label: string;
  provider: string;
  model: string;
  isDefault: boolean;
  /** Whether the model honours `thinkingMode` (enabled/disabled) at the provider level. */
  supportsThinking: boolean;
  /** Whether the model honours `reasoningEffort` (high/max) — narrower than supportsThinking. */
  supportsReasoningEffort: boolean;
  /** Informational; server is the source of truth once /api/llm/models returns it. */
  protocolFamily?: ProtocolFamily;
}

// Fallback when /api/llm/models fails or returns empty. The server remains the
// source of truth; these flags only keep the UI functional while the request
// is in-flight or errored. Treat any missing capability flag as `false` at the
// call site (don't promote a default here — new models the backend knows about
// must surface their real capability via the API, not this fallback table).
export const FALLBACK_MODEL_OPTIONS: ModelOption[] = [
  { id: 'bailian:qwen3.5-plus',             label: 'bailian:qwen3.5-plus',             provider: 'bailian',  model: 'qwen3.5-plus',             isDefault: false, supportsThinking: true,  supportsReasoningEffort: false, protocolFamily: 'qwen_dashscope' },
  { id: 'bailian:qwen3-max-2026-01-23',     label: 'bailian:qwen3-max-2026-01-23',     provider: 'bailian',  model: 'qwen3-max-2026-01-23',     isDefault: false, supportsThinking: true,  supportsReasoningEffort: false, protocolFamily: 'qwen_dashscope' },
  { id: 'bailian:qwen3-coder-next',         label: 'bailian:qwen3-coder-next',         provider: 'bailian',  model: 'qwen3-coder-next',         isDefault: false, supportsThinking: true,  supportsReasoningEffort: false, protocolFamily: 'qwen_dashscope' },
  { id: 'bailian:glm-5',                    label: 'bailian:glm-5',                    provider: 'bailian',  model: 'glm-5',                    isDefault: false, supportsThinking: false, supportsReasoningEffort: false, protocolFamily: 'generic_openai' },
  { id: 'deepseek:deepseek-chat',           label: 'deepseek:deepseek-chat',           provider: 'deepseek', model: 'deepseek-chat',            isDefault: false, supportsThinking: false, supportsReasoningEffort: false, protocolFamily: 'deepseek_chat_legacy' },
  { id: 'deepseek:deepseek-v4-pro',         label: 'deepseek:deepseek-v4-pro',         provider: 'deepseek', model: 'deepseek-v4-pro',          isDefault: false, supportsThinking: true,  supportsReasoningEffort: true,  protocolFamily: 'deepseek_v4' },
  { id: 'claude:claude-sonnet-4-20250514',  label: 'claude:claude-sonnet-4-20250514',  provider: 'claude',   model: 'claude-sonnet-4-20250514', isDefault: false, supportsThinking: false, supportsReasoningEffort: false, protocolFamily: 'claude' },
];
