import React, { useCallback, useEffect, useState } from 'react';
import { Alert, message } from 'antd';
import { listMobileDevices, revokeMobileDevice, type MobileDevice } from '../api/mobile';
import MobileDeviceList from '../components/mobile/MobileDeviceList';
import MobilePairingPanel from '../components/mobile/MobilePairingPanel';
import '../components/mobile/mobile.css';

function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const data = (err as { response?: { data?: { error?: unknown; message?: unknown } } }).response?.data;
    if (typeof data?.error === 'string') return data.error;
    if (typeof data?.message === 'string') return data.message;
  }
  return fallback;
}

const MobileDevices: React.FC = () => {
  const [devices, setDevices] = useState<MobileDevice[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState<string | null>(null);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const refreshDevices = useCallback(async () => {
    const res = await listMobileDevices();
    setDevices(res.data ?? []);
  }, []);

  useEffect(() => {
    let active = true;
    setLoading(true);
    refreshDevices()
      .catch((err) => {
        if (!active) return;
        setErrorText(extractErrorMessage(err, 'Failed to load mobile devices'));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [refreshDevices]);

  const handleRefreshAfterPairing = useCallback(async () => {
    await refreshDevices();
  }, [refreshDevices]);

  const handleRevoke = useCallback(
    async (deviceId: string) => {
      setRevokingId(deviceId);
      setErrorText(null);
      try {
        await revokeMobileDevice(deviceId);
        await refreshDevices();
      } catch (err) {
        const msg = extractErrorMessage(err, 'Failed to revoke mobile device');
        setErrorText(msg);
        message.error(msg);
      } finally {
        setRevokingId(null);
      }
    },
    [refreshDevices],
  );

  return (
    <div className="mobile-devices-view">
      <header className="mobile-page-header">
        <div>
          <h1 className="mobile-page-title">Mobile Devices</h1>
          <p className="mobile-page-subtitle">
            Pair the SkillForge iOS companion and manage trusted mobile clients.
          </p>
        </div>
      </header>

      {errorText && <Alert type="error" showIcon title={errorText} />}

      <MobilePairingPanel onPaired={handleRefreshAfterPairing} />

      <section className="mobile-section">
        <div className="mobile-section-header">
          <div>
            <h2 className="mobile-section-title">Paired Devices</h2>
            <p className="mobile-section-subtitle">Revoking a device immediately invalidates its mobile token.</p>
          </div>
        </div>
        <MobileDeviceList
          devices={devices}
          loading={loading}
          revokingId={revokingId}
          onRevoke={handleRevoke}
        />
      </section>
    </div>
  );
};

export default MobileDevices;
