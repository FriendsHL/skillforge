import api from './client';

// ─── Skill Drafts ───────────────────────────────────────────────────────────

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.3 — `EvaluationResult` mirror lives in
 * `./skillDrafts.ts` to keep the eval-specific types co-located with their
 * dedicated endpoint wrappers. Imported here only so `SkillDraft` can
 * optionally surface a typed result blob alongside the legacy fields.
 */
import type { EvaluationResult } from './skillDrafts';

export interface SkillDraft {
  id: string;
  sourceSessionId?: string;
  ownerId: number;
  name: string;
  description?: string;
  triggers?: string;
  requiredTools?: string;
  promptHint?: string;
  extractionRationale?: string;
  /**
   * SKILL-CREATOR-WITH-EVAL Phase 1.3 adds `'evaluating'`, `'evaluated_passed'`,
   * and `'rejected'` (the BE persists them via V91 `t_skill_draft.status` free-
   * form VARCHAR; tech-design.md "决策记录 D8"). `'evaluating'` is the transient
   * state `SkillCreatorService.dispatchEvaluation` writes while child sessions
   * are in-flight; UI shows an "Evaluating…" badge until the coordinator
   * finalises with `'evaluated_passed'` or `'rejected'`. Older drafts created
   * before V91 remain `'draft' | 'approved' | 'discarded'`.
   */
  status: 'draft' | 'approved' | 'discarded' | 'evaluating' | 'evaluated_passed' | 'rejected';
  skillId?: number;
  createdAt: string;
  reviewedAt?: string;
  reviewedBy?: number;
  // P1 §9 dedup signal: 0..1 jaccard/levenshtein blend; populated when BE
  // detects a candidate that is likely a duplicate of an existing skill.
  similarity?: number;
  // Existing skill name/id this draft is most similar to, when similarity is set.
  mergeCandidateId?: string;
  mergeCandidateName?: string;

  // ─── SKILL-CREATOR-WITH-EVAL Phase 1.3 (V91 columns + eval result) ───────
  /**
   * Creation source. Free-form VARCHAR on the BE; known values from the
   * tech-design D8 enum: 'upload' | 'marketplace' | 'natural-language' |
   * 'extract-from-sessions' | 'attribution' | 'manual' | 'skill-creator-eval'.
   */
  source?: string;
  /** Target agent the draft was evaluated against (V91 `target_agent_id`). */
  targetAgentId?: number;
  /** Transient SkillEntity id rendered during evaluation (V91 `candidate_skill_id`). */
  candidateSkillId?: number;
  /**
   * Parsed evaluation result. BE serialises `t_skill_draft.evaluation_result_json`
   * as a structured object on this field. Absent when the draft has not been
   * evaluated yet (legacy drafts and pending pipelines).
   */
  evaluationResult?: EvaluationResult;
  /** Convenience: ISO timestamp pulled from `evaluationResult.evaluatedAt`. */
  evaluatedAt?: string;
}

export interface SkillExtractionStartResult {
  status: string;
  count?: number;
  message?: string;
}

// P1 (B-1): all skill-draft endpoints take `userId` query param.
export const triggerSkillExtraction = (agentId: number | string, userId: number) =>
  api.post<SkillExtractionStartResult>(`/agents/${agentId}/skill-drafts`, null, {
    params: { userId },
  });

export const getSkillDrafts = (userId: number) =>
  api.get<SkillDraft[]>('/skill-drafts', { params: { userId } });

/**
 * Review a draft. `forceCreate=true` is required by the backend when the
 * candidate has high similarity (≥0.85) to an existing skill — the modal
 * confirmation flow sets it after the operator explicitly acknowledges
 * the duplicate (P1-C-8).
 *
 * NOTE on body field naming: BE reads `reviewedBy` (legacy stable contract,
 * see SkillDraftController.reviewDraft) — keep the FE field name as
 * `reviewedBy` even though semantically it is the acting user id. Renaming
 * to `userId` here would 400 every approve/discard until BE catches up
 * (Code Judge r1 B-FE-1).
 *
 * SKILL-DASHBOARD-POLISH-V2 §H — `newName` is the optional rename hint sent
 * by the merge UX modal's "Rename and create new" branch. When BE supports
 * the field, it persists `draft.name = newName` before re-running the
 * approve flow (which then no longer collides). Older BE builds ignore the
 * extra field; in that case the second approve still throws name_conflict
 * and the FE surfaces the error.
 */
export const reviewSkillDraft = (
  id: string,
  action: 'approve' | 'discard',
  userId: number,
  options?: { forceCreate?: boolean; newName?: string },
) =>
  api.patch<SkillDraft>(`/skill-drafts/${id}`, {
    action,
    reviewedBy: userId,
    forceCreate: options?.forceCreate ?? false,
    ...(options?.newName ? { newName: options.newName } : {}),
  });

/**
 * SKILL-DASHBOARD-POLISH-V2 §H — merge an approved draft into an existing
 * skill instead of creating a new one. The BE rewrites the target skill's
 * SKILL.md with the draft's content, updates description/triggers/promptHint,
 * and marks the draft `status='approved'` with `skillId=targetSkillId`.
 *
 * Wire shape (per task brief):
 *   POST /api/skill-drafts/{id}/merge?targetSkillId=X&reviewedBy=Y
 */
export const mergeDraftIntoSkill = (
  draftId: string,
  targetSkillId: number,
  userId: number,
) =>
  api.post<SkillDraft>(`/skill-drafts/${draftId}/merge`, null, {
    params: { targetSkillId, reviewedBy: userId },
  });

/**
 * SKILL-DASHBOARD-POLISH-V2 §H — error body shape returned by the BE on
 * approve when the candidate's name exact-matches an existing skill (case
 * insensitive). The drawer's merge modal reads `existingSkillId` to drive
 * the "Update existing" branch; `name` is the conflict name (normalised by
 * BE so the Modal can show what the user is about to overwrite).
 */
export interface DraftNameConflictError {
  error: 'name_conflict';
  existingSkillId: number;
  /** BE-normalised conflict name (matches the existing skill's display name). */
  name: string;
  /** Optional: existing skill enabled state — lets the FE distinguish
   *  "merge into active skill" vs. "merge into disabled skill" copy. */
  existingSkillEnabled?: boolean;
}
