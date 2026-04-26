import { z } from 'zod';

// --- Agent schema ---
export const AgentSchema = z.object({
  id: z.number(),
  name: z.string(),
  description: z.string().optional().nullable(),
  role: z.string().optional().nullable(),
  systemPrompt: z.string().optional().nullable(),
  soulPrompt: z.string().optional().nullable(),
  toolsPrompt: z.string().optional().nullable(),
  modelId: z.string().optional().nullable(),
  executionMode: z.enum(['ask', 'auto']).optional(),
  skillIds: z.union([z.string(), z.array(z.string()), z.array(z.number())]).optional().nullable(),
  toolIds: z.union([z.string(), z.array(z.string())]).optional().nullable(),
  // JSON-serialised config blobs persisted on AgentEntity — keep as string here,
  // hooks (useBehaviorRules / useLifecycleHooks) parse them client-side.
  behaviorRules: z.string().optional().nullable(),
  lifecycleHooks: z.string().optional().nullable(),
  // Surface `public` flag for visibility edits; omitted values preserve current state.
  public: z.boolean().optional(),
  // Thinking Mode v1 (see docs/design-thinking-mode.md). Both fields are
  // optional and nullable so GET from older server builds that don't yet
  // persist these columns round-trips without schema strip.
  thinkingMode: z.enum(['auto', 'enabled', 'disabled']).optional().nullable(),
  reasoningEffort: z.enum(['low', 'medium', 'high', 'max']).optional().nullable(),
});
export type AgentDto = z.infer<typeof AgentSchema>;

/** Enumerated types re-exported for consumers (AgentDrawer, tests). */
export type ThinkingMode = 'auto' | 'enabled' | 'disabled';
export type ReasoningEffort = 'low' | 'medium' | 'high' | 'max';

// --- Session schema ---
export const SessionSchema = z.object({
  id: z.union([z.string(), z.number()]).transform(String),
  title: z.string().optional().nullable(),
  agentId: z.union([z.string(), z.number()]).optional().nullable(),
  agentName: z.string().optional().nullable(),
  messageCount: z.number().optional().nullable(),
  totalTokens: z.number().optional().nullable(),
  runtimeStatus: z.enum(['idle', 'running', 'error', 'waiting_user', 'compacting']).optional(),
  runtimeStep: z.string().optional().nullable(),
  runtimeError: z.string().optional().nullable(),
  channelPlatform: z.string().optional().nullable(),
  createdAt: z.string().optional().nullable(),
  updatedAt: z.string().optional().nullable(),
}).passthrough();
export type SessionDto = z.infer<typeof SessionSchema>;

// --- Message content block schema ---
const ContentBlockSchema = z.union([
  z.object({ type: z.literal('text'), text: z.string() }),
  z.object({ type: z.literal('tool_use'), id: z.string(), name: z.string(), input: z.unknown() }),
  z.object({ type: z.literal('tool_result'), tool_use_id: z.string().optional(), content: z.unknown(), is_error: z.boolean().optional() }),
  z.object({ type: z.string() }).passthrough(),
]);

export const MessageSchema = z.object({
  role: z.enum(['user', 'assistant', 'system']),
  content: z.union([z.string(), z.array(ContentBlockSchema)]),
  timestamp: z.string().optional(),
  toolCalls: z.array(z.unknown()).optional(),
}).passthrough();
export type MessageDto = z.infer<typeof MessageSchema>;

/** Safe list parse: returns the raw list if validation fails (non-crashing). */
export function safeParseList<T extends z.ZodTypeAny>(
  schema: T,
  list: unknown[],
): z.infer<T>[] {
  const result = z.array(schema).safeParse(list);
  if (!result.success) {
    console.warn('[schema] validation warning:', result.error.flatten());
    return list as z.infer<T>[];
  }
  return result.data;
}
