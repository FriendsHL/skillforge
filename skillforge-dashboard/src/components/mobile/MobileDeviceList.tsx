import React from 'react';
import { Button, Empty, Spin, Tag, Tooltip } from 'antd';
import { DeleteOutlined, MobileOutlined } from '@ant-design/icons';
import type { MobileDevice } from '../../api/mobile';

interface MobileDeviceListProps {
  devices: MobileDevice[];
  loading: boolean;
  revokingId: string | null;
  onRevoke: (deviceId: string) => void;
}

function platformLabel(platform: string): string {
  return platform.toLowerCase() === 'ios' ? 'iOS' : platform;
}

function formatDate(value: string | null): string {
  if (!value) return 'Never';
  return new Date(value).toLocaleString();
}

const MobileDeviceList: React.FC<MobileDeviceListProps> = ({
  devices,
  loading,
  revokingId,
  onRevoke,
}) => {
  if (loading) {
    return (
      <div className="mobile-list-loading">
        <Spin />
      </div>
    );
  }

  if (devices.length === 0) {
    return (
      <div className="mobile-empty mobile-empty--devices">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No paired mobile devices" />
      </div>
    );
  }

  return (
    <div className="mobile-device-list">
      {devices.map((device) => (
        <article key={device.id} className="mobile-device-row">
          <div className="mobile-device-icon" aria-hidden="true">
            <MobileOutlined />
          </div>
          <div className="mobile-device-main">
            <div className="mobile-device-titleline">
              <h3>{device.deviceName}</h3>
              <Tag color={device.status === 'active' ? 'success' : 'default'}>{device.status}</Tag>
            </div>
            <div className="mobile-device-meta">
              <span>{platformLabel(device.platform)}</span>
              <span>{device.appVersion ?? 'Unknown app'}</span>
              <span>Last seen {formatDate(device.lastSeenAt)}</span>
            </div>
            <div className="mobile-device-scopes">
              {device.scopes.map((scope) => (
                <Tag key={scope}>{scope}</Tag>
              ))}
            </div>
          </div>
          <Tooltip title="Revoke this device">
            <Button
              danger
              icon={<DeleteOutlined />}
              loading={revokingId === device.id}
              onClick={() => onRevoke(device.id)}
            >
              Revoke
            </Button>
          </Tooltip>
        </article>
      ))}
    </div>
  );
};

export default MobileDeviceList;
