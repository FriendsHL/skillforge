import React, { useState, useMemo } from 'react';
import { Typography, Form, Select, InputNumber, Button, Space, Alert } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  listPatterns,
  type ListPatternsParams,
  type PatternListItem,
  type SessionOutcome,
  type SuspectSurface,
} from '../api/insights';
import PatternList from '../components/insights/PatternList';
import PatternDetailDrawer from '../components/insights/PatternDetailDrawer';
import OptimizationEventsPage from './OptimizationEvents';
import TabBar from '../components/TabBar';

const { Title, Paragraph, Text } = Typography;

interface FilterFormValues {
  outcome?: SessionOutcome;
  surface?: SuspectSurface;
  agent?: number;
  limit?: number;
}

const OUTCOME_OPTIONS: { value: SessionOutcome; label: string }[] = [
  { value: 'success', label: 'success' },
  { value: 'partial_success', label: 'partial_success' },
  { value: 'failure', label: 'failure' },
  { value: 'cancelled', label: 'cancelled' },
];

const SURFACE_OPTIONS: { value: SuspectSurface; label: string }[] = [
  { value: 'skill', label: 'skill' },
  { value: 'prompt', label: 'prompt' },
  { value: 'behavior_rule', label: 'behavior_rule' },
  { value: 'other', label: 'other' },
  { value: 'unclear', label: 'unclear' },
];

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

const INSIGHTS_TABS = [
  { key: 'patterns', label: 'Patterns' },
  { key: 'optimization', label: 'Optimization' },
];

/**
 * PROD-LABEL-CLUSTER Phase 1.5 — `/insights/patterns` page.
 *
 * Composes filter Form + PatternList + PatternDetailDrawer. All state lives
 * here (filters, selected pattern, drawer open); the children are dumb
 * presentational components.
 */
const Insights: React.FC = () => {
  const [activeTab, setActiveTab] = useState('patterns');
  const [filters, setFilters] = useState<ListPatternsParams>({ limit: DEFAULT_LIMIT });
  const [selectedPattern, setSelectedPattern] = useState<PatternListItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [form] = Form.useForm<FilterFormValues>();

  // queryKey is derived from filters so any filter change refetches.
  // 30s staleTime — the cron runs hourly, so this trades a tiny bit of
  // freshness for snappier nav back to the page.
  const { data: patterns = [], isLoading, isError, error } = useQuery({
    queryKey: ['insights-patterns', filters],
    queryFn: () => listPatterns(filters).then((r) => r.data ?? []),
    staleTime: 30_000,
  });

  const errMsg = useMemo(() => {
    if (!isError) return null;
    if (error instanceof Error) return error.message;
    return 'Failed to load patterns.';
  }, [isError, error]);

  const onApply = (values: FilterFormValues) => {
    const next: ListPatternsParams = {};
    if (values.outcome) next.outcome = values.outcome;
    if (values.surface) next.surface = values.surface;
    if (values.agent !== undefined && values.agent !== null) {
      next.agent = values.agent;
    }
    next.limit = values.limit ?? DEFAULT_LIMIT;
    setFilters(next);
  };

  const onReset = () => {
    form.resetFields();
    setFilters({ limit: DEFAULT_LIMIT });
  };

  const onRowClick = (pattern: PatternListItem) => {
    setSelectedPattern(pattern);
    setDrawerOpen(true);
  };

  const onDrawerClose = () => {
    setDrawerOpen(false);
    // Keep selectedPattern set briefly so the drawer fades out cleanly;
    // destroyOnClose unmounts the inner Table anyway.
  };

  if (activeTab === 'optimization') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={setActiveTab} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <OptimizationEventsPage />
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
      <TabBar tabs={INSIGHTS_TABS} activeTab={activeTab} onSwitch={setActiveTab} />
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: 'var(--sp-6, 24px) var(--sp-8, 32px)', maxWidth: 1600, margin: '0 auto' }}>
        <div style={{ marginBottom: 24 }}>
          <Title level={3} style={{ marginBottom: 4 }}>
            Insights — Failure Patterns
          </Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            Hourly session-annotator agent labels production sessions and groups them by
            (outcome × suspect_surface × top_failing_tool × agent). Clusters with ≥ 3 members surface here.
          </Paragraph>
        </div>

        <Form<FilterFormValues>
        form={form}
        layout="inline"
        initialValues={{ limit: DEFAULT_LIMIT }}
        onFinish={onApply}
        style={{ marginBottom: 20, rowGap: 8 }}
      >
        <Form.Item label="Outcome" name="outcome">
          <Select
            allowClear
            placeholder="any"
            options={OUTCOME_OPTIONS}
            style={{ minWidth: 160 }}
          />
        </Form.Item>
        <Form.Item label="Surface" name="surface">
          <Select
            allowClear
            placeholder="any"
            options={SURFACE_OPTIONS}
            style={{ minWidth: 160 }}
          />
        </Form.Item>
        <Form.Item label="Agent id" name="agent">
          <InputNumber min={1} placeholder="any" style={{ width: 110 }} />
        </Form.Item>
        <Form.Item label="Limit" name="limit">
          <InputNumber min={1} max={MAX_LIMIT} style={{ width: 90 }} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              Apply
            </Button>
            <Button onClick={onReset}>Reset</Button>
          </Space>
        </Form.Item>
      </Form>

      {errMsg && (
        <Alert
          type="error"
          showIcon
          message="Failed to load patterns"
          description={<Text style={{ fontSize: 12 }}>{errMsg}</Text>}
          style={{ marginBottom: 16 }}
        />
      )}

      <PatternList patterns={patterns} loading={isLoading} onRowClick={onRowClick} />

      <PatternDetailDrawer
        pattern={selectedPattern}
        open={drawerOpen}
        onClose={onDrawerClose}
      />
      </div>
    </div>
  );
};

export default Insights;
