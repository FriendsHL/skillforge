import api from './index';

/**
 * PROD-LABEL-CLUSTER Phase 1.5 — REST client for `/api/insights/patterns`.
 *
 * Field names mirror the BE `InsightsController.PatternListItem` /
 * `PatternMemberItem` records (Jackson default camelCase serialization).
 * Keep this file in lock-step with `InsightsController.java`'s record
 * definitions; any rename on the wire must update both sides at once.
 */

/** Suspect-surface enum from BE `SessionAnnotationConstants`. */
export type SuspectSurface =
  | 'skill'
  | 'prompt'
  | 'behavior_rule'
  | 'other'
  | 'unclear';

/** Session outcome enum from BE `SessionAnnotationConstants`. */
export type SessionOutcome =
  | 'success'
  | 'partial_success'
  | 'failure'
  | 'cancelled';

export interface PatternListItem {
  id: number;
  signature: string;
  outcome: string;
  suspectSurface: string;
  /** Nullable on BE — the cluster may have no dominant failing tool. */
  topFailingTool: string | null;
  /** Nullable on BE — pattern may not be tied to a single agent. */
  agentId: number | null;
  memberCount: number;
  /** Reserved for V2 attribution (Phase 1.5 returns null). */
  suggestedSurface: string | null;
  /** ISO-8601 string (Instant serialized as ISO by Jackson default). */
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface PatternMemberItem {
  sessionId: string;
  agentName: string | null;
  /** ISO-8601 or null when the session never completed. */
  completedAt: string | null;
  /** Truncated to 200 chars + "…" by BE; nullable. */
  runtimeError: string | null;
  /** LLM-annotated outcome value (success / partial_success / failure / cancelled). */
  outcome: string | null;
  /** LLM annotator's per-session reasoning explaining why this row exists.
   *  Truncated to 200 chars + "…" by BE; nullable when no annotation. */
  outcomeReasoning: string | null;
}

export interface ListPatternsParams {
  outcome?: SessionOutcome;
  surface?: SuspectSurface;
  /** BE filter on `agent_id` column; null/absent = no filter. */
  agent?: number;
  /** BE-clamped to [1, 200], default 50. */
  limit?: number;
}

/**
 * `GET /api/insights/patterns` — BE-side ordering is
 * `ORDER BY member_count DESC, last_seen_at DESC`. FE does not re-sort.
 */
export const listPatterns = (params?: ListPatternsParams) =>
  api.get<PatternListItem[]>('/insights/patterns', { params });

/**
 * `GET /api/insights/patterns/{id}/members` — BE-side ordering is
 * `ORDER BY added_at DESC`. BE-clamped to [1, 500], default 100.
 * Returns 404 when the pattern row is missing.
 */
export const listPatternMembers = (patternId: number, limit?: number) =>
  api.get<PatternMemberItem[]>(`/insights/patterns/${patternId}/members`, {
    params: limit !== undefined ? { limit } : undefined,
  });
