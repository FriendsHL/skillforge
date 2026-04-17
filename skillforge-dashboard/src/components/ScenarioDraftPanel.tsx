import React, { useCallback, useMemo, useState } from 'react';
import {
  Button, Tag, Space, Typography, Empty, Spin, Popconfirm, Modal, Form,
  Input, Badge, message,
} from 'antd';
import {
  ThunderboltOutlined, CheckOutlined, EditOutlined, DeleteOutlined,
  DownOutlined, RightOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import {
  getScenarioDrafts, triggerScenarioExtraction, reviewScenarioDraft,
  type EvalScenarioDraft,
} from '../api';

const { Text } = Typography;
const { TextArea } = Input;

// ── Status styling ──────────────────────────────────────────────────────────

const STATUS_TAG: Record<EvalScenarioDraft['status'], { color: string; label: string }> = {
  draft: { color: 'orange', label: 'Draft' },
  active: { color: 'green', label: 'Active' },
  discarded: { color: 'default', label: 'Discarded' },
};

type TabKey = 'all' | 'draft' | 'active' | 'discarded';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'draft', label: 'Draft' },
  { key: 'active', label: 'Active' },
  { key: 'discarded', label: 'Discarded' },
];

// ── Edit Modal ──────────────────────────────────────────────────────────────

interface EditModalProps {
  draft: EvalScenarioDraft | null;
  open: boolean;
  onClose: () => void;
  onSubmit: (id: string, edits: { name?: string; task?: string; oracleExpected?: string }) => void;
  loading: boolean;
}

const EditModal: React.FC<EditModalProps> = ({ draft, open, onClose, onSubmit, loading }) => {
  const [form] = Form.useForm();

  const handleOpen = () => {
    if (draft) {
      form.setFieldsValue({
        name: draft.name,
        task: draft.task,
        oracleExpected: draft.oracleExpected ?? '',
      });
    }
  };

  return (
    <Modal
      title="Edit & Approve Scenario"
      open={open}
      onCancel={onClose}
      afterOpenChange={(visible) => { if (visible) handleOpen(); }}
      onOk={() => {
        form.validateFields()
          .then((values) => { if (draft) onSubmit(draft.id, values); })
          .catch(() => { /* validation errors shown inline */ });
      }}
      okText="Approve"
      confirmLoading={loading}
      destroyOnClose
      width={560}
    >
      <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
        <Form.Item name="name" label="Name" rules={[{ required: true, message: 'Name is required' }]}>
          <Input />
        </Form.Item>
        <Form.Item name="task" label="Task" rules={[{ required: true, message: 'Task is required' }]}>
          <TextArea rows={4} />
        </Form.Item>
        <Form.Item name="oracleExpected" label="Expected Output / Criteria">
          <TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
};

// ── Rationale Expander ──────────────────────────────────────────────────────

const RationaleText: React.FC<{ text: string }> = React.memo(({ text }) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <div style={{ marginTop: 4 }}>
      <Button
        type="link"
        size="small"
        style={{ padding: 0, fontSize: 11, color: 'var(--text-muted)' }}
        icon={expanded ? <DownOutlined /> : <RightOutlined />}
        onClick={() => setExpanded((v) => !v)}
      >
        Rationale
      </Button>
      {expanded && (
        <Text
          italic
          style={{
            display: 'block',
            fontSize: 11,
            color: 'var(--text-muted)',
            marginTop: 4,
            lineHeight: 1.5,
          }}
        >
          {text}
        </Text>
      )}
    </div>
  );
});
RationaleText.displayName = 'RationaleText';

// ── Scenario Card ───────────────────────────────────────────────────────────

interface ScenarioCardProps {
  draft: EvalScenarioDraft;
  onApprove: (id: string) => void;
  onDiscard: (id: string) => void;
  onEdit: (draft: EvalScenarioDraft) => void;
  approving: boolean;
  discarding: boolean;
}

const ScenarioCard: React.FC<ScenarioCardProps> = React.memo(
  ({ draft, onApprove, onDiscard, onEdit, approving, discarding }) => {
    const isDiscarded = draft.status === 'discarded';
    const isDraft = draft.status === 'draft';
    const statusCfg = STATUS_TAG[draft.status];

    return (
      <div
        style={{
          display: 'flex',
          gap: 12,
          padding: '12px 16px',
          borderRadius: 'var(--radius-sm)',
          border: '1px solid var(--border-subtle)',
          background: isDiscarded ? 'var(--bg-hover)' : 'var(--bg-surface)',
          opacity: isDiscarded ? 0.55 : 1,
        }}
      >
        {/* Left: status + meta */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <Space size={8} align="center" wrap>
            <Tag color={statusCfg.color} style={{ margin: 0, fontSize: 11 }}>
              {statusCfg.label}
            </Tag>
            <Text strong style={{ fontSize: 13 }}>{draft.name}</Text>
            {draft.status === 'active' && (
              <Tag color="green" style={{ margin: 0, fontSize: 10, border: 'none' }}>
                In Eval Set
              </Tag>
            )}
          </Space>

          <Text
            type="secondary"
            style={{ display: 'block', fontSize: 11, marginTop: 2 }}
          >
            {draft.category} / {draft.split}
          </Text>

          {/* Task text — 3-line clamp */}
          <div
            style={{
              marginTop: 8,
              fontSize: 12,
              lineHeight: 1.55,
              display: '-webkit-box',
              WebkitLineClamp: 3,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
              wordBreak: 'break-word',
            }}
          >
            {draft.task}
          </div>

          {/* Oracle */}
          <Text
            type="secondary"
            style={{ display: 'block', fontSize: 11, marginTop: 6 }}
          >
            Oracle: {draft.oracleType}
            {draft.oracleExpected && ` — ${draft.oracleExpected}`}
          </Text>

          {/* Rationale */}
          {draft.extractionRationale && (
            <RationaleText text={draft.extractionRationale} />
          )}
        </div>

        {/* Right: actions — draft only */}
        {isDraft && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0 }}>
            <Popconfirm
              title="Approve this scenario?"
              description="It will become part of the active eval set."
              onConfirm={() => onApprove(draft.id)}
            >
              <Button
                size="small"
                type="primary"
                icon={<CheckOutlined />}
                loading={approving}
              >
                Approve
              </Button>
            </Popconfirm>
            <Button
              size="small"
              icon={<EditOutlined />}
              onClick={() => onEdit(draft)}
            >
              Edit
            </Button>
            <Popconfirm
              title="Discard this scenario?"
              onConfirm={() => onDiscard(draft.id)}
            >
              <Button
                size="small"
                danger
                icon={<DeleteOutlined />}
                loading={discarding}
              >
                Discard
              </Button>
            </Popconfirm>
          </div>
        )}
      </div>
    );
  },
);
ScenarioCard.displayName = 'ScenarioCard';

// ── Main Panel ──────────────────────────────────────────────────────────────

interface ScenarioDraftPanelProps {
  agentId: number | string;
}

const ScenarioDraftPanel: React.FC<ScenarioDraftPanelProps> = ({ agentId }) => {
  const queryClient = useQueryClient();
  // Normalize to string so queryKey is always stable
  const normalizedId = String(agentId);
  const queryKey = useMemo(() => ['scenario-drafts', normalizedId], [normalizedId]);

  const [activeTab, setActiveTab] = useState<TabKey>('all');
  const [editingDraft, setEditingDraft] = useState<EvalScenarioDraft | null>(null);

  const { data: drafts = [], isLoading, isError, refetch } = useQuery({
    queryKey,
    queryFn: () => getScenarioDrafts(normalizedId),
  });

  const invalidate = useCallback(
    () => queryClient.invalidateQueries({ queryKey }),
    [queryClient, queryKey],
  );

  const extractMutation = useMutation({
    mutationFn: () => triggerScenarioExtraction(normalizedId),
    onSuccess: (data: { status?: string; draftCount?: number }) => {
      if (data?.status === 'already_has_drafts') {
        message.info(`${data.draftCount} draft(s) already pending. Review or discard them first.`);
      } else {
        message.success('Extracting scenarios in background, refresh in a moment');
        setTimeout(invalidate, 1500);
      }
    },
    onError: () => message.error('Failed to trigger extraction'),
  });

  // Use mutation.variables to track which item is being acted on — avoids shared actionId race
  const approveMutation = useMutation({
    mutationFn: (id: string) => reviewScenarioDraft(id, 'approve'),
    onSuccess: () => { message.success('Scenario approved'); invalidate(); },
    onError: () => message.error('Approve failed'),
  });

  const discardMutation = useMutation({
    mutationFn: (id: string) => reviewScenarioDraft(id, 'discard'),
    onSuccess: () => { message.success('Scenario discarded'); invalidate(); },
    onError: () => message.error('Discard failed'),
  });

  const editApproveMutation = useMutation({
    mutationFn: ({ id, edits }: { id: string; edits: { name?: string; task?: string; oracleExpected?: string } }) =>
      reviewScenarioDraft(id, 'approve', edits),
    onSuccess: () => {
      message.success('Scenario approved with edits');
      setEditingDraft(null);
      invalidate();
    },
    onError: () => message.error('Save failed'),
  });

  // Stable callbacks for ScenarioCard — keeps React.memo effective
  const handleApprove = useCallback(
    (id: string) => approveMutation.mutate(id),
    [approveMutation],
  );
  const handleDiscard = useCallback(
    (id: string) => discardMutation.mutate(id),
    [discardMutation],
  );

  // Counts per status
  const counts: Record<TabKey, number> = useMemo(() => ({
    all: drafts.length,
    draft: drafts.filter((d) => d.status === 'draft').length,
    active: drafts.filter((d) => d.status === 'active').length,
    discarded: drafts.filter((d) => d.status === 'discarded').length,
  }), [drafts]);

  const filtered = useMemo(
    () => activeTab === 'all' ? drafts : drafts.filter((d) => d.status === activeTab),
    [drafts, activeTab],
  );

  if (isError) {
    return (
      <Empty description="Failed to load scenario drafts">
        <Button icon={<ReloadOutlined />} onClick={() => refetch()}>Retry</Button>
      </Empty>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text strong style={{ fontSize: 15 }}>Scenario Drafts</Text>
        <Button
          icon={<ThunderboltOutlined />}
          onClick={() => extractMutation.mutate()}
          loading={extractMutation.isPending}
        >
          Generate from Sessions
        </Button>
      </div>

      {/* Tab filters */}
      <Space size={8}>
        {TABS.map((tab) => (
          <Badge
            key={tab.key}
            count={counts[tab.key]}
            size="small"
            showZero
            style={{ backgroundColor: activeTab === tab.key ? 'var(--accent-primary)' : 'var(--border-medium)' }}
          >
            <Button
              size="small"
              type={activeTab === tab.key ? 'primary' : 'default'}
              onClick={() => setActiveTab(tab.key)}
              style={{ minWidth: 60 }}
            >
              {tab.label}
            </Button>
          </Badge>
        ))}
      </Space>

      {/* List */}
      {isLoading ? (
        <Spin style={{ display: 'block', margin: '40px auto' }} />
      ) : filtered.length === 0 ? (
        <Empty
          description={
            activeTab === 'all'
              ? "No scenarios yet. Click 'Generate from Sessions' to start."
              : `No ${activeTab} scenarios.`
          }
          style={{ margin: '40px 0' }}
        />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {filtered.map((d) => (
            <ScenarioCard
              key={d.id}
              draft={d}
              onApprove={handleApprove}
              onDiscard={handleDiscard}
              onEdit={setEditingDraft}
              approving={approveMutation.isPending && approveMutation.variables === d.id}
              discarding={discardMutation.isPending && discardMutation.variables === d.id}
            />
          ))}
        </div>
      )}

      {/* Edit modal */}
      <EditModal
        draft={editingDraft}
        open={!!editingDraft}
        onClose={() => setEditingDraft(null)}
        onSubmit={(id, edits) => editApproveMutation.mutate({ id, edits })}
        loading={editApproveMutation.isPending}
      />
    </div>
  );
};

export default React.memo(ScenarioDraftPanel);
