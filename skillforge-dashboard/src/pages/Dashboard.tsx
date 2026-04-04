import React, { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Spin, message } from 'antd';
import { RobotOutlined, MessageOutlined, ThunderboltOutlined, DollarOutlined, CalendarOutlined, CloudOutlined } from '@ant-design/icons';
import { getDashboardOverview } from '../api';

const Dashboard: React.FC = () => {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getDashboardOverview()
      .then((res) => setData(res.data))
      .catch(() => message.error('Failed to load dashboard data'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <Spin size="large" style={{ display: 'block', marginTop: 100, textAlign: 'center' }} />;
  }

  const stats = [
    {
      title: 'Total Agents',
      value: data?.totalAgents ?? 0,
      icon: <RobotOutlined style={{ fontSize: 24, color: '#1677ff' }} />,
    },
    {
      title: 'Active Agents',
      value: data?.activeAgents ?? 0,
      icon: <ThunderboltOutlined style={{ fontSize: 24, color: '#52c41a' }} />,
    },
    {
      title: 'Total Sessions',
      value: data?.totalSessions ?? 0,
      icon: <MessageOutlined style={{ fontSize: 24, color: '#13c2c2' }} />,
    },
    {
      title: 'Today Sessions',
      value: data?.todaySessions ?? 0,
      icon: <CalendarOutlined style={{ fontSize: 24, color: '#faad14' }} />,
    },
    {
      title: 'Total Tokens',
      value: (data?.totalInputTokens ?? 0) + (data?.totalOutputTokens ?? 0),
      icon: <CloudOutlined style={{ fontSize: 24, color: '#722ed1' }} />,
    },
    {
      title: 'Total Output Tokens',
      value: data?.totalOutputTokens ?? 0,
      icon: <DollarOutlined style={{ fontSize: 24, color: '#f5222d' }} />,
    },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>Overview</h2>
      <Row gutter={16}>
        {stats.map((s, i) => (
          <Col xs={24} sm={12} lg={8} key={i}>
            <Card>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                {s.icon}
                <Statistic title={s.title} value={s.value} prefix={s.prefix} />
              </div>
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
};

export default Dashboard;
