import api from './client';

// ─── Self-Improve Pipeline ───────────────────────────────────────────────────

export interface ImprovementStartResult {
  abRunId: string;
  promptVersionId: string;
  status: string;
}

export interface AbScenarioResult {
  scenarioId: string;
  scenarioName: string;
  baseline: { status: 'PASS' | 'FAIL' | 'TIMEOUT'; oracleScore: number };
  candidate: { status: 'PASS' | 'FAIL' | 'TIMEOUT'; oracleScore: number };
}

export interface AbRunDetail {
  abRunId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  deltaPassRate: number | null;
  candidatePassRate: number | null;
  baselinePassRate: number | null;
  promoted: boolean;
  completedScenarios: number;
  scenarioResults: AbScenarioResult[];
  failureReason?: string;
  /**
   * EVAL-DATASET-LAYER V1 — dataset version this prompt A/B run was bound
   * to. Null = legacy path (held_out + ephemeral scenarios). Emitted by
   * PromptImproveController.toAbRunDetail / toAbRunSummary. The FE renders
   * a "Dataset: <name>@v<n>" label by lazy-fetching `/eval/dataset-versions/{id}`
   * then `/eval/datasets/{datasetId}` (BE deliberately does not pre-compose
   * the label — keeps the controller layer thin).
   */
  datasetVersionId?: string | null;
}

export interface PromptVersion {
  id: string;
  versionNumber: number;
  status: 'candidate' | 'active' | 'deprecated' | 'failed';
  source: 'manual' | 'auto_improve';
  deltaPassRate: number | null;
  baselinePassRate: number | null;
  improvementRationale: string | null;
  createdAt: string;
  promotedAt: string | null;
  deprecatedAt: string | null;
  content?: string;
}

export const triggerPromptImprove = (agentId: string, evalRunId: string) =>
  api.post<ImprovementStartResult>(`/agents/${agentId}/prompt-improve`, { evalRunId });

export const getAbRunDetail = (agentId: string, abRunId: string) =>
  api.get<AbRunDetail>(`/agents/${agentId}/prompt-improve/${abRunId}`);

export const getActiveImprovement = (agentId: string) =>
  api.get<AbRunDetail | null>(`/agents/${agentId}/prompt-improve/active`);

export const getPromptVersions = (agentId: string) =>
  api.get<PromptVersion[]>(`/agents/${agentId}/prompt-versions`);

export const getPromptVersionDetail = (agentId: string, versionId: string) =>
  api.get<PromptVersion>(`/agents/${agentId}/prompt-versions/${versionId}`);

export const rollbackPromptVersion = (agentId: string, versionId: string) =>
  api.post(`/agents/${agentId}/prompt-versions/${versionId}/rollback`);

export const resumeAutoImprove = (agentId: string) =>
  api.post(`/agents/${agentId}/prompt-improve/resume`);
