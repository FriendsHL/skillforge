import React from 'react';
import { Card, Empty, Button, Tag, Skeleton } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import type { LifecycleHookPresetDto } from '../../api';
import {
  lifecycleHooksConfigSchema,
  type LifecycleHooksConfig,
} from '../../constants/lifecycleHooks';

interface PresetModeProps {
  presets: LifecycleHookPresetDto[];
  isLoading: boolean;
  /** Called when user applies a preset. Passes validated config. */
  onApply: (config: LifecycleHooksConfig, presetName: string) => void;
}

/**
 * Preset mode: horizontal row of preset cards. Click "Apply" overwrites
 * rawJson with the preset config. Presets come from
 * `GET /api/lifecycle-hooks/presets` (see doc §4.2).
 */
const PresetMode: React.FC<PresetModeProps> = ({ presets, isLoading, onApply }) => {
  if (isLoading) {
    return (
      <div className="sf-hooks-preset-grid">
        {Array.from({ length: 4 }).map((_, i) => (
          <Card key={i} className="sf-hooks-preset-card" bordered>
            <Skeleton active paragraph={{ rows: 2 }} />
          </Card>
        ))}
      </div>
    );
  }

  if (presets.length === 0) {
    return (
      <Empty
        description="No presets available. Check backend /api/lifecycle-hooks/presets endpoint."
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  return (
    <div className="sf-hooks-preset-grid">
      {presets.map((preset) => (
        <PresetCard key={preset.id} preset={preset} onApply={onApply} />
      ))}
    </div>
  );
};

interface PresetCardProps {
  preset: LifecycleHookPresetDto;
  onApply: (config: LifecycleHooksConfig, presetName: string) => void;
}

const PresetCard: React.FC<PresetCardProps> = ({ preset, onApply }) => {
  const validation = lifecycleHooksConfigSchema.safeParse(preset.config);

  const handleApply = () => {
    if (!validation.success) return;
    onApply(validation.data, preset.name);
  };

  const eventCount = validation.success
    ? Object.values(validation.data.hooks ?? {}).reduce(
        (sum, arr) => sum + (Array.isArray(arr) ? arr.length : 0),
        0,
      )
    : 0;

  return (
    <Card
      className="sf-hooks-preset-card"
      bordered
      title={
        <div className="sf-hooks-preset-title">
          <ThunderboltOutlined />
          <span>{preset.name}</span>
        </div>
      }
    >
      <p className="sf-hooks-preset-desc">{preset.description}</p>
      <div className="sf-hooks-preset-meta">
        <Tag color="blue">{eventCount} hooks</Tag>
        {!validation.success && <Tag color="red">Invalid preset</Tag>}
      </div>
      <Button
        type="primary"
        size="small"
        block
        disabled={!validation.success}
        onClick={handleApply}
      >
        应用
      </Button>
    </Card>
  );
};

export default PresetMode;
