/**
 * EvolveAdoptCard — P1 close-loop adopt card tests.
 *
 * Covers:
 *   - per-surface diff sections render only for non-null bundle pointers
 *   - Approve & Adopt → Modal.confirm → adoptEvolveBundle called with the
 *     exact (evolveRunId, userId, bundle) contract
 *   - per-surface outcome toasts (ok / noop / failed) + anyFailed notification
 *   - button disabled after a fully-successful adopt (no double-submit)
 *   - partial failure leaves the button re-enabled for retry
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Modal, message, notification } from 'antd';

import EvolveAdoptCard from '../EvolveAdoptCard';
import type { EvolveIteration, AdoptResult } from '../../../api/evolve';

// ── matchMedia stub (AntD relies on it in jsdom) ──
beforeEach(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
});

// ── API mocks ──
vi.mock('../../../api/evolve', async (orig) => {
  const actual = await orig<typeof import('../../../api/evolve')>();
  return {
    ...actual,
    adoptEvolveBundle: vi.fn(),
    getEvolveSkillDraft: vi.fn(),
  };
});
vi.mock('../../../api/index', () => ({
  getAgent: vi.fn(),
  getPromptVersionDetail: vi.fn(),
}));
vi.mock('../../../api/behaviorRules', () => ({
  getBehaviorRuleVersion: vi.fn(),
  listBehaviorRuleVersions: vi.fn(),
}));

import { adoptEvolveBundle, getEvolveSkillDraft } from '../../../api/evolve';
import { getAgent, getPromptVersionDetail } from '../../../api/index';
import {
  getBehaviorRuleVersion,
  listBehaviorRuleVersions,
} from '../../../api/behaviorRules';

const mockAdopt = vi.mocked(adoptEvolveBundle);
const mockSkillDraft = vi.mocked(getEvolveSkillDraft);
const mockGetAgent = vi.mocked(getAgent);
const mockPromptDetail = vi.mocked(getPromptVersionDetail);
const mockRuleVersion = vi.mocked(getBehaviorRuleVersion);
const mockRuleList = vi.mocked(listBehaviorRuleVersions);

// ── fixtures ──
function makeIteration(
  bundle: EvolveIteration['candidateBundle'],
  overrides: Partial<EvolveIteration> = {},
): EvolveIteration {
  return {
    iteration: 3,
    surface: 'prompt',
    changeDesc: 'Tightened instruction phrasing',
    candidateId: 'cand-001',
    baselineScore: 0.72,
    candidateScore: 0.81,
    delta: 0.09,
    kept: true,
    abRunId: 'ab-1',
    createdAt: '2026-06-01T10:00:00Z',
    candidateBundle: bundle,
    ...overrides,
  };
}

type ConfirmCfg = { onOk?: () => unknown; afterClose?: () => void };

/**
 * Stub Modal.confirm to auto-accept: invoke onOk, then fire afterClose once it
 * settles (mirrors AntD closing the dialog after the OK handler resolves —
 * which is what re-enables the button via the confirmOpen guard).
 */
function autoConfirm() {
  return vi.spyOn(Modal, 'confirm').mockImplementation((cfg: ConfirmCfg) => {
    Promise.resolve(cfg.onOk?.()).finally(() => cfg.afterClose?.());
    return { destroy: vi.fn(), update: vi.fn() } as never;
  });
}

function renderCard(
  iteration: EvolveIteration,
  onAdopted?: (r: AdoptResult) => void,
) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <EvolveAdoptCard
        evolveRunId="run-xyz"
        agentId={42}
        iteration={iteration}
        userId={7}
        onAdopted={onAdopted}
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  // default resolved diff data
  mockPromptDetail.mockResolvedValue({ data: { content: 'NEW prompt body' } } as never);
  mockGetAgent.mockResolvedValue({ data: { systemPrompt: 'OLD prompt body' } } as never);
  mockRuleVersion.mockResolvedValue({
    data: { rulesJson: '{"builtinRuleIds":["a","b"]}' },
  } as never);
  mockRuleList.mockResolvedValue({
    data: [{ rulesJson: '{"builtinRuleIds":["a"]}' }],
  } as never);
  mockSkillDraft.mockResolvedValue({
    data: { promptHint: 'draft skill body' },
  } as never);
});

describe('EvolveAdoptCard — surface rendering', () => {
  it('renders only the prompt section when bundle has only promptVersionId', async () => {
    renderCard(
      makeIteration({
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: null,
        skillDraftId: null,
      }),
    );

    // prompt section appears
    expect(await screen.findByText('Prompt')).toBeInTheDocument();
    // rule / skill not rendered
    expect(screen.queryByText('Behavior rule')).not.toBeInTheDocument();
    expect(screen.queryByText('Skill')).not.toBeInTheDocument();

    // only the prompt queries fired
    await waitFor(() => expect(mockPromptDetail).toHaveBeenCalledWith('42', 'pv-1'));
    expect(mockRuleVersion).not.toHaveBeenCalled();
    expect(mockSkillDraft).not.toHaveBeenCalled();
  });

  it('renders all three surfaces when every pointer is present', async () => {
    renderCard(
      makeIteration({
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: 'rv-9',
        skillDraftId: 'sd-3',
      }),
    );

    expect(await screen.findByText('Prompt')).toBeInTheDocument();
    expect(await screen.findByText('Behavior rule')).toBeInTheDocument();
    expect(await screen.findByText('Skill')).toBeInTheDocument();

    // each surface's fetchers fire with the right id
    await waitFor(() => expect(mockRuleVersion).toHaveBeenCalledWith('rv-9'));
    await waitFor(() => expect(mockSkillDraft).toHaveBeenCalledWith('sd-3'));
    expect(mockRuleList).toHaveBeenCalledWith({ agentId: '42', status: 'active' });
  });

  it('marks the rule surface as new when the agent has no active rule version', async () => {
    // empty active list → no current rule version
    mockRuleList.mockResolvedValue({ data: [] } as never);

    renderCard(
      makeIteration({
        promptVersionId: null,
        behaviorRuleVersionId: 'rv-9',
        skillDraftId: null,
      }),
    );

    expect(await screen.findByText('Behavior rule')).toBeInTheDocument();
    // the "new — no active version" note appears (only the rule surface renders here)
    expect(await screen.findByText('新建 — 无现役版本')).toBeInTheDocument();
  });

  it('renders nothing when the bundle has no surfaces', () => {
    const { container } = renderCard(
      makeIteration({
        promptVersionId: null,
        behaviorRuleVersionId: null,
        skillDraftId: null,
      }),
    );
    expect(container.querySelector('[data-testid="evolve-adopt-card"]')).toBeNull();
  });

  it('shows the iteration delta badge', async () => {
    renderCard(
      makeIteration(
        { promptVersionId: 'pv-1', behaviorRuleVersionId: null, skillDraftId: null },
        { delta: 0.09 },
      ),
    );
    expect(await screen.findByTestId('eadopt-delta')).toHaveTextContent('+0.090');
  });
});

describe('EvolveAdoptCard — adopt flow', () => {
  it('opens Modal.confirm and posts the exact bundle on confirm', async () => {
    const confirmSpy = autoConfirm();
    mockAdopt.mockResolvedValue({
      data: {
        prompt: { status: 'ok', reason: null },
        rule: null,
        skill: null,
        anyFailed: false,
      },
    } as never);

    const onAdopted = vi.fn();
    renderCard(
      makeIteration({
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: 'rv-9',
        skillDraftId: null,
      }),
      onAdopted,
    );

    fireEvent.click(await screen.findByTestId('eadopt-approve-btn'));

    expect(confirmSpy).toHaveBeenCalledOnce();
    await waitFor(() =>
      expect(mockAdopt).toHaveBeenCalledWith('run-xyz', 7, {
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: 'rv-9',
        skillDraftId: null,
      }),
    );
    await waitFor(() => expect(onAdopted).toHaveBeenCalledOnce());
    confirmSpy.mockRestore();
  });

  it('fires per-surface toasts and disables the button after full success', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => '' as never);
    const infoSpy = vi.spyOn(message, 'info').mockImplementation(() => '' as never);
    autoConfirm();
    mockAdopt.mockResolvedValue({
      data: {
        prompt: { status: 'ok', reason: null },
        rule: { status: 'noop', reason: null },
        skill: null,
        anyFailed: false,
      },
    } as never);

    renderCard(
      makeIteration({
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: 'rv-9',
        skillDraftId: null,
      }),
    );

    fireEvent.click(await screen.findByTestId('eadopt-approve-btn'));

    await waitFor(() =>
      expect(successSpy).toHaveBeenCalledWith(expect.stringContaining('Prompt')),
    );
    expect(infoSpy).toHaveBeenCalledWith(expect.stringContaining('Behavior rule'));

    // button reflects adopted state and is disabled
    await waitFor(() =>
      expect(screen.getByTestId('eadopt-approve-btn')).toBeDisabled(),
    );
    expect(screen.getByTestId('eadopt-approve-btn')).toHaveTextContent('已采纳');

    successSpy.mockRestore();
    infoSpy.mockRestore();
  });

  it('warns via notification and keeps button enabled on partial failure', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => '' as never);
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => '' as never);
    const notifySpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never);
    autoConfirm();
    mockAdopt.mockResolvedValue({
      data: {
        prompt: { status: 'ok', reason: null },
        rule: null,
        skill: { status: 'failed', reason: 'Skill name conflict' },
        anyFailed: true,
      },
    } as never);

    renderCard(
      makeIteration({
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: null,
        skillDraftId: 'sd-3',
      }),
    );

    fireEvent.click(await screen.findByTestId('eadopt-approve-btn'));

    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('Skill name conflict')),
    );
    expect(successSpy).toHaveBeenCalledWith(expect.stringContaining('Prompt'));
    expect(notifySpy).toHaveBeenCalledOnce();

    // partial failure → still re-enabled for retry
    await waitFor(() =>
      expect(screen.getByTestId('eadopt-approve-btn')).not.toBeDisabled(),
    );

    errorSpy.mockRestore();
    successSpy.mockRestore();
    notifySpy.mockRestore();
  });

  it('surfaces a request-level error toast when adopt rejects', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => '' as never);
    autoConfirm();
    mockAdopt.mockRejectedValue(new Error('boom'));

    renderCard(
      makeIteration({
        promptVersionId: 'pv-1',
        behaviorRuleVersionId: null,
        skillDraftId: null,
      }),
    );

    fireEvent.click(await screen.findByTestId('eadopt-approve-btn'));

    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('boom')),
    );
    // request error is not a successful adopt → button stays enabled
    expect(screen.getByTestId('eadopt-approve-btn')).not.toBeDisabled();
    errorSpy.mockRestore();
  });
});
