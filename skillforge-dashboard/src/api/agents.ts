import api from './client';
import type { ModelOption } from '../constants/models';

// Agent API

export interface CreateAgentRequest {
  name: string;
  description?: string;
  role?: string;
  systemPrompt?: string;
  soulPrompt?: string;
  toolsPrompt?: string;
  modelId?: string;
  executionMode?: 'ask' | 'auto';
  maxLoops?: number;
  skillIds?: string;
  toolIds?: string;
  public?: boolean;
  behaviorRules?: string;
  lifecycleHooks?: string;
  /** Thinking Mode v1 — `auto` preserves the provider's default behaviour. */
  thinkingMode?: 'auto' | 'enabled' | 'disabled';
  /** Only honoured by models whose protocolFamily exposes reasoning effort (see ModelOption). */
  reasoningEffort?: 'low' | 'medium' | 'high' | 'max';
  /**
   * CHAT-REASONING-PANEL — per-agent default visibility for the reasoning
   * panel rendered above assistant bubbles. `null` (or omitted) = follow
   * global default (collapsed). `true` = panel starts expanded for sessions
   * using this agent. `false` = panel starts collapsed. Stored on
   * `t_agent.thinking_visible` (nullable BOOLEAN, V119 migration).
   */
  thinkingVisible?: boolean | null;
  /** P1 Skill Control Plane: JSON-array string of system-skill names this agent has opted out of. */
  disabledSystemSkills?: string;
  /**
   * MCP-CLIENT-MVP — comma-separated list of MCP server names this agent enables
   * (e.g. `"time,github"`, or `""` for none). Stored as VARCHAR(512) on
   * `t_agent.mcp_server_ids`. **Not** a JSON array — see `schemas.ts#mcpServerIds`
   * for the rationale (server names are restricted to `[a-z0-9_]+` so commas
   * are unambiguous).
   */
  mcpServerIds?: string;
}

export interface UpdateAgentRequest extends Partial<CreateAgentRequest> {
  id?: number;
  // `public` mirrors AgentEntity#isPublic; omitted values preserve existing visibility.
  public?: boolean;
}

/**
 * SYSTEM-AGENT-TYPING Phase 2.2 — list agents with optional `agentType` filter.
 * BE default = `'user'` (returns user-created agents only). Pass `'all'` to
 * include system agents (cron-managed) in the response; pass `'system'` to
 * fetch system agents only. Existing callers (Chat sidebar, hooks editors,
 * etc.) continue to work without changes because the param is optional —
 * but when omitted, the BE returns the smaller `'user'` set by design.
 */
export const getAgents = (agentType?: 'user' | 'system' | 'all') =>
  api.get('/agents', { params: agentType ? { agentType } : undefined });
export const getAgent = (id: number) => api.get(`/agents/${id}`);
export const createAgent = (data: CreateAgentRequest) => api.post('/agents', data);
export const updateAgent = (id: number, data: UpdateAgentRequest) => api.put(`/agents/${id}`, data);
export const deleteAgent = (id: number) => api.delete(`/agents/${id}`);

// LLM models
export const getLlmModels = () => api.get<ModelOption[]>('/llm/models');

// Tool API (Java function-calling tools: Bash, Read, etc.)
export const getTools = () => api.get('/tools');
