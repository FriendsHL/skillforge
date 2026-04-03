import React, { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Spin, message } from 'antd';
import { RobotOutlined, MessageOutlined, ThunderboltOutlined, DollarOutlined } from '@ant-design/icons';
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
      value: data?.agentCount ?? 0,
      icon: <RobotOutlined style={{ fontSize: 24, color: '#1677ff' }} />,
    },
    {
      title: 'Active Sessions',
      value: data?.activeSessionCount ?? 0,
      icon: <MessageOutlined style={{ fontSize: 24, color: '#52c41a' }} />,
    },
    {
      title: 'Today Token Usage',
      value: data?.todayTokenUsage ?? 0,
      icon: <ThunderboltOutlined style={{ fontSize: 24, color: '#faad14' }} />,
    },
    {
      title: 'Today Cost (est.)',
      value: data?.todayCost ?? ((data?.todayTokenUsage ?? 0) * 0.00001).toFixed(4),
      prefix: '$',
      icon: <DollarOutlined style={{ fontSize: 24, color: '#f5222d' }} />,
    },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>Overview</h2>
      <Row gutter={16}>
        {stats.map((s, i) => (
          <Col xs={24} sm={12} lg={6} key={i}>
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
