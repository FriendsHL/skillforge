/**
 * P1 close-loop adopt — EvolveAdoptCard
 *
 * Renders the winning candidate bundle of a kept evolve iteration as a
 * per-surface diff (prompt / behavior rule / skill) plus a single
 * "Approve & Adopt" action that promotes every targeted surface atomically
 * per-surface (each surface commits independently on the BE).
 *
 * Data loading: a FIXED set of useQuery hooks (constant hook count across
 * renders — Rules of Hooks) gated by `enabled` per surface. Each surface
 * fetches its candidate + current content and diffs them with <SkillMdDiff>.
 *
 * Adopt is irreversible per surface, so the button is loading+disabled while
 * in flight and stays disabled once the bundle has been adopted (no
 * double-submit).
 */
import React, { useState, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Modal, Tag, message, notification } from 'antd';
import { SkillMdDiff } from '../skills/SkillMdDiff';
import {
  adoptEvolveBundle,
  getEvolveSkillDraft,
  type EvolveIteration,
  type AdoptBundleRequest,
  type AdoptResult,
  type SurfaceResult,
} from '../../api/evolve';
import {
  getAgent,
  getPromptVersionDetail,
} from '../../api/index';
import {
  getBehaviorRuleVersion,
  listBehaviorRuleVersions,
} from '../../api/behaviorRules';
import './evolve.css';

interface EvolveAdoptCardProps {
  evolveRunId: string;
  /** Numeric agent id (BE behavior-rule/prompt clients stringify internally). */
  agentId: number;
  /** The kept iteration whose candidateBundle is being adopted. */
  iteration: EvolveIteration;
  /** Acting human user id (must not be the system user 0). */
  userId: number;
  /** Fired after a successful adopt POST with the per-surface result. */
  onAdopted?: (result: AdoptResult) => void;
}

const STALE = 30_000;

function formatDelta(delta: number): string {
  const sign = delta > 0 ? '+' : '';
  return `${sign}${delta.toFixed(3)}`;
}

/** Pretty rules JSON for the diff; falls back to the raw string on parse error. */
function prettyJson(raw: string | null | undefined): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

interface SurfaceSectionProps {
  title: string;
  tagColor: string;
  loading: boolean;
  error: string | null;
  current: string;
  candidate: string;
  /** When true, omit the "current" column framing (brand-new surface). */
  isNew?: boolean;
}

const SurfaceSection: React.FC<SurfaceSectionProps> = ({
  title,
  tagColor,
  loading,
  error,
  current,
  candidate,
  isNew = false,
}) => (
  <div className="eadopt-surface">
    <div className="eadopt-surface-head">
      <Tag color={tagColor} className="eadopt-surface-tag">
        {title}
      </Tag>
      {isNew && <span className="eadopt-surface-note">新建 — 无现役版本</span>}
    </div>
    {loading ? (
      <p className="eadopt-surface-status">Loading diff…</p>
    ) : error ? (
      <p className="eadopt-surface-status eadopt-surface-status--error" role="alert">
        {error}
      </p>
    ) : (
      <SkillMdDiff
        parent={current}
        candidate={candidate}
        parentLabel={isNew ? '（无现役）' : '现役'}
        candidateLabel="候选"
      />
    )}
  </div>
);

const EvolveAdoptCard: React.FC<EvolveAdoptCardProps> = ({
  evolveRunId,
  agentId,
  iteration,
  userId,
  onAdopted,
}) => {
  const bundle = iteration.candidateBundle;
  const promptVersionId = bundle?.promptVersionId ?? null;
  const behaviorRuleVersionId = bundle?.behaviorRuleVersionId ?? null;
  const skillDraftId = bundle?.skillDraftId ?? null;

  const [adopting, setAdopting] = useState(false);
  const [adopted, setAdopted] = useState(false);
  // True from the moment the confirm dialog opens until it fully closes — keeps
  // the button disabled across the modal window so a second click can't stack a
  // second confirm dialog (adopt is irreversible; double-submit must not happen).
  const [confirmOpen, setConfirmOpen] = useState(false);

  // ── prompt surface (candidate version content vs agent's live systemPrompt) ──
  const promptCandidateQ = useQuery({
    queryKey: ['adopt-prompt-candidate', agentId, promptVersionId],
    queryFn: () =>
      getPromptVersionDetail(String(agentId), promptVersionId as string).then(
        (r) => r.data.content ?? '',
      ),
    enabled: promptVersionId != null,
    staleTime: STALE,
  });
  const promptCurrentQ = useQuery({
    queryKey: ['adopt-prompt-current', agentId],
    queryFn: () =>
      getAgent(agentId).then(
        (r) => (r.data as { systemPrompt?: string }).systemPrompt ?? '',
      ),
    enabled: promptVersionId != null,
    staleTime: STALE,
  });

  // ── behavior-rule surface (candidate rulesJson vs current active rulesJson) ──
  const ruleCandidateQ = useQuery({
    queryKey: ['adopt-rule-candidate', behaviorRuleVersionId],
    queryFn: () =>
      getBehaviorRuleVersion(behaviorRuleVersionId as string).then((r) =>
        prettyJson(r.data.rulesJson),
      ),
    enabled: behaviorRuleVersionId != null,
    staleTime: STALE,
  });
  const ruleCurrentQ = useQuery({
    queryKey: ['adopt-rule-current', agentId],
    queryFn: () =>
      listBehaviorRuleVersions({ agentId: String(agentId), status: 'active' }).then(
        (r) => prettyJson(r.data[0]?.rulesJson),
      ),
    enabled: behaviorRuleVersionId != null,
    staleTime: STALE,
  });

  // ── skill surface (draft content — brand-new, no current) ──
  const skillDraftQ = useQuery({
    queryKey: ['adopt-skill-draft', skillDraftId],
    queryFn: () =>
      getEvolveSkillDraft(skillDraftId as string).then((r) => r.data.promptHint ?? ''),
    enabled: skillDraftId != null,
    staleTime: STALE,
  });

  const errMsg = (e: unknown): string =>
    e instanceof Error ? e.message : 'Failed to load diff.';

  const reportOutcome = useCallback(
    (result: AdoptResult) => {
      const surfaces: ReadonlyArray<[string, SurfaceResult | null]> = [
        ['Prompt', result.prompt],
        ['Behavior rule', result.rule],
        ['Skill', result.skill],
      ];
      for (const [label, sr] of surfaces) {
        if (!sr) continue;
        if (sr.status === 'ok') {
          message.success(`${label} 已采纳`);
        } else if (sr.status === 'noop') {
          message.info(`${label} 已是生效版本`);
        } else {
          message.error(`${label} 采纳失败：${sr.reason ?? '未知错误'}`);
        }
      }
      if (result.anyFailed) {
        notification.warning({
          message: '部分采纳失败',
          description: '部分面采纳未成功，其余已成功的面已独立生效，不会回滚。请检查失败面后重试。',
          duration: 0,
        });
      }
    },
    [],
  );

  const runAdopt = useCallback(async () => {
    // Guard against a re-entrant call even though the button is also disabled.
    if (adopting || adopted) return;
    setAdopting(true);
    const req: AdoptBundleRequest = {
      promptVersionId,
      behaviorRuleVersionId,
      skillDraftId,
    };
    try {
      const res = await adoptEvolveBundle(evolveRunId, userId, req);
      const result = res.data;
      reportOutcome(result);
      // Mark as adopted only when nothing failed — a partial failure should
      // still allow a retry of the failed surfaces.
      if (!result.anyFailed) setAdopted(true);
      onAdopted?.(result);
    } catch (e) {
      message.error(`采纳请求失败：${errMsg(e)}`);
    } finally {
      setAdopting(false);
    }
  }, [
    adopting,
    adopted,
    promptVersionId,
    behaviorRuleVersionId,
    skillDraftId,
    evolveRunId,
    userId,
    reportOutcome,
    onAdopted,
  ]);

  const handleAdoptClick = useCallback(() => {
    if (adopting || adopted || confirmOpen) return;
    setConfirmOpen(true);
    Modal.confirm({
      title: '确认采纳整个候选包？',
      content:
        '整包一次生效：各面 prompt / behavior rule / skill 会分别 promote 到生效状态，操作不可撤销。',
      okText: 'Approve & Adopt',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: runAdopt,
      // Fires after the dialog fully closes for both OK and Cancel — re-enables
      // the button (unless adopt already flipped `adopted`).
      afterClose: () => setConfirmOpen(false),
    });
  }, [adopting, adopted, confirmOpen, runAdopt]);

  const hasAnySurface =
    promptVersionId != null || behaviorRuleVersionId != null || skillDraftId != null;

  if (!hasAnySurface) {
    return null;
  }

  return (
    <section className="eadopt-card" aria-label="Adopt candidate bundle" data-testid="evolve-adopt-card">
      <header className="eadopt-head">
        <div className="eadopt-head-main">
          <h4 className="eadopt-title">采纳赢家候选包</h4>
          <p className="eadopt-subtitle">
            迭代 #{iteration.iteration} · {iteration.changeDesc}
          </p>
        </div>
        <span
          className={
            'eadopt-delta ' +
            (iteration.delta > 0
              ? 'eadopt-delta--pos'
              : iteration.delta < 0
                ? 'eadopt-delta--neg'
                : 'eadopt-delta--zero')
          }
          title="本迭代整体 pass-rate delta"
          data-testid="eadopt-delta"
        >
          Δ {formatDelta(iteration.delta)}
        </span>
      </header>

      <div className="eadopt-surfaces">
        {promptVersionId != null && (
          <SurfaceSection
            title="Prompt"
            tagColor="geekblue"
            loading={promptCandidateQ.isLoading || promptCurrentQ.isLoading}
            error={
              promptCandidateQ.isError
                ? errMsg(promptCandidateQ.error)
                : promptCurrentQ.isError
                  ? errMsg(promptCurrentQ.error)
                  : null
            }
            current={promptCurrentQ.data ?? ''}
            candidate={promptCandidateQ.data ?? ''}
          />
        )}

        {behaviorRuleVersionId != null && (
          <SurfaceSection
            title="Behavior rule"
            tagColor="purple"
            loading={ruleCandidateQ.isLoading || ruleCurrentQ.isLoading}
            error={
              ruleCandidateQ.isError
                ? errMsg(ruleCandidateQ.error)
                : ruleCurrentQ.isError
                  ? errMsg(ruleCurrentQ.error)
                  : null
            }
            current={ruleCurrentQ.data ?? ''}
            candidate={ruleCandidateQ.data ?? ''}
            // No active rule version yet (empty `active` list) → treat as new,
            // mirroring the skill surface, rather than diffing against blank.
            isNew={!ruleCurrentQ.isLoading && !ruleCurrentQ.data}
          />
        )}

        {skillDraftId != null && (
          <SurfaceSection
            title="Skill"
            tagColor="cyan"
            loading={skillDraftQ.isLoading}
            error={skillDraftQ.isError ? errMsg(skillDraftQ.error) : null}
            current=""
            candidate={skillDraftQ.data ?? ''}
            isNew
          />
        )}
      </div>

      <footer className="eadopt-foot">
        <span className="eadopt-foot-hint">
          {adopted ? '已采纳 — 候选包已生效。' : '采纳后不可撤销。'}
        </span>
        <Button
          type="primary"
          danger
          loading={adopting}
          disabled={adopting || adopted || confirmOpen}
          onClick={handleAdoptClick}
          data-testid="eadopt-approve-btn"
        >
          {adopted ? '已采纳' : 'Approve & Adopt'}
        </Button>
      </footer>
    </section>
  );
};

export default EvolveAdoptCard;
