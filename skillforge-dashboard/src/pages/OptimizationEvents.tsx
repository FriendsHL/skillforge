import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Typography,
  Table,
  Tag,
  Tooltip,
  Space,
  Button,
  Select,
  InputNumber,
  Input,
  Modal,
  Form,
  message,
  notification,
  Alert,
  Empty,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useAuth } from '../contexts/AuthContext';
import {
  listEvents,
  approveEvent,
  rejectEvent,
  retryEvent,
  type AttributionStage,
  type AttributionSurface,
  type AttributionEventUpdatedMessage,
  type OptimizationEventDto,
  type ListEventsResponse,
} from '../api/attribution';
import EventDetailDrawer from '../components/attribution/EventDetailDrawer';
import { surfaceColor, riskColor } from '../components/attribution/stageStyle';
import BehaviorRuleAbRowActions from '../components/optimization/BehaviorRuleAbRowActions';
import BehaviorRuleAbDetailDrawer from '../components/optimization/BehaviorRuleAbDetailDrawer';
import type { BehaviorRuleAbRunUpdatedMessage } from '../api/behaviorRule';

dayjs.extend(relativeTime);

const { Title, Text } = Typography;

/** Stage options for the timeline filter dropdown. `undefined` = "all". */
const STAGE_OPTIONS: { value: AttributionStage; label: string }[] = [
  { value: 'dispatch_initiated', label: 'dispatch_initiated' },
  { value: 'proposal_pending', label: 'proposal_pending' },
  { value: 'proposal_approved', label: 'proposal_approved' },
  { value: 'proposal_rejected', label: 'proposal_rejected' },
  { value: 'candidate_generating', label: 'candidate_generating' },
  { value: 'candidate_ready', label: 'candidate_ready' },
  { value: 'candidate_failed', label: 'candidate_failed' },
  { value: 'candidate_created', label: 'candidate_created' },
  { value: 'ab_running', label: 'ab_running' },
  { value: 'ab_passed', label: 'ab_passed' },
  { value: 'ab_failed', label: 'ab_failed' },
  { value: 'canary_started', label: 'canary_started' },
  { value: 'promoted', label: 'promoted' },
  { value: 'rolled_back', label: 'rolled_back' },
  { value: 'verified', label: 'verified' },
];

/** Phase grouping — groups the 15 raw stages into 4 logical phases (F6). */
const STAGE_PHASES: { phase: string; phaseColor: string; stages: AttributionStage[] }[] = [
  { phase: 'Proposal', phaseColor: 'gold', stages: ['dispatch_initiated', 'proposal_pending', 'proposal_approved', 'proposal_rejected'] },
  { phase: 'Candidate', phaseColor: 'blue', stages: ['candidate_generating', 'candidate_ready', 'candidate_failed', 'candidate_created'] },
  { phase: 'A/B Test', phaseColor: 'geekblue', stages: ['ab_running', 'ab_passed', 'ab_failed'] },
  { phase: 'Rollout', phaseColor: 'purple', stages: ['canary_started', 'promoted', 'rolled_back', 'verified'] },
];

/** Map each stage → its phase colour for table chip rendering (F6). */
const STAGE_TO_PHASE_COLOR: Record<string, string> = {};
for (const group of STAGE_PHASES) {
  for (const s of group.stages) {
    STAGE_TO_PHASE_COLOR[s] = group.phaseColor;
  }
}

/** Grouped Select options — Ant Design native optgroup format. */
const STAGE_GROUPS = STAGE_PHASES.map((g) => ({
  label: g.phase,
  options: STAGE_OPTIONS.filter((o) => (g.stages as string[]).includes(o.value)).map((o) => ({
    value: o.value,
    label: o.label,
  })),
}));

/** Quick-reject reasons (F8) — chip set for the reject modal. */
const QUICK_REJECT_REASONS = [
  'off-topic',
  'duplicate',
  'low-impact',
  'risk-too-high',
];

const SURFACE_OPTIONS: { value: AttributionSurface; label: string }[] = [
  { value: 'skill', label: 'skill' },
  { value: 'prompt', label: 'prompt' },
  { value: 'behavior_rule', label: 'behavior_rule' },
  { value: 'other', label: 'other' },
  { value: 'unclear', label: 'unclear' },
];

const TIMELINE_PAGE_SIZE = 20;

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

/** Truncate a long description preview for table columns. Accepts null
 * because BE V80 marks `description` nullable (sentinel rows). */
function truncate(s: string | null | undefined, max = 80): string {
  if (!s) return '';
  return s.length > max ? `${s.slice(0, max)}…` : s;
}

interface RejectFormValues {
  reason: string;
}

/**
 * V3 ATTRIBUTION-AGENT Phase 1.5 — `/insights/optimization-events` page.
 *
 * Two-pane layout:
 *   - Top: Pending Approvals queue (stage=proposal_pending, action buttons).
 *   - Bottom: Recent Events timeline (filterable; click row → drawer).
 *
 * Live updates via the layout-level `/ws/users/{userId}` socket — we open a
 * second connection here scoped to attribution events so the page works even
 * when the layout WS bus isn't carrying them.
 */
interface OptimizationEventsProps {
  /**
   * FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — initial stage filter
   * passed from Insights when the URL is `?tab=optimization&stage=…`. The
   * flywheel drill-down link for G1/step6/G3 lands here pre-filtered to
   * `proposal_pending` / `ab_passed` respectively (PRD R3 真消费 requirement).
   */
  initialStageFilter?: AttributionStage;
  /** Same as above for `?agentId=N` flywheel drill-down. */
  initialAgentIdFilter?: number;
}

const OptimizationEvents: React.FC<OptimizationEventsProps> = ({
  initialStageFilter,
  initialAgentIdFilter,
}) => {
  const { userId } = useAuth();
  const queryClient = useQueryClient();

  // Timeline filter state — page=0 is the only default the user-facing filter
  // toggles trigger a re-query against. agentId uses InputNumber (BE accepts
  // raw long; no enum constraint).
  // ts-B3 fix — accept initial filter from the parent Insights tab so
  // flywheel drill-down ?stage= URL param actually narrows the table.
  const [stageFilter, setStageFilter] = useState<AttributionStage | undefined>(initialStageFilter);
  const [agentIdFilter, setAgentIdFilter] = useState<number | undefined>(initialAgentIdFilter);
  const [surfaceFilter, setSurfaceFilter] = useState<AttributionSurface | undefined>(undefined);
  const [page, setPage] = useState<number>(0);

  // ts-B3 — re-apply initial filter when the URL `?stage=` flips (e.g.
  // operator navigates from G1 step-card → G3 step-card without unmounting
  // Insights). Keep the existing user-edited filter for filters the URL
  // doesn't try to set, so we don't wipe a manually-typed agent id either.
  useEffect(() => {
    if (initialStageFilter !== undefined) setStageFilter(initialStageFilter);
  }, [initialStageFilter]);
  useEffect(() => {
    if (initialAgentIdFilter !== undefined) setAgentIdFilter(initialAgentIdFilter);
  }, [initialAgentIdFilter]);

  const [drawerEvent, setDrawerEvent] = useState<OptimizationEventDto | null>(null);
  const [drawerOpen, setDrawerOpen] = useState<boolean>(false);

  // BEHAVIOR-RULE-AB-EVAL V1 — separate drawer for the per-row A/B detail
  // surface. Holds the candidate version id (not the event id) because the
  // drawer queries latestAbRun(versionId).
  const [behaviorRuleDrawerVersionId, setBehaviorRuleDrawerVersionId] =
    useState<string | null>(null);
  const [behaviorRuleDrawerOpen, setBehaviorRuleDrawerOpen] = useState<boolean>(false);

  const openBehaviorRuleDrawer = useCallback((versionId: string) => {
    setBehaviorRuleDrawerVersionId(versionId);
    setBehaviorRuleDrawerOpen(true);
  }, []);
  const closeBehaviorRuleDrawer = useCallback(() => {
    setBehaviorRuleDrawerOpen(false);
  }, []);

  const [rejectTarget, setRejectTarget] = useState<OptimizationEventDto | null>(null);
  const [rejectSubmitting, setRejectSubmitting] = useState<boolean>(false);
  const [rejectForm] = Form.useForm<RejectFormValues>();

  // ── F8: Batch selection for pending approvals ──
  const [selectedPendingKeys, setSelectedPendingKeys] = useState<React.Key[]>([]);

  const [actionLoadingId, setActionLoadingId] = useState<number | null>(null);

  // ── F8: Quick-reject chip handler ──
  const appendQuickRejectReason = useCallback(
    (chip: string) => {
      const current = rejectForm.getFieldValue('reason') ?? '';
      const sep = current.trim() ? ', ' : '';
      rejectForm.setFieldsValue({ reason: `${current}${sep}${chip}` });
    },
    [rejectForm],
  );

  // ── F8: Approve note state ──
  const [approveNoteModal, setApproveNoteModal] = useState<{
    event: OptimizationEventDto;
  } | null>(null);
  const [approveNote, setApproveNote] = useState('');

  // ───────────────────────── queries ─────────────────────────

  // Pending queue: always pinned to stage=proposal_pending, ignores filter
  // dropdowns so the operator never loses sight of action items.
  const {
    data: pendingData,
    isLoading: pendingLoading,
    isError: pendingError,
    error: pendingErrorObj,
  } = useQuery<ListEventsResponse>({
    queryKey: ['attribution-events', 'pending'],
    queryFn: () =>
      listEvents({ stage: 'proposal_pending', page: 0, size: 50 }).then((r) => r.data),
    // 30s is a nice middle-ground: WS push invalidates on every transition,
    // and the dispatcher cron is hourly. No need to hammer.
    staleTime: 30_000,
  });

  // Timeline: filtered list of recent events.
  const timelineParams = useMemo(
    () => ({
      stage: stageFilter,
      agentId: agentIdFilter,
      surfaceType: surfaceFilter,
      page,
      size: TIMELINE_PAGE_SIZE,
    }),
    [stageFilter, agentIdFilter, surfaceFilter, page],
  );

  const {
    data: timelineData,
    isLoading: timelineLoading,
    isError: timelineError,
    error: timelineErrorObj,
  } = useQuery<ListEventsResponse>({
    queryKey: ['attribution-events', 'timeline', timelineParams],
    queryFn: () => listEvents(timelineParams).then((r) => r.data),
    staleTime: 30_000,
    placeholderData: (prev) => prev,
  });

  const pendingErr = useMemo(() => {
    if (!pendingError) return null;
    if (pendingErrorObj instanceof Error) return pendingErrorObj.message;
    return 'Failed to load pending approvals.';
  }, [pendingError, pendingErrorObj]);

  const timelineErr = useMemo(() => {
    if (!timelineError) return null;
    if (timelineErrorObj instanceof Error) return timelineErrorObj.message;
    return 'Failed to load timeline.';
  }, [timelineError, timelineErrorObj]);

  const invalidateAll = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['attribution-events'] });
  }, [queryClient]);

  // ───────────────────────── WS subscription ─────────────────────────

  // Subscribe to /ws/users/{userId} for `attribution_event_updated` payloads.
  // Per frontend.md footgun #2: cleanup MUST close the socket. Per ratify with
  // the team-lead brief, we increment stage in-place on the cached row so the
  // user sees the chip flip without a full refetch; the `notification` call
  // surfaces stage transitions.
  useEffect(() => {
    if (!userId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as Partial<AttributionEventUpdatedMessage> &
          Partial<BehaviorRuleAbRunUpdatedMessage> & {
            type?: string;
          };

        // BEHAVIOR-RULE-AB-EVAL V1 — separate WS event from
        // attribution_event_updated. Carries (candidateVersionId, status, event)
        // so we can invalidate the per-row latestAbRun query without touching
        // attribution-events caches.
        if (
          msg.type === 'behavior_rule_ab_run_updated' &&
          typeof msg.candidateVersionId === 'string'
        ) {
          const candidateVersionId = msg.candidateVersionId;
          queryClient.invalidateQueries({
            queryKey: ['behavior-rule-ab', candidateVersionId],
          });
          // The stage on the related attribution event may have shifted too
          // (ab_running / ab_passed / ab_failed) — refresh the attribution
          // timeline so the chip flips without waiting for the cron tick.
          queryClient.invalidateQueries({ queryKey: ['attribution-events'] });
          return;
        }

        if (msg.type !== 'attribution_event_updated' || typeof msg.eventId !== 'number') {
          return;
        }
        const eventId = msg.eventId;
        const nextStage = msg.stage ?? '';
        const prevStage = msg.previousStage ?? null;
        const updatedAt = msg.updatedAt ?? new Date().toISOString();

        // Notification toast — short label so it doesn't drown the timeline.
        notification.info({
          message: 'Attribution event updated',
          description: prevStage
            ? `Event #${eventId}: ${prevStage} → ${nextStage}`
            : `Event #${eventId}: ${nextStage}`,
          duration: 4,
        });

        // Incremental cache patch: walk every cached attribution-events query
        // (pending + every timelineParams permutation) and update only the
        // matching row's stage + updatedAt. If the row isn't on this page,
        // we still bump staleness so the queue re-fetches on next focus.
        queryClient.setQueriesData<ListEventsResponse | undefined>(
          { queryKey: ['attribution-events'] },
          (old) => {
            if (!old || !old.items) return old;
            let changed = false;
            const items = old.items.map((row) => {
              if (row.id === eventId) {
                changed = true;
                return { ...row, stage: nextStage, updatedAt };
              }
              return row;
            });
            return changed ? { ...old, items } : old;
          },
        );

        // Pending queue may need to drop the row (if transition leaves
        // proposal_pending) or add a new one (rare). Cheapest safe path:
        // invalidate just the pending query so it refetches.
        queryClient.invalidateQueries({
          queryKey: ['attribution-events', 'pending'],
        });

        // If the drawer is showing this event, mirror the patch there too so
        // the chip flips without re-opening.
        setDrawerEvent((current) =>
          current && current.id === eventId
            ? { ...current, stage: nextStage, updatedAt }
            : current,
        );
      } catch {
        /* ignore malformed WS frames */
      }
    };

    return () => {
      try {
        ws.close();
      } catch {
        /* ignore */
      }
    };
  }, [userId, queryClient]);

  // ───────────────────────── actions ─────────────────────────

  const onApprove = useCallback(
    (event: OptimizationEventDto) => {
      setApproveNote('');
      setApproveNoteModal({ event });
    },
    [],
  );

  const onSubmitApprove = useCallback(async () => {
    if (!approveNoteModal) return;
    const { event } = approveNoteModal;
    setActionLoadingId(event.id);
    try {
      await approveEvent(event.id, { approverUserId: userId, note: approveNote.trim() || undefined });
      message.success(`Event #${event.id} approved.`);
      setApproveNoteModal(null);
      invalidateAll();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      const reason = e.response?.data?.error || e.message || 'Approve failed.';
      message.error(reason);
    } finally {
      setActionLoadingId(null);
    }
  }, [approveNoteModal, approveNote, userId, invalidateAll]);

  const onOpenReject = useCallback(
    (event: OptimizationEventDto) => {
      setRejectTarget(event);
      rejectForm.resetFields();
    },
    [rejectForm],
  );

  const onSubmitReject = useCallback(async () => {
    if (!rejectTarget) return;
    let values: RejectFormValues;
    try {
      values = await rejectForm.validateFields();
    } catch {
      return;
    }
    setRejectSubmitting(true);
    try {
      await rejectEvent(rejectTarget.id, {
        approverUserId: userId,
        reason: values.reason.trim(),
      });
      message.success(`Event #${rejectTarget.id} rejected.`);
      setRejectTarget(null);
      invalidateAll();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      const reason = e.response?.data?.error || e.message || 'Reject failed.';
      message.error(reason);
    } finally {
      setRejectSubmitting(false);
    }
  }, [rejectTarget, userId, rejectForm, invalidateAll]);

  const onRetry = useCallback(
    (event: OptimizationEventDto) => {
      Modal.confirm({
        title: `Retry candidate generation for event #${event.id}?`,
        content:
          'Retry flips the stage back to candidate_generating and re-runs the ' +
          'downstream skill/prompt creation pipeline.',
        okText: 'Retry',
        okType: 'primary',
        cancelText: 'Cancel',
        onOk: async () => {
          setActionLoadingId(event.id);
          try {
            await retryEvent(event.id, { approverUserId: userId });
            message.success(`Event #${event.id} retry started.`);
            invalidateAll();
          } catch (err: unknown) {
            const e = err as { response?: { data?: { error?: string } }; message?: string };
            const reason = e.response?.data?.error || e.message || 'Retry failed.';
            message.error(reason);
          } finally {
            setActionLoadingId(null);
          }
        },
      });
    },
    [userId, invalidateAll],
  );

  const openDrawer = useCallback((event: OptimizationEventDto) => {
    setDrawerEvent(event);
    setDrawerOpen(true);
  }, []);

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false);
  }, []);

  // ───────────────────────── columns ─────────────────────────

  const pendingColumns: ColumnsType<OptimizationEventDto> = useMemo(
    () => [
      {
        title: 'Description',
        dataIndex: 'description',
        key: 'description',
        ellipsis: true,
        render: (text: string | null, record) =>
          text ? (
            <Tooltip title={text} placement="topLeft">
              <Text
                style={{ cursor: 'pointer', maxWidth: 360, display: 'inline-block' }}
                onClick={() => openDrawer(record)}
              >
                {truncate(text, 80)}
              </Text>
            </Tooltip>
          ) : (
            <Text
              type="secondary"
              style={{ cursor: 'pointer' }}
              onClick={() => openDrawer(record)}
            >
              —
            </Text>
          ),
      },
      {
        title: 'Surface',
        dataIndex: 'surfaceType',
        key: 'surfaceType',
        width: 110,
        render: (s: string) => <Tag color={surfaceColor(s)}>{s}</Tag>,
      },
      {
        title: 'Agent',
        dataIndex: 'agentId',
        key: 'agentId',
        width: 80,
        render: (id: number) => (
          <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>#{id}</Text>
        ),
      },
      {
        title: 'Confidence',
        dataIndex: 'confidence',
        key: 'confidence',
        width: 100,
        align: 'right' as const,
        // Nullable on BE for sentinel rows; Number.isFinite(null) === false
        // so the dash branch fires correctly at runtime.
        render: (v: number | null) =>
          typeof v === 'number' && Number.isFinite(v) ? (
            <Text strong>{Math.round(v * 100)}%</Text>
          ) : (
            <Text type="secondary">—</Text>
          ),
      },
      {
        title: 'Risk',
        dataIndex: 'risk',
        key: 'risk',
        width: 90,
        render: (r: string | null) =>
          r ? <Tag color={riskColor(r)}>{r}</Tag> : <Text type="secondary">—</Text>,
      },
      {
        title: 'Created',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 130,
        render: (iso: string) => (
          <Tooltip title={fmtAbsolute(iso)}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {fmtRelative(iso)}
            </Text>
          </Tooltip>
        ),
      },
      {
        title: 'Actions',
        key: 'actions',
        width: 220,
        render: (_: unknown, record) => (
          <Space size="small">
            <Button
              type="primary"
              size="small"
              loading={actionLoadingId === record.id}
              onClick={() => onApprove(record)}
            >
              Approve
            </Button>
            <Button
              danger
              size="small"
              disabled={actionLoadingId === record.id}
              onClick={() => onOpenReject(record)}
            >
              Reject
            </Button>
          </Space>
        ),
      },
    ],
    [actionLoadingId, onApprove, onOpenReject, openDrawer],
  );

  const timelineColumns: ColumnsType<OptimizationEventDto> = useMemo(
    () => [
      {
        title: 'Stage',
        dataIndex: 'stage',
        key: 'stage',
        width: 170,
        render: (s: string) => <Tag color={STAGE_TO_PHASE_COLOR[s] ?? 'default'}>{s}</Tag>,
      },
      {
        title: 'Description',
        dataIndex: 'description',
        key: 'description',
        ellipsis: true,
        render: (text: string | null) =>
          text ? (
            <Tooltip title={text} placement="topLeft">
              <Text style={{ maxWidth: 360, display: 'inline-block' }}>
                {truncate(text, 80)}
              </Text>
            </Tooltip>
          ) : (
            <Text type="secondary">—</Text>
          ),
      },
      {
        title: 'Surface',
        dataIndex: 'surfaceType',
        key: 'surfaceType',
        width: 110,
        render: (s: string) => <Tag color={surfaceColor(s)}>{s}</Tag>,
      },
      {
        title: 'Agent',
        dataIndex: 'agentId',
        key: 'agentId',
        width: 80,
        render: (id: number) => (
          <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>#{id}</Text>
        ),
      },
      {
        title: 'Risk',
        dataIndex: 'risk',
        key: 'risk',
        width: 90,
        render: (r: string | null) =>
          r ? <Tag color={riskColor(r)}>{r}</Tag> : <Text type="secondary">—</Text>,
      },
      {
        title: 'Updated',
        dataIndex: 'updatedAt',
        key: 'updatedAt',
        width: 130,
        render: (iso: string) => (
          <Tooltip title={fmtAbsolute(iso)}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {fmtRelative(iso)}
            </Text>
          </Tooltip>
        ),
      },
      {
        title: 'Actions',
        key: 'actions',
        width: 320,
        render: (_: unknown, record) => {
          // BEHAVIOR-RULE-AB-EVAL V1 — behavior_rule + candidate_ready rows
          // get a dual-criteria badge + Promote/Retry buttons (PRD UC-1/2/3).
          // Falls through to legacy candidate_failed retry for other surfaces.
          if (
            record.surfaceType === 'behavior_rule' &&
            record.stage === 'candidate_ready' &&
            record.candidateBehaviorRuleVersionId
          ) {
            return (
              <BehaviorRuleAbRowActions
                versionId={record.candidateBehaviorRuleVersionId}
                onOpenDetail={openBehaviorRuleDrawer}
              />
            );
          }
          return record.stage === 'candidate_failed' ? (
            <Button
              size="small"
              loading={actionLoadingId === record.id}
              onClick={(e) => {
                e.stopPropagation();
                onRetry(record);
              }}
            >
              Retry
            </Button>
          ) : null;
        },
      },
    ],
    [actionLoadingId, onRetry, openBehaviorRuleDrawer],
  );

  // ───────────────────────── render ─────────────────────────

  const pendingItems = pendingData?.items ?? [];
  const timelineItems = timelineData?.items ?? [];
  const timelineTotal = timelineData?.total ?? 0;

  const onResetFilters = () => {
    setStageFilter(undefined);
    setAgentIdFilter(undefined);
    setSurfaceFilter(undefined);
    setPage(0);
  };

  const statsBar: React.CSSProperties = {
    display: 'flex',
    gap: 'var(--sp-6, 24px)',
    marginBottom: 'var(--sp-6, 24px)',
  };
  const statItem: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    gap: 2,
  };
  const statCount: React.CSSProperties = {
    fontFamily: 'var(--font-serif)',
    fontSize: 28,
    fontWeight: 500,
    letterSpacing: '-0.02em',
    lineHeight: 1.2,
  };
  const statLabel: React.CSSProperties = {
    fontSize: 'var(--font-size-xs, 11px)',
    color: 'var(--fg-3)',
    letterSpacing: '0.05em',
    textTransform: 'uppercase' as const,
  };
  const sectionCard: React.CSSProperties = {
    border: '1px solid var(--border, #e0e0e0)',
    borderRadius: 8,
    padding: 'var(--sp-5, 20px)',
    marginBottom: 'var(--sp-5, 20px)',
  };

  return (
    <div
      style={{
        padding: 'var(--sp-6, 24px) var(--sp-8, 32px)',
        maxWidth: 1600,
        margin: '0 auto',
      }}
    >
      {/* Stats */}
      <div style={statsBar}>
        <div style={statItem}>
          <span style={{ ...statCount, color: 'var(--color-warn, #d49a3a)' }}>
            {pendingItems.length}
          </span>
          <span style={statLabel}>pending</span>
        </div>
        <div style={statItem}>
          <span style={statCount}>{timelineTotal}</span>
          <span style={statLabel}>total events</span>
        </div>
      </div>

      {/* ─── F7: Client-side Funnel (纯前端, 从 timeline 数据计算) ─── */}
      {timelineItems.length > 0 && (
        <div style={sectionCard}>
          <Title level={4} style={{ margin: '0 0 12px' }}>
            Conversion funnel
          </Title>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {(() => {
              const phaseCounts = STAGE_PHASES.map((p) => ({
                ...p,
                count: timelineItems.filter((item) =>
                  (p.stages as string[]).includes(item.stage),
                ).length,
              }));
              const maxCount = Math.max(...phaseCounts.map((p) => p.count), 1);
              const baselineCount = phaseCounts[0]?.count ?? 0;
              return phaseCounts.map((p) => (
                <div key={p.phase} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span
                    style={{
                      width: 80,
                      fontSize: 12,
                      fontWeight: 600,
                      color: 'var(--fg-1)',
                      textAlign: 'right' as const,
                    }}
                  >
                    {p.phase}
                  </span>
                  <div
                    style={{
                      flex: 1,
                      height: 24,
                      borderRadius: 4,
                      background: 'var(--bg-elev, #f0f0f0)',
                      position: 'relative',
                      overflow: 'hidden',
                    }}
                  >
                    <div
                      style={{
                        height: '100%',
                        width: `${(p.count / maxCount) * 100}%`,
                        borderRadius: 4,
                        background: p.phaseColor === 'gold' ? '#d49a3a'
                          : p.phaseColor === 'blue' ? '#4a8cf7'
                          : p.phaseColor === 'geekblue' ? '#667eea'
                          : '#a78bfa',
                        transition: 'width 300ms ease',
                        display: 'flex',
                        alignItems: 'center',
                        paddingLeft: 8,
                        minWidth: p.count > 0 ? 40 : 0,
                      }}
                    >
                      {p.count > 0 && (
                        <span style={{ fontSize: 11, fontWeight: 600, color: '#fff' }}>
                          {p.count}
                        </span>
                      )}
                    </div>
                  </div>
                  <span style={{ width: 60, fontSize: 11, color: 'var(--fg-3)' }}>
                    {baselineCount > 0 ? `${Math.round((p.count / baselineCount) * 100)}%` : '—'}
                  </span>
                </div>
              ));
            })()}
          </div>
        </div>
      )}

      {/* ─── Pending Approvals ─── */}
      <div style={sectionCard}>
        <Space align="center" style={{ marginBottom: 16 }}>
          <Title level={4} style={{ margin: 0 }}>
            Pending approvals
          </Title>
          {pendingItems.length > 0 && (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                minWidth: 24,
                height: 22,
                borderRadius: 11,
                background: 'var(--color-warn, #d49a3a)',
                color: '#fff',
                fontSize: 12,
                fontWeight: 600,
                padding: '0 8px',
              }}
            >
              {pendingItems.length}
            </span>
          )}
        </Space>

        {pendingErr && (
          <Alert
            type="error"
            showIcon
            message={pendingErr}
            style={{ marginBottom: 12 }}
          />
        )}

        {/* ── F8: Batch action bar ── */}
        {selectedPendingKeys.length > 0 && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              marginBottom: 12,
              padding: '8px 12px',
              borderRadius: 6,
              border: '1px solid var(--border, #e0e0e0)',
              background: 'var(--bg-elev, #f5f5f5)',
            }}
          >
            <Text type="secondary" style={{ fontSize: 13 }}>
              {selectedPendingKeys.length} selected
            </Text>
            <Button
              type="primary"
              size="small"
              onClick={async () => {
                let approved = 0;
                const failed: React.Key[] = [];
                for (const id of selectedPendingKeys) {
                  try {
                    await approveEvent(Number(id), { approverUserId: userId });
                    approved += 1;
                  } catch {
                    failed.push(id);
                  }
                }
                if (failed.length > 0) {
                  message.warning(`Approved ${approved} events; ${failed.length} failed.`);
                } else {
                  message.success(`Approved ${approved} events.`);
                }
                setSelectedPendingKeys(failed);
                invalidateAll();
              }}
            >
              Approve all
            </Button>
            <Button
              danger
              size="small"
              onClick={async () => {
                let rejected = 0;
                const failed: React.Key[] = [];
                for (const id of selectedPendingKeys) {
                  try {
                    await rejectEvent(Number(id), {
                      approverUserId: userId,
                      reason: 'batch reject',
                    });
                    rejected += 1;
                  } catch {
                    failed.push(id);
                  }
                }
                if (failed.length > 0) {
                  message.warning(`Rejected ${rejected} events; ${failed.length} failed.`);
                } else {
                  message.success(`Rejected ${rejected} events.`);
                }
                setSelectedPendingKeys(failed);
                invalidateAll();
              }}
            >
              Reject all
            </Button>
            <Button size="small" onClick={() => setSelectedPendingKeys([])}>
              Clear
            </Button>
          </div>
        )}

        <Table<OptimizationEventDto>
          rowKey="id"
          columns={pendingColumns}
          dataSource={pendingItems}
          loading={pendingLoading}
          size="small"
          rowSelection={{
            selectedRowKeys: selectedPendingKeys,
            onChange: (keys) => setSelectedPendingKeys(keys),
          }}
          pagination={{ pageSize: 10, showSizeChanger: false, hideOnSinglePage: true }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  <Text type="secondary">
                    No pending proposals — the attribution-curator dispatcher runs hourly.
                  </Text>
                }
              />
            ),
          }}
        />
      </div>

      {/* ─── Timeline ─── */}
      <div style={sectionCard}>
        <Title level={4} style={{ margin: '0 0 12px' }}>
          Recent events
        </Title>

        {/* Filter bar — three controls + reset. Each is independent. */}
        <Space wrap style={{ marginBottom: 12 }}>
          <Select<AttributionStage>
            allowClear
            placeholder="Stage · 4 phases"
            style={{ minWidth: 240 }}
            value={stageFilter}
            options={STAGE_GROUPS}
            onChange={(v) => {
              setStageFilter(v);
              setPage(0);
            }}
          />
          <Select<AttributionSurface>
            allowClear
            placeholder="Surface"
            style={{ minWidth: 160 }}
            value={surfaceFilter}
            options={SURFACE_OPTIONS}
            onChange={(v) => {
              setSurfaceFilter(v);
              setPage(0);
            }}
          />
          <InputNumber
            placeholder="Agent id"
            style={{ width: 140 }}
            min={1}
            value={agentIdFilter ?? null}
            onChange={(v) => {
              setAgentIdFilter(typeof v === 'number' ? v : undefined);
              setPage(0);
            }}
          />
          <Button onClick={onResetFilters}>Reset</Button>
        </Space>

        {timelineErr && (
          <Alert
            type="error"
            showIcon
            message={timelineErr}
            style={{ marginBottom: 12 }}
          />
        )}

        <Table<OptimizationEventDto>
          rowKey="id"
          columns={timelineColumns}
          dataSource={timelineItems}
          loading={timelineLoading}
          size="small"
          pagination={{
            current: page + 1,
            pageSize: TIMELINE_PAGE_SIZE,
            total: timelineTotal,
            showSizeChanger: false,
            onChange: (p) => setPage(p - 1),
          }}
          onRow={(record) => ({
            onClick: () => openDrawer(record),
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  <Text type="secondary">
                    No events match the current filter.
                  </Text>
                }
              />
            ),
          }}
        />
      </div>

      {/* ─── Approve note modal (F8: symmetric friction) ─── */}
      <Modal
        title={approveNoteModal ? `Approve event #${approveNoteModal.event.id}` : ''}
        open={approveNoteModal !== null}
        onOk={onSubmitApprove}
        onCancel={() => setApproveNoteModal(null)}
        okText="Approve"
        okButtonProps={{ loading: actionLoadingId !== null }}
        cancelButtonProps={{ disabled: actionLoadingId !== null }}
        destroyOnClose
      >
        <p style={{ color: 'var(--fg-2)', marginBottom: 16, fontSize: 14 }}>
          Approving will dispatch candidate generation for{' '}
          <code>surface={approveNoteModal?.event?.surfaceType}</code>.
        </p>
        <Input.TextArea
          rows={2}
          placeholder="Optional note (why approved) — feeds back into the evolution loop..."
          value={approveNote}
          onChange={(e) => setApproveNote(e.target.value)}
          autoFocus
        />
      </Modal>

      {/* ─── Reject reason modal with quick-reason chips (F8) ─── */}
      <Modal
        title={rejectTarget ? `Reject event #${rejectTarget.id}` : 'Reject event'}
        open={rejectTarget !== null}
        onOk={onSubmitReject}
        onCancel={() => setRejectTarget(null)}
        okText="Reject"
        okButtonProps={{ danger: true, loading: rejectSubmitting }}
        cancelButtonProps={{ disabled: rejectSubmitting }}
        destroyOnClose
      >
        <Form<RejectFormValues> form={rejectForm} layout="vertical" preserve={false}>
          {/* Quick-reason chips — click to prepend */}
          <div style={{ marginBottom: 12 }}>
            <div
              style={{
                fontSize: 11,
                color: 'var(--fg-3)',
                marginBottom: 6,
                textTransform: 'uppercase',
                letterSpacing: '0.06em',
              }}
            >
              Quick reasons
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {QUICK_REJECT_REASONS.map((chip) => (
                <Button
                  key={chip}
                  size="small"
                  type="dashed"
                  onClick={() => appendQuickRejectReason(chip)}
                >
                  {chip}
                </Button>
              ))}
            </div>
          </div>
          <Form.Item
            name="reason"
            label="Reason"
            rules={[
              {
                required: true,
                whitespace: true,
                message: 'Please enter a rejection reason.',
              },
              { min: 1, message: 'Reason must be at least 1 character.' },
            ]}
          >
            <Input.TextArea
              rows={4}
              placeholder="Why is this proposal being rejected?"
              autoFocus
            />
          </Form.Item>
        </Form>
      </Modal>

      <EventDetailDrawer
        event={drawerEvent}
        open={drawerOpen}
        onClose={closeDrawer}
        currentUserId={userId}
      />

      <BehaviorRuleAbDetailDrawer
        versionId={behaviorRuleDrawerVersionId}
        open={behaviorRuleDrawerOpen}
        onClose={closeBehaviorRuleDrawer}
      />
    </div>
  );
};

export default OptimizationEvents;
