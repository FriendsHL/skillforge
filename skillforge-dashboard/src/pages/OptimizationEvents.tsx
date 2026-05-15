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
  Divider,
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
import { stageColor, surfaceColor, riskColor } from '../components/attribution/stageStyle';

dayjs.extend(relativeTime);

const { Title, Paragraph, Text } = Typography;

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
const OptimizationEvents: React.FC = () => {
  const { userId } = useAuth();
  const queryClient = useQueryClient();

  // Timeline filter state — page=0 is the only default the user-facing filter
  // toggles trigger a re-query against. agentId uses InputNumber (BE accepts
  // raw long; no enum constraint).
  const [stageFilter, setStageFilter] = useState<AttributionStage | undefined>(undefined);
  const [agentIdFilter, setAgentIdFilter] = useState<number | undefined>(undefined);
  const [surfaceFilter, setSurfaceFilter] = useState<AttributionSurface | undefined>(undefined);
  const [page, setPage] = useState<number>(0);

  const [drawerEvent, setDrawerEvent] = useState<OptimizationEventDto | null>(null);
  const [drawerOpen, setDrawerOpen] = useState<boolean>(false);

  const [rejectTarget, setRejectTarget] = useState<OptimizationEventDto | null>(null);
  const [rejectSubmitting, setRejectSubmitting] = useState<boolean>(false);
  const [rejectForm] = Form.useForm<RejectFormValues>();

  const [actionLoadingId, setActionLoadingId] = useState<number | null>(null);

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
        const msg = JSON.parse(ev.data) as Partial<AttributionEventUpdatedMessage> & {
          type?: string;
        };
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
      Modal.confirm({
        title: `Approve event #${event.id}?`,
        content: `Approving will dispatch candidate generation for surface=${event.surfaceType}.`,
        okText: 'Approve',
        okType: 'primary',
        cancelText: 'Cancel',
        onOk: async () => {
          setActionLoadingId(event.id);
          try {
            await approveEvent(event.id, { approverUserId: userId });
            message.success(`Event #${event.id} approved.`);
            invalidateAll();
          } catch (err: unknown) {
            const e = err as { response?: { data?: { error?: string } }; message?: string };
            const reason = e.response?.data?.error || e.message || 'Approve failed.';
            message.error(reason);
          } finally {
            setActionLoadingId(null);
          }
        },
      });
    },
    [userId, invalidateAll],
  );

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
        render: (s: string) => <Tag color={stageColor(s)}>{s}</Tag>,
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
        width: 120,
        render: (_: unknown, record) =>
          record.stage === 'candidate_failed' ? (
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
          ) : null,
      },
    ],
    [actionLoadingId, onRetry],
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

  return (
    <div
      style={{
        padding: 'var(--sp-6, 24px) var(--sp-8, 32px)',
        maxWidth: 1600,
        margin: '0 auto',
      }}
    >
      <div style={{ marginBottom: 24 }}>
        <Title level={3} style={{ marginBottom: 4 }}>
          Optimization Events
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          Operator queue + lifecycle timeline for attribution-curator proposals.
          Approve to trigger candidate generation; reject to close the proposal.
        </Paragraph>
      </div>

      {/* ─── Pending Approvals ─── */}
      <div style={{ marginBottom: 32 }}>
        <Space align="baseline" style={{ marginBottom: 12 }}>
          <Title level={4} style={{ margin: 0 }}>
            Pending approvals
          </Title>
          <Text type="secondary">
            {pendingItems.length} proposal{pendingItems.length === 1 ? '' : 's'} awaiting decision
          </Text>
        </Space>

        {pendingErr && (
          <Alert
            type="error"
            showIcon
            message={pendingErr}
            style={{ marginBottom: 12 }}
          />
        )}

        <Table<OptimizationEventDto>
          rowKey="id"
          columns={pendingColumns}
          dataSource={pendingItems}
          loading={pendingLoading}
          size="small"
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

      <Divider />

      {/* ─── Timeline ─── */}
      <div>
        <Space align="baseline" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
          <Title level={4} style={{ margin: 0 }}>
            Recent events
          </Title>
          <Text type="secondary">
            {timelineTotal} total
          </Text>
        </Space>

        {/* Filter bar — three controls + reset. Each is independent. */}
        <Space wrap style={{ marginBottom: 12 }}>
          <Select<AttributionStage>
            allowClear
            placeholder="Stage"
            style={{ minWidth: 200 }}
            value={stageFilter}
            options={STAGE_OPTIONS}
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

      {/* ─── Reject reason modal ─── */}
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
    </div>
  );
};

export default OptimizationEvents;
