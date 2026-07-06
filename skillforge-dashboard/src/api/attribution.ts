import api from './index';

/**
 * V3 ATTRIBUTION-AGENT Phase 1.5 — REST client for `/api/attribution/events`.
 *
 * Field names mirror the BE Jackson default camelCase serialization. Keep this
 * file in lock-step with the BE record / DTO definitions (per
 * `.claude/rules/java.md` footgun #6 — FE-BE contract):
 *
 *   skillforge-server/.../attribution/AttributionEventController.java
 *   skillforge-server/.../attribution/OptimizationEventDto.java
 *
 * BE → FE type mapping reminder (java.md table):
 *   Java Long/Integer/int → number
 *   Java Instant          → ISO-8601 string
 *   Java BigDecimal       → number (Jackson default — numeric JSON token)
 *   Java nullable boxed   → T | null
 */

/**
 * Lifecycle stage from BE `t_optimization_event.stage`.
 * Mirrors `OptimizationEventEntity.STAGE_*` constants.
 */
export type AttributionStage =
  | 'dispatch_initiated'
  | 'proposal_pending'
  | 'proposal_approved'
  | 'proposal_rejected'
  | 'candidate_generating'
  | 'candidate_ready'
  | 'candidate_failed'
  | 'candidate_created'
  | 'ab_running'
  | 'ab_passed'
  | 'ab_failed'
  | 'canary_started'
  | 'promoted'
  | 'rolled_back'
  | 'verified';

/** Surface type from `OptimizationEventEntity.SURFACE_*`. */
export type AttributionSurface =
  | 'skill'
  | 'prompt'
  | 'behavior_rule'
  | 'other'
  | 'unclear';

/** Risk gating from `OptimizationEventEntity.RISK_*`. */
export type AttributionRisk = 'low' | 'medium' | 'high';

/**
 * Wire shape mirroring {@code OptimizationEventDto} 1:1. All 18 fields are
 * present; nullable BE columns (foreign keys, attribution session id, cooldown)
 * surface as `T | null`.
 */
export interface OptimizationEventDto {
  id: number;
  patternId: number;
  agentId: number;
  surfaceType: string;
  /** Nullable on BE (V80 schema). curator may emit proposals where the
   * surface-classification doesn't fit a known change_type bucket. */
  changeType: string | null;
  /** Nullable on BE — sentinel `dispatch_initiated` rows are written before
   * the curator agent produces a proposal description. */
  description: string | null;
  /** Nullable on BE (free-form impact note may be omitted). */
  expectedImpact: string | null;
  /** BigDecimal serialized as JSON number; 0..1 inclusive. Nullable on BE
   * for sentinel / pre-proposal rows. */
  confidence: number | null;
  /** Nullable on BE — `risk` is curator-assigned, absent on sentinel rows. */
  risk: string | null;
  stage: string;
  candidateSkillId: number | null;
  candidatePromptVersionId: number | null;
  /**
   * BEHAVIOR-RULE-AB-EVAL V1 — id of the {@code t_behavior_rule_version} row
   * created when a behavior_rule candidate is approved (UUID-style VARCHAR(36)
   * on BE; sibling of {@code candidateSkillId} / {@code candidatePromptVersionId}
   * but for the behavior_rule surface). Nullable for non-behavior_rule events
   * or pre-candidate stages. FE row Actions for {@code surfaceType=='behavior_rule'
   * && stage=='candidate_ready'} uses this to fetch latestAbRun + render the
   * dual-criteria badge / Promote / Retry buttons.
   */
  candidateBehaviorRuleVersionId: string | null;
  abRunId: number | null;
  canaryId: number | null;
  attributionSessionId: string | null;
  /** ISO-8601 or null when no cooldown applies (terminal stages). */
  cooldownExpiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

// ───────────────────────── request DTOs ─────────────────────────

export interface ListEventsParams {
  stage?: AttributionStage;
  agentId?: number;
  surfaceType?: AttributionSurface;
  page?: number;
  size?: number;
}

export interface ListEventsResponse {
  items: OptimizationEventDto[];
  page: number;
  size: number;
  /** BE returns Page.getTotalElements() — Long → number on the wire. */
  total: number;
}

export interface ApproveRequest {
  approverUserId: number;
  /** Optional note — symmetric to reject's mandatory reason (F8). Sent as
   *  metadata for training the feedback loop; BE may ignore if not wired yet. */
  note?: string;
}

export interface RejectRequest {
  approverUserId: number;
  reason: string;
}

export interface RetryRequest {
  approverUserId: number;
}

// ───────────────────────── WS event shape ─────────────────────────

/**
 * Payload pushed by {@code AttributionEventBroadcaster}. Type literal lets
 * the WS message handler narrow with a discriminator before reading fields.
 * Keep in sync with `AttributionEventBroadcaster.EVENT_TYPE`.
 */
export interface AttributionEventUpdatedMessage {
  type: 'attribution_event_updated';
  eventId: number;
  patternId: number | null;
  stage: string;
  /** Absent on the first transition (dispatch_initiated has no prior stage). */
  previousStage?: string | null;
  updatedAt: string;
}

// ───────────────────────── endpoints ─────────────────────────

/**
 * `GET /api/attribution/events?stage=&agentId=&surfaceType=&page=&size=`.
 * BE clamps size to [1, 200], default 20. Ordering: `created_at DESC` (server-
 * side, FE does not re-sort). Empty/blank query-string filters are normalized
 * to null on BE.
 */
export const listEvents = (params?: ListEventsParams) =>
  api.get<ListEventsResponse>('/attribution/events', { params });

/** `GET /api/attribution/events/{id}` — 404 if missing. */
export const getEvent = (id: number) =>
  api.get<OptimizationEventDto>(`/attribution/events/${id}`);

/**
 * `POST /api/attribution/events/{id}/approve` — 200 with updated DTO.
 * - 400 if approverUserId is null / event not in proposal_pending
 * - 404 if event id not found
 * - 409 on state-machine violations (e.g. already approved)
 */
export const approveEvent = (id: number, body: ApproveRequest) =>
  api.post<OptimizationEventDto>(`/attribution/events/${id}/approve`, body);

/**
 * `POST /api/attribution/events/{id}/reject`.
 * - 400 if approverUserId null / reason missing / event not in proposal_pending
 * - 404 if id not found
 * - 409 on state-machine violations
 */
export const rejectEvent = (id: number, body: RejectRequest) =>
  api.post<OptimizationEventDto>(`/attribution/events/${id}/reject`, body);

/**
 * `POST /api/attribution/events/{id}/retry` — manual retry for a
 * `candidate_failed` event. Stage flips back to `candidate_generating`.
 * - 400 if event not in candidate_failed
 * - 404 if id not found
 * - 409 on state-machine violations
 */
export const retryEvent = (id: number, body: RetryRequest) =>
  api.post<OptimizationEventDto>(`/attribution/events/${id}/retry`, body);
