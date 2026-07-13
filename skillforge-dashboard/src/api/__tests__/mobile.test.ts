import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../index', () => {
  const get = vi.fn();
  const post = vi.fn();
  return {
    default: { get, post },
  };
});

import api from '../index';
import {
  createMobilePairing,
  getMobilePairingStatus,
  listMobileDevices,
  revokeMobileDevice,
} from '../mobile';

const mocked = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
};

describe('mobile API client', () => {
  beforeEach(() => {
    mocked.get.mockReset();
    mocked.post.mockReset();
  });

  it('creates a mobile pairing with serverName and endpoints body', async () => {
    mocked.post.mockResolvedValueOnce({ data: { pairingId: 'p1' } });

    await createMobilePairing({
      serverName: 'SkillForge Dev',
      endpoints: ['http://192.168.1.10:8080'],
    });

    expect(mocked.post).toHaveBeenCalledWith('/mobile/pairings', {
      serverName: 'SkillForge Dev',
      endpoints: ['http://192.168.1.10:8080'],
    });
  });

  it('polls pairing status by id', async () => {
    mocked.get.mockResolvedValueOnce({ data: { status: 'pending' } });

    await getMobilePairingStatus('pairing-1');

    expect(mocked.get).toHaveBeenCalledWith('/mobile/pairings/pairing-1');
  });

  it('lists and revokes mobile devices', async () => {
    mocked.get.mockResolvedValueOnce({ data: [] });
    mocked.post.mockResolvedValueOnce({ data: { id: 'device-1', status: 'revoked' } });

    await listMobileDevices();
    await revokeMobileDevice('device-1');

    expect(mocked.get).toHaveBeenCalledWith('/mobile/devices');
    expect(mocked.post).toHaveBeenCalledWith('/mobile/devices/device-1/revoke');
  });
});
