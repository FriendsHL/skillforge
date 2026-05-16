import React, { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Pagination,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTrials,
  listTrials,
  type CandidateSurfaceType,
  type DynamicSimErrorResponse,
  type SimulatorTrialResponse,
} from '../../api/dynamicSim';
import {
  getEvalDatasetScenarios,
  type EvalDatasetScenario,
} from '../../api';

const { Text } = Typography;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.4 — operator panel for kicking off
 * dynamic-sim trials and inspecting the resulting transcripts.
 *
 * <p>Composes:
 * <ol>
 *   <li>Top — controls form (scenario picker filtered by agent + surface
 *       picker [behavior_rule disabled] + candidate version id input + persona
 *       multi-select + max-turns + Run button).</li>
 *   <li>Middle — paginated trial history table (filterable by scenario or
 *       candidate-version+surface, sorted createdAt DESC).</li>
 *   <li>Per-row — "View transcript →" jumps to {@code /sessions/{sessionId}}
 *       which reuses the existing {@code SessionDetail} + {@code ChatWindow}
 *       viewer (no new viewer code).</li>
 * </ol>
 *
 * <p><strong>behavior_rule disable invariant</strong>: the BE rejects
 * {@code behavior_rule} surface trials at 4 layers (orchestrator entry /
 * RunSimulatorTrial tool / DynamicSimController POST 400 / tech-design
 * limitation). FE is the 4th layer — the option is disabled with a tooltip
 * citing V5.1 backlog. If somehow bypassed, the BE 400 surfaces as an
 * Ant Design {@code message.error} with the BE-supplied error string +
 * supportedSurfaces list, never silently succeeding.
 *
 * <p><strong>Candidate version id</strong>: rendered as a freeform text input
 * because the BE column is {@code VARCHAR(64)} with surface-dependent
 * semantics (skill version id / prompt version id / behavior_rule version
 * id). A typed selector per-surface is V5.5 follow-up — first cut keeps the
 * surgical change minimal. Leaving the field blank runs against the agent's
 * current baseline (per BE controller contract).
 */

export interface DynamicSimPanelProps {
  /** Numeric agent id from the parent agent picker. */
  agentNumericId: number;
  /** 5 fixed personas from {@code application.yml} — FE-hardcoded mirror. */
  personas: string[];
}

const PAGE_SIZE_DEFAULT = 20;
const PAGE_SIZE_MAX = 100;

/**
 * Tag color hint per termination reason — semantic colouring rather than
 * decorative (per design.md Required Quality #5).
 */
function terminationColor(reason: string | null): string {
  switch (reason) {
    case 'task_completed':
      return 'green';
    case 'failure_signal':
      return 'red';
    case 'max_turns':
      return 'gold';
    case 'error':
      return 'volcano';
    case null:
    case undefined:
      return 'default';
    default:
      return 'default';
  }
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

interface DynamicSimFormValues {
  scenarioId?: string;
  candidateSurfaceType?: CandidateSurfaceType;
  candidateAgentVersionId?: string;
  selectedPersonas: string[];
  maxTurns?: number;
}

const DynamicSimPanel: React.FC<DynamicSimPanelProps> = ({
  agentNumericId,
  personas,
}) => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<DynamicSimFormValues>();

  // Filter state for the trial-history table — distinct from the form values
  // so editing the form doesn't immediately re-query the table; the operator
  // explicitly chooses when to apply filters via the "Filter by scenario" /
  // candidate version selectors.
  const [filterScenarioId, setFilterScenarioId] = useState<string | undefined>(
    undefined,
  );
  const [filterVersionId, setFilterVersionId] = useState<string | undefined>(
    undefined,
  );
  const [filterSurface, setFilterSurface] = useState<
    CandidateSurfaceType | undefined
  >(undefined);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(PAGE_SIZE_DEFAULT);

  // Per-agent scenario list — feeds the scenario picker. EvalScenarioEntity.id
  // is a string (UUID-ish), agentId stringified to query.
  const scenariosQuery = useQuery({
    queryKey: ['eval-scenarios', agentNumericId],
    queryFn: () =>
      getEvalDatasetScenarios(String(agentNumericId)).then((r) => r.data ?? []),
    staleTime: 60_000,
  });

  const scenarioOptions = useMemo(
    () =>
      (scenariosQuery.data ?? []).map((s: EvalDatasetScenario) => ({
        value: s.id,
        label: `${s.name} (${s.id.slice(0, 8)}…)`,
      })),
    [scenariosQuery.data],
  );

  // Trial history list — refetched whenever filter / page changes. 30s stale
  // because trials persist on demand; navigating between agents shouldn't
  // burn an extra refetch.
  const trialsQuery = useQuery({
    queryKey: [
      'dynamic-sim-trials',
      filterScenarioId ?? null,
      filterVersionId ?? null,
      filterSurface ?? null,
      page,
      pageSize,
    ],
    queryFn: () =>
      listTrials({
        scenarioId: filterScenarioId,
        candidateAgentVersionId: filterVersionId,
        candidateSurfaceType: filterSurface,
        page,
        size: pageSize,
      }).then((r) => r.data),
    staleTime: 30_000,
  });

  const launchMutation = useMutation({
    mutationFn: createTrials,
    onSuccess: (resp) => {
      const data = resp.data;
      message.success(
        `Trial launched: ${data.personaCount} persona(s) running for scenario ${data.scenarioId.slice(0, 8)}…`,
      );
      // Auto-apply the just-submitted scenario as the table filter so the
      // operator sees the trials show up as they finish (background polling
      // via React Query refetchInterval is overkill here — manual Refresh
      // button below is enough for V5).
      const sid = form.getFieldValue('scenarioId') as string | undefined;
      if (sid) {
        setFilterScenarioId(sid);
        setPage(0);
      }
      queryClient.invalidateQueries({ queryKey: ['dynamic-sim-trials'] });
    },
    onError: (err: unknown) => {
      // Best-effort extraction of the BE error envelope. Axios stuffs the
      // server response into `err.response.data`; fall back to err.message
      // if the shape isn't what we expect (network blip, CORS, etc.).
      const maybeAxios = err as {
        response?: { data?: DynamicSimErrorResponse };
        message?: string;
      };
      const beData = maybeAxios.response?.data;
      if (beData && typeof beData.error === 'string') {
        const supported = beData.supportedSurfaces?.length
          ? ` (supported surfaces: ${beData.supportedSurfaces.join(', ')})`
          : '';
        message.error(`Trial launch rejected: ${beData.error}${supported}`);
      } else {
        message.error(`Trial launch failed: ${maybeAxios.message ?? 'unknown error'}`);
      }
    },
  });

  const onLaunch = (values: DynamicSimFormValues) => {
    if (!values.scenarioId) {
      message.warning('Pick a scenario first.');
      return;
    }
    if (!values.selectedPersonas || values.selectedPersonas.length === 0) {
      message.warning('Pick at least one persona.');
      return;
    }
    // Defensive client-side mirror of the BE 4-layer behavior_rule reject —
    // disabled in the Select but if a future refactor enables it accidentally
    // we still want to surface the limitation rather than ship a 400.
    if (values.candidateSurfaceType === 'behavior_rule') {
      message.error(
        'behavior_rule dynamic sim 暂不支持 — V5.1 backlog (V4 AgentLoopEngine 7+1 红灯不可改).',
      );
      return;
    }
    launchMutation.mutate({
      scenarioId: values.scenarioId,
      candidateAgentVersionId: values.candidateAgentVersionId?.trim() || undefined,
      candidateSurfaceType: values.candidateSurfaceType,
      personas: values.selectedPersonas,
      maxTurns: values.maxTurns,
    });
  };

  const onSelectAllPersonas = () => {
    form.setFieldValue('selectedPersonas', personas);
  };

  const onClearFilters = () => {
    setFilterScenarioId(undefined);
    setFilterVersionId(undefined);
    setFilterSurface(undefined);
    setPage(0);
  };

  const trials = trialsQuery.data?.content ?? [];
  const totalElements = trialsQuery.data?.totalElements ?? 0;

  const tableColumns = [
    {
      title: 'Trial id',
      dataIndex: 'trialId',
      key: 'trialId',
      width: 120,
      render: (id: string) => (
        <Text
          style={{
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: 11,
          }}
        >
          {id.slice(0, 8)}…
        </Text>
      ),
    },
    {
      title: 'Persona',
      dataIndex: 'persona',
      key: 'persona',
      ellipsis: true,
      render: (p: string) => (
        <Tooltip title={p}>
          <span style={{ fontSize: 12 }}>{p}</span>
        </Tooltip>
      ),
    },
    {
      title: 'Surface',
      dataIndex: 'candidateSurfaceType',
      key: 'candidateSurfaceType',
      width: 100,
      render: (s: string | null) =>
        s ? <Tag color="purple">{s}</Tag> : <Tag>baseline</Tag>,
    },
    {
      title: 'Turns',
      dataIndex: 'turnsUsed',
      key: 'turnsUsed',
      width: 70,
      align: 'right' as const,
    },
    {
      title: 'Termination',
      dataIndex: 'terminationReason',
      key: 'terminationReason',
      width: 130,
      render: (r: string | null) => (
        <Tag color={terminationColor(r)} style={{ marginRight: 0 }}>
          {r ?? 'pending'}
        </Tag>
      ),
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (iso: string) => (
        <Text style={{ fontSize: 11, color: 'var(--fg-3)' }}>
          {formatTimestamp(iso)}
        </Text>
      ),
    },
    {
      title: 'Transcript',
      key: 'transcript',
      width: 130,
      render: (_: unknown, row: SimulatorTrialResponse) => (
        <Button
          type="link"
          size="small"
          onClick={() => navigate(`/sessions/${row.sessionId}`)}
        >
          View transcript →
        </Button>
      ),
    },
  ];

  return (
    <div
      style={{
        padding: '14px 16px',
        border: '1px solid var(--border-subtle, #2a2a31)',
        borderRadius: 8,
        background: 'var(--bg-base, transparent)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 12,
        }}
      >
        <span style={{ fontWeight: 600, fontSize: 13, color: 'var(--fg-1)' }}>
          Dynamic Sim Trial Harness
        </span>
        <Tag color="purple">user-simulator</Tag>
        <span
          style={{
            marginLeft: 'auto',
            fontSize: 11,
            color: 'var(--fg-4)',
            fontFamily: 'var(--font-mono, monospace)',
          }}
        >
          agent #{agentNumericId}
        </span>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12, fontSize: 12 }}
        message="V5 Phase 1 — manual trigger only"
        description="Pick a scenario + candidate version (skill / prompt) + persona(s) and Run Trial. The user-simulator agent (xiaomi-mimo / mimo-v2.5-pro) plays the persona and ping-pongs with your candidate agent until task_completed / failure_signal / max_turns. Transcripts persist as origin='user_sim' sessions, isolated from V1 / V2 / V3 production analytics."
      />

      <Form<DynamicSimFormValues>
        form={form}
        layout="vertical"
        initialValues={{
          selectedPersonas: [],
          maxTurns: 10,
        }}
        onFinish={onLaunch}
        style={{ marginBottom: 16 }}
      >
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
            gap: 12,
            marginBottom: 8,
          }}
        >
          <Form.Item
            label="Scenario"
            name="scenarioId"
            rules={[{ required: true, message: 'Pick a scenario' }]}
            style={{ marginBottom: 0 }}
          >
            <Select
              loading={scenariosQuery.isLoading}
              showSearch
              optionFilterProp="label"
              placeholder="Pick a scenario for this agent"
              options={scenarioOptions}
              allowClear
              notFoundContent={
                scenariosQuery.isLoading ? 'Loading…' : 'No scenarios for this agent'
              }
            />
          </Form.Item>

          <Form.Item
            label={
              <span>
                Surface{' '}
                <Tooltip title="behavior_rule disabled — V5.1 backlog. V4 AgentLoopEngine 7+1 红灯文件 不可改 to inject candidate behavior_rule into engine read path.">
                  <span style={{ color: 'var(--fg-4)', cursor: 'help' }}>(?)</span>
                </Tooltip>
              </span>
            }
            name="candidateSurfaceType"
            style={{ marginBottom: 0 }}
          >
            <Select
              placeholder="(blank = baseline)"
              allowClear
              options={[
                { value: 'prompt', label: 'prompt' },
                { value: 'skill', label: 'skill' },
                {
                  value: 'behavior_rule',
                  label: (
                    <Tooltip title="暂不支持，V5.1 backlog (V4 AgentLoopEngine 7+1 红灯不可改)">
                      <span>behavior_rule (disabled)</span>
                    </Tooltip>
                  ),
                  disabled: true,
                },
              ]}
            />
          </Form.Item>

          <Form.Item
            label={
              <span>
                Candidate version id{' '}
                <Tooltip title="VARCHAR(64): skill version id / prompt version id. Leave blank to run against the agent's current baseline.">
                  <span style={{ color: 'var(--fg-4)', cursor: 'help' }}>(?)</span>
                </Tooltip>
              </span>
            }
            name="candidateAgentVersionId"
            style={{ marginBottom: 0 }}
          >
            <Input placeholder="(blank = baseline)" maxLength={64} />
          </Form.Item>

          <Form.Item
            label="Max turns"
            name="maxTurns"
            style={{ marginBottom: 0 }}
          >
            <InputNumber min={1} max={50} style={{ width: '100%' }} />
          </Form.Item>
        </div>

        <Form.Item
          label={
            <span>
              Personas{' '}
              <span style={{ fontSize: 11, color: 'var(--fg-4)' }}>
                (5 fixed — V5.x backlog to expand)
              </span>
            </span>
          }
          name="selectedPersonas"
          style={{ marginBottom: 12 }}
          rules={[
            {
              validator: (_r, v) =>
                v && v.length > 0
                  ? Promise.resolve()
                  : Promise.reject(new Error('Pick at least one persona')),
            },
          ]}
        >
          <Select
            mode="multiple"
            placeholder="Pick personas (one trial per persona)"
            options={personas.map((p) => ({ value: p, label: p }))}
            maxTagCount="responsive"
          />
        </Form.Item>

        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={launchMutation.isPending}
          >
            Run Trial
          </Button>
          <Button onClick={onSelectAllPersonas}>Select all 5 personas</Button>
          <Button onClick={() => form.resetFields()}>Reset</Button>
        </Space>
      </Form>

      <div
        style={{
          marginTop: 24,
          marginBottom: 12,
          paddingTop: 16,
          borderTop: '1px solid var(--border-subtle, #2a2a31)',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            marginBottom: 8,
            flexWrap: 'wrap',
          }}
        >
          <span style={{ fontWeight: 600, fontSize: 13, color: 'var(--fg-1)' }}>
            Trial history
          </span>
          <span style={{ fontSize: 11, color: 'var(--fg-4)' }}>
            {totalElements} total · sorted createdAt DESC
          </span>
          <Button
            size="small"
            onClick={() =>
              queryClient.invalidateQueries({ queryKey: ['dynamic-sim-trials'] })
            }
            loading={trialsQuery.isFetching}
            style={{ marginLeft: 'auto' }}
          >
            Refresh
          </Button>
        </div>

        <div
          style={{
            display: 'flex',
            gap: 8,
            marginBottom: 12,
            flexWrap: 'wrap',
            alignItems: 'center',
          }}
        >
          <Select
            placeholder="Filter by scenario"
            allowClear
            value={filterScenarioId}
            onChange={(v) => {
              setFilterScenarioId(v ?? undefined);
              setPage(0);
            }}
            options={scenarioOptions}
            style={{ minWidth: 220 }}
            size="small"
          />
          <Input
            placeholder="Filter by candidate version id"
            value={filterVersionId ?? ''}
            onChange={(e) => {
              setFilterVersionId(e.target.value || undefined);
              setPage(0);
            }}
            style={{ width: 220 }}
            size="small"
            allowClear
          />
          <Select
            placeholder="Filter by surface"
            allowClear
            value={filterSurface}
            onChange={(v) => {
              setFilterSurface(v ?? undefined);
              setPage(0);
            }}
            options={[
              { value: 'prompt', label: 'prompt' },
              { value: 'skill', label: 'skill' },
            ]}
            style={{ width: 160 }}
            size="small"
          />
          <Button size="small" onClick={onClearFilters}>
            Clear filters
          </Button>
        </div>

        {trialsQuery.isError && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
            message="Failed to load trials."
            description="GET /api/dynamic-sim/trials returned an error. Try Refresh, or check the server log."
          />
        )}

        {!trialsQuery.isError && trials.length === 0 && !trialsQuery.isLoading && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>
                No trials yet — run one above.
              </span>
            }
            style={{ marginTop: 24, marginBottom: 24 }}
          />
        )}

        {(trials.length > 0 || trialsQuery.isLoading) && (
          <>
            <Table<SimulatorTrialResponse>
              dataSource={trials}
              columns={tableColumns}
              rowKey="trialId"
              size="small"
              pagination={false}
              loading={trialsQuery.isLoading}
            />
            <div
              style={{
                marginTop: 12,
                display: 'flex',
                justifyContent: 'flex-end',
              }}
            >
              <Pagination
                current={page + 1}
                pageSize={pageSize}
                total={totalElements}
                showSizeChanger
                pageSizeOptions={[10, 20, 50, 100]}
                onChange={(p, ps) => {
                  setPage(p - 1);
                  setPageSize(Math.min(ps, PAGE_SIZE_MAX));
                }}
                size="small"
              />
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default DynamicSimPanel;
