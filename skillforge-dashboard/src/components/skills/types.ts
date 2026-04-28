// P1-C-7 (B-1): the legacy `DEFAULT_SOURCE_AGENT_ID = 1` placeholder has been
// removed. SkillList now requires the operator to pick a source agent from a
// selector before extract / A-B / evolution actions are enabled. Skill API
// writes inject `userId` from `useAuth()`, mirroring the chat API contract.

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
  /** P1-C-6 telemetry: counted via SkillEntity.failureCount; absent for older rows. */
  failureCount?: number;
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
