import React, { useEffect, useMemo, useState } from 'react';
import { Card, Col, Row, Spin, message, List, Tag, Typography } from 'antd';
import {
  RobotOutlined,
  MessageOutlined,
  ThunderboltOutlined,
  CloudOutlined,
  CalendarOutlined,
  FireOutlined,
} from '@ant-design/icons';

import ReactECharts from 'echarts-for-react';
import { useNavigate } from 'react-router-dom';
import { getDashboardOverview, getDailyUsage, getSessions } from '../api';

const { Text } = Typography;

interface Overview {
  totalAgents: number;
  activeAgents: number;
  totalSessions: number;
  todaySessions: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  todayInputTokens: number;
  todayOutputTokens: number;
}

interface DailyPoint {
  date: string;
  inputTokens: number;
  outputTokens: number;
}

interface SessionItem {
  id?: string;
  sessionId?: string;
  agentId?: number;
  title?: string;
  messageCount?: number;
  totalInputTokens?: number;
  totalOutputTokens?: number;
  updatedAt?: string;
}

const formatNumber = (n: number) => n.toLocaleString();

const Dashboard: React.FC = () => {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [daily, setDaily] = useState<DailyPoint[]>([]);
  const [recent, setRecent] = useState<SessionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    const load = async () => {
      try {
        const [ovRes, dailyRes, sessRes] = await Promise.all([
          getDashboardOverview(),
          getDailyUsage(7),
          getSessions(1),
        ]);
        setOverview(ovRes.data as Overview);
        setDaily(dailyRes.data as DailyPoint[]);
        const list: SessionItem[] = Array.isArray(sessRes.data)
          ? sessRes.data
          : (sessRes.data as any)?.data ?? [];
        // 取最近 5 条(后端按 updatedAt desc 返回的话直接 slice)
        setRecent(list.slice(0, 5));
      } catch {
        message.error('Failed to load dashboard data');
      } finally {
        setLoading(false);
      }
    };
    void load();
  }, []);

  const trendOption = useMemo(
    () => ({
      tooltip: { trigger: 'axis' as const },
      grid: { left: 40, right: 20, top: 30, bottom: 30 },
      legend: { data: ['Input', 'Output'], top: 0 },
      xAxis: {
        type: 'category' as const,
        data: daily.map((d) => d.date),
        boundaryGap: false,
      },
      yAxis: { type: 'value' as const },
      series: [
        {
          name: 'Input',
          type: 'line' as const,
          smooth: true,
          showSymbol: false,
          areaStyle: { opacity: 0.15 },
          itemStyle: { color: '#1677ff' },
          data: daily.map((d) => d.inputTokens),
        },
        {
          name: 'Output',
          type: 'line' as const,
          smooth: true,
          showSymbol: false,
          areaStyle: { opacity: 0.15 },
          itemStyle: { color: '#52c41a' },
          data: daily.map((d) => d.outputTokens),
        },
      ],
    }),
    [daily]
  );

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 100, textAlign: 'center' }} />;
  }

  const ov = overview ?? ({} as Overview);
  const totalTokens = (ov.totalInputTokens ?? 0) + (ov.totalOutputTokens ?? 0);
  const todayTokens = (ov.todayInputTokens ?? 0) + (ov.todayOutputTokens ?? 0);

  const kpis = [
    {
      title: 'Total Agents',
      value: ov.totalAgents ?? 0,
      icon: <RobotOutlined style={{ fontSize: 22, color: 'var(--accent-primary)' }} />,
      sub: `${ov.activeAgents ?? 0} active`,
    },
    {
      title: 'Total Sessions',
      value: ov.totalSessions ?? 0,
      icon: <MessageOutlined style={{ fontSize: 22, color: 'var(--accent-primary)' }} />,
      sub: `${ov.todaySessions ?? 0} today`,
    },
    {
      title: "Today's Tokens",
      value: todayTokens,
      icon: <CalendarOutlined style={{ fontSize: 22, color: 'var(--accent-primary)' }} />,
      sub: `in ${formatNumber(ov.todayInputTokens ?? 0)} / out ${formatNumber(ov.todayOutputTokens ?? 0)}`,
    },
    {
      title: 'Total Tokens',
      value: totalTokens,
      icon: <CloudOutlined style={{ fontSize: 22, color: 'var(--accent-primary)' }} />,
      sub: `in ${formatNumber(ov.totalInputTokens ?? 0)} / out ${formatNumber(ov.totalOutputTokens ?? 0)}`,
    },
  ];

  return (
    <div>
      <Row gutter={[16, 16]}>
        {kpis.map((k) => (
          <Col xs={24} sm={12} lg={6} key={k.title}>
            <Card
              className="sf-stat-card"
              style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="sf-stat-label">{k.title}</div>
                  <div className="sf-stat-metric">{formatNumber(Number(k.value))}</div>
                  <div className="sf-stat-sub">{k.sub}</div>
                </div>
                <div style={{ flexShrink: 0, opacity: 0.85 }}>{k.icon}</div>
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={16}>
          <Card title={<><ThunderboltOutlined /> Token usage — last 7 days</>} className="sf-surface-card" style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
            {daily.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>
                暂无数据
              </div>
            ) : (
              <ReactECharts option={trendOption} style={{ height: 280 }} />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title={<><FireOutlined /> Recent sessions</>} className="sf-surface-card" style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
            {recent.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>
                暂无会话
              </div>
            ) : (
              <List
                size="small"
                dataSource={recent}
                renderItem={(s) => {
                  const sid = String(s.id ?? s.sessionId ?? '');
                  const tokens = (s.totalInputTokens ?? 0) + (s.totalOutputTokens ?? 0);
                  return (
                    <List.Item
                      style={{ cursor: 'pointer' }}
                      onClick={() => sid && navigate(`/chat/${sid}`)}
                    >
                      <div style={{ width: '100%' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                          <Text ellipsis style={{ maxWidth: 180 }}>
                            {s.title && s.title !== 'New Session'
                              ? s.title
                              : `Session ${sid.slice(0, 8)}`}
                          </Text>
                          <Tag color="blue">{s.messageCount ?? 0} msg</Tag>
                        </div>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          {formatNumber(tokens)} tokens
                          {s.updatedAt ? ` · ${s.updatedAt.slice(0, 16).replace('T', ' ')}` : ''}
                        </Text>
                      </div>
                    </List.Item>
                  );
                }}
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
