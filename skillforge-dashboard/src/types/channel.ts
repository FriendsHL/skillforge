export interface ChannelConfig {
  id: number;
  platform: string;
  displayName: string;
  active: boolean;
  defaultAgentId: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelConversation {
  id: number;
  platform: string;
  conversationId: string;
  sessionId: string;
  createdAt: string;
  closedAt: string | null;
}

export interface ChannelDelivery {
  id: string;
  platform: string;
  conversationId: string;
  inboundMessageId: string;
  status: 'PENDING' | 'IN_FLIGHT' | 'RETRY' | 'DELIVERED' | 'FAILED';
  retryCount: number;
  lastError: string | null;
  scheduledAt: string;
  deliveredAt: string | null;
}
