import React, { useEffect, useState } from 'react';
import { Card, Row, Col, Spin } from 'antd';
import ReactECharts from 'echarts-for-react';
import { getDailyUsage, getUsageByModel, getUsageByAgent } from '../api';

interface DailyUsage {
  date: string;
  inputTokens: number;
  outputTokens: number;
}

interface ModelUsageItem {
  model: string;
  totalTokens: number;
}

interface AgentUsageItem {
  agentName: string;
  totalTokens: number;
}

const ModelUsage: React.FC = () => {
  const [dailyData, setDailyData] = useState<DailyUsage[]>([]);
  const [modelData, setModelData] = useState<ModelUsageItem[]>([]);
  const [agentData, setAgentData] = useState<AgentUsageItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [dailyRes, modelRes, agentRes] = await Promise.all([
          getDailyUsage(30),
          getUsageByModel(),
          getUsageByAgent(),
        ]);
        setDailyData(dailyRes.data as DailyUsage[]);
        setModelData(modelRes.data as ModelUsageItem[]);
        setAgentData(agentRes.data as AgentUsageItem[]);
      } catch (err) {
        console.error('Failed to fetch usage data', err);
      } finally {
        setLoading(false);
      }
    };
    void fetchData();
  }, []);

  const dailyChartOption = {
    tooltip: {
      trigger: 'axis' as const,
    },
    legend: {
      data: ['Input Tokens', 'Output Tokens'],
    },
    xAxis: {
      type: 'category' as const,
      data: dailyData.map((d) => d.date),
    },
    yAxis: {
      type: 'value' as const,
    },
    series: [
      {
        name: 'Input Tokens',
        type: 'line',
        smooth: true,
        data: dailyData.map((d) => d.inputTokens),
        itemStyle: { color: '#1890ff' },
      },
      {
        name: 'Output Tokens',
        type: 'line',
        smooth: true,
        data: dailyData.map((d) => d.outputTokens),
        itemStyle: { color: '#52c41a' },
      },
    ],
  };

  const modelPieOption = {
    tooltip: {
      trigger: 'item' as const,
      formatter: '{b}: {c} ({d}%)',
    },
    legend: {
      orient: 'vertical' as const,
      left: 'left',
    },
    series: [
      {
        type: 'pie',
        radius: '60%',
        data: modelData.map((m) => ({
          name: m.model,
          value: m.totalTokens,
        })),
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)',
          },
        },
      },
    ],
  };

  const agentBarOption = {
    tooltip: {
      trigger: 'axis' as const,
    },
    xAxis: {
      type: 'category' as const,
      data: agentData.map((a) => a.agentName),
      axisLabel: {
        rotate: 30,
      },
    },
    yAxis: {
      type: 'value' as const,
    },
    series: [
      {
        type: 'bar',
        data: agentData.map((a) => a.totalTokens),
        itemStyle: { color: '#722ed1' },
      },
    ],
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>Model Usage</h2>
      <Card title="Token Usage Trend (Last 30 Days)" style={{ marginBottom: 16 }}>
        <ReactECharts option={dailyChartOption} style={{ height: 350 }} />
      </Card>
      <Row gutter={16}>
        <Col span={12}>
          <Card title="Usage by Model">
            <ReactECharts option={modelPieOption} style={{ height: 350 }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title="Usage by Agent">
            <ReactECharts option={agentBarOption} style={{ height: 350 }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ModelUsage;
