import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

const listMobileDevicesMock = vi.fn();
const createMobilePairingMock = vi.fn();
const getMobilePairingStatusMock = vi.fn();
const revokeMobileDeviceMock = vi.fn();

vi.mock('../../api/mobile', () => ({
  listMobileDevices: (...args: unknown[]) => listMobileDevicesMock(...args),
  createMobilePairing: (...args: unknown[]) => createMobilePairingMock(...args),
  getMobilePairingStatus: (...args: unknown[]) => getMobilePairingStatusMock(...args),
  revokeMobileDevice: (...args: unknown[]) => revokeMobileDeviceMock(...args),
}));

import MobileDevices from '../MobileDevices';
import { buildMobilePairingEndpoints } from '../../components/mobile/mobilePairingEndpoints';

const pairingResponse = {
  data: {
    pairingId: 'pairing-1',
    status: 'pending',
    setupCode: '842193',
    expiresAt: '2026-07-09T06:05:00Z',
    qrPayload: {
      type: 'skillforge.mobile_pairing',
      version: 1,
      serverName: 'SkillForge Dev',
      pairingId: 'pairing-1',
      pairingSecret: 'pairing-secret',
      endpoints: ['http://192.168.1.10:8080'],
      expiresAt: '2026-07-09T06:05:00Z',
    },
  },
};

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('MobileDevices page', () => {
  it('keeps the current origin and configured LAN/Tailscale pairing endpoints once', () => {
    expect(buildMobilePairingEndpoints(
      'https://macbook-air.example.ts.net',
      ' http://192.168.1.6:3000, https://macbook-air.example.ts.net ',
    )).toEqual([
      'https://macbook-air.example.ts.net',
      'http://192.168.1.6:3000',
    ]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    vi.useRealTimers();
    listMobileDevicesMock.mockReset().mockResolvedValue({
      data: [
        {
          id: 'device-1',
          deviceName: 'Youren iPhone',
          platform: 'ios',
          appVersion: '1.0.0',
          status: 'active',
          scopes: ['chat:read'],
          lastSeenAt: '2026-07-09T06:01:00Z',
          createdAt: '2026-07-09T06:00:00Z',
          revokedAt: null,
        },
      ],
    });
    createMobilePairingMock.mockReset().mockResolvedValue(pairingResponse);
    getMobilePairingStatusMock.mockReset().mockResolvedValue({
      data: { pairingId: 'pairing-1', status: 'pending', claimedDeviceId: null },
    });
    revokeMobileDeviceMock.mockReset().mockResolvedValue({ data: { id: 'device-1', status: 'revoked' } });
  });

  it('renders paired devices from the API', async () => {
    render(<MobileDevices />);

    expect(await screen.findByText('Youren iPhone')).toBeInTheDocument();
    expect(screen.getByText('iOS')).toBeInTheDocument();
    expect(screen.getByText('active')).toBeInTheDocument();
  });

  it('generates a QR pairing payload with setup code', async () => {
    render(<MobileDevices />);

    fireEvent.click(await screen.findByRole('button', { name: /pair iphone/i }));

    expect(createMobilePairingMock).toHaveBeenCalledWith({
      serverName: 'SkillForge Dev',
      endpoints: buildMobilePairingEndpoints(
        window.location.origin,
        import.meta.env.VITE_SKILLFORGE_MOBILE_ENDPOINTS,
      ),
    });
    expect(await screen.findByText('842193')).toBeInTheDocument();
    expect(screen.getByText(/waiting for scan/i)).toBeInTheDocument();
    expect(screen.getByTestId('mobile-pairing-qr-payload')).toHaveTextContent('skillforge.mobile_pairing');
  });

  it('polls until pairing is claimed and refreshes devices', async () => {
    vi.useFakeTimers();
    getMobilePairingStatusMock.mockResolvedValueOnce({
      data: { pairingId: 'pairing-1', status: 'claimed', claimedDeviceId: 'device-2' },
    });
    render(<MobileDevices />);

    fireEvent.click(screen.getByRole('button', { name: /pair iphone/i }));
    await act(async () => {
      await flushPromises();
    });
    expect(screen.getByText('842193')).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(2000);
      await flushPromises();
    });

    expect(screen.getByText('Paired')).toBeInTheDocument();
    expect(listMobileDevicesMock).toHaveBeenCalledTimes(2);
  });

  it('stops polling and shows expired state when pairing expires', async () => {
    vi.useFakeTimers();
    getMobilePairingStatusMock.mockResolvedValueOnce({
      data: { pairingId: 'pairing-1', status: 'expired', claimedDeviceId: null },
    });
    render(<MobileDevices />);

    fireEvent.click(screen.getByRole('button', { name: /pair iphone/i }));
    await act(async () => {
      await flushPromises();
    });
    expect(screen.getByText('842193')).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(2000);
      await flushPromises();
    });

    expect(screen.getByText('QR code expired')).toBeInTheDocument();
    expect(listMobileDevicesMock).toHaveBeenCalledTimes(1);
  });

  it('clears pairing poll timer on unmount', async () => {
    vi.useFakeTimers();
    const view = render(<MobileDevices />);

    fireEvent.click(screen.getByRole('button', { name: /pair iphone/i }));
    await act(async () => {
      await flushPromises();
    });

    view.unmount();

    await act(async () => {
      vi.advanceTimersByTime(2000);
      await flushPromises();
    });

    expect(getMobilePairingStatusMock).not.toHaveBeenCalled();
  });

  it('revokes a device and refreshes list', async () => {
    render(<MobileDevices />);

    fireEvent.click(await screen.findByRole('button', { name: /revoke/i }));

    await waitFor(() => expect(revokeMobileDeviceMock).toHaveBeenCalledWith('device-1'));
    expect(listMobileDevicesMock).toHaveBeenCalledTimes(2);
  });
});
