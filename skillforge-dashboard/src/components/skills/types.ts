// P1-C-7 (B-1): the legacy `DEFAULT_SOURCE_AGENT_ID = 1` placeholder has been
// removed. SkillList now requires the operator to pick a source agent from a
// selector before extract / A-B / evolution actions are enabled. Skill API
// writes inject `userId` from `useAuth()`, mirroring the chat API contract.

/** P1-D governance: artifact lifecycle state on disk; null/legacy rows treated as 'active'. */
export type SkillArtifactStatus = 'active' | 'missing' | 'invalid' | 'shadowed';

/** P1-D: explicit type axis. system = built-in/registry-bundled, runtime = user/agent-authored. */
export type SkillType = 'system' | 'runtime';

export interface SkillRow {
  id: number | string;
  name: string;
  description?: string;
  /**
   * UI category (system vs custom). Derived from `isSystem`. Kept for backward
   * compat with the legacy filter sidebar + `src-system` / `src-custom` CSS.
   * For the actual provenance string from the backend (upload / skill-creator
   * / skillhub / clawhub / etc.) use `originSource`.
   */
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

  // ─── P1-D governance fields (T8 backend, T9 frontend) ─────────────────────
  /** True when SkillEntity.isSystem; system skills are read-only and undeletable. */
  isSystem: boolean;
  /** Artifact lifecycle on disk; absent on legacy rows → treated as 'active'. */
  artifactStatus?: SkillArtifactStatus;
  /** Absolute filesystem path that owns this skill's artifact directory. */
  skillPath?: string;
  /** Name of the skill currently shadowing this one (set when artifactStatus === 'shadowed'). */
  shadowedBy?: string;
  /** ISO timestamp of the last filesystem scan that touched this row. */
  lastScannedAt?: string;
  /**
   * Backend source string — provenance of this skill. Examples:
   * `upload` | `skill-creator` | `draft-approve` | `evolution-fork`
   * | `skillhub` | `clawhub` | `github` | `filesystem`.
   * Distinct from `source` which is the UI system/custom category.
   */
  originSource?: string;
  /** Convenience axis for the system/runtime tag (mirrors `isSystem`). */
  type?: SkillType;
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
