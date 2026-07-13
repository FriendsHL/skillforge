import api from './index';

export type MobileDeviceStatus = 'active' | 'revoked';
export type MobilePairingStatus = 'pending' | 'claimed' | 'expired' | 'cancelled';

export interface MobilePairingQrPayload {
  type: 'skillforge.mobile_pairing';
  version: number;
  serverName: string;
  pairingId: string;
  pairingSecret: string;
  endpoints: string[];
  expiresAt: string;
}

export interface MobilePairingCreateRequest {
  serverName: string;
  endpoints: string[];
}

export interface MobilePairingCreateResponse {
  pairingId: string;
  status: MobilePairingStatus;
  setupCode: string;
  expiresAt: string;
  qrPayload: MobilePairingQrPayload;
}

export interface MobilePairingStatusResponse {
  pairingId: string;
  status: MobilePairingStatus;
  claimedDeviceId: string | null;
}

export interface MobileDevice {
  id: string;
  deviceName: string;
  platform: string;
  appVersion: string | null;
  status: MobileDeviceStatus;
  scopes: string[];
  lastSeenAt: string | null;
  createdAt: string;
  revokedAt: string | null;
}

export const createMobilePairing = (data: MobilePairingCreateRequest) =>
  api.post<MobilePairingCreateResponse>('/mobile/pairings', data);

export const getMobilePairingStatus = (pairingId: string) =>
  api.get<MobilePairingStatusResponse>(`/mobile/pairings/${pairingId}`);

export const listMobileDevices = () => api.get<MobileDevice[]>('/mobile/devices');

export const revokeMobileDevice = (deviceId: string) =>
  api.post<MobileDevice>(`/mobile/devices/${deviceId}/revoke`);
