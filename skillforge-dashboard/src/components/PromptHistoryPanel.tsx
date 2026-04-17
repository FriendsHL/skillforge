import React, { useState } from 'react';
import {
  Table, Tag, Button, Modal, Space, Typography, Empty, Spin, Popconfirm,
  message,
} from 'antd';
import {
  EyeOutlined, RollbackOutlined, HistoryOutlined, CrownOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import {
  getPromptVersions, getPromptVersionDetail, rollbackPromptVersion,
  type PromptVersion, type AbScenarioResult,
} from '../api';

const { Text } = Typography;

// ── Status styling ──────────────────────────────────────────────────────────

const STATUS_CONFIG: Record<string, { color: string; label: string }> = {
  active: { color: 'var(--color-success)', label: 'Active' },
  candidate: { color: 'var(--accent-primary)', label: 'Candidate' },
  deprecated: { color: 'var(--text-muted)', label: 'Deprecated' },
  failed: { color: 'var(--color-error)', label: 'Failed' },
};

const SOURCE_LABEL: Record<string, string> = {
  manual: 'Manual',
  auto_improve: 'Auto',
};

// ── AB Result comparison ────────────────────────────────────────────────────

function deltaIndicator(
  baseline: { status: string },
  candidate: { status: string },
): React.ReactNode {
  if (baseline.status === 'FAIL' && candidate.status === 'PASS') {
    return <span style={{ color: 'var(--color-success)', fontWeight: 600 }}>&#x25B2;</span>;
  }
  if (baseline.status === 'PASS' && candidate.status === 'FAIL') {
    return <span style={{ color: 'var(--color-error)', fontWeight: 600 }}>&#x25BC;</span>;
  }
  if (baseline.status === 'PASS' && candidate.status === 'PASS') {
    return <span style={{ color: 'var(--text-muted)' }}>&#x2500;</span>;
  }
  // FAIL→FAIL
  return <span style={{ color: 'var(--color-warning)' }}>&#x2500;</span>;
}

// ── AB Results Table (inside modal) ─────────────────────────────────────────

const AbResultsTable: React.FC<{ results: AbScenarioResult[] }> = React.memo(({ results }) => {
  if (!results || results.length === 0) {
    return <Text type="secondary" style={{ fontSize: 12 }}>No A/B results available</Text>;
  }

  const columns = [
    {
      title: 'Scenario',
      dataIndex: 'scenarioName',
      ellipsis: true,
      render: (v: string, r: AbScenarioResult) => (
        <Text style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}>{v || r.scenarioId}</Text>
      ),
    },
    {
      title: 'Baseline',
      width: 100,
      render: (_: unknown, r: AbScenarioResult) => (
        <Tag
          color={r.baseline.status === 'PASS' ? 'green' : r.baseline.status === 'FAIL' ? 'red' : 'orange'}
          style={{ fontSize: 11, margin: 0 }}
        >
          {r.baseline.status}
        </Tag>
      ),
    },
    {
      title: 'Candidate',
      width: 100,
      render: (_: unknown, r: AbScenarioResult) => (
        <Tag
          color={r.candidate.status === 'PASS' ? 'green' : r.candidate.status === 'FAIL' ? 'red' : 'orange'}
          style={{ fontSize: 11, margin: 0 }}
        >
          {r.candidate.status}
        </Tag>
      ),
    },
    {
      title: 'Change',
      width: 70,
      align: 'center' as const,
      render: (_: unknown, r: AbScenarioResult) => deltaIndicator(r.baseline, r.candidate),
    },
  ];

  return (
    <Table
      size="small"
      rowKey="scenarioId"
      dataSource={results}
      columns={columns}
      pagination={false}
      style={{ marginTop: 12 }}
    />
  );
});

// ── Version Detail Modal ────────────────────────────────────────────────────

interface DetailModalProps {
  agentId: string;
  versionId: string | null;
  open: boolean;
  onClose: () => void;
}

const VersionDetailModal: React.FC<DetailModalProps> = ({ agentId, versionId, open, onClose }) => {
  const { data, isLoading } = useQuery({
    queryKey: ['prompt-version-detail', agentId, versionId],
    queryFn: () => versionId ? getPromptVersionDetail(agentId, versionId).then(r => r.data) : null,
    enabled: !!versionId && open,
  });

  const version = data as (PromptVersion & { scenarioResults?: AbScenarioResult[] }) | null;

  return (
    <Modal
      title={
        <Space>
          <HistoryOutlined />
          <span>Prompt Version {version ? `v${version.versionNumber}` : ''}</span>
          {version && (
            <Tag
              style={{
                color: STATUS_CONFIG[version.status]?.color,
                borderColor: STATUS_CONFIG[version.status]?.color,
                background: 'transparent',
                fontSize: 11,
              }}
            >
              {STATUS_CONFIG[version.status]?.label ?? version.status}
            </Tag>
          )}
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width={680}
      destroyOnClose
    >
      {isLoading ? (
        <Spin style={{ display: 'block', margin: '40px auto' }} />
      ) : !version ? (
        <Empty description="Failed to load version" />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Metadata */}
          <div style={{
            display: 'flex', gap: 16, flexWrap: 'wrap',
            padding: '12px 16px',
            background: 'var(--bg-surface)',
            borderRadius: 'var(--radius-sm, 6px)',
            border: '1px solid var(--border-subtle)',
          }}>
            <div>
              <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Source</Text>
              <Text style={{ fontSize: 12 }}>{SOURCE_LABEL[version.source] ?? version.source}</Text>
            </div>
            {version.deltaPassRate != null && (
              <div>
                <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Delta</Text>
                <Text
                  strong
                  style={{
                    fontSize: 12,
                    color: version.deltaPassRate > 0 ? 'var(--color-success)' : version.deltaPassRate < 0 ? 'var(--color-error)' : undefined,
                  }}
                >
                  {version.deltaPassRate >= 0 ? '+' : ''}{version.deltaPassRate.toFixed(1)}pp
                </Text>
              </div>
            )}
            <div>
              <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Created</Text>
              <Text style={{ fontSize: 12 }}>
                {new Date(version.createdAt).toLocaleString('zh-CN')}
              </Text>
            </div>
            {version.promotedAt && (
              <div>
                <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Promoted</Text>
                <Text style={{ fontSize: 12 }}>
                  {new Date(version.promotedAt).toLocaleString('zh-CN')}
                </Text>
              </div>
            )}
          </div>

          {/* Rationale */}
          {version.improvementRationale && (
            <div>
              <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
                Improvement Rationale
              </Text>
              <pre style={{
                fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                background: 'var(--bg-surface)', padding: '10px 12px',
                borderRadius: 'var(--radius-sm, 6px)',
                borderLeft: '3px solid var(--accent-primary)',
                fontFamily: 'var(--font-mono)',
                maxHeight: 120, overflowY: 'auto',
              }}>
                {version.improvementRationale}
              </pre>
            </div>
          )}

          {/* Prompt content — sanitized plain text, no innerHTML */}
          {version.content && (
            <div>
              <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
                Prompt Content
              </Text>
              <pre style={{
                fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                background: 'var(--bg-code)', color: 'var(--text-on-accent)',
                padding: '12px 14px',
                borderRadius: 'var(--radius-sm, 6px)',
                fontFamily: 'var(--font-mono)',
                maxHeight: 320, overflowY: 'auto',
                lineHeight: 1.6,
              }}>
                {version.content}
              </pre>
            </div>
          )}

          {/* AB comparison results */}
          {version.scenarioResults && version.scenarioResults.length > 0 && (
            <div>
              <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
                A/B Comparison Results
              </Text>
              <AbResultsTable results={version.scenarioResults} />
            </div>
          )}
        </div>
      )}
    </Modal>
  );
};

// ── Main Panel ──────────────────────────────────────────────────────────────

interface PromptHistoryPanelProps {
  agentId: string;
}

const PromptHistoryPanel: React.FC<PromptHistoryPanelProps> = ({ agentId }) => {
  const queryClient = useQueryClient();
  const [detailVersionId, setDetailVersionId] = useState<string | null>(null);

  const { data: versions = [], isLoading, isError } = useQuery({
    queryKey: ['prompt-versions', agentId],
    queryFn: () => getPromptVersions(agentId).then(r => {
      const d = r.data;
      return Array.isArray(d) ? d : [];
    }),
    refetchInterval: 15000,
  });

  const rollbackMutation = useMutation({
    mutationFn: (versionId: string) => rollbackPromptVersion(agentId, versionId),
    onSuccess: () => {
      message.success('Rolled back successfully');
      queryClient.invalidateQueries({ queryKey: ['prompt-versions', agentId] });
    },
    onError: () => message.error('Rollback failed'),
  });

  // Sort: active first, then by versionNumber descending
  const sorted = [...versions].sort((a, b) => {
    if (a.status === 'active' && b.status !== 'active') return -1;
    if (b.status === 'active' && a.status !== 'active') return 1;
    return b.versionNumber - a.versionNumber;
  });

  const columns = [
    {
      title: 'Version',
      width: 100,
      render: (_: unknown, r: PromptVersion) => (
        <Space size={4}>
          {r.status === 'active' && <CrownOutlined style={{ color: 'var(--color-success)', fontSize: 12 }} />}
          <Text strong style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}>v{r.versionNumber}</Text>
        </Space>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      width: 110,
      render: (v: string) => {
        const cfg = STATUS_CONFIG[v];
        return (
          <Tag
            style={{
              color: cfg?.color,
              borderColor: cfg?.color,
              background: v === 'active' ? 'color-mix(in oklab, var(--color-success) 10%, transparent)' : 'transparent',
              fontSize: 11,
              margin: 0,
            }}
          >
            {v === 'active' ? 'Active' : cfg?.label ?? v}
          </Tag>
        );
      },
    },
    {
      title: 'Source',
      dataIndex: 'source',
      width: 80,
      render: (v: string) => (
        <Text style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
          {SOURCE_LABEL[v] ?? v}
        </Text>
      ),
    },
    {
      title: 'Delta',
      dataIndex: 'deltaPassRate',
      width: 80,
      render: (v: number | null) => {
        if (v == null) return <Text type="secondary" style={{ fontSize: 11 }}>-</Text>;
        const color = v > 0 ? 'var(--color-success)' : v < 0 ? 'var(--color-error)' : undefined;
        return (
          <Text strong style={{ fontSize: 12, color }}>
            {v >= 0 ? '+' : ''}{v.toFixed(1)}pp
          </Text>
        );
      },
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      width: 140,
      render: (v: string) => (
        <Text style={{ fontSize: 11 }}>
          {v ? new Date(v).toLocaleString('zh-CN', {
            month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit',
          }) : '-'}
        </Text>
      ),
    },
    {
      title: 'Actions',
      width: 140,
      render: (_: unknown, r: PromptVersion) => (
        <Space size={4}>
          <Button
            size="small"
            type="link"
            icon={<EyeOutlined />}
            onClick={() => setDetailVersionId(r.id)}
            style={{ fontSize: 12, padding: '0 4px' }}
          >
            View
          </Button>
          {r.status === 'deprecated' && (
            <Popconfirm
              title="Rollback to this version?"
              description="The current active version will be deprecated."
              onConfirm={() => rollbackMutation.mutate(r.id)}
            >
              <Button
                size="small"
                type="link"
                icon={<RollbackOutlined />}
                loading={rollbackMutation.isPending}
                style={{ fontSize: 12, padding: '0 4px', color: 'var(--color-warning)' }}
              >
                Rollback
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  if (isError) {
    return <Empty description="Failed to load prompt versions" />;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Table
        size="small"
        rowKey="id"
        dataSource={sorted}
        columns={columns}
        loading={isLoading}
        pagination={false}
        locale={{
          emptyText: <Empty description="No prompt versions yet" />,
        }}
        style={{
          borderRadius: 'var(--radius-sm, 6px)',
        }}
      />

      <VersionDetailModal
        agentId={agentId}
        versionId={detailVersionId}
        open={!!detailVersionId}
        onClose={() => setDetailVersionId(null)}
      />
    </div>
  );
};

export default React.memo(PromptHistoryPanel);
