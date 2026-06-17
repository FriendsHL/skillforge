/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 (FE) — skill-draft evaluation report types
 * and API wrappers.
 *
 * The shapes in this file mirror the BE Java `EvaluationResult` record defined
 * in `docs/requirements/active/SKILL-CREATOR-WITH-EVAL/tech-design.md` (and to be
 * implemented in Phase 1.1 as `SkillCreatorService` writes `t_skill_draft
 * .evaluation_result_json`). Jackson default serialization on the BE produces
 * camelCase keys, so each TS field matches the Java field name 1:1.
 *
 * **java.md known footgun #6 (FE-BE contract)**: when the BE record lands, a
 * round-trip IT MUST verify ObjectMapper-written keys against these field
 * names. Renaming any field below requires a synchronised BE change.
 */
import api from './client';
import type { SkillDraft } from './drafts';

/**
 * Per-baseline metric vector. The `compositeScore` / `overallScore` fields come
 * from `EvalJudgeTool.judgeMultiTurnConversation`, while `passRate`,
 * `avgLatencyMs`, and `totalCostUsd` are aggregated by the BE
 * `SkillCreatorEvalCoordinator` from per-scenario `t_subagent_run` rows.
 */
export interface SkillMetrics {
  /** judge composite (0..1, holistic blend) */
  compositeScore: number;
  /** judge overall (0..1) */
  overallScore: number;
  /** count(compositeScore >= 0.7) / N — service-side aggregation */
  passRate: number;
  /** wall-time mean across scenarios */
  avgLatencyMs: number;
  /** sum of token cost across scenarios */
  totalCostUsd: number;
}

/**
 * Full evaluation result blob persisted at `t_skill_draft.evaluation_result_json`.
 * `delta` is `withSkill - withoutSkill` per axis (BE-computed).
 */
export interface EvaluationResult {
  withSkill: SkillMetrics;
  withoutSkill: SkillMetrics;
  delta: SkillMetrics;
  llmSummary: string;
  sourceSessionIds: string[];
  scenarioCount: number;
  /** ISO-8601 instant (Jackson `Instant` default) */
  evaluatedAt: string;
  evaluatorVersion: string;
}

/**
 * BE Phase 1.1 will accept a `status` filter on the existing
 * `GET /api/skill-drafts` endpoint (or expose `GET /api/skills/drafts?status=...`
 * — the tech-design references both; we use the canonical `/skill-drafts`
 * endpoint with a status query param so this wrapper keeps working whether or
 * not the alias path ships).
 *
 * Until BE Phase 1.1 lands, callers will get whatever rows the legacy endpoint
 * returns (typically `status='draft'` only). FE filtering then narrows by
 * `draft.status === 'rejected'` client-side — see `SkillDrafts.tsx`.
 */
export const getRejectedDrafts = (userId: number) =>
  api.get<SkillDraft[]>('/skill-drafts', { params: { userId, status: 'rejected' } });

export const getEvaluatedPassedDrafts = (userId: number) =>
  api.get<SkillDraft[]>('/skill-drafts', {
    params: { userId, status: 'evaluated_passed' },
  });

/**
 * Helper: derive a verdict from a result blob (used by the report component
 * to colour Alert / status badge without recomputing inline). The 5pp threshold
 * is the BE default (`PASS_RATE_DELTA_THRESHOLD = 0.05` in `SkillCreatorService`).
 */
export const PASS_RATE_DELTA_THRESHOLD = 0.05;

export function isPassingResult(result: EvaluationResult): boolean {
  return result.delta.passRate >= PASS_RATE_DELTA_THRESHOLD;
}

/**
 * SKILL-CREATOR-PHASE-1.6 (FE F4) — manual evaluation trigger payload.
 *
 * Mirrors the BE Phase 1.2 controller `POST /api/skill-drafts/{id}/evaluate`
 * `@RequestBody` shape (camelCase Jackson default). Operator selects a target
 * agent (filtered to `agentType=user`) in `TriggerEvaluationModal` and
 * optionally narrows the source-session set; if `scenarios` is omitted the BE
 * falls back to the draft's own `sourceSessionId` (or `firstByCreatedAt` for
 * extract-from-sessions drafts).
 *
 * **java.md known footgun #6 (FE-BE contract)**: when be-dev lands Phase 1.2,
 * a grep of `SkillDraftController.evaluate` must confirm `@RequestBody` field
 * names match `targetAgentId` / `scenarios` / `threshold` 1:1. Renaming any
 * field here requires a synchronised BE change (silent reflection failure
 * otherwise: BE deserialises null, evaluation runs against unrelated agent /
 * uses wrong threshold, single-test coverage on either side won't catch it).
 *
 * `threshold` is sent only when an operator overrides the BE default of 5pp;
 * Ratify decision keeps the UI hardcoded at 5pp for Phase 1.6 (slider exposed
 * in Phase 1.7) so this field is reserved for future use but already wired.
 */
export interface TriggerEvaluationPayload {
  /** Target agent id — operator picks from `getAgents('user')`. Required. */
  targetAgentId: number;
  /** Optional source-session ids; omit to let BE pick the default. */
  scenarios?: string[];
  /** Optional delta threshold override (BE default 0.05 = 5pp). */
  threshold?: number;
}

/**
 * Response shape from BE Phase 1.2 controller. BE writes
 * `t_skill_draft.status='evaluating'` synchronously then dispatches the eval
 * coordinator on AFTER_COMMIT (Phase 1.1-1.3 archive pattern). FE wired to
 * the `session_updated` WS broadcast so the badge flips to "Evaluating…"
 * without a refetch.
 */
export interface TriggerEvaluationResponse {
  draftId: string;
  status: 'evaluating';
}

export const triggerEvaluation = (
  draftId: string,
  payload: TriggerEvaluationPayload,
) =>
  api.post<TriggerEvaluationResponse>(
    `/skill-drafts/${draftId}/evaluate`,
    payload,
  );
