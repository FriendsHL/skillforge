/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — REST client for evolve run trajectories.
 *
 * Envelope contracts (footgun #6b — outer shape must be locked here):
 *
 *   GET /api/evolve/agents/{agentId}/runs?limit=N
 *     → { items: EvolveRunSummary[] }       ← FE reads r.data.items
 *
 *   GET /api/evolve/runs/{evolveRunId}
 *     → EvolveRunDetail                     ← FE reads r.data directly (NOT enveloped)
 *
 * Field names mirror BE Jackson default camelCase. Keep in lock-step with
 * the BE DTO (java.md known footgun #6 / #6b).
 *
 * BE → FE type mapping:
 *   Java Long / Integer / int  → number
 *   Java Instant               → ISO-8601 string
 *   Java Double / BigDecimal   → number | null (when nullable boxed)
 *   Java boolean               → boolean
 */
import api from './index';

// ─────────────────────────── candidate bundle (per-surface pointers) ───────

/**
 * P1 close-loop adopt — the winning candidate's per-surface version pointers,
 * recorded on a kept iteration. Mirrors BE {@code CandidateBundle} record
 * (evolve/dto) field-by-field (footgun #6). Each pointer is null when that
 * surface was not changed in this iteration; the whole bundle is null on the
 * iteration when no pointers were recorded.
 */
export interface CandidateBundle {
  /** Prompt version id to promote (null when prompt unchanged). */
  promptVersionId: string | null;
  /** Behavior-rule version id to promote (null when rule unchanged). */
  behaviorRuleVersionId: string | null;
  /** Skill draft id to approve+register (null when no skill change). */
  skillDraftId: string | null;
}

// ─────────────────────────── iteration (one row per loop) ──────────────────

export interface EvolveIteration {
  /** 1-based iteration index within this run. */
  iteration: number;
  /** Which surface type was changed: 'prompt' | 'skill' | 'behavior_rule'. */
  surface: string;
  /** Human-readable description of what changed. */
  changeDesc: string;
  /** Candidate entity id (prompt version / skill draft / rule version). */
  candidateId: string;
  /** Baseline score for this iteration (null when baseline unavailable). */
  baselineScore: number | null;
  /** Candidate score after A/B eval (null when eval not completed). */
  candidateScore: number | null;
  /** Score delta: candidateScore - baselineScore (may be negative). */
  delta: number;
  /** Whether the gate decided to keep this candidate. */
  kept: boolean;
  /** ID of the A/B run entity used for this iteration. */
  abRunId: string | null;
  /** ISO-8601 creation timestamp. */
  createdAt: string;
  /**
   * P1 close-loop — per-surface candidate pointers for the winning bundle.
   * Null when this iteration recorded no bundle (legacy rows / non-kept).
   */
  candidateBundle: CandidateBundle | null;
}

// ─────────────────────────── run summary (list item) ───────────────────────

export interface EvolveRunSummary {
  evolveRunId: string;
  /** Run lifecycle status: 'running' | 'completed' | 'error' | 'cancelled'. */
  status: string;
  /** ISO-8601 creation timestamp. */
  createdAt: string;
  /** ISO-8601 last update timestamp. */
  updatedAt: string;
  /** How many iterations have been recorded so far. */
  iterationCount: number;
  /**
   * Net score delta across all kept iterations (null when no iterations yet or
   * when the score could not be computed).
   */
  finalDelta: number | null;
}

// ─────────────────────────── run detail (trajectory) ───────────────────────

export interface EvolveRunDetail {
  evolveRunId: string;
  agentId: number;
  agentName: string;
  /** Run lifecycle status. */
  status: string;
  /** ISO-8601 creation timestamp. */
  createdAt: string;
  /** ISO-8601 last update timestamp. */
  updatedAt: string;
  /** All recorded iterations, ordered by iteration number ascending. */
  iterations: EvolveIteration[];
}

// ─────────────────────────── envelope type ─────────────────────────────────

/** Envelope returned by GET /api/evolve/agents/{agentId}/runs */
interface EvolveRunListEnvelope {
  items: EvolveRunSummary[];
}

// ─────────────────────────── API wrappers ──────────────────────────────────

/**
 * List evolve runs for an agent.
 * Reads r.data.items (enveloped list).
 *
 * @param agentId  The target agent's numeric ID.
 * @param limit    Maximum number of runs to return (default 20).
 */
export const listEvolveRuns = (agentId: number, limit = 20) =>
  api.get<EvolveRunListEnvelope>(`/evolve/agents/${agentId}/runs`, {
    params: { limit },
  });

/**
 * Fetch the full detail (including iterations) for one evolve run.
 * Reads r.data directly — NOT enveloped.
 *
 * @param evolveRunId  The evolve run's string UUID / ID.
 */
export const getEvolveRun = (evolveRunId: string) =>
  api.get<EvolveRunDetail>(`/evolve/runs/${evolveRunId}`);

// ─────────────────────────── trigger ───────────────────────────────────────

/** Body returned by POST /api/evolve/agents/{agentId}/run (202 ACCEPTED). */
export interface EvolveTriggerResult {
  evolveRunId: string;
  sessionId: string;
  agentId: number;
  agentName: string;
  maxIter: number;
  /** Always 'running' on a fresh trigger. */
  status: string;
}

/**
 * Trigger an agent-driven evolve run.
 * `POST /api/evolve/agents/{agentId}/run?reportId=&maxIter=` (no body; params only).
 *
 * @param agentId  The agent to evolve.
 * @param opts.reportId  Optional pre-existing completed opt-report id to drive
 *                       the loop from (focused-loop path; skips the live
 *                       opt-report workflow). Server 400s on a malformed id.
 * @param opts.maxIter   Optional iteration ceiling (server clamps to [1, 50]).
 *
 * Errors: 404 (agent missing), 409 (an evolve run already in flight for the
 * agent), 400 (malformed reportId).
 */
export const triggerEvolveRun = (
  agentId: number,
  opts?: { reportId?: string; maxIter?: number },
) =>
  api.post<EvolveTriggerResult>(`/evolve/agents/${agentId}/run`, null, {
    params: {
      ...(opts?.reportId ? { reportId: opts.reportId } : {}),
      ...(opts?.maxIter != null ? { maxIter: opts.maxIter } : {}),
    },
  });

// ─────────────────────────── adopt (close-loop promotion) ──────────────────

/**
 * Body for POST /api/evolve/runs/{evolveRunId}/adopt. Mirrors BE
 * {@code AdoptBundleRequest} (footgun #6). Each pointer is optional/null —
 * the server promotes only the non-null surfaces. The server cross-checks
 * these pointers against the run's kept iterations (anti-tamper).
 */
export interface AdoptBundleRequest {
  promptVersionId?: string | null;
  behaviorRuleVersionId?: string | null;
  skillDraftId?: string | null;
}

/**
 * Per-surface adopt outcome. Mirrors BE {@code SurfaceResult} record.
 *   - 'ok'     → surface promoted.
 *   - 'noop'   → already active / nothing to do (idempotent).
 *   - 'failed' → promotion threw; {@link reason} carries the message.
 */
export interface SurfaceResult {
  status: 'ok' | 'noop' | 'failed';
  /** Failure detail when status==='failed'; null otherwise. */
  reason: string | null;
}

/**
 * Adopt outcome across all surfaces. Mirrors BE {@code AdoptResult} record.
 * Each surface is null when the request did not target it. Surfaces are
 * promoted in independent transactions, so a partial failure leaves the
 * succeeded surfaces committed — {@link anyFailed} flags that at least one
 * targeted surface failed.
 *
 * Returned BARE (not enveloped) — FE reads r.data directly.
 */
export interface AdoptResult {
  prompt: SurfaceResult | null;
  rule: SurfaceResult | null;
  skill: SurfaceResult | null;
  anyFailed: boolean;
}

/**
 * Adopt a winning candidate bundle for an evolve run — promotes each non-null
 * surface (prompt active-version swap / rule active swap / skill draft
 * approve+register). Irreversible per surface.
 *
 * `POST /api/evolve/runs/{evolveRunId}/adopt?userId=` with the bundle as body.
 * Reads r.data directly — NOT enveloped.
 *
 * Errors: 400 (system user / blank pointers / bundle not from a kept
 * iteration / ownership mismatch), 404 (run missing or not an evolve run).
 *
 * @param evolveRunId  The evolve run whose kept iteration owns the bundle.
 * @param userId       Acting (human) user id — must not be the system user.
 * @param bundle       Per-surface version pointers to promote.
 */
export const adoptEvolveBundle = (
  evolveRunId: string,
  userId: number,
  bundle: AdoptBundleRequest,
) =>
  api.post<AdoptResult>(`/evolve/runs/${evolveRunId}/adopt`, bundle, {
    params: { userId },
  });

// ─────────────────────────── skill-draft content (for diff) ────────────────

/**
 * Lightweight skill-draft view for rendering the skill surface of the adopt
 * diff. Mirrors the BE {@code GET /api/evolve/skill-drafts/{draftId}} payload
 * (footgun #6) — bare object, NOT enveloped.
 */
export interface EvolveSkillDraftView {
  id: string;
  name: string;
  /** The drafted SKILL.md body / prompt hint shown in the diff. */
  promptHint: string | null;
  triggers: string | null;
  requiredTools: string | null;
}

/**
 * Fetch a skill draft's content for the adopt diff.
 * `GET /api/evolve/skill-drafts/{draftId}` — reads r.data directly.
 */
export const getEvolveSkillDraft = (draftId: string) =>
  api.get<EvolveSkillDraftView>(`/evolve/skill-drafts/${draftId}`);
