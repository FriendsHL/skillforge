import React, { useEffect, useState } from 'react';
import { Card, Row, Col, Spin, Segmented, Space, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import { getDailyUsage, getUsageByModel, getUsageByAgent } from '../api';

const { Text } = Typography;

type RangeKey = '7d' | '30d' | '90d' | 'all';
const RANGE_DAYS: Record<RangeKey, number> = {
  '7d': 7,
  '30d': 30,
  '90d': 90,
  all: 3650, // 10 年,作为"全部"的近似
};

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
  const [range, setRange] = useState<RangeKey>('30d');

  useEffect(() => {
    let cancelled = false;
    const fetchData = async () => {
      setLoading(true);
      try {
        const [dailyRes, modelRes, agentRes] = await Promise.all([
          getDailyUsage(RANGE_DAYS[range]),
          getUsageByModel(),
          getUsageByAgent(),
        ]);
        if (cancelled) return;
        setDailyData(dailyRes.data as DailyUsage[]);
        setModelData(modelRes.data as ModelUsageItem[]);
        setAgentData(agentRes.data as AgentUsageItem[]);
      } catch (err) {
        if (!cancelled) console.error('Failed to fetch usage data', err);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void fetchData();
    return () => {
      cancelled = true;
    };
  }, [range]);

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

  const totalInRange = dailyData.reduce(
    (acc, d) => acc + (d.inputTokens || 0) + (d.outputTokens || 0),
    0
  );

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', marginBottom: 20 }}>
        <Space size={12}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            Total: {totalInRange.toLocaleString()} tokens
          </Text>
          <Segmented
            value={range}
            onChange={(v) => setRange(v as RangeKey)}
            options={[
              { label: '7d', value: '7d' },
              { label: '30d', value: '30d' },
              { label: '90d', value: '90d' },
              { label: 'All', value: 'all' },
            ]}
          />
        </Space>
      </div>

      {loading ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      ) : (
        <>
      <Card title={`Token Usage Trend (${range})`} style={{ marginBottom: 16 }}>
        {dailyData.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>暂无数据</div>
        ) : (
          <ReactECharts option={dailyChartOption} style={{ height: 350 }} />
        )}
      </Card>
      <Row gutter={16}>
        <Col span={12}>
          <Card title="Usage by Model (all-time)">
            {modelData.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>暂无数据</div>
            ) : (
              <ReactECharts option={modelPieOption} style={{ height: 350 }} />
            )}
          </Card>
        </Col>
        <Col span={12}>
          <Card title="Usage by Agent (all-time)">
            {agentData.length === 0 ? (
              <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>暂无数据</div>
            ) : (
              <ReactECharts option={agentBarOption} style={{ height: 350 }} />
            )}
          </Card>
        </Col>
      </Row>
        </>
      )}
    </div>
  );
};

export default ModelUsage;
