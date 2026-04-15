import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Card, List, Tag, Typography, Button, Tooltip } from 'antd';
import { useNavigate } from 'react-router-dom';
import { getSubAgentRuns, extractList } from '../api';

const { Text } = Typography;

interface SubAgentRun {
  runId: string;
  childSessionId?: string | null;
  childAgentId?: number | null;
  childAgentName?: string | null;
  task?: string | null;
  status?: string | null;
  finalMessage?: string | null;
  spawnedAt?: string | null;
  completedAt?: string | null;
}

interface Props {
  sessionId?: string;
  parentRunning: boolean;
}

const statusColor = (status?: string | null) => {
  switch (status) {
    case 'RUNNING':
      return 'processing';
    case 'COMPLETED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'CANCELLED':
      return 'default';
    default:
      return 'default';
  }
};

const formatTs = (iso?: string | null) => {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    return d.toLocaleTimeString();
  } catch {
    return '';
  }
};

const SubAgentRunsPanel: React.FC<Props> = ({ sessionId, parentRunning }) => {
  const [runs, setRuns] = useState<SubAgentRun[]>([]);
  const navigate = useNavigate();
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchRuns = useCallback(async () => {
    if (!sessionId) return;
    try {
      const res = await getSubAgentRuns(sessionId, 1);
      const list = extractList<SubAgentRun>(res);
      // 最近派发的排在前面
      list.sort((a, b) => {
        const ta = a.spawnedAt ? new Date(a.spawnedAt).getTime() : 0;
        const tb = b.spawnedAt ? new Date(b.spawnedAt).getTime() : 0;
        return tb - ta;
      });
      setRuns(list);
    } catch {
      // swallow — panel is non-critical
    }
  }, [sessionId]);

  // 初次加载 + session 切换时
  useEffect(() => {
    setRuns([]);
    if (sessionId) fetchRuns();
  }, [sessionId, fetchRuns]);

  // parent running 时轮询 3s;idle 时停止
  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    if (sessionId && parentRunning) {
      pollRef.current = setInterval(fetchRuns, 3000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [sessionId, parentRunning, fetchRuns]);

  // parent 从 running → idle 的瞬间,再拉一次,确保最终状态展示
  const wasRunningRef = useRef(parentRunning);
  useEffect(() => {
    if (wasRunningRef.current && !parentRunning && sessionId) {
      fetchRuns();
    }
    wasRunningRef.current = parentRunning;
  }, [parentRunning, sessionId, fetchRuns]);

  if (!sessionId || runs.length === 0) {
    return null;
  }

  return (
    <Card
      size="small"
      title="SubAgent dispatches"
      style={{ margin: '8px 12px 0' }}
      styles={{ body: { padding: '4px 0' } }}
    >
      <List
        size="small"
        dataSource={runs}
        renderItem={(run) => {
          const shortRunId = run.runId ? run.runId.slice(0, 8) : '';
          return (
            <List.Item
              style={{ padding: '6px 12px' }}
              actions={[
                run.childSessionId ? (
                  <Button
                    key="view"
                    size="small"
                    type="link"
                    onClick={() => navigate(`/chat/${run.childSessionId}`)}
                  >
                    View child
                  </Button>
                ) : (
                  <Text key="no-child" type="secondary" style={{ fontSize: 12 }}>
                    (no child yet)
                  </Text>
                ),
              ]}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Text strong ellipsis style={{ maxWidth: 180 }}>
                    {run.childAgentName || `Agent #${run.childAgentId ?? '?'}`}
                  </Text>
                  <Tag color={statusColor(run.status)} style={{ marginRight: 0 }}>
                    {run.status || 'UNKNOWN'}
                  </Tag>
                  <Tooltip title={run.runId}>
                    <Text type="secondary" style={{ fontSize: 11, fontFamily: 'monospace' }}>
                      {shortRunId}
                    </Text>
                  </Tooltip>
                  {run.spawnedAt && (
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatTs(run.spawnedAt)}
                    </Text>
                  )}
                </div>
                {run.task && (
                  <Text type="secondary" ellipsis style={{ fontSize: 12 }}>
                    {run.task}
                  </Text>
                )}
              </div>
            </List.Item>
          );
        }}
      />
    </Card>
  );
};

export default SubAgentRunsPanel;
