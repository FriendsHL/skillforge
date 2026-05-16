import api from './index';

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.4 — REST client for the user-simulator
 * trial harness. Talks to {@code DynamicSimController} (BE Phase 1.3).
 *
 * <p>Field-by-field mirror of the BE {@code SimulatorTrialResponse} record per
 * the java.md footgun #6 contract — Java field names land in the JSON via
 * Jackson default camelCase, so a grep on either side stays grep-able:
 *
 * <pre>
 *   trialId / scenarioId / candidateAgentVersionId / candidateSurfaceType
 *   persona / sessionId / turnsUsed / terminationReason
 *   observedFailureSignals / createdAt
 * </pre>
 *
 * <p>Type mapping (per java.md table):
 * <pre>
 *   Java String           → string
 *   Java String (null)    → string | null
 *   Java Integer          → number
 *   Java Instant          → ISO-8601 string
 * </pre>
 *
 * <p>Known V5 limitation surfaced through this client:
 * {@code candidateSurfaceType === 'behavior_rule'} is rejected at 4 layers
 * (orchestrator entry / RunSimulatorTrial tool / DynamicSimController POST
 * 400 / FE selector disabled). The BE 400 response shape is
 * {@code { error: string, supportedSurfaces: string[] }} — callers should
 * surface {@code error} verbatim and list {@code supportedSurfaces} for the
 * operator. See tech-design.md "已知 limitation" section.
 */

/** Surface enum — mirrors {@code OptimizableSurface} V4 surface types. */
export type CandidateSurfaceType = 'prompt' | 'skill' | 'behavior_rule';

/** Termination reason enum (BE writes one of these on trial finish). */
export type TerminationReason =
  | 'task_completed'
  | 'failure_signal'
  | 'max_turns'
  | 'error';

/**
 * Wire shape of {@code SimulatorTrialEntity} → {@code SimulatorTrialResponse}.
 * 10 fields, names align with BE record components exactly.
 */
export interface SimulatorTrialResponse {
  trialId: string;
  scenarioId: string;
  candidateAgentVersionId: string | null;
  candidateSurfaceType: string | null;
  persona: string;
  sessionId: string;
  turnsUsed: number;
  terminationReason: string | null;
  observedFailureSignals: string | null;
  /** ISO-8601 (BE Instant). */
  createdAt: string;
}

/** {@code POST /api/dynamic-sim/trials} request body. */
export interface CreateTrialRequest {
  scenarioId: string;
  /** Optional — when null, BE runs against the agent's current baseline. */
  candidateAgentVersionId?: string;
  /**
   * Required when {@code candidateAgentVersionId} is set. BE rejects
   * {@code 'behavior_rule'} with HTTP 400 + supportedSurfaces list (V5.1
   * backlog — V4 AgentLoopEngine 7+1 red-light file can't be touched).
   */
  candidateSurfaceType?: CandidateSurfaceType;
  /** Non-empty array — one trial fans out per persona string. */
  personas: string[];
  /** Defaults to {@code 10} on the BE if omitted. */
  maxTurns?: number;
}

/**
 * {@code POST} response (HTTP 202 Accepted). The endpoint dispatches async
 * via {@code abEvalLoopExecutor}; FE polls {@code GET /trials} for outcome.
 */
export interface CreateTrialResponse {
  scenarioId: string;
  personaCount: number;
  /** Currently always {@code "RUNNING"}; left as string for forward compat. */
  status: string;
}

/**
 * Spring {@code Page<T>} envelope shape used by {@code GET /trials}. The BE
 * controller returns {@code Map<String, Object>} with these exact keys.
 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ListTrialsParams {
  /** Filter by scenario. Mutually-exclusive with the candidate-version pair. */
  scenarioId?: string;
  /** Must be paired with {@code candidateSurfaceType} on the BE. */
  candidateAgentVersionId?: string;
  candidateSurfaceType?: CandidateSurfaceType;
  /** 0-indexed page number. Defaults to 0 on the BE. */
  page?: number;
  /** 1-100 (BE caps to 100). Defaults to 20 on the BE. */
  size?: number;
}

/**
 * BE 400 error envelope when {@code behavior_rule} or invalid surface is sent.
 * Surfaced by {@code DynamicSimController.launchTrials}.
 */
export interface DynamicSimErrorResponse {
  error: string;
  supportedSurfaces?: string[];
}

/** {@code POST /api/dynamic-sim/trials} — async fan-out, one trial per persona. */
export const createTrials = (req: CreateTrialRequest) =>
  api.post<CreateTrialResponse>('/dynamic-sim/trials', req);

/** {@code GET /api/dynamic-sim/trials} — paginated list, sorted createdAt DESC. */
export const listTrials = (params: ListTrialsParams) =>
  api.get<PageResponse<SimulatorTrialResponse>>('/dynamic-sim/trials', { params });

/** {@code GET /api/dynamic-sim/trials/{trialId}} — single trial detail. */
export const getTrial = (trialId: string) =>
  api.get<SimulatorTrialResponse>(`/dynamic-sim/trials/${trialId}`);
