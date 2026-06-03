/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — evolve API client unit tests.
 *
 * Key assertions:
 *   - listEvolveRuns reads r.data.items  (enveloped list)
 *   - getEvolveRun   reads r.data directly (single object, NOT enveloped)
 *   - URL patterns match BE contract exactly
 *   - limit param is forwarded
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../index', () => {
  const get = vi.fn();
  const post = vi.fn();
  return { default: { get, post } };
});

import api from '../index';
import {
  listEvolveRuns,
  getEvolveRun,
  adoptEvolveBundle,
  getEvolveSkillDraft,
  type EvolveRunSummary,
  type EvolveRunDetail,
  type EvolveIteration,
  type AdoptBundleRequest,
  type AdoptResult,
} from '../evolve';

const mockedGet = (api as unknown as { get: ReturnType<typeof vi.fn> }).get;
const mockedPost = (api as unknown as { post: ReturnType<typeof vi.fn> }).post;

// ──────────────────────── fixture helpers ────────────────────────────────────

function makeIteration(overrides: Partial<EvolveIteration> = {}): EvolveIteration {
  return {
    iteration: 1,
    surface: 'prompt',
    changeDesc: 'Tightened instruction phrasing',
    candidateId: 'cand-001',
    baselineScore: 0.72,
    candidateScore: 0.78,
    delta: 0.06,
    kept: true,
    abRunId: 'ab-run-001',
    createdAt: '2026-05-31T10:00:00Z',
    candidateBundle: null,
    ...overrides,
  };
}

function makeSummary(overrides: Partial<EvolveRunSummary> = {}): EvolveRunSummary {
  return {
    evolveRunId: 'run-abc',
    status: 'completed',
    createdAt: '2026-05-31T09:00:00Z',
    updatedAt: '2026-05-31T09:45:00Z',
    iterationCount: 5,
    finalDelta: 0.12,
    ...overrides,
  };
}

function makeDetail(overrides: Partial<EvolveRunDetail> = {}): EvolveRunDetail {
  return {
    evolveRunId: 'run-abc',
    agentId: 42,
    agentName: 'main-assistant',
    status: 'completed',
    createdAt: '2026-05-31T09:00:00Z',
    updatedAt: '2026-05-31T09:45:00Z',
    iterations: [makeIteration()],
    ...overrides,
  };
}

// ─────────────────────────── tests ───────────────────────────────────────────

describe('evolve API — envelope contract', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  // ── listEvolveRuns ────────────────────────────────────────────────────────

  it('listEvolveRuns calls /evolve/agents/{agentId}/runs with limit param', async () => {
    const envelope = { items: [makeSummary()] };
    mockedGet.mockResolvedValueOnce({ data: envelope });

    await listEvolveRuns(42, 10);

    expect(mockedGet).toHaveBeenCalledWith('/evolve/agents/42/runs', {
      params: { limit: 10 },
    });
  });

  it('listEvolveRuns uses default limit=20 when not provided', async () => {
    mockedGet.mockResolvedValueOnce({ data: { items: [] } });
    await listEvolveRuns(7);
    expect(mockedGet).toHaveBeenCalledWith('/evolve/agents/7/runs', {
      params: { limit: 20 },
    });
  });

  it('listEvolveRuns — caller reads r.data.items (enveloped list shape)', async () => {
    const summary = makeSummary({ evolveRunId: 'run-xyz', iterationCount: 3 });
    mockedGet.mockResolvedValueOnce({ data: { items: [summary] } });

    const res = await listEvolveRuns(42);
    // FE must read r.data.items — not r.data directly
    expect(res.data.items).toHaveLength(1);
    expect(res.data.items[0].evolveRunId).toBe('run-xyz');
    expect(res.data.items[0].iterationCount).toBe(3);
  });

  it('listEvolveRuns — finalDelta may be null (no iterations yet)', async () => {
    const summary = makeSummary({ finalDelta: null, iterationCount: 0 });
    mockedGet.mockResolvedValueOnce({ data: { items: [summary] } });

    const res = await listEvolveRuns(42);
    expect(res.data.items[0].finalDelta).toBeNull();
  });

  // ── getEvolveRun ──────────────────────────────────────────────────────────

  it('getEvolveRun calls /evolve/runs/{evolveRunId} (correct URL)', async () => {
    mockedGet.mockResolvedValueOnce({ data: makeDetail() });
    await getEvolveRun('run-abc');
    expect(mockedGet).toHaveBeenCalledWith('/evolve/runs/run-abc');
  });

  it('getEvolveRun — caller reads r.data directly (NOT enveloped)', async () => {
    const detail = makeDetail({ evolveRunId: 'run-abc', agentId: 99 });
    mockedGet.mockResolvedValueOnce({ data: detail });

    const res = await getEvolveRun('run-abc');
    // FE reads r.data directly (NOT r.data.items)
    expect(res.data.evolveRunId).toBe('run-abc');
    expect(res.data.agentId).toBe(99);
    expect(res.data.agentName).toBe('main-assistant');
  });

  it('getEvolveRun — iterations array contains expected fields', async () => {
    const iter = makeIteration({
      iteration: 2,
      surface: 'skill',
      changeDesc: 'Rewrote bash tool description',
      candidateScore: 0.85,
      delta: 0.13,
      kept: false,
    });
    mockedGet.mockResolvedValueOnce({ data: makeDetail({ iterations: [iter] }) });

    const res = await getEvolveRun('run-abc');
    const it0 = res.data.iterations[0];
    expect(it0.iteration).toBe(2);
    expect(it0.surface).toBe('skill');
    expect(it0.candidateScore).toBe(0.85);
    expect(it0.delta).toBe(0.13);
    expect(it0.kept).toBe(false);
  });

  it('getEvolveRun — candidateScore may be null (eval not completed)', async () => {
    const iter = makeIteration({ candidateScore: null, baselineScore: null });
    mockedGet.mockResolvedValueOnce({ data: makeDetail({ iterations: [iter] }) });

    const res = await getEvolveRun('run-abc');
    expect(res.data.iterations[0].candidateScore).toBeNull();
    expect(res.data.iterations[0].baselineScore).toBeNull();
  });

  it('getEvolveRun — iteration carries the candidateBundle pointers', async () => {
    const iter = makeIteration({
      candidateBundle: {
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: null,
        skillDraftId: 'sd-3',
      },
    });
    mockedGet.mockResolvedValueOnce({ data: makeDetail({ iterations: [iter] }) });

    const res = await getEvolveRun('run-abc');
    const b = res.data.iterations[0].candidateBundle;
    expect(b).not.toBeNull();
    expect(b?.promptVersionId).toBe('pv-1');
    expect(b?.behaviorRuleVersionId).toBeNull();
    expect(b?.skillDraftId).toBe('sd-3');
  });

  it('getEvolveRun — candidateBundle may be null (legacy / non-kept rows)', async () => {
    const iter = makeIteration({ candidateBundle: null });
    mockedGet.mockResolvedValueOnce({ data: makeDetail({ iterations: [iter] }) });

    const res = await getEvolveRun('run-abc');
    expect(res.data.iterations[0].candidateBundle).toBeNull();
  });
});

// ─────────────────────────── adopt contract ──────────────────────────────────

describe('evolve API — adopt + skill-draft contract', () => {
  beforeEach(() => {
    mockedPost.mockReset();
    mockedGet.mockReset();
  });

  it('adoptEvolveBundle POSTs /evolve/runs/{id}/adopt with userId param + bundle body', async () => {
    const result: AdoptResult = {
      prompt: { status: 'ok', reason: null },
      rule: { status: 'noop', reason: null },
      skill: null,
      anyFailed: false,
    };
    mockedPost.mockResolvedValueOnce({ data: result });

    const bundle: AdoptBundleRequest = {
      promptVersionId: 'pv-1',
      behaviorRuleVersionId: 'rv-9',
      skillDraftId: null,
    };
    await adoptEvolveBundle('run-abc', 7, bundle);

    expect(mockedPost).toHaveBeenCalledWith('/evolve/runs/run-abc/adopt', bundle, {
      params: { userId: 7 },
    });
  });

  it('adoptEvolveBundle — caller reads r.data directly (bare AdoptResult, NOT enveloped)', async () => {
    const result: AdoptResult = {
      prompt: { status: 'ok', reason: null },
      rule: null,
      skill: { status: 'failed', reason: 'Skill name conflict' },
      anyFailed: true,
    };
    mockedPost.mockResolvedValueOnce({ data: result });

    const res = await adoptEvolveBundle('run-abc', 7, { promptVersionId: 'pv-1' });
    expect(res.data.prompt?.status).toBe('ok');
    expect(res.data.skill?.status).toBe('failed');
    expect(res.data.skill?.reason).toBe('Skill name conflict');
    expect(res.data.anyFailed).toBe(true);
  });

  it('getEvolveSkillDraft GETs /evolve/skill-drafts/{draftId} (bare object)', async () => {
    mockedGet.mockResolvedValueOnce({
      data: { id: 'sd-3', name: 'my-skill', promptHint: 'body', triggers: null, requiredTools: null },
    });

    const res = await getEvolveSkillDraft('sd-3');
    expect(mockedGet).toHaveBeenCalledWith('/evolve/skill-drafts/sd-3');
    expect(res.data.id).toBe('sd-3');
    expect(res.data.promptHint).toBe('body');
  });
});
