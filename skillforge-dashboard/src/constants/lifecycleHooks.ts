/**
 * N3 Lifecycle Hooks — frontend constants + Zod validation schema.
 * Mirrors the backend design (docs/design-n3-lifecycle-hooks.md §2 + §5.5).
 * Handler is polymorphic via discriminatedUnion('type'); P0 UI only enables `skill`.
 */

import { z } from 'zod';

// ─── Event metadata ──────────────────────────────────────────────────────────

export const LIFECYCLE_HOOK_EVENT_IDS = [
  'SessionStart',
  'UserPromptSubmit',
  'PostToolUse',
  'Stop',
  'SessionEnd',
] as const;

export type LifecycleHookEventId = (typeof LIFECYCLE_HOOK_EVENT_IDS)[number];

export interface LifecycleHookEventMeta {
  id: LifecycleHookEventId;
  displayName: string;
  description: string;
  /** Example input fields surfaced in UI tooltip (not a runtime schema). */
  inputSchema: Record<string, string>;
  /** Whether this event can ABORT the main loop (UserPromptSubmit / SessionStart). */
  canAbort: boolean;
}

/**
 * Fallback metadata if `GET /api/lifecycle-hooks/events` is unavailable.
 * Backend returns the same shape; keep keys in sync when adding events.
 */
export const DEFAULT_LIFECYCLE_HOOK_EVENTS: LifecycleHookEventMeta[] = [
  {
    id: 'SessionStart',
    displayName: '会话开始',
    description: '用户在 session 中发送第一条消息时触发,可用于注入上下文或启动监控。',
    inputSchema: { agent_name: 'string', user_id: 'long', session_id: 'string' },
    canAbort: true,
  },
  {
    id: 'UserPromptSubmit',
    displayName: '用户提交 Prompt',
    description: '每一轮 loop 开始、prompt 送入 LLM 之前触发。适合内容过滤、敏感词拦截、prompt 增强。',
    inputSchema: {
      user_message: 'string',
      message_count: 'int',
      session_id: 'string',
    },
    canAbort: true,
  },
  {
    id: 'PostToolUse',
    displayName: '工具调用后',
    description: '每次 Skill / tool 执行完、结果回传 LLM 之前触发。适合审计、结果清洗。',
    inputSchema: {
      skill_name: 'string',
      skill_input: 'object',
      skill_output: 'string',
      success: 'bool',
      duration_ms: 'long',
    },
    canAbort: false,
  },
  {
    id: 'Stop',
    displayName: 'Loop 结束',
    description: 'Agent Loop 正常结束(end_turn / 达到 maxLoops)时触发。适合清理、汇总。',
    inputSchema: {
      loop_count: 'int',
      total_input_tokens: 'long',
      total_output_tokens: 'long',
    },
    canAbort: false,
  },
  {
    id: 'SessionEnd',
    displayName: 'Session 结束',
    description: 'Session 关闭(用户取消 / error 终止 / 手动关闭)时异步触发。常用于摘要写入外部系统。',
    inputSchema: {
      user_id: 'long',
      message_count: 'int',
      reason: 'string',
    },
    canAbort: false,
  },
];

// ─── Handler types ───────────────────────────────────────────────────────────

export const HOOK_HANDLER_TYPES = ['skill', 'script', 'method'] as const;
export type HookHandlerType = (typeof HOOK_HANDLER_TYPES)[number];

export interface HookHandlerTypeMeta {
  value: HookHandlerType;
  label: string;
  availability: 'available' | 'p1' | 'p2';
  description: string;
}

export const HOOK_HANDLER_TYPE_META: HookHandlerTypeMeta[] = [
  {
    value: 'skill',
    label: 'Skill',
    availability: 'available',
    description: '调用已注册的 Skill。推荐。',
  },
  {
    value: 'script',
    label: 'Script',
    availability: 'available',
    description: '内联 bash / node 脚本,沙箱子进程执行。',
  },
  {
    value: 'method',
    label: 'Method',
    availability: 'p2',
    description: '平台内置方法(P2 实现)。',
  },
];

/** Allowed script languages (P1 = bash, node; python reserved for P2). */
export const SCRIPT_LANG_OPTIONS = [
  { value: 'bash', label: 'Bash' },
  { value: 'node', label: 'Node.js' },
] as const;

export type ScriptLang = (typeof SCRIPT_LANG_OPTIONS)[number]['value'];

/** localStorage key for "user already confirmed script-handler risk" flag. */
export const SCRIPT_CONFIRM_STORAGE_KEY = 'sf.lifecycle.scriptConfirmed';

// ─── Policy / limits ─────────────────────────────────────────────────────────

export const FAILURE_POLICIES = ['CONTINUE', 'ABORT', 'SKIP_CHAIN'] as const;
export type FailurePolicy = (typeof FAILURE_POLICIES)[number];

export const TIMEOUT_MIN_SECONDS = 1;
export const TIMEOUT_MAX_SECONDS = 300;
export const TIMEOUT_DEFAULT_SECONDS = 30;

export const MAX_ENTRIES_PER_EVENT = 10;
export const MAX_SCRIPT_BODY_BYTES = 4096;

// ─── Zod schema (handler discriminatedUnion) ─────────────────────────────────

const skillHandlerSchema = z.object({
  type: z.literal('skill'),
  skillName: z.string().min(1, 'skillName is required'),
  args: z.record(z.string(), z.unknown()).optional(),
});

const scriptHandlerSchema = z.object({
  type: z.literal('script'),
  scriptLang: z.enum(['bash', 'node']),
  scriptBody: z
    .string()
    .min(1, 'scriptBody is required')
    .max(MAX_SCRIPT_BODY_BYTES, 'scriptBody too large (max 4KB)'),
  args: z.record(z.string(), z.unknown()).optional(),
});

const methodHandlerSchema = z.object({
  type: z.literal('method'),
  methodRef: z.string().min(1, 'methodRef is required'),
  args: z.record(z.string(), z.unknown()).optional(),
});

export const hookHandlerSchema = z.discriminatedUnion('type', [
  skillHandlerSchema,
  scriptHandlerSchema,
  methodHandlerSchema,
]);

export const hookEntrySchema = z
  .object({
    handler: hookHandlerSchema,
    timeoutSeconds: z
      .number()
      .int()
      .min(TIMEOUT_MIN_SECONDS)
      .max(TIMEOUT_MAX_SECONDS)
      .default(TIMEOUT_DEFAULT_SECONDS),
    failurePolicy: z.enum(FAILURE_POLICIES).default('CONTINUE'),
    async: z.boolean().default(false),
    displayName: z.string().optional(),
    _id: z.string().optional(),
  })
  .refine((entry) => !(entry.async === true && entry.failurePolicy === 'SKIP_CHAIN'), {
    message: 'async entry cannot use SKIP_CHAIN policy',
    path: ['failurePolicy'],
  });

export const lifecycleHooksConfigSchema = z.object({
  version: z.literal(1),
  hooks: z
    .object({
      SessionStart: z.array(hookEntrySchema).max(MAX_ENTRIES_PER_EVENT).default([]),
      UserPromptSubmit: z.array(hookEntrySchema).max(MAX_ENTRIES_PER_EVENT).default([]),
      PostToolUse: z.array(hookEntrySchema).max(MAX_ENTRIES_PER_EVENT).default([]),
      Stop: z.array(hookEntrySchema).max(MAX_ENTRIES_PER_EVENT).default([]),
      SessionEnd: z.array(hookEntrySchema).max(MAX_ENTRIES_PER_EVENT).default([]),
    })
    .partial(),
});

export type HookHandler = z.infer<typeof hookHandlerSchema>;
export type HookEntry = z.infer<typeof hookEntrySchema>;
export type LifecycleHooksConfig = z.infer<typeof lifecycleHooksConfigSchema>;

// ─── Defaults ────────────────────────────────────────────────────────────────

export const EMPTY_HOOKS_CONFIG: LifecycleHooksConfig = {
  version: 1,
  hooks: {
    SessionStart: [],
    UserPromptSubmit: [],
    PostToolUse: [],
    Stop: [],
    SessionEnd: [],
  },
};

/** Stringified empty config — single source of truth for first-render rawJson. */
export const EMPTY_HOOKS_JSON = JSON.stringify(EMPTY_HOOKS_CONFIG, null, 2);

export const DEFAULT_NEW_ENTRY: HookEntry = {
  handler: { type: 'skill', skillName: '' },
  timeoutSeconds: TIMEOUT_DEFAULT_SECONDS,
  failurePolicy: 'CONTINUE',
  async: false,
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Which failure policies are allowed per event. ABORT only makes sense for
 * synchronous, pre-LLM events (SessionStart / UserPromptSubmit).
 */
export function allowedFailurePolicies(eventId: LifecycleHookEventId): FailurePolicy[] {
  if (eventId === 'UserPromptSubmit' || eventId === 'SessionStart') {
    return ['CONTINUE', 'ABORT', 'SKIP_CHAIN'];
  }
  return ['CONTINUE', 'SKIP_CHAIN'];
}

export interface JsonParseResult {
  parsed: LifecycleHooksConfig | null;
  errors: string[];
}

/** Safe parse + Zod validation. Returns both parsed config and human-readable errors. */
export function safeParseHooksJson(raw: string): JsonParseResult {
  let obj: unknown;
  try {
    obj = JSON.parse(raw);
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'Invalid JSON';
    return { parsed: null, errors: [`JSON syntax: ${msg}`] };
  }
  const result = lifecycleHooksConfigSchema.safeParse(obj);
  if (!result.success) {
    const errors = result.error.issues.map((issue) => {
      const path = issue.path.join('.') || '<root>';
      return `${path}: ${issue.message}`;
    });
    return { parsed: null, errors };
  }
  // Ensure all event arrays exist (schema marks them partial but we want concrete arrays)
  const filled: LifecycleHooksConfig = {
    version: 1,
    hooks: {
      SessionStart: result.data.hooks.SessionStart ?? [],
      UserPromptSubmit: result.data.hooks.UserPromptSubmit ?? [],
      PostToolUse: result.data.hooks.PostToolUse ?? [],
      Stop: result.data.hooks.Stop ?? [],
      SessionEnd: result.data.hooks.SessionEnd ?? [],
    },
  };
  return { parsed: filled, errors: [] };
}

/** Serialize config to rawJson with 2-space indent. Strips the UI-only `_id` field from entries. */
export function stringifyHooks(config: LifecycleHooksConfig): string {
  return JSON.stringify(config, (key, value) => (key === '_id' ? undefined : value), 2);
}
