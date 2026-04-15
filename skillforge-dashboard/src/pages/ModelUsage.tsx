import React, { useState, useEffect } from 'react';
import { Card, Row, Col, Spin, Segmented, Space, Typography, message } from 'antd';
import ReactECharts from 'echarts-for-react';
import { useQuery } from '@tanstack/react-query';
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
  const [range, setRange] = useState<RangeKey>('30d');

  const dailyQuery = useQuery({
    queryKey: ['usage', 'daily', range],
    queryFn: () => getDailyUsage(RANGE_DAYS[range]).then((res) => res.data as DailyUsage[]),
  });
  const modelQuery = useQuery({
    queryKey: ['usage', 'by-model'],
    queryFn: () => getUsageByModel().then((res) => res.data as ModelUsageItem[]),
    staleTime: 5 * 60_000, // aggregate stats rarely change — 5 min stale
  });
  const agentQuery = useQuery({
    queryKey: ['usage', 'by-agent'],
    queryFn: () => getUsageByAgent().then((res) => res.data as AgentUsageItem[]),
    staleTime: 5 * 60_000,
  });

  useEffect(() => {
    if (dailyQuery.isError) message.error('Failed to load daily usage');
  }, [dailyQuery.isError]);
  useEffect(() => {
    if (modelQuery.isError) message.error('Failed to load usage by model');
  }, [modelQuery.isError]);
  useEffect(() => {
    if (agentQuery.isError) message.error('Failed to load usage by agent');
  }, [agentQuery.isError]);

  const dailyData = dailyQuery.data ?? [];
  const modelData = modelQuery.data ?? [];
  const agentData = agentQuery.data ?? [];
  const loading = dailyQuery.isLoading || modelQuery.isLoading || agentQuery.isLoading;

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
