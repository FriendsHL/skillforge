export interface ModelOption {
  id: string;
  label: string;
  provider: string;
  model: string;
  isDefault: boolean;
}

// Fallback when /api/llm/models fails (matches historical hardcoded list shape).
export const FALLBACK_MODEL_OPTIONS: ModelOption[] = [
  { id: 'bailian:qwen3.5-plus', label: 'bailian:qwen3.5-plus', provider: 'bailian', model: 'qwen3.5-plus', isDefault: false },
  { id: 'bailian:qwen3-max-2026-01-23', label: 'bailian:qwen3-max-2026-01-23', provider: 'bailian', model: 'qwen3-max-2026-01-23', isDefault: false },
  { id: 'bailian:qwen3-coder-next', label: 'bailian:qwen3-coder-next', provider: 'bailian', model: 'qwen3-coder-next', isDefault: false },
  { id: 'bailian:glm-5', label: 'bailian:glm-5', provider: 'bailian', model: 'glm-5', isDefault: false },
  { id: 'openai:deepseek-chat', label: 'openai:deepseek-chat', provider: 'openai', model: 'deepseek-chat', isDefault: false },
  { id: 'openai:gpt-4o', label: 'openai:gpt-4o', provider: 'openai', model: 'gpt-4o', isDefault: false },
  { id: 'claude:claude-sonnet-4-20250514', label: 'claude:claude-sonnet-4-20250514', provider: 'claude', model: 'claude-sonnet-4-20250514', isDefault: false },
];
