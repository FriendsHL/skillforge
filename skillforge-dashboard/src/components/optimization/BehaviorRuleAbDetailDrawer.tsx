import React, { useMemo } from 'react';
import {
  Drawer,
  Descriptions,
  Tag,
  Typography,
  Space,
  Empty,
  Alert,
  Table,
  Tooltip,
  Spin,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import {
  behaviorRuleApi,
  roleColor,
  roleLabel,
  type BehaviorRuleAbRun,
  type BehaviorRuleAbRunStatus,
  type BehaviorRuleAbScenarioResult,
} from '../../api/behaviorRule';
import {
  deltaTagColor,
  dualCriteriaFailureReason,
  TARGET_DELTA_GREEN_FLOOR_PP,
  REGRESSION_DELTA_FLOOR_PP,
} from './BehaviorRuleAbBadge';

const { Text, Paragraph } = Typography;

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

/** Format pass-rate (BE returns 0..100 percent number, NOT 0..1 fraction)
 *  as percent string. HOT-FIX commit cc7286b follow-up — first dogfood
 *  rendered "8163.3%" instead of "81.6%" because FE double-multiplied. */
function fmtPct(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return `${v.toFixed(1)}%`;
}

function fmtDeltaPp(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  const sign = v >= 0 ? '+' : '';
  return `${sign}${v.toFixed(1)}pp`;
}

function statusTagColor(status: BehaviorRuleAbRunStatus): string {
  switch (status) {
    case 'COMPLETED':
      return 'green';
    case 'RUNNING':
      return 'processing';
    case 'PENDING':
      return 'default';
    case 'FAILED':
      return 'red';
    case 'SUPERSEDED':
      return 'default';
    default:
      return 'default';
  }
}

function scoreOf(side: BehaviorRuleAbScenarioResult['baseline']): number | undefined {
  // BE {@code AbScenarioResult} record exposes {@code oracleScore} (judge
  // composite score 0..1) — single canonical field; legacy {@code compositeScore}
  // option was dropped in r2 cleanup so the shape matches the BE record.
  return side.oracleScore;
}

export interface BehaviorRuleAbDetailDrawerProps {
  /** The candidate version id whose latestAbRun the drawer is showing.
   *  When null, the drawer renders an empty placeholder (parent should still
   *  toggle `open` to control mount/unmount cost). */
  versionId: string | null;
  open: boolean;
  onClose: () => void;
}

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — drawer surfacing the dual-criteria A/B detail
 * for a single behavior_rule candidate version. Triggered from the
 * OptimizationEvents timeline row via the BehaviorRuleAbBadge.
 *
 * <p>Renders three layers (from top to bottom):
 *   1. Header chips: status / kind / dataset
 *   2. Aggregate Descriptions: baseline / candidate / overall delta
 *      + target subset delta + regression subset delta + dual-criteria chip
 *   3. Per-scenario Table (V2 optional): yellow row for target subset,
 *      white for regression. Banner when target subset is empty (fallback).
 *
 * <p>Pure presentational + own query — parent owns open/close + WS-invalidate.
 * The drawer re-uses the same {@code latestAbRun} query key so a WS invalidate
 * in OptimizationEvents auto-refreshes this view too.
 */
export const BehaviorRuleAbDetailDrawer: React.FC<BehaviorRuleAbDetailDrawerProps> = ({
  versionId,
  open,
  onClose,
}) => {
  const { data, isLoading, isError, error } = useQuery<BehaviorRuleAbRun | null>({
    queryKey: ['behavior-rule-ab', versionId],
    queryFn: () =>
      behaviorRuleApi.latestAbRun(versionId as string).then((r) => r.data),
    enabled: !!versionId && open,
    staleTime: 30_000,
  });

  const headerTitle = useMemo(() => {
    if (!data) return 'Behavior rule A/B';
    // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (D5 / AC-8): owner-role chip sits
    // FIRST in the header (right after the run id) — drawer top per the
    // tech-design §5.2 placement (after title, before Descriptions). Using
    // the conditional spread idiom keeps the previous tag order stable when
    // ownerAgentRole is null on legacy rows.
    return (
      <Space size="small" wrap>
        <span>A/B run #{data.id.slice(0, 8)}…</span>
        {data.ownerAgentRole && (
          <Tag color={roleColor(data.ownerAgentRole)}>
            {roleLabel(data.ownerAgentRole)}
          </Tag>
        )}
        <Tag color={statusTagColor(data.status)}>{data.status}</Tag>
        <Tag>{data.abRunKind}</Tag>
        {data.promoted && <Tag color="blue">promoted</Tag>}
      </Space>
    );
  }, [data]);

  const errMsg = useMemo(() => {
    if (!isError) return null;
    if (error instanceof Error) return error.message;
    return 'Failed to load A/B run detail.';
  }, [isError, error]);

  // ───────────────────────── per-scenario table ─────────────────────────

  const scenarioColumns: ColumnsType<BehaviorRuleAbScenarioResult> = useMemo(
    () => [
      {
        title: 'Scenario',
        dataIndex: 'scenarioName',
        key: 'scenarioName',
        ellipsis: true,
        render: (name: string, r) => (
          <Tooltip title={name}>
            <Text
              style={{
                fontSize: 12,
                fontFamily: 'var(--font-mono, monospace)',
              }}
            >
              {r.isTarget ? '★ ' : ''}
              {name}
            </Text>
          </Tooltip>
        ),
      },
      {
        title: 'Baseline',
        key: 'baseline',
        width: 110,
        render: (_: unknown, r) => (
          <Space size={4}>
            <Tag color={r.baseline.status === 'PASS' ? 'green' : 'red'}>
              {r.baseline.status}
            </Tag>
            <Text type="secondary" style={{ fontSize: 11 }}>
              {scoreOf(r.baseline)?.toFixed(2) ?? '—'}
            </Text>
          </Space>
        ),
      },
      {
        title: 'Candidate',
        key: 'candidate',
        width: 110,
        render: (_: unknown, r) => (
          <Space size={4}>
            <Tag color={r.candidate.status === 'PASS' ? 'green' : 'red'}>
              {r.candidate.status}
            </Tag>
            <Text type="secondary" style={{ fontSize: 11 }}>
              {scoreOf(r.candidate)?.toFixed(2) ?? '—'}
            </Text>
          </Space>
        ),
      },
      {
        title: 'Subset',
        key: 'subset',
        width: 110,
        render: (_: unknown, r) =>
          r.isTarget ? (
            <Tag color="gold">target</Tag>
          ) : (
            <Tag>regression</Tag>
          ),
      },
    ],
    [],
  );

  // ───────────────────────── render ─────────────────────────

  if (!versionId) {
    return (
      <Drawer open={open} onClose={onClose} width={900} title="Behavior rule A/B">
        <Empty description="No candidate selected" />
      </Drawer>
    );
  }

  if (isLoading) {
    return (
      <Drawer open={open} onClose={onClose} width={900} title="Behavior rule A/B">
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin />
        </div>
      </Drawer>
    );
  }

  if (errMsg) {
    return (
      <Drawer open={open} onClose={onClose} width={900} title="Behavior rule A/B">
        <Alert type="error" showIcon message={errMsg} />
      </Drawer>
    );
  }

  if (!data) {
    return (
      <Drawer open={open} onClose={onClose} width={900} title="Behavior rule A/B">
        <Empty description="No A/B run yet for this candidate. Trigger one via Retry." />
      </Drawer>
    );
  }

  const targetSubsetEmpty =
    (data.targetCount ?? 0) === 0 || data.targetDeltaPp === null;
  const dualOk = data.dualCriteriaSatisfied === true;

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={900}
      title={headerTitle}
      destroyOnClose
    >
      {data.status === 'FAILED' && data.failureReason && (
        <Alert
          type="error"
          showIcon
          message="A/B run failed"
          description={
            <Paragraph
              style={{
                margin: 0,
                whiteSpace: 'pre-wrap',
                fontSize: 12,
                fontFamily: 'var(--font-mono, monospace)',
              }}
            >
              {data.failureReason}
            </Paragraph>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      {targetSubsetEmpty && data.status === 'COMPLETED' && (
        <Alert
          type="warning"
          showIcon
          message="No target scenarios matched"
          description={
            <>
              The candidate version&apos;s {' '}
              <Text code style={{ fontSize: 11 }}>
                target_trigger_tags
              </Text>{' '}
              produced no hits in the dataset — running in{' '}
              <Text strong>regression-check-only mode</Text>. Promotion criteria
              relaxes to {' '}
              <Text code style={{ fontSize: 11 }}>
                regression_delta_pp ≥ {REGRESSION_DELTA_FLOOR_PP}
              </Text>{' '}
              (target delta is null per INV-4).
            </>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      <Descriptions
        size="small"
        column={2}
        bordered
        style={{ marginBottom: 16 }}
        labelStyle={{ width: 150, fontSize: 12 }}
        contentStyle={{ fontSize: 12 }}
      >
        <Descriptions.Item label="Agent">
          <Text style={{ fontFamily: 'var(--font-mono, monospace)' }}>
            #{data.agentId}
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="Candidate version">
          <Text style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 11 }}>
            {data.candidateVersionId.slice(0, 16)}…
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="Dataset">
          {data.datasetVersionId ? (
            <Text style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 11 }}>
              {data.datasetVersionId.slice(0, 16)}…
            </Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Run kind">{data.abRunKind}</Descriptions.Item>
        <Descriptions.Item label="Baseline pass-rate">
          {fmtPct(data.baselinePassRate)}
        </Descriptions.Item>
        <Descriptions.Item label="Candidate pass-rate">
          {fmtPct(data.candidatePassRate)}
        </Descriptions.Item>
        <Descriptions.Item label="Overall delta">
          {/* r2-FE-1 fix: never use truthy `?` on numeric delta fields — 0 is
              a confirmed-zero-impact result, not "missing data". Always
              compare `!= null` so a +0.0pp run still renders its Tag.
              HOT-FIX cc7286b follow-up: BE deltaPassRate IS the pp value
              already (e.g. 2.04 means +2.04pp); NO `* 100` multiply. */}
          <Tag color={deltaTagColor(data.deltaPassRate ?? null)}>
            {fmtDeltaPp(data.deltaPassRate ?? null)}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="Dual-criteria">
          {data.status !== 'COMPLETED' ? (
            <Text type="secondary">pending</Text>
          ) : dualOk ? (
            <Tag color="green">satisfied</Tag>
          ) : (
            <Tooltip title={dualCriteriaFailureReason(data)}>
              <Tag color="red">not satisfied</Tag>
            </Tooltip>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Target subset">
          <Space size={4}>
            <Text>n={data.targetCount ?? 0}</Text>
            <Tag color={deltaTagColor(data.targetDeltaPp)}>
              Δ {fmtDeltaPp(data.targetDeltaPp)}
            </Tag>
            <Text type="secondary" style={{ fontSize: 11 }}>
              (threshold ≥ +{TARGET_DELTA_GREEN_FLOOR_PP}pp)
            </Text>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="Regression subset">
          <Space size={4}>
            <Text>n={data.regressionCount ?? 0}</Text>
            <Tag color={deltaTagColor(data.regressionDeltaPp)}>
              Δ {fmtDeltaPp(data.regressionDeltaPp)}
            </Tag>
            <Text type="secondary" style={{ fontSize: 11 }}>
              (floor ≥ {REGRESSION_DELTA_FLOOR_PP}pp)
            </Text>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="Started">
          {fmtAbsolute(data.startedAt) || <Text type="secondary">—</Text>}
        </Descriptions.Item>
        <Descriptions.Item label="Completed">
          {fmtAbsolute(data.completedAt) || <Text type="secondary">—</Text>}
        </Descriptions.Item>
      </Descriptions>

      {data.scenarioResults && data.scenarioResults.length > 0 ? (
        <>
          <Typography.Title level={5} style={{ marginBottom: 8 }}>
            Per-scenario breakdown
          </Typography.Title>
          <Table<BehaviorRuleAbScenarioResult>
            rowKey="scenarioId"
            columns={scenarioColumns}
            dataSource={data.scenarioResults}
            size="small"
            pagination={{ pageSize: 20, hideOnSinglePage: true, showSizeChanger: false }}
            rowClassName={(r) =>
              r.isTarget ? 'sf-brar-row-target' : 'sf-brar-row-regression'
            }
          />
          <style>
            {`.sf-brar-row-target > td {
                background: rgba(245, 192, 49, 0.12) !important;
              }
              .sf-brar-row-regression > td {
                background: transparent;
              }`}
          </style>
        </>
      ) : (
        <Alert
          type="info"
          showIcon
          message="Per-scenario detail not yet exposed by the V1 BE DTO."
          description={
            <Text type="secondary" style={{ fontSize: 12 }}>
              The aggregate target / regression subset deltas above are the
              full V1 surface. Per-scenario breakdown is on the V2 roadmap
              (BE serializes {`'abScenarioResultsJson'`} on the entity but
              the response DTO does not currently expose it).
            </Text>
          }
        />
      )}
    </Drawer>
  );
};

export default BehaviorRuleAbDetailDrawer;
