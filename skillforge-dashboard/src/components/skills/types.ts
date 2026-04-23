// TODO(P1-1 follow-up): source agentId from an agent selector or route context.
// Current SkillList is a global page with no agent scope — placeholder until a
// picker lands (tracked in docs/design-self-improve-pipeline.md).
export const DEFAULT_SOURCE_AGENT_ID = 1;

export interface SkillRow {
  id: number | string;
  name: string;
  description?: string;
  source: 'system' | 'custom';
  lang: string;
  enabled: boolean;
  system: boolean;
  requiredTools?: string;
  createdAt?: string;
  tags: string[];
  readOnly?: boolean;
  toolSchema?: unknown;
  semver?: string;
  parentSkillId?: number;
  usageCount?: number;
  successCount?: number;
}

export interface SkillDetailData {
  name: string;
  description?: string;
  skillMd?: string;
  promptContent?: string;
  references?: Record<string, string>;
  scripts?: Array<{ name: string; content: string }>;
  requiredTools?: string;
  enabled?: boolean;
  createdAt?: string;
}
