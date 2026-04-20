import React, { useState, useCallback } from 'react';
import { Button, Modal, message, Tooltip } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listChannelConfigs,
  listChannelConversations,
  listChannelDeliveries,
  deleteChannelConfig,
} from '../api/channels';
import type { ChannelConfig } from '../types/channel';
import ChannelStatusBadge from '../components/channels/ChannelStatusBadge';
import ChannelConfigDrawer from '../components/channels/ChannelConfigDrawer';
import ChannelConversationList from '../components/channels/ChannelConversationList';
import DeliveryRetryPanel from '../components/channels/DeliveryRetryPanel';
import '../components/channels/channels.css';

const FEISHU_ICON = (
  <svg width={20} height={20} viewBox="0 0 48 48" fill="none">
    <path d="M24 4C12.954 4 4 12.954 4 24s8.954 20 20 20 20-8.954 20-20S35.046 4 24 4z" fill="#2a82e4" opacity={0.15}/>
    <text x="50%" y="54%" textAnchor="middle" dominantBaseline="middle" fontSize="22" fill="#2a82e4">飞</text>
  </svg>
);

const TELEGRAM_ICON = (
  <svg width={20} height={20} viewBox="0 0 240 240" fill="#29abe2">
    <path d="M120 0C53.726 0 0 53.726 0 120s53.726 120 120 120 120-53.726 120-120S186.274 0 120 0zm58.807 82.313l-20.248 95.383c-1.486 6.614-5.367 8.24-10.876 5.127l-30.066-22.147-14.52 13.97c-1.607 1.607-2.951 2.951-6.053 2.951l2.167-30.728 55.944-50.534c2.432-2.167-.529-3.368-3.772-1.2l-69.156 43.556-29.79-9.304c-6.475-2.025-6.604-6.475 1.353-9.589l116.314-44.835c5.394-1.942 10.12 1.316 8.703 7.35z"/>
  </svg>
);

const MOCK_ICON = '🧪';

const PLUS_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);

const EDIT_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M11 2.5l2.5 2.5L5 13.5H2.5V11L11 2.5z" />
  </svg>
);

const DELETE_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 4.5h10M6 4.5V3h4v1.5M5.5 4.5l.5 8h4l.5-8" />
  </svg>
);

function getPlatformIcon(platform: string): React.ReactNode {
  const lower = platform.toLowerCase();
  if (lower === 'feishu') return FEISHU_ICON;
  if (lower === 'telegram') return TELEGRAM_ICON;
  if (lower === 'mock') return MOCK_ICON;
  return '📡';
}

function getPlatformIconClass(platform: string): string {
  const lower = platform.toLowerCase();
  if (lower === 'feishu') return 'channel-platform-icon channel-platform-icon--feishu';
  if (lower === 'telegram') return 'channel-platform-icon channel-platform-icon--telegram';
  if (lower === 'mock') return 'channel-platform-icon channel-platform-icon--mock';
  return 'channel-platform-icon channel-platform-icon--unknown';
}

const Channels: React.FC = () => {
  const queryClient = useQueryClient();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ChannelConfig | null>(null);

  const { data: configs = [], isLoading: configsLoading } = useQuery({
    queryKey: ['channel-configs'],
    queryFn: () => listChannelConfigs().then((r) => r.data ?? []),
    staleTime: 30_000,
  });

  const { data: conversations = [], isLoading: convsLoading } = useQuery({
    queryKey: ['channel-conversations'],
    queryFn: () => listChannelConversations().then((r) => r.data ?? []),
    staleTime: 30_000,
  });

  const { data: deliveries = [], isLoading: deliveriesLoading } = useQuery({
    queryKey: ['channel-deliveries'],
    queryFn: () =>
      listChannelDeliveries({ status: ['FAILED', 'RETRY', 'PENDING'] }).then((r) => r.data ?? []),
    staleTime: 15_000,
  });

  const { mutate: removeConfig } = useMutation({
    mutationFn: (id: number) => deleteChannelConfig(id),
    onSuccess: () => {
      message.success('Channel removed');
      queryClient.invalidateQueries({ queryKey: ['channel-configs'] });
    },
    onError: () => {
      message.error('Failed to remove channel');
    },
  });

  const handleOpenCreate = useCallback(() => {
    setEditTarget(null);
    setDrawerOpen(true);
  }, []);

  const handleOpenEdit = useCallback((cfg: ChannelConfig) => {
    setEditTarget(cfg);
    setDrawerOpen(true);
  }, []);

  const handleDelete = useCallback(
    (cfg: ChannelConfig) => {
      Modal.confirm({
        title: `Remove ${cfg.displayName}?`,
        content: 'Active conversations linked to this channel will lose their connection.',
        okText: 'Remove',
        okType: 'danger',
        onOk: () => removeConfig(cfg.id),
      });
    },
    [removeConfig],
  );

  return (
    <div className="channels-view">
      {/* Header */}
      <div className="channels-page-header">
        <div>
          <h1 className="channels-page-title">Channels</h1>
          <p className="channels-page-subtitle">
            Manage messaging platform connections (Feishu, Telegram)
          </p>
        </div>
        <Button type="primary" icon={PLUS_ICON} onClick={handleOpenCreate}>
          Add Channel
        </Button>
      </div>

      {/* Platform cards */}
      <div className="channels-section">
        <div className="channels-section-header">
          <h2 className="channels-section-title">Configured Platforms</h2>
          <span className="channels-section-count">{configs.length}</span>
        </div>

        <div className="channels-platform-grid">
          {configsLoading
            ? Array.from({ length: 2 }, (_, i) => (
                <div key={i} className="channel-card" style={{ opacity: 0.4, minHeight: 90 }} />
              ))
            : configs.map((cfg) => (
                <div key={cfg.id} className="channel-card">
                  <div className="channel-card-header">
                    <div className={getPlatformIconClass(cfg.platform)}>
                      {getPlatformIcon(cfg.platform)}
                    </div>
                    <div className="channel-card-meta">
                      <p className="channel-card-name">{cfg.displayName}</p>
                      <p className="channel-card-platform-label">{cfg.platform}</p>
                    </div>
                  </div>
                  <div className="channel-card-footer">
                    <ChannelStatusBadge active={cfg.active} />
                    <div className="channel-card-actions">
                      <Tooltip title="Edit">
                        <Button
                          size="small"
                          icon={EDIT_ICON}
                          onClick={() => handleOpenEdit(cfg)}
                        />
                      </Tooltip>
                      <Tooltip title="Remove">
                        <Button
                          size="small"
                          danger
                          icon={DELETE_ICON}
                          onClick={() => handleDelete(cfg)}
                        />
                      </Tooltip>
                    </div>
                  </div>
                </div>
              ))}

          {/* Add new channel card */}
          <button
            type="button"
            className="channel-add-card"
            onClick={handleOpenCreate}
          >
            <span style={{ fontSize: 18, lineHeight: 1 }}>+</span>
            Connect a platform
          </button>
        </div>
      </div>

      {/* Conversations */}
      <div className="channels-section">
        <div className="channels-section-header">
          <h2 className="channels-section-title">Conversations</h2>
          <span className="channels-section-count">{conversations.length}</span>
        </div>
        <ChannelConversationList
          conversations={conversations}
          loading={convsLoading}
        />
      </div>

      {/* Failed deliveries */}
      <div className="channels-section">
        <div className="channels-section-header">
          <h2 className="channels-section-title">Pending / Failed Deliveries</h2>
          <span className="channels-section-count">{deliveries.length}</span>
        </div>
        <DeliveryRetryPanel
          deliveries={deliveries}
          loading={deliveriesLoading}
        />
      </div>

      <ChannelConfigDrawer
        open={drawerOpen}
        config={editTarget}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default Channels;
