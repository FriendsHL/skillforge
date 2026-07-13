import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, QRCode, Spin, Typography, message } from 'antd';
import { QrcodeOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  createMobilePairing,
  getMobilePairingStatus,
  type MobilePairingCreateResponse,
} from '../../api/mobile';
import { buildMobilePairingEndpoints } from './mobilePairingEndpoints';

const POLL_INTERVAL_MS = 2000;
const QR_SIZE = 208;
const SERVER_NAME = 'SkillForge Dev';

type PairingPhase = 'idle' | 'creating' | 'waiting' | 'paired' | 'expired' | 'failed';

interface MobilePairingPanelProps {
  onPaired: () => void | Promise<void>;
}

function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const data = (err as { response?: { data?: { error?: unknown; message?: unknown } } }).response?.data;
    if (typeof data?.error === 'string') return data.error;
    if (typeof data?.message === 'string') return data.message;
  }
  return fallback;
}

const MobilePairingPanel: React.FC<MobilePairingPanelProps> = ({ onPaired }) => {
  const [phase, setPhase] = useState<PairingPhase>('idle');
  const [pairing, setPairing] = useState<MobilePairingCreateResponse | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pairingIdRef = useRef<string | null>(null);
  const mountedRef = useRef(true);

  function clearTimer() {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      clearTimer();
    };
  }, []);

  function scheduleNextPoll() {
    clearTimer();
    timerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
  }

  async function poll() {
    const pairingId = pairingIdRef.current;
    if (!pairingId || !mountedRef.current) return;
    try {
      const res = await getMobilePairingStatus(pairingId);
      if (!mountedRef.current || pairingIdRef.current !== pairingId) return;
      if (res.data.status === 'claimed') {
        clearTimer();
        pairingIdRef.current = null;
        setPhase('paired');
        await onPaired();
        return;
      }
      if (res.data.status === 'expired' || res.data.status === 'cancelled') {
        clearTimer();
        pairingIdRef.current = null;
        setPhase('expired');
        return;
      }
      scheduleNextPoll();
    } catch (err) {
      if (!mountedRef.current || pairingIdRef.current !== pairingId) return;
      clearTimer();
      pairingIdRef.current = null;
      setPhase('failed');
      const msg = extractErrorMessage(err, 'Failed to check pairing status');
      setErrorText(msg);
      message.error(msg);
    }
  }

  async function handleCreatePairing() {
    clearTimer();
    pairingIdRef.current = null;
    setErrorText(null);
    setPhase('creating');
    try {
      const res = await createMobilePairing({
        serverName: SERVER_NAME,
        endpoints: buildMobilePairingEndpoints(
          window.location.origin,
          import.meta.env.VITE_SKILLFORGE_MOBILE_ENDPOINTS,
        ),
      });
      if (!mountedRef.current) return;
      pairingIdRef.current = res.data.pairingId;
      setPairing(res.data);
      setPhase('waiting');
      scheduleNextPoll();
    } catch (err) {
      if (!mountedRef.current) return;
      setPhase('failed');
      const msg = extractErrorMessage(err, 'Failed to create mobile pairing');
      setErrorText(msg);
      message.error(msg);
    }
  }

  const qrPayloadText = pairing ? JSON.stringify(pairing.qrPayload) : '';

  return (
    <section className="mobile-section mobile-pairing-panel">
      <div className="mobile-section-header">
        <div>
          <h2 className="mobile-section-title">iPhone Pairing</h2>
          <p className="mobile-section-subtitle">Generate a one-time QR code for the SkillForge iOS companion.</p>
        </div>
        <Button
          type="primary"
          icon={phase === 'idle' ? <QrcodeOutlined /> : <ReloadOutlined />}
          loading={phase === 'creating'}
          onClick={handleCreatePairing}
        >
          {phase === 'idle' ? 'Pair iPhone' : 'New QR Code'}
        </Button>
      </div>

      {errorText && <Alert type="error" showIcon title={errorText} />}

      {phase === 'idle' && (
        <div className="mobile-empty">
          <QrcodeOutlined />
          <span>No active pairing QR</span>
        </div>
      )}

      {phase === 'creating' && (
        <div className="mobile-qr-frame mobile-qr-frame--loading">
          <Spin />
        </div>
      )}

      {phase === 'waiting' && pairing && (
        <div className="mobile-pairing-grid">
          <div className="mobile-qr-frame">
            <QRCode value={qrPayloadText} size={QR_SIZE} />
          </div>
          <div className="mobile-pairing-copy">
            <div className="mobile-pairing-status">
              <Spin size="small" />
              <span>Waiting for scan</span>
            </div>
            <div>
              <p className="mobile-label">Setup code</p>
              <Typography.Text className="mobile-setup-code" copyable>
                {pairing.setupCode}
              </Typography.Text>
            </div>
            <p className="mobile-muted">Expires at {new Date(pairing.expiresAt).toLocaleString()}</p>
            <Typography.Text
              data-testid="mobile-pairing-qr-payload"
              className="mobile-qr-payload"
              copyable={{ text: qrPayloadText, tooltips: ['Copy QR payload', 'Copied'] }}
            >
              {qrPayloadText}
            </Typography.Text>
          </div>
        </div>
      )}

      {phase === 'paired' && (
        <Alert type="success" showIcon title="Paired" description="The iOS device is connected to this SkillForge server." />
      )}

      {phase === 'expired' && (
        <Alert type="warning" showIcon title="QR code expired" description="Create a new QR code and scan again from the iOS app." />
      )}
    </section>
  );
};

export default MobilePairingPanel;
