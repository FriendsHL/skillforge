import api from './index';
import type { ChannelConfig, ChannelConversation, ChannelDelivery } from '../types/channel';

export interface CreateChannelConfigRequest {
  platform: string;
  displayName: string;
  defaultAgentId: number;
  active?: boolean;
  credentials?: Record<string, string>;
}

export interface UpdateChannelConfigRequest {
  displayName?: string;
  defaultAgentId?: number;
  active?: boolean;
  credentials?: Record<string, string> | null;
}

export interface ListChannelConversationsParams {
  platform?: string;
  page?: number;
  size?: number;
}

export interface ListChannelDeliveriesParams {
  platform?: string;
  status?: string[];
  page?: number;
  size?: number;
}

export interface ChannelTestResult {
  ok: boolean;
  message?: string;
}

export const listChannelConfigs = () =>
  api.get<ChannelConfig[]>('/channel-configs');

export const createChannelConfig = (data: CreateChannelConfigRequest) =>
  api.post<ChannelConfig>('/channel-configs', data);

export const updateChannelConfig = (id: number, data: UpdateChannelConfigRequest) =>
  api.patch<ChannelConfig>(`/channel-configs/${id}`, data);

export const deleteChannelConfig = (id: number) =>
  api.delete(`/channel-configs/${id}`);

export const testChannelConfig = (id: number) =>
  api.get<ChannelTestResult>(`/channel-configs/${id}/test`);

export const listChannelConversations = (params?: ListChannelConversationsParams) =>
  api.get<ChannelConversation[]>('/channel-conversations', { params });

export const listChannelDeliveries = (params?: ListChannelDeliveriesParams) => {
  const search = new URLSearchParams();
  if (params?.platform) search.set('platform', params.platform);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  params?.status?.forEach((s) => search.append('status', s));
  const qs = search.toString();
  return api.get<ChannelDelivery[]>(`/channel-deliveries${qs ? `?${qs}` : ''}`);
};

export const retryDelivery = (id: string) =>
  api.post(`/channel-deliveries/${id}/retry`);

export const dropDelivery = (id: string) =>
  api.post(`/channel-deliveries/${id}/drop`);
