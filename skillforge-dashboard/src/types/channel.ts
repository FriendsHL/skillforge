export interface ChannelConfig {
  id: number;
  platform: string;
  displayName: string;
  active: boolean;
  configJson?: string | null;
  defaultAgentId: number;
  webhookSecretSet?: boolean;
  credentialsSet?: boolean;
  warning?: string | null;
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

/** Response from POST /channel-configs/weixin/qr-login/start */
export interface WeixinQrLoginStart {
  /** The raw qrcode string (32-hex ticket) — pass back to status for polling. */
  qrcode: string;
  /**
   * The login URL to encode into a scannable QR code (the user scans it in
   * WeChat). This is a URL, NOT a base64 image — render it via a QR generator.
   */
  qrcode_img_content: string;
}

/** Response from GET /channel-configs/weixin/qr-login/status */
export interface WeixinQrLoginStatus {
  /** Free-form backend status, e.g. "pending" | "confirmed" | "expired". */
  status: string;
  /** True once the scan is confirmed and the bot token has been bound. */
  bound: boolean;
}
